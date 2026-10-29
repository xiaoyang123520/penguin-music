package com.jussicodes.music.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import androidx.datastore.preferences.core.edit
import com.jussicodes.music.desktop.DesktopPrefs
import com.jussicodes.music.desktop.PLAYBACK_RATES
import com.jussicodes.music.desktop.formatPlaybackSpeed
import com.jussicodes.music.desktop.mv.MvVlcPanel
import com.jussicodes.music.utils.dataStore
import com.rcmiku.ncmapi.api.mv.MvApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * MV 播放窗口（2.1.0 上线，2.1.1 换引擎）：独立 Compose Window，
 * 内嵌 VLCJ（libVLC）播放面板（[MvVlcPanel]）。2.1.0 曾用 JCEF
 * HTML5 video，但发货包的标准版 CEF 构建不含 H.264 解码、网易云
 * MV 全 H.264 故播不了，2.1.1 改走 libVLC 自带 FFmpeg 解码链。
 * 地址经 MvApi（/mv/detail + /mv/url）解析；底部控制条的进度由
 * 面板每 500ms 回报驱动，播放/暂停/拖动进度反向控制面板；
 * 播放出错如实报错并提供「用系统浏览器打开」兜底。
 * 2.1.2：调用方按 mvId key 整窗重建（A3-01 换片修复）；错误事件码
 * 在本窗映射成人话、原码只进日志（A3-03）；控制条加音量滑杆（只控
 * VLC 组件、跨换片记忆）与全屏按钮（WindowPlacement 切换）（A3-09）。
 */
