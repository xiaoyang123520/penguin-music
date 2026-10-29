package com.jussicodes.music.desktop

import android.content.Context
import android.content.StringResources
import android.widget.ToastSink
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.datastore.preferences.core.edit
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.crossfade
import com.jussicodes.music.LocalPlayerController
import com.jussicodes.music.LocalPlayerState
import com.jussicodes.music.LocalUiScaleController
import com.jussicodes.music.Strings
import com.jussicodes.music.UiScaleController
import com.jussicodes.music.constants.apiBaseUrlKey
import com.jussicodes.music.constants.apiServerListCleanedKey
import com.jussicodes.music.constants.apiServerListKey
import com.jussicodes.music.constants.audioSourceKey
import com.jussicodes.music.constants.cacheAutoCleanEnabledKey
import com.jussicodes.music.constants.cacheAutoCleanThresholdKey
import com.jussicodes.music.constants.directSourceKey
import com.jussicodes.music.constants.githubDownloadSourceKey
import com.jussicodes.music.constants.ignoredUpdateVersionKey
import com.jussicodes.music.constants.localSourceKey
import com.jussicodes.music.constants.ncmCookieKey
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import com.jussicodes.music.desktop.lyrics.DesktopLyricAwtWindow
import com.jussicodes.music.desktop.lyrics.DesktopLyricController
import com.jussicodes.music.desktop.ui.ArtworkImage
import com.jussicodes.music.desktop.ui.DesktopUiState
import com.jussicodes.music.desktop.ui.LikeController
import com.jussicodes.music.desktop.ui.SessionState
import com.jussicodes.music.constants.themeColorSourceKey
import com.jussicodes.music.constants.uiScaleKey
import com.jussicodes.music.constants.unblockSourceKey
import com.jussicodes.music.data.ExplorePreloader
import com.jussicodes.music.data.HomeBgTextStore
import com.jussicodes.music.data.HomeTransparentCardStore
import com.jussicodes.music.data.HomeUserInfoStore
import com.jussicodes.music.data.MsgSessionCache
import com.jussicodes.music.data.NcmCookieStore
import com.jussicodes.music.data.PinnedAlbumStore
import com.jussicodes.music.extensions.init
import com.jussicodes.music.playback.PlaybackReloadBus
import com.jussicodes.music.playback.PlayerController
import com.jussicodes.music.playback.PlayerState
import com.jussicodes.music.playback.state
import com.jussicodes.music.ui.components.ScaledDialogDensity
import com.jussicodes.music.ui.components.UpdateDialog
import com.jussicodes.music.ui.screen.MainScreen
import com.jussicodes.music.ui.theme.JetMeloTheme
import com.jussicodes.music.ui.theme.ThemeColorSource
import com.jussicodes.music.utils.AppUpdateManager
import com.jussicodes.music.utils.CacheManager
import com.jussicodes.music.utils.apiServers
import com.jussicodes.music.utils.SongListUtil
import com.jussicodes.music.utils.UpdateDownloadService
import com.jussicodes.music.utils.UpdateInfo
import com.jussicodes.music.utils.UserAgentUtil
import com.jussicodes.music.utils.dataStore
import com.jussicodes.music.utils.downloadSources
import com.jussicodes.music.utils.get
import com.jussicodes.music.utils.rememberPreference
import com.rcmiku.ncmapi.api.API_BASE_URL
import com.rcmiku.ncmapi.api.AudioSourceSettings
import com.rcmiku.ncmapi.api.AudioSources
import com.rcmiku.ncmapi.api.LocalSources
import com.rcmiku.ncmapi.api.LxSourceBridge
import com.rcmiku.ncmapi.api.UNBLOCK_SOURCE
import com.rcmiku.ncmapi.api.player.PlayerApi
import com.rcmiku.ncmapi.utils.CookieProvider
import com.rcmiku.ncmapi.utils.FileProvider
import com.rcmiku.ncmapi.utils.UserAgentProvider
import com.rcmiku.ncmapi.utils.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okio.Path.Companion.toPath
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

private data class AudioSync(
    val apiUrl: String?,
    val unblockSource: String?,
    val audioSource: String?,
    val directSource: String?,
)

