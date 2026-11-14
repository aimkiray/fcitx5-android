/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.util.Base64
import com.alibaba.dashscope.audio.asr.recognition.Recognition
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult
import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam
import com.alibaba.dashscope.audio.omni.OmniRealtimeTranscriptionParam
import com.alibaba.dashscope.common.ResultCallback
import com.alibaba.dashscope.utils.Constants
import com.google.gson.JsonObject
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.fcitx.fcitx5.android.R
import timber.log.Timber

/**
 * DashScope 实时流式 ASR 引擎（SDK），移植裁剪自 BiBi-Keyboard。
 *
 * - Fun-ASR / Qwen-Audio 3.x 走 Recognition + inference WebSocket（支持连接复用）。
 * - Qwen3-ASR-Flash-Realtime 走 OmniRealtimeConversation + realtime WebSocket（不支持复用）。
 * - 仅 external PCM 模式：音频采集由 [VoiceInputAudioCapture] 负责，经 [appendPcm] 推流。
 * - 所有配置经构造参数注入，不读取 Prefs/SharedPreferences。
 */
class DashscopeStreamAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val apiKey: String,
    private val model: String,
    private val languages: List<String>,
    private val keepDialect: Boolean,
    private val disfluencyRemoval: Boolean,
    private val prompt: String,
    private val wsRealtimeUrl: String,
    private val wsInferenceUrl: String,
    private val listener: StreamingAsrEngine.Listener
) : StreamingAsrEngine {

    companion object {
        private const val FINAL_RESULT_TIMEOUT_MS = 6000L
        private const val MAX_ERROR_MESSAGE_LENGTH = 200
    }

    private val running = AtomicBoolean(false)

    @Volatile
    private var controlJob: Job? = null

    private val sampleRate = 16000

    @Volatile
    private var conversation: OmniRealtimeConversation? = null
    @Volatile
    private var recognizer: Recognition? = null
    @Volatile
    private var useRecognitionProtocol: Boolean = false
    @Volatile
    private var selectedModel: String = DEFAULT_DASH_MODEL
    @Volatile
    private var recognitionReuseKey: DashscopeRecognitionReuseKey? = null

    /**
     * safeClose 内 recognizer/recognitionReuseKey 读-置空与连接交接
     * （invalidate/returnSuccess）的原子锁，防止并发 safeClose 重复交接。
     */
    private val closeLock = Any()

    /** 仅在 Recognition onComplete 后为 true，允许把连接归还到复用缓存。 */
    private val recognitionTaskSucceeded = AtomicBoolean(false)

    // 用于识别结果
    // currentTurnText: 当前已确定的文本（来自 text 事件的 text 字段，用于实时预览）
    // currentTurnStash: 当前未确定的中间文本（来自 text 事件的 stash 字段，用于实时预览）
    // finalTranscript: 用户停止后，由 stop() 触发的最终完整识别结果
    @Volatile
    private var currentTurnText: String = ""
    @Volatile
    private var currentTurnStash: String = ""
    @Volatile
    private var finalTranscript: String? = null
    @Volatile
    private var finalResultDeferred: CompletableDeferred<String?>? = null
    private val finalDelivered = AtomicBoolean(false)

    override val isRunning: Boolean
        get() = running.get()

    private val prebuffer = java.util.ArrayDeque<ByteArray>()
    private val prebufferLock = Any()

    @Volatile private var recognizerReady: Boolean = false

    override fun start() {
        if (running.get()) return
        selectedModel = normalizeDashAsrModel(model)
        useRecognitionProtocol = isRecognitionStreamingModel(selectedModel)
        val isOmniRealtime = isQwen3RealtimeModel(selectedModel)
        if (!useRecognitionProtocol && !isOmniRealtime) {
            listener.onError(
                "Model $selectedModel does not support streaming; " +
                    "choose a realtime or streaming model"
            )
            return
        }
        if (apiKey.isBlank()) {
            listener.onError(context.getString(R.string.voice_error_no_api_key))
            return
        }

        running.set(true)
        currentTurnText = ""
        currentTurnStash = ""
        finalTranscript = null
        finalResultDeferred = null
        finalDelivered.set(false)
        recognitionTaskSucceeded.set(false)
        recognitionReuseKey = null

        // 在 IO 线程启动 SDK 识别（采集由外部 appendPcm 推流）
        controlJob?.cancel()
        controlJob = scope.launch(Dispatchers.IO) {
            try {
                if (useRecognitionProtocol) {
                    startRecognitionStreaming(selectedModel)
                } else {
                    startQwen3RealtimeStreaming(selectedModel)
                }
            } catch (t: Throwable) {
                Timber.e(t, "Failed to start DashScope streaming recognition")
                if (finalDelivered.compareAndSet(false, true)) {
                    try {
                        listener.onError(
                            context.getString(R.string.voice_error_failed) +
                                ": ${t.message ?: ""}"
                        )
                    } catch (notifyError: Throwable) {
                        Timber.e(notifyError, "notify error failed")
                    }
                }
                running.set(false)
                safeClose(disposeRecognition = true, reason = "start_failed")
            }
        }
    }

    private fun startQwen3RealtimeStreaming(model: String) {
        val wsUrl = wsRealtimeUrl

        val param = OmniRealtimeParam.builder()
            .model(model)
            .apikey(apiKey)
            .url(wsUrl)
            .build()

        val transcriptionParam = OmniRealtimeTranscriptionParam()
        transcriptionParam.setInputSampleRate(sampleRate)
        transcriptionParam.setInputAudioFormat("pcm")
        languages.firstOrNull()?.let(transcriptionParam::setLanguage)
        val corpus = prompt.trim()
        if (corpus.isNotEmpty()) {
            transcriptionParam.setCorpusText(corpus)
        }

        val config = OmniRealtimeConfig.builder()
            .modalities(listOf(OmniRealtimeModality.TEXT))
            .enableTurnDetection(false)
            .transcriptionConfig(transcriptionParam)
            .build()

        val callback = object : OmniRealtimeCallback() {
            override fun onOpen() {
                Timber.d("WebSocket opened, updating session config")
                try {
                    conversation?.updateSession(config)
                    // recognizerReady 待 session.updated 事件到达后再置位
                    // （见 handleQwen3RealtimeEvent），防配置未生效时推流丢帧
                } catch (t: Throwable) {
                    Timber.e(t, "updateSession failed")
                }
            }

            override fun onEvent(message: JsonObject) {
                handleQwen3RealtimeEvent(message)
            }

            override fun onClose(code: Int, reason: String) {
                Timber.d("WebSocket closed: $code $reason")
                if (running.get()) {
                    running.set(false)
                    if (finalDelivered.compareAndSet(false, true)) {
                        try {
                            listener.onError(
                                context.getString(R.string.voice_error_failed) + ": $reason"
                            )
                        } catch (t: Throwable) {
                            Timber.e(t, "notify error failed")
                        }
                    }
                }
            }
        }

        val conv = OmniRealtimeConversation(param, callback)
        conversation = conv
        recognizer = null
        recognizerReady = false
        // stop() 先于 connect() 到达：不发起连接
        if (!running.get()) {
            safeClose(disposeRecognition = true, reason = "stopped_before_connect")
            return
        }
        conv.connect()
        // stop() 可能在 connect 期间已把 running 置 false：连接立即关闭，避免孤儿 WS
        if (!running.get()) {
            safeClose(disposeRecognition = true, reason = "stopped_during_start")
            return
        }
    }

    private fun handleQwen3RealtimeEvent(message: JsonObject) {
        val eventType = message.get("type")?.asString ?: return
        when (eventType) {
            "session.updated" -> {
                // 会话配置生效后才置 ready 并冲刷预缓冲，
                // onOpen 即置位会让早期音频在配置应用前发出
                recognizerReady = true
                flushPrebuffer()
            }
            "conversation.item.input_audio_transcription.text" -> {
                val text = message.get("text")?.asString ?: ""
                val stash = message.get("stash")?.asString ?: ""
                if (!running.get()) return
                currentTurnText = text
                currentTurnStash = stash
                val preview = currentTurnText + currentTurnStash
                if (preview.isNotEmpty()) {
                    try {
                        listener.onPartial(preview)
                    } catch (t: Throwable) {
                        Timber.e(t, "notify partial failed")
                    }
                }
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val transcript = message.get("transcript")?.asString ?: ""
                // 识别文本属隐私数据，只记录长度不落明文
                Timber.d("Transcription completed: len=${transcript.length}")
                finalTranscript = transcript
                finalResultDeferred?.complete(transcript)
                if (finalDelivered.compareAndSet(false, true)) {
                    try {
                        listener.onFinal(transcript)
                    } catch (t: Throwable) {
                        Timber.e(t, "notify final failed")
                    }
                }
                // 服务端自发 completed（非 stop() 触发，finalResultDeferred 为 null）时
                // stop() 的收尾路径不会执行，需在此关闭 WS
                if (finalResultDeferred == null) {
                    running.set(false)
                    safeClose(disposeRecognition = true, reason = "realtime_spontaneous_complete")
                }
            }
            "conversation.item.input_audio_transcription.failed" -> {
                val errorMsg = (message.getAsJsonObject("error")?.get("message")?.asString
                    ?: "Transcription failed").take(MAX_ERROR_MESSAGE_LENGTH)
                Timber.e("Transcription failed: $errorMsg")
                running.set(false)
                if (finalDelivered.compareAndSet(false, true)) {
                    try {
                        listener.onError(
                            context.getString(R.string.voice_error_failed) + ": $errorMsg"
                        )
                    } catch (t: Throwable) {
                        Timber.e(t, "notify error failed")
                    }
                }
                finalResultDeferred?.complete(null)
                safeClose(disposeRecognition = true, reason = "realtime_transcript_failed")
            }
            "error" -> {
                val errorMsg = (message.getAsJsonObject("error")?.get("message")?.asString
                    ?: "Unknown error").take(MAX_ERROR_MESSAGE_LENGTH)
                Timber.e("Server error: $errorMsg")
                if (running.get()) {
                    running.set(false)
                    if (finalDelivered.compareAndSet(false, true)) {
                        try {
                            listener.onError(
                                context.getString(R.string.voice_error_failed) + ": $errorMsg"
                            )
                        } catch (t: Throwable) {
                            Timber.e(t, "notify error failed")
                        }
                    }
                }
                finalResultDeferred?.complete(null)
                safeClose(disposeRecognition = true, reason = "realtime_server_error")
            }
            else -> Timber.d("Realtime event: $eventType")
        }
    }

    private fun startRecognitionStreaming(model: String) {
        // Fun-ASR 与 Qwen-Audio 3.x 使用 Recognition SDK 和 inference endpoint。
        val wsUrl = wsInferenceUrl
        try {
            Constants.baseWebsocketApiUrl = wsUrl
        } catch (t: Throwable) {
            Timber.w(t, "Failed to set baseWebsocketApiUrl")
        }

        val param = buildDashRecognitionParam(
            model = model,
            apiKey = apiKey,
            sampleRate = sampleRate,
            languages = languages,
            keepDialect = keepDialect,
            disfluencyRemoval = disfluencyRemoval
        )
        val reuseKey = DashscopeRecognitionReuseKey(
            wsUrl = wsUrl,
            apiKey = apiKey,
            model = model,
            sampleRate = sampleRate
        )
        recognitionReuseKey = reuseKey
        recognitionTaskSucceeded.set(false)
        recognizer?.let { leftover ->
            DashscopeRecognitionConnectionCache.invalidate(
                null,
                leftover,
                "replaced_before_borrow"
            )
        }
        val borrowed = DashscopeRecognitionConnectionCache.borrow(reuseKey)
        val rec = borrowed.recognition
        recognizer = rec
        conversation = null
        recognizerReady = false
        Timber.d(
            (if (borrowed.reused) "dash_ws_session_reuse" else "dash_ws_session_new") +
                " model=$model reused=${borrowed.reused}"
        )
        val callback = object : ResultCallback<RecognitionResult>() {
            override fun onEvent(result: RecognitionResult) {
                handleRecognitionEvent(result)
            }

            override fun onComplete() {
                handleRecognitionComplete()
            }

            override fun onError(e: Exception) {
                handleRecognitionError(e)
            }
        }

        // stop() 在 borrow 后 call 前到达：不发起新会话，借来的连接直接收尾
        if (!running.get()) {
            safeClose(disposeRecognition = true, reason = "stopped_before_call")
            return
        }
        try {
            rec.call(param, callback)
        } catch (t: Throwable) {
            // 借出的脏连接：废弃后新建一次（不递归）
            if (borrowed.reused) {
                Timber.w(t, "reuse call failed, recreating Recognition")
                Timber.d(
                    "dash_ws_reuse_fail_reconnect model=$model " +
                        "error=${t.message ?: t.javaClass.simpleName}"
                )
                DashscopeRecognitionConnectionCache.invalidate(reuseKey, rec, "reuse_call_failed")
                // stop() 在 call 期间被调用：不要再新建连接
                if (!running.get()) {
                    safeClose(disposeRecognition = true, reason = "stopped_during_start")
                    return
                }
                val fresh = Recognition()
                recognizer = fresh
                recognitionTaskSucceeded.set(false)
                fresh.call(param, callback)
            } else {
                throw t
            }
        }

        // stop() 在建连期间被调用：连接已成孤儿，立即关闭
        if (!running.get()) {
            safeClose(disposeRecognition = true, reason = "stopped_during_start")
            return
        }
        recognizerReady = true
        flushPrebuffer()
    }

    private fun handleRecognitionEvent(result: RecognitionResult) {
        val sentenceText = result.getSentence()?.getText().orEmpty()
        if (sentenceText.isBlank()) return

        val isEnd = result.isSentenceEnd
        if (isEnd) {
            currentTurnText = appendSentence(currentTurnText, sentenceText)
            currentTurnStash = ""
        } else {
            currentTurnStash = sentenceText
        }

        if (!running.get()) return
        val preview = (currentTurnText + currentTurnStash).trim()
        if (preview.isNotEmpty()) {
            try {
                listener.onPartial(preview)
            } catch (t: Throwable) {
                Timber.e(t, "notify partial failed")
            }
        }
    }

    private fun handleRecognitionComplete() {
        recognitionTaskSucceeded.set(true)
        val finalText = (currentTurnText + currentTurnStash).trim()
        finalTranscript = finalText
        finalResultDeferred?.complete(finalText)

        if (finalDelivered.compareAndSet(false, true)) {
            running.set(false)
            try {
                listener.onFinal(finalText)
            } catch (t: Throwable) {
                Timber.e(t, "notify final failed")
            }
        }

        // 自发完成（finalResultDeferred 为 null/已结束，即非 stop() 触发）时，
        // stop() 的 finally 兜底路径不会执行，需在此归还连接到复用缓存
        // （recognitionTaskSucceeded=true → returnSuccess）
        if (finalResultDeferred?.isCompleted != true) {
            safeClose(disposeRecognition = false, reason = "spontaneous_complete")
        }
    }

    private fun handleRecognitionError(e: Exception) {
        val msg = e.message ?: "Recognition error"
        Timber.e(e, "DashScope Recognition streaming error: $msg")
        recognitionTaskSucceeded.set(false)
        if (running.get()) {
            running.set(false)
            if (finalDelivered.compareAndSet(false, true)) {
                try {
                    listener.onError(
                        context.getString(R.string.voice_error_failed) + ": $msg"
                    )
                } catch (t: Throwable) {
                    Timber.e(t, "notify error failed")
                }
            }
        }
        finalResultDeferred?.complete(null)
        safeClose(disposeRecognition = true, reason = "recognition_error")
    }

    private fun appendSentence(existing: String, sentence: String): String {
        val s = sentence.trim()
        if (s.isEmpty()) return existing
        val cur = existing.trim()
        if (cur.isEmpty()) return s
        val last = cur.last()
        val first = s.first()
        val needsSpace = last.isAsciiLetterOrDigit() && first.isAsciiLetterOrDigit()
        return if (needsSpace) "$cur $s" else cur + s
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        (this in 'a'..'z') || (this in 'A'..'Z') || (this in '0'..'9')

    /**
     * 冲刷预缓冲区
     */
    private fun flushPrebuffer() {
        var flushed: Array<ByteArray>? = null
        synchronized(prebufferLock) {
            if (prebuffer.isNotEmpty()) {
                flushed = prebuffer.toTypedArray()
                prebuffer.clear()
            }
        }
        flushed?.forEach { b ->
            sendAudioFrame(b)
        }
    }

    /** 发送 PCM 音频帧。 */
    private fun sendAudioFrame(audioChunk: ByteArray) {
        if (useRecognitionProtocol) {
            try {
                recognizer?.sendAudioFrame(ByteBuffer.wrap(audioChunk))
            } catch (t: Throwable) {
                Timber.e(t, "sendAudioFrame failed")
            }
            return
        }
        try {
            val base64Audio = Base64.encodeToString(audioChunk, Base64.NO_WRAP)
            conversation?.appendAudio(base64Audio)
        } catch (t: Throwable) {
            Timber.e(t, "appendAudio failed")
        }
    }

    // ========== 外部推流（16kHz/16bit/mono PCM，由 VoiceInputAudioCapture 提供） ==========
    /**
     * 推入一帧 PCM。
     *
     * 契约：传入的 ByteArray 必须为本帧独占——prebuffer 会持有引用直至
     * 冲刷发送，调用方不得在传入后继续复写该数组（VoiceInputManager 已用
     * copyOfRange 从 AudioRecord 复用 buf 切出专属帧，故此处不再二次拷贝）。
     */
    override fun appendPcm(pcm: ByteArray) {
        if (!running.get()) return
        // 不调用 VAD/leveler；振幅由 VoiceInputAudioCapture.onLevel 经 VoiceInputManager 分发
        if (!recognizerReady) {
            synchronized(prebufferLock) {
                prebuffer.addLast(pcm)
                // 上限 20 帧（100ms/帧 ≈ 2s），超出丢弃最旧帧，避免建连慢时无限堆积
                while (prebuffer.size > 20) prebuffer.removeFirst()
            }
        } else {
            flushPrebuffer()
            sendAudioFrame(pcm)
        }
    }

    override fun stop() {
        if (!running.get()) return
        running.set(false)

        // deferred 必须在 launch 之前同步赋值：否则自发 completed 在
        // running.set(false) 与协程体执行之间的窗口到达时，
        // finalResultDeferred 仍为 null，会被误判为自发完成，
        // 连接被 invalidate 而非复用
        val resultDeferred = CompletableDeferred<String?>()
        finalResultDeferred = resultDeferred

        // 采集已由外部停止（VoiceInputManager 先停 capture，再调本方法触发最终识别）
        scope.launch(Dispatchers.IO) {
            try {
                // 通知 UI：录音阶段结束，可复位麦克风按钮
                try {
                    listener.onStopped()
                } catch (t: Throwable) {
                    Timber.e(t, "notify stopped failed")
                }

                if (recognizer == null && conversation == null && !recognizerReady) {
                    // 引擎未就绪（建连期间即被 stop）：没有可停的实例，
                    // 也不会有 final 返回，跳过 6s await 直接走兜底投递
                    deliverStopFallback(resultDeferred, "engine_not_ready")
                } else {
                    // recognizer.stop()/conversation.commit() 是可能长时间阻塞的
                    // 同步调用：放入独立 job，与 resultDeferred.await 赛跑
                    val triggerName =
                        if (useRecognitionProtocol) "recognizer.stop" else "conversation.commit"
                    val stopTrigger = launch {
                        runCatching {
                            Timber.d("Calling $triggerName to trigger final recognition")
                            if (useRecognitionProtocol) {
                                recognizer?.stop()
                            } else {
                                conversation?.commit()
                            }
                        }.onFailure { t ->
                            Timber.w(t, "$triggerName failed")
                            deliverStopFallback(
                                resultDeferred, "$triggerName failed: ${t.message.orEmpty()}"
                            )
                        }
                    }

                    // 等待 completed 事件返回或超时；阻塞调用中断不了就放弃等待，
                    // 保证 finally/safeClose 一定走到、state 不卡 STOPPING
                    val awaited =
                        withTimeoutOrNull(FINAL_RESULT_TIMEOUT_MS) { resultDeferred.await() }
                    stopTrigger.cancel()
                    if (awaited == null && finalDelivered.compareAndSet(false, true)) {
                        // 超时未收到 onComplete：连接状态未知，不可归还复用
                        recognitionTaskSucceeded.set(false)
                        // 超时后使用当前文本作为兜底结果
                        val fallbackText =
                            (finalTranscript ?: (currentTurnText + currentTurnStash)).trim()
                        try {
                            listener.onFinal(fallbackText)
                        } catch (notifyError: Throwable) {
                            Timber.e(notifyError, "notify final timeout fallback failed")
                        }
                    }
                }
            } catch (t: Throwable) {
                Timber.w(t, "stop cleanup failed")
            } finally {
                if (!resultDeferred.isCompleted) {
                    resultDeferred.complete(finalTranscript)
                }
                finalResultDeferred = null
                val canReuse = useRecognitionProtocol && recognitionTaskSucceeded.get()
                safeClose(
                    disposeRecognition = !canReuse,
                    reason = if (canReuse) "task_finished" else "stop_no_complete"
                )
            }
        }
    }

    private fun deliverStopFallback(
        resultDeferred: CompletableDeferred<String?>,
        error: String
    ) {
        Timber.d("deliver stop fallback: $error")
        recognitionTaskSucceeded.set(false)
        val fallbackText = (currentTurnText + currentTurnStash).trim()
        if (finalDelivered.compareAndSet(false, true)) {
            try {
                listener.onFinal(fallbackText)
            } catch (notifyError: Throwable) {
                Timber.e(notifyError, "notify final fallback failed")
            }
        }
        if (!resultDeferred.isCompleted) {
            resultDeferred.complete(fallbackText)
        }
    }

    /**
     * @param disposeRecognition true 时关闭并废弃 Recognition（错误/超时/取消）；
     *   false 且任务已 onComplete 时归还到进程内缓存以复用 WebSocket。
     */
    private fun safeClose(disposeRecognition: Boolean, reason: String) {
        recognizerReady = false
        try {
            conversation?.close()
        } catch (t: Throwable) {
            Timber.w(t, "conversation close failed")
        } finally {
            conversation = null
        }

        // recognizer 读-置空与连接交接（invalidate/returnSuccess）必须原子，
        // 否则并发 safeClose 可能重复交接同一连接、或丢失新赋的实例
        synchronized(closeLock) {
            val rec = recognizer
            val key = recognitionReuseKey
            recognizer = null
            recognitionReuseKey = null
            if (rec == null) return

            if (!useRecognitionProtocol) {
                // realtime 路径不应持有 Recognition；防御性关闭
                try {
                    DashscopeRecognitionTransport.close(rec, reason)
                } catch (t: Throwable) {
                    Timber.w(t, "unexpected recognition close failed")
                }
                return
            }

            if (disposeRecognition || !recognitionTaskSucceeded.get()) {
                DashscopeRecognitionConnectionCache.invalidate(key, rec, reason)
            } else if (key != null) {
                DashscopeRecognitionConnectionCache.returnSuccess(key, rec)
            } else {
                DashscopeRecognitionConnectionCache.invalidate(null, rec, "missing_reuse_key")
            }
        }
    }
}

internal fun buildDashRecognitionParam(
    model: String,
    apiKey: String,
    sampleRate: Int,
    languages: List<String>,
    keepDialect: Boolean = false,
    disfluencyRemoval: Boolean = true
): RecognitionParam {
    val builder = RecognitionParam.builder()
        .model(model)
        .apiKey(apiKey)
        .format("pcm")
        .sampleRate(sampleRate)
    val normalized = normalizeDashAsrModel(model)
    // message 不支持 language_hints；其余 Recognition 流式按模型能力传。
    if (!isQwenAudio31MessageModel(normalized)) {
        val normalizedLanguages = parseDashLanguages(languages.joinToString(","))
        val languageHints = if (isQwenAudioModel(normalized)) {
            normalizedLanguages
        } else {
            normalizedLanguages.take(1)
        }
        if (languageHints.isNotEmpty()) {
            builder.parameter("language_hints", languageHints.toTypedArray())
        }
    }
    // Fun-ASR / Qwen-Audio Recognition 流式（含 message）均支持；固定开启语义断句与保活
    if (isSemanticPunctuationSupported(normalized)) {
        builder.parameter("semantic_punctuation_enabled", true)
        builder.parameter("heartbeat", true)
    }
    // Qwen-Audio 3.1 streaming / message：方言开关。
    // 语义断句开启时服务端忽略 VAD 参数，故不传 vad_model。
    val isQwenAudio31Recognition =
        normalized.equals(DASH_MODEL_QWEN_AUDIO_31_STREAMING, ignoreCase = true) ||
            isQwenAudio31MessageModel(normalized)
    if (isQwenAudio31Recognition) {
        builder.parameter("keep_dialect", keepDialect)
    }
    // message：中间结果默认关，必须显式开启；自动润色跟用户开关
    if (isQwenAudio31MessageModel(normalized)) {
        builder.parameter("intermediate_result_enabled", true)
        builder.parameter("disfluency_removal_enabled", disfluencyRemoval)
    }
    return builder.build()
}

// ========================================================================
// 模型 ID 常量与判定函数（移植自 BiBi Prefs.kt / DashScopePrefsCompat.kt）
// ========================================================================

// 模型 ID 常量（来自 BiBi Prefs.kt L2477-2489；CTO 修订：BiBi 的
// DASH_MODEL_QWEN_AUDIO_REALTIME / _31_REALTIME 实际值为 "-streaming" 后缀）
internal const val DASH_MODEL_FUN_ASR_REALTIME = "fun-asr-realtime"
internal const val DASH_MODEL_FUN_ASR_FLASH = "fun-asr-flash-2026-06-15"
internal const val DASH_MODEL_QWEN_AUDIO_FLASH = "qwen-audio-3.0-asr-flash"
internal const val DASH_MODEL_QWEN_AUDIO_STREAMING =
    "qwen-audio-3.0-asr-flash-streaming" // =BiBi DASH_MODEL_QWEN_AUDIO_REALTIME
internal const val DASH_MODEL_QWEN_AUDIO_31_FLASH = "qwen-audio-3.1-asr-flash"
internal const val DASH_MODEL_QWEN_AUDIO_31_STREAMING =
    "qwen-audio-3.1-asr-flash-streaming" // =BiBi DASH_MODEL_QWEN_AUDIO_31_REALTIME
internal const val DASH_MODEL_QWEN_AUDIO_31_MESSAGE = "qwen-audio-3.1-asr-flash-message"
internal const val DASH_MODEL_QWEN3_REALTIME = "qwen3-asr-flash-realtime" // OmniRealtime
internal const val DASH_MODEL_QWEN3_FLASH = "qwen3-asr-flash"

/** CTO 修订：默认必须是流式模型（BiBi 的 DEFAULT_DASH_MODEL 是非流式 flash）。 */
internal const val DEFAULT_DASH_MODEL = DASH_MODEL_QWEN_AUDIO_STREAMING

private const val DASH_LEGACY_QWEN3_REALTIME_VERSIONED_MODEL =
    "qwen3-asr-flash-realtime-2026-02-10"
private const val MAX_QWEN_AUDIO_LANGUAGE_HINTS = 4

internal fun normalizeDashAsrModel(model: String): String {
    val trimmed = model.trim()
    return when {
        trimmed.isBlank() -> DEFAULT_DASH_MODEL
        trimmed.equals(DASH_LEGACY_QWEN3_REALTIME_VERSIONED_MODEL, ignoreCase = true) ->
            DASH_MODEL_QWEN3_REALTIME
        else -> trimmed
    }
}

/** Recognition WebSocket 流式模型集合（按 BiBi isRecognitionStreamingModel 真实实现）。 */
internal fun isRecognitionStreamingModel(model: String): Boolean =
    normalizeDashAsrModel(model).let {
        it.equals(DASH_MODEL_FUN_ASR_REALTIME, ignoreCase = true) ||
            it.equals(DASH_MODEL_QWEN_AUDIO_31_MESSAGE, ignoreCase = true) ||
            it.equals(DASH_MODEL_QWEN_AUDIO_31_STREAMING, ignoreCase = true) ||
            it.equals(DASH_MODEL_QWEN_AUDIO_STREAMING, ignoreCase = true)
    }

/** OmniRealtime WebSocket 模型（当前仅 qwen3-asr-flash-realtime）。 */
internal fun isQwen3RealtimeModel(model: String): Boolean =
    normalizeDashAsrModel(model).equals(DASH_MODEL_QWEN3_REALTIME, ignoreCase = true)

/** 支持 language_hints 的 Qwen-Audio 模型（不含 message：文档注明不支持）。 */
internal fun isQwenAudioModel(model: String): Boolean = normalizeDashAsrModel(model).let {
    it.equals(DASH_MODEL_QWEN_AUDIO_31_FLASH, ignoreCase = true) ||
        it.equals(DASH_MODEL_QWEN_AUDIO_FLASH, ignoreCase = true) ||
        it.equals(DASH_MODEL_QWEN_AUDIO_31_STREAMING, ignoreCase = true) ||
        it.equals(DASH_MODEL_QWEN_AUDIO_STREAMING, ignoreCase = true)
}

internal fun isQwenAudio31MessageModel(model: String): Boolean =
    normalizeDashAsrModel(model).equals(DASH_MODEL_QWEN_AUDIO_31_MESSAGE, ignoreCase = true)

internal fun isSemanticPunctuationSupported(model: String): Boolean =
    isRecognitionStreamingModel(model)

internal fun parseDashLanguages(value: String): List<String> = value
    .splitToSequence(',')
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()
    .take(MAX_QWEN_AUDIO_LANGUAGE_HINTS)
    .toList()
