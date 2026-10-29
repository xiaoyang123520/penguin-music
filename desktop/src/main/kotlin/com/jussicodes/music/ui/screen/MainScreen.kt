package com.jussicodes.music.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.jussicodes.music.LocalPlayerState
import com.jussicodes.music.constants.ncmCookieKey
import com.jussicodes.music.desktop.DesktopPrefs
import com.jussicodes.music.desktop.ui.ArtworkImage
import com.jussicodes.music.desktop.ui.DesktopRoutes
import com.jussicodes.music.desktop.ui.DesktopUiState
import com.jussicodes.music.desktop.ui.FullPlayer
import com.jussicodes.music.desktop.ui.PlayBar
import com.jussicodes.music.desktop.ui.QueuePanel
import com.jussicodes.music.desktop.ui.SessionState
import com.jussicodes.music.desktop.weblogin.WebLoginBrowser
import com.jussicodes.music.extensions.playMediaAt
import com.jussicodes.music.extensions.setPlaylist
import com.jussicodes.music.ui.navigation.NavGraph
import com.jussicodes.music.ui.navigation.PlaylistNav
import com.jussicodes.music.ui.navigation.RecordNav
import com.jussicodes.music.ui.navigation.Screen
import com.jussicodes.music.ui.navigation.UserNav
import com.jussicodes.music.utils.dataStore
import com.jussicodes.music.viewModel.LibraryScreenViewModel
import com.rcmiku.ncmapi.api.account.AccountApi
import com.rcmiku.ncmapi.utils.CookieProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面 2.0 主壳（替代安卓 MainScreen）：
 * 四区布局——左栏（可调宽）/ 顶栏（历史 ‹ › + 搜索 + 账号）/ 内容（NavGraph）/
 * 底部整宽播放栏；右侧队列抽屉与全屏播放页以覆盖层呈现。
 * 全局快捷键、导航历史栈、窗口状态持久化都在这一层。
 */
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()
    val homePagerState = rememberPagerState(initialPage = 0) { 2 }
    val playerState = LocalPlayerState.current
    val history = remember { NavHistory() }

    var homePage by remember { mutableStateOf(0) }
    var restored by remember { mutableStateOf(false) }

    // ---- 持久化状态恢复（侧栏宽度、上次页面）----
    var sidebarWidth by remember { mutableStateOf(208.dp) }
    LaunchedEffect(Unit) {
        val prefs = context.dataStore.data.first()
        prefs[DesktopPrefs.sidebarWidth]?.let { sidebarWidth = it.dp }
        DesktopUiState.queueOpen.value = prefs[DesktopPrefs.queuePanelOpen] ?: false
        // 上次停留页面（尽力恢复；拼不回就留在首页）
        val last = prefs[DesktopPrefs.lastRoute]
        if (!last.isNullOrBlank() && last != Screen.Library.route && last != Screen.Explore.route) {
            runCatching { navController.navigate(last) }
        }
        restored = true
    }
    // 队列开关落盘
    val queueOpen by DesktopUiState.queueOpen.collectAsState()
    LaunchedEffect(queueOpen) {
        if (restored) context.dataStore.edit { it[DesktopPrefs.queuePanelOpen] = queueOpen }
    }
    // 音量/播放模式持久化
    LaunchedEffect(playerState) {
        val player = playerState?.player ?: return@LaunchedEffect
        val prefs = context.dataStore.data.first()
        prefs[DesktopPrefs.volume]?.let { player.volume = it }
        prefs[DesktopPrefs.shuffle]?.let { player.shuffleModeEnabled = it }
        prefs[DesktopPrefs.repeatMode]?.let { player.repeatMode = it }
        // 2.2.0（L5）：倍速恢复（播放条/MV 改档时直接写此键）。
        prefs[DesktopPrefs.playbackSpeed]?.let { player.setPlaybackSpeed(it) }
    }
    // 2.3.0（A6-02）：倍速落盘键运行时同步——MV 窗口改档、设置页
    // 「恢复默认」删键等任何写键路径，都经这条 collect 即时应用到
    // 音乐引擎（与 MvPlayerWindow 的 mvSpeed effect 同口径：键缺失
    // 即 1f）。PlayBar 改档时自己已调引擎，本条重放同一值，幂等；
    // 上条一次性恢复与本条首帧值同源，不冲突。
    val playbackSpeedKey by context.dataStore.data
        .map { it[DesktopPrefs.playbackSpeed] }
        .collectAsState(initial = null)
    LaunchedEffect(playerState, playbackSpeedKey) {
        val player = playerState?.player ?: return@LaunchedEffect
        player.setPlaybackSpeed(playbackSpeedKey ?: 1f)
    }
    if (playerState != null) {
        LaunchedEffect(playerState.volume, playerState.repeatMode, playerState.shuffleModeEnabled) {
            if (!restored) return@LaunchedEffect
            context.dataStore.edit {
                it[DesktopPrefs.volume] = playerState.volume
                it[DesktopPrefs.repeatMode] = playerState.repeatMode
                it[DesktopPrefs.shuffle] = playerState.shuffleModeEnabled
            }
        }
    }

    // ---- 导航历史跟踪 ----
    val currentEntry by navController.currentBackStackEntryFlow.collectAsState(initial = null)
    LaunchedEffect(currentEntry) {
        val entry = currentEntry ?: return@LaunchedEffect
        history.onCurrentChanged(entry)
        if (restored) {
            entry.toConcreteRoute()?.let { route ->
                scope.launch { context.dataStore.edit { it[DesktopPrefs.lastRoute] = route } }
            }
        }
    }

    fun goHomePage(page: Int) {
        homePage = page
        scope.launch { runCatching { homePagerState.scrollToPage(page) } }
        val route = if (page == 0) Screen.Library.route else Screen.Explore.route
        navController.navigate(route) {
            popUpTo(Screen.Library.route) { inclusive = false }
            launchSingleTop = true
        }
    }

    fun doBack() {
        if (DesktopUiState.fullPlayerOpen.value) {
            DesktopUiState.fullPlayerOpen.value = false
            return
        }
        if (history.canBack) {
            history.expectBackward()
            navController.popBackStack()
        }
    }
    fun doForward() {
        val target = history.forwardTarget() ?: return
        history.expectForward()
        runCatching {
            when (target) {
                is String -> navController.navigate(target) { launchSingleTop = true }
                else -> navController.navigate(target) { launchSingleTop = true }
            }
        }
    }
    DesktopUiState.backAction = { doBack() }
    DesktopUiState.forwardAction = { doForward() }
    // A3-02：打开 MV 时暂停主播放（DesktopUiState.openMv 调用；
    // 关 MV 后不自动恢复）。与 backAction 同注册模式。
    DesktopUiState.pauseMainPlayerAction = {
        val state = playerState
        if (state?.isPlaying == true) state.player.pause()
    }

    val fullPlayerOpen by DesktopUiState.fullPlayerOpen.collectAsState()

    // Surface 统一提供 background + contentColor(onSurface)：没有它，深色主题下
    // 内容区 Text 的 LocalContentColor 仍是默认黑（自查发现的对比度事故，2.0 修）
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val player = playerState?.player
                when {
                    event.key == Key.Escape -> {
                        when {
                            fullPlayerOpen -> {
                                DesktopUiState.fullPlayerOpen.value = false
                                true
                            }
                            queueOpen -> {
                                DesktopUiState.queueOpen.value = false
                                true
                            }
                            else -> false
                        }
                    }
                    event.isAltPressed && event.key == Key.DirectionLeft -> { doBack(); true }
                    event.isAltPressed && event.key == Key.DirectionRight -> { doForward(); true }
                    event.isCtrlPressed && event.key == Key.L -> {
                        DesktopUiState.focusSearchSignal.value += 1
                        // 顶栏搜索不在搜索页时：直接跳搜索页并聚焦其大搜索框
                        if (currentEntry?.destination?.route != Screen.Search.route) {
                            navController.navigate(Screen.Search.route) { launchSingleTop = true }
                        }
                        true
                    }
                    event.isCtrlPressed && event.key == Key.DirectionLeft -> { player?.seekToPrevious(); true }
                    event.isCtrlPressed && event.key == Key.DirectionRight -> { player?.seekToNext(); true }
                    event.isCtrlPressed && event.key == Key.DirectionUp -> {
                        player?.let {
                            it.volume = (it.volume + 0.05f).coerceAtMost(1f)
                            Toast.makeText(context, "音量 ${(it.volume * 100).toInt()}%", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    event.isCtrlPressed && event.key == Key.DirectionDown -> {
                        player?.let {
                            it.volume = (it.volume - 0.05f).coerceAtLeast(0f)
                            Toast.makeText(context, "音量 ${(it.volume * 100).toInt()}%", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    else -> false
                }
            }
            // 空格播放/暂停（2.1.3，A4-10 改根逻辑）：不再走 onPreviewKeyEvent
            // 抢先拦截——预览阶段在焦点控件之前触发，旧实现只能靠各文本框
            // 手工登记 inputFocused 防误触，没登记的框（定时自定义、私聊
            // 消息、房号输入、重命名对话框等）打空格会被吞且误切播放。
            // 改走气泡阶段 onKeyEvent：焦点在文本框/按钮等可编辑/可激活
            // 控件时，空格先被控件自己消费（输入字符/触发点击），冒泡不到
            // 根部；没被消费才轮到这里切播放。inputFocused 登记保留作
            // 第二道防线（独立窗口里的输入框事件不经本根节点时兜底）。
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    event.key == Key.Spacebar &&
                    !DesktopUiState.inputFocused.value
                ) {
                    val player = playerState?.player
                    player?.let { if (playerState?.isPlaying == true) it.pause() else it.play() }
                    player != null
                } else {
                    false
                }
            }
    ) {
        Row(Modifier.fillMaxSize()) {
            // ---------------- 侧栏 ----------------
            Sidebar(
                width = sidebarWidth,
                currentRoute = currentEntry?.destination?.route,
                homePage = homePage,
                onHomePage = { goHomePage(it) },
                navController = navController,
                onWidthChange = { w ->
                    sidebarWidth = w
                    scope.launch { context.dataStore.edit { it[DesktopPrefs.sidebarWidth] = w.value } }
                }
            )
            // ---------------- 主列 ----------------
            Column(Modifier.weight(1f).fillMaxHeight()) {
                TopBar(
                    navController = navController,
                    canBack = history.canBack,
                    canForward = history.canForward,
                    onBack = { doBack() },
                    onForward = { doForward() }
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    NavGraph(
                        navController = navController,
                        bottomPadding = 0.dp,
                        homePagerState = homePagerState,
                        onHomePageChange = { homePage = it },
                        onHomePageScroll = {}
                    )
                    // 队列抽屉（右侧覆盖）
                    if (queueOpen) {
                        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight()) {
                            key("queue") { QueuePanel() }
                        }
                    }
                }
                PlayBar()
            }
        }
        // 全屏播放页覆盖层
        if (fullPlayerOpen) {
            Box(Modifier.fillMaxSize()) { FullPlayer() }
        }
    }
}

