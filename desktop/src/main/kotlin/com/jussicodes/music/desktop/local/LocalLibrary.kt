package com.jussicodes.music.desktop.local

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.jussicodes.music.constants.MediaSessionConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 本地曲库里的一首歌（标签缺失用文件名兜底，见 [LocalLibrary.scan]）。 */
@Serializable
data class LocalTrack(
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val format: String
) {
    val fileName: String get() = File(path).name
}

/**
 * 本地音乐曲库（2.1.0）：用户添加文件夹 → 递归扫描 → 读标签入库。
 * 曲库与文件夹列表持久化在 filesDir/local-library.json（扫描结果缓存，
 * 每次进页面/点刷新时后台重扫，文件增删能跟上）。
 *
 * 本地歌进队列时 mediaId = "local:<绝对路径>"、uri = file://，
 * DesktopPlayer 见此标记走本地解码分支，不进在线音源级联；
 * 解析缓存键 [com.jussicodes.music.playback] 的在线链路完全不碰它。
 */
object LocalLibrary {

    const val MEDIA_ID_PREFIX = "local:"
    private val AUDIO_EXTS = setOf("mp3", "flac", "wav", "m4a")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Persisted(
        val folders: List<String> = emptyList(),
        val tracks: List<LocalTrack> = emptyList()
    )

    val folders = MutableStateFlow<List<String>>(emptyList())
    val tracks = MutableStateFlow<List<LocalTrack>>(emptyList())
    val scanning = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var storeFile: File? = null

    fun init(context: Context) {
        if (storeFile != null) return
        val file = File(context.filesDir, "local-library.json")
        storeFile = file
        runCatching {
            if (file.exists()) {
                val persisted = json.decodeFromString<Persisted>(file.readText())
                folders.value = persisted.folders
                tracks.value = persisted.tracks
            }
        }
    }

    fun addFolder(dir: File) {
        val path = dir.absolutePath
        if (folders.value.contains(path)) {
            rescan()
            return
        }
        folders.value = folders.value + path
        persist()
        rescan()
    }

    fun removeFolder(path: String) {
        folders.value = folders.value - path
        tracks.value = tracks.value.filterNot { it.path.startsWith(path + File.separator) }
        persist()
    }

    /** 后台全量重扫已添加的文件夹（去重按绝对路径，结果按 歌手/专辑/标题 排序）。 */
    fun rescan() {
        val dirs = folders.value.map { File(it) }
        if (dirs.isEmpty()) return
        scope.launch {
            scanning.value = true
            try {
                val found = withContext(Dispatchers.IO) { scan(dirs) }
                tracks.value = found
                persist()
            } finally {
                scanning.value = false
            }
        }
    }

    private fun scan(dirs: List<File>): List<LocalTrack> {
        val files = mutableListOf<File>()
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                .onEnter { it.name != "." && !it.name.startsWith(".") }
                .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTS }
                .forEach { files.add(it) }
        }
        return files.distinctBy { it.absolutePath }
            .map { file ->
                val tag = LocalTags.read(file)
                LocalTrack(
                    path = file.absolutePath,
                    title = tag.title ?: file.nameWithoutExtension,
                    artist = tag.artist ?: "未知歌手",
                    album = tag.album ?: "未知专辑",
                    durationMs = tag.durationMs,
                    format = file.extension.lowercase()
                )
            }
            .sortedWith(compareBy({ it.artist }, { it.album }, { it.title }))
    }

    private fun persist() {
        val file = storeFile ?: return
        runCatching {
            file.writeText(
                json.encodeToString(Persisted(folders.value, tracks.value))
            )
        }
    }

    fun LocalTrack.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setUri("file://" + path.replace('\\', '/'))
            .setMediaId(MEDIA_ID_PREFIX + path)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setExtras(Bundle().apply {
                        putLong(MediaSessionConstants.EXTRA_DURATION_MS, durationMs)
                        putString(MediaSessionConstants.EXTRA_SOURCE_NAME, "本地音乐")
                    })
                    .build()
            )
            .build()

    fun List<LocalTrack>.toMediaItems(): List<MediaItem> = map { it.toMediaItem() }

    /** mediaId 是否为本地歌；是则返回文件。 */
    fun fileOf(mediaId: String?): File? {
        if (mediaId == null || !mediaId.startsWith(MEDIA_ID_PREFIX)) return null
        return File(mediaId.removePrefix(MEDIA_ID_PREFIX))
    }
}
