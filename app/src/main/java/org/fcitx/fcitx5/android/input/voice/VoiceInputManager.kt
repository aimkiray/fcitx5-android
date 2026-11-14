/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.os.Handler
import android.os.Looper
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 内置 DashScope 语音输入管理器（进程级单例，替代已废弃的 AIDL ProviderManager）。
 *
 * 状态机：IDLE → LISTENING → STOPPING → IDLE。
 * - toggle()：idle 开始；listening/stopping 停止。前置检查失败返回 false。
 * - 引擎回调在 IO/binder 线程触发，所有 service/UI 操作经 mainHandler 切回主线程。
 */
object VoiceInputManager {
    const val BUILTIN_PROVIDER_ID = "builtin_dashscope"

    // ---- 状态机 ----
    private enum class State { IDLE, LISTENING, STOPPING }
    @Volatile private var state = State.IDLE

    // ---- 实例 ----
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var capture: VoiceInputAudioCapture? = null
    @Volatile private var engine: DashscopeStreamAsrEngine? = null

    /**
     * 会话代次：startSession 成功入口递增，回调构造时捕获。
     * 迟到的旧会话回调（engine/capture 的 post、字段清理）经 gen 比对丢弃，
     * 防止跨会话污染（旧回调清掉新会话字段、旧 final 落进新会话）。
     */
    @Volatile private var sessionGen = 0

    /**
     * stop(discardFinal = true) 设置：会话被输入目标切换/服务销毁终止时，
     * 丢弃本会话的最终识别结果，防止迟到的 final 落进下一个输入框。
     */
    @Volatile private var discardFinal = false

    // ---- 回调（KawaiiBarComponent 注册） ----
    // 全部 @Volatile：onLevel 等在采集线程做非空判断，
    // 注册/注销须对采集/引擎线程立即可见
    // 状态提示已由全键盘波形覆盖层承载（KeyboardWindow 注册 overlay 回调）；
    // 此处仅保留错误回调用于 Toast 报错
    @Volatile var voiceErrorCallback: ((String) -> Unit)? = null

    // ---- 覆盖层回调（KeyboardWindow 注册；与上方 KawaiiBar 回调并存，互不干扰） ----
    @Volatile var voiceOverlayShowCallback: (() -> Unit)? = null
    @Volatile var voiceOverlayWaveCallback: (() -> Unit)? = null
    @Volatile var voiceOverlayAmplCallback: ((Int) -> Unit)? = null
    @Volatile var voiceOverlayHideCallback: (() -> Unit)? = null

    /**
     * 引擎与采集均已就绪（ready 已通知）。
     * 仅供 KeyboardWindow 重建时恢复覆盖层判定波形是否应已可见；
     * 读取须先过 isListening()（IDLE/STOPPING 时该值无意义）。
     */
    @Volatile private var sessionReady = false
    internal fun isSessionReady(): Boolean = sessionReady

    /**
     * 仅 LISTENING 为 true（不含 STOPPING）：KeyboardWindow 重建时只应
     * 为仍在录的会话补建覆盖层；STOPPING 的 hide 已在 stopSession 触发，
     * 补建会产生盖至 final 的幽灵纯色板
     */
    internal fun isListening(): Boolean = state == State.LISTENING

    // ---- 公开方法 ----

    fun isBuiltinId(id: String): Boolean = id == BUILTIN_PROVIDER_ID

    fun isActive(): Boolean = state != State.IDLE

    /**
     * 切换语音输入（toggle）：idle → 开始；listening/stopping → 停止。
     * 返回 true 表示已启动或已请求停止。
     */
    fun toggle(service: FcitxInputMethodService): Boolean {
        return when (state) {
            State.IDLE -> {
                // CTO 修订（评审 S1）：前置检查失败必须返回 false，
                // 否则 hold 模式 voiceHoldActive 误置
                if (!VoiceInputAudioCapture.hasPermission(service)) {
                    voiceErrorCallback?.invoke(
                        service.getString(R.string.voice_error_no_permission)
                    )
                    // 覆盖层未 show 时幂等；保底清理可能的残留
                    voiceOverlayHideCallback?.invoke()
                    return false
                }
                if (AppPrefs.getInstance().voice.voiceInputDashscopeApiKey
                        .getValue().isBlank()
                ) {
                    voiceErrorCallback?.invoke(
                        service.getString(R.string.voice_error_no_api_key)
                    )
                    voiceOverlayHideCallback?.invoke()
                    return false
                }
                startSession(service)
                true
            }
            State.LISTENING, State.STOPPING -> {
                stopSession()
                true
            }
        }
    }

    /**
     * 强制停止（onFinishInputView / onDestroy / onStartInput / 外部调用）。
     * @param discardFinal true 时丢弃本会话的最终识别结果，
     *   防止迟到的 final 落进下一个输入框；hold/toggle 正常停止传 false。
     */
    fun stop(service: FcitxInputMethodService, discardFinal: Boolean = false) {
        if (state == State.IDLE) return
        this.discardFinal = discardFinal
        stopSession()
    }

