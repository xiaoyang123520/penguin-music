package com.jussicodes.music.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jussicodes.music.LocalPlayerState
import com.jussicodes.music.extensions.cacheSongs
import com.jussicodes.music.extensions.playMediaAt
import com.jussicodes.music.extensions.removeSong
import com.jussicodes.music.utils.SongListUtil

/**
 * 右侧播放队列面板（对标 Spotify/网易云的队列抽屉）：点行切歌、
 * 单曲移除（走 PlayerExt.removeSong 保持持久化队列同步）、清空队列。
 */
@Composable
fun QueuePanel() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val playerState = LocalPlayerState.current ?: return
    val player = playerState.player
    val count = player.mediaItemCount
    val currentIndex = playerState.mediaItemIndex
    val queueSongs = cacheSongs.collectAsState().value
    val listState = rememberLazyListState()

    LaunchedEffect(currentIndex) {
        if (currentIndex in 0 until count) {
            runCatching { listState.scrollToItem(currentIndex) }
        }
    }

    Column(
        modifier = Modifier
            .width(340.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "播放队列 ($count)",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    player.clearMediaItems()
                    runCatching { SongListUtil.saveSongList(emptyList()) }
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Filled.DeleteSweep, "清空队列", modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = { DesktopUiState.queueOpen.value = false },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Filled.Close, "关闭", modifier = Modifier.size(18.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
        if (count == 0) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("队列是空的", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            items(count, key = { i -> player.getMediaItemAt(i).mediaId + "@" + i }) { i ->
                val item = player.getMediaItemAt(i)
                val song = queueSongs?.getOrNull(i)
                val title = song?.name ?: item.mediaMetadata.title?.toString().orEmpty()
                val artist = song?.ar?.joinToString(" / ") { it.name }
                    ?: item.mediaMetadata.artist?.toString().orEmpty()
                val art = song?.al?.picUrl ?: item.mediaMetadata.artworkUri?.toString()
                val isCurrent = i == currentIndex
                // 2.0.2：队列行右键菜单（播放/喜欢/下载/从队列移除），与歌曲表对齐
                androidx.compose.foundation.ContextMenuArea(
                    items = {
                        buildList {
                            add(androidx.compose.foundation.ContextMenuItem("播放") {
                                player.playMediaAt(i)
                            })
                            if (song != null) {
                                val liked = song.id in com.jussicodes.music.desktop.ui.LikeController.likedIds.value
                                add(androidx.compose.foundation.ContextMenuItem(
                                    if (liked) "取消喜欢" else "喜欢"
                                ) {
                                    if (!com.jussicodes.music.desktop.ui.LikeController.toggle(
                                            context, song.id
                                        )
                                    ) {
                                        android.widget.Toast.makeText(
                                            context, "请先登录", android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                })
                                add(androidx.compose.foundation.ContextMenuItem("下载") {
                                    DownloadHelper.downloadSong(context, song)
                                })
                            }
                            add(androidx.compose.foundation.ContextMenuItem("从队列移除") {
                                player.removeSong(item.mediaId)
                            })
                        }
                    }
                ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceContainerLow)
                        .clickable { player.playMediaAt(i) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArtworkImage(url = art, modifier = Modifier.size(38.dp), corner = 5.dp, thumbSize = 80)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            title.ifBlank { "未知歌曲" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = { player.removeSong(item.mediaId) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Filled.Close, "从队列移除", modifier = Modifier.size(15.dp))
                    }
                }
                }
            }
        }
    }
}
