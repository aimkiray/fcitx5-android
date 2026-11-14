/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.os.Handler
import android.os.Looper
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    const val BUILTIN_SF_PROVIDER_ID = "builtin_siliconflow"
    const val BUILTIN_VOLC_PROVIDER_ID = "builtin_volc"
    const val BUILTIN_SV_PROVIDER_ID = "builtin_sensevoice"

    // ---- 状态机 ----
    private enum class State { IDLE, LISTENING, STOPPING }
    @Volatile private var state = State.IDLE

    // ---- 实例 ----
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var capture: VoiceInputAudioCapture? = null
    @Volatile private var engine: StreamingAsrEngine? = null

    /**
     * 上一会话 capture.stop() 的执行句柄（IO 协程内 join 采集线程 ≤300ms）。
     * discard/回调路径同步置 IDLE 但旧 AudioRecord 释放是异步的；
     * startSession 在新采集启动前检查并等待它，防录音双开（评审 C-M2）。
     */
    @Volatile private var captureStopJob: Job? = null

    /**
     * 已进入终止流程但尚未收口的引擎引用（评审 A-M2 修订）：
     * 正常 stopSession 把 `engine` 置空后，SF/Volc 上传、SV 解码可能仍在途；
     * 此时 discard 路径（onStartInput/onFinishInputView/onDestroy）需要经此
     * 引用调 `cancel()` 真正中断在途上传（`call.cancel()`），否则已废弃
     * 会话的音频照常外发第三方。
     * 置位：stopSession 非 cancel 路径；清位：onFinal/onError 收口、
     * capture.onError、discard 路径取用后、startSession 新会话开始。
     */
    @Volatile private var stoppingEngine: StreamingAsrEngine? = null

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

    fun isBuiltinId(id: String): Boolean =
        id == BUILTIN_PROVIDER_ID ||
            id == BUILTIN_SF_PROVIDER_ID ||
            id == BUILTIN_VOLC_PROVIDER_ID ||
            id == BUILTIN_SV_PROVIDER_ID

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
                // API key 前置检查按 provider 分支：
                // DashScope 仅需自有 key；SiliconFlow 自有 key 与内置
                // BuildConfig key 双轨，均空才阻断（引擎 start() 内再兜底）
                val prefs = AppPrefs.getInstance()
                val providerId = prefs.keyboard.preferredVoiceInput.getValue()
                val keyMissing = when (providerId) {
                    BUILTIN_PROVIDER_ID ->
                        prefs.voice.voiceInputDashscopeApiKey.getValue().isBlank()
                    BUILTIN_SF_PROVIDER_ID ->
                        prefs.voice.voiceInputSfApiKey.getValue().isBlank() &&
                            BuildConfig.SF_FREE_API_KEY.isBlank()
                    BUILTIN_VOLC_PROVIDER_ID -> {
                        // 双 auth 任一组就绪即可
                        val v = prefs.voice
                        if (v.voiceInputVolcUseNewAuth.getValue()) {
                            v.voiceInputVolcApiKey.getValue().isBlank()
                        } else {
                            v.voiceInputVolcAppKey.getValue().isBlank() ||
                                v.voiceInputVolcAccessKey.getValue().isBlank()
                        }
                    }
                    BUILTIN_SV_PROVIDER_ID -> {
                        // 模型就绪检查（轻量存在性；完整 sha256 在 stop() IO 协程内做，
                        // 严禁在主线程对 228MB 文件做 sha256——会 ANR）
                        !SenseVoiceModelManager.modelFilesExist(service)
                    }
                    else -> false
                }
                if (keyMissing) {
                    val msgRes = when (providerId) {
                        BUILTIN_SF_PROVIDER_ID -> R.string.voice_error_sf_unavailable
                        BUILTIN_VOLC_PROVIDER_ID -> R.string.voice_error_volc_unavailable
                        BUILTIN_SV_PROVIDER_ID -> R.string.voice_error_sv_model_missing
                        else -> R.string.voice_error_no_api_key
                    }
                    voiceErrorCallback?.invoke(service.getString(msgRes))
                    voiceOverlayHideCallback?.invoke()
                    return false
                }
                startSession(service)
                true
            }
            State.LISTENING, State.STOPPING -> {
                stopSession(service)
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
        // discardFinal=true 即废弃会话：引擎走 cancel() 直接丢弃结果
        // （SF 不再把整段音频上传第三方后再丢弃），而非 stop() 正常收尾
        stopSession(service, cancel = discardFinal)
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
        // 上一会话若在终止途中从未回调（如被取消的 SV 解码），其引用在此
        // 丢弃——迟到的回调已由 sessionGen 守卫作废，继续持有只会泄漏
        stoppingEngine = null
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
        val providerId = prefs.keyboard.preferredVoiceInput.getValue()

        // start() 失败回滚时的双 toast 防护（评审 A-L4）：四个引擎的 start()
        // 校验失败都会同步调 onError（已带具体错误文案），回滚分支据此
        // 跳过通用 voice_error_failed 补刀
        var startErrorEmitted = false
        val engineInstance: StreamingAsrEngine = when (providerId) {
            BUILTIN_PROVIDER_ID -> {
                val apiKey = prefs.voice.voiceInputDashscopeApiKey.getValue()
                val model = prefs.voice.voiceInputDashscopeModel.getValue().modelId
                val lang = prefs.voice.voiceInputDashscopeLanguage.getValue()
                // 支持逗号分隔多语言（realtime 路径只用 firstOrNull，整串传入会出错）
                val languages =
                    lang.split(',').map { it.trim() }.filter { it.isNotEmpty() }

                // URL：固定使用中国大陆端点（阿里云官方 wss）
                // 后续迭代可引入 region pref 扩展，v2 先硬编码
                DashscopeStreamAsrEngine(
                    context = service,
                    scope = scope,
                    apiKey = apiKey,
                    model = model,
                    languages = languages,
                    keepDialect = false,
                    disfluencyRemoval = true,
                    prompt = "",
                    wsRealtimeUrl = "wss://maas.qianwenaiapi.com/api-ws/v1/realtime",
                    wsInferenceUrl = "wss://maas.qianwenaiapi.com/api-ws/v1/inference",
                    listener = engineListener(service, gen) { startErrorEmitted = true }
                )
            }
            BUILTIN_SF_PROVIDER_ID -> {
                val userKey = prefs.voice.voiceInputSfApiKey.getValue()
                val model = prefs.voice.voiceInputSfModel.getValue().modelId
                SiliconFlowFileAsrEngine(
                    context = service,
                    scope = scope,
                    userApiKey = userKey,
                    model = model,
                    listener = engineListener(service, gen) { startErrorEmitted = true }
                )
            }
            BUILTIN_VOLC_PROVIDER_ID -> {
                val v = prefs.voice
                val auth = VolcAuthValues(
                    useNewAuth = v.voiceInputVolcUseNewAuth.getValue(),
                    apiKey = v.voiceInputVolcApiKey.getValue(),
                    appKey = v.voiceInputVolcAppKey.getValue(),
                    accessKey = v.voiceInputVolcAccessKey.getValue()
                )
                val model = v.voiceInputVolcModel.getValue()
                val enableDdc = v.voiceInputVolcDdc.getValue()
                VolcFileAsrEngine(
                    context = service,
                    scope = scope,
                    auth = auth,
                    model = model,
                    enableDdc = enableDdc,
                    listener = engineListener(service, gen) { startErrorEmitted = true }
                )
            }
            BUILTIN_SV_PROVIDER_ID -> {
                SenseVoiceFileAsrEngine(
                    context = service,
                    scope = scope,
                    listener = engineListener(service, gen) { startErrorEmitted = true }
                )
            }
            else -> {
                // 不应进入（isBuiltinId 外路径不走 startSession），防御性处理
                voiceErrorCallback?.invoke(service.getString(R.string.voice_error_failed))
                voiceOverlayHideCallback?.invoke()
                state = State.IDLE
                return
            }
        }
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
                    // 先收口状态（与 onFinal/onError 对齐）：采集已死、会话终结，
                    // 回调内若同步触发新会话不会因残留 LISTENING 误判
                    state = State.IDLE
                    sessionReady = false
                    // 作废旧会话全部迟到回调，
                    // 防服务端迟到 final 在错误 toast 后仍上屏、防二次 toast
                    sessionGen++
                    voiceErrorCallback?.invoke(msg)
                    voiceOverlayHideCallback?.invoke()
                    service.clearVoiceComposingText()
                    // 异步死采集后字段不能残留（否则 stopSession 拿到野实例）；
                    // capture 死后引擎必须收尾：cancel 丢弃结果并释放资源
                    // （防 WS 泄漏；SF 不再把已废弃音频上传第三方）。
                    // 若 stopSession 已把引用移交 stoppingEngine，同样须够到
                    val c = capture
                    capture = null
                    val e = engine ?: stoppingEngine
                    engine = null
                    stoppingEngine = null
                    // c?.stop() 幂等：onError 路径采集多已自释放，但 worker
                    // finally 可能仍在收尾，stop() 兜底 join（R3 句柄登记）
                    captureStopJob = scope.launch {
                        c?.stop()
                        e?.cancel()
                    }
                }
            }
        )
        capture = captureInstance

        // R3：discard/回调路径同步置 IDLE，但旧 capture.stop() 的 join（≤300ms）
        // 仍在 IO 协程异步跑——此时直接 start 新 AudioRecord 可能双开失败。
        // prev 在跑则把"引擎启动 + 采集启动 + 就绪标记"整体推迟到其收口后；
        // prev 非 active（常见路径：上一会话早已停完或无上一会话）走原同步路径
        val prev = captureStopJob
        captureStopJob = null
        if (prev?.isActive == true) {
            // join 是挂起而非阻塞；resume 回主线程后按原顺序续跑
            scope.launch(Dispatchers.Main) {
                prev.join()
                if (gen != sessionGen) return@launch
                if (state != State.LISTENING) {
                    // 延迟窗口内会话被终止（如 stopSession 非 cancel 路径已置
                    // STOPPING）：未启动的引擎不会产生回调收口，此处代其收口，
                    // 防 STOPPING 无出口悬挂 + 迟到的采集误启动
                    capture = null
                    engine = null
                    stoppingEngine = null
                    state = State.IDLE
                    sessionReady = false
                    return@launch
                }
                startEngineAndCapture(service, gen, engineInstance, captureInstance) {
                    startErrorEmitted
                }
            }
            return
        }
        startEngineAndCapture(service, gen, engineInstance, captureInstance) {
            startErrorEmitted
        }
    }

    /**
     * startSession 尾部（抽取以便 R3 延迟分支复用，执行顺序与原内联一致）：
     * 先启动引擎（建立 WS 连接 + 准备 prebuffer），再启动采集；
     * 采集成功后才置 sessionReady 并通知 ready。两条回滚路径同原实现。
     * 须在主线程语境调用（原路径同步执行、延迟路径 Dispatchers.Main）。
     */
    private fun startEngineAndCapture(
        service: FcitxInputMethodService,
        gen: Int,
        engineInstance: StreamingAsrEngine,
        captureInstance: VoiceInputAudioCapture,
        startErrorEmitted: () -> Boolean
    ) {
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
            // 引擎 start() 已同步 onError 过的失败不再补通用 toast（防双弹）；
            // 仅 onError 未触发的路径（异常先于回调挂接）补一条
            if (gen == sessionGen && !startErrorEmitted()) {
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

    /**
     * 引擎事件监听器（DashScope / SiliconFlow 两路径共用，逻辑与原内联匿名
     * 对象完全一致）：gen 守卫丢弃迟到回调，service/UI 操作经 mainHandler
     * 切回主线程。
     */
    private fun engineListener(
        service: FcitxInputMethodService,
        gen: Int,
        onErrorDispatched: () -> Unit = {}
    ): StreamingAsrEngine.Listener = object : StreamingAsrEngine.Listener {
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
            stoppingEngine = null
            captureStopJob = scope.launch { c?.stop() }
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
            // 标记须在 gen 检查前：start() 同步 onError 时回滚分支据此跳过
            // 通用错误补刀；旧会话迟到 onError 写此标志无害（捕获变量已死）
            onErrorDispatched()
            if (gen != sessionGen) return
            val c = capture
            capture = null
            engine = null
            stoppingEngine = null
            captureStopJob = scope.launch { c?.stop() }
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

    private fun stopSession(service: FcitxInputMethodService, cancel: Boolean = false) {
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
        // discard 重入时 engine 已被首次 stopSession 置空：经 stoppingEngine
        // 取回在途引擎（SF/Volc 上传、SV 解码），保证 cancel() 可达（A-M2）
        val e = engine ?: stoppingEngine
        // 与 capture 对称置空：STOPPING 期间字段不得残留引擎引用，
        // 防止 stop() 重入或清理路径拿到野实例重复 stop
        engine = null
        if (cancel) {
            stoppingEngine = null
            // 废弃会话不依赖引擎回调收口（被取消的 SV 解码不会回调；
            // SF/Volc 取消触发的 onError 对本会话也已无意义）：
            // 作废本 gen 全部迟到回调（防错误 toast/迟到 final 上屏），直接收口
            sessionGen++
            state = State.IDLE
            service.clearVoiceComposingText()
        } else {
            // 正常停止：引擎引用移交 stoppingEngine 直至 onFinal/onError
            // 收口，期间 discard 重入仍可经它 cancel 到在途上传
            stoppingEngine = e
        }
        captureStopJob = scope.launch {
            c?.stop()
            if (cancel) {
                // 废弃会话：cancel() 丢弃结果、不上屏不上传
                // （DashScope 默认实现即 stop()；SF/Volc 中断在途 call）
                e?.cancel()
            } else {
                // engine.stop() 触发 recognizer.stop()/conversation.commit()
                // 或 SF 整段上传，等 onFinal/onError 收口
                e?.stop()
            }
        }
        // 非 cancel 路径由 onFinal/onError 回调将 state 设回 IDLE
    }
}
