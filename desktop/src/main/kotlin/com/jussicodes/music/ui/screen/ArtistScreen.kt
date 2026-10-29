package com.jussicodes.music.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.jussicodes.music.LocalPlayerState
import com.jussicodes.music.desktop.ui.ArtworkImage
import com.jussicodes.music.desktop.ui.SongTable
import com.jussicodes.music.extensions.addToPlaylist
import com.jussicodes.music.extensions.insertToPlaylist
import com.jussicodes.music.extensions.playMediaAt
import com.jussicodes.music.extensions.setPlaylist
import com.jussicodes.music.ui.navigation.AlbumNav
import com.rcmiku.ncmapi.api.artist.ArtistApi
import com.rcmiku.ncmapi.model.Album
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 桌面歌手详情页（替代安卓版）：头图 + 关注 + 热门歌曲表 + 专辑网格 + 相似歌手。 */
@Composable
fun ArtistScreen(navController: NavController) {
    val viewModel = hiltViewModel<com.jussicodes.music.viewModel.ArtistScreenViewModel>()
    val headInfo by viewModel.artistHeadInfo.collectAsState()
    val topSong by viewModel.artistTopSong.collectAsState()
    val simi by viewModel.simiArtists.collectAsState()
    val subscribed by viewModel.isArtistSubscribed.collectAsState()
    val followStats by viewModel.artistFollowStats.collectAsState()
    val playerState = LocalPlayerState.current
    val context = LocalContext.current
    var loadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(headInfo) { if (headInfo != null) loadFailed = false }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(12_000)
        if (headInfo == null) loadFailed = true
    }

    val artist = headInfo?.data?.artist
    val artistId = artist?.id ?: 0
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    LaunchedEffect(artistId) {
        if (artistId > 0) {
            albums = withContext(Dispatchers.IO) {
                runCatching { ArtistApi.artistAlbum(artistId, limit = 30, offset = 0).getOrNull()?.hotAlbums }
                    .getOrNull().orEmpty()
            }
        }
    }

    val songs = topSong?.songs.orEmpty()
    val currentSongId = playerState?.currentMediaItem?.mediaId?.toLongOrNull()
    val gridState = rememberLazyGridState()
    // 歌手简介（2.0.5，C7）：/artist/desc 数据层早已备好，此前无 UI 入口。
    var showDesc by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (artist == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (loadFailed) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("歌手信息加载失败，请检查网络", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { loadFailed = false }) {
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
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
        ) {
            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtworkImage(url = artist.cover, modifier = Modifier.size(132.dp), corner = 66.dp, thumbSize = 300)
                Spacer(Modifier.width(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        artist.name,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildString {
                            append("${artist.musicSize} 首歌曲 · ${artist.albumSize} 张专辑")
                            followStats?.fansCnt?.takeIf { it > 0 }?.let { append(" · ${formatPlayCount(it.toDouble())} 粉丝") }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (headInfo?.data?.briefDesc?.isNotBlank() == true) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            headInfo!!.data.briefDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = {
                            val player = playerState?.player ?: return@Button
                            if (songs.isEmpty()) return@Button
                            player.setPlaylist(songs, sourceId = artistId, sourceName = artist.name, sourceType = "artist")
                            player.playMediaAt(0)
                        }) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("播放热门")
                        }
                        OutlinedButton(onClick = { viewModel.toggleArtistSub() }) {
                            Text(if (subscribed) "已关注" else "关注")
                        }
                        OutlinedButton(onClick = { showDesc = true }) {
                            Text("歌手简介")
                        }
                    }
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (songs.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "热门歌曲",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    // 嵌套在网格里：给表格一个按行数算出的显式高度，避免无限高测量
                    val topSongs = songs.take(50)
                    SongTable(
                        songs = topSongs,
                        onPlayAt = { i ->
                            val player = playerState?.player ?: return@SongTable
                            player.setPlaylist(songs, sourceId = artistId, sourceName = artist.name, sourceType = "artist")
                            player.playMediaAt(i)
                        },
                        onPlayNext = { song -> playerState?.player?.insertToPlaylist(song) },
                        onAddToQueue = { song -> playerState?.player?.addToPlaylist(song) },
                        onArtistClick = { id -> if (id > 0 && id != artistId) navController.navigate(com.jussicodes.music.ui.navigation.ArtistNav(id)) },
                        onAlbumClick = { id -> if (id > 0) navController.navigate(AlbumNav(id)) },
                        currentSongId = currentSongId,
                        isPlaying = playerState?.isPlaying == true,
                        modifier = Modifier.fillMaxWidth().height((topSongs.size * 57 + 42).dp)
                    )
                }
            }
            if (albums.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "专辑",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                    )
                }
                items(albums, key = { it.id }) { album ->
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { if (album.id > 0) navController.navigate(AlbumNav(album.id)) }
                            .padding(8.dp)
                    ) {
                        ArtworkImage(url = album.picUrl, modifier = Modifier.size(140.dp), corner = 8.dp, thumbSize = 300)
                        Spacer(Modifier.height(7.dp))
                        Text(
                            album.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (album.publishTime > 0) {
                                java.text.SimpleDateFormat("yyyy", java.util.Locale.getDefault())
                                    .format(java.util.Date(album.publishTime))
                            } else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (simi.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "相似歌手",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                    )
                }
                items(simi.take(12), key = { it.id }) { sim ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { if (sim.id > 0) navController.navigate(com.jussicodes.music.ui.navigation.ArtistNav(sim.id)) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ArtworkImage(url = sim.cover, modifier = Modifier.size(40.dp), corner = 20.dp, thumbSize = 100)
                        Spacer(Modifier.width(9.dp))
                        Text(sim.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(16.dp)) }
        }

        if (showDesc) {
            ArtistDescDialog(artistId = artistId, artistName = artist.name) {
                showDesc = false
            }
        }
    }
}

/** 歌手简介对话框：briefDesc 摘要 + introduction 分节全文（可滚动）。 */
@Composable
private fun ArtistDescDialog(artistId: Long, artistName: String, onDismiss: () -> Unit) {
    var desc by remember {
        mutableStateOf<com.rcmiku.ncmapi.model.ArtistDescResponse?>(null)
    }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(artistId) {
        if (artistId > 0) {
            val r = withContext(Dispatchers.IO) {
                runCatching { ArtistApi.artistDesc(artistId).getOrNull() }
            }
            desc = r.getOrNull()
            failed = desc == null
        }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                Modifier.width(640.dp).padding(22.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$artistName · 歌手简介",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = onDismiss) { Text("关闭") }
                }
                Spacer(Modifier.height(12.dp))
                when {
                    failed -> Text(
                        "简介加载失败，请稍后重试",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    desc == null -> Text(
                        "加载中…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> {
                        val d = desc!!
                        if (d.briefDesc.isNotBlank()) {
                            Text(d.briefDesc, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(12.dp))
                        }
                        d.introduction.forEach { section ->
                            if (section.ti.isNotBlank()) {
                                Text(
                                    section.ti,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                            if (section.txt.isNotBlank()) {
                                Text(section.txt, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                        if (d.briefDesc.isBlank() && d.introduction.isEmpty()) {
                            Text(
                                "暂无简介",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
