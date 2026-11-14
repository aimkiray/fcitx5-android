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
