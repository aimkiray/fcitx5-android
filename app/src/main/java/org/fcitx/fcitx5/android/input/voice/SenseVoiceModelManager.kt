/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * SenseVoice 模型文件生命周期管理（进程级单例）：
 * - 模型目录：`<getExternalFilesDir(null)>/sensevoice/small-int8/`
 * - sha256 校验（移植自 BiBi LocalModelIntegrity，轻量版：无 SP 持久化缓存，每次启动重校）
 * - 下载器：okhttp 流式 GET → 临时文件 → sha256 校验 → rename 落位
 * - 进程内防重复：[downloadJob] 单例 Job，重复触发直接 return（不取消再起）
 * - [downloadState] StateFlow 供 Preference summary 订阅
 *
 * 实现者注意：
 * - 下载期间若 Activity/Service 销毁，Job 在 managerScope 内存活，不绑 LifecycleScope。
 * - 外存不可用（null）时回退 filesDir（不建议；外存路径兼容性更佳）。
 */
object SenseVoiceModelManager {

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(val progressPercent: Int) : DownloadState()
        object Verifying : DownloadState()
        object Ready : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> get() = _downloadState

    @Volatile private var downloadJob: Job? = null

    /** 在途 HTTP 调用；[cancelDownload] 经此真正中断阻塞中的 okhttp read。 */
    @Volatile private var activeCall: Call? = null

    /**
     * 下载互斥锁：被取消的旧 job 在锁内走完清理前，新下载不得入场，
     * 防两个 job 并发写同一 `.tmp` 损坏文件。
     */
    private val downloadMutex = Mutex()