/**
 * 导航历史栈：跟踪 NavController 的条目，支持 ‹ › 与 Alt+←/→。
 * 导航 2.9 的 arguments 是 SavedState 而非 Bundle，因此条目统一转成
 * 类型安全路由对象（toRoute）或字符串路由保存；前进 = 重新 navigate
 * 到存下的目标；后退 = popBackStack。
 */
private class NavHistory {
    private val entries = mutableListOf<Any>()
    private val keys = mutableListOf<String>()
    private var index = -1
    private var pendingNav: String? = null // "back" / "forward"

    var canBack: Boolean = false
        private set
    var canForward: Boolean = false
        private set

    fun onCurrentChanged(entry: NavBackStackEntry) {
        val target = entry.toNavTarget()
        val key = entry.historyKey(target)
        when (pendingNav) {
            "back" -> index = (index - 1).coerceAtLeast(0)
            "forward" -> index = (index + 1).coerceAtMost(entries.lastIndex)
            else -> {
                if (keys.getOrNull(index) != key) {
                    while (entries.size > index + 1) {
                        entries.removeAt(entries.lastIndex)
                        keys.removeAt(keys.lastIndex)
                    }
                    if (keys.lastOrNull() != key) {
                        entries.add(target)
                        keys.add(key)
                    }
                    index = entries.lastIndex
                }
            }
        }
        pendingNav = null
        canBack = index > 0
        canForward = index < entries.lastIndex
    }

