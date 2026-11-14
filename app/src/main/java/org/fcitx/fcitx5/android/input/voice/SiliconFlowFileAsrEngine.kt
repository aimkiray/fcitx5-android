/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SiliconFlow 文件转写引擎（非流式）。
 *
 * - [appendPcm]：采集线程调用，累积原始 PCM 帧（16kHz/16bit/mono）。
 * - [stop]：VoiceInputManager 在 IO 协程内调用；构建 WAV，HTTP POST，回调 [listener]。
 * - 无 partial：[StreamingAsrEngine.Listener.onPartial] 永不被调用。
 * - 所有构造参数由 VoiceInputManager 注入，不读取 Prefs/SharedPreferences。
 */
class SiliconFlowFileAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    /** 用户自有 SF key（可为空串）；优先于 BuildConfig 内置 key。 */
    private val userApiKey: String,
    /** 识别模型 id（如 "FunAudioLLM/SenseVoiceSmall"）。 */
    private val model: String,
    private val listener: StreamingAsrEngine.Listener
) : StreamingAsrEngine {

    companion object {
        private const val SF_ENDPOINT = "https://api.siliconflow.cn/v1/audio/transcriptions"
        private const val SAMPLE_RATE = 16000
        private const val BITS_PER_SAMPLE = 16
        private const val CHANNELS = 1
        /** WAV PCM 头大小（固定 44 字节）。 */
        private const val WAV_HEADER_SIZE = 44

        /**
         * 上传调用总超时（连接+发送+等响应），防 stop() 在 HTTP 上无界等待
         * 导致 STOPPING 无出口、hold 模式静默丢整段语音。
         */
        private const val UPLOAD_TIMEOUT_MS = 45_000L

        /**
         * PCM 缓冲上限：16kHz/16bit/mono ≈ 32KB/s，19.2MB ≈ 10min。
         * SF 接口单文件上限 50MB（无界累积约 26min 触顶），且低端机有 OOM 风险。
         */
        private const val MAX_PCM_BYTES = 19_200_000

        private val HTTP_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .callTimeout(180, TimeUnit.SECONDS)
                // 不跟随重定向：防 302 把带 Authorization 头的请求转发到跨域主机泄漏
                .followRedirects(false)
                .build()
        }
    }

    private val running = AtomicBoolean(false)

    /**
     * PCM 帧缓冲区。appendPcm 在采集线程写，stop 在 IO 协程（单一 launch）读后清空。
     * 两者不并发（VoiceInputManager 保证先 capture.stop() 再 engine.stop()），
     * 但仍用 synchronized 防止极端竞态。
     */
    private val pcmBuffer = ArrayList<ByteArray>()
    private val bufferLock = Any()
    /** 已缓冲 PCM 字节数（仅 bufferLock 内读写）。 */
    private var bufferedBytes = 0
    /** 缓冲达到 [MAX_PCM_BYTES] 后置位：appendPcm 丢弃后续帧，stop 仍上传已录部分。 */
    @Volatile private var durationCapped = false
    /** 在途 HTTP 调用；[cancel] 经此中断上传。 */
    @Volatile private var activeCall: Call? = null

    override val isRunning: Boolean get() = running.get()

    /**
     * start() 仅校验鉴权并置 running=true；不建立网络连接（文件引擎无长连接）。
     * 鉴权失败同步调 [listener].onError 并保持 running=false。
     */
    override fun start() {
        if (running.get()) return
        val key = resolveApiKey()
        if (key == null) {
            listener.onError(context.getString(R.string.voice_error_sf_unavailable))
            return
        }
        synchronized(bufferLock) {
            pcmBuffer.clear()
            bufferedBytes = 0
        }
        durationCapped = false
        running.set(true)
    }

    /**
     * 推入一帧 PCM。
     *
     * 契约：传入的 [ByteArray] 须为本帧独占（VoiceInputManager 已用 copyOfRange 切出）；
     * 本方法不再拷贝。running=false 时静默丢弃。
     */
    override fun appendPcm(pcm: ByteArray) {
        if (!running.get()) return
        synchronized(bufferLock) {
            // 缓冲上限 [MAX_PCM_BYTES]（≈10min）：超长静默截断后续帧。
            // 已录部分仍正常上传识别——截断出结果比报错丢失整段更优，
            // 故不回调 onError、不打断录音
            if (bufferedBytes + pcm.size > MAX_PCM_BYTES) {
                durationCapped = true
                return
            }
            pcmBuffer.add(pcm)
            bufferedBytes += pcm.size
        }
    }

    /**
     * 停止录音阶段并触发识别：在 IO 协程中构建 WAV → HTTP POST → 回调。
     *
     * VoiceInputManager 保证在 capture.stop() 之后（IO 协程内）调用本方法，
     * 因此调用时 appendPcm 已不再被调用。
     */
    override fun stop() {
        if (!running.compareAndSet(true, false)) return

        // 通知录音阶段结束（VoiceInputManager 不依赖此回调做状态迁移，
        // 但 DashScope 路径有 onStopped，SF 路径对称提供）
        try { listener.onStopped() } catch (t: Throwable) {
            Timber.e(t, "SF onStopped notify failed")
        }

        scope.launch(Dispatchers.IO) {
            val key = resolveApiKey()
            if (key == null) {
                listener.onError(context.getString(R.string.voice_error_sf_unavailable))
                return@launch
            }

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
                // 时长上限截断：只记长度不落明文，已缓冲部分照常上传
                Timber.d("SF pcm capped, uploading ${pcmData.size} bytes")
            }

            if (pcmData.isEmpty()) {
                // 录音时长过短（零帧）：直接投递空 final，让 VoiceInputManager 走 isBlank 路径
                listener.onFinal("")
                return@launch
            }

            var tmp: File? = null
            try {
                val wavBytes = buildWav(pcmData)
                tmp = File.createTempFile("asr_sf_", ".wav", context.cacheDir)
                FileOutputStream(tmp).use { it.write(wavBytes) }

                val resolvedModel = model.ifBlank { DEFAULT_SF_FREE_MODEL }

                val multipart = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("model", resolvedModel)
                    .addFormDataPart(
                        "file",
                        "audio.wav",
                        tmp.asRequestBody("audio/wav".toMediaType())
                    )
                    .build()

                val request = Request.Builder()
                    .url(SF_ENDPOINT)
                    .addHeader("Authorization", "Bearer $key")
                    .post(multipart)
                    .build()

                val call = HTTP_CLIENT.newCall(request)
                activeCall = call
                // execute() 是阻塞调用，协程取消无法直接中断它；
                // 用独立 Deferred 承载并挂起 await，withTimeoutOrNull 的
                // [UPLOAD_TIMEOUT_MS] 上限才真正生效。超时后 call.cancel()
                // 断开底层 socket（execute 在 async 内抛 IOException，
                // 由不再被 await 的 Deferred 吞掉），按失败路径收口——
                // 对齐 DashScope 有界收口语义
                val respDeferred = async { call.execute() }
                val resp = withTimeoutOrNull(UPLOAD_TIMEOUT_MS) { respDeferred.await() }
                activeCall = null
                if (resp == null) {
                    call.cancel()
                    listener.onError(
                        context.getString(R.string.voice_error_sf_timeout)
                    )
                    return@launch
                }
                resp.use { response ->
                    val bodyStr = response.body.string()
                    if (!response.isSuccessful) {
                        // SF 错误体形如 {"code":..,"message":".."}：
                        // 提取 message 拼进提示，仅有 HTTP reason 难以定位问题
                        val detail = try {
                            JSONObject(bodyStr).optString("message", "")
                        } catch (_: Throwable) { "" }
                        listener.onError(
                            context.getString(
                                R.string.voice_error_sf_http,
                                response.code,
                                detail.ifBlank { response.message }.take(120)
                            )
                        )
                        return@use
                    }
                    val text = try {
                        JSONObject(bodyStr).optString("text", "")
                    } catch (_: Throwable) { "" }
                    // text 为空串时 VoiceInputManager.onFinal 走 isBlank → clearVoiceComposingText
                    listener.onFinal(text)
                }
            } catch (ce: CancellationException) {
                // 协程取消不是错误：向上抛出保持结构化取消语义
                throw ce
            } catch (t: Throwable) {
                Timber.e(t, "SF file ASR failed")
                listener.onError(
                    context.getString(R.string.voice_error_failed) + ": ${t.message.orEmpty().take(120)}"
                )
            } finally {
                activeCall = null
                tmp?.let { f ->
                    if (!f.delete()) f.deleteOnExit()
                }
            }
        }
    }

    /**
     * 废弃会话：仅本地清理，不触发上传、不投递错误。
     *
     * 若走 stop()，整段音频会被 POST 到第三方服务器后结果又被丢弃
     * （白调 API + 已废弃会话的音频外发，与用户预期不符）。
     * manager 侧两条调用路径的收口：capture.onError 已自行置 IDLE
     * （且 sessionGen 已递增，此处的 onFinal 会被 gen 守卫丢弃）；
     * discardFinal 的 stopSession 依赖引擎回调收口 STOPPING，
     * 故仍投递空 final（blank → clearVoiceComposingText 分支）。
     */
    override fun cancel() {
        // 先中断可能在途的上传（stop() 已 CAS 置 false 时此分支仍可达）
        activeCall?.cancel()
        if (!running.compareAndSet(true, false)) return
        synchronized(bufferLock) {
            pcmBuffer.clear()
            bufferedBytes = 0
        }
        durationCapped = false
        try { listener.onFinal("") } catch (t: Throwable) {
            Timber.e(t, "SF cancel final notify failed")
        }
    }

    // ----- 私有工具 -----

    /**
     * 解析生效的 API key。
     * 返回 null 表示两轨均为空，调用方须调 onError。
     * 注意：key 值不写入日志。
     */
    private fun resolveApiKey(): String? {
        if (userApiKey.isNotBlank()) return userApiKey
        val builtin = BuildConfig.SF_FREE_API_KEY
        if (builtin.isNotBlank()) return builtin
        return null
    }

    /**
     * 将原始 PCM（16kHz/16bit/mono）封装为标准 WAV（RIFF PCM）。
     * 头固定 44 字节，小端序。
     */
    private fun buildWav(pcm: ByteArray): ByteArray {
        val dataSize = pcm.size
        val totalSize = WAV_HEADER_SIZE + dataSize
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = (CHANNELS * BITS_PER_SAMPLE / 8).toShort()

        // RIFF chunk
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(totalSize - 8)          // ChunkSize
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        // fmt sub-chunk
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)                     // Subchunk1Size（PCM）
        buf.putShort(1)                    // AudioFormat = PCM
        buf.putShort(CHANNELS.toShort())
        buf.putInt(SAMPLE_RATE)
        buf.putInt(byteRate)
        buf.putShort(blockAlign)
        buf.putShort(BITS_PER_SAMPLE.toShort())
        // data sub-chunk
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        buf.put(pcm)

        return buf.array()
    }
}

/** v1 默认免费模型（与 BiBi DEFAULT_SF_FREE_ASR_MODEL 保持一致）。 */
internal const val DEFAULT_SF_FREE_MODEL = "FunAudioLLM/SenseVoiceSmall"
