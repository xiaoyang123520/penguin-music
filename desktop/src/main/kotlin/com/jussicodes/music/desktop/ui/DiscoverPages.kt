package com.jussicodes.music.desktop.ui

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jussicodes.music.LocalPlayerState
import com.jussicodes.music.extensions.addToPlaylist
import com.jussicodes.music.extensions.insertToPlaylist
import com.jussicodes.music.extensions.playMediaAt
import com.jussicodes.music.extensions.setPlaylist
import com.jussicodes.music.ui.navigation.PlaylistNav
import com.jussicodes.music.ui.navigation.Screen
import com.rcmiku.ncmapi.api.account.AccountApi
import com.rcmiku.ncmapi.api.apiGet
import com.rcmiku.ncmapi.api.playlist.PlaylistApi
import com.rcmiku.ncmapi.model.ApiCodeResponse
import com.rcmiku.ncmapi.model.Playlist
import com.rcmiku.ncmapi.model.Song
import com.rcmiku.ncmapi.model.SongAlbum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 2.0.2 新页面组（API 对齐的功能层接入）：
 * 云盘（/user/cloud 列表 + /user/cloud/del 删除）、排行榜（/toplist）、
 * 歌单广场（/playlist/catlist 分类 + /top/playlist）。
 * 云盘上传（NOS 四步：check→token→upload→info/pub）于 2.0.5 接入
 * （CloudUploadHelper），进度与失败在页面可见。
 */

@Serializable
private data class SquarePlaylistsResponse(
    val playlists: List<Playlist> = emptyList(),
    val total: Int = 0
)

// ---------------------------------------------------------------- 云盘

