package com.jussicodes.music.utils

import com.rcmiku.ncmapi.model.Song
import com.rcmiku.ncmapi.utils.json
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Desktop shadow of the app SongListUtil (build.sh EXCL excludes the app
 * copy). Identical behavior except persistence hardening (2.0.5 A6):
 * atomic tmp+rename writes and a corruption-tolerant load.
 */
@Serializable
data class SongSourceMeta(
    val sourceId: Long = 0L,
    val sourceName: String = "list",
    val sourceType: String? = null,
    val navId: Long = 0L
)

object SongListUtil {

    private const val SONG_LIST = "song_list.json"
    private const val SOURCE_META = "song_list_source.json"
    private var file: File? = null
    private var songList: List<Song> = emptyList()
    // The in-memory list is the source of truth for playback resumption;
    // persisting it to disk runs on the IO dispatcher so tapping a song in
    // a large playlist never blocks the UI thread with a full-list JSON
    // encode + file write.
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun init(file: File) {
        if (!file.exists()) file.mkdir()
        this.file = file
    }

    fun saveSong(song: Song) {
        val updatedSongList = songList.toMutableList()
        updatedSongList.add(song)
        saveSongList(updatedSongList)
    }

    fun removeSong(songId: Long) {
        val updatedSongList = songList.toMutableList()
        updatedSongList.removeIf { it.id == songId }
        songList = updatedSongList
        saveSongList(updatedSongList)
    }

    fun saveSong(song: Song, index: Int) {
        val updatedSongList = songList.toMutableList()
        updatedSongList.add(index, song)
        saveSongList(updatedSongList)
    }


    fun saveSongList(songList: List<Song>) {
        this.songList = songList
        ioScope.launch {
            val target = file ?: return@launch
            // 2.0.5（A6）：原子写——先写 .tmp 再 rename 覆盖。此前直接
            // writeText 目标文件，强杀/断电写到一半会留下截断 JSON，
            // 下次启动 loadSongList 解码抛异常、队列恢复整条断链。
            runCatching {
                val dest = File(target, SONG_LIST)
                val tmp = File(target, "$SONG_LIST.tmp")
                tmp.writeText(json.encodeToString<List<Song>>(songList))
                if (!tmp.renameTo(dest)) {
                    dest.writeText(tmp.readText())
                    tmp.delete()
                }
            }
        }
    }

    fun loadSongList(): List<Song>? {
        val songListJson = File(file, SONG_LIST)
        if (!songListJson.exists()) return null
        // 2.0.5（A6）：读端防腐——文件损坏时返回 null（当作无队列），
        // 不再把解码异常抛给 Player.init 断掉启动恢复链。
        return runCatching {
            json.decodeFromString<List<Song>>(songListJson.readText())
        }.onSuccess { songList = it }.getOrNull()
    }

    // The last queue's source metadata (label shown above the player),
    // persisted so it survives an app restart like the song list itself.
    fun saveSongSource(meta: SongSourceMeta) {
        ioScope.launch {
            val dir = file ?: return@launch
            runCatching {
                val dest = File(dir, SOURCE_META)
                val tmp = File(dir, "$SOURCE_META.tmp")
                tmp.writeText(json.encodeToString(meta))
                if (!tmp.renameTo(dest)) {
                    dest.writeText(tmp.readText())
                    tmp.delete()
                }
            }
        }
    }

    fun loadSongSource(): SongSourceMeta? {
        val target = file ?: return null
        val sourceJson = File(target, SOURCE_META)
        if (!sourceJson.exists()) return null
        return runCatching { json.decodeFromString<SongSourceMeta>(sourceJson.readText()) }.getOrNull()
    }
}
