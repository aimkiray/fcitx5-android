/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
/**
 * DashScope Recognition WebSocket 连接复用（进程内单槽缓存）。
 *
 * 对齐官方 Java SDK「对象池 borrow/return/invalidate」模式，但不引入 commons-pool2：
 * 移动端多为串行识别，单槽即可跨引擎实例复用同一 Recognition（inference WebSocket）。
 * Qwen3 realtime 不走此缓存。
 */
package org.fcitx.fcitx5.android.input.voice

import com.alibaba.dashscope.audio.asr.recognition.Recognition
import timber.log.Timber

internal data class DashscopeRecognitionReuseKey(
    val wsUrl: String,
    val apiKey: String,
    val model: String,
    val sampleRate: Int
) {
    // apiKey 属敏感数据，toString 只保留前缀掩码，防止误入日志
    override fun toString(): String =
        "DashscopeRecognitionReuseKey(wsUrl=$wsUrl, apiKey=${apiKey.take(4)}…, " +
            "model=$model, sampleRate=$sampleRate)"
}

internal object DashscopeRecognitionConnectionCache {
    /**
     * 空闲复用上限。官方文档口径为 60s，但常见服务端/网关空闲超时更短
     * （约 30s）：30s 后归还的连接可能已被服务端断开，borrow 时会拿到
     * 死连接（虽 startRecognitionStreaming 有 call 失败重建兜底，仍多
     * 一次往返）。收紧到 30s 降低借到死连接的概率。
     */
    private const val MAX_IDLE_MS = 30_000L

    private val lock = Any()
    private var idle: IdleEntry? = null

    private data class IdleEntry(
        val key: DashscopeRecognitionReuseKey,
        val recognition: Recognition,
        val returnedAtElapsedMs: Long
    )

    data class BorrowResult(
        val recognition: Recognition,
        val reused: Boolean
    )

    fun borrow(key: DashscopeRecognitionReuseKey): BorrowResult = synchronized(lock) {
        val existing = idle
        idle = null
        if (existing == null) {
            logDiag("dash_ws_reuse_new", mapOf("reason" to "empty", "model" to key.model))
            return BorrowResult(Recognition(), reused = false)
        }
        if (existing.key != key) {
            logDiag(
                "dash_ws_reuse_new",
                mapOf("reason" to "key_mismatch", "model" to key.model)
            )
            closeQuietly(existing.recognition, "key_mismatch")
            return BorrowResult(Recognition(), reused = false)
        }
        val idleMs = android.os.SystemClock.elapsedRealtime() - existing.returnedAtElapsedMs
        if (idleMs > MAX_IDLE_MS) {
            logDiag(
                "dash_ws_reuse_new",
                mapOf("reason" to "idle_expired", "idleMs" to idleMs, "model" to key.model)
            )
            closeQuietly(existing.recognition, "idle_expired")
            return BorrowResult(Recognition(), reused = false)
        }
        logDiag(
            "dash_ws_reuse_hit",
            mapOf("idleMs" to idleMs, "model" to key.model)
        )
        return BorrowResult(existing.recognition, reused = true)
    }

    /**
     * 任务成功结束后归还。不要归还未完成或失败的 Recognition。
     */
    fun returnSuccess(key: DashscopeRecognitionReuseKey, recognition: Recognition) {
        synchronized(lock) {
            val existing = idle
            idle = null
            if (existing != null) {
                closeQuietly(existing.recognition, "superseded")
            }
            idle = IdleEntry(
                key = key,
                recognition = recognition,
                returnedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
            )
            logDiag("dash_ws_reuse_return", mapOf("model" to key.model))
        }
    }

    /**
     * 任务失败或配置失效：关闭底层 WebSocket 并废弃对象。
     */
    fun invalidate(
        key: DashscopeRecognitionReuseKey?,
        recognition: Recognition,
        reason: String
    ) {
        synchronized(lock) {
            val existing = idle
            if (existing != null && existing.recognition === recognition) {
                idle = null
            } else if (existing != null && key != null && existing.key == key) {
                idle = null
                closeQuietly(existing.recognition, "invalidate_stale_$reason")
            }
        }
        closeQuietly(recognition, reason)
        logDiag(
            "dash_ws_reuse_invalidate",
            mapOf("reason" to reason, "model" to (key?.model ?: ""))
        )
    }

    /** 进程或配置切换时清空空闲连接。 */
    fun clear(reason: String = "clear") {
        synchronized(lock) {
            val existing = idle
            idle = null
            if (existing != null) {
                closeQuietly(existing.recognition, reason)
                logDiag(
                    "dash_ws_reuse_invalidate",
                    mapOf("reason" to reason, "model" to existing.key.model)
                )
            }
        }
    }

    private fun closeQuietly(recognition: Recognition, reason: String) {
        try {
            DashscopeRecognitionTransport.close(recognition, reason)
        } catch (t: Throwable) {
            Timber.w(t, "duplex close failed: $reason")
        }
    }

    private fun logDiag(event: String, data: Map<String, Any?> = emptyMap()) {
        Timber.d("$event $data")
    }
}
