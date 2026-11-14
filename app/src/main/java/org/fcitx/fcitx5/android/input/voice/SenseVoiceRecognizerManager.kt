/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import kotlin.coroutines.coroutineContext

/**
 * sherpa-onnx `OfflineRecognizer` 进程级缓存管理（移植自 BiBi BaseSherpaOfflineRecognizerManager
 * + SenseVoiceOnnxManager，本仓直接依赖 AAR 而非反射，API 更稳定）。
 *
 * - 进程级单例（object）；首次 [decodeOffline] 时加载，之后复用。
 * - numThreads = 2, provider = "cpu", useITN = true（v1 固定值，不暴露偏好）。
 * - [unload]：释放 recognizer 内存；供设置页"清除模型"后调用。
 * - 解码期间可通过取消 [decodeOffline] 所在协程中断（CancellationException 传递）。
 *
 * 注意：sherpa-onnx `OfflineRecognizer` 创建开销约数百 ms~2s；[Mutex] 保证
 * 多协程不并发 create/decode，不支持并行多会话（本仓单会话保证此场景不出现）。
 *
 * AAR 实测 API（sherpa-onnx-2.0.1 classes.jar javap 已核验）：
 * `OfflineRecognizer(assetManager: AssetManager?, config: OfflineRecognizerConfig)`、
 * `createStream()`、`decode(stream)`、`getResult(stream).text`、
 * `stream.acceptWaveform(samples, sampleRate)`、`stream.release()`、`recognizer.release()`。
 */
object SenseVoiceRecognizerManager {

    private const val TAG = "SenseVoiceRecognizer"
    private const val NUM_THREADS = 2
    private const val PROVIDER = "cpu"
    private const val USE_ITN = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile private var cachedRecognizer: OfflineRecognizer? = null
    @Volatile private var cachedModelPath: String? = null

    val isPrepared: Boolean get() = cachedRecognizer != null

    /**
     * 解码一段 PCM（FloatArray，范围 -1..1，16kHz）。
     * - 若识别器未加载或配置变化，重新创建（先回调 [onLoadStart]，完成后 [onLoadDone]）。
     * - 返回识别文本（可能空串）；抛出则调用方捕获转 onError。
     * - 协程取消（[CancellationException]）向上传播——SenseVoiceFileAsrEngine 的 decodeJob.cancel()
     *   会到达此处。
     */
    suspend fun decodeOffline(
        modelPath: String,
        tokensPath: String,
        samples: FloatArray,
        sampleRate: Int,
        onLoadStart: (() -> Unit)? = null,
        onLoadDone: (() -> Unit)? = null
    ): String = mutex.withLock {
        coroutineContext.ensureActive()
        val recognizer = ensurePrepared(modelPath, tokensPath, onLoadStart, onLoadDone)
        // 加载完成（可能耗时 ~1s）后、建流前再确认：取消的会话不必进入解码
        coroutineContext.ensureActive()
        val stream = recognizer.createStream()
        return@withLock try {
            stream.acceptWaveform(samples, sampleRate = sampleRate)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text
            // getResult 后确认未取消：被取消的会话不应把真实文本回传调用方
            coroutineContext.ensureActive()
            text
        } finally {
            stream.release()
        }
    }

    /** 卸载识别器（释放 JNI 资源）：供"清除模型"设置项调用。 */
    fun unload() {
        scope.launch {
            val r = mutex.withLock {
                val r = cachedRecognizer
                cachedRecognizer = null
                cachedModelPath = null
                r
            }
            r?.let {
                try { it.release() } catch (t: Throwable) {
                    Timber.e(t, "Failed to release OfflineRecognizer")
                }
            }
        }
    }

    // 须在 mutex 锁内调用
    private suspend fun ensurePrepared(
        modelPath: String,
        tokensPath: String,
        onLoadStart: (() -> Unit)?,
        onLoadDone: (() -> Unit)?
    ): OfflineRecognizer {
        coroutineContext.ensureActive()
        val cached = cachedRecognizer
        if (cached != null && cachedModelPath == modelPath) return cached

        try { onLoadStart?.invoke() } catch (t: Throwable) {
            Log.w(TAG, "onLoadStart callback failed", t)
        }

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = 16000,
                featureDim = 80
            ),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = modelPath,
                    language = "auto",
                    useInverseTextNormalization = USE_ITN
                ),
                tokens = tokensPath,
                numThreads = NUM_THREADS,
                provider = PROVIDER,
                debug = false
            )
        )
        // OfflineRecognizer(config) 在创建时传 assetManager=null（绝对路径）。
        // 先建新实例成功后才 release 旧实例（对齐 BiBi）：构造失败时旧缓存
        // 仍可用，不至于"旧的已毁、新的没来"两空
        val recognizer = OfflineRecognizer(assetManager = null, config = config)
        cachedRecognizer?.let {
            try { it.release() } catch (_: Throwable) {}
        }
        cachedRecognizer = recognizer
        cachedModelPath = modelPath

        try { onLoadDone?.invoke() } catch (t: Throwable) {
            Log.w(TAG, "onLoadDone callback failed", t)
        }
        return recognizer
    }
}
