package com.jussicodes.music.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jussicodes.music.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Desktop version of AppUpdateManager.
 *
 * 2.0.5（A7）重写更新检查：此前查的是上游 jussichords 的 GitHub releases
 * 并与上游版本号 2.4.5 比对——企鹅音乐自己的更新永远触发不了，上游发
 * 安卓新版却会弹框、下载还指向 APK 发布页。现改查自有版本源
 * [PENGUIN_VERSION_URL]（{"version","url","notes"}），与桌面版自己的
 * [BuildConfig.VERSION_NAME] 比对；确认后由 UpdateDownloadService 真下载
 * zip 并打开所在文件夹（桌面版是解压覆盖安装，无应用内安装一说）。
 * GitHub 源类保留仅为兼容 UpdateDialog 的历史参数，不再参与检查。
 */

/** 企鹅音乐自有版本源（随每版一并上传到 dl 站点）。 */
const val PENGUIN_VERSION_URL = "https://tsemby.lblb.eu/dl/penguin-version.json"

@Serializable
data class PenguinVersionInfo(
    val version: String = "",
    val url: String = "",
    val notes: String = "",
    // 2.1.2（A3-06）：可选完整性校验值——发布时由协调者在
    // penguin-version.json 里带上 zip 的 MD5（小写 hex）；旧版 json 无此
    // 字段时为空串，下载后回退到「长度比对 + ZipFile 可读性」校验。
    val md5: String = "",
)

const val REPO_OWNER = "easyTIDollar"
const val REPO_NAME = "jussichords"
const val GITHUB_RELEASES_URL =
    "https://api.github.com/repos/${REPO_OWNER}/${REPO_NAME}/releases?per_page=10"
const val GITHUB_API_LATEST_URL =
    "https://api.github.com/repos/${REPO_OWNER}/${REPO_NAME}/releases/latest"
const val GITHUB_CANONICAL_DOWNLOAD_BASE =
    "https://github.com/${REPO_OWNER}/${REPO_NAME}/releases/download"
const val GITHUB_RELEASES_LATEST_PAGE =
    "https://github.com/${REPO_OWNER}/${REPO_NAME}/releases/latest"

sealed class UpdateSource {
    abstract val id: String
    abstract val name: String
    abstract val detail: String

    abstract fun fetchReleasesText(apiClient: OkHttpClient): String

    open fun toDownloadUrl(canonicalGithubDownloadUrl: String): String =
        canonicalGithubDownloadUrl

    abstract fun probeRequest(): Request
}

class GitHubUpdateSource(
    override val id: String,
    override val name: String,
    override val detail: String,
    private val prefix: String,
) : UpdateSource() {

    override fun fetchReleasesText(apiClient: OkHttpClient): String {
        val conn = NetProxy.openHttp(prefix + GITHUB_API_LATEST_URL)
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "jussichords/${BuildConfig.VERSION_NAME}")
        val code = conn.responseCode
        if (code !in 200..299) throw IOException("GitHub API HTTP $code")
        return conn.inputStream.bufferedReader().readText()
    }

    override fun toDownloadUrl(canonicalGithubDownloadUrl: String): String =
        if (prefix.isBlank()) canonicalGithubDownloadUrl else "$prefix$canonicalGithubDownloadUrl"

    override fun probeRequest(): Request =
        Request.Builder()
            .url(prefix + GITHUB_RELEASES_LATEST_PAGE)
            .head()
            .header("User-Agent", "jussichords/${BuildConfig.VERSION_NAME}")
            .build()
}

/** 全局下载源列表：gh-proxy 为主源，GitHub 直连兜底。 */
val downloadSources = listOf(
    GitHubUpdateSource("gh-proxy", "gh-proxy.com", "gh-proxy.com", "https://gh-proxy.com/"),
    GitHubUpdateSource("direct", "GitHub 直连", "直连", ""),
)

fun sourceById(sourceId: String?): UpdateSource? =
    downloadSources.firstOrNull { it.id == sourceId }