@Composable
fun MvPlayerWindow(request: MvPlayRequest) {
    val windowState = rememberWindowState(width = 960.dp, height = 600.dp)
    var videoUrl by remember { mutableStateOf<String?>(null) }
    var titleText by remember { mutableStateOf(request.title) }
    var artistText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var posSec by remember { mutableStateOf(0.0) }
    var durSec by remember { mutableStateOf(0.0) }
    var isPaused by remember { mutableStateOf(true) }
    var seeking by remember { mutableStateOf(false) }
    var seekDraft by remember { mutableStateOf(0f) }
    val handle = remember { MvVlcPanel.Handle() }
    // MV 音量（A3-09）：UI 0..1 对应 vlcj 0..100 标称段（Handle 内部钳位）。
    // 初值读 MvVlcPanel 的进程级记忆：本窗按 mvId key 整窗重建（A3-01）
    // 时 remember 会重置，靠这份外层记忆保证换片不弹回满格；与主播放器
    // 音量完全独立（只写 VLC 组件，不碰主 Player）。
    var volume by remember { mutableStateOf(MvVlcPanel.rememberedVolume / 100f) }
    var volumeBeforeMute by remember { mutableStateOf(volume) }
    // 2.2.0（A5-13/L6）：清晰度档位、取链重试代次、面板重建代次、
    // 播完标记、切档恢复位置。clarity=null 表示落盘值尚未读到（读到
    // 后才发取链请求，避免先按默认档取一次又按落盘档重取）。
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var brs by remember { mutableStateOf<List<Int>>(emptyList()) }
    // 2.3.0（A6-07）：本支 MV 的详情是否已取完——取链 effect 须等
    // brs 到位才跑，否则落盘档位无从校验（旧实现取链与取详情赛跑，
    // brs 为空时直接用落盘值取链，跨 MV 档位错配）。
    var detailDone by remember { mutableStateOf(false) }
    var clarity by remember { mutableStateOf<Int?>(null) }
    var loadAttempt by remember { mutableStateOf(0) }
    var panelAttempt by remember { mutableStateOf(0) }
    var endedFlag by remember { mutableStateOf(false) }
    var resumeAtSec by remember { mutableStateOf(0.0) }
    // 切档时置真：取链 effect 据此把当前进度作为新面板的起播位置。
    var switchingClarity by remember { mutableStateOf(false) }
    // MV 倍速（2.2.0，L5）：与音乐共用落盘键；改档经下方 effect 与
    // 控制条按钮双路同步到 Handle（即时）与 rememberedRate（新会话）。
    val mvSpeed by context.dataStore.data
        .map { it[DesktopPrefs.playbackSpeed] ?: 1f }
        .collectAsState(initial = 1f)
    LaunchedEffect(mvSpeed) {
        MvVlcPanel.rememberedRate = mvSpeed
        handle.setRate(mvSpeed)
    }

    fun switchClarity(newClarity: Int) {
        if (clarity == newClarity) return
        switchingClarity = true
        clarity = newClarity
        scope.launch {
            context.dataStore.edit { it[DesktopPrefs.mvClarity] = newClarity }
        }
    }

    LaunchedEffect(request.mvId) {
        clarity = context.dataStore.data.first()[DesktopPrefs.mvClarity] ?: 0
    }

    LaunchedEffect(request.mvId) {
        // A3-01 双保险：调用方已按 mvId key 整窗重建，这里再把每支 MV 的
        // 会话状态清零——防将来有不走 key 的复用路径时旧片状态残留
        // （旧地址/旧进度/旧错误横幅串到新片上）。
        videoUrl = null
        errorText = null
        posSec = 0.0
        durSec = 0.0
        isPaused = true
        seeking = false
        seekDraft = 0f
        titleText = request.title
        artistText = ""
        brs = emptyList()
        endedFlag = false
        resumeAtSec = 0.0
        handle.lastFailureDetail = null
        detailDone = false
        val detail = runCatching { MvApi.mvDetail(request.mvId) }.getOrNull()
            ?.getOrNull()?.data
        if (detail != null) {
            if (detail.name.isNotBlank()) titleText = detail.name
            artistText = detail.artistName
            // L6：可选清晰度 = detail.brs（降序去重）；实际默认档与
            // 选档都经取链 effect 的 effectiveR 计算。
            brs = detail.brs.map { it.br }.filter { it > 0 }
                .distinct().sortedDescending()
        }
        // 详情取完（成功与否）才放行取链；失败时 brs 为空，取链
        // effect 按「无法校验」处理，仍用落盘值取链（不阻塞播放）。
        detailDone = true
    }

    // 取链（2.2.0 拆分）：依赖清晰度与重试代次。A5-13 的取链失败重试
    // 与 L6 的切档重取共用此路。
    // 2.3.0（A6-07）：落盘档位跨 MV 沿用时先校验是否在本支 brs 内——
    // 不在则回落到本支可用档位（与自动档同一规则）再取链；brs 为空
    // （详情失败）时无法校验，仍用落盘值取链，不阻塞播放。
    LaunchedEffect(request.mvId, clarity, loadAttempt, detailDone) {
        if (!detailDone) return@LaunchedEffect
        val c = clarity ?: return@LaunchedEffect
        val autoR = brs.filter { it <= 1080 }.maxOrNull() ?: brs.maxOrNull() ?: 1080
        val effectiveR = when {
            c > 0 && brs.isNotEmpty() && c !in brs -> autoR
            c > 0 -> c
            brs.isNotEmpty() -> autoR
            else -> 1080
        }
        val wasSwitching = switchingClarity
        switchingClarity = false
        if (wasSwitching) {
            resumeAtSec = posSec
            handle.dispose()
            // 强制面板重建：即使服务端对新档返回同一地址字符串，
            // key 里的 panelAttempt 也能保证旧 Session 被换掉。
            panelAttempt++
        }
        videoUrl = null
        errorText = null
        val url = runCatching { MvApi.mvUrl(request.mvId, effectiveR) }.getOrNull()
            ?.getOrNull()?.data?.url
        if (url.isNullOrBlank()) {
            errorText = "MV 播放地址获取失败（可能需要登录，或该 MV 为会员/地区受限内容）"
        } else {
            videoUrl = url
        }
    }
    DisposableEffect(Unit) {
        onDispose { handle.dispose() }
    }

    Window(
        onCloseRequest = { DesktopUiState.closeMv() },
        title = "MV · $titleText",
        state = windowState
    ) {
        Column(Modifier.fillMaxSize().background(Color(0xFF101014))) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                when {
                    errorText != null -> Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(errorText!!, color = Color.White)
                        // 2.2.0（A5-13）：窗内重试——旧版错误态只有
                        // 「浏览器打开」一条路，瞬时网络/VLC 抖动就得
                        // 关窗重开整支 MV。取链失败（无 URL）→ 重跑取链
                        // effect；VLC 错误（有 URL）→ 处置旧 Session 并
                        // 重建面板原地再播。
                        TextButton(onClick = {
                            val hadUrl = videoUrl != null
                            errorText = null
                            if (hadUrl) {
                                handle.dispose()
                                resumeAtSec = posSec
                                panelAttempt++
                            } else {
                                loadAttempt++
                            }
                        }) { Text("重试", color = Color(0xFF9DB8FF)) }
                        // 解码失败等情形的兜底：系统浏览器打开同一地址。
                        val playableUrl = videoUrl
                        if (playableUrl != null) {
                            TextButton(onClick = {
                                runCatching {
                                    java.awt.Desktop.getDesktop()
                                        .browse(java.net.URI(playableUrl))
                                }
                            }) { Text("用系统浏览器打开", color = Color(0xFF9DB8FF)) }
                        }
                    }
                    videoUrl == null -> Row(
                        Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Color.White)
                        Spacer(Modifier.width(10.dp))
                        Text("正在加载 MV…", color = Color.White)
                    }
                    else -> key(videoUrl, panelAttempt) {
                        SwingPanel(
                        factory = {
                            MvVlcPanel.createComponent(videoUrl!!, handle, startAtSec = resumeAtSec) { ev ->
                                when {
                                    ev.startsWith("pos:") -> {
                                        val parts = ev.removePrefix("pos:").split(":")
                                        posSec = parts.getOrNull(0)?.toDoubleOrNull() ?: posSec
                                        durSec = parts.getOrNull(1)?.toDoubleOrNull() ?: durSec
                                        isPaused = parts.getOrNull(2) == "1"
                                        if (!isPaused) endedFlag = false
                                    }
                                    ev == "ended" -> {
                                        isPaused = true
                                        // 2.2.0（A5-13）：播完置标记，下方
                                        // 覆盖层给「重新播放」引导（旧版播完
                                        // 只剩黑屏、无任何提示）。
                                        endedFlag = true
                                    }
                                    ev.startsWith("error:") -> {
                                        // A3-03：内部事件码只进日志，用户文案
                                        // 在宿主侧按码映射成人话；A3-05 的具体
                                        // 失败原因经 Handle 旁路字段带过来拼入。
                                        val code = ev.removePrefix("error:")
                                        println(
                                            "[MV] vlc error code=$code mvId=${request.mvId} " +
                                                "detail=${handle.lastFailureDetail ?: "-"}"
                                        )
                                        errorText = when (code) {
                                            "vlc-init" -> buildString {
                                                append("MV 播放引擎初始化失败，无法播放。")
                                                handle.lastFailureDetail?.let {
                                                    append(it)
                                                    if (!it.endsWith("。")) append("。")
                                                }
                                                append("请检查杀毒软件隔离区、确认程序已完整解压后重试，或用系统浏览器打开。")
                                            }
                                            else -> "MV 播放失败，请检查网络后重试，或用系统浏览器打开"
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                // 2.2.0（A5-13）：播完覆盖层——黑屏给「重新播放」引导。
                if (endedFlag && errorText == null && videoUrl != null) {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("播放结束", color = Color.White)
                        Spacer(Modifier.size(10.dp))
                        Button(onClick = {
                            endedFlag = false
                            isPaused = false
                            handle.replay()
                        }) { Text("重新播放") }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    // 播完态点播放键 = 重播（A5-13）；其余与旧行为一致。
                    if (endedFlag) {
                        endedFlag = false
                        isPaused = false
                        handle.replay()
                    } else if (isPaused) {
                        handle.play()
                    } else {
                        handle.pause()
                    }
                }) {
                    Icon(
                        if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (isPaused) "播放" else "暂停",
                        tint = Color.White
                    )
                }
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        listOf(titleText, artistText).filter { it.isNotBlank() }.joinToString(" · "),
                        color = Color.White,
                        maxLines = 1
                    )
                    if (durSec > 0) {
                        Slider(
                            value = if (seeking) seekDraft
                                else (posSec / durSec).toFloat().coerceIn(0f, 1f),
                            onValueChange = { seeking = true; seekDraft = it },
                            onValueChangeFinished = {
                                handle.seekTo(seekDraft.toDouble() * durSec)
                                seeking = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "${formatMvTime(posSec)} / ${formatMvTime(durSec)}",
                    color = Color(0xFFBBBBBB)
                )
                Spacer(Modifier.width(10.dp))
                // 清晰度（2.2.0，L6）：档位来自 detail.brs；选「自动」
                // 时取最高 ≤1080（无则最大）。切换保留进度重建面板，
                // 选档落盘下次沿用。仅一档时不显示（无可切换）。
                if (brs.size > 1) {
                    var clarityMenuOpen by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { clarityMenuOpen = true }) {
                            Text(
                                if (clarity == null || clarity == 0) "自动" else "${clarity}P",
                                color = Color.White
                            )
                        }
                        DropdownMenu(
                            expanded = clarityMenuOpen,
                            onDismissRequest = { clarityMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("自动") },
                                onClick = { clarityMenuOpen = false; switchClarity(0) }
                            )
                            brs.forEach { br ->
                                DropdownMenuItem(
                                    text = { Text("${br}P") },
                                    onClick = { clarityMenuOpen = false; switchClarity(br) }
                                )
                            }
                        }
                    }
                }
                // 倍速（2.2.0，L5）：点击循环切档，与音乐共用落盘键，
                // 经 mvSpeed effect 同步到 VLC rate。
                TextButton(onClick = {
                    val idx = PLAYBACK_RATES.indexOf(mvSpeed)
                        .takeIf { it >= 0 } ?: PLAYBACK_RATES.indexOf(1f)
                    val next = PLAYBACK_RATES[(idx + 1) % PLAYBACK_RATES.size]
                    scope.launch {
                        context.dataStore.edit { it[DesktopPrefs.playbackSpeed] = next }
                    }
                    handle.setRate(next)
                }) {
                    Text(formatPlaybackSpeed(mvSpeed), color = Color.White)
                }
                // 音量（A3-09）：只控本窗 VLC 组件，与主播放器音量互不干扰；
                // 点图标静音/恢复，滑杆 0..1 → vlcj 0..100 标称段。
                IconButton(onClick = {
                    if (volume > 0f) {
                        volumeBeforeMute = volume
                        volume = 0f
                        handle.setVolume(0)
                    } else {
                        val restore = volumeBeforeMute.takeIf { it > 0f } ?: 1f
                        volume = restore
                        handle.setVolume((restore * 100).roundToInt())
                    }
                }) {
                    Icon(
                        if (volume == 0f) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                        contentDescription = if (volume == 0f) "取消静音" else "静音",
                        tint = Color.White
                    )
                }
                Slider(
                    value = volume,
                    onValueChange = {
                        volume = it
                        handle.setVolume((it * 100).roundToInt())
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.width(84.dp)
                )
                // 全屏（A3-09）：Compose Window 的 placement 切换；退出回到
                // Floating 时 WindowState 保留浮动尺寸/位置，即恢复原尺寸。
                val isFullscreen = windowState.placement == WindowPlacement.Fullscreen
                IconButton(onClick = {
                    windowState.placement = if (isFullscreen) {
                        WindowPlacement.Floating
                    } else {
                        WindowPlacement.Fullscreen
                    }
                }) {
                    Icon(
                        if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = if (isFullscreen) "退出全屏" else "全屏",
                        tint = Color.White
                    )
                }
                TextButton(onClick = { DesktopUiState.closeMv() }) {
                    Text("关闭", color = Color.White)
                }
            }
        }
    }
}

private fun formatMvTime(sec: Double): String {
    if (sec <= 0 || sec.isNaN()) return "0:00"
    val total = sec.toInt()
    return "%d:%02d".format(total / 60, total % 60)
}
