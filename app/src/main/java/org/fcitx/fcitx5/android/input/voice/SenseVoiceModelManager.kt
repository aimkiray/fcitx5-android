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
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * sha256 结果内存缓存：key = (absolutePath, length, lastModified)。
     * 每次会话 stop() 都跑 checkStatus（228MB 全量 sha256 ~2s），
     * 文件未变时命中缓存直接复用；deleteModel / 下载落位时失效。
     */
    private val hashCache =
        ConcurrentHashMap<Triple<String, Long, Long>, String>()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    /** 模型目录（需 Context，每次按需解析不缓存路径）。 */
    fun modelDir(context: Context): File {
        // 个别 ROM/存储状态下 getExternalFilesDir 会抛 RuntimeException
        // （而非返回 null）：runCatching 兜底回退 filesDir，防设置页/引擎崩溃
        val base = runCatching { context.getExternalFilesDir(null) }.getOrNull()
            ?: context.filesDir
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
        // 经 downloadMutex 串行化：被取消的旧 job 可能仍在锁内跑 verifyAndPlace
        // 收尾，必须等它出锁后再删——否则"先删后 rename"把模型复活（E-L3）。
        // verifyAndPlace 的 ensureActive 保证被取消的 job 不再做 rename
        managerScope.launch {
            downloadMutex.withLock {
                val dir = modelDir(appCtx)
                // 白名单删除：仅删模型文件与下载残留 .tmp；目录内用户自放文件不动
                File(dir, SenseVoiceModelSpec.MODEL_FILE).delete()
                File(dir, SenseVoiceModelSpec.TOKENS_FILE).delete()
                cleanupTmpFiles(dir)
                dir.delete()  // 目录为空才删除成功，天然幂等
                hashCache.clear()
                SenseVoiceRecognizerManager.unload()
                // 竞态：新下载若已接管则不得回写 Idle 覆盖其 Downloading 状态
                if (downloadJob?.isActive != true) {
                    _downloadState.value = DownloadState.Idle
                }
            }
        }
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
        // SHA-256 完整校验（经 hashCache：文件未变时跳过 ~2s 全量哈希）
        if (sha256HexCached(tokensFile) != SenseVoiceModelSpec.tokens.sha256) {
            return SvModelStatus.IntegrityError(SenseVoiceModelSpec.TOKENS_FILE)
        }
        if (sha256HexCached(modelFile) != SenseVoiceModelSpec.model.sha256) {
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
        // 竞态：新下载可能已在本函数执行间隙接管（downloadJob/activeCall 被
        // 重新赋值、状态已写 Downloading），此时回写 Idle 会覆盖在途下载
        if (downloadJob?.isActive == true || activeCall != null) return
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
        // 上次进程死亡/强杀可能遗留 .tmp 半成品：先清再下，防陈旧残件
        // 混入本轮校验落位（正常取消路径已清，此处兜进程级异常）
        cleanupTmpFiles(dir)
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
    private suspend fun verifyAndPlace(tmp: File, dest: File, spec: LocalModelFileSpec) {
        // destTouched 标记 dest.delete() 已执行：catch 据此区分"rename/copyTo
        // 半途产生的 dest 残件"与"落位前完好存在的旧文件"，仅前者允许删除——
        // 否则 sha 不匹配/取消时会误删完好的旧模型
        var destTouched = false
        try {
            if (tmp.length() != spec.sizeBytes) {
                throw IllegalStateException("${spec.name} size mismatch: ${tmp.length()} != ${spec.sizeBytes}")
            }
            if (sha256Hex(tmp) != spec.sha256) {
                throw IllegalStateException("${spec.name} sha256 mismatch")
            }
            // delete/rename 是不可逆点，各先确认未取消：收窄"dest 已删、
            // tmp 已清"的两空窗口（与 deleteModel 的锁串行化配合防复活）
            coroutineContext.ensureActive()
            dest.delete()
            destTouched = true
            coroutineContext.ensureActive()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            // 落位完成：清掉该路径的旧 sha256 缓存条目
            // （新文件元数据本就不命中，此为防陈旧条目堆积）
            hashCache.keys.removeIf { it.first == dest.absolutePath }
        } catch (t: Throwable) {
            tmp.delete()
            if (destTouched) dest.delete()
            throw t
        }
    }

    /**
     * 带内存缓存的 sha256：key = (absolutePath, length, lastModified)，
     * 文件未变时跳过全量读（每次会话省 ~2s）。getOrPut 非原子，
     * 极端并发重复计算无害（结果一致，后写覆盖）。
     */
    private fun sha256HexCached(file: File): String {
        val key = Triple(file.absolutePath, file.length(), file.lastModified())
        return hashCache.getOrPut(key) { sha256Hex(file) }
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