fun orderedSources(preferredSourceId: String?): List<UpdateSource> {
    val preferred = sourceById(preferredSourceId) ?: return downloadSources
    return listOf(preferred) + downloadSources.filterNot { it === preferred }
}

fun canonicalGitHubDownloadUrl(tag: String, apkName: String): String =
    "$GITHUB_CANONICAL_DOWNLOAD_BASE/$tag/$apkName"

/**
 * 默认的 ncmapi 后端地址列表（解灰旁路用）。用户可在设置页增删（DataStore apiServerListKey）。
 * 2.0.1：移除 https://api.jussichords.kdns.fr（2026-10-23 实测回 530，源站已下线）。
 */
val apiServers = listOf(
    "https://api.jussicodes.kdns.fr",
    "https://api.jussichords.indevs.in",
)

object AppUpdateManager {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 测速口径（2.0.2 改）：与真实取链完全一致——GET `<server>/song/url/match
     * ?id=<探针曲>`，正是 PlayerApi 解灰时打的同一路径/方法/响应协议。
     * 返回增强版协议 JSON（含 code 字段，200=匹配成功、500=引擎在但本次
     * 未匹配）即记可用；新版端点 404 时回退旧版 UNM 的 /match 再判一次。
     * 旧实现用 HEAD / 探根路径，与取链口径不一致（根路径活着但解灰端点
     * 死了也会误报可用），已废弃。
     */
    suspend fun measureApiServers(servers: List<String> = apiServers): List<ApiServerStatus> =
        withContext(Dispatchers.IO) {
            // 2.0.5（A12）：并发测速。此前串行 map 逐台测，每台最坏
            // 8s+20s 且双端点回退，多台时用户要等一两分钟；改 async
            // 并发后总时长≈最慢一台。结果顺序与入参一致（UI 按原序展示）。
            kotlinx.coroutines.coroutineScope {
                servers.map { server ->
                    async { probeApiServer(server) }
                }.awaitAll()
            }
        }

