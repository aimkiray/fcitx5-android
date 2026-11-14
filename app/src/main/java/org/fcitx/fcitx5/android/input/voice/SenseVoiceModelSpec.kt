/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * 本地 SenseVoice 模型文件完整性规格（移植自 BiBi LocalModelSpecs.SenseVoice）。
 *
 * 下载源（已实测 200，2026-11-14）：
 *   - 主源：BiBi release zip `https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.zip`
 *   - 镜像：主源前拼 `https://gh-proxy.com/`（国内加速）
 *   注：HF `csukuangfj` 的 int8-2024-07-17 仓不存在（仅 fp32 2024 / int8 2025-09-09，
 *   后者 sha256 与下列指纹不符）；hf-mirror 不适用——勿恢复 HF 逐文件方案。
 *
 * 指纹由 BiBi LocalModelSpecs.SenseVoice 提供，与调研文档 §B 一致。
 */
internal object SenseVoiceModelSpec {
    const val VARIANT_DIR = "small-int8"
    const val MODEL_FILE = "model.int8.onnx"
    const val TOKENS_FILE = "tokens.txt"
    const val SUBDIR = "sensevoice"

    val model = LocalModelFileSpec(
        name = MODEL_FILE,
        sizeBytes = 239_233_841L,
        sha256 = "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"
    )
    val tokens = LocalModelFileSpec(
        name = TOKENS_FILE,
        sizeBytes = 315_894L,
        sha256 = "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"
    )

    // 单一 zip 源 + gh-proxy 镜像前缀
    const val MODEL_ZIP_URL =
        "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/" +
        "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.zip"
    const val MIRROR_PREFIX = "https://gh-proxy.com/"
    fun zipUrl(useMirror: Boolean): String =
        if (useMirror) MIRROR_PREFIX + MODEL_ZIP_URL else MODEL_ZIP_URL
}

/**
 * 轻量版文件完整性规格（本仓自用，不依赖 BiBi LocalModelIntegrity 全部复杂度）。
 * 只需 name/sizeBytes/sha256 三字段，sha256 校验用 MessageDigest SHA-256。
 */
internal data class LocalModelFileSpec(
    val name: String,
    val sizeBytes: Long,
    val sha256: String
)

/**
 * 模型就绪状态封装（对齐 BiBi LocalModelCheck 语义，不带泛型参数——本仓只用 Unit ready）。
 */
internal sealed class SvModelStatus {
    /** 两个文件均存在且 sha256 通过。 */
    object Ready : SvModelStatus()
    /** 至少一个文件不存在。 */
    object Missing : SvModelStatus()
    /** 文件存在但 sha256 不匹配（fileName 供错误提示）。 */
    data class IntegrityError(val fileName: String) : SvModelStatus()
}
