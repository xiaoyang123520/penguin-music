package com.jussicodes.music.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.datastore.preferences.core.edit
import com.jussicodes.music.LocalPlayerState
import com.jussicodes.music.desktop.DesktopPrefs
import com.jussicodes.music.desktop.PLAYBACK_RATES
import com.jussicodes.music.desktop.formatPlaybackSpeed
import com.jussicodes.music.desktop.lyrics.DesktopLyricController
import com.jussicodes.music.extensions.cacheSongs
import com.jussicodes.music.utils.dataStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 底部整宽播放栏（对标网易云 Windows 版 + 效果图）：三段式——
 * 左 封面/歌名/歌手/红心，中 播放模式+上下曲+播放键 与 时间-进度-总时长，
 * 右 歌词/桌面歌词/队列/音量/全屏。队列为空时整体呈禁用态，不假装能播。
 */
@Composable
fun PlayBar() {
    val playerState = LocalPlayerState.current
    val context = LocalContext.current
    val player = playerState?.player
    val hasQueue = (playerState?.currentMediaItem != null)
    val isPlaying = playerState?.isPlaying == true

    val queueOpen by DesktopUiState.queueOpen.collectAsState()
    val lyricEnabled by DesktopLyricController.enabled.collectAsState()
    val lyricLocked by DesktopLyricController.locked.collectAsState()
    val likedIds by LikeController.likedIds.collectAsState()

    // 当前歌曲（优先队列缓存里的 Song 模型，含真实专辑/歌手 id）
    val currentIndex = playerState?.mediaItemIndex ?: -1
    val queueSongs = cacheSongs.collectAsState().value
    val currentSong = queueSongs?.getOrNull(currentIndex)
    val title = currentSong?.name ?: playerState?.mediaMetadata?.title?.toString().orEmpty()
    val artist = currentSong?.ar?.joinToString(" / ") { it.name }
        ?: playerState?.mediaMetadata?.artist?.toString().orEmpty()
    val artwork = currentSong?.al?.picUrl?.takeIf { it.isNotBlank() }
        ?: playerState?.mediaMetadata?.artworkUri?.toString()
    val liked = currentSong != null && currentSong.id in likedIds

    // 进度轮询（拖动时不被轮询打断，松手才 seek）
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(playerState, playerState?.currentMediaItem) {
        if (player == null) return@LaunchedEffect
        position = player.currentPosition.coerceAtLeast(0)
        duration = player.duration.coerceAtLeast(0)
        while (isActive) {
            if (dragFraction == null) {
                position = player.currentPosition.coerceAtLeast(0)
                duration = player.duration.coerceAtLeast(0)
            }
            delay(400)
        }
    }
    val shownPosition = dragFraction?.let { (it * duration).toLong() } ?: position
    val fraction = if (duration > 0) (shownPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f

    // 音量（直接读写 player，不轮询，避免拖动被刷新打断）
    var volume by remember { mutableFloatStateOf(playerState?.volume ?: 1f) }
    LaunchedEffect(playerState?.volume) { volume = playerState?.volume ?: volume }
    var volumeBeforeMute by remember { mutableFloatStateOf(1f) }

    // A4-04：音效对话框开关（播放条入口，与全屏页/设置页共用同一 EqDialog）
    var showEqDialog by remember { mutableStateOf(false) }
    if (showEqDialog) {
        com.jussicodes.music.ui.screen.EqDialog(onDismiss = { showEqDialog = false })
    }

    Column(Modifier.fillMaxWidth()) {
    // A4-03：常驻错误条（引擎错误停留时出现在播放条上方，不再只有 4 秒 Toast）
    StickyErrorBar(player)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        // 窄窗紧凑模式（自查：最小窗口 900 宽时主列仅 ~697dp，左右固定 300 会把
        // 中段走带挤没）：紧凑时左段收窄、音量只留图标（点击静音仍可用）
        val compact = maxWidth < 780.dp
        Row(
            modifier = Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ---- 左：身份 ----
            Row(
                modifier = Modifier.width(if (compact) 176.dp else 300.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = hasQueue) { DesktopUiState.fullPlayerOpen.value = true }
                ) {
                    ArtworkImage(
                        url = artwork,
                        modifier = Modifier.size(52.dp),
                        corner = 8.dp,
                        thumbSize = 120
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title.ifBlank { "企鹅音乐" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        artist.ifBlank { if (hasQueue) "" else "选择一首歌开始播放" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = {
                        val song = currentSong ?: return@IconButton
                        if (!LikeController.toggle(context, song.id)) {
                            android.widget.Toast.makeText(context, "请先登录", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = currentSong != null,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "喜欢",
                        tint = if (liked) Color(0xFFE83E52) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // ---- 中：走带 + 进度 ----
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val shuffleOn = playerState?.shuffleModeEnabled == true
                    IconButton(
                        onClick = { player?.shuffleModeEnabled = !shuffleOn },
                        enabled = hasQueue,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Filled.Shuffle, "随机",
                            tint = if (shuffleOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    IconButton(
                        onClick = { player?.seekToPrevious() },
                        enabled = hasQueue,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Filled.SkipPrevious, "上一首", modifier = Modifier.size(22.dp))
                    }
                    Box(
                        modifier = Modifier.size(40.dp).clip(CircleShape)
                            .background(if (hasQueue) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(enabled = hasQueue) {
                                if (isPlaying) player?.pause() else player?.play()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "暂停" else "播放",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(
                        onClick = { player?.seekToNext() },
                        enabled = hasQueue,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Filled.SkipNext, "下一首", modifier = Modifier.size(22.dp))
                    }
                    val repeatMode = playerState?.repeatMode ?: Player.REPEAT_MODE_OFF
                    IconButton(
                        onClick = {
                            val next = when (repeatMode) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                else -> Player.REPEAT_MODE_OFF
                            }
                            player?.repeatMode = next
                        },
                        enabled = hasQueue,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            "循环模式",
                            tint = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    // 2.2.0（L5）：倍速档位——点开菜单选档，即时应用到
                    // 引擎并落盘（MainScreen 启动时恢复）。非 1× 时
                    // 文案高亮提示当前处于变速状态。
                    val speedScope = rememberCoroutineScope()
                    val speed by context.dataStore.data
                        .map { it[DesktopPrefs.playbackSpeed] ?: 1f }
                        .collectAsState(initial = 1f)
                    var speedMenuOpen by remember { mutableStateOf(false) }
                    Box {
                        TextButton(
                            onClick = { speedMenuOpen = true },
                            enabled = hasQueue,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(width = 44.dp, height = 32.dp)
                        ) {
                            Text(
                                formatPlaybackSpeed(speed),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (speed != 1f) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        DropdownMenu(
                            expanded = speedMenuOpen,
                            onDismissRequest = { speedMenuOpen = false }
                        ) {
                            PLAYBACK_RATES.forEach { rate ->
                                DropdownMenuItem(
                                    text = { Text(formatPlaybackSpeed(rate)) },
                                    onClick = {
                                        speedMenuOpen = false
                                        player?.setPlaybackSpeed(rate)
                                        speedScope.launch {
                                            context.dataStore.edit {
                                                it[DesktopPrefs.playbackSpeed] = rate
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.width(520.dp)
                ) {
                    Text(
                        formatDuration(shownPosition),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(34.dp)
                    )
                    Slider(
                        value = fraction,
                        onValueChange = { if (hasQueue && duration > 0) dragFraction = it },
                        onValueChangeFinished = {
                            val f = dragFraction
                            if (player != null && f != null && duration > 0) {
                                player.seekTo((f * duration).toLong())
                                position = (f * duration).toLong()
                            }
                            dragFraction = null
                        },
                        enabled = hasQueue && duration > 0,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                        ),
                        modifier = Modifier.weight(1f).height(22.dp)
                    )
                    Text(
                        formatDuration(duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(34.dp)
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // ---- 右：工具 ----
            Row(
                modifier = Modifier.width(if (compact) 196.dp else 300.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                // 音效（2.1.3，A4-04）：打开与设置页同一个音效对话框
                //（均衡器 + 混响 + 8D），播放中随手可调，不必钻设置页。
                IconButton(
                    onClick = { showEqDialog = true },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        Icons.Filled.GraphicEq, "音效",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                }
                // 词：打开全屏页歌词（网易云同款小方块）
                Box(
                    modifier = Modifier.size(30.dp).clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = hasQueue) { DesktopUiState.fullPlayerOpen.value = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text("词", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ContextMenuArea(items = {
                    listOf(
                        ContextMenuItem(if (lyricLocked) "解锁桌面歌词" else "锁定桌面歌词") {
                            DesktopLyricController.setLocked(!lyricLocked)
                        },
                        ContextMenuItem("桌面歌词设置") { LyricSettingsSignal.request() },
                    )
                }) {
                    IconButton(
                        // 2.0.1：去掉 hasQueue 门控——没选歌时也要能开（悬浮窗显示
                        // 标题占位），且切换给 Toast 反馈，不再「点了没反应」。
                        onClick = {
                            val target = !lyricEnabled
                            DesktopLyricController.setEnabled(target)
                            android.widget.Toast.makeText(
                                context,
                                if (target) "桌面歌词已打开" else "桌面歌词已关闭",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Filled.Subtitles, "桌面歌词",
                            tint = if (lyricEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
                IconButton(
                    onClick = { DesktopUiState.queueOpen.value = !queueOpen },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        Icons.Filled.QueueMusic, "播放队列",
                        tint = if (queueOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                }
                IconButton(
                    onClick = { DesktopUiState.listenTogetherOpen.value = true },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        Icons.Filled.Groups, "一起听",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                }
                IconButton(
                    onClick = {
                        if (volume > 0f) {
                            volumeBeforeMute = volume
                            volume = 0f
                            player?.volume = 0f
                        } else {
                            volume = volumeBeforeMute
                            player?.volume = volumeBeforeMute
                        }
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        if (volume == 0f) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                        "音量",
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!compact) {
                Slider(
                    value = volume,
                    onValueChange = {
                        volume = it
                        player?.volume = it
                    },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.width(84.dp).height(22.dp)
                )
                }
                // 定时关闭（2.0.5，C1）：后端 PlayerState.startTimer 一直
                // 现成（安卓有入口），桌面此前零入口。菜单选时长/自定义/
                // 取消，设置后按钮点亮并在提示里显示剩余分钟。
                var timerMenuOpen by remember { mutableStateOf(false) }
                var customTimerOpen by remember { mutableStateOf(false) }
                val timerSet = playerState?.isSleepTimerSet == true
                Box {
                    IconButton(
                        onClick = { timerMenuOpen = true },
                        enabled = playerState != null,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Filled.Schedule, "定时关闭",
                            modifier = Modifier.size(19.dp),
                            tint = if (timerSet) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = timerMenuOpen,
                        onDismissRequest = { timerMenuOpen = false }
                    ) {
                        if (timerSet) {
                            val remainSec = playerState?.remainingTime ?: 0L
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Text("剩余 ${(remainSec / 60) + if (remainSec % 60 > 0) 1 else 0} 分钟（点此取消）")
                                },
                                onClick = {
                                    playerState?.cancelTimer()
                                    timerMenuOpen = false
                                }
                            )
                        }
                        listOf(15L, 30L, 45L, 60L).forEach { minutes ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("$minutes 分钟后停止播放") },
                                onClick = {
                                    playerState?.startTimer(minutes * 60)
                                    timerMenuOpen = false
                                    android.widget.Toast.makeText(
                                        context, "$minutes 分钟后停止播放",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("自定义…") },
                            onClick = { timerMenuOpen = false; customTimerOpen = true }
                        )
                    }
                }
                if (customTimerOpen) {
                    var customText by remember { mutableStateOf("") }
                    androidx.compose.ui.window.Dialog(onDismissRequest = { customTimerOpen = false }) {
                        androidx.compose.material3.Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Column(Modifier.padding(20.dp)) {
                                Text("定时关闭", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(10.dp))
                                androidx.compose.material3.OutlinedTextField(
                                    value = customText,
                                    onValueChange = { customText = it.filter { c -> c.isDigit() } },
                                    label = { Text("分钟（1–600）") },
                                    singleLine = true
                                )
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                    androidx.compose.material3.OutlinedButton(
                                        onClick = { customTimerOpen = false }
                                    ) { Text("取消") }
                                    Spacer(Modifier.width(8.dp))
                                    androidx.compose.material3.Button(
                                        enabled = (customText.toLongOrNull() ?: 0L) in 1L..600L,
                                        onClick = {
                                            val m = customText.toLong()
                                            playerState?.startTimer(m * 60)
                                            customTimerOpen = false
                                            android.widget.Toast.makeText(
                                                context, "$m 分钟后停止播放",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    ) { Text("开始计时") }
                                }
                            }
                        }
                    }
                }
                // 迷你模式（2.0.5，C9）：主窗口缩成置顶小窗播放条
                IconButton(
                    onClick = { DesktopUiState.miniMode.value = true },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        Icons.Filled.PictureInPictureAlt, "迷你模式",
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { DesktopUiState.fullPlayerOpen.value = true },
                    enabled = hasQueue,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        Icons.Filled.Fullscreen, "全屏",
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    }
}

/**
 * 常驻错误条（2.1.3，A4-03）：DesktopPlayer 错误停留时置
 * [com.jussicodes.music.playback.PlaybackStatus.stickyError]，这里渲染
 * 成播放条上方的一条 error 提示：原因 + 「重试」（走新 play() 语义从
 * 出错点重启会话）/「跳下一首」/ 关闭。下一次会话启动会自动清除。
 */
@Composable
private fun StickyErrorBar(player: Player?) {
    val sticky by com.jussicodes.music.playback.PlaybackStatus.stickyError.collectAsState()
    val err = sticky ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                err.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!err.detail.isNullOrBlank()) {
                Text(
                    err.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        androidx.compose.material3.TextButton(
            onClick = { player?.play() },
            enabled = player != null
        ) { Text("重试") }
        androidx.compose.material3.TextButton(
            onClick = {
                if (player?.hasNextMediaItem() == true) {
                    player.seekToNext()
                    player.play()
                }
            },
            enabled = player != null
        ) { Text("跳下一首") }
        IconButton(
            onClick = { com.jussicodes.music.playback.PlaybackStatus.clear() },
            modifier = Modifier.size(30.dp)
        ) {
            Text(
                "✕",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/** 桌面歌词设置跳转信号（壳层监听后打开设置页并定位歌词组）。 */
object LyricSettingsSignal {
    val requests = kotlinx.coroutines.flow.MutableStateFlow(0)
    fun request() { requests.value += 1 }
}
