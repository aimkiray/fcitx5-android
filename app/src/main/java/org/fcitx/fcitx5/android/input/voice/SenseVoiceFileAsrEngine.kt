/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.fcitx.fcitx5.android.R
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地 SenseVoice 文件式 ASR 引擎（零网络）。
 *
 * - [start]：校验模型就绪（文件 sha256 OK + OfflineRecognizer 类可用）→ running=true；
 *   未就绪同步 onError，VoiceInputManager.toggle() 前置检查已先拦截，此为兜底。
 * - [appendPcm]：采集线程缓冲 PCM，上限 [MAX_PCM_BYTES]（对齐 SF）。
 * - [stop]：IO 协程 → pcmToFloatArray → SenseVoiceRecognizerManager.decodeOffline
 *   → onFinal；首次加载 OfflineRecognizer 约 ~1s，主线程无感（IO 协程执行）。
 * - [cancel]：取消解码协程 + 清缓冲 + 投空 final（对齐 SF/Volc cancel 语义）。
 * - 无 partial（v1）。
 *
 * 所有构造参数由 VoiceInputManager 注入，不读取 Prefs。
 */
class SenseVoiceFileAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val listener: StreamingAsrEngine.Listener
) : StreamingAsrEngine {

    companion object {
        private const val SAMPLE_RATE = 16000

        /**
         * PCM 缓冲上限：16kHz/16bit/mono ≈ 32KB/s × 180s = 3min。
         * 与 SF 的 HTTP 上限语义不同：本地 decode 为不可中断 JNI，
         * 10min 整段 → 数分钟解码 + 58MB 瞬时堆，上限收至 3min。
         */
        private const val MAX_PCM_BYTES = 5_760_000

        /**
         * 本地 decode 超时：pcmData.size/32 = 音频毫秒（32KB/s），
         * RTF×2 余量 + 30s 基础，封顶 180s（防等 recognizer mutex 无界悬挂）。
         */
        private const val DECODE_TIMEOUT_BASE_MS = 30_000L
        private const val DECODE_TIMEOUT_MAX_MS = 180_000L
    }

    private val running = AtomicBoolean(false)
    private val pcmBuffer = ArrayList<ByteArray>()
    private val bufferLock = Any()
    private var bufferedBytes = 0
    @Volatile private var durationCapped = false
    @Volatile private var decodeJob: Job? = null

    /**
     * cancel() 置位标记：stop() 协程在 decodeJob 赋值前的 µs 窗口被 cancel
     * 时 decodeJob 仍为 null、无法取消协程；decode 前检查此标记兜底，
     * 保证被取消会话不进入不可中断的 native decode。
     */
    @Volatile private var cancelRequested = false

    override val isRunning: Boolean get() = running.get()

    override fun start() {
        if (running.get()) return
        // 前置检查：与 toggle() 同一口径（文件存在 + 非零大小）；
        // 完整 sha256 校验在 stop() 内 IO 协程做（大文件约 2s，主线程不可阻塞）
        if (!SenseVoiceModelManager.modelFilesExist(context)) {
            listener.onError(context.getString(R.string.voice_error_sv_model_missing))
            return
        }
        synchronized(bufferLock) {
            pcmBuffer.clear()
            bufferedBytes = 0
        }
        durationCapped = false
        cancelRequested = false
        running.set(true)
        // 预热 OfflineRecognizer（首次加载 ~1-2s JNI）：与 stop() 的 decode
        // 经同一 mutex 天然去重；失败静默，decode 时会重试并走正常错误回调
        val dir = SenseVoiceModelManager.modelDir(context)
        SenseVoiceRecognizerManager.warmUp(
            modelPath = File(dir, SenseVoiceModelSpec.MODEL_FILE).absolutePath,
            tokensPath = File(dir, SenseVoiceModelSpec.TOKENS_FILE).absolutePath
        )
    }

    override fun appendPcm(pcm: ByteArray) {
        if (!running.get()) return
        synchronized(bufferLock) {
            if (bufferedBytes + pcm.size > MAX_PCM_BYTES) {
                durationCapped = true
                return
            }
            pcmBuffer.add(pcm)
            bufferedBytes += pcm.size
        }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { listener.onStopped() } catch (t: Throwable) {
            Timber.e(t, "SV onStopped notify failed")
        }
        decodeJob = scope.launch(Dispatchers.IO) {
            val pcmData: ByteArray
            synchronized(bufferLock) {
                val totalBytes = pcmBuffer.sumOf { it.size }
                val buf = ByteArray(totalBytes)
                var offset = 0
                for (chunk in pcmBuffer) {
                    chunk.copyInto(buf, offset)
                    offset += chunk.size
                }
                pcmBuffer.clear()
                bufferedBytes = 0
                pcmData = buf
            }
            // cancel 在本协程起跑前到达（decodeJob 赋值窗口）：被取消会话
            // 早退，省掉 sha256 校验 + decode 整段开销
            if (cancelRequested) return@launch
            if (durationCapped) {
                durationCapped = false
                Timber.d("SV pcm capped, decoding ${pcmData.size} bytes")
            }
            if (pcmData.isEmpty()) {
                listener.onFinal("")
                return@launch
            }
            val dir = SenseVoiceModelManager.modelDir(context)
            val modelPath = File(dir, SenseVoiceModelSpec.MODEL_FILE).absolutePath
            val tokensPath = File(dir, SenseVoiceModelSpec.TOKENS_FILE).absolutePath
            // 再次校验（start 仅做存在性检查；IO 协程内可做完整 sha256 校验）
            val status = SenseVoiceModelManager.checkStatus(context)
            if (status !is SvModelStatus.Ready) {
                val msg = when (status) {
                    is SvModelStatus.IntegrityError ->
                        context.getString(R.string.voice_error_sv_integrity, status.fileName)
                    else ->
                        context.getString(R.string.voice_error_sv_model_missing)
                }
                listener.onError(msg)
                return@launch
            }
            val samples = pcmToFloatArray(pcmData)
            // 防御：非空 pcmData 不足一帧（奇数字节）时 samples 为空，按空 final 收口
            if (samples.isEmpty()) {
                listener.onFinal("")
                return@launch
            }
            // decode 前最后一道取消检查：防被取消会话进入不可中断 JNI
            // （已在跑的 decode 由 decodeOffline 内部 ensureActive 覆盖）
            if (cancelRequested) return@launch
            try {
                // decode 等 recognizer mutex 可能无界排队：上一会话被取消的
                // decode 在 native 层跑完才放锁。withTimeoutOrNull 上限保证
                // 必收口——超时按错误回报，防 STOPPING 无出口悬挂
                val timeoutMs = (pcmData.size / 32L * 2 + DECODE_TIMEOUT_BASE_MS)
                    .coerceAtMost(DECODE_TIMEOUT_MAX_MS)
                val text = withTimeoutOrNull(timeoutMs) {
                    SenseVoiceRecognizerManager.decodeOffline(
                        modelPath = modelPath,
                        tokensPath = tokensPath,
                        samples = samples,
                        sampleRate = SAMPLE_RATE,
                        onLoadStart = {
                            // 首次加载：VoiceInputManager 覆盖层已显示"录音结束/处理中"状态
                            // 无需额外 toast（BiBi 有 toast 是因为其覆盖层不显示处理状态）
                        },
                        onLoadDone = {}
                    )
                }
                if (text == null) {
                    listener.onError(context.getString(R.string.voice_error_sv_timeout))
                    return@launch
                }
                // 解码完成后、投递前确认未取消：被 cancel 的会话不得投真实文本
                coroutineContext.ensureActive()
                listener.onFinal(text.trim())
            } catch (ce: CancellationException) {
                // cancel() 触发：不回调 listener（由 cancel() 侧投空 final）
                throw ce
            } catch (t: Throwable) {
                Timber.e(t, "SenseVoice decode failed")
                listener.onError(
                    context.getString(R.string.voice_error_failed) + ": ${t.message.orEmpty().take(120)}"
                )
            }
        }
    }

    override fun cancel() {
        // 先置位再取消：覆盖 decodeJob 赋值前的窗口（此时协程已在跑但
        // decodeJob 仍为 null），stop() 协程在 decode 前必查此标记
        cancelRequested = true
        decodeJob?.cancel()
        decodeJob = null
        // 对齐 SF：stop() 已跑（running=false）时解码协程自行收口，
        // cancel 不重复投 onFinal("")（否则已投递真实文本后再补一条空 final）
        if (!running.compareAndSet(true, false)) return
        synchronized(bufferLock) {
            pcmBuffer.clear()
            bufferedBytes = 0
        }
        durationCapped = false
        try { listener.onFinal("") } catch (t: Throwable) {
            Timber.e(t, "SV cancel final notify failed")
        }
    }

    // s16le → FloatArray[-1..1]（对齐 BiBi SenseVoiceFileAsrEngine.pcmToFloatArray）
    private fun pcmToFloatArray(pcm: ByteArray): FloatArray {
        if (pcm.isEmpty()) return FloatArray(0)
        val n = pcm.size / 2
        val out = FloatArray(n)
        var i = 0; var offset = 0
        while (i < n) {
            val s = (pcm[offset + 1].toInt() shl 8) or (pcm[offset].toInt() and 0xFF)
            var f = s / 32768.0f
            if (f > 1f) f = 1f else if (f < -1f) f = -1f
            out[i] = f; i++; offset += 2
        }
        return out
    }
}