/**
 * 企鹅音乐 2.0 桌面入口。
 * 进程初始化沿用 1.x（initProcess）；2.0 新增：
 * 自定义标题栏（无边框窗口）、托盘、桌面歌词独立窗、窗口几何恢复、
 * 主题模式（浅/深/跟随系统）接线、媒体键与鼠标侧键、DPI 感知。
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
fun main(args: Array<String>) {
    // 2.0.5（A10）：测试钩子默认在发布包中禁用。此前 --screenshot-screen
    // （可全屏截图落盘）、--clicks（可脚本化点击）、--data-dir（可重定向
    // 数据目录）随发布包裸奔，属于低危后门面。现统一收口：仅当环境变量
    // PENGUIN_DEV_HOOKS=1 或系统属性 penguin.devHooks=true 时才解析这些
    // 参数（沙箱验收脚本显式带上），正式分发不带即全部失效。
    val devHooks = System.getenv("PENGUIN_DEV_HOOKS") == "1" ||
        System.getProperty("penguin.devHooks") == "true"
    val screenshotPath = if (devHooks) {
        args.firstOrNull { it.startsWith("--screenshot=") }?.removePrefix("--screenshot=")
    } else null
    val screenshotScreenPath = if (devHooks) {
        args.firstOrNull { it.startsWith("--screenshot-screen=") }
            ?.removePrefix("--screenshot-screen=")
    } else null
    val autoExitMs = if (devHooks) {
        args.firstOrNull { it.startsWith("--auto-exit-ms=") }
            ?.removePrefix("--auto-exit-ms=")?.toLongOrNull()
    } else null
    if (devHooks) {
        args.firstOrNull { it.startsWith("--data-dir=") }?.removePrefix("--data-dir=")
            ?.let { System.setProperty("jussichords.dataDir", it) }
    }
    // 2.1.0 验收钩子：--open-mv=<mvId> 启动后自动打开 MV 窗（同样受
    // devHooks 门控）；--open-lt 启动后自动打开一起听面板。
    val bootOpenMvId = if (devHooks) {
        args.firstOrNull { it.startsWith("--open-mv=") }?.removePrefix("--open-mv=")
            ?.toLongOrNull()
    } else null
    val bootOpenLt = devHooks && args.any { it == "--open-lt" }

    // 2.0.5（A9）：未捕获异常除 stderr 外落 logs/crash.log——Windows GUI
    // 进程闪退时 stderr 无人看，等于零记录。
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        System.err.println("[uncaught:${thread.name}] $error")
        error.printStackTrace()
        DesktopLog.crash(thread.name, error)
    }

    // 2.0.5（A2）：单实例。已有实例在跑则通知它前置、本进程直接退出，
    // 避免双开同写偏好/队列文件并两路出声。
    val dataDir = File(
        System.getProperty("jussichords.dataDir")
            ?: (System.getProperty("user.home") + File.separator + ".jussichords")
    )
    if (!SingleInstance.tryAcquire(dataDir)) return
    ToastSink.handler = { message ->
        println("[Toast] $message")
        DesktopToast.show(message)
    }
    StringResources.provider = { id -> Strings.byId(id) }

    val context = DesktopContextHolder.context
    initProcess(context)
    DesktopLyricController.init(context)

    // 启动前同步读窗口几何与主题，让首帧就是恢复后的样子
    val bootPrefs = runBlocking { runCatching { context.dataStore.data.first() }.getOrNull() }
    val bootWidth = (bootPrefs?.get(DesktopPrefs.windowWidth) ?: 1280f).coerceIn(900f, 3840f)
    val bootHeight = (bootPrefs?.get(DesktopPrefs.windowHeight) ?: 840f).coerceIn(600f, 2160f)
    val bootX = bootPrefs?.get(DesktopPrefs.windowX)
    val bootY = bootPrefs?.get(DesktopPrefs.windowY)
    val bootMaximized = bootPrefs?.get(DesktopPrefs.windowMaximized) == true
    val bootCloseToTray = bootPrefs?.get(DesktopPrefs.closeToTray) == true
    // 2.0.5（A3）：钳制范围改全部显示器边界的并集。此前只用主屏——窗口
    // 关在副屏时坐标落在主屏范围外，一律被拉回主屏，多屏位置恢复全错
    // （歌词窗 DesktopLyricAwtWindow 早已做全屏设备枚举，此处对齐）。
    val screenBounds = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { it.defaultConfiguration.bounds }
            .reduceOrNull { acc, r -> acc.union(r) }
    }.getOrNull()
    // 全局快捷键（2.0.5，C3）：开关落盘在 bootPrefs 里，handler 在窗口
    // 起来、playerState 就绪后由 LaunchedEffect 接上。
    if (bootPrefs?.get(DesktopPrefs.globalHotkeys) == true) {
        DesktopHotkeys.start()
    }
    // F6 全局媒体键：随启动默认开启，与 globalHotkeys 开关（只管
    // Ctrl+Alt 组合键）互不绑定；非 Windows 平台内部直接跳过。
    DesktopHotkeys.startMediaKeys()
    val safeX = if (bootX != null && screenBounds != null) {
        bootX.coerceIn(screenBounds.x - 200, screenBounds.x + screenBounds.width - 200)
    } else bootX
    val safeY = if (bootY != null && screenBounds != null) {
        bootY.coerceIn(screenBounds.y, screenBounds.y + screenBounds.height - 100)
    } else bootY

    application {
        val appScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
        val windowState = rememberWindowState(
            width = bootWidth.dp,
            height = bootHeight.dp,
            position = if (safeX != null && safeY != null) {
                WindowPosition(safeX.dp, safeY.dp)
            } else WindowPosition.PlatformDefault,
            placement = if (bootMaximized) WindowPlacement.Maximized else WindowPlacement.Floating
        )
        var appVisible by remember { mutableStateOf(true) }
        var closeToTray by remember { mutableStateOf(bootCloseToTray) }
        var playerState by remember { mutableStateOf<PlayerState?>(null) }
        var pendingUpdateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
        var uiScale by rememberPreference(uiScaleKey, 1f)
        var liveUiScale by remember { mutableStateOf(uiScale) }
        var githubDownloadSource by rememberPreference(
            githubDownloadSourceKey, downloadSources.first().id
        )
        var themeMode by remember {
            mutableStateOf(bootPrefs?.get(DesktopPrefs.themeMode) ?: DesktopPrefs.THEME_SYSTEM)
        }
        val lyricEnabled by DesktopLyricController.enabled.collectAsState()
        val lyricLocked by DesktopLyricController.locked.collectAsState()
        // MV 播放窗口（2.1.0）：DesktopUiState.mvRequest 非空即弹窗。
        val mvRequest by com.jussicodes.music.desktop.ui.DesktopUiState.mvRequest.collectAsState()
        // 一起听面板（2.1.0）：对话框开关。
        val ltOpen by com.jussicodes.music.desktop.ui.DesktopUiState.listenTogetherOpen.collectAsState()
        // 字号/双行自 2.0.3 起由 AWT 歌词窗直接读 controller 状态。
        // 歌词窗位置/宽度状态自 2.0.3 起由纯 AWT 窗（DesktopLyricAwtWindow）
        // 直接读 DesktopLyricController 的 StateFlow，主窗不再收集。
        val appIcon = remember { createAppIcon() }
        val iconPainter = remember(appIcon) { androidx.compose.ui.graphics.painter.BitmapPainter(appIcon) }

        LaunchedEffect(uiScale) {
            liveUiScale = uiScale
            ScaledDialogDensity.scale = uiScale
        }
        // 主题模式/关窗行为跟随设置变更
        LaunchedEffect(Unit) {
            context.dataStore.data.collect { prefs ->
                themeMode = prefs[DesktopPrefs.themeMode] ?: DesktopPrefs.THEME_SYSTEM
                closeToTray = prefs[DesktopPrefs.closeToTray] ?: false
            }
        }
        LaunchedEffect(Unit) {
            PlayerController.init(context)
            PlayerController.controller?.let { player ->
                // 2.0.5（A6）：队列恢复包 runCatching——song_list.json 万一
                // 损坏（旧版非原子写），异常不再断掉后续初始化链（歌词/
                // 会话/更新检查都在这之后），队列读端自身也已防腐。
                runCatching { player.init(context) }
                    .onFailure { println("[init] queue restore failed: $it") }
                playerState = player.state(context)
                // 一起听（2.1.0）：接上与安卓同一套会话状态机（安卓由
                // PlaybackService attach，桌面此前从未 attach、无入口）。
                runCatching {
                    com.jussicodes.music.playback.ListenTogetherSession
                        .attach(player, context)
                }.onFailure { println("[init] listen-together attach failed: $it") }
            }
            DesktopLyricController.playerProvider = { playerState }
            LikeController.ensureLoaded(context)
            SessionState.refresh(context)
            val ignoredVersion = context.dataStore.data.first()[ignoredUpdateVersionKey].orEmpty()
            val updateInfo = AppUpdateManager.checkUpdate().getOrNull()
            if (updateInfo != null && updateInfo.versionName != ignoredVersion) {
                pendingUpdateInfo = updateInfo
            }
            MsgSessionCache.ensureLoaded(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        }
        // 单实例前置（2.0.5，A2）：第二实例发来 SHOW → 显示并拉回前台
        LaunchedEffect(Unit) {
            SingleInstance.onShowRequest = {
                javax.swing.SwingUtilities.invokeLater {
                    appVisible = true
                    MainWindowHolder.window?.let { w ->
                        w.isVisible = true
                        if (w is Frame && w.extendedState == Frame.ICONIFIED) {
                            w.extendedState = Frame.NORMAL
                        }
                        w.toFront()
                    }
                }
            }
        }
        // 全局快捷键 handler（2.0.5，C3）：playerState 就绪后接上
        LaunchedEffect(playerState) {
            val ps = playerState ?: return@LaunchedEffect
            DesktopHotkeys.handler = { action ->
                val p = ps.player
                when (action) {
                    DesktopHotkeys.Action.PLAY_PAUSE ->
                        if (ps.isPlaying) p.pause() else p.play()
                    DesktopHotkeys.Action.NEXT -> p.seekToNext()
                    DesktopHotkeys.Action.PREV -> p.seekToPrevious()
                    DesktopHotkeys.Action.VOLUME_UP ->
                        p.volume = (p.volume + 0.05f).coerceAtMost(1f)
                    DesktopHotkeys.Action.VOLUME_DOWN ->
                        p.volume = (p.volume - 0.05f).coerceAtLeast(0f)
                    DesktopHotkeys.Action.TOGGLE_LYRIC ->
                        DesktopLyricController.setEnabled(
                            !DesktopLyricController.enabled.value
                        )
                    // F6：STOP 只由全局媒体键分发（组合键无此键），穷尽分支
                    // 与媒体键语义一致（暂停 + MEDIA_KEY 注记）。
                    DesktopHotkeys.Action.STOP -> {
                        com.jussicodes.music.playback.PauseDiagnostics.note(
                            com.jussicodes.music.playback.PauseDiagnostics.MEDIA_KEY
                        )
                        p.pause()
                    }
                }
            }
            // F6 全局媒体键 handler：与 AWT 媒体键段同语义（STOP=暂停、
            // PLAY_PAUSE 暂停时注记 MEDIA_KEY），与 Ctrl+Alt 组合键
            // handler 分离，避免组合键空格暂停被误记为媒体键。
            DesktopHotkeys.mediaHandler = { action ->
                val p = ps.player
                when (action) {
                    DesktopHotkeys.Action.NEXT -> p.seekToNext()
                    DesktopHotkeys.Action.PREV -> p.seekToPrevious()
                    DesktopHotkeys.Action.STOP -> {
                        com.jussicodes.music.playback.PauseDiagnostics.note(
                            com.jussicodes.music.playback.PauseDiagnostics.MEDIA_KEY
                        )
                        p.pause()
                    }
                    DesktopHotkeys.Action.PLAY_PAUSE ->
                        if (ps.isPlaying) {
                            com.jussicodes.music.playback.PauseDiagnostics.note(
                                com.jussicodes.music.playback.PauseDiagnostics.MEDIA_KEY
                            )
                            p.pause()
                        } else p.play()
                    else -> DesktopHotkeys.handler(action)
                }
            }
        }
        val miniMode by DesktopUiState.miniMode.collectAsState()
        // 窗口几何落盘（2.0.5，A4：加节流。此前 snapshotFlow 每帧都
        // dataStore.edit 全量写盘，拖动/缩放时一秒几十次写，肉眼可感卡顿；
        // 现停稳 450ms 才写一次，恢复语义不变。）
        LaunchedEffect(windowState) {
            snapshotFlow {
                Triple(windowState.size, windowState.position, windowState.placement)
            }.debounce(450).collect { (size, position, placement) ->
                context.dataStore.edit { prefs ->
                    prefs[DesktopPrefs.windowWidth] = size.width.value
                    prefs[DesktopPrefs.windowHeight] = size.height.value
                    if (position is WindowPosition.Absolute) {
                        prefs[DesktopPrefs.windowX] = position.x.value.toInt()
                        prefs[DesktopPrefs.windowY] = position.y.value.toInt()
                    }
                    prefs[DesktopPrefs.windowMaximized] = placement == WindowPlacement.Maximized
                }
            }
        }

        val isDark = when (themeMode) {
            DesktopPrefs.THEME_DARK -> true
            DesktopPrefs.THEME_LIGHT -> false
            else -> androidx.compose.foundation.isSystemInDarkTheme()
        }
        val baseDensity = LocalDensity.current
        val scaledDensity = remember(baseDensity, liveUiScale) {
            val scale = liveUiScale.coerceIn(0.7f, 1.1f)
            Density(
                density = baseDensity.density * scale,
                fontScale = baseDensity.fontScale * scale
            )
        }

        fun requestCloseMain() {
            if (closeToTray) appVisible = false else exitApplication()
        }

        // ---------------- 托盘 ----------------
        Tray(
            icon = iconPainter,
            tooltip = "企鹅音乐",
            onAction = { appVisible = true },
            menu = {
                Item(if (appVisible) "隐藏主窗口" else "打开企鹅音乐") {
                    appVisible = !appVisible
                }
                Separator()
                Item(if (playerState?.isPlaying == true) "暂停" else "播放") {
                    val p = playerState ?: return@Item
                    if (p.isPlaying) p.player.pause() else p.player.play()
                }
                Item("上一首") { playerState?.player?.seekToPrevious() }
                Item("下一首") { playerState?.player?.seekToNext() }
                Separator()
                CheckboxItem(
                    "显示桌面歌词",
                    checked = lyricEnabled,
                    onCheckedChange = { DesktopLyricController.setEnabled(it) }
                )
                CheckboxItem(
                    "锁定桌面歌词",
                    checked = lyricLocked,
                    onCheckedChange = { DesktopLyricController.setLocked(it) }
                )
                Separator()
                Item("退出企鹅音乐") { exitApplication() }
            }
        )

        // ---------------- 主窗口 ----------------
        Window(
            onCloseRequest = { requestCloseMain() },
            visible = appVisible,
            title = "企鹅音乐",
            state = windowState,
            undecorated = true,
            icon = iconPainter,
        ) {
            DisposableEffect(window) {
                window.minimumSize = java.awt.Dimension(900, 600)
                MainWindowHolder.window = window
                onDispose {
                    if (MainWindowHolder.window === window) MainWindowHolder.window = null
                }
            }
            // 迷你模式（2.0.5，C9）：窗口缩成小窗并置顶，退出恢复原尺寸与
            // 最小尺寸。尺寸走 windowState（Compose 的单一事实源，直接改
            // AWT bounds 会被状态同步覆盖回去）。
            var savedWindowSize by remember { mutableStateOf<DpSize?>(null) }
            LaunchedEffect(miniMode) {
                if (miniMode) {
                    if (savedWindowSize == null) savedWindowSize = windowState.size
                    window.minimumSize = java.awt.Dimension(360, 88)
                    windowState.size = DpSize(400.dp, 96.dp)
                    window.isAlwaysOnTop = true
                } else {
                    window.isAlwaysOnTop = false
                    window.minimumSize = java.awt.Dimension(900, 600)
                    savedWindowSize?.let { windowState.size = it }
                    savedWindowSize = null
                }
            }
            // 媒体键 + 鼠标侧键（应用内全局 AWT 事件）
            DisposableEffect(window) {
                val listener = AWTEventListener { event ->
                    when (event) {
                        is KeyEvent -> {
                            if (event.id == KeyEvent.KEY_PRESSED
                                && !DesktopHotkeys.isMediaKeyActive(event.keyCode)
                            ) {
                                // F6 防双触发：全局媒体键已注册成功的键码归全局
                                // 路径，AWT 段跳过，避免同一次按键触发两次；全局
                                // 未注册（如被占用）时 AWT 仍兜底，保证应用内可用。
                                when (event.keyCode) {
                                    0xB0 -> playerState?.player?.seekToNext() // VK_MEDIA_NEXT_TRACK
                                    0xB1 -> playerState?.player?.seekToPrevious() // VK_MEDIA_PREV_TRACK
                                    0xB2 -> { // VK_MEDIA_STOP → 暂停
                                        // 2.1.3（A4-03）：媒体键暂停，声明原因落盘。
                                        com.jussicodes.music.playback.PauseDiagnostics.note(
                                            com.jussicodes.music.playback.PauseDiagnostics.MEDIA_KEY
                                        )
                                        playerState?.player?.pause()
                                    }
                                    0xB3 -> { // VK_MEDIA_PLAY_PAUSE
                                        val p = playerState
                                        if (p != null) {
                                            if (p.isPlaying) {
                                                com.jussicodes.music.playback.PauseDiagnostics.note(
                                                    com.jussicodes.music.playback.PauseDiagnostics.MEDIA_KEY
                                                )
                                                p.player.pause()
                                            } else p.player.play()
                                        }
                                    }
                                }
                            }
                        }
                        is MouseEvent -> {
                            if (event.id == MouseEvent.MOUSE_PRESSED) {
                                when (event.button) {
                                    4 -> DesktopUiState.backAction()
                                    5 -> DesktopUiState.forwardAction()
                                }
                            }
                        }
                    }
                }
                java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(
                    listener,
                    java.awt.AWTEvent.KEY_EVENT_MASK or java.awt.AWTEvent.MOUSE_EVENT_MASK
                )
                onDispose {
                    java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
                }
            }

            // 测试钩子（截图/自动点击），与 1.x 兼容并扩展 drag/right/wheel/keycode
            // 2.0.5（A10）：与 main() 的参数解析一样受 devHooks 门控。
            val clicksSpec = if (devHooks) {
                args.firstOrNull { it.startsWith("--clicks=") }?.removePrefix("--clicks=")
            } else null
            if (screenshotPath != null || screenshotScreenPath != null || autoExitMs != null) {
                LaunchedEffect(Unit) {
                    if (clicksSpec != null) {
                        delay(4_000)
                        val robot = java.awt.Robot()
                        val origin = window.locationOnScreen
                        println(
                            "[clicks] window loc=$origin size=${window.size} " +
                                "insets=${window.insets} rootPane=" +
                                (window as? javax.swing.JFrame)?.rootPane?.bounds
                        )
                        for (step in clicksSpec.split(";")) {
                            runCatching { runScriptStep(robot, origin, step) }
                                .onFailure { println("[clicks] step failed: $step -> $it") }
                        }
                    } else {
                        delay(autoExitMs ?: 9_000)
                    }
                    if (screenshotPath != null) {
                        runCatching {
                            val robot = java.awt.Robot()
                            val bounds = java.awt.Rectangle(window.locationOnScreen, window.size)
                            val image = robot.createScreenCapture(bounds)
                            ImageIO.write(image, "png", File(screenshotPath))
                            println("[screenshot] written to $screenshotPath")
                        }.onFailure { println("[screenshot] FAILED: $it") }
                    }
                    if (screenshotScreenPath != null) {
                        runCatching {
                            val robot = java.awt.Robot()
                            val screen = java.awt.Toolkit.getDefaultToolkit().screenSize
                            val image = robot.createScreenCapture(
                                java.awt.Rectangle(0, 0, screen.width, screen.height)
                            )
                            ImageIO.write(image, "png", File(screenshotScreenPath))
                            println("[screenshot-screen] written to $screenshotScreenPath")
                        }.onFailure { println("[screenshot-screen] FAILED: $it") }
                    }
                    exitApplication()
                }
            }

            // 2.0.3 测试钩子：--drag-trace 每 20ms 采样主窗屏幕坐标打到 stdout
            // （拖动轨迹单调性断言用）；--dump-state=<file> 每秒把播放状态追加
            // 落盘（mediaId/position/isPlaying/state/error），供种子矩阵断言
            // 「进度真推进、无静默失败」。
            if (devHooks && args.any { it == "--drag-trace" }) {
                LaunchedEffect(Unit) {
                    val t0 = System.currentTimeMillis()
                    while (true) {
                        // 窗口未 showing 时 locationOnScreen 抛 IllegalComponentState，
                        // 必须守卫（首版钩子在此把验收跑挂过：异常被 Compose 弹成模态框）
                        if (window.isShowing) {
                            val loc = runCatching { window.locationOnScreen }.getOrNull()
                            if (loc != null) {
                                println(
                                    "[dragtrace] t=${System.currentTimeMillis() - t0} " +
                                        "x=${loc.x} y=${loc.y}"
                                )
                            }
                        }
                        delay(20)
                    }
                }
            }
            val dumpStatePath = if (devHooks) {
                args.firstOrNull { it.startsWith("--dump-state=") }
                    ?.removePrefix("--dump-state=")
            } else null
            if (dumpStatePath != null) {
                LaunchedEffect(Unit) {
                    val file = File(dumpStatePath)
                    runCatching { file.parentFile?.mkdirs() }
                    while (true) {
                        val line = runCatching {
                            val p = playerState?.player
                            "t=${System.currentTimeMillis()} " +
                                "mediaId=${p?.currentMediaItem?.mediaId} " +
                                "title=${p?.mediaMetadata?.title} " +
                                "pos=${p?.currentPosition} dur=${p?.duration} " +
                                "playing=${p?.isPlaying} state=${p?.playbackState} " +
                                "error=${p?.playerError?.message?.take(80)}"
                        }.getOrElse { "t=${System.currentTimeMillis()} dump-error $it" }
                        runCatching { file.appendText(line + "\n") }
                        delay(1000)
                    }
                }
            }

            CompositionLocalProvider(
                LocalDensity provides scaledDensity,
                LocalContext provides context,
            ) {
                JetMeloTheme(
                    darkTheme = isDark,
                    artwork = playerState?.mediaMetadata?.artworkUri,
                    artworkForced = isDark
                ) {
                    CompositionLocalProvider(
                        LocalPlayerController provides PlayerController,
                        LocalPlayerState provides playerState,
                        LocalUiScaleController provides UiScaleController(
                            scale = liveUiScale,
                            setScale = {
                                val scale = it.coerceIn(0.7f, 1.1f)
                                liveUiScale = scale
                                uiScale = scale
                                ScaledDialogDensity.scale = scale
                            }
                        )
                    ) {
                        if (miniMode) {
                            MiniPlayerBar(playerState = playerState)
                        } else {
                            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                                PenguinTitleBar(
                                    onClose = { requestCloseMain() },
                                    onMinimize = {
                                        (window as? Frame)?.state = Frame.ICONIFIED
                                    },
                                    onToggleMaximize = {
                                        windowState.placement =
                                            if (windowState.placement == WindowPlacement.Maximized) {
                                                WindowPlacement.Floating
                                            } else WindowPlacement.Maximized
                                    }
                                )
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    MainScreen()
                                }
                            }
                        }
                        pendingUpdateInfo?.let { updateInfo ->
                            UpdateDialog(
                                updateInfo = updateInfo,
                                isDownloading = false,
                                downloadProgress = null,
                                progressText = null,
                                selectedSourceId = githubDownloadSource,
                                sourceStatuses = emptyList(),
                                isMeasuringSources = false,
                                onMeasureSources = {},
                                onSourceSelected = { githubDownloadSource = it },
                                onDismiss = { pendingUpdateInfo = null },
                                onIgnoreVersion = {
                                    pendingUpdateInfo = null
                                    appScope.launch {
                                        context.dataStore.edit { prefs ->
                                            prefs[ignoredUpdateVersionKey] = updateInfo.versionName
                                        }
                                    }
                                },
                                onDownload = {
                                    // 2.0.5（A7）：真下载自有源 zip 并打开所在
                                    // 文件夹（UpdateDownloadService 内部处理
                                    // 进度/成功/失败提示）。此前这里同时
                                    // start 下载又弹「去 GitHub」的矛盾 Toast，
                                    // 且下载本身是假的（指向 APK 发布页）。
                                    UpdateDownloadService.start(context, updateInfo, githubDownloadSource)
                                    pendingUpdateInfo = null
                                }
                            )
                        }
                    }
                }
            }
            // 一起听面板（2.1.0）：主窗口内对话框。
            if (ltOpen) {
                com.jussicodes.music.desktop.ui.ListenTogetherPanel(
                    onDismiss = {
                        com.jussicodes.music.desktop.ui.DesktopUiState.listenTogetherOpen.value = false
                    }
                )
            }
            // 无边框窗口边缘缩放手柄（右/下/左/上 + 右下角）
            if (windowState.placement == WindowPlacement.Floating && !miniMode) {
                WindowResizeHandles()
            }
        }

        // ---------------- MV 播放窗（2.1.0） ----------------
        // 结构（供 A3-02 协调者接线）：mvRequest 是 DesktopUiState.mvRequest
        // StateFlow 上面 :279 collectAsState 收集来的本地值；关闭走
        // DesktopUiState.closeMv() 置空（MvPlayerWindow 内部调用）。
        // A3-01（2.1.2）：按 mvId 加 key——窗已开时再开第二支 MV 时整窗
        // 内容（含 SwingPanel/VLC Session）按新 mvId 重建，不再只换标题
        // 不换片；mvId 不变的重组不触发重建。
        mvRequest?.let { req ->
            key(req.mvId) {
                com.jussicodes.music.desktop.ui.MvPlayerWindow(request = req)
            }
        }
        // 验收钩子：启动后自动打开 MV 窗 / 一起听面板（devHooks 门控）。
        LaunchedEffect(Unit) {
            if (bootOpenMvId != null || bootOpenLt) {
                kotlinx.coroutines.delay(5000)
                bootOpenMvId?.let {
                    com.jussicodes.music.desktop.ui.DesktopUiState.openMv(it, "MV")
                }
                if (bootOpenLt) {
                    com.jussicodes.music.desktop.ui.DesktopUiState.listenTogetherOpen.value = true
                }
            }
        }

        // ---------------- 桌面歌词窗 ----------------
        // 2.0.3：歌词窗改纯 AWT JWindow 实现（DesktopLyricAwtWindow），不再走
        // Compose Window——Compose 透明窗在用户真机上连续两版出问题（不出窗/
        // 锁定失效）。开关状态仍由 DesktopLyricController.enabled 驱动，这里
        // 只把状态桥给 AWT 窗单例（setVisible 内部保证：无队列也出占位窗、
        // 每次调用都有日志与可见反馈）。
        LaunchedEffect(lyricEnabled) {
            DesktopLyricAwtWindow.setVisible(lyricEnabled)
        }
    }
}



/**
 * 迷你模式小窗播放条（2.0.5，C9）：封面/歌名/歌手 + 上一首/播放暂停/
 * 下一首 + 退出迷你，底部一条细进度。整条可拖动（屏幕绝对坐标算法同
 * 标题栏 2.0.3 修复）；窗口尺寸/置顶由壳层 LaunchedEffect(miniMode) 管。
 */
