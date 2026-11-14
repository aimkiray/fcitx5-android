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
        private const val MAX_PCM_BYTES = 19_200_000
    }

    private val running = AtomicBoolean(false)
    private val pcmBuffer = ArrayList<ByteArray>()
    private val bufferLock = Any()
    private var bufferedBytes = 0
    @Volatile private var durationCapped = false
    @Volatile private var decodeJob: Job? = null

    override val isRunning: Boolean get() = running.get()

    override fun start() {
        if (running.get()) return
        // 前置检查：模型文件就绪
        val dir = SenseVoiceModelManager.modelDir(context)
        val modelFile = File(dir, SenseVoiceModelSpec.MODEL_FILE)
        val tokensFile = File(dir, SenseVoiceModelSpec.TOKENS_FILE)
        if (!modelFile.exists() || !tokensFile.exists()) {
            listener.onError(context.getString(R.string.voice_error_sv_model_missing))
            return
        }
        // sha256 校验在 stop() 内 IO 协程做（模型大文件约 2s；start 在主线程，不可阻塞）
        // toggle 前置检查已做过 modelFilesExist，此处仅快速文件存在性检查
        synchronized(bufferLock) {
            pcmBuffer.clear()
            bufferedBytes = 0
        }
        durationCapped = false
        running.set(true)
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
            try {
                val text = SenseVoiceRecognizerManager.decodeOffline(
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
