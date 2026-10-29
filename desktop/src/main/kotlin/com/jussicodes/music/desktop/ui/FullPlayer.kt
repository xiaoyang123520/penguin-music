package com.jussicodes.music.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.jussicodes.music.LocalPlayerState
import com.jussicodes.music.utils.dataStore
import com.jussicodes.music.desktop.lyrics.DesktopLyricController
import com.jussicodes.music.desktop.lyrics.LyricRepository
import com.jussicodes.music.desktop.lyrics.buildSweptText
import com.jussicodes.music.extensions.cacheSongs
import com.jussicodes.music.ui.theme.rememberArtworkSeed
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 全屏播放页（对标网易云 Windows 版播放页 + YesPlayMusic）：
 * 封面高斯模糊放大作背景 + 取色渐变；左大封面/歌名/控制区，
 * 右滚动歌词（当前行高亮居中、点击行 seek、翻译可开关）。
 * Esc 或左上收起按钮退出（Esc 在壳层处理）。
 */
@Composable
fun FullPlayer() {
    val playerState = LocalPlayerState.current ?: return
    val player = playerState.player
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isPlaying = playerState.isPlaying

    val queueSongs = cacheSongs.collectAsState().value
    val currentSong = queueSongs?.getOrNull(playerState.mediaItemIndex)
    val title = currentSong?.name ?: playerState.mediaMetadata.title?.toString().orEmpty()
    val artist = currentSong?.ar?.joinToString(" / ") { it.name }
        ?: playerState.mediaMetadata.artist?.toString().orEmpty()
    val album = currentSong?.al?.name ?: playerState.mediaMetadata.albumTitle?.toString().orEmpty()
    val artwork = currentSong?.al?.picUrl?.takeIf { it.isNotBlank() }
        ?: playerState.mediaMetadata.artworkUri?.toString()
    val songId = currentSong?.id ?: playerState.currentMediaItem?.mediaId?.toLongOrNull() ?: 0L

    val seed = rememberArtworkSeed(artwork)
    val likedIds by LikeController.likedIds.collectAsState()
    val liked = songId > 0 && songId in likedIds
    val lyricEnabled by DesktopLyricController.enabled.collectAsState()

    // 音效对话框（2.1.3，A4-04）：与设置页/播放条共用同一 EqDialog
    var showEqDialog by remember { mutableStateOf(false) }
    if (showEqDialog) {
        com.jussicodes.music.ui.screen.EqDialog(onDismiss = { showEqDialog = false })
    }

    // 歌词
    var entries by remember { mutableStateOf<List<com.jussicodes.music.desktop.lyrics.LyricEntry>>(emptyList()) }
    // 译文开关在下方以 DataStore 落盘形式声明（2.2.0，A5-14）。
    // A3-07：右侧页签 0=歌词 1=评论（评论以 PlayerComments 弹层呈现，关闭回到歌词）
    var rightTab by remember { mutableStateOf(0) }
    // 本地歌（2.1.0）：歌词走同目录同名 .lrc，不走在线 /lyric/new。
    val localFile = com.jussicodes.music.desktop.local.LocalLibrary.fileOf(
        playerState.currentMediaItem?.mediaId
    )
    LaunchedEffect(songId, localFile) {
        // #12：切歌先清旧词——新词到达前 currentIndex 对空表计算恒为 -1（安全降级），
        // 不再出现「新歌位置套旧词高亮/滚动」的错位帧。
        entries = emptyList()
        entries = when {
            localFile != null -> runCatching {
                com.jussicodes.music.desktop.local.LocalLyrics.loadFor(localFile)
            }.getOrDefault(emptyList())
            songId > 0 -> runCatching { LyricRepository.load(songId) }.getOrDefault(emptyList())
            else -> emptyList()
        }
    }
    val hasTranslation = entries.any { !it.translation.isNullOrBlank() }
    // 罗马音（2.0.5，C6）：开关落 DataStore，与设置页歌词组同键。
    val hasRoma = entries.any { !it.roma.isNullOrBlank() }
    val showRoma by context.dataStore.data
        .map { it[com.jussicodes.music.desktop.DesktopPrefs.lyricRoma] ?: false }
        .collectAsState(initial = false)
    // 译文开关（2.2.0，A5-14）：改 DataStore 落盘，与罗马音同口径——
    // 旧实现是 remember 内存态，关掉重开全屏页/重启即恢复显示。
    val showTranslation by context.dataStore.data
        .map { it[com.jussicodes.music.desktop.DesktopPrefs.lyricTranslation] ?: true }
        .collectAsState(initial = true)

    // 位置轮询
    var position by remember { mutableLongStateOf(player.currentPosition.coerceAtLeast(0)) }
    var duration by remember { mutableLongStateOf(player.duration.coerceAtLeast(0)) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(songId, isPlaying) {
        while (isActive) {
            if (dragFraction == null) {
                position = player.currentPosition.coerceAtLeast(0)
                duration = player.duration.coerceAtLeast(0)
            }
            delay(300)
        }
    }
    val shownPosition = dragFraction?.let { (it * duration).toLong() } ?: position
    val currentIndex = LyricRepository.indexAt(entries, shownPosition)

    val listState = rememberLazyListState()
    // #13：自动滚动用户守卫——记录最近一次「非程序触发」的滚动时刻，
    // 用户手动滚动中及停手后 3 秒宽限期内不抢滚动；宽限期过后由当前行
    // 的滚动 effect 等待到期再恢复跟随。autoScrolling 区分程序滚动与
    // 用户滚动，避免 animateScrollToItem 自身被误记为手动滚动。
    var lastUserScrollMs by remember { mutableLongStateOf(0L) }
    var autoScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !autoScrolling) {
                lastUserScrollMs = System.currentTimeMillis()
            }
        }
    }
    // 宽限期到期主动恢复：lastUserScrollMs 每次手动滚动都会刷新并重启本
    // effect，停手满 3 秒才自增 followTick，令下方跟随 effect 重跑——
    // 否则长歌词行期间滚走要等下一行变化才恢复跟随。
    var followTick by remember { mutableStateOf(0) }
    LaunchedEffect(lastUserScrollMs) {
        if (lastUserScrollMs == 0L) return@LaunchedEffect
        delay(3_000)
        followTick++
    }
    LaunchedEffect(currentIndex, followTick) {
        if (currentIndex < 0) return@LaunchedEffect
        // 等待用户滚动结束且宽限期（3 秒）到期；期间 currentIndex 变化会
        // 取消本 effect 并由新行重新等待，不会堆积过期滚动。
        while (listState.isScrollInProgress ||
            System.currentTimeMillis() - lastUserScrollMs < 3_000L
        ) {
            if (listState.isScrollInProgress && !autoScrolling) {
                lastUserScrollMs = System.currentTimeMillis()
            }
            delay(250)
        }
        if (entries.isEmpty()) return@LaunchedEffect
        autoScrolling = true
        try {
            runCatching { listState.animateScrollToItem(currentIndex, -260) }
        } finally {
            autoScrolling = false
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 背景：封面模糊放大 + 取色渐变 + 暗罩（保证文字对比度）
        if (artwork != null) {
            AsyncImage(
                model = artwork.thumb(400),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(90.dp)
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        (seed ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.35f),
                        Color.Black.copy(alpha = 0.55f),
                        Color.Black.copy(alpha = 0.78f)
                    )
                )
            )
        )

        Column(Modifier.fillMaxSize()) {
            // 顶栏
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { DesktopUiState.fullPlayerOpen.value = false }) {
                    Icon(Icons.Filled.KeyboardArrowDown, "收起", tint = Color.White, modifier = Modifier.size(26.dp))
                }
                Text("正在播放", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                if (hasRoma) {
                    Box(
                        Modifier.clip(RoundedCornerShape(6.dp))
                            .background(if (showRoma) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f))
                            .clickable {
                                scope.launch {
                                    context.dataStore.edit { prefs ->
                                        prefs[com.jussicodes.music.desktop.DesktopPrefs.lyricRoma] = !showRoma
                                    }
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text("罗马音", color = Color.White, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                if (hasTranslation) {
                    Box(
                        Modifier.clip(RoundedCornerShape(6.dp))
                            .background(if (showTranslation) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f))
                            .clickable {
                                scope.launch {
                                    context.dataStore.edit { prefs ->
                                        prefs[com.jussicodes.music.desktop.DesktopPrefs.lyricTranslation] =
                                            !showTranslation
                                    }
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text("译", color = Color.White, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                // 音效（2.1.3，A4-04）：均衡器 + 混响 + 8D 对话框
                IconButton(onClick = { showEqDialog = true }) {
                    Icon(
                        Icons.Filled.GraphicEq, "音效",
                        tint = Color.White.copy(alpha = 0.8f)
                    )
                }
                IconButton(onClick = {
                    val target = !lyricEnabled
                    DesktopLyricController.setEnabled(target)
                    android.widget.Toast.makeText(
                        context,
                        if (target) "桌面歌词已打开" else "桌面歌词已关闭",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }) {
                    Icon(
                        Icons.Filled.Subtitles, "桌面歌词",
                        tint = if (lyricEnabled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.8f)
                    )
                }
                // MV 播放（2.1.0）：当前歌曲带 MV 时出现。
                val mvId = currentSong?.mv ?: 0L
                if (mvId > 0) {
                    IconButton(onClick = {
                        DesktopUiState.openMv(mvId, currentSong?.name ?: "MV")
                    }) {
                        Icon(
                            Icons.Filled.OndemandVideo, "播放 MV",
                            tint = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
                // 一起听（2.1.0）。
                IconButton(onClick = { DesktopUiState.listenTogetherOpen.value = true }) {
                    Icon(
                        Icons.Filled.Groups, "一起听",
                        tint = Color.White.copy(alpha = 0.8f)
                    )
                }
            }

            Row(Modifier.fillMaxSize().padding(start = 44.dp, end = 44.dp, bottom = 20.dp)) {
                // 左：封面 + 信息 + 控制
                Column(
                    modifier = Modifier.width(340.dp).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    ArtworkImage(
                        url = artwork,
                        modifier = Modifier.size(300.dp),
                        corner = 14.dp,
                        thumbSize = 600
                    )
                    Spacer(Modifier.height(22.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                title.ifBlank { "未知歌曲" },
                                color = Color.White,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Text(
                                listOf(artist, album).filter { it.isNotBlank() }.joinToString(" · "),
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1
                            )
                        }
                        IconButton(onClick = {
                            if (songId > 0 && !LikeController.toggle(context, songId)) {
                                android.widget.Toast.makeText(context, "请先登录", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(
                                if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                "喜欢",
                                tint = if (liked) Color(0xFFE83E52) else Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    // 进度
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatDuration(shownPosition), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(36.dp))
                        Slider(
                            value = if (duration > 0) (shownPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                            onValueChange = { dragFraction = it },
                            onValueChangeFinished = {
                                dragFraction?.let { f -> if (duration > 0) player.seekTo((f * duration).toLong()) }
                                dragFraction = null
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier.weight(1f).height(24.dp)
                        )
                        Text(formatDuration(duration), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(36.dp))
                    }
                    // 走带
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val shuffleOn = playerState.shuffleModeEnabled
                        IconButton(onClick = { player.shuffleModeEnabled = !shuffleOn }) {
                            Icon(Icons.Filled.Shuffle, "随机", tint = if (shuffleOn) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.8f), modifier = Modifier.size(19.dp))
                        }
                        IconButton(onClick = { player.seekToPrevious() }) {
                            Icon(Icons.Filled.SkipPrevious, "上一首", tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                        Box(
                            Modifier.size(54.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable { if (isPlaying) player.pause() else player.play() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "播放/暂停", tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                        IconButton(onClick = { player.seekToNext() }) {
                            Icon(Icons.Filled.SkipNext, "下一首", tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                        val repeatMode = playerState.repeatMode
                        IconButton(onClick = {
                            player.repeatMode = when (repeatMode) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                else -> Player.REPEAT_MODE_OFF
                            }
                        }) {
                            Icon(
                                if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                                "循环",
                                tint = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.width(48.dp))

                // 右：歌词 / 评论页签（A3-07）
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        listOf(0 to "歌词", 1 to "评论").forEach { (tab, label) ->
                            Box(
                                Modifier.clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (rightTab == tab) Color.White.copy(alpha = 0.22f)
                                        else Color.White.copy(alpha = 0.08f)
                                    )
                                    .clickable { rightTab = tab }
                                    .padding(horizontal = 14.dp, vertical = 5.dp)
                            ) {
                                Text(label, color = Color.White, fontSize = 12.sp)
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (entries.isEmpty()) {
                        Text(
                            "暂无歌词",
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            itemsIndexed(entries) { i, entry ->
                                val isCurrent = i == currentIndex
                                val nextTime = entries.getOrNull(i + 1)?.time ?: duration
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { player.seekTo(entry.time) }
                                        .padding(horizontal = 16.dp, vertical = 7.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    if (isCurrent) {
                                        Text(
                                            text = buildSweptText(
                                                entry, shownPosition, nextTime, true,
                                                MaterialTheme.colorScheme.primary, Color.White
                                            ),
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            textAlign = TextAlign.Center
                                        )
                                    } else {
                                        Text(
                                            entry.text,
                                            color = Color.White.copy(alpha = if (kotlin.math.abs(i - currentIndex) <= 2) 0.75f else 0.45f),
                                            style = MaterialTheme.typography.bodyLarge,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                    if (showRoma && !entry.roma.isNullOrBlank()) {
                                        Text(
                                            entry.roma!!,
                                            color = Color.White.copy(alpha = if (isCurrent) 0.75f else 0.38f),
                                            style = MaterialTheme.typography.bodySmall,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                    if (showTranslation && !entry.translation.isNullOrBlank()) {
                                        Text(
                                            entry.translation!!,
                                            color = Color.White.copy(alpha = if (isCurrent) 0.8f else 0.4f),
                                            style = MaterialTheme.typography.bodyMedium,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                    }
                }
            }
        }

        // A3-07：评论页签内容——PlayerComments 本身是 ModalBottomSheet 弹层
        // （与桌面 PlaylistScreen 歌单评论同款用法），切到评论页签即打开，
        // 关闭（onBackPressed）回到歌词页签。mediaId 传当前 songId，
        // 组件内部按 resolvedMediaId 重新拉取，切歌后评论自动跟随新歌。
        if (rightTab == 1) {
            com.jussicodes.music.ui.components.PlayerComments(
                mediaId = songId.takeIf { it > 0 },
                mediaMetadata = playerState.mediaMetadata,
                commentType = 0,
                onBackPressed = { rightTab = 0 }
            )
        }
    }
}
