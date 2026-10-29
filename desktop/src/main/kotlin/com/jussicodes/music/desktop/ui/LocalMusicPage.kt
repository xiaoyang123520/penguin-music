package com.jussicodes.music.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jussicodes.music.LocalPlayerState
import com.jussicodes.music.desktop.local.LocalLibrary
import com.jussicodes.music.desktop.local.LocalLibrary.toMediaItems
import com.jussicodes.music.desktop.local.LocalTrack
import com.jussicodes.music.extensions.cacheSongs
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

/**
 * 本地音乐页（2.1.0）：文件夹管理（添加/移除）+ 曲库列表（搜索、
 * 全部/按专辑/按歌手分组）。点击曲目把当前显示列表整表入队从该首
 * 起播（本地 MediaItem 带 local: mediaId，DesktopPlayer 走本地
 * 解码分支，不经在线音源级联）。
 */
@Composable
fun LocalMusicPage(navController: NavController) {
    val context = LocalContext.current
    val playerState = LocalPlayerState.current
    val folders by LocalLibrary.folders.collectAsState()
    val allTracks by LocalLibrary.tracks.collectAsState()
    val scanning by LocalLibrary.scanning.collectAsState()

    LaunchedEffect(Unit) {
        LocalLibrary.init(context)
        LocalLibrary.rescan()
    }

    var query by remember { mutableStateOf("") }
    var groupMode by remember { mutableStateOf(0) } // 0 全部 / 1 按专辑 / 2 按歌手

    val shown = remember(allTracks, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            allTracks
        } else {
            allTracks.filter {
                it.title.contains(q, true) || it.artist.contains(q, true) ||
                    it.album.contains(q, true) || it.fileName.contains(q, true)
            }
        }
    }

    fun playFrom(list: List<LocalTrack>, index: Int) {
        val player = playerState?.player ?: return
        if (list.isEmpty() || index !in list.indices) return
        // 与云盘列表同口径：清在线歌曲缓存，UI 走 MediaMetadata 展示。
        cacheSongs.value = null
        player.setMediaItems(list.toMediaItems(), index, 0L)
        player.prepare()
        player.play()
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("本地音乐", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Text(
                if (scanning) "正在扫描…" else "共 ${allTracks.size} 首",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = {
                SwingUtilities.invokeLater {
                    val chooser = JFileChooser().apply {
                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        dialogTitle = "选择音乐文件夹"
                    }
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        chooser.selectedFile?.let { LocalLibrary.addFolder(it) }
                    }
                }
            }) { Text("添加文件夹") }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { LocalLibrary.rescan() }) {
                Icon(Icons.Filled.Refresh, "重新扫描")
            }
        }
        Spacer(Modifier.height(10.dp))

        // 文件夹列表
        if (folders.isNotEmpty()) {
            Column {
                folders.forEach { folder ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Icon(
                            Icons.Filled.FolderOpen, null,
                            Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            folder,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "移除",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.clickable { LocalLibrary.removeFolder(folder) }
                                .padding(4.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索本地歌曲") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            listOf("全部", "按专辑", "按歌手").forEachIndexed { i, label ->
                val selected = groupMode == i
                Box(
                    Modifier.clickable { groupMode = i }
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.shapes.small
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.width(6.dp))
            }
            OutlinedButton(onClick = { playFrom(shown, 0) }) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("播放全部")
            }
        }
        Spacer(Modifier.height(12.dp))

        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (folders.isEmpty()) {
                        "还没有添加音乐文件夹。点右上角「添加文件夹」选择本地音乐目录。"
                    } else if (scanning) {
                        "正在扫描文件夹…"
                    } else {
                        "没有匹配的本地歌曲（支持 MP3 / FLAC / WAV / M4A）。"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        when (groupMode) {
            0 -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(shown, key = { _, t -> t.path }) { index, track ->
                    LocalTrackRow(track) { playFrom(shown, index) }
                }
            }
            else -> {
                val groups = remember(shown, groupMode) {
                    shown.groupBy { if (groupMode == 1) it.album else it.artist }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    groups.forEach { (group, list) ->
                        item(key = "h-$group") {
                            Text(
                                "$group（${list.size} 首）",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                        items(list, key = { it.path }) { track ->
                            LocalTrackRow(track) {
                                playFrom(shown, shown.indexOf(track))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalTrackRow(track: LocalTrack, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        Icon(
            Icons.Filled.MusicNote, null, Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                "${track.artist} · ${track.album}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            track.format.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (track.durationMs > 0) formatLocalDuration(track.durationMs) else "--:--",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatLocalDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
