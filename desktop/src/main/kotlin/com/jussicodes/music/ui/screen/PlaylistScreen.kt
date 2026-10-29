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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.jussicodes.music.LocalPlayerState
import com.jussicodes.music.desktop.ui.ArtworkImage
import com.jussicodes.music.desktop.ui.DesktopUiState
import com.jussicodes.music.desktop.ui.SongTable
import com.jussicodes.music.extensions.addToPlaylist
import com.jussicodes.music.extensions.insertToPlaylist
import com.jussicodes.music.extensions.playMediaAt
import com.jussicodes.music.extensions.setPlaylist
import com.jussicodes.music.ui.navigation.AlbumNav
import com.jussicodes.music.ui.navigation.ArtistNav
import com.jussicodes.music.ui.theme.rememberArtworkSeed
import com.jussicodes.music.viewModel.PlaylistScreenViewModel
import kotlinx.coroutines.launch

private enum class PlaylistSort { DEFAULT, TITLE, ARTIST, ALBUM, DURATION }

/**
 * 桌面歌单详情页（替代安卓版，对标网易云 Windows 版）：
 * 封面取色渐变头图 + 元信息 + 播放全部/随机/收藏 + 列表内筛选与排序 +
 * 桌面歌曲表（右键菜单/双击播放）。数据与操作走原 PlaylistScreenViewModel。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PlaylistScreen(
    navController: NavController,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedVisibilityScope
) {
    val viewModel = hiltViewModel<PlaylistScreenViewModel>()
    val detail by viewModel.playlistDetail.collectAsState()
    val info by viewModel.playlistInfo.collectAsState()
    val playerState = LocalPlayerState.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(detail) { if (detail != null) loadFailed = false }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(12_000)
        if (detail == null) loadFailed = true
    }

    var filter by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(PlaylistSort.DEFAULT) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val playlist = detail?.playlist
    val allTracks = playlist?.getAllTracks().orEmpty()
    val shownTracks = remember(allTracks, filter, sort) {
        var list = allTracks
        if (filter.isNotBlank()) {
            val q = filter.trim()
            list = list.filter {
                it.name.contains(q, true) ||
                    it.ar.any { a -> a.name.contains(q, true) } ||
                    it.al.name.contains(q, true)
            }
        }
        when (sort) {
            PlaylistSort.DEFAULT -> list
            PlaylistSort.TITLE -> list.sortedBy { it.name }
            PlaylistSort.ARTIST -> list.sortedBy { it.ar.firstOrNull()?.name.orEmpty() }
            PlaylistSort.ALBUM -> list.sortedBy { it.al.name }
            PlaylistSort.DURATION -> list.sortedBy { it.dt }
        }
    }

    val seed = rememberArtworkSeed(playlist?.coverImgUrl)
    val currentSongId = playerState?.currentMediaItem?.mediaId?.toLongOrNull()

    // 定位当前播放（壳层信号）
    val locateSignal by DesktopUiState.locateCurrentSignal.collectAsState()
    LaunchedEffect(locateSignal) {
        if (locateSignal == 0) return@LaunchedEffect
        val idx = shownTracks.indexOfFirst { it.id == currentSongId }
        if (idx >= 0) runCatching { listState.animateScrollToItem(idx) }
    }

    fun playAt(indexInShown: Int) {
        val player = playerState?.player ?: return
        // 筛选/排序后播放：以当前展示序为准重建队列，保证「所见即所播」
        // 2.2.0（A5-02）：起点直接用展示位次，不再按 id 反查——同曲
        // 在歌单里重复出现时 indexOfFirst 永远命中第一首，点第二首
        // 播的还是第一首（且高亮行与实际播放错位）。
        player.setPlaylist(shownTracks, sourceId = playlist?.id ?: 0, sourceName = playlist?.name ?: "歌单")
        val start = indexInShown.coerceIn(0, (shownTracks.size - 1).coerceAtLeast(0))
        player.playMediaAt(start)
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (playlist == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (loadFailed) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("歌单加载失败，请检查网络", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { loadFailed = false; viewModel.refresh() }) {
                            Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("重试")
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        // 2.1.3（A4-08）：大歌单分批补拉进度（PlaylistApi
                        // fillMissingTracks 驱动），不再无提示干等。
                        val fillText by com.rcmiku.ncmapi.api.playlist.PlaylistApi
                            .fillProgress.collectAsState()
                        if (fillText != null) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                fillText!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            return@Column
        }

        // ---- 头图（封面取色渐变，对标 Spotify/网易云详情页头图）----
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
                ArtworkImage(url = playlist.coverImgUrl, modifier = Modifier.size(168.dp), corner = 10.dp, thumbSize = 400)
                Spacer(Modifier.width(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        playlist.name.ifBlank { "未命名歌单" },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ArtworkImage(url = playlist.creator?.avatarUrl, modifier = Modifier.size(22.dp), corner = 11.dp, thumbSize = 60)
                        Spacer(Modifier.width(7.dp))
                        Text(
                            playlist.creator?.nickname.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                playlist.creator?.userId?.takeIf { it > 0 }?.let {
                                    navController.navigate(com.jussicodes.music.ui.navigation.UserNav(it))
                                }
                            }
                        )
                        Text(
                            " · ${playlist.trackCount} 首" +
                                if (playlist.playCount > 0) " · 播放 ${formatPlayCount(playlist.playCount)}" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (playlist.description.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            playlist.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { if (shownTracks.isNotEmpty()) playAt(0) }) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("播放全部")
                        }
                        OutlinedButton(onClick = {
                            val player = playerState?.player ?: return@OutlinedButton
                            if (shownTracks.isEmpty()) return@OutlinedButton
                            player.setPlaylist(
                                shownTracks, sourceId = playlist.id, sourceName = playlist.name
                            )
                            player.shuffleModeEnabled = true
                            player.playMediaAt((shownTracks.indices).random())
                        }) {
                            Icon(Icons.Filled.Shuffle, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("随机播放")
                        }
                        val subscribed = info?.subscribed ?: playlist.specialType == 0 && (info?.playlist?.subscribed == true)
                        OutlinedButton(onClick = { viewModel.playlistSub(!subscribed) }) {
                            Icon(
                                if (subscribed) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                null, Modifier.size(16.dp),
                                tint = if (subscribed) Color(0xFFE83E52) else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(if (subscribed) "已收藏" else "收藏")
                        }
                        // 评论入口（2.0.2：歌单评论，复用 PlayerComments，commentType=2）
                        OutlinedButton(onClick = { showComments = true }) {
                            Icon(Icons.Filled.Comment, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("评论")
                        }
                        // 导出（2.0.5，C11 导出半）：m3u / 纯文本两种格式，
                        // 落下载目录（~/Music/PenguinMusic，与歌曲下载同目录）
                        var exportMenuOpen by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { exportMenuOpen = true }) {
                                Icon(Icons.Filled.IosShare, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("导出")
                            }
                            DropdownMenu(
                                expanded = exportMenuOpen,
                                onDismissRequest = { exportMenuOpen = false }
                            ) {
                                DropdownMenuItem(text = { Text("导出为 M3U 播放列表") }, onClick = {
                                    exportMenuOpen = false
                                    exportPlaylist(playlist.name, allTracks, asM3u = true) {
                                        android.widget.Toast.makeText(
                                            context, it, android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                })
                                DropdownMenuItem(text = { Text("导出为纯文本（歌名 - 歌手）") }, onClick = {
                                    exportMenuOpen = false
                                    exportPlaylist(playlist.name, allTracks, asM3u = false) {
                                        android.widget.Toast.makeText(
                                            context, it, android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                })
                            }
                        }
                        IconButton(onClick = {
                            val idx = shownTracks.indexOfFirst { it.id == currentSongId }
                            if (idx >= 0) scope.launch { runCatching { listState.animateScrollToItem(idx) } }
                            else android.widget.Toast.makeText(context, "当前播放不在这个歌单里", android.widget.Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Filled.MyLocation, "定位当前播放", Modifier.size(19.dp))
                        }
                    }
                }
            }
        }

        // ---- 工具行：筛选 + 排序 ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("筛选本歌单", style = MaterialTheme.typography.bodySmall) },
                leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(16.dp)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                // #5：空格防误触——聚焦时登记 inputFocused，打字不触发全局播放/暂停
                modifier = Modifier.width(220.dp)
                    .onFocusChanged { DesktopUiState.inputFocused.value = it.isFocused }
            )
            Spacer(Modifier.weight(1f))
            Text(
                when (sort) {
                    PlaylistSort.DEFAULT -> ""
                    PlaylistSort.TITLE -> "按歌名排序"
                    PlaylistSort.ARTIST -> "按歌手排序"
                    PlaylistSort.ALBUM -> "按专辑排序"
                    PlaylistSort.DURATION -> "按时长排序"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box {
                IconButton(onClick = { sortMenuOpen = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.FilterList, "排序", Modifier.size(18.dp))
                }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    DropdownMenuItem(text = { Text("默认排序") }, onClick = { sort = PlaylistSort.DEFAULT; sortMenuOpen = false })
                    DropdownMenuItem(text = { Text("歌名") }, onClick = { sort = PlaylistSort.TITLE; sortMenuOpen = false })
                    DropdownMenuItem(text = { Text("歌手") }, onClick = { sort = PlaylistSort.ARTIST; sortMenuOpen = false })
                    DropdownMenuItem(text = { Text("专辑") }, onClick = { sort = PlaylistSort.ALBUM; sortMenuOpen = false })
                    DropdownMenuItem(text = { Text("时长") }, onClick = { sort = PlaylistSort.DURATION; sortMenuOpen = false })
                }
            }
        }

        if (shownTracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (allTracks.isEmpty()) "这个歌单还没有歌曲" else "没有匹配「$filter」的歌曲",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // 从歌单移除：仅当歌单属于当前登录用户（自建/我喜欢）时提供，
            // 走已有 /playlist/tracks（op=del）接口，成功后刷新本页。
            val myUid = com.jussicodes.music.desktop.ui.SessionState.profile.collectAsState().value?.userId
            val ownPlaylist = playlist != null && myUid != null && myUid > 0 &&
                playlist.creator?.userId == myUid
            SongTable(
                songs = shownTracks,
                onPlayAt = { playAt(it) },
                onPlayNext = { song -> playerState?.player?.insertToPlaylist(song) },
                onAddToQueue = { song -> playerState?.player?.addToPlaylist(song) },
                onArtistClick = { id -> if (id > 0) navController.navigate(ArtistNav(id)) },
                onAlbumClick = { id -> if (id > 0) navController.navigate(AlbumNav(id)) },
                listState = listState,
                currentSongId = currentSongId,
                isPlaying = playerState?.isPlaying == true,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                onRemoveFromPlaylist = if (ownPlaylist) {
                    { song ->
                        scope.launch {
                            val r = com.rcmiku.ncmapi.api.apiGet<com.rcmiku.ncmapi.model.ApiCodeResponse>(
                                "/playlist/tracks",
                                mapOf(
                                    "op" to "del", "pid" to (playlist?.id ?: 0),
                                    "tracks" to song.id.toString()
                                )
                            )
                            if (r.getOrNull()?.code == 200) {
                                android.widget.Toast.makeText(
                                    context, "已从歌单移除", android.widget.Toast.LENGTH_SHORT
                                ).show()
                                viewModel.refresh()
                            } else {
                                android.widget.Toast.makeText(
                                    context, "移除失败", android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                } else null
            )
        }
    }

    // 评论覆盖层（2.0.2）
    if (showComments && playlist != null) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        ) {
            com.jussicodes.music.ui.components.PlayerComments(
                mediaId = playlist.id,
                mediaMetadata = androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(playlist.name)
                    .build(),
                commentType = 2,
                onBackPressed = { showComments = false }
            )
        }
    }
    }
}

/**
 * 歌单导出（2.0.5，C11）：写到下载目录。m3u 带 #EXTINF 元数据行 +
 * 文件项（同名已下载文件存在时给相对文件名，否则空行占位——纯元数据
 * 导出，不含音频地址）；纯文本每行「歌名 - 歌手」。
 */
