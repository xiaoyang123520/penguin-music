package com.jussicodes.music.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rcmiku.ncmapi.model.Song

/**
 * 桌面歌曲表（对标网易云 Windows 版歌曲列表）：表头 + 行悬停高亮 +
 * 悬停出操作 + 右键菜单 + 双击播放。几千首走 LazyColumn 懒加载。
 */
@Composable
fun SongTable(
    songs: List<Song>,
    onPlayAt: (Int) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onArtistClick: (Long) -> Unit,
    onAlbumClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    currentSongId: Long? = null,
    isPlaying: Boolean = false,
    showHeader: Boolean = true,
    // 2.0.2：非空时菜单多一项移除（歌单详情传「从歌单移除」、云盘页传
    // 「从云盘删除」，文案由 removalLabel 决定）；仅上下文有归属时才传。
    onRemoveFromPlaylist: ((Song) -> Unit)? = null,
    removalLabel: String = "从歌单移除",
) {
    val likedIds by LikeController.likedIds.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(modifier = modifier) {
        if (showHeader) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("#", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(36.dp))
                Text("标题", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("专辑", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(200.dp))
                Text("时长", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(52.dp))
                Spacer(Modifier.width(64.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            // 2.2.0（A5-02）：key 必须唯一——歌单允许同曲重复出现
            // （id 相同），裸 id 作 key 时 LazyColumn 直接崩
            // （IllegalArgumentException: Key was already used）。
            // 改复合键 id@index：下标是列表内的稳定位次，可区分重复项。
            itemsIndexed(songs, key = { index, s -> "${s.id}@$index" }) { index, song ->
                SongRow(
                    index = index,
                    song = song,
                    isCurrent = song.id == currentSongId,
                    isPlaying = isPlaying,
                    liked = song.id in likedIds,
                    onPlay = { onPlayAt(index) },
                    onPlayNext = { onPlayNext(song) },
                    onAddToQueue = { onAddToQueue(song) },
                    onArtistClick = onArtistClick,
                    onAlbumClick = onAlbumClick,
                    onToggleLike = {
                        if (!LikeController.toggle(context, song.id)) {
                            android.widget.Toast.makeText(context, "请先登录", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    onDownload = { DownloadHelper.downloadSong(context, song) },
                    onRemove = onRemoveFromPlaylist?.let { handler -> { handler(song) } },
                    removalLabel = removalLabel,
                    onCopyInfo = {
                        clipboard.setText(AnnotatedString("${song.name} - ${song.ar.joinToString("/") { it.name }}"))
                        android.widget.Toast.makeText(context, "已复制歌曲信息", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

@Composable
private fun SongRow(
    index: Int,
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    liked: Boolean,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onArtistClick: (Long) -> Unit,
    onAlbumClick: (Long) -> Unit,
    onToggleLike: () -> Unit,
    onCopyInfo: () -> Unit,
    onDownload: () -> Unit,
    onRemove: (() -> Unit)?,
    removalLabel: String,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val bg = when {
        isCurrent -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    val titleColor = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface

    // 菜单项单点定义，右键菜单与行尾「更多」按钮共用（2.0.2 补齐下载/移除）。
    val menuActions: List<Pair<String, () -> Unit>> = buildList {
        add("播放" to onPlay)
        add("下一首播放" to onPlayNext)
        add("加入播放队列" to onAddToQueue)
        add((if (liked) "取消喜欢" else "喜欢") to onToggleLike)
        add("下载" to onDownload)
        if (song.mv > 0) {
            add("播放 MV" to {
                com.jussicodes.music.desktop.ui.DesktopUiState.openMv(song.mv, song.name)
            })
        }
        add("查看专辑" to { onAlbumClick(song.al.id) })
        add("复制歌曲信息" to onCopyInfo)
        if (onRemove != null) add(removalLabel to onRemove)
    }
    var moreMenuOpen by remember { mutableStateOf(false) }

    ContextMenuArea(
        items = {
            menuActions.map { (label, action) -> ContextMenuItem(label, action) }
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(bg)
                .hoverable(hover)
                .combinedClickable(onClick = {}, onDoubleClick = onPlay)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
                if (isCurrent) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp).clickable { onPlay() }
                    )
                } else {
                    Text(
                        "%02d".format(index + 1),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    song.name.ifBlank { "未知歌曲" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    song.ar.joinToString(" / ") { it.name }.ifBlank { "未知歌手" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable {
                        song.ar.firstOrNull()?.let { onArtistClick(it.id) }
                    }
                )
            }
            Text(
                song.al.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(200.dp).clickable { onAlbumClick(song.al.id) }
            )
            Text(
                formatDuration(song.dt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(52.dp)
            )
            Box(Modifier.width(34.dp), contentAlignment = Alignment.Center) {
                if (liked || hovered) {
                    Icon(
                        if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "喜欢",
                        tint = if (liked) Color(0xFFE83E52) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp).clickable { onToggleLike() }
                    )
                }
            }
            // 行尾「更多」按钮：右键菜单的可见兜底（触控板/新用户找不到右键时用）
            Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
                if (hovered || moreMenuOpen) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp).clickable { moreMenuOpen = true }
                    )
                    androidx.compose.material3.DropdownMenu(
                        expanded = moreMenuOpen,
                        onDismissRequest = { moreMenuOpen = false }
                    ) {
                        menuActions.forEach { (label, action) ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { moreMenuOpen = false; action() }
                            )
                        }
                    }
                }
            }
        }
    }
}