    /**
     * 下载代次：startDownload 递增。被取消的旧 job 在 catch 收口时
     * 比对 gen，若已有新下载接管则不得回写 Idle 覆盖其 Downloading 状态。
     */
    @Volatile private var downloadGen = 0

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    /** 模型目录（需 Context，每次按需解析不缓存路径）。 */
    fun modelDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "${SenseVoiceModelSpec.SUBDIR}/${SenseVoiceModelSpec.VARIANT_DIR}")
    }

    /** 轻量就绪检查：仅文件存在 + 非零大小。主线程安全（无 IO 读内容）；
     *  供 VoiceInputManager.toggle() 前置检查与引擎 start() 使用。
     *  完整 sha256 校验只在 IO 协程（checkStatus / stop 路径）做。 */
    fun modelFilesExist(context: Context): Boolean {
        val dir = modelDir(context)
        return File(dir, SenseVoiceModelSpec.MODEL_FILE).length() > 0 &&
               File(dir, SenseVoiceModelSpec.TOKENS_FILE).length() > 0
    }

    /** 删除模型文件 + 卸载识别器（供"删除本地模型"设置项）；幂等。 */
    fun deleteModel(context: Context) {
        val appCtx = context.applicationContext
        // 先取消在途下载：防"删除后被在途下载复活"（cancelDownload 内含 tmp 清理）
        cancelDownload()
        val dir = modelDir(appCtx)
        // 白名单删除：仅删模型文件与下载残留 .tmp；目录内用户自放文件不动
        File(dir, SenseVoiceModelSpec.MODEL_FILE).delete()
        File(dir, SenseVoiceModelSpec.TOKENS_FILE).delete()
        cleanupTmpFiles(dir)
        dir.delete()  // 目录为空才删除成功，天然幂等
        SenseVoiceRecognizerManager.unload()
        _downloadState.value = DownloadState.Idle
    }

    /** 探测并校验模型文件（同步，IO 线程调用）。 */
    internal fun checkStatus(context: Context): SvModelStatus {
        val dir = modelDir(context.applicationContext)
        val modelFile = File(dir, SenseVoiceModelSpec.MODEL_FILE)
        val tokensFile = File(dir, SenseVoiceModelSpec.TOKENS_FILE)
        if (!modelFile.exists() || !tokensFile.exists()) return SvModelStatus.Missing
        // Size 快速预校（大文件节省 sha256 时间）
        if (modelFile.length() != SenseVoiceModelSpec.model.sizeBytes) {
            return SvModelStatus.IntegrityError(SenseVoiceModelSpec.MODEL_FILE)
        }
        if (tokensFile.length() != SenseVoiceModelSpec.tokens.sizeBytes) {
            return SvModelStatus.IntegrityError(SenseVoiceModelSpec.TOKENS_FILE)
        }
        // SHA-256 完整校验
        if (sha256Hex(tokensFile) != SenseVoiceModelSpec.tokens.sha256) {
            return SvModelStatus.IntegrityError(SenseVoiceModelSpec.TOKENS_FILE)
        }
        if (sha256Hex(modelFile) != SenseVoiceModelSpec.model.sha256) {
            return SvModelStatus.IntegrityError(SenseVoiceModelSpec.MODEL_FILE)
        }
        return SvModelStatus.Ready
    }

    /**
     * 触发后台下载（若已在下载中直接 return）。
     * [useMirror]：true 用 `https://gh-proxy.com/` 前缀镜像，false 直连 GitHub release。
     * 下载完成后自动更新 [_downloadState]。
     */
    fun startDownload(context: Context, useMirror: Boolean = false) {
        // 进程级 manager 不得在协程内长持调用方 Context（Activity）——统一
        // 收敛为 applicationContext（评审 B-M2），downloadAll/checkStatus 只用它
        val appCtx = context.applicationContext
        if (downloadJob?.isActive == true) return
        val gen = ++downloadGen
        _downloadState.value = DownloadState.Downloading(0)
        downloadJob = managerScope.launch {
            try {
                // 取消的旧 job 可能仍在锁内收尾，新下载排锁等待，避免并发写盘
                downloadMutex.withLock {
                    downloadAll(appCtx, useMirror)
                    coroutineContext.ensureActive()
                    _downloadState.value = DownloadState.Verifying
                    val status = checkStatus(appCtx)
                    // 写终态前确认未取消：cancel 期间 checkStatus 跑完后
                    // 不得用 Ready/Error 覆盖 cancelDownload 写下的 Idle
                    coroutineContext.ensureActive()
                    _downloadState.value = when (status) {
                        SvModelStatus.Ready -> DownloadState.Ready
                        is SvModelStatus.IntegrityError ->
                            DownloadState.Error(
                                appCtx.getString(
                                    R.string.voice_error_sv_integrity,
                                    status.fileName
                                )
                            )
                        SvModelStatus.Missing ->
                            DownloadState.Error("model files still missing after download")
                    }
                }
            } catch (ce: CancellationException) {
                cleanupTmpFiles(modelDir(appCtx))
                // 已有新下载接管时不得回写 Idle 覆盖其 Downloading 状态
                if (gen == downloadGen) {
                    _downloadState.value = DownloadState.Idle
                }
                throw ce
            } catch (t: Throwable) {
                // call.cancel() 中断阻塞 read 抛的是 IOException：若 job 已取消，
                // 按取消收口（cancelDownload 已写 Idle + 清 tmp），不得误报 Error
                coroutineContext.ensureActive()
                Timber.e(t, "SenseVoice download failed")
                cleanupTmpFiles(modelDir(appCtx))
                _downloadState.value = DownloadState.Error(t.message.orEmpty().take(120))
            }
        }
    }

    /** 取消正在进行的下载（真正中断阻塞中的 okhttp read + 清 `.tmp` 残留）。 */
    fun cancelDownload() {
        // 先断在途 HTTP：execute() 的阻塞 read 才能立即返回，
        // 否则 job 标记取消后仍要跑完整个文件才到取消点
        activeCall?.cancel()
        activeCall = null
        downloadJob?.cancel()
        downloadJob = null
        cleanupTmpFiles(modelDir(appContext))
        _downloadState.value = DownloadState.Idle
    }

    // ----- 私有实现 -----

    private fun cleanupTmpFiles(dir: File) {
        dir.listFiles()?.forEach { if (it.name.endsWith(".tmp")) it.delete() }
    }

    /**
     * 下载单个 zip → [ZipInputStream] 流式解压目标两文件（容忍任意嵌套目录名，
     * 按 entry.name 的 basename 匹配 `model.int8.onnx`/`tokens.txt`）→ 每文件
     * 写 `.tmp` → sha256 校验 → rename 落位。
     * 进度按目标文件已解压字节数 / 两文件 sizeBytes 总和折算 0–100%
     * （不依赖 Content-Length，chunked 传输亦可平滑推进）。
     * 解压字节累计超过两文件 sizeBytes 总和 1.5 倍即中止（防恶意 zip 撑磁盘）。
     * 找不到目标文件 → 抛异常走 Error 状态。
     */
    private suspend fun downloadAll(context: Context, useMirror: Boolean) {
        val dir = modelDir(context)
        dir.mkdirs()
        val url = SenseVoiceModelSpec.zipUrl(useMirror)
        val request = Request.Builder().url(url).get().build()
        val call = httpClient.newCall(request)
        activeCall = call
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code}: ${resp.message}")
                }
                val body = resp.body
                val targets = mapOf(
                    SenseVoiceModelSpec.MODEL_FILE to
                        (File(dir, "${SenseVoiceModelSpec.MODEL_FILE}.tmp") to SenseVoiceModelSpec.model),
                    SenseVoiceModelSpec.TOKENS_FILE to
                        (File(dir, "${SenseVoiceModelSpec.TOKENS_FILE}.tmp") to SenseVoiceModelSpec.tokens)
                )
                // 进度分母：两目标文件解压后 sizeBytes 总和（239,233,841 + 315,894）
                val totalExpectedBytes =
                    SenseVoiceModelSpec.model.sizeBytes + SenseVoiceModelSpec.tokens.sizeBytes
                // zip 解压安全上限：超限即中止，防恶意/损坏 zip 撑爆磁盘
                val maxInflatedBytes = totalExpectedBytes * 3 / 2
                var extractedBytes = 0L   // 目标文件已解压字节（进度分子）
                var inflatedBytes = 0L    // 全部解压字节（含 drain，防护计数）
                var lastPct = -1
                val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                val found = mutableSetOf<String>()
                ZipInputStream(body.byteStream().buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        coroutineContext.ensureActive()
                        val name = entry.name.substringAfterLast('/')
                        val target = targets[name]
                        if (target != null && !entry.isDirectory) {
                            val (tmpFile, _) = target
                            tmpFile.outputStream().use { out ->
                                while (true) {
                                    val n = zis.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    extractedBytes += n
                                    inflatedBytes += n
                                    if (inflatedBytes > maxInflatedBytes) {
                                        throw IllegalStateException(
                                            "zip inflated size exceeds limit"
                                        )
                                    }
                                    val pct = (extractedBytes * 100 / totalExpectedBytes)
                                        .toInt().coerceIn(0, 100)
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        _downloadState.value =
                                            DownloadState.Downloading(pct)
                                    }
                                }
                            }
                            // 每文件写完确认未取消（cancel 后经 catch 收口，不留半成品错觉）
                            coroutineContext.ensureActive()
                            found += name
                        } else {
                            // 跳过无关条目但仍须 drain（否则 nextEntry 提前结束）；
                            // drain 字节同样计入解压防护
                            while (true) {
                                val n = zis.read(buf)
                                if (n < 0) break
                                inflatedBytes += n
                                if (inflatedBytes > maxInflatedBytes) {
                                    throw IllegalStateException(
                                        "zip inflated size exceeds limit"
                                    )
                                }
                                coroutineContext.ensureActive()
                            }
                        }
                        // 每 entry 后确认未取消
                        coroutineContext.ensureActive()
                        entry = zis.nextEntry
                    }
                }
                if (found != targets.keys) {
                    throw IllegalStateException(
                        "zip missing entries: ${(targets.keys - found).joinToString(",")}"
                    )
                }
            }
        } finally {
            activeCall = null
        }
        // 逐文件 sha256 校验并落位（复用 verifyAndPlace）
        coroutineContext.ensureActive()
        verifyAndPlace(
            File(dir, "${SenseVoiceModelSpec.MODEL_FILE}.tmp"),
            File(dir, SenseVoiceModelSpec.MODEL_FILE),
            SenseVoiceModelSpec.model
        )
        coroutineContext.ensureActive()
        verifyAndPlace(
            File(dir, "${SenseVoiceModelSpec.TOKENS_FILE}.tmp"),
            File(dir, SenseVoiceModelSpec.TOKENS_FILE),
            SenseVoiceModelSpec.tokens
        )
    }

    /** tmp 文件 sha256 校验 → rename 落位（失败删 tmp 抛异常）。 */
    private fun verifyAndPlace(tmp: File, dest: File, spec: LocalModelFileSpec) {
        try {
            if (tmp.length() != spec.sizeBytes) {
                throw IllegalStateException("${spec.name} size mismatch: ${tmp.length()} != ${spec.sizeBytes}")
            }
            if (sha256Hex(tmp) != spec.sha256) {
                throw IllegalStateException("${spec.name} sha256 mismatch")
            }
            dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
