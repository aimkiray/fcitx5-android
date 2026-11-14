/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.transition.Slide
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.voice.VoiceInputManager
import org.fcitx.fcitx5.android.input.voice.WaveformView
import org.fcitx.fcitx5.android.input.wm.EssentialWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent

class KeyboardWindow : InputWindow.SimpleInputWindow<KeyboardWindow>(), EssentialWindow,
    InputBroadcastReceiver {

    private val service by manager.inputMethodService()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val windowManager: InputWindowManager by manager.must()
    private val popup: PopupComponent by manager.must()
    private val bar: KawaiiBarComponent by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()

    companion object : EssentialWindow.Key

    override val key: EssentialWindow.Key
        get() = KeyboardWindow

    override fun enterAnimation(lastWindow: InputWindow) = Slide().apply {
        slideEdge = Gravity.BOTTOM
    }.takeIf {
        // disable animation switching between picker
        lastWindow !is PickerWindow
    }

    override fun exitAnimation(nextWindow: InputWindow) =
        super.exitAnimation(nextWindow).takeIf {
            // disable animation switching between picker
            nextWindow !is PickerWindow
        }

    private lateinit var keyboardView: FrameLayout

    // 语音会话覆盖层（FrameLayout 容器 + 内部 WaveformView），由
    // VoiceInputManager 的 overlay 回调驱动 show/startWave/hide/updateAmplitude
    private var voiceOverlay: FrameLayout? = null
    private var voiceWave: WaveformView? = null
    // onAttached 中注册的 overlay 回调引用，供 onDetached 的 === 守卫精确注销
    private var localShowCb: (() -> Unit)? = null
    private var localWaveCb: (() -> Unit)? = null
    private var localAmplCb: ((Int) -> Unit)? = null
    private var localHideCb: (() -> Unit)? = null

    private val keyboards: HashMap<String, BaseKeyboard> by lazy {
        hashMapOf(
            TextKeyboard.Name to TextKeyboard(context, theme),
            NumberKeyboard.Name to NumberKeyboard(context, theme)
        )
    }
    private var currentKeyboardName = ""
    private var lastSymbolType: String by AppPrefs.getInstance().internal.lastSymbolLayout

    private val currentKeyboard: BaseKeyboard? get() = keyboards[currentKeyboardName]

    private val keyActionListener = KeyActionListener { it, source ->
        if (it is KeyAction.LayoutSwitchAction) {
            switchLayout(it.act)
        } else {
            commonKeyActionListener.listener.onKeyAction(it, source)
        }
    }

    private val popupActionListener: PopupActionListener by lazy {
        popup.listener
    }

    // This will be called EXACTLY ONCE
    override fun onCreateView(): View {
        keyboardView = context.frameLayout(R.id.keyboard_view)
        attachLayout(TextKeyboard.Name)
        return keyboardView
    }

    private fun detachCurrentLayout() {
        currentKeyboard?.also {
            it.onDetach()
            keyboardView.removeView(it)
            it.keyActionListener = null
            it.popupActionListener = null
        }
    }

    private fun attachLayout(target: String) {
        currentKeyboardName = target
        currentKeyboard?.let {
            it.keyActionListener = keyActionListener
            it.popupActionListener = popupActionListener
            // 键盘视图插入到 index 0：语音覆盖层后添加、恒在顶层，
            // 否则 switchLayout 换入的新键盘会压在 overlay 上（波形
            // SurfaceView 现为 setZOrderMediaOverlay，surface 在窗口
            // 内容之下、靠 SurfaceView 打孔透出，可见性由 view 顺序保证）
            keyboardView.apply { addView(it, 0, lParams(matchParent, matchParent)) }
            it.onAttach()
            it.onReturnDrawableUpdate(returnKeyDrawable.resourceId)
            it.onInputMethodUpdate(fcitx.runImmediately { inputMethodEntryCached })
        }
    }

    fun switchLayout(to: String, remember: Boolean = true) {
        val target = to.ifEmpty { lastSymbolType }
        ContextCompat.getMainExecutor(service).execute {
            if (keyboards.containsKey(target)) {
                if (remember && target != TextKeyboard.Name) {
                    lastSymbolType = target
                }
                if (target == currentKeyboardName) return@execute
                detachCurrentLayout()
                attachLayout(target)
                if (windowManager.isAttached(this)) {
                    notifyBarLayoutChanged()
                }
            } else {
                if (remember) {
                    lastSymbolType = PickerWindow.Key.Symbol.name
                }
                windowManager.attachWindow(PickerWindow.Key.Symbol)
            }
        }
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        val targetLayout = when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> NumberKeyboard.Name
            InputType.TYPE_CLASS_PHONE -> NumberKeyboard.Name
            else -> TextKeyboard.Name
        }
        switchLayout(targetLayout, remember = false)
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        currentKeyboard?.onInputMethodUpdate(ime)
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        currentKeyboard?.onPunctuationUpdate(mapping)
    }

    override fun onReturnKeyDrawableUpdate(resourceId: Int) {
        currentKeyboard?.onReturnDrawableUpdate(resourceId)
    }

    override fun onAttached() {
        currentKeyboard?.let {
            it.keyActionListener = keyActionListener
            it.popupActionListener = popupActionListener
            it.onAttach()
        }
        notifyBarLayoutChanged()
        // 注册语音覆盖层回调。局部 val 持有 lambda 引用，供 onDetached
        // 的 === 守卫精确注销（与 KawaiiBarComponent 的回调注册模式一致）：
        // 回调挂在进程级单例上，避免窗口 detach 后迟到事件触达已销毁视图
        val showCb: () -> Unit = { showVoiceOverlay() }
        val waveCb: () -> Unit = { startVoiceOverlayWave() }
        val amplCb: (Int) -> Unit = { rms -> updateVoiceOverlayAmplitude(rms) }
        val hideCb: () -> Unit = { hideVoiceOverlay() }
        localShowCb = showCb
        localWaveCb = waveCb
        localAmplCb = amplCb
        localHideCb = hideCb
        VoiceInputManager.apply {
            voiceOverlayShowCallback = showCb
            voiceOverlayWaveCallback = waveCb
            voiceOverlayAmplCallback = amplCb
            voiceOverlayHideCallback = hideCb
        }
        // InputView 重建（主题切换/旋转/窗口切换回来）时语音会话可能仍在
        // 进行：本窗口注册已错过 show 触发，按会话状态补建覆盖层；
        // 会话已就绪（ready 已通知）时波形直接可见，否则保持 INVISIBLE
        // 直到后续 voiceOverlayWaveCallback 到来。
        // 仅 LISTENING 会话补建：STOPPING 会话的 hide 回调已在
        // stopSession 入口触发（覆盖层马上滑出），补建会产生盖在键盘上的
        // 幽灵纯色板直到 final 到达
        if (VoiceInputManager.isListening() && voiceOverlay == null) {
            showVoiceOverlay()
            if (VoiceInputManager.isSessionReady()) startVoiceOverlayWave()
        }
    }

    override fun onDetached() {
        // attachWindow 的顺序是 onDetached() → view.removeView(oldView)：
        // 本函数末尾把 keyActionListener 置 null 之后，removeView 触发的
        // cancelTouchTarget → cancelled Up → VoiceInputHoldEnd 会被丢弃，
        // hold 会话成为孤儿（用户已松手但录音继续）。detach 隐含取消 hold，
        // 故在此显式投递；CommonKeyActionListener 内 voiceHoldActive=false
        // 时该 action 是 no-op，幂等
        commonKeyActionListener.listener.onKeyAction(
            KeyAction.VoiceInputHoldEnd, KeyActionListener.Source.Keyboard
        )
        // 先收起覆盖层防残留（keyboardView 离屏后 removeView 仍安全），
        // 再按 === 守卫注销本窗口注册的回调
        hideVoiceOverlay()
        with(VoiceInputManager) {
            if (voiceOverlayShowCallback === localShowCb) voiceOverlayShowCallback = null
            if (voiceOverlayWaveCallback === localWaveCb) voiceOverlayWaveCallback = null
            if (voiceOverlayAmplCallback === localAmplCb) voiceOverlayAmplCallback = null
            if (voiceOverlayHideCallback === localHideCb) voiceOverlayHideCallback = null
        }
        localShowCb = null
        localWaveCb = null
        localAmplCb = null
        localHideCb = null
        currentKeyboard?.let {
            it.onDetach()
            it.keyActionListener = null
            it.popupActionListener = null
        }
        popup.dismissAll()
    }

    /**
     * 语音会话激活时覆盖整个键盘区域的覆盖层：
     * 主题键盘底色 + 居中的 [WaveformView] 声波动画（初始 INVISIBLE，
     * 引擎/采集就绪后经 startVoiceOverlayWave 显示）。
     * 不可点击/不可聚焦：触摸事件穿透至下层键盘，hold 松开的
     * ACTION_UP 仍可到达按键结束会话。
     */
    private fun showVoiceOverlay() {
        if (voiceOverlay != null) return // 幂等守卫

        // 透明主题（TransparentDark/TransparentLight）的 keyboardColor 为
        // 0x00000000：半透明色会让 ColorUtils.calculateContrast 抛
        // IllegalArgumentException（崩溃且遗留 LISTENING 孤儿会话）。先叠底
        // 不透明的 backgroundColor，得到覆盖层实际呈现的底色，"全键盘被覆盖"
        // 语义在透明主题下同样成立
        val kb = theme.keyboardColor // Theme 基类直接提供
        val bgColor = if (Color.alpha(kb) == 255) kb else
            ColorUtils.compositeColors(kb, theme.backgroundColor or 0xFF000000.toInt())

        // 对比色挑选链：取第一个对背景对比度 ≥ 2.5 的候选色，保证波形可辨认；
        // runCatching 兜底防御（自定义主题给入半透明 candidate 等异常输入不崩溃）
        val waveColor = listOf(
            theme.genericActiveForegroundColor,
            theme.accentKeyBackgroundColor,
            theme.keyTextColor
        ).firstOrNull { candidate ->
            runCatching {
                ColorUtils.calculateContrast(candidate, bgColor) >= 2.5
            }.getOrDefault(false)
        } ?: theme.genericActiveForegroundColor // fallback，保证不 crash

        val overlay = FrameLayout(keyboardView.context).apply {
            setBackgroundColor(bgColor)
            isClickable = false
            isFocusable = false
            // NO_HIDE_DESCENDANTS：覆盖层与其内部 WaveformView 整体对
            // 无障碍服务不可见（纯装饰性动画，不承载语义信息）
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val wave = WaveformView(keyboardView.context).apply {
            setWaveformColor(waveColor)
            visibility = View.INVISIBLE
        }
        overlay.addView(
            wave, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        voiceOverlay = overlay
        voiceWave = wave

        // 直接 addView，不做滑入动画：TransitionManager 会先遍历整棵
        // 键盘视图树计算起始布局，叠加 SurfaceView 首帧创建的重量级开销，
        // 恰好在按下语音键的最敏感路径上制造掉帧；实测滑入动画也影响
        // 体感流畅度，故省略
        keyboardView.addView(
            overlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun startVoiceOverlayWave() {
        val wave = voiceWave ?: return
        wave.visibility = View.VISIBLE
        wave.start()
    }

    private fun hideVoiceOverlay() {
        val overlay = voiceOverlay ?: return
        val wave = voiceWave

        // 先停波形渲染再摘字段引用；不做滑出动画（同 show 路径的理由），
        // removeView 对已 detach 的父视图同样安全，随后 View 层触发
        // onDetachedFromWindow 释放 WaveLineView 渲染线程
        wave?.stop()
        voiceOverlay = null
        voiceWave = null
        keyboardView.removeView(overlay)
    }

    private fun updateVoiceOverlayAmplitude(rms: Int) {
        voiceWave?.updateAmplitude(WaveformView.rmsToAmplitude(rms))
    }

    // Call this when
    // 1) the keyboard window was newly attached
    // 2) currently keyboard window is attached and switchLayout was used
    private fun notifyBarLayoutChanged() {
        bar.onKeyboardLayoutSwitched(currentKeyboardName == NumberKeyboard.Name)
    }
}