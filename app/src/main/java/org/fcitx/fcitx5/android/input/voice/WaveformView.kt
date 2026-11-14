/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import jaygoo.widget.wlv.WaveLineView
import kotlin.math.ln

/**
 * 语音会话覆盖层中的实时声波动画视图：FrameLayout 包装 vendored
 * [WaveLineView]（SurfaceView + 渲染线程），统一 start/stop/release 生命周期。
 *
 * vendored 固有瑕疵台账（与上游/fork 一致，保留保真，不修）：
 * 无 exit 动画、RenderView 死 import java.util.List。
 * 已修原台账条目（详见 jaygoo/widget/wlv 两文件头偏离清单）：
 * 非 volatile 跨线程字段、stopAnim 后渲染线程空转、
 * onWindowFocusChanged 失焦分支反向、targetVolume=50 初值、
 * setZOrderOnTop→setZOrderMediaOverlay。
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val AMPLITUDE_GAIN = 1.6
        private const val AMPLITUDE_LOG_K = 18.0

        // RMS (Int, 0–32767) → WaveformView amplitude (Float, 0–1)
        fun rmsToAmplitude(rms: Int): Float = (rms.coerceIn(0, 32767) / 32767f)

        // amplitude (Float, 0–1) → WaveLineView volume (Int, 0–100), log-scaled
        fun amplitudeToVolume(amplitude: Float): Int {
            val a = (amplitude.coerceIn(0f, 1f) * AMPLITUDE_GAIN).coerceIn(0.0, 1.0)
            val mapped = ln(1.0 + AMPLITUDE_LOG_K * a) / ln(1.0 + AMPLITUDE_LOG_K)
            return (mapped * 100).toInt().coerceIn(0, 100)
        }
    }

    private var isActive = false

    // WaveLineView 内部保持 setZOrderOnTop（见其 initAttr 注释）：
    // 波形 surface 浮于整个 IME 窗口之上，mediaOverlay 路径实测逐帧
    // alpha 混合导致卡顿已回退；代价是语音期间同窗口 popup 仅被波形
    // 线像素遮挡（P2 视觉瑕疵，已接受）
    private val waveView = WaveLineView(context).apply {
        setBackGroundColor(android.graphics.Color.TRANSPARENT)
        setSensibility(15)   // 实际钳位到 10（WaveLineView 内部 checkSensibilityValue）
        setMoveSpeed(250f)
    }

    init {
        addView(waveView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        visibility = View.GONE
    }

    fun setWaveformColor(@ColorInt color: Int) {
        waveView.setLineColor(color)
    }

    fun updateAmplitude(amplitude: Float) {
        if (!isActive) return
        waveView.setVolume(amplitudeToVolume(amplitude))
    }

    fun start() {
        if (isActive) return // fork 幂等守卫：重复 start 不重启渲染线程/动画
        isActive = true
        waveView.startAnim()
    }

    fun stop() {
        if (!isActive) return // fork 幂等守卫：未激活时不再触碰 surface
        isActive = false
        waveView.stopAnim()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        waveView.onWindowFocusChanged(hasWindowFocus)
    }

    // L-4 记录（不修）：不可见即停渲染，但重新可见时不会自动重启——
    // 当前覆盖层生命周期由 VoiceInputManager 回调驱动，stop 后必走
    // hideVoiceOverlay 移除视图，无"停后再可见"的触发路径，故无需重启逻辑
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE && isActive) stop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // detach 清理 best-effort：surface 可能已销毁，
        // stopAnim/release 内部异常不外溢（防崩溃盖住 detach 流程）
        runCatching {
            stop()
            waveView.release()
        }
    }
}
