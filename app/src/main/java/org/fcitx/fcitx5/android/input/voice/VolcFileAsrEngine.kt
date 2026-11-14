/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.fcitx.fcitx5.android.R
import org.json.JSONObject
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 火山引擎文件识别引擎（非流式，移植自 BiBi VolcFileAsrEngine）。
 *
 * - [appendPcm]：采集线程调用，累积原始 PCM（16kHz/16bit/mono），上限 [MAX_PCM_BYTES]。
 * - [stop]：VoiceInputManager 在 IO 协程内调用；构建 WAV → base64 → JSON POST flash → 回调。
 * - 无 partial：[StreamingAsrEngine.Listener.onPartial] 永不被调用。
 * - 所有构造参数由 VoiceInputManager 注入，不读取 Prefs/SharedPreferences。
 *
 * 对齐 U7 修复后的 SF 模式：[UPLOAD_TIMEOUT_MS] 有界、[cancel] 不上传废弃会话音频。
 */
class VolcFileAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    /** 鉴权值（useNewAuth/apiKey/appKey/accessKey），至少一组就绪。 */
    private val auth: VolcAuthValues,
    /** 模型 enum（提供 resourceId）。 */
    private val model: VolcAsrModelEnum,
    /** 去口语化开关。 */
    private val enableDdc: Boolean,
    private val listener: StreamingAsrEngine.Listener
) : StreamingAsrEngine {

    companion object {
        private const val VOLC_ENDPOINT =
            "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash"
        private const val SAMPLE_RATE = 16000
        private const val BITS_PER_SAMPLE = 16
        private const val CHANNELS = 1
        private const val WAV_HEADER_SIZE = 44

        /** 上传调用总超时上限（对齐 SF；防止 STOPPING 无出口）。 */
        private const val UPLOAD_TIMEOUT_MS = 45_000L

        /** PCM 缓冲上限（对齐 SF：16kHz/16bit/mono ≈ 32KB/s，≈10min）。 */
        private const val MAX_PCM_BYTES = 19_200_000

        /**
         * Volc 专属 HTTP client：callTimeout 60s（对齐 BiBi），其余与 SF 一致。
         * 进程级单例，避免每次会话重建连接池。
         */
        private val HTTP_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .followRedirects(false)  // 防带凭证头跨域转发
                .build()
        }
    }

    private val running = AtomicBoolean(false)
    private val pcmBuffer = ArrayList<ByteArray>()
    private val bufferLock = Any()
    private var bufferedBytes = 0
    @Volatile private var durationCapped = false
    @Volatile private var activeCall: Call? = null

    /**
     * cancel() 置位标记：stop() 上传协程在 `activeCall = call` 赋值前的
     * 构建窗口（buildWav/base64/JSON，大段音频可达 ~0.5-1s）被 cancel 时
     * activeCall 仍为 null、无可中断；协程两处检查此标记兜底，
     * 防废弃会话音频外发。
     */
    @Volatile private var cancelRequested = false

    override val isRunning: Boolean get() = running.get()

    /**
     * start() 仅校验鉴权并置 running=true；不建立网络连接。
     * 鉴权失败同步调 [listener].onError（VoiceInputManager.toggle 已前置检查，此为兜底）。
     */
    override fun start() {
        if (running.get()) return
        if (!auth.hasCredentials()) {
            listener.onError(context.getString(R.string.voice_error_volc_unavailable))
            return
        }
        synchronized(bufferLock) {
            pcmBuffer.clear()
            bufferedBytes = 0
        }
        durationCapped = false
        cancelRequested = false
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
            Timber.e(t, "Volc onStopped notify failed")
        }
        scope.launch(Dispatchers.IO) {
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
            // cancel 在本协程起跑前到达：早退省掉 buildWav/base64/JSON
            // 构建（最长 ~1s），更谈不上上传废弃会话音频
            if (cancelRequested) return@launch
            if (durationCapped) {
                durationCapped = false
                Timber.d("Volc pcm capped, uploading ${pcmData.size} bytes")
            }
            if (pcmData.isEmpty()) {
                listener.onFinal("")
                return@launch
            }
            try {
                val wavBytes = buildWav(pcmData)
                val b64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
                val json = buildRequestJson(b64)
                val request = Request.Builder()
                    .url(VOLC_ENDPOINT)
                    .volcHeaders(
                        auth = auth,
                        resourceId = model.resourceId,
                        requestId = UUID.randomUUID().toString()
                    )
                    .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                val call = HTTP_CLIENT.newCall(request)
                activeCall = call
                // cancel 在 newCall~赋值窗口读到 activeCall==null 时无法
                // 中断本 call；此处补检并自行中断（finally 清 activeCall）
                if (cancelRequested) {
                    activeCall = null
                    call.cancel()
                    return@launch
                }
                val respDeferred = async { call.execute() }
                val resp = withTimeoutOrNull(UPLOAD_TIMEOUT_MS) { respDeferred.await() }
                activeCall = null
                if (resp == null) {
                    call.cancel()
                    listener.onError(context.getString(R.string.voice_error_volc_timeout))
                    return@launch
                }
                resp.use { response ->
                    if (!response.isSuccessful) {
                        // 错误详情优先取 X-Api-Message 头（BiBi 行为），回退 HTTP reason
                        val msg = response.header("X-Api-Message")
                            ?: response.message
                        listener.onError(
                            context.getString(
                                R.string.voice_error_volc_http,
                                response.code,
                                msg.take(120)
                            )
                        )
                        return@use
                    }
                    val bodyStr = response.body.string()
                    val text = try {
                        val obj = JSONObject(bodyStr)
                        if (obj.has("result")) {
                            obj.getJSONObject("result").optString("text", "")
                        } else ""
                    } catch (_: Throwable) { "" }
                    listener.onFinal(text)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Timber.e(t, "Volc file ASR failed")
                listener.onError(
                    context.getString(R.string.voice_error_failed) + ": ${t.message.orEmpty().take(120)}"
                )
            } finally {
                activeCall = null
            }
        }
    }

    /**
     * 废弃会话：中断在途上传 + 清缓冲 + 投空 final（对齐 SF cancel 语义）。
     */
    override fun cancel() {
        // 先置位再中断：覆盖 activeCall 赋值前的构建窗口，协程内两处
        // 检查点保证被取消会话的音频不外发
        cancelRequested = true
        activeCall?.cancel()
        if (!running.compareAndSet(true, false)) return
        synchronized(bufferLock) {
            pcmBuffer.clear()
            bufferedBytes = 0
        }
        durationCapped = false
        try { listener.onFinal("") } catch (t: Throwable) {
            Timber.e(t, "Volc cancel final notify failed")
        }
    }

    // ----- 私有工具 -----

    /**
     * 构建火山引擎请求体 JSON：
     * {user:{uid:<appKey>}, audio:{data:<b64wav>,format:"wav",rate:16000,bits:16,channel:1},
     *  request:{model_name:"bigmodel",enable_itn:true,enable_punc:true,enable_ddc:<bool>}}
     *
     * user.uid 用 appKey：新 auth 模式下 BiBi 同样取 appKey（可能为空串，服务端容忍）。
     */
    private fun buildRequestJson(base64Audio: String): String {
        val user = JSONObject().apply { put("uid", auth.appKey) }
        val audio = JSONObject().apply {
            put("data", base64Audio)
            put("format", "wav")
            put("rate", SAMPLE_RATE)
            put("bits", BITS_PER_SAMPLE)
            put("channel", CHANNELS)
        }
        val request = JSONObject().apply {
            put("model_name", "bigmodel")
            put("enable_itn", true)
            put("enable_punc", true)
            put("enable_ddc", enableDdc)
        }
        return JSONObject().apply {
            put("user", user)
            put("audio", audio)
            put("request", request)
        }.toString()
    }

    /** 将 PCM 封装为 WAV（RIFF PCM，44B 头，小端序）。与 SF buildWav 等价。 */
    private fun buildWav(pcm: ByteArray): ByteArray {
        val dataSize = pcm.size
        val totalSize = WAV_HEADER_SIZE + dataSize
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = (CHANNELS * BITS_PER_SAMPLE / 8).toShort()
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(totalSize - 8)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(CHANNELS.toShort())
        buf.putInt(SAMPLE_RATE)
        buf.putInt(byteRate)
        buf.putShort(blockAlign)
        buf.putShort(BITS_PER_SAMPLE.toShort())
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        buf.put(pcm)
        return buf.array()
    }
}