@Composable
private fun MiniPlayerBar(playerState: PlayerState?) {
    val player = playerState?.player
    val queueSongs by com.jussicodes.music.extensions.cacheSongs.collectAsState()
    val currentSong = queueSongs?.getOrNull(playerState?.mediaItemIndex ?: -1)
    val title = currentSong?.name
        ?: playerState?.mediaMetadata?.title?.toString().orEmpty()
    val artist = currentSong?.ar?.joinToString(" / ") { it.name }
        ?: playerState?.mediaMetadata?.artist?.toString().orEmpty()
    val artwork = currentSong?.al?.picUrl?.takeIf { it.isNotBlank() }
        ?: playerState?.mediaMetadata?.artworkUri?.toString()
    val isPlaying = playerState?.isPlaying == true
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    LaunchedEffect(playerState) {
        val p = player ?: return@LaunchedEffect
        while (true) {
            position = p.currentPosition.coerceAtLeast(0)
            duration = p.duration.coerceAtLeast(0)
            delay(400)
        }
    }
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    var startWin: java.awt.Point? = null
                    var startPtr: java.awt.Point? = null
                    detectDragGestures(
                        onDragStart = {
                            startWin = MainWindowHolder.window?.location
                            startPtr = java.awt.MouseInfo.getPointerInfo()?.location
                        },
                        onDragEnd = { startWin = null; startPtr = null },
                        onDragCancel = { startWin = null; startPtr = null }
                    ) { change, _ ->
                        change.consume()
                        val w = MainWindowHolder.window
                        val sw = startWin
                        val sp = startPtr
                        val now = java.awt.MouseInfo.getPointerInfo()?.location
                        if (w != null && sw != null && sp != null && now != null) {
                            w.setLocation(sw.x + (now.x - sp.x), sw.y + (now.y - sp.y))
                        }
                    }
                }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtworkImage(url = artwork, modifier = Modifier.size(52.dp), corner = 7.dp, thumbSize = 120)
            Spacer(Modifier.width(10.dp))
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(
                    title.ifBlank { "企鹅音乐" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    artist.ifBlank { "选择一首歌开始播放" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { player?.seekToPrevious() }, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    "上一首", Modifier.size(20.dp)
                )
            }
            Box(
                Modifier.size(38.dp)
                    .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)
                    .clickable(enabled = player != null) {
                        if (isPlaying) player?.pause() else player?.play()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause
                    else Icons.Filled.PlayArrow,
                    if (isPlaying) "暂停" else "播放",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = { player?.seekToNext() }, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.SkipNext,
                    "下一首", Modifier.size(20.dp)
                )
            }
            IconButton(
                onClick = { DesktopUiState.miniMode.value = false },
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    Icons.Filled.FullscreenExit,
                    "退出迷你模式", Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // 底部细进度
        val fraction = if (duration > 0) {
            (position.toFloat() / duration).coerceIn(0f, 1f)
        } else 0f
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
        ) {
            Box(
                Modifier.fillMaxWidth(fraction).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

/** 程序内生成图标（深底 + 白音符圆点），避免依赖外部资源。 */
private fun createAppIcon(): ImageBitmap {
    val size = 128
    val image = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.setRenderingHint(
        java.awt.RenderingHints.KEY_ANTIALIASING,
        java.awt.RenderingHints.VALUE_ANTIALIAS_ON
    )
    g.color = java.awt.Color(0x1F, 0x8A, 0x70)
    g.fillRoundRect(0, 0, size, size, 28, 28)
    g.color = java.awt.Color.WHITE
    // 简易音符：圆头 + 竖杆 + 横梁
    g.fillOval(34, 82, 26, 20)
    g.fillOval(84, 74, 26, 20)
    g.fillRect(56, 34, 6, 52)
    g.fillRect(106, 26, 6, 52)
    g.fillRect(56, 26, 56, 12)
    g.dispose()
    val baos = ByteArrayOutputStream()
    ImageIO.write(image, "png", baos)
    return org.jetbrains.skia.Image.makeFromEncoded(baos.toByteArray()).toComposeImageBitmap()
}

/** 自定义标题栏（对标网易云 Windows 版的自绘标题栏）。
 * 2.0 时拖动区内层 Row 同时挂 weight(1f)+fillMaxWidth()，把三个窗口按钮
 * 挤出右界；2.0.1 改为 weight 拖动区 + 兄弟按钮（Xvfb 下命中正常）；
 * 2.0.2 改为分层结构（底层整幅拖动层 + 上层内容行），修 Windows 真机
 * 「拖不动 + 按钮按不动」，见函数内注释。 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun androidx.compose.ui.window.WindowScope.PenguinTitleBar(
    onClose: () -> Unit,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit
) {
    var closeHovered by remember { mutableStateOf(false) }
    // 2.0.2 分层结构（修 Windows 真机「拖不动 + 按钮按不动」）：
    // 旧结构把带 detectTapGestures 的手势 Row 包在 WindowDraggableArea 里、
    // 按钮做 Row 的兄弟项，Windows 上整条标题栏命中不稳（手势子级吞掉了
    // 拖动区的命中判定，按钮也间歇失灵）。改为分层结构：底层整幅拖动层
    // （手动 detectDragGestures 拖 AWT 窗口 + 双击最大化，见下方注释）；
    // 上层 Row 只放图标/标题/按钮，按钮不被任何手势父级包裹，空白处的
    // 按下自然穿透到下面的拖动层（无 pointerInput 的布局节点不消费事件）。
    Box(
        Modifier.fillMaxWidth().height(40.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        // 底层拖动层：手动拖 AWT 窗口。不用框架的 WindowDraggableArea——
        // 其实测在 Xvfb 下 awaitFirstDown 被上层手势竞争吞掉、拖动完全
        // 不触发（run4/04-drag 截图窗口纹丝不动）。
        // 2.0.3 拖动算法重写（修真机抖动）：2.0.2 用「窗口当前位置 + 组件
        // 相对增量 drag」逐步累加——drag 是窗口内坐标的增量，窗口每被移动
        // 一次坐标系就跟着变，形成反馈振荡（真机上窗口一直抖）。本版在
        // onDragStart 一次性捕获「窗口位置 + 屏幕绝对指针（MouseInfo）」，
        // 拖动中 setLocation = 起点窗口位置 + (当前屏幕指针 − 起点屏幕
        // 指针)，全程只用屏幕绝对坐标，不碰组件相对坐标。
        // 双击最大化挂同一层的另一个 pointerInput。
        val awtWindow = window
        Box(
            Modifier.fillMaxSize()
                .pointerInput(awtWindow) {
                    var dragStartWin: java.awt.Point? = null
                    var dragStartPtr: java.awt.Point? = null
                    detectDragGestures(
                        onDragStart = {
                            dragStartWin = awtWindow.location
                            dragStartPtr =
                                java.awt.MouseInfo.getPointerInfo()?.location
                        },
                        onDragEnd = {
                            dragStartWin = null
                            dragStartPtr = null
                        },
                        onDragCancel = {
                            dragStartWin = null
                            dragStartPtr = null
                        }
                    ) { change, _ ->
                        change.consume()
                        val sw = dragStartWin
                        val sp = dragStartPtr
                        val now = java.awt.MouseInfo.getPointerInfo()?.location
                        if (sw != null && sp != null && now != null) {
                            awtWindow.setLocation(
                                sw.x + (now.x - sp.x), sw.y + (now.y - sp.y)
                            )
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { onToggleMaximize() })
                }
        )
        Row(
            Modifier.fillMaxSize().padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(22.dp)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.MusicNote, null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "企鹅音乐",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onMinimize, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Minimize, "最小化", Modifier.size(17.dp))
            }
            IconButton(onClick = onToggleMaximize, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.CropSquare, "最大化/还原", Modifier.size(15.dp))
            }
            Box(
                Modifier.size(46.dp, 40.dp)
                    .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Enter) {
                        closeHovered = true
                    }
                    .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Exit) {
                        closeHovered = false
                    }
                    .background(
                        if (closeHovered) androidx.compose.ui.graphics.Color(0xFFE81123)
                        else androidx.compose.ui.graphics.Color.Transparent
                    )
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Close, "关闭", Modifier.size(17.dp),
                    tint = if (closeHovered) androidx.compose.ui.graphics.Color.White
                    else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/** 无边框窗口的边缘缩放手柄：拖动窗口四边调整大小（最小 900×600）。 */
@Composable
private fun androidx.compose.ui.window.WindowScope.WindowResizeHandles() {
    val awtWindow = window
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.align(Alignment.CenterEnd).width(6.dp)
                .then(Modifier.fillMaxHeight())
                .pointerInput(awtWindow) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val b = awtWindow.bounds
                        awtWindow.setBounds(b.x, b.y, (b.width + drag.x.toInt()).coerceAtLeast(900), b.height)
                    }
                }
        )
        Box(
            Modifier.align(Alignment.BottomCenter).height(6.dp).fillMaxWidth()
                .pointerInput(awtWindow) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val b = awtWindow.bounds
                        awtWindow.setBounds(b.x, b.y, b.width, (b.height + drag.y.toInt()).coerceAtLeast(600))
                    }
                }
        )
        Box(
            Modifier.align(Alignment.CenterStart).width(6.dp)
                .then(Modifier.fillMaxHeight())
                .pointerInput(awtWindow) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val b = awtWindow.bounds
                        val newWidth = (b.width - drag.x.toInt()).coerceAtLeast(900)
                        val dx = b.width - newWidth
                        awtWindow.setBounds(b.x + dx, b.y, newWidth, b.height)
                    }
                }
        )
        Box(
            Modifier.align(Alignment.TopCenter).height(6.dp).fillMaxWidth()
                .pointerInput(awtWindow) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val b = awtWindow.bounds
                        val newHeight = (b.height - drag.y.toInt()).coerceAtLeast(600)
                        val dy = b.height - newHeight
                        awtWindow.setBounds(b.x, b.y + dy, b.width, newHeight)
                    }
                }
        )
    }
}

