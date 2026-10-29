package com.jussicodes.music.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** 网易云 CDN 缩略参数：给图片 URL 拼 ?param=WxH，避免列表里拉原图。 */
fun String?.thumb(size: Int): String? {
    val url = this?.takeIf { it.isNotBlank() } ?: return null
    if (url.contains("param=")) return url
    val sep = if (url.contains('?')) "&" else "?"
    return "$url${sep}param=${size}y$size"
}

/**
 * 封面图（带占位）：无封面/加载失败时是主题色渐变 + 音符，不露白不破版。
 * 对标网易云 Windows 版列表封面占位做法。
 */
@Composable
fun ArtworkImage(
    url: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 6.dp,
    thumbSize: Int = 200,
    seedColor: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(
                Brush.linearGradient(
                    listOf(
                        seedColor.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url.thumb(thumbSize),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

/** 毫秒 → m:ss（表格/进度用，等宽数字由调用方字体保证）。 */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
