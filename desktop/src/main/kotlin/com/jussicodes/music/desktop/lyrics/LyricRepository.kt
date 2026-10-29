package com.jussicodes.music.desktop.lyrics

import com.jussicodes.music.utils.parseLrc
import com.rcmiku.ncmapi.api.player.PlayerApi

/** 一个逐字片段（来自 YRC）。start/dur 为毫秒。 */
data class LyricWord(val start: Long, val dur: Long, val text: String)

/** 一行歌词：时间、原文、翻译（可空）、罗马音（可空）、逐字（可空，YRC 才有）。 */
data class LyricEntry(
    val time: Long,
    val text: String,
    val translation: String? = null,
    val words: List<LyricWord>? = null,
    val roma: String? = null
)

/**
 * 歌词仓库：拉 /lyric/new，优先用 YRC 逐字（酷狗式卡拉OK变色的数据基础），
 * 没有 YRC 时用 LRC 并按行时长做整行扫光近似。带进程内缓存。
 */
object LyricRepository {
    // #11（2.1.2）：缓存改线程安全 + 上限淘汰。歌词被全屏页与桌面歌词并发共用，
    // 原 mutableMapOf 无同步；现用锁保护 accessOrder 的 LinkedHashMap，
    // 超过上限时淘汰最久未访问的一条。网络请求在锁外执行，不阻塞其他读者。
    private const val MAX_CACHE_ENTRIES = 200
    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<Long, List<LyricEntry>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Long, List<LyricEntry>>?
        ): Boolean = size > MAX_CACHE_ENTRIES
    }

    suspend fun load(songId: Long): List<LyricEntry> {
        synchronized(cacheLock) { cache[songId] }?.let { return it }
        val result = PlayerApi.songLyric(songId)
        // #11：请求失败不写缓存——否则一次断网会被当成「这首歌无歌词」永久缓存，
        // 且调用方拿到空列表只是本次降级，下次 load 仍可重试。
        if (result.isFailure) return emptyList()
        // 接口成功（含该歌确实无歌词、各字段为空）才写缓存，空结果也可缓存。
        val response = result.getOrNull()
        val entries = buildEntries(
            lrc = response?.lrc?.lyric.orEmpty(),
            tlyric = response?.tlyric?.lyric.orEmpty(),
            yrc = response?.yrc?.lyric.orEmpty(),
            romalrc = response?.romalrc?.lyric.orEmpty()
        )
        synchronized(cacheLock) { cache[songId] = entries }
        return entries
    }

    fun buildEntries(
        lrc: String,
        tlyric: String,
        yrc: String,
        romalrc: String = ""
    ): List<LyricEntry> {
        val translations = tlyric.takeIf { it.isNotBlank() }
            ?.let { runCatching { it.parseLrc() }.getOrNull() }
            ?.filter { it.text.isNotBlank() }
            .orEmpty()
        // 罗马音（2.0.5，C6）：/lyric/new 的 romalrc 一直在返回、双端都没用。
        // 格式同 LRC，按与译文相同的「时间戳最近行（80ms 内）」口径对齐。
        val romas = romalrc.takeIf { it.isNotBlank() }
            ?.let { runCatching { it.parseLrc() }.getOrNull() }
            ?.filter { it.text.isNotBlank() }
            .orEmpty()
        fun lineAt(lines: List<com.jussicodes.music.utils.LrcLine>, time: Long): String? {
            if (lines.isEmpty()) return null
            lines.firstOrNull { it.time == time }?.let { return it.text }
            // 时间戳差几十毫秒是常态：取最近的一行（80ms 内）
            return lines.minByOrNull { kotlin.math.abs(it.time - time) }
                ?.takeIf { kotlin.math.abs(it.time - time) <= 80 }
                ?.text
        }
        fun translationAt(time: Long): String? = lineAt(translations, time)
        fun romaAt(time: Long): String? = lineAt(romas, time)

        val yrcEntries = if (yrc.isNotBlank()) parseYrc(yrc) else emptyList()
        if (yrcEntries.isNotEmpty()) {
            return yrcEntries.map {
                it.copy(translation = translationAt(it.time), roma = romaAt(it.time))
            }
        }
        if (lrc.isBlank()) return emptyList()
        return runCatching { lrc.parseLrc() }.getOrDefault(emptyList())
            .filter { it.text.isNotBlank() }
            .map {
                LyricEntry(
                    time = it.time, text = it.text,
                    translation = translationAt(it.time), roma = romaAt(it.time)
                )
            }
    }

    private val yrcLineRegex = Regex("""^\[(\d+),(\d+)]\s*(.*)$""")
    private val yrcWordRegex = Regex("""\((\d+),(\d+),\d+\)([^(]*)""")

    fun parseYrc(yrc: String): List<LyricEntry> {
        val out = mutableListOf<LyricEntry>()
        for (rawLine in yrc.lineSequence()) {
            val line = rawLine.trim()
            val m = yrcLineRegex.find(line) ?: continue
            val start = m.groupValues[1].toLongOrNull() ?: continue
            val rest = m.groupValues[3]
            val words = mutableListOf<LyricWord>()
            for (wm in yrcWordRegex.findAll(rest)) {
                val ws = wm.groupValues[1].toLongOrNull() ?: continue
                val wd = wm.groupValues[2].toLongOrNull() ?: 0L
                val text = wm.groupValues[3]
                if (text.isNotEmpty()) words += LyricWord(ws, wd, text)
            }
            val text = if (words.isNotEmpty()) words.joinToString("") { it.text } else rest.trim()
            if (text.isBlank()) continue
            out += LyricEntry(time = start, text = text, words = words.takeIf { it.isNotEmpty() })
        }
        return out.sortedBy { it.time }
    }

    /** 当前行下标：最后一个 time <= position 的行；无则 -1。 */
    fun indexAt(entries: List<LyricEntry>, positionMs: Long): Int {
        if (entries.isEmpty()) return -1
        var idx = -1
        for (i in entries.indices) {
            if (entries[i].time <= positionMs) idx = i else break
        }
        return idx
    }
}
