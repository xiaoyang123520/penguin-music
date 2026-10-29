package com.jussicodes.music.desktop.ui

import com.jussicodes.music.utils.NetProxy
import android.content.Context
import android.widget.Toast
import com.jussicodes.music.desktop.DesktopPrefs
import com.jussicodes.music.utils.dataStore
import com.rcmiku.ncmapi.api.player.PlayerApi
import com.rcmiku.ncmapi.api.player.SongLevel
import com.rcmiku.ncmapi.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * 歌曲下载（2.0.2 新增，右键菜单「下载」）：取链走与播放完全相同的音源
 * 链路——先试客户端下载链 /song/download/url/v1，拿不到再回退播放链
 * songPlayUrlV1（音源三选一在里面生效）。文件落 ~/Music/PenguinMusic/。
 *
 * 2.0.5（A11）缺陷组修复：此前同名文件静默覆盖、无进度无取消、异常时
 * 只删 0 字节半截文件、同歌并发互踩。现改为任务模型——每个下载是一个
 * [DownloadTask]（状态进 [tasks] 流，供下载管理页展示/重试/取消）：
 * - 落盘先写唯一文件名（重名自动加 (1)/(2) 后缀，绝不覆盖已有文件）；
 * - 下载中写 .part 临时文件，成功才 rename 成品；失败/取消删半截；
 * - 同一首歌同时只允许一个进行中的任务。
 */
object DownloadHelper {

    enum class Status { RUNNING, DONE, FAILED, CANCELED }

    data class DownloadTask(
        val id: Long,
        val song: Song,
        val fileName: String,
        val totalBytes: Long = -1,
        val downloadedBytes: Long = 0,
        val status: Status = Status.RUNNING,
        val error: String? = null,
        val file: File? = null,
    )

    val tasks = MutableStateFlow<List<DownloadTask>>(emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val activeSongIds = ConcurrentHashMap.newKeySet<Long>()

    fun downloadDir(): File {
        val music = File(System.getProperty("user.home"), "Music")
        val base = if (music.exists()) music else File(System.getProperty("user.home"))
        return File(base, "PenguinMusic").apply { mkdirs() }
    }

    fun downloadSong(context: Context, song: Song) {
        if (!activeSongIds.add(song.id)) {
            Toast.makeText(context, "《${song.name}》正在下载中", Toast.LENGTH_SHORT).show()
            return
        }
        val taskId = System.nanoTime()
        val placeholder = DownloadTask(id = taskId, song = song, fileName = song.name)
        tasks.value = listOf(placeholder) + tasks.value
        Toast.makeText(context, "开始下载：${song.name}", Toast.LENGTH_SHORT).show()
        val job = scope.launch {
            try {
                val file = downloadInternal(context, song) { total, read ->
                    updateTask(taskId) {
                        it.copy(totalBytes = total, downloadedBytes = read)
                    }
                }
                updateTask(taskId) {
                    it.copy(
                        status = Status.DONE, file = file, fileName = file.name,
                        downloadedBytes = file.length(),
                        totalBytes = file.length()
                    )
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "已下载：${file.name}", Toast.LENGTH_LONG).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                updateTask(taskId) { it.copy(status = Status.CANCELED, error = "已取消") }
            } catch (e: Exception) {
                updateTask(taskId) {
                    it.copy(status = Status.FAILED, error = e.message ?: "未知错误")
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context, "下载失败：${e.message ?: "未知错误"}", Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                activeSongIds.remove(song.id)
                jobs.remove(taskId)
            }
        }
        jobs[taskId] = job
    }

    fun cancel(taskId: Long) {
        jobs[taskId]?.cancel()
    }

    fun retry(context: Context, task: DownloadTask) {
        tasks.value = tasks.value.filterNot { it.id == task.id }
        downloadSong(context, task.song)
    }

    fun dismiss(taskId: Long) {
        tasks.value = tasks.value.filterNot { it.id == taskId }
    }

    private fun updateTask(taskId: Long, block: (DownloadTask) -> DownloadTask) {
        tasks.value = tasks.value.map { if (it.id == taskId) block(it) else it }
    }

    /** 唯一落盘名：目标已存在时追加 (1)、(2)…，绝不覆盖。 */
    private fun uniqueFile(dir: File, base: String, ext: String): File {
        var candidate = File(dir, "$base.$ext")
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($n).$ext")
            n++
        }
        return candidate
    }

    private suspend fun downloadInternal(
        context: Context,
        song: Song,
        onProgress: (total: Long, read: Long) -> Unit
    ): File {
        val prefs = context.dataStore.data.first()
        val level = SongLevel.entries.firstOrNull {
            it.value == prefs[DesktopPrefs.downloadQuality]
        } ?: SongLevel.EXHIGH
        val pseudoId = "${song.id}?fee=${song.fee}"
        val url = PlayerApi.songDownloadUrlV1(song.id.toString(), level)
            .getOrNull()?.data?.url?.takeIf { it.isNotBlank() }
            ?: PlayerApi.songPlayUrlV1(pseudoId, level)
                .getOrNull()?.data?.firstOrNull()?.url?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("拿不到下载地址（可能需要登录或会员）")
        val ext = url.substringBefore('?').substringAfterLast('.', "mp3")
            .takeIf { it.length in 2..5 } ?: "mp3"
        val artists = song.ar.joinToString("、") { it.name }.ifBlank { "未知歌手" }
        val safeName = "${song.name} - $artists"
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(120)
        val dir = downloadDir()
        val file = uniqueFile(dir, safeName, ext)
        val part = File(dir, file.name + ".part")
        val conn = NetProxy.openHttp(url)
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
        // 2.3.0（A6-06）：total/read 提到 try 外，流结束后显式校验。
        var total = -1L
        var read = 0L
        try {
            total = conn.contentLengthLong
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (read - lastReport >= 256 * 1024) {
                            lastReport = read
                            onProgress(total, read)
                        }
                    }
                    onProgress(total, read)
                }
            }
        } catch (e: Exception) {
            // 失败/取消：一律删半截 .part，不留残文件冒充成品。
            runCatching { part.delete() }
            throw e
        }
        // 2.3.0（A6-06）：显式检查 HTTP 状态码（≥400 时 getInputStream
        // 已抛异常兜底，这里补状态本身异常的口子）+ 完整性校验——
        // 此前流结束只判 part 非空，连接被干净截断（read < total）
        // 时截断文件会被 rename 成品、任务标 DONE 并 Toast「已下载」，
        // 与更新下载的长度+zip+MD5 三关口径不一致。
        val code = conn.responseCode
        if (code !in 200..299) {
            part.delete()
            throw IOException("下载 HTTP $code")
        }
        if (total > 0 && read != total) {
            part.delete()
            throw IOException("下载不完整：已下载 $read / $total 字节，请重试")
        }
        if (part.length() == 0L) {
            part.delete()
            throw IllegalStateException("下载内容为空")
        }
        if (!part.renameTo(file)) {
            part.copyTo(file, overwrite = false)
            part.delete()
        }
        return file
    }
}