    fun expectBackward() { pendingNav = "back" }
    fun expectForward() { pendingNav = "forward" }

    fun forwardTarget(): Any? = entries.getOrNull(index + 1)
}

/** 条目 → 可保存的目标：类型安全路由对象，或字符串路由本身。 */
private fun NavBackStackEntry.toNavTarget(): Any {
    val pattern = destination.route ?: return "?"
    return when {
        pattern.contains("PlaylistNav") ->
            runCatching { toRoute<com.jussicodes.music.ui.navigation.PlaylistNav>() as Any }.getOrNull() ?: pattern
        pattern.contains("AlbumNav") ->
            runCatching { toRoute<com.jussicodes.music.ui.navigation.AlbumNav>() as Any }.getOrNull() ?: pattern
        pattern.contains("ArtistNav") ->
            runCatching { toRoute<com.jussicodes.music.ui.navigation.ArtistNav>() as Any }.getOrNull() ?: pattern
        pattern.contains("RadioNav") ->
            runCatching { toRoute<com.jussicodes.music.ui.navigation.RadioNav>() as Any }.getOrNull() ?: pattern
        pattern.contains("UserNav") ->
            runCatching { toRoute<com.jussicodes.music.ui.navigation.UserNav>() as Any }.getOrNull() ?: pattern
        pattern.contains("RecordNav") ->
            runCatching { toRoute<com.jussicodes.music.ui.navigation.RecordNav>() as Any }.getOrNull() ?: pattern
        pattern.contains("CloudSongNav") ->
            runCatching { toRoute<com.jussicodes.music.ui.navigation.CloudSongNav>() as Any }.getOrNull() ?: pattern
        else -> pattern
    }
}