private fun exportPlaylist(
    playlistName: String,
    tracks: List<com.rcmiku.ncmapi.model.Song>,
    asM3u: Boolean,
    onDone: (String) -> Unit
) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
        val msg = runCatching {
            val dir = com.jussicodes.music.desktop.ui.DownloadHelper.downloadDir()
            val safeName = playlistName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .ifBlank { "playlist" }
            val ext = if (asM3u) "m3u" else "txt"
            val file = java.io.File(dir, "$safeName.$ext")
            // 本地已下载文件（与 DownloadHelper 同命名口径）供 m3u 文件项引用
            val localNames = dir.listFiles()?.map { it.name }?.toSet().orEmpty()
            fun localFileName(song: com.rcmiku.ncmapi.model.Song): String {
                val artists = song.ar.joinToString("、") { it.name }.ifBlank { "未知歌手" }
                val base = "${song.name} - $artists"
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)
                return listOf("mp3", "flac", "m4a", "wav", "ogg")
                    .firstOrNull { "$base.$it" in localNames }
                    ?.let { "$base.$it" } ?: ""
            }
            val text = buildString {
                if (asM3u) {
                    appendLine("#EXTM3U")
                    appendLine("#PLAYLIST:$playlistName")
                    tracks.forEach { song ->
                        val artists = song.ar.joinToString(" / ") { it.name }
                        appendLine("#EXTINF:${song.dt / 1000},$artists - ${song.name}")
                        appendLine(localFileName(song))
                    }
                } else {
                    tracks.forEach { song ->
                        val artists = song.ar.joinToString(" / ") { it.name }
                        appendLine("${song.name} - $artists")
                    }
                }
            }
            file.writeText(text, Charsets.UTF_8)
            "已导出 ${tracks.size} 首 → ${file.absolutePath}"
        }.getOrElse { "导出失败：${it.message}" }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDone(msg) }
    }
}

internal fun formatPlayCount(count: Double): String = when {
    count >= 100_000_000 -> "%.1f亿".format(count / 100_000_000)
    count >= 10_000 -> "%.1f万".format(count / 10_000)
    else -> count.toLong().toString()
}