/** 脚本化测试步进（扩展版）：click/paste/key/drag/right/wheel/keycode。 */
private suspend fun runScriptStep(
    robot: java.awt.Robot,
    origin: java.awt.Point,
    step: String
) {
    val trimmed = step.trim()
    if (trimmed.isEmpty()) return
    when {
        trimmed.startsWith("paste:") -> {
            val text = trimmed.removePrefix("paste:").substringBeforeLast(",")
            val waitMs = trimmed.substringAfterLast(",").trim().toLongOrNull() ?: 1000
            val cb = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            cb.setContents(java.awt.datatransfer.StringSelection(text), null)
            robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL)
            robot.keyPress(java.awt.event.KeyEvent.VK_V)
            robot.keyRelease(java.awt.event.KeyEvent.VK_V)
            robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL)
            println("[clicks] pasted '$text', waiting ${waitMs}ms")
            delay(waitMs)
        }
        trimmed.startsWith("keycode:") -> {
            val name = trimmed.removePrefix("keycode:").substringBeforeLast(",")
            val waitMs = trimmed.substringAfterLast(",").trim().toLongOrNull() ?: 1000
            val code = name.toIntOrNull() ?: runCatching {
                java.awt.event.KeyEvent::class.java.getField(name.uppercase()).getInt(null)
            }.getOrDefault(java.awt.event.KeyEvent.VK_ENTER)
            robot.keyPress(code)
            robot.keyRelease(code)
            println("[clicks] keycode $name($code), waiting ${waitMs}ms")
            delay(waitMs)
        }
        trimmed.startsWith("key:") -> {
            val name = trimmed.removePrefix("key:").substringBeforeLast(",")
            val waitMs = trimmed.substringAfterLast(",").trim().toLongOrNull() ?: 1000
            val code = when (name.uppercase()) {
                "ENTER" -> java.awt.event.KeyEvent.VK_ENTER
                "ESCAPE" -> java.awt.event.KeyEvent.VK_ESCAPE
                "TAB" -> java.awt.event.KeyEvent.VK_TAB
                "SPACE" -> java.awt.event.KeyEvent.VK_SPACE
                "LCTRL" -> java.awt.event.KeyEvent.VK_CONTROL
                else -> java.awt.event.KeyEvent.VK_ENTER
            }
            if (name.contains("+")) {
                // 组合键：如 ctrl+l / ctrl+left / alt+left
                val parts = name.split("+").map { it.trim().uppercase() }
                val codes = parts.map { part ->
                    when (part) {
                        "CTRL", "CONTROL" -> java.awt.event.KeyEvent.VK_CONTROL
                        "ALT" -> java.awt.event.KeyEvent.VK_ALT
                        "SHIFT" -> java.awt.event.KeyEvent.VK_SHIFT
                        "LEFT" -> java.awt.event.KeyEvent.VK_LEFT
                        "RIGHT" -> java.awt.event.KeyEvent.VK_RIGHT
                        "UP" -> java.awt.event.KeyEvent.VK_UP
                        "DOWN" -> java.awt.event.KeyEvent.VK_DOWN
                        else -> part.singleOrNull()?.let {
                            java.awt.event.KeyEvent.getExtendedKeyCodeForChar(it.code)
                        } ?: java.awt.event.KeyEvent.VK_UNDEFINED
                    }
                }
                codes.forEach { robot.keyPress(it) }
                codes.asReversed().forEach { robot.keyRelease(it) }
                println("[clicks] combo $name, waiting ${waitMs}ms")
            } else {
                robot.keyPress(code)
                robot.keyRelease(code)
                println("[clicks] key $name, waiting ${waitMs}ms")
            }
            delay(waitMs)
        }
        trimmed.startsWith("drag:") -> {
            val parts = trimmed.removePrefix("drag:").split(",").map { it.trim().toInt() }
            val (x1, y1, x2, y2) = listOf(parts[0], parts[1], parts[2], parts[3])
            val waitMs = parts.getOrElse(4) { 1200 }
            robot.mouseMove(origin.x + x1, origin.y + y1)
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            val steps = 12
            for (i in 1..steps) {
                robot.mouseMove(
                    origin.x + x1 + (x2 - x1) * i / steps,
                    origin.y + y1 + (y2 - y1) * i / steps
                )
                delay(30)
            }
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            println("[clicks] dragged $x1,$y1 -> $x2,$y2, waiting ${waitMs}ms")
            delay(waitMs.toLong())
        }
        trimmed.startsWith("right:") -> {
            val parts = trimmed.removePrefix("right:").split(",").map { it.trim().toInt() }
            val (x, y) = parts[0] to parts[1]
            val waitMs = parts.getOrElse(2) { 1200 }
            robot.mouseMove(origin.x + x, origin.y + y)
            robot.mousePress(java.awt.event.InputEvent.BUTTON3_DOWN_MASK)
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON3_DOWN_MASK)
            println("[clicks] right-clicked at $x,$y, waiting ${waitMs}ms")
            delay(waitMs.toLong())
        }
        trimmed.startsWith("wheel:") -> {
            val parts = trimmed.removePrefix("wheel:").split(",").map { it.trim().toInt() }
            val (x, y, amount) = listOf(parts[0], parts[1], parts[2])
            val waitMs = parts.getOrElse(3) { 800 }
            robot.mouseMove(origin.x + x, origin.y + y)
            robot.mouseWheel(amount)
            println("[clicks] wheel $amount at $x,$y, waiting ${waitMs}ms")
            delay(waitMs.toLong())
        }
        else -> {
            val parts = trimmed.split(",").map { it.trim().toInt() }
            val (x, y) = parts[0] to parts[1]
            val waitMs = parts.getOrElse(2) { 1500 }
            robot.mouseMove(origin.x + x, origin.y + y)
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            println("[clicks] clicked at $x,$y, waiting ${waitMs}ms")
            delay(waitMs.toLong())
        }
    }
}

