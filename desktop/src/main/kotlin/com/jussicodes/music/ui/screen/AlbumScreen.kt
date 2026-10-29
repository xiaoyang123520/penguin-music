package com.jussicodes.music.ui.screen

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.toRoute
import com.jussicodes.music.LocalPlayerState
import com.jussicodes.music.desktop.ui.ArtworkImage
import com.jussicodes.music.desktop.ui.SongTable
import com.jussicodes.music.extensions.addToPlaylist
import com.jussicodes.music.extensions.insertToPlaylist
import com.jussicodes.music.extensions.playMediaAt
import com.jussicodes.music.extensions.setPlaylist
import com.jussicodes.music.ui.navigation.ArtistNav
import com.jussicodes.music.ui.theme.rememberArtworkSeed
import com.jussicodes.music.viewModel.AlbumScreenViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 桌面专辑详情页（替代安卓版）：取色头图 + 发行信息 + 歌曲表。 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AlbumScreen(
    navController: NavController,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedVisibilityScope
) {
    val viewModel = hiltViewModel<AlbumScreenViewModel>()
    val detailResult by viewModel.albumDetail.collectAsState()
    val detail = detailResult?.getOrNull()
    val albumInfo by viewModel.albumInfo.collectAsState()
    val playerState = LocalPlayerState.current
    var loadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(detailResult) {
        if (detail != null) loadFailed = false
        if (detailResult?.isFailure == true) loadFailed = true
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(12_000)
        if (detail == null) loadFailed = true
    }

    val album = detail?.album
    val songs = detail?.songs.orEmpty()
    val seed = rememberArtworkSeed(album?.picUrl)
    val currentSongId = playerState?.currentMediaItem?.mediaId?.toLongOrNull()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (album == null || detail == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (loadFailed) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("专辑加载失败，请检查网络", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = {
                            // 重新进入本页触发 ViewModel 重新拉取
                            val albumId = runCatching {
                                navController.currentBackStackEntry
                                    ?.toRoute<com.jussicodes.music.ui.navigation.AlbumNav>()?.albumId
                            }.getOrNull() ?: 0L
                            if (albumId > 0) {
                                loadFailed = false
                                navController.navigate(com.jussicodes.music.ui.navigation.AlbumNav(albumId)) {
                                    popUpTo(com.jussicodes.music.ui.navigation.AlbumNav(albumId)) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        }) {
                            Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("重试")
                        }
                    }
                } else CircularProgressIndicator()
            }
            return@Column
        }

        Box(
            Modifier.fillMaxWidth().background(
                Brush.verticalGradient(
                    listOf(
                        (seed ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.5f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
        ) {
            Row(Modifier.padding(24.dp)) {
                ArtworkImage(url = album.picUrl, modifier = Modifier.size(168.dp), corner = 10.dp, thumbSize = 400)
                Spacer(Modifier.width(22.dp))
                Column(Modifier.weight(1f)) {
                    Text("专辑", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        album.name.ifBlank { "未命名专辑" },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                    val artistNames = album.artists.ifEmpty { listOf(album.artist) }
                    Row {
                        Text("歌手：", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        artistNames.forEachIndexed { i, a ->
                            if (i > 0) Text(" / ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                a.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { if (a.id > 0) navController.navigate(ArtistNav(a.id)) }
                            )
                        }
                    }
                    val publish = if (album.publishTime > 0) {
                        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(album.publishTime))
                    } else ""
                    Text(
                        listOf(
                            publish.takeIf { it.isNotBlank() }?.let { "发行 $it" },
                            "${songs.size} 首",
                            album.company?.takeIf { it.isNotBlank() }
                        ).filterNotNull().joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (album.description.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            album.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = {
                            val player = playerState?.player ?: return@Button
                            if (songs.isEmpty()) return@Button
                            player.setPlaylist(songs, sourceId = album.id, sourceName = album.name, sourceType = "album")
                            player.playMediaAt(0)
                        }) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("播放全部")
                        }
                        val followed = albumInfo?.isSub == true
                        OutlinedButton(onClick = { viewModel.albumSub(!followed) }) {
                            Icon(
                                if (followed) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                null, Modifier.size(16.dp),
                                tint = if (followed) Color(0xFFE83E52) else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(if (followed) "已收藏" else "收藏专辑")
                        }
                    }
                }
            }
        }

        if (songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("这张专辑暂时没有可播放的歌曲", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            SongTable(
                songs = songs,
                onPlayAt = { i ->
                    val player = playerState?.player ?: return@SongTable
                    player.setPlaylist(songs, sourceId = album.id, sourceName = album.name, sourceType = "album")
                    player.playMediaAt(i)
                },
                onPlayNext = { song -> playerState?.player?.insertToPlaylist(song) },
                onAddToQueue = { song -> playerState?.player?.addToPlaylist(song) },
                onArtistClick = { id -> if (id > 0) navController.navigate(ArtistNav(id)) },
                onAlbumClick = {},
                currentSongId = currentSongId,
                isPlaying = playerState?.isPlaying == true,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)
            )
        }
    }
}