    private fun startSession(service: FcitxInputMethodService) {
        if (!VoiceInputAudioCapture.hasPermission(service)) {
            voiceErrorCallback?.invoke(
                service.getString(R.string.voice_error_no_permission)
            )
            voiceOverlayHideCallback?.invoke()
            return
        }
        // 新会话代次：使上一会话尚未投递/正在投递的回调全部失效
        sessionGen++
        val gen = sessionGen
        discardFinal = false
        sessionReady = false
        // 上一会话可能异常残留 voiceComposingActive 标记；先复位，
        // 否则它会误清新会话写出的真实 preedit
        service.clearVoiceComposingText()
        // 提交当前 fcitx preedit（fcitx reset() 是提交而非丢弃），
        // 避免语音 composing 与残留 preedit 互相覆盖
        service.postFcitxJob { reset() }
        // 会话从建连阶段起即视为 active：onFinishInputView/onStartInput 清理路径
        // 与 VoiceInputHoldEnd 都依赖 isActive()，否则建连窗口内会话成为孤儿
        state = State.LISTENING
        // 覆盖层立即显示（wave 初始不可见，ready 后再启动动画）；
        // gen 守卫与迟到回调口径一致，防快速 stop 后 show 落到新会话
        if (gen == sessionGen) voiceOverlayShowCallback?.invoke()
        val prefs = AppPrefs.getInstance()
        val apiKey = prefs.voice.voiceInputDashscopeApiKey.getValue()
        val model = prefs.voice.voiceInputDashscopeModel.getValue().modelId
        val lang = prefs.voice.voiceInputDashscopeLanguage.getValue()
        // 支持逗号分隔多语言（realtime 路径只用 firstOrNull，整串传入会出错）
        val languages = lang.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        // URL：固定使用中国大陆端点（阿里云官方 wss）
        // 后续迭代可引入 region pref 扩展，v2 先硬编码
        val wsRealtimeUrl = "wss://maas.qianwenaiapi.com/api-ws/v1/realtime"
        val wsInferenceUrl = "wss://maas.qianwenaiapi.com/api-ws/v1/inference"

        val engineInstance = DashscopeStreamAsrEngine(
            context = service,
            scope = scope,
            apiKey = apiKey,
            model = model,
            languages = languages,
            keepDialect = false,
            disfluencyRemoval = true,
            prompt = "",
            wsRealtimeUrl = wsRealtimeUrl,
            wsInferenceUrl = wsInferenceUrl,
            listener = object : StreamingAsrEngine.Listener {
                override fun onPartial(text: String) {
                    mainHandler.post {
                        if (gen != sessionGen) return@post
                        // 会话已结束/取消的迟到 partial 不上屏（幽灵 partial）
                        if (state != State.LISTENING) return@post
                        service.setVoiceComposingText(text)
                    }
                }
                override fun onFinal(text: String) {
                    // 旧会话引擎的迟到回调不得触碰新会话字段（误停新实例）
                    if (gen != sessionGen) return
                    // 先取引用置空再 post；capture.stop() 会 join 采集线程，
                    // 挪到 IO 协程执行，避免阻塞主线程
                    val c = capture
                    capture = null
                    engine = null
                    scope.launch { c?.stop() }
                    mainHandler.post {
                        if (gen != sessionGen) return@post
                        state = State.IDLE
                        sessionReady = false
                        if (discardFinal || text.isBlank()) {
                            service.clearVoiceComposingText()
                        } else {
                            service.commitVoiceText(text)
                        }
                        voiceOverlayHideCallback?.invoke()
                    }
                }
                override fun onError(message: String) {
                    if (gen != sessionGen) return
                    val c = capture
                    capture = null
                    engine = null
                    scope.launch { c?.stop() }
                    mainHandler.post {
                        if (gen != sessionGen) return@post
                        state = State.IDLE
                        sessionReady = false
                        service.clearVoiceComposingText()
                        voiceErrorCallback?.invoke(message)
                        voiceOverlayHideCallback?.invoke()
                    }
                }
                override fun onStopped() {
                    // 录音阶段结束，引擎等待 final；不需要更新 UI（已在 listening 状态）
                }
            }
        )
        engine = engineInstance

        // PERF 修订：onLevel 节流缓存——采集线程每 ~100ms 调一次 onLevel，
        // 全量 post 会让主线程堆积无效 runnable；rms 差 <5 视觉不可辨，
        // 直接跳过（仅 post 前更新缓存：与"上次已 post 值"比，缓慢漂移
        // 累积到阈值仍会发出）。lambda 捕获，仅采集线程读写
        var lastPostedRms = Int.MIN_VALUE
        val captureInstance = VoiceInputAudioCapture(
            context = service,
            onPcm = { pcm, _, len, _ ->
                // 采集线程；捕获本实例而非 engine 字段，防止跨会话喂流。
                // appendPcm 内部线程安全。copyOfRange 切出本帧专属数组
                // （AudioRecord buf 复用），appendPcm 契约要求传入专属数组
                engineInstance.appendPcm(pcm.copyOfRange(0, len))
            },
            onLevel = { rms ->
                // 采集线程 → UI 回调须切主线程；未注册回调时不必 post
                if (voiceOverlayAmplCallback != null &&
                    abs(rms - lastPostedRms) >= 5
                ) {
                    lastPostedRms = rms
                    mainHandler.post {
                        if (gen != sessionGen) return@post
                        voiceOverlayAmplCallback?.invoke(rms)
                    }
                }
            },
            onError = { msg ->
                mainHandler.post {
                    if (gen != sessionGen) return@post
                    // 采集已死、会话终结：作废旧会话全部迟到回调，
                    // 防服务端迟到 final 在错误 toast 后仍上屏、防二次 toast
                    sessionGen++
                    voiceErrorCallback?.invoke(msg)
                    voiceOverlayHideCallback?.invoke()
                    service.clearVoiceComposingText()
                    // 异步死采集后字段不能残留（否则 stopSession 拿到野实例）；
                    // capture 死后引擎必须收尾，防 WS 泄漏（engine.stop 内部
                    // running/finalDelivered 已兜底单投递）
                    capture = null
                    val e = engine
                    engine = null
                    scope.launch { e?.stop() }
                    state = State.IDLE
                    sessionReady = false
                }
            }
        )
        capture = captureInstance

        // 先启动引擎（建立 WS 连接 + 准备 prebuffer），再启动采集；
        // 采集成功后才置 LISTENING 并通知 ready。
        // start() 除返回 isRunning=false 的同步校验失败外，还可能在
        // 回调挂接前的初始化阶段抛异常：与校验失败走同一条回滚路径
        if (!runCatching { engineInstance.start() }.isSuccess ||
            !engineInstance.isRunning
        ) {
            // start() 失败（onError 已被调用并调度清理，或异常发生于
            // 回调挂接前）：不得启动采集，防会话误入 LISTENING
            capture = null
            engine = null
            state = State.IDLE
            // start() 异常或 isRunning=false 时 WS 握手可能已建立连接，
            // 显式 stop 回滚防泄漏；onError 若已被调用此 stop 为幂等双保险
            runCatching { engineInstance.stop() }
            // onError 未触发的失败路径（异常先于回调挂接）无用户反馈，补一条
            if (gen == sessionGen) {
                voiceErrorCallback?.invoke(service.getString(R.string.voice_error_failed))
            }
            // show 已触发：覆盖层随回滚收起（onError 的 post 也会 hide，幂等双保险）
            if (gen == sessionGen) voiceOverlayHideCallback?.invoke()
            return
        }
        // AudioRecord 初始化可抛 SecurityException/IllegalStateException 等
        // （如权限在检查后被撤回、AudioRecord 构造路径未覆盖的异常），
        // 不包 runCatching 会崩溃且 LISTENING 卡死、覆盖层残留
        val captureStarted = runCatching { captureInstance.start() }
            .onFailure {
                // 正常 return false 的路径均已先调 onError（有 toast）；
                // 抛异常时 onError 未必触发，补一条兜底错误反馈
                voiceErrorCallback?.invoke(service.getString(R.string.voice_error_failed))
            }
            .getOrDefault(false)
        if (!captureStarted) {
            // 采集启动失败（onError 已被 VoiceInputAudioCapture 触发）
            engineInstance.stop()
            engine = null
            capture = null
            state = State.IDLE
            // 同上：覆盖层随回滚收起
            if (gen == sessionGen) voiceOverlayHideCallback?.invoke()
            return
        }
        sessionReady = true
        if (gen == sessionGen) voiceOverlayWaveCallback?.invoke()
    }

    private fun stopSession() {
        state = State.STOPPING
        // 会话进入终止流程即视为"未就绪"：isListening() 已不含 STOPPING，
        // 窗口重建不再补建覆盖层；此复位保证残留的 isSessionReady() 读到 false
        sessionReady = false
        // 用户主动结束：覆盖层立即滑出，不等引擎 final 回调
        voiceOverlayHideCallback?.invoke()
        // capture.stop() 会 join 采集线程（最长 300ms），与 engine.stop() 一起放入
        // 协程执行，保持"先停采集、再让引擎 commit"的顺序且不阻塞主线程
        val c = capture
        capture = null
        val e = engine
        // 与 capture 对称置空：STOPPING 期间字段不得残留引擎引用，
        // 防止 stop() 重入或清理路径拿到野实例重复 stop
        engine = null
        scope.launch {
            c?.stop()
            // engine.stop() 触发 recognizer.stop()/conversation.commit()，等 onFinal
            e?.stop()
        }
        // onFinal/onError 回调会将 state 设回 IDLE
    }
}