/** Everything JetMeloApp.onCreate did, minus Android-only registrations. */
private fun initProcess(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 2.2.0（L1）：网络代理模式在任何网络动作之前同步应用——读落盘
    // 键后 NetProxy.apply（直连默认；跟随系统则解析 WinINet/env 并
    // 同步 JVM 代理系统属性给 OkHttp/Ktor 数据面）。
    runBlocking {
        runCatching {
            val mode = context.dataStore.data.first()[DesktopPrefs.proxyMode]
            com.jussicodes.music.utils.NetProxy.apply(mode)
            println("[NetProxy] mode=${com.jussicodes.music.utils.NetProxy.mode}")
        }
    }

    // 2.0.2：EQ 设置回读（开关/预设/增益落盘在 eqEnabled/eqPreset/eqGains）
    scope.launch { com.jussicodes.music.playback.audio.EqSettings.load(context) }

    // Coil singleton image loader (mirrors JetMeloApp.newImageLoader).
    SingletonImageLoader.setSafe { platformContext ->
        ImageLoader.Builder(platformContext)
            .crossfade(true)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(64 * 1024 * 1024L)
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCache {
                DiskCache.Builder()
                    .maxSizeBytes(512 * 1024 * 1024L)
                    .directory(
                        File(context.cacheDir, "coil").absolutePath.toPath()
                    )
                    .build()
            }
            .build()
    }

    FileProvider.init(context.cacheDir.resolve("ncm"))
    SongListUtil.init(context.filesDir.resolve("playlist"))
    // 本地音乐曲库（2.1.0）：恢复已持久化的文件夹与曲库缓存。
    com.jussicodes.music.desktop.local.LocalLibrary.init(context)

    // 2.0.1：登录指纹——持久设备身份落盘到应用数据目录（跨重启稳定），
    // 启动即预热种 Cookie（IO 协程，失败静默，登录请求里还会再兜底触发）。
    com.rcmiku.ncmapi.api.DeviceIdentity.init(
        File(context.filesDir, "device-identity.properties")
    )
    // #16（2.1.2）：deviceId 是登录指纹敏感标识，启动日志只留前后各
    // 4 位 + 长度，够对账「身份是否稳定」又不整串落盘/进 stdout。
    val deviceIdForLog = com.rcmiku.ncmapi.api.DeviceIdentity.deviceId
    println(
        "[identity] deviceId=${deviceIdForLog.take(4)}…${deviceIdForLog.takeLast(4)}" +
            " (len=${deviceIdForLog.length})"
    )
    scope.launch { runCatching { com.rcmiku.ncmapi.api.DeviceIdentity.warmUpBlocking() } }
    // 2.0.3：登录诊断日志接线（风控响应脱敏落盘 logs/login.log）。
    com.rcmiku.ncmapi.api.AuthDiagnostics.sink = { line -> DesktopLog.login(line) }
    // 2.0.3：匿名注册预热——尽早换 MUSIC_A 游客令牌（登录请求里还会兜底，
    // 带 10 分钟节流；失败静默，登录主链不受影响）。
    scope.launch {
        runCatching {
            com.rcmiku.ncmapi.api.DeviceIdentity.warmUpBlocking()
            com.rcmiku.ncmapi.api.account.AuthApi.ensureAnonymous()
        }
    }

    ExplorePreloader.init(context)
    PinnedAlbumStore.init(context)
    PinnedAlbumStore.watchHidden(context)
    PinnedAlbumStore.watchColumns(context)
    HomeTransparentCardStore.watch(context)
    HomeBgTextStore.watch(context)
    NcmCookieStore.watch(context)
    HomeUserInfoStore.init(context)
    MsgSessionCache.init(context)
    UserAgentProvider.init(UserAgentUtil.DEFAULT_USER_AGENT)

    // LX plugin store/manager + 2.1.0 真引擎（GraalJS 沙盒，
    // desktop 版 LxPluginEngine）。解析未命中时若引擎刚记下脚本错误，
    // 抛给 PlayerApi 写进 ResolveTrace——不假成功、不静默。
    com.jussicodes.music.lx.LxPluginStore.attach(context)
    com.jussicodes.music.lx.LxPluginManager.init(context)
    LxSourceBridge.resolver = { songId, songLevel ->
        val url = com.jussicodes.music.lx.LxPluginManager.resolveUrl(songId, songLevel)
        if (url == null) {
            com.jussicodes.music.lx.LxEngineStatus.freshError()?.let {
                throw IllegalStateException(it)
            }
        }
        url
    }

    scope.launch { ExplorePreloader.loadCache() }
    scope.launch { PinnedAlbumStore.loadCache() }

    // One-time migrations, identical to the Android build.
    scope.launch {
        if (context.dataStore.data.first()[audioSourceKey] == null) {
            context.dataStore.edit { prefs ->
                prefs.remove(localSourceKey)
                prefs[audioSourceKey] = AudioSources.LX
            }
        }
    }
    scope.launch {
        // 2.0.1：已下线服务器（主会话 2026-10-23 实测回 530）追加清洗——
        // 老用户已跑过一次性清洗（cleaned=true）也要把存量列表里的死地址摘掉，
        // 但该路径只删死地址、不动用户自定义服务器。
        val deadServers = setOf("https://api.jussichords.kdns.fr")
        val firstPrefs = context.dataStore.data.first()
        val alreadyCleaned = firstPrefs[apiServerListCleanedKey] ?: false
        val storedList = firstPrefs[apiServerListKey]
            ?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }
        val hasDead = storedList?.any { it in deadServers } == true
        if (!alreadyCleaned || hasDead) {
            val builtin = apiServers.toSet()
            context.dataStore.edit { prefs ->
                val currentList = prefs[apiServerListKey]
                    ?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }
                    ?: apiServers.toList()
                val cleaned = if (alreadyCleaned) {
                    currentList.filter { it !in deadServers }
                } else {
                    currentList.filter { it in builtin && it !in deadServers }
                }.ifEmpty { apiServers.toList() }
                prefs[apiServerListKey] = json.encodeToString(cleaned)
                val current = prefs[apiBaseUrlKey]
                if (!current.isNullOrEmpty() && (current in deadServers || (!alreadyCleaned && current !in builtin))) {
                    prefs[apiBaseUrlKey] = ""
                }
                prefs[apiServerListCleanedKey] = true
            }
        }
    }
    scope.launch {
        val prefs = context.dataStore.data.first()
        val autoClean = prefs[cacheAutoCleanEnabledKey] ?: false
        if (autoClean) {
            val threshold = prefs[cacheAutoCleanThresholdKey] ?: (1024L * 1024 * 1024)
            val snapshot = CacheManager.measure(context)
            if (snapshot.totalBytes > threshold) {
                CacheManager.clear(context)
            }
        }
    }
    scope.launch {
        context.dataStore.data
            .map { it[ncmCookieKey] }
            .distinctUntilChanged()
            .collect { ncmCookie ->
                val cookieMap = ncmCookie
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
                if (cookieMap?.containsKey("MUSIC_U") == true) {
                    CookieProvider.init(cookieMap)
                } else {
                    CookieProvider.clear()
                }
                ExplorePreloader.warmUp(context)
            }
    }
    scope.launch {
        context.dataStore.data
            .map { prefs ->
                AudioSync(
                    prefs[apiBaseUrlKey],
                    prefs[unblockSourceKey],
                    prefs[audioSourceKey],
                    prefs[directSourceKey]
                )
            }
            .distinctUntilChanged()
            .collect { (apiUrl, unblockSource, audioSource, directSource) ->
                API_BASE_URL = apiUrl.orEmpty()
                UNBLOCK_SOURCE = unblockSource ?: "AUTO"
                val selected =
                    AudioSources.fromKey(audioSource.orEmpty())?.value ?: AudioSources.DEFAULT
                AudioSourceSettings.SELECTED = selected
                val direct = LocalSources.fromKey(directSource.orEmpty())?.value
                    ?: LocalSources.AUTO
                PlayerApi.DirectSourceSettings.SOURCE = direct
                PlaybackReloadBus.sourceTag = "$selected/$direct"
            }
    }
    // 2.0：首启把强调色来源切到「封面取色」（用户已有的选择不覆盖）
    scope.launch {
        if (context.dataStore.data.first()[themeColorSourceKey] == null) {
            context.dataStore.edit { prefs ->
                prefs[themeColorSourceKey] = ThemeColorSource.ARTWORK.name
            }
        }
    }
    // 2.0.3 启动配置消毒：PROXY 音源 + 存量坏服务器（已下线死台 / 空或损坏 /
    // 真实取链路径探不通）会把用户卡死在「点播放没反应」。启动时短超时探活
    // /song/url/match：当前服务器不通则依次探用户服务器列表，第一个活的顶上；
    // 全灭则清地址并切回默认音源。任何实际动作都 Toast 告知一次。
    scope.launch {
        runCatching { sanitizeProxyConfig(context) }
            .onFailure { println("[sanitize] FAILED: ${it.javaClass.simpleName}: ${it.message}") }
    }
}