@Composable
fun CloudPage(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playerState = LocalPlayerState.current
    val profile by SessionState.profile.collectAsState()
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableStateOf(0) }

    LaunchedEffect(profile?.userId, reloadTick) {
        if (profile == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        loadError = null
        val result = AccountApi.cloudSong(offset = 0, limit = 200)
        result.onSuccess { resp ->
            songs = resp.data.map { cloud ->
                val s = cloud.simpleSong
                Song(
                    id = s.id,
                    name = s.name.ifBlank { cloud.album },
                    al = s.al ?: SongAlbum(),
                    ar = s.ar,
                    dt = s.dt
                )
            }
        }.onFailure { e ->
            // #8：失败与真空区分——失败置错误态（保留旧列表数据，重试走 reloadTick 既有刷新语义）
            loadError = e.message?.takeIf { it.isNotBlank() } ?: "云盘加载失败"
        }
        loading = false
    }

    // 云盘上传（2.0.5，C8）：选本地音频文件 → NOS 四步链，进度/失败可见。
    var uploading by remember { mutableStateOf(false) }
    var uploadText by remember { mutableStateOf("") }
    var uploadFraction by remember { mutableStateOf<Float?>(null) }
    fun startUpload() {
        val dialog = java.awt.FileDialog(
            null as java.awt.Frame?, "选择要上传的音频文件", java.awt.FileDialog.LOAD
        )
        dialog.setFilenameFilter { _, name ->
            name.lowercase().let {
                it.endsWith(".mp3") || it.endsWith(".flac") || it.endsWith(".m4a") ||
                    it.endsWith(".wav") || it.endsWith(".ogg")
            }
        }
        dialog.isVisible = true
        val dir = dialog.directory
        val name = dialog.file
        dialog.dispose()
        if (dir == null || name == null) return
        val file = java.io.File(dir, name)
        val uid = profile?.userId ?: 0L
        uploading = true
        uploadText = "准备上传 ${file.name}…"
        uploadFraction = null
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    CloudUploadHelper.upload(file, uid) { p ->
                        uploadText = p.step
                        uploadFraction = p.fraction
                    }
                }
            }
            uploading = false
            result.onSuccess { msg ->
                android.widget.Toast.makeText(
                    context, "${file.name}：$msg", android.widget.Toast.LENGTH_LONG
                ).show()
                uploadText = ""
                reloadTick++
            }.onFailure { e ->
                uploadText = "上传失败：${e.message}"
                android.widget.Toast.makeText(
                    context, "上传失败：${e.message}", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("音乐云盘", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Text(
                "${songs.size} 首",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Button(enabled = profile != null && !uploading, onClick = { startUpload() }) {
                Text(if (uploading) "上传中…" else "上传音乐")
            }
        }
        if (uploadText.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                uploadText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            uploadFraction?.let { f ->
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { f },
                    modifier = Modifier.fillMaxWidth(0.5f)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "云盘歌曲可直接播放（走官方取链），右键可删除。上传走 NOS 四步链（校验→凭证→上传→发布）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        when {
            profile == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("登录后查看你的音乐云盘", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { navController.navigate(Screen.Login.route) }) { Text("去登录") }
                }
            }
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            loadError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "云盘加载失败：${loadError}",
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { reloadTick++ }) { Text("重试") }
                }
            }
            songs.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("云盘里还没有歌曲", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> SongTable(
                songs = songs,
                onPlayAt = { index ->
                    val player = playerState?.player ?: return@SongTable
                    player.setPlaylist(songs, sourceId = -1, sourceName = "音乐云盘")
                    player.playMediaAt(index)
                },
                onPlayNext = { song -> playerState?.player?.insertToPlaylist(song) },
                onAddToQueue = { song -> playerState?.player?.addToPlaylist(song) },
                onArtistClick = { },
                onAlbumClick = { },
                modifier = Modifier.fillMaxSize(),
                onRemoveFromPlaylist = { song ->
                    scope.launch {
                        val r = apiGet<ApiCodeResponse>(
                            "/user/cloud/del", mapOf("id" to song.id.toString())
                        )
                        if (r.getOrNull()?.code == 200) {
                            android.widget.Toast.makeText(
                                context, "已从云盘删除", android.widget.Toast.LENGTH_SHORT
                            ).show()
                            reloadTick++
                        } else {
                            android.widget.Toast.makeText(
                                context, "删除失败", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                removalLabel = "从云盘删除"
            )
        }
    }
}

// ---------------------------------------------------------------- 排行榜

@Composable
fun ToplistPage(navController: NavController) {
    var charts by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var retryTick by remember { mutableStateOf(0) }
    LaunchedEffect(retryTick) {
        loading = true
        loadError = null
        val result = PlaylistApi.topList()
        result.onSuccess { charts = it.list }
            .onFailure { e ->
                // #8：失败态与真空榜单区分
                loadError = e.message?.takeIf { it.isNotBlank() } ?: "排行榜加载失败"
            }
        loading = false
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp)) {
        Text("排行榜", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (loadError != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("排行榜加载失败：${loadError}", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { retryTick++ }) { Text("重试") }
                }
            }
        } else if (charts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无榜单数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(charts, key = { it.id }) { playlist ->
                    PlaylistCard(playlist) { navController.navigate(PlaylistNav(playlist.id)) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 歌单广场

@Composable
fun PlaylistSquarePage(navController: NavController) {
    var categories by remember { mutableStateOf<List<String>>(listOf("全部")) }
    var selectedCat by remember { mutableStateOf("全部") }
    var order by remember { mutableStateOf("hot") }
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var retryTick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        val root = apiGet<JsonObject>("/playlist/catlist", emptyMap()).getOrNull()
        val subs = runCatching {
            root?.get("sub")?.jsonArray?.mapNotNull {
                it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
            }.orEmpty()
        }.getOrDefault(emptyList())
        if (subs.isNotEmpty()) categories = listOf("全部") + subs.distinct()
    }
    LaunchedEffect(selectedCat, order, retryTick) {
        loading = true
        loadError = null
        val result = apiGet<SquarePlaylistsResponse>(
            "/top/playlist",
            mapOf("cat" to selectedCat, "order" to order, "limit" to 50, "offset" to 0)
        )
        result.onSuccess { playlists = it.playlists }
            .onFailure { e ->
                // #8：失败态与「分类真无歌单」区分
                loadError = e.message?.takeIf { it.isNotBlank() } ?: "歌单加载失败"
            }
        loading = false
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("歌单广场", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            listOf("hot" to "热门", "new" to "最新").forEach { (value, label) ->
                val selected = order == value
                Box(
                    Modifier.clip(RoundedCornerShape(14.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                        .clickable { order = value }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        // 分类 chips（两行横向排布，点选切换）
        categories.chunked(10).forEach { rowCats ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowCats.forEach { cat ->
                    val selected = selectedCat == cat
                    Box(
                        Modifier.clip(RoundedCornerShape(14.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            .clickable { selectedCat = cat }
                            .padding(horizontal = 11.dp, vertical = 5.dp)
                    ) {
                        Text(
                            cat,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(6.dp))
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (loadError != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("歌单加载失败：${loadError}", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { retryTick++ }) { Text("重试") }
                }
            }
        } else if (playlists.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("这个分类暂时没有歌单", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistCard(playlist) { navController.navigate(PlaylistNav(playlist.id)) }
                }
            }
        }
    }
}

@Composable
private fun PlaylistCard(playlist: Playlist, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
    ) {
        ArtworkImage(
            url = playlist.coverImgUrl.ifBlank { playlist.picUrl },
            modifier = Modifier.fillMaxWidth().height(150.dp),
            corner = 10.dp,
            thumbSize = 300
        )
        Spacer(Modifier.height(6.dp))
        Text(
            playlist.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "${formatCount(playlist.playCount)} 播放 · ${playlist.trackCount} 首",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatCount(count: Double): String = when {
    count >= 100_000_000 -> "%.1f亿".format(count / 100_000_000)
    count >= 10_000 -> "%.1f万".format(count / 10_000)
    else -> count.toLong().toString()
}
