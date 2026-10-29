package com.jussicodes.music.ui.screen

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.input.ImeAction
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
import com.jussicodes.music.ui.navigation.PlaylistNav
import com.jussicodes.music.ui.navigation.UserNav
import com.jussicodes.music.viewModel.SearchViewModel
import com.rcmiku.ncmapi.api.search.SearchType
import com.rcmiku.ncmapi.model.Song

/**
 * 桌面搜索页（替代安卓版）：大搜索框（防抖建议、回车执行、历史）、
 * 类型页签（单曲/歌单/专辑/歌手）、空态展示搜索历史与排行榜。
 * 数据走原 SearchViewModel（/cloudsearch）。
 */
@Composable
fun SearchScreen(navController: NavController) {
    val viewModel = hiltViewModel<SearchViewModel>()
    val results by viewModel.searchResults.collectAsState()
    val searchError by viewModel.searchError.collectAsState()
    // 2.2.0（L2）：搜索分页状态（加载更多）。
    val searchHasMore by viewModel.searchHasMore.collectAsState()
    val searchLoadingMore by viewModel.searchLoadingMore.collectAsState()
    // 2.3.0（A6-05）：加载更多失败的行内提示（与首搜失败的 searchError 分开）。
    val searchLoadMoreError by viewModel.searchLoadMoreError.collectAsState()
    val searchType by viewModel.searchType.collectAsState()
    val suggest by viewModel.suggestKeywordResponse.collectAsState()
    val history by viewModel.searchHistory.collectAsState(initial = emptyList())
    val topLists by viewModel.topLists.collectAsState()
    val playerState = LocalPlayerState.current

    var text by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var suggestOpen by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // 顶栏搜索框提交（DesktopUiState.pendingSearch）
    val pending by DesktopUiState.pendingSearch.collectAsState()
    LaunchedEffect(pending) {
        val q = pending ?: return@LaunchedEffect
        text = q
        submitted = q
        searching = true
        viewModel.updateSearchValue(q)
        DesktopUiState.pendingSearch.value = null
    }
    // Ctrl+L 聚焦信号
    val focusSignal by DesktopUiState.focusSearchSignal.collectAsState()
    LaunchedEffect(focusSignal) {
        if (focusSignal > 0) runCatching { focusRequester.requestFocus() }
    }
    LaunchedEffect(results) { if (results.isNotEmpty()) searching = false }
    // #8：搜索失败立即结束转圈，下方 when 显示失败态（与真无结果区分）
    LaunchedEffect(searchError) { if (searchError != null) searching = false }
    LaunchedEffect(submitted) {
        if (submitted.isNotBlank()) {
            kotlinx.coroutines.delay(8000)
            searching = false
        }
    }

    fun submit(q: String) {
        val query = q.trim()
        if (query.isEmpty()) return
        text = query
        submitted = query
        suggestOpen = false
        searching = true
        viewModel.updateSearchValue(query)
    }

    val songs: List<Song> = results.mapNotNull { it.song }
    val playlists = results.mapNotNull { it.playlist }
    val albums = results.mapNotNull { it.album }
    val artists = results.mapNotNull { it.artist }
    val users = results.mapNotNull { it.user }
    val currentSongId = playerState?.currentMediaItem?.mediaId?.toLongOrNull()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 搜索框
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        if (it.isNotBlank()) {
                            viewModel.fetchSearchKeyword(it)
                            suggestOpen = true
                        } else {
                            suggestOpen = false
                        }
                    },
                    placeholder = { Text("搜歌曲、歌手、专辑、歌单") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        if (text.isNotEmpty()) {
                            IconButton(onClick = { text = ""; suggestOpen = false }) {
                                Icon(Icons.Filled.Clear, "清空", Modifier.size(18.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit(text) }),
                    modifier = Modifier.fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { DesktopUiState.inputFocused.value = it.isFocused }
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) {
                                submit(text); true
                            } else false
                        }
                )
                // 联想词下拉
                val suggestItems = suggest?.data?.suggests.orEmpty()
                if (suggestOpen && submitted != text && suggestItems.isNotEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        suggestItems.take(8).forEach { item ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { submit(item.keyword) }
                                    .padding(horizontal = 14.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Search, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(9.dp))
                                Text(item.keyword, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        if (submitted.isBlank()) {
            // 空态：历史 + 排行榜（侧栏「排行榜」也落到这里）
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (history.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.History, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("搜索历史", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text(
                                "清空",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { viewModel.clearSearchHistory() }.padding(4.dp)
                            )
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            history.take(10).forEach { h ->
                                AssistChip(
                                    onClick = { submit(h) },
                                    label = { Text(h, maxLines = 1) },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Filled.Close, "删除",
                                            Modifier.size(13.dp).clickable { viewModel.deleteSearchQuery(h) }
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 10.dp)
                    ) {
                        Icon(Icons.Filled.TrendingUp, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("排行榜", style = MaterialTheme.typography.titleSmall)
                    }
                }
                items(topLists, key = { it.id }) { pl ->
                    Column(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable { navController.navigate(PlaylistNav(pl.id)) }
                            .padding(8.dp)
                    ) {
                        ArtworkImage(url = pl.picUrl, modifier = Modifier.size(140.dp), corner = 8.dp, thumbSize = 300)
                        Spacer(Modifier.height(7.dp))
                        Text(pl.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${pl.trackCount} 首",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(14.dp)) }
            }
            return@Column
        }

        // 类型页签
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                "单曲" to SearchType.Song,
                "歌单" to SearchType.Playlist,
                "专辑" to SearchType.Album,
                "歌手" to SearchType.Artist,
                "用户" to SearchType.User
            ).forEach { (label, type) ->
                FilterChip(
                    selected = searchType == type,
                    onClick = { viewModel.updateSearchType(type) },
                    label = { Text(label) }
                )
            }
        }

        when {
            searching && results.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            // #8 失败态：请求失败且无（旧）结果可展示时显示，与真无结果文案/视觉区分
            searchError != null && results.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "搜索失败：${searchError}，请检查网络或 API 服务器设置",
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = {
                            searching = true
                            viewModel.retrySearch()
                        }) { Text("重试") }
                    }
                }
            }
            results.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有找到与「$submitted」相关的结果", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            searchType == SearchType.Song -> {
                if (songs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("没有找到相关单曲", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    SongTable(
                        songs = songs,
                        onPlayAt = { i ->
                            val player = playerState?.player ?: return@SongTable
                            player.setPlaylist(songs, sourceName = "搜索:$submitted")
                            player.playMediaAt(i)
                        },
                        onPlayNext = { song -> playerState?.player?.insertToPlaylist(song) },
                        onAddToQueue = { song -> playerState?.player?.addToPlaylist(song) },
                        onArtistClick = { id -> if (id > 0) navController.navigate(ArtistNav(id)) },
                        onAlbumClick = { id -> if (id > 0) navController.navigate(AlbumNav(id)) },
                        currentSongId = currentSongId,
                        isPlaying = playerState?.isPlaying == true,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)
                    )
                }
            }
            searchType == SearchType.Playlist -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(playlists, key = { it.id }) { pl ->
                        Column(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .clickable { navController.navigate(PlaylistNav(pl.id)) }
                                .padding(8.dp)
                        ) {
                            ArtworkImage(url = pl.picUrl, modifier = Modifier.size(140.dp), corner = 8.dp, thumbSize = 300)
                            Spacer(Modifier.height(7.dp))
                            Text(pl.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${pl.trackCount} 首 · ${pl.creator?.nickname.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
            searchType == SearchType.Album -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(albums, key = { it.id }) { al ->
                        Column(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .clickable { navController.navigate(AlbumNav(al.id)) }
                                .padding(8.dp)
                        ) {
                            ArtworkImage(url = al.picUrl, modifier = Modifier.size(140.dp), corner = 8.dp, thumbSize = 300)
                            Spacer(Modifier.height(7.dp))
                            Text(al.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                al.artists.joinToString(" / ") { it.name }.ifBlank { al.artist.name },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
            searchType == SearchType.Artist -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(220.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(artists, key = { it.id }) { ar ->
                        Row(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .clickable { navController.navigate(ArtistNav(ar.id)) }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ArtworkImage(url = ar.cover, modifier = Modifier.size(46.dp), corner = 23.dp, thumbSize = 100)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(ar.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${ar.musicSize} 首歌曲",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            searchType == SearchType.User -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(220.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(users, key = { it.id }) { u ->
                        Row(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .clickable { navController.navigate(UserNav(u.id)) }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ArtworkImage(url = u.avatarUrl, modifier = Modifier.size(46.dp), corner = 23.dp, thumbSize = 100)
                            Spacer(Modifier.width(10.dp))
                            Text(u.nickname, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            else -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无结果", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 2.2.0（L2）：加载更多——结果非空且接口报还有下一页时，在
        // 结果区底部给统一入口（歌曲表与四种网格共用）。旧实现只取
        // 第一页 100 条，热门词的大量结果被静默截断。
        // 2.3.0（A6-05）：失败时按钮不消失，行内给错误文案、按钮文案
        // 改「重试」，点之可再跑同一页（VM 侧失败保留 hasMore）。
        if (results.isNotEmpty() && searchHasMore) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (searchLoadMoreError != null) {
                    Text(
                        "加载更多失败：$searchLoadMoreError",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Row(horizontalArrangement = Arrangement.Center) {
                    Button(
                        onClick = { viewModel.loadMoreSearchResults() },
                        enabled = !searchLoadingMore
                    ) {
                        Text(
                            if (searchLoadingMore) "加载中…"
                            else if (searchLoadMoreError != null) "重试"
                            else "加载更多"
                        )
                    }
                }
            }
        }
    }
}