/** 目标对象的字段表（数据类属性 → 值），供历史去重与路由重建。 */
private fun targetFields(target: Any): Map<String, String> {
    if (target is String) return emptyMap()
    val result = linkedMapOf<String, String>()
    var cls: Class<*>? = target.javaClass
    while (cls != null && cls != Any::class.java) {
        for (field in cls.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
            runCatching {
                field.isAccessible = true
                field.get(target)?.let { result[field.name] = it.toString() }
            }
        }
        cls = cls.superclass
    }
    return result
}

private fun NavBackStackEntry.historyKey(target: Any): String {
    val pattern = destination.route ?: return "?"
    val fields = targetFields(target)
    if (fields.isEmpty()) return pattern
    return pattern + "|" + fields.entries.joinToString("&") { "${it.key}=${it.value}" }
}

/** NavBackStackEntry → 可再次 navigate 的具体路由串（用于恢复上次页面）。 */
private fun NavBackStackEntry.toConcreteRoute(): String? {
    val pattern = destination.route ?: return null
    if (!pattern.contains("{")) return pattern
    val fields = targetFields(toNavTarget())
    var route = pattern
    for ((name, value) in fields) {
        route = route.replace("{$name}", value)
    }
    return if (route.contains("{")) null else route
}

@Composable
private fun Sidebar(
    width: androidx.compose.ui.unit.Dp,
    currentRoute: String?,
    homePage: Int,
    onHomePage: (Int) -> Unit,
    navController: NavController,
    onWidthChange: (androidx.compose.ui.unit.Dp) -> Unit
) {
    val context = LocalContext.current
    val playerState = LocalPlayerState.current
    val libraryVm = hiltViewModel<LibraryScreenViewModel>()
    val userPlaylists by libraryVm.userPlaylists.collectAsState()
    val profile by SessionState.profile.collectAsState()
    val density = LocalDensity.current
    var dragTotal by remember { mutableStateOf(0f) }

    Row(Modifier.fillMaxHeight()) {
        Column(
            Modifier.width(width).fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            // 品牌行已去掉：标题栏已有品牌，避免上下重复（自查项）
            Spacer(Modifier.height(6.dp))

            SidebarItem(
                icon = Icons.Filled.Home, label = "我的音乐",
                selected = (currentRoute == Screen.Library.route) && homePage == 0
            ) { onHomePage(0) }
            SidebarItem(
                icon = Icons.Filled.Explore, label = "发现音乐",
                selected = (currentRoute == Screen.Library.route || currentRoute == Screen.Explore.route) && homePage == 1
            ) { onHomePage(1) }
            SidebarItem(
                icon = Icons.Filled.TrendingUp, label = "排行榜",
                selected = currentRoute == DesktopRoutes.TOPLIST
            ) { navController.navigate(DesktopRoutes.TOPLIST) { launchSingleTop = true } }
            SidebarItem(
                icon = Icons.Filled.Apps, label = "歌单广场",
                selected = currentRoute == DesktopRoutes.SQUARE
            ) { navController.navigate(DesktopRoutes.SQUARE) { launchSingleTop = true } }
            SidebarItem(
                icon = Icons.Filled.Radio, label = "电台",
                selected = currentRoute == DesktopRoutes.RADIOS
            ) { navController.navigate(DesktopRoutes.RADIOS) { launchSingleTop = true } }
            SidebarItem(
                icon = Icons.Filled.Podcasts, label = "私人FM",
                selected = currentRoute == Screen.Roam.route
            ) { navController.navigate(Screen.Roam.route) { launchSingleTop = true } }

            Spacer(Modifier.height(14.dp))
            Text(
                "我的音乐",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
            Spacer(Modifier.height(4.dp))
            val likedPlaylist = userPlaylists.firstOrNull { it.specialType == 5 }
            SidebarItem(
                icon = Icons.Filled.Favorite, label = "我喜欢的音乐",
                selected = currentRoute?.startsWith("playlist") == true && likedPlaylist != null &&
                    currentEntryIsPlaylist(currentRoute)
            ) {
                if (likedPlaylist != null) {
                    navController.navigate(PlaylistNav(likedPlaylist.id))
                } else if (profile == null) {
                    Toast.makeText(context, "登录后可查看我喜欢的音乐", Toast.LENGTH_SHORT).show()
                    navController.navigate(Screen.Login.route)
                } else {
                    Toast.makeText(context, "暂无喜欢的音乐歌单", Toast.LENGTH_SHORT).show()
                }
            }
            SidebarItem(
                icon = Icons.Filled.History, label = "最近播放",
                selected = currentRoute == Screen.RecentPlay.route
            ) { navController.navigate(Screen.RecentPlay.route) { launchSingleTop = true } }
            SidebarItem(
                icon = Icons.Filled.Cloud, label = "音乐云盘",
                selected = currentRoute == DesktopRoutes.CLOUD
            ) { navController.navigate(DesktopRoutes.CLOUD) { launchSingleTop = true } }
            // 下载管理（2.0.5，C4）
            SidebarItem(
                icon = Icons.Filled.Download, label = "下载管理",
                selected = currentRoute == DesktopRoutes.DOWNLOADS
            ) { navController.navigate(DesktopRoutes.DOWNLOADS) { launchSingleTop = true } }
            // 本地音乐（2.1.0）
            SidebarItem(
                icon = Icons.Filled.FolderOpen, label = "本地音乐",
                selected = currentRoute == DesktopRoutes.LOCAL
            ) { navController.navigate(DesktopRoutes.LOCAL) { launchSingleTop = true } }
            SidebarItem(
                icon = Icons.Filled.LibraryMusic, label = "播放历史",
                selected = currentRoute?.contains("record") == true
            ) {
                val uid = profile?.userId ?: 0L
                if (uid > 0) navController.navigate(RecordNav(uid))
                else {
                    Toast.makeText(context, "登录后可查看播放记录", Toast.LENGTH_SHORT).show()
                    navController.navigate(Screen.Login.route)
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "我的歌单",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp).weight(1f)
                )
                // 导入歌单（2.0.5，C11 导入半）
                var importOpen by remember { mutableStateOf(false) }
                IconButton(onClick = { importOpen = true }, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Filled.Add, "导入歌单", Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (importOpen) {
                    ImportPlaylistDialog(
                        onDismiss = { importOpen = false },
                        onImported = { id ->
                            importOpen = false
                            navController.navigate(PlaylistNav(id))
                        }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(userPlaylists.filter { it.specialType != 5 }, key = { it.id }) { playlist ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable { navController.navigate(PlaylistNav(playlist.id)) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ArtworkImage(url = playlist.picUrl, modifier = Modifier.size(32.dp), corner = 5.dp, thumbSize = 80)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                playlist.name, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${playlist.trackCount} 首",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (userPlaylists.isEmpty()) {
                    item {
                        Text(
                            if (profile == null) "登录后显示你的歌单" else "暂无歌单",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
        // 拖拽调宽手柄（用 updatedState 读最新宽度，避免拖拽中途重启手势识别）
        val latestWidth by androidx.compose.runtime.rememberUpdatedState(width)
        Box(
            Modifier.width(5.dp).fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragTotal = 0f },
                        onDragEnd = { dragTotal = 0f },
                        onDragCancel = { dragTotal = 0f }
                    ) { change, drag ->
                        change.consume()
                        dragTotal += drag.x
                        val basePx = with(density) { latestWidth.toPx() }
                        val clamped = (basePx + dragTotal).coerceIn(
                            with(density) { 180.dp.toPx() },
                            with(density) { 320.dp.toPx() }
                        )
                        if (clamped != basePx) {
                            onWidthChange(with(density) { clamped.toDp() })
                            dragTotal -= (clamped - basePx)
                        }
                    }
                }
        )
    }
}

private fun currentEntryIsPlaylist(route: String?): Boolean = route?.contains("playlist") == true

/**
 * 导入歌单对话框（2.0.5，C11 导入半）：粘贴网易云歌单链接或纯 ID，
 * 提取 id → /playlist/detail 验存 → 直接打开该歌单页（公开歌单无需
 * 登录；私有/不存在时报错不跳转）。
 */
@Composable
private fun ImportPlaylistDialog(onDismiss: () -> Unit, onImported: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(20.dp).width(420.dp)) {
                Text("导入歌单", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "粘贴网易云音乐歌单链接（music.163.com/#/playlist?id=…）或歌单 ID",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text("歌单链接 / ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.OutlinedButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.Button(
                        enabled = text.isNotBlank() && !busy,
                        onClick = {
                            val id = Regex("""(?:[?&]id=|playlist[/:=]?)(\d{4,})""")
                                .find(text.trim())?.groupValues?.get(1)?.toLongOrNull()
                                ?: text.trim().toLongOrNull()
                            if (id == null) {
                                error = "没从输入里找到歌单 ID"
                                return@Button
                            }
                            busy = true
                            scope.launch {
                                val resp = com.rcmiku.ncmapi.api.playlist.PlaylistApi
                                    .playlistDetail(id, 1)
                                busy = false
                                resp.onSuccess { detail ->
                                    val name = detail.playlist.name
                                    if (name.isNotBlank()) {
                                        Toast.makeText(
                                            context, "已打开歌单：$name",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        onImported(id)
                                    } else {
                                        error = "歌单不存在或不可见（ID $id）"
                                    }
                                }.onFailure {
                                    error = "校验失败：${it.message?.take(60)}"
                                }
                            }
                        }
                    ) { Text(if (busy) "校验中…" else "导入") }
                }
            }
        }
    }
}

@Composable
private fun SidebarItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon, null, Modifier.size(19.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun TopBar(
    navController: NavController,
    canBack: Boolean,
    canForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile by SessionState.profile.collectAsState()
    val expired by SessionState.expired.collectAsState()
    var searchText by remember { mutableStateOf("") }
    var accountMenuOpen by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusSignal by DesktopUiState.focusSearchSignal.collectAsState()
    LaunchedEffect(focusSignal) {
        if (focusSignal > 0) runCatching { focusRequester.requestFocus() }
    }

    Row(
        Modifier.fillMaxWidth().height(54.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, enabled = canBack, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Filled.ChevronLeft, "后退", Modifier.size(22.dp))
        }
        IconButton(onClick = onForward, enabled = canForward, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Filled.ChevronRight, "前进", Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        // 自绘丸形搜索框：M3 OutlinedTextField 有最小高度，38dp 会把文字裁掉（自查项）
        var searchFocused by remember { mutableStateOf(false) }
        Row(
            Modifier.width(300.dp).height(38.dp).clip(RoundedCornerShape(19.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(
                    1.dp,
                    if (searchFocused) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(19.dp)
                )
                .padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Search, null, Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = searchText,
                onValueChange = { searchText = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    val q = searchText.trim()
                    if (q.isNotEmpty()) {
                        DesktopUiState.pendingSearch.value = q
                        navController.navigate(Screen.Search.route) { launchSingleTop = true }
                    }
                }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (searchText.isEmpty()) {
                            Text(
                                "搜索",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier.weight(1f)
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        searchFocused = it.isFocused
                        DesktopUiState.inputFocused.value = it.isFocused
                    }
            )
        }
        Spacer(Modifier.weight(1f))
        if (profile != null) {
            Box {
                Row(
                    Modifier.clip(RoundedCornerShape(18.dp))
                        .clickable { accountMenuOpen = true }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArtworkImage(url = profile!!.avatarUrl, modifier = Modifier.size(28.dp), corner = 14.dp, thumbSize = 80)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        if (expired) "登录已过期" else profile!!.nickname,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (expired) Color(0xFFE83E52) else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Icon(Icons.Filled.MoreVert, null, Modifier.size(16.dp))
                }
                DropdownMenu(expanded = accountMenuOpen, onDismissRequest = { accountMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("个人主页") },
                        onClick = {
                            accountMenuOpen = false
                            profile?.userId?.takeIf { it > 0 }?.let { navController.navigate(UserNav(it)) }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("设置") },
                        onClick = {
                            accountMenuOpen = false
                            navController.navigate(Screen.Settings.route) { launchSingleTop = true }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("退出登录") },
                        onClick = {
                            accountMenuOpen = false
                            scope.launch {
                                context.dataStore.edit { it.remove(ncmCookieKey) }
                                withContext(Dispatchers.IO) {
                                    CookieProvider.clear()
                                    // #3：一并清 JCEF 网页登录 Cookie；
                                    // 失败只记日志，不阻断退出流程。
                                    runCatching { WebLoginBrowser.clearCookies() }
                                        .onFailure {
                                            System.err.println("[Logout] clear JCEF cookies failed: $it")
                                        }
                                }
                                AccountApi.invalidateUserPlaylistCache()
                                SessionState.onLoggedOut()
                                Toast.makeText(context, "已退出登录", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        } else {
            Box(
                Modifier.clip(RoundedCornerShape(16.dp))
                    .background(if (expired) Color(0xFFE83E52) else MaterialTheme.colorScheme.primary)
                    .clickable { navController.navigate(Screen.Login.route) }
                    .padding(horizontal = 16.dp, vertical = 7.dp)
            ) {
                Text(
                    if (expired) "登录已过期" else "登录",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        IconButton(
            onClick = { navController.navigate(Screen.Settings.route) { launchSingleTop = true } },
            modifier = Modifier.size(34.dp)
        ) {
            Icon(Icons.Filled.Settings, "设置", Modifier.size(20.dp))
        }
    }
}
