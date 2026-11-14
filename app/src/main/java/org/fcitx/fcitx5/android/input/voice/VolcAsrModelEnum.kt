/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

/**
 * 火山引擎文件识别模型枚举。
 *
 * - [modelId]：UI 列表与 BiBi catalog 对齐的稳定 id。
 * - [resourceId]：火山 X-Api-Resource-Id 头取值（BiBi VolcAsrModel.resourceId）。
 *
 * 仅含文件式模型；流式 v1/v2（sauc.duration）不移植。
 */
enum class VolcAsrModelEnum(
    val modelId: String,
    val resourceId: String,
    override val stringRes: Int
) : ManagedPreferenceEnum {
    DoubaoFileV2(
        "doubao_file_v2",
        "volc.seedasr.auc",
        R.string.voice_volc_model_file_v2
    ),
    DoubaoFileV1(
        "doubao_file_v1",
        "volc.bigasr.auc",
        R.string.voice_volc_model_file_v1
    ),
    DoubaoFileTurboV1(
        "doubao_file_turbo_v1",
        "volc.bigasr.auc_turbo",
        R.string.voice_volc_model_file_turbo_v1
   );
}
