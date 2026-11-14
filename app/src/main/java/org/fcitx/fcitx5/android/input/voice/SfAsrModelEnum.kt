/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

/**
 * SiliconFlow 免费 ASR 模型枚举。
 * 两者均在 SiliconFlow 平台免费（¥0）。
 */
enum class SfAsrModelEnum(
    val modelId: String,
    override val stringRes: Int
) : ManagedPreferenceEnum {
    SenseVoiceSmall(
        "FunAudioLLM/SenseVoiceSmall",
        R.string.voice_sf_model_sensevoice
    ),
    TeleSpeechASR(
        "TeleAI/TeleSpeechASR",
        R.string.voice_sf_model_telespeech
    )
}
