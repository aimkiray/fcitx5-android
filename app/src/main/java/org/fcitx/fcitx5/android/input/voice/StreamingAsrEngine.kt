/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/** 流式 ASR 引擎基础接口（内置引擎专用，不走 AIDL）。 */
interface StreamingAsrEngine {
    val isRunning: Boolean
    fun start()
    fun stop()

    /**
     * 取消会话（丢弃结果）：默认等同 stop()。
     * 文件型引擎应 override 为"仅本地清理、不上传"——已废弃会话的音频
     * 不应被发往第三方服务器后再丢弃结果（白调 API + 隐私外发）。
     */
    fun cancel() {
        stop()
    }

    /** 推入 PCM 帧（16kHz/16bit/mono，独占数组）。默认空实现供不支持推流的引擎忽略。 */
    fun appendPcm(pcm: ByteArray) {}

    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
        /** 录音阶段结束（用户停止后，上传/识别期间）。 */
        fun onStopped() {}
        /** 实时振幅（0..32767，与 VoiceInputAudioCapture.rmsOf 返回值范围一致）。 */
        fun onAmplitude(rms: Int) {}
    }
}