    private fun probeApiServer(server: String): ApiServerStatus {
        val base = server.trimEnd('/')
        val started = System.nanoTime()
        // 探针曲：Beyond《海阔天空》(347230)，VIP/灰色场景的常青曲，
        // 解灰引擎通常数秒内给出匹配；超时按端点真实耗时上限给 20s。
        val endpoints = listOf("$base/song/url/match?id=347230", "$base/match?id=347230")
        var lastError: Throwable? = null
        for ((index, endpoint) in endpoints.withIndex()) {
            try {
                val conn = NetProxy.openHttp(endpoint)
                conn.requestMethod = "GET"
                conn.connectTimeout = 8_000
                conn.readTimeout = 20_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "PenguinMusic/${BuildConfig.VERSION_NAME}")
                val code = conn.responseCode
                val elapsed = (System.nanoTime() - started) / 1_000_000
                if (code == 404 && index == 0) continue // 旧版 UNM 无此端点，回退 /match
                val body = runCatching {
                    (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.readText()?.take(4096)
                }.getOrNull().orEmpty()
                val protocolOk = body.contains("\"code\"") &&
                    (body.contains("\"data\"") || body.contains("\"url\""))
                return if (protocolOk) {
                    val matched = body.contains("\"code\":200") || body.contains("\"code\": 200")
                    ApiServerStatus(
                        server, elapsed, true,
                        if (matched) "可用 · ${elapsed} ms" else "可用 · ${elapsed} ms（探针曲未匹配）"
                    )
                } else {
                    ApiServerStatus(server, elapsed, false, "不可用 · HTTP $code")
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        return ApiServerStatus(
            server = server,
            latencyMs = null,
            available = false,
            message = when (lastError) {
                is java.net.SocketTimeoutException -> "超时"
                is java.net.UnknownHostException -> "无法解析"
                is java.net.ConnectException -> "连接被拒"
                else -> "不可用"
            }
        )
    }

    /** 取后端中「可用且延迟最低」的，全不可用时返回 null。 */
    fun pickFastestApiServer(statuses: List<ApiServerStatus>): String? =
        statuses
            .filter { it.available && it.latencyMs != null }
            .minByOrNull { it.latencyMs!! }
            ?.server

    /** 查自有版本源 penguin-version.json，与本机桌面版版本比对。 */
    suspend fun checkUpdate(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = NetProxy.openHttp(PENGUIN_VERSION_URL)
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "PenguinMusic/${BuildConfig.VERSION_NAME}")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("版本源 HTTP $code")
            val text = conn.inputStream.bufferedReader().readText()
            val remote = json.decodeFromString(PenguinVersionInfo.serializer(), text)
            val found = remote.version.removePrefix("v")
            // 2.2.0（A5-15）：顺带清理其它版本的遗留 .part（当前远端
            // 版本的 .part 是可续传进度，不动）。
            if (found.isNotBlank()) cleanupStaleUpdateParts(found)
            if (found.isNotBlank() && remote.url.isNotBlank() &&
                isNewer(found, BuildConfig.VERSION_NAME)
            ) {
                UpdateInfo(
                    versionName = found,
                    releaseName = "企鹅音乐 $found",
                    body = remote.notes,
                    apkName = "",
                    apkSize = 0,
                    downloadUrl = remote.url,
                    md5 = remote.md5.trim().lowercase(),
                )
            } else {
                null
            }
        }
    }

    private fun isNewer(found: String, installed: String): Boolean {
        fun parts(v: String): List<Int> =
            v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(found)
        val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** 清理其它版本的遗留 .part（2.2.0，A5-15）：只保留 keepVersion 的续传进度。 */
    private fun cleanupStaleUpdateParts(keepVersion: String) {
        runCatching {
            val downloads = File(System.getProperty("user.home"), "Downloads")
            val keepName = "PenguinMusic-Windows-$keepVersion.zip.part"
            downloads.listFiles()?.forEach { f ->
                if (f.isFile && f.name.startsWith("PenguinMusic-Windows-") &&
                    f.name.endsWith(".zip.part") && f.name != keepName
                ) {
                    runCatching { f.delete() }
                }
            }
        }
    }

    /** 解析 "Content-Range: bytes a-b/TOTAL" 的 TOTAL；无/非法返回 null。 */
    private fun parseContentRangeTotal(header: String?): Long? =
        header?.substringAfter('/', "")?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    /**
     * 2.3.0（A6-04）：解析 Content-Range 起点，如 "bytes=524288-720582/720583"
     * → 524288；无起点（星号形态）或解析失败返回 null。
     */
    private fun parseContentRangeStart(header: String?): Long? =
        header?.substringBefore('/')?.trim()?.removePrefix("bytes=")?.trim()
            ?.substringBefore('-')?.trim()?.toLongOrNull()

    /**
     * 真下载更新包（2.0.5）：桌面版分发物是 zip，下载到 ~/Downloads 后由
     * UpdateDownloadService 打开所在文件夹，用户解压覆盖安装。
     * 2.1.2（A3-06）：下载后强制完整性校验（长度比对 + zip 全条目可读 +
     * 版本源 md5 存在时 MD5 比对），不过则删文件抛错，绝不报完成。
     * 2.2.0（A5-15 + A4-09/L7）：落盘改 .part + 断点续传——先写
     * `PenguinMusic-Windows-<版本>.zip.part`，已有进度时带 Range 头续
     * 传（206 续写；200=服务端不支持续传，清零重下；416=.part 已
     * 无效，删后重来；其它状态码显式报错）。2.3.0（A6-04）：206 先
     * 核对 Content-Range 起点是否等于本地 .part 长度，不等同样清零
     * 重下（防接到错误偏移上整包作废）。流干净关闭但字节不足时
     * 在本次调用内继续续传（≤3 次）。网络中断抛错但**保留** .part，
     * 下次调用自动续；完整性三关对 .part 执行，全过才改名为正式名；
     * 完整性失败删 .part（内容不可信，续传只会继续坏下去）。
     * 正式名文件因此永远是校验通过的完整包，下载中崩溃不再留半截
     * 正式包冒充完整更新（A5-15 的原始问题）。
     */
    suspend fun downloadApk(
        updateInfo: UpdateInfo,
        sourceId: String,
        onProgress: (DownloadProgress) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val downloads = File(System.getProperty("user.home"), "Downloads")
                .apply { mkdirs() }
            val target = File(downloads, "PenguinMusic-Windows-${updateInfo.versionName}.zip")
            val part = File(downloads, target.name + ".part")
            var existing = if (part.isFile) part.length() else 0L
            var read = existing
            var total = -1L
            var attempts = 0
            var completed = false
            while (!completed) {
                attempts++
                if (attempts > 3) {
                    throw IOException("下载多次中断，进度已保存（${part.length()} 字节），请稍后重试")
                }
                val conn = NetProxy.openHttp(updateInfo.downloadUrl)
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("User-Agent", "PenguinMusic/${BuildConfig.VERSION_NAME}")
                if (existing > 0) {
                    conn.setRequestProperty("Range", "bytes=$existing-")
                }
                val code = conn.responseCode
                if (code == 416 && existing > 0) {
                    // .part 已比服务端文件还长/位置无效：删后从 0 重来。
                    conn.disconnect()
                    part.delete()
                    existing = 0
                    read = 0
                    continue
                }
                if (code !in 200..299) {
                    conn.disconnect()
                    throw IOException("下载 HTTP $code")
                }
                val isPartial = code == 206
                if (isPartial && existing > 0) {
                    // 2.3.0（A6-04）：206 必须核对 Content-Range 起点是否
                    // 等于本地 .part 长度——异常代理/文件被替换时起点可能
                    // 错位，接到错误偏移上整包作废。不等时按 200 路径
                    // 清零重下（删 .part、下一轮不带 Range）。
                    val rangeStart = parseContentRangeStart(
                        conn.getHeaderField("Content-Range")
                    )
                    if (rangeStart != null && rangeStart != existing) {
                        conn.disconnect()
                        part.delete()
                        existing = 0
                        read = 0
                        continue
                    }
                }
                if (existing > 0 && !isPartial) {
                    // 服务端忽略 Range 回了全量：清零重下。
                    existing = 0
                    read = 0
                }
                total = if (isPartial) {
                    parseContentRangeTotal(conn.getHeaderField("Content-Range"))
                        ?: run {
                            val cl = conn.contentLengthLong
                            if (cl >= 0) existing + cl else -1L
                        }
                } else {
                    conn.contentLengthLong
                }
                if (total > 0 && existing > total) {
                    conn.disconnect()
                    part.delete()
                    existing = 0
                    read = 0
                    continue
                }
                try {
                    conn.inputStream.use { input ->
                        java.io.RandomAccessFile(part, "rw").use { raf ->
                            if (existing == 0L) raf.setLength(0)
                            raf.seek(existing)
                            val buf = ByteArray(256 * 1024)
                            var lastReport = read
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                raf.write(buf, 0, n)
                                read += n
                                if (read - lastReport >= 1024 * 1024) {
                                    lastReport = read
                                    onProgress(
                                        DownloadProgress(
                                            read, total,
                                            if (total > 0) read.toFloat() / total else 0f
                                        )
                                    )
                                }
                            }
                        }
                    }
                } catch (e: IOException) {
                    // 网络中断：.part 保留，下次调用从断点续传（不删）。
                    throw IOException(
                        "下载中断（已保存 ${part.length()} 字节，下次将断点续传）：${e.message}",
                        e
                    )
                }
                if (total > 0 && read < total) {
                    // 流干净关闭但不完整：以新断点在本调用内继续续传。
                    existing = read
                    continue
                }
                completed = true
            }
            // 完整性三关对 .part 执行，全过才改名。
            try {
                if (part.length() == 0L) throw IOException("下载内容为空")
                if (total > 0 && part.length() != total) {
                    throw IOException(
                        "下载不完整：收到 ${part.length()} / $total 字节，文件可能已损坏，请重试"
                    )
                }
                verifyZipReadable(part)
                val expectedMd5 = updateInfo.md5.trim().lowercase()
                if (expectedMd5.isNotEmpty()) {
                    val actualMd5 = md5Hex(part)
                    if (!actualMd5.equals(expectedMd5, ignoreCase = true)) {
                        throw IOException(
                            "更新包校验失败（MD5 不匹配），文件可能已损坏，请重试"
                        )
                    }
                }
            } catch (e: Exception) {
                part.delete()
                throw e
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            // 校验全过、改名完成之后才报完成。
            onProgress(DownloadProgress(read, total, 1f, done = true))
            target
        }
    }

    /** zip 可完整打开且每个条目都能读完（CRC 在读条目时校验）。 */
    private fun verifyZipReadable(file: File) {
        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                var count = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    count++
                    if (!entry.isDirectory) {
                        zip.getInputStream(entry).use { input ->
                            val buf = ByteArray(256 * 1024)
                            while (input.read(buf) >= 0) {
                                // 读完整个条目，触发 CRC/结构校验。
                            }
                        }
                    }
                }
                if (count == 0) throw IOException("更新包内没有任何文件")
            }
        } catch (e: ZipException) {
            throw IOException("更新包损坏或不完整（无法打开 zip）：${e.message}", e)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("更新包损坏或不完整（无法打开 zip）：${e.message}", e)
        }
    }

    /** 文件 MD5（小写 hex），与历版交付口径的 MD5 对齐。 */
    private fun md5Hex(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun createInstallIntent(context: Context, apkFile: File): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_RELEASES_LATEST_PAGE))

    fun installApk(context: Context, apkFile: File): Result<Unit> =
        Result.failure(UnsupportedOperationException("桌面版不支持应用内安装更新"))

    suspend fun measureDownloadSources(): List<UpdateSourceStatus> =
        withContext(Dispatchers.IO) {
            downloadSources.map { source ->
                val started = System.nanoTime()
                val ok = runCatching {
                    val conn = NetProxy.openHttp(source.toDownloadUrl(GITHUB_RELEASES_LATEST_PAGE))
                    conn.requestMethod = "HEAD"
                    conn.connectTimeout = 3_000
                    conn.readTimeout = 3_000
                    conn.instanceFollowRedirects = false
                    conn.responseCode in 200..399
                }.getOrDefault(false)
                val latency = (System.nanoTime() - started) / 1_000_000
                UpdateSourceStatus(
                    source = source,
                    latencyMs = if (ok) latency else null,
                    available = ok,
                    message = if (ok) "${latency}ms" else "不可用",
                )
            }
        }
}

data class UpdateSourceStatus(
    val source: UpdateSource,
    val latencyMs: Long?,
    val available: Boolean,
    val message: String,
)

/** 预设 ncmapi 后端的 Ping 结果：URL、延迟（ms）、是否可用、说明文案。 */
data class ApiServerStatus(
    val server: String,
    val latencyMs: Long?,
    val available: Boolean,
    val message: String,
)

@Serializable
data class UpdateInfo(
    val versionName: String,
    val releaseName: String,
    val body: String,
    val apkName: String,
    val apkSize: Long,
    val downloadUrl: String,
    // 2.1.2（A3-06）：版本源带的可选 MD5（空串=未提供，下载后只做
    // 长度/zip 可读性校验）。尾加字段带默认值，不破坏既有构造点。
    val md5: String = "",
)

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val progress: Float,
    val done: Boolean = false,
)

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String = "",
    val body: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(
    val name: String,
    val size: Long = 0,
    @SerialName("browser_download_url") val downloadUrl: String,
)