/** PROXY 配置探活：收到任何 HTTP 响应即算活（业务码不论），异常/超时算死。 */
private fun probeApiServer(base: String): Boolean {
    return try {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(3500, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(3500, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        val request = okhttp3.Request.Builder()
            .url("$base/song/url/match?id=347230")
            .header("User-Agent", "PenguinMusic/2.0.3")
            .build()
        client.newCall(request).execute().use { it.code in 100..599 }
    } catch (e: Exception) {
        false
    }
}

private suspend fun sanitizeProxyConfig(context: Context) {
    val deadServers = setOf("https://api.jussichords.kdns.fr")
    val prefs = context.dataStore.data.first()
    val selected = AudioSources.fromKey(prefs[audioSourceKey].orEmpty())?.value
        ?: AudioSources.DEFAULT
    println("[sanitize] check selected=$selected base=${prefs[apiBaseUrlKey]}")
    if (selected != AudioSources.PROXY) return
    val rawBase = prefs[apiBaseUrlKey]?.trim()?.trimEnd('/')
    val storedList = prefs[apiServerListKey]
        ?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }
        ?: apiServers.toList()

    suspend fun fallBack(reason: String) {
        // 先在用户列表里找活的顶上；全灭才清地址切默认音源。
        val alternates = (storedList + apiServers)
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() && it !in deadServers && it != rawBase }
            .distinct()
        println("[sanitize] fallback reason=$reason alternates=$alternates")
        val alive = alternates.firstOrNull {
            val ok = probeApiServer(it)
            println("[sanitize] probe $it -> $ok")
            ok
        }
        if (alive != null) {
            context.dataStore.edit { it[apiBaseUrlKey] = alive }
            ToastSink.handler("音源服务器不可用（$reason），已自动切换到 $alive")
            println("[sanitize] proxy base switched: $rawBase -> $alive ($reason)")
        } else {
            context.dataStore.edit {
                it[apiBaseUrlKey] = ""
                it[audioSourceKey] = AudioSources.DEFAULT
            }
            ToastSink.handler("音源服务器不可用（$reason），已切回默认音源")
            println("[sanitize] proxy base cleared, source reset to default ($reason)")
        }
    }

    when {
        rawBase.isNullOrBlank() -> fallBack("未配置服务器地址")
        rawBase in deadServers -> fallBack("服务器已下线")
        !(rawBase.startsWith("http://") || rawBase.startsWith("https://")) ->
            fallBack("服务器地址损坏")
        !probeApiServer(rawBase) -> fallBack("服务器连接超时")
        else -> println("[sanitize] proxy base alive: $rawBase")
    }
}
