package com.jussicodes.music.desktop.lyrics

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * 逐字扫光文本构造（原 DesktopLyricWindow 的 Compose 版逻辑，2.0.3 拆出
 * 独立文件：全屏播放页 FullPlayer 继续用它；AWT 桌面歌词窗有自己的
 * Graphics2D 等价实现，见 DesktopLyricAwtWindow）。
 */
internal fun buildSweptText(
    entry: LyricEntry,
    position: Long,
    lineEnd: Long,
    wordByWord: Boolean,
    playedColor: Color,
    unplayedColor: Color
) = buildAnnotatedString {
    val words = entry.words
    if (wordByWord && !words.isNullOrEmpty()) {
        for (word in words) {
            val wordEnd = word.start + word.dur
            when {
                position >= wordEnd -> withStyle(SpanStyle(color = playedColor)) { append(word.text) }
                position <= word.start -> withStyle(SpanStyle(color = unplayedColor)) { append(word.text) }
                else -> {
                    // 正在唱的词按字符进度切分（酷狗式逐字变色）
                    val frac = if (word.dur > 0) {
                        ((position - word.start).toFloat() / word.dur).coerceIn(0f, 1f)
                    } else 0f
                    val cut = (word.text.length * frac).toInt().coerceIn(0, word.text.length)
                    if (cut > 0) withStyle(SpanStyle(color = playedColor)) { append(word.text.substring(0, cut)) }
                    if (cut < word.text.length) {
                        withStyle(SpanStyle(color = unplayedColor)) { append(word.text.substring(cut)) }
                    }
                }
            }
        }
    } else {
        // 无逐字数据：按本行时间进度把整行按字符比例扫过去
        val total = (lineEnd - entry.time).coerceAtLeast(1)
        val frac = ((position - entry.time).toFloat() / total).coerceIn(0f, 1f)
        val cut = (entry.text.length * frac).toInt().coerceIn(0, entry.text.length)
        if (cut > 0) withStyle(SpanStyle(color = playedColor)) { append(entry.text.substring(0, cut)) }
        if (cut < entry.text.length) {
            withStyle(SpanStyle(color = unplayedColor)) { append(entry.text.substring(cut)) }
        }
    }
}
