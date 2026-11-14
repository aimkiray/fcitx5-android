/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.R
import timber.log.Timber
import kotlin.concurrent.thread

// Mic capture for the IME's voice-input AIDL bridge.
// onPcm is invoked on the recording thread. Implementations must not block.
class VoiceInputAudioCapture(
    private val context: Context,
    private val config: Config = Config(),
    private val onPcm: (ByteArray, Int, Int, Long) -> Unit,
    private val onLevel: ((Int) -> Unit)? = null,
    private val onError: (String) -> Unit,
) {
    data class Config(
        val sampleRate: Int = DEFAULT_SAMPLE_RATE,
        val bitsPerSample: Int = DEFAULT_BITS_PER_SAMPLE,
        val channels: Int = DEFAULT_CHANNELS,
    )

    companion object {
        const val DEFAULT_SAMPLE_RATE = 16000
        const val DEFAULT_BITS_PER_SAMPLE = 16
        const val DEFAULT_CHANNELS = 1

        // 权限撤回看门狗：连续 ~1s（10×100ms 帧）rms 低于阈值才复查权限
        private const val SILENT_RMS_THRESHOLD = 5
        private const val SILENT_FRAME_LIMIT = 10

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var running = false

    fun start(): Boolean {
        if (!hasPermission(context)) {
            onError(context.getString(R.string.voice_error_no_permission))
            return false
        }
        val minBuf = AudioRecord.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            onError(context.getString(R.string.voice_error_audio_minbuf, minBuf))
            return false
        }
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                config.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (e: SecurityException) {
            onError(context.getString(R.string.voice_error_audio_construct, e.message))
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            onError(context.getString(R.string.voice_error_audio_not_init))
            return false
        }
        recorder = rec
        running = true
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            rec.release(); recorder = null; running = false
            onError(context.getString(R.string.voice_error_audio_start_failed, e.message))
            return false
        }

        // 100 ms chunks ⇒ low latency for partials, modest binder pressure.
        val chunkSamples = config.sampleRate / 10
        val chunkBytes = chunkSamples * config.bitsPerSample / 8 * config.channels
        val buf = ByteArray(chunkBytes)
        val startedAt = System.currentTimeMillis()

        worker = thread(name = "voice-input-capture", isDaemon = true) {
            // 音频采集对调度延迟敏感：提到音频级优先级，避免读空/溢出丢帧；
            // runCatching 兜底——个别 ROM/权限收紧时提权失败不影响采集
            runCatching {
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_URGENT_AUDIO
                )
            }
            var silentFrames = 0
            try {
                while (running && recorder != null) {
                    val read = recorder?.read(buf, 0, chunkBytes) ?: break
                    if (read < 0) {
                        // ERROR_INVALID_OPERATION/BAD_VALUE/DEAD_OBJECT/ERROR 统一上报：
                        // 只报一次（随后 break），stop() 途中产生的负值不上报
                        Timber.w("AudioRecord.read error=$read")
                        if (running) {
                            onError(context.getString(R.string.voice_error_audio_read, read))
                        }
                        break
                    }
                    if (read == 0) continue
                    val pts = System.currentTimeMillis() - startedAt
                    onPcm(buf, 0, read, pts)
                    val rms = rmsOf(buf, read)
                    onLevel?.invoke(rms)
                    // 权限被撤回后 AudioRecord 不报错、持续输出静音帧，
                    // 会话无声僵死。连续 ~1s rms<阈值时复查权限；已失则
                    // 走与 read<0 相同的 onError 路径终止会话（用户正常
                    // 沉默时权限仍在，不误杀；stop() 途中不上报）
                    silentFrames =
                        if (rms < SILENT_RMS_THRESHOLD) silentFrames + 1 else 0
                    if (silentFrames >= SILENT_FRAME_LIMIT &&
                        !hasPermission(context)
                    ) {
                        Timber.w("RECORD_AUDIO revoked mid-session, stopping capture")
                        if (running) {
                            onError(
                                context.getString(R.string.voice_error_no_permission)
                            )
                        }
                        break
                    }
                }
            } catch (e: Throwable) {
                Timber.w(e, "capture thread crashed")
            } finally {
                // 读错误/异常退出也要释放录音器并复位标志，避免静默死采集占用麦克风
                val rec = recorder
                recorder = null
                try { rec?.stop() } catch (_: Exception) {}
                try { rec?.release() } catch (_: Exception) {}
                running = false
            }
        }
        Timber.i("VoiceInputAudioCapture started: config=$config minBuf=$minBuf chunkBytes=$chunkBytes")
        return true
    }

    fun stop() {
        running = false
        val rec = recorder
        recorder = null
        try { rec?.stop() } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
        worker?.let {
            try { it.join(300) } catch (_: InterruptedException) {}
        }
        worker = null
    }

    private fun rmsOf(buf: ByteArray, len: Int): Int {
        if (len < 2) return 0
        val count = len / 2
        var sumSq = 0.0
        var i = 0
        while (i < len - 1) {
            val lo = buf[i].toInt() and 0xff
            val hi = buf[i + 1].toInt()
            val s = (hi shl 8) or lo
            sumSq += (s * s).toDouble()
            i += 2
        }
        return Math.sqrt(sumSq / count).toInt().coerceIn(0, 32767)
    }
}
