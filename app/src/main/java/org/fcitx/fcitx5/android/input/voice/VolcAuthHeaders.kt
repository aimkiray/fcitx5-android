/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import okhttp3.Headers
import okhttp3.Request

/**
 * 火山引擎 ASR 鉴权请求头组装（移植自 BiBi VolcAuthHeaders.kt，去掉 Prefs 耦合，
 * 改为纯值对象，由 VoiceInputManager 在工厂处从 AppPrefs 读取后注入）。
 *
 * 双 auth：
 * - 新 auth（useNewAuth=true 且 apiKey 非空）：仅 `X-Api-Key: <apiKey>`。
 * - 旧 auth（回退）：`X-Api-App-Key: <appKey>` + `X-Api-Access-Key: <accessKey>`。
 * 任一凭证组就绪即可；VoiceInputManager.toggle() 前置检查保证不会构造全空 [VolcAuthValues]。
 *
 * 业务头：`X-Api-Resource-Id`（必选，来自模型 enum）+ `X-Api-Request-Id`（UUID）+
 * `X-Api-Sequence: -1`（文件识别固定 -1）。
 *
 * key 值不得写入日志（见规格 §8）。
 */
data class VolcAuthValues(
    val useNewAuth: Boolean,
    val apiKey: String,
    val appKey: String,
    val accessKey: String
) {
    /**
     * 是否具备可用凭证（toggle 前置检查调用）。
     * 新 auth：apiKey 非空；旧 auth：appKey 与 accessKey 均非空。
     */
    fun hasCredentials(): Boolean =
        if (useNewAuth) apiKey.isNotBlank()
        else appKey.isNotBlank() && accessKey.isNotBlank()

    /**
     * 凭证头列表（不含业务头）。
     * 值经 [stripNonHeaderChars] 剥离非 ASCII 可见字符：非法头值会让
     * okhttp Headers.Builder.add 抛 IllegalArgumentException，异常消息
     * 内嵌原始凭证值，可能经 toast/logcat 泄漏——剥离后既防崩也防泄。
     */
    fun credentialHeaders(): List<Pair<String, String>> =
        if (useNewAuth) listOf("X-Api-Key" to apiKey.stripNonHeaderChars())
        else listOf(
            "X-Api-App-Key" to appKey.stripNonHeaderChars(),
            "X-Api-Access-Key" to accessKey.stripNonHeaderChars()
        )

    /** 掩码三 key（防 data class 默认 toString 把凭证泄进日志）。 */
    override fun toString(): String =
        "VolcAuthValues(useNewAuth=$useNewAuth, apiKey=${mask(apiKey)}, " +
            "appKey=${mask(appKey)}, accessKey=${mask(accessKey)})"

    private fun mask(key: String): String =
        if (key.isBlank()) "<blank>" else "${key.take(4)}…"
}

/**
 * 组装火山引擎 ASR 请求头（凭证 + 业务）。
 * @param requestId 每次请求生成新 UUID.randomUUID().toString()
 */
fun volcHeaders(
    auth: VolcAuthValues,
    resourceId: String,
    requestId: String,
    sequence: String = "-1"
): Headers {
    val builder = Headers.Builder()
    auth.credentialHeaders().forEach { (name, value) -> builder.add(name, value) }
    builder.add("X-Api-Resource-Id", resourceId)
    builder.add("X-Api-Request-Id", requestId)
    builder.add("X-Api-Sequence", sequence)
    return builder.build()
}

/** Request.Builder 扩展，便于链式调用。 */
fun Request.Builder.volcHeaders(
    auth: VolcAuthValues,
    resourceId: String,
    requestId: String,
    sequence: String = "-1"
    // 与本扩展同名的顶层函数签名完全一致：此处必须包名限定，
    // 否则非限定调用会优先解析为隐式 this（Request.Builder）上的本扩展自身
): Request.Builder = headers(
    org.fcitx.fcitx5.android.input.voice.volcHeaders(
        auth, resourceId, requestId, sequence
    )
)
