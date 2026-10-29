package com.jussicodes.music.desktop.local

import com.jussicodes.music.desktop.lyrics.LyricEntry
import com.jussicodes.music.desktop.lyrics.LyricRepository
import java.io.File

/**
 * 本地歌词（2.1.0）：与音频同目录、同名的 .lrc 文件尽力加载，
 * 解析复用在线歌词的 [LyricRepository.buildEntries]（含时间对齐口径）。
 */
object LocalLyrics {

    fun loadFor(audioFile: File): List<LyricEntry> {
        val dir = audioFile.parentFile ?: return emptyList()
        val lrc = File(dir, audioFile.nameWithoutExtension + ".lrc")
        if (!lrc.exists() || !lrc.isFile) return emptyList()
        val text = runCatching { lrc.readText(Charsets.UTF_8) }.getOrNull()
            ?: runCatching { lrc.readText(java.nio.charset.Charset.forName("GBK")) }.getOrNull()
            ?: return emptyList()
        if (text.isBlank()) return emptyList()
        return runCatching {
            LyricRepository.buildEntries(lrc = text, tlyric = "", yrc = "")
        }.getOrDefault(emptyList())
    }
}
