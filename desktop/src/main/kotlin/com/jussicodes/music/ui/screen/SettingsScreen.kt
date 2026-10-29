package com.jussicodes.music.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.datastore.preferences.core.edit
import androidx.navigation.NavController
import com.jussicodes.music.BuildConfig
import com.jussicodes.music.LocalUiScaleController
import com.jussicodes.music.constants.apiBaseUrlKey
import com.jussicodes.music.constants.apiServerListKey
import com.jussicodes.music.constants.audioEffectEightDEnabledKey
import com.jussicodes.music.constants.audioEffectIntensityKey
import com.jussicodes.music.constants.audioEffectModeKey
import com.jussicodes.music.constants.audioEffectReverbEnabledKey
import com.jussicodes.music.constants.audioEffectSpeedKey
import com.jussicodes.music.constants.audioQualityKey
import com.jussicodes.music.constants.audioSourceKey
import com.jussicodes.music.constants.autoSkipNextOnErrorKey
import com.jussicodes.music.constants.cacheAutoCleanEnabledKey
import com.jussicodes.music.constants.cacheAutoCleanThresholdKey
import com.jussicodes.music.constants.desktopLyricEnabledKey
import com.jussicodes.music.constants.directSourceKey
import com.jussicodes.music.constants.homeBgBlurKey
import com.jussicodes.music.constants.homeBgImageKey
import com.jussicodes.music.constants.homeBgOffsetXKey
import com.jussicodes.music.constants.homeBgOffsetYKey
import com.jussicodes.music.constants.homeBgOpacityKey
import com.jussicodes.music.constants.homeBgScaleKey
import com.jussicodes.music.constants.homeBgTextColorKey
import com.jussicodes.music.constants.homeTransparentCardKey
import com.jussicodes.music.constants.lyricTranslationEnabledKey
import com.jussicodes.music.constants.ncmCookieKey
import com.jussicodes.music.constants.pinnedAlbumColumnsKey
import com.jussicodes.music.constants.pinnedAlbumsHiddenKey
import com.jussicodes.music.constants.themeColorSourceKey
import com.jussicodes.music.constants.topListEnabledKey
import com.jussicodes.music.constants.uiScaleKey
import com.jussicodes.music.constants.unblockSourceKey
import com.jussicodes.music.constants.use40DpIconKey
import com.jussicodes.music.constants.wordLyricEnabledKey
import com.jussicodes.music.desktop.DesktopPrefs
import com.jussicodes.music.desktop.lyrics.DesktopLyricController
import com.jussicodes.music.desktop.ui.ArtworkImage
import com.jussicodes.music.desktop.ui.DesktopUiState
import com.jussicodes.music.desktop.ui.SessionState
import com.jussicodes.music.desktop.weblogin.WebLoginBrowser
import com.jussicodes.music.lx.AudioSourceSwitcher
import com.jussicodes.music.lx.LxPluginManager
import com.jussicodes.music.playback.audio.EqSettings
import com.jussicodes.music.ui.components.AudioSourceSelector
import com.jussicodes.music.ui.components.LocalSourceDialog
import com.jussicodes.music.ui.components.UnblockSourceDialog
import com.jussicodes.music.ui.navigation.Screen
import com.jussicodes.music.ui.theme.ThemeColorSource
import com.jussicodes.music.utils.ApiServerStatus
import com.jussicodes.music.utils.AppUpdateManager
import com.jussicodes.music.utils.CacheManager
import com.jussicodes.music.utils.NetProxy
import com.jussicodes.music.utils.dataStore
import com.rcmiku.ncmapi.api.AudioSources
import com.rcmiku.ncmapi.api.LocalSources
import com.rcmiku.ncmapi.api.UnblockSources
import com.rcmiku.ncmapi.api.account.AccountApi
import com.rcmiku.ncmapi.api.apiGet
import com.rcmiku.ncmapi.api.player.PlayerApi
import com.rcmiku.ncmapi.api.player.SongLevel
import com.rcmiku.ncmapi.utils.CookieProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 桌面设置页（2.0 重写，替代安卓版的层层子页结构）：
 * 单页滚动分组——账号 / 播放（含 EQ）/ 音源设置 / 界面 / 桌面歌词 / 快捷键 /
 * 缓存 / 关于。音源设置组于 2.0.2 从共享版整组补回（2.0–2.0.1 曾整组漏掉，
 * API 服务器管理收进该组做条目对话框，与共享版信息架构一致）。
 * 所有控件改动即时生效并落盘（沿用上游 DataStore 键，老设置不丢失）。
 */
@Composable
fun SettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile by SessionState.profile.collectAsState()
    val expired by SessionState.expired.collectAsState()
    val scrollState = rememberScrollState()

    // 歌词组定位信号（播放栏歌词菜单「桌面歌词设置」）
    val lyricSettingsSignal by com.jussicodes.music.desktop.ui.LyricSettingsSignal.requests.collectAsState()
    LaunchedEffect(lyricSettingsSignal) {
        if (lyricSettingsSignal > 0) {
            runCatching { scrollState.animateScrollTo(1400) }
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(horizontal = 28.dp, vertical = 20.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        // ---------- 账号 ----------
        SettingsSection("账号")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            if (profile != null) {
                ArtworkImage(url = profile!!.avatarUrl, modifier = Modifier.size(44.dp), corner = 22.dp, thumbSize = 100)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile!!.nickname.ifBlank { "网易云用户" }, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (expired) "登录已过期，请重新登录" else "已登录 · UID ${profile!!.userId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (expired) Color(0xFFE83E52) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (expired) {
                    Button(onClick = { navController.navigate(Screen.Login.route) }) { Text("重新登录") }
                    Spacer(Modifier.width(8.dp))
                }
                OutlinedButton(onClick = {
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
                }) { Text("退出登录") }
            } else {
                Text("未登录（可正常浏览与搜索，登录后同步歌单与喜欢）", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Button(onClick = { navController.navigate(Screen.Login.route) }) { Text("去登录") }
            }
        }
        // 每日签到（2.0.2 接入 /daily_signin：安卓端 type=0 与 web 端 type=1 各签一次）
        if (profile != null) {
            var signState by remember { mutableStateOf("") }
            var signing by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("每日签到", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        signState.ifBlank { "签到领取经验与云贝（两个端口都签，与官网一致）" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    enabled = !signing,
                    onClick = {
                        signing = true
                        scope.launch {
                            val results = listOf(0, 1).map { type ->
                                apiGet<JsonObject>("/daily_signin", mapOf("type" to type))
                            }
                            signing = false
                            val okCount = results.count { r ->
                                r.getOrNull()?.get("code")?.jsonPrimitive?.intOrNull == 200
                            }
                            val firstMsg = results.firstNotNullOfOrNull { r ->
                                r.getOrNull()?.get("msg")?.jsonPrimitive?.contentOrNull
                                    ?: r.getOrNull()?.get("message")?.jsonPrimitive?.contentOrNull
                            }
                            signState = when {
                                okCount == 2 -> "签到成功（双端）"
                                okCount == 1 -> "签到成功（另一端今日可能已签）"
                                else -> "签到失败：${firstMsg ?: "请稍后再试"}"
                            }
                            Toast.makeText(context, signState, Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { Text(if (signing) "签到中…" else "签到") }
            }
        }

        // ---------- 播放 ----------
        SettingsSection("播放")
        var onlineQuality by remember { mutableStateOf(SongLevel.STANDARD) }
        var downloadQuality by remember { mutableStateOf(SongLevel.EXHIGH) }
        var autoSkip by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            val prefs = context.dataStore.data.first()
            // 2.0.4：在线音质默认值与 DesktopPlayer 的 enumPreference 默认
            // 统一为 STANDARD（与手机端一致）——此前设置页默认 EXHIGH、
            // 播放器默认 STANDARD，同键两处默认不一致，读不到存量值时
            // 两边各执一词。已存值不受影响。
            // 存量兼容：设置页旧版存 value（小写）、安卓存枚举名，两样都认。
            onlineQuality = SongLevel.entries.firstOrNull { it.name == prefs[audioQualityKey] }
                ?: SongLevel.entries.firstOrNull { it.value == prefs[audioQualityKey] }
                ?: SongLevel.STANDARD
            downloadQuality = SongLevel.entries.firstOrNull { it.value == prefs[DesktopPrefs.downloadQuality] } ?: SongLevel.EXHIGH
            autoSkip = prefs[autoSkipNextOnErrorKey] ?: false
        }
        QualityRow("在线播放音质", onlineQuality) { level ->
            onlineQuality = level
            scope.launch { context.dataStore.edit { it[audioQualityKey] = level.value } }
        }
        QualityRow("下载音质", downloadQuality) { level ->
            downloadQuality = level
            scope.launch { context.dataStore.edit { it[DesktopPrefs.downloadQuality] = level.value } }
        }
        SwitchRow("播放失败自动跳下一首", "音频地址失效或加载失败时自动跳过（对标网易云）", autoSkip) { on ->
            autoSkip = on
            scope.launch { context.dataStore.edit { it[autoSkipNextOnErrorKey] = on } }
        }
        // 网络代理（2.2.0，L1）：直连（默认）/ 跟随 Windows 系统代理。
        var proxyMode by remember { mutableStateOf(NetProxy.MODE_DIRECT) }
        LaunchedEffect(Unit) {
            proxyMode = context.dataStore.data.first()[DesktopPrefs.proxyMode]
                ?: NetProxy.MODE_DIRECT
        }
        ProxyRow(proxyMode) { mode ->
            proxyMode = mode
            NetProxy.apply(mode)
            scope.launch { context.dataStore.edit { it[DesktopPrefs.proxyMode] = mode } }
        }
        // 音效（均衡器）（2.0.2：10 段 biquad，引擎 PCM 管线实时处理；
        // 2.1.3 A4-04 改名「音效（均衡器）」，与播放页/播放条入口同名）
        var showEqDialog by remember { mutableStateOf(false) }
        fun eqSummaryText(): String {
            val base = if (EqSettings.enabled) "已开启 · ${EqSettings.preset}" else "已关闭"
            return if (EqSettings.reverbEnabled) "$base · 混响开" else base
        }
        var eqSummary by remember { mutableStateOf(eqSummaryText()) }
        SettingNavRow("音效（均衡器）", eqSummary) { showEqDialog = true }
        if (showEqDialog) {
            EqDialog(onDismiss = {
                showEqDialog = false
                eqSummary = eqSummaryText()
            })
        }

        // ---------- 音源设置（2.0.2 补齐：与「播放」不可分离的整组，见 AudioSourceSection） ----------
        SettingsSection("音源设置")
        AudioSourceSection(navController)

        // ---------- 界面 ----------
        SettingsSection("界面")
        var themeMode by remember { mutableStateOf(DesktopPrefs.THEME_SYSTEM) }
        var artworkTheme by remember { mutableStateOf(true) }
        var closeToTray by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            val prefs = context.dataStore.data.first()
            themeMode = prefs[DesktopPrefs.themeMode] ?: DesktopPrefs.THEME_SYSTEM
            artworkTheme = runCatching {
                ThemeColorSource.valueOf(prefs[themeColorSourceKey] ?: "") == ThemeColorSource.ARTWORK
            }.getOrDefault(false)
            closeToTray = prefs[DesktopPrefs.closeToTray] ?: false
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text("主题", style = MaterialTheme.typography.bodyLarge)
                Text("浅色 / 深色 / 跟随系统，切换即时生效", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                listOf(
                    DesktopPrefs.THEME_LIGHT to "浅色",
                    DesktopPrefs.THEME_DARK to "深色",
                    DesktopPrefs.THEME_SYSTEM to "跟随系统"
                ).forEach { (mode, label) ->
                    Box(
                        Modifier.clip(RoundedCornerShape(6.dp))
                            .background(if (themeMode == mode) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable {
                                themeMode = mode
                                scope.launch { context.dataStore.edit { it[DesktopPrefs.themeMode] = mode } }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (themeMode == mode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        SwitchRow("封面动态取色", "界面强调色跟随当前播放歌曲的封面主色（2.0 默认开启）", artworkTheme) { on ->
            artworkTheme = on
            scope.launch {
                context.dataStore.edit {
                    it[themeColorSourceKey] = (if (on) ThemeColorSource.ARTWORK else ThemeColorSource.WALLPAPER).name
                }
            }
        }
        val uiScaleController = LocalUiScaleController.current
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text("界面缩放", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "当前 ${(uiScaleController.scale * 100).toInt()}%，独立于系统缩放",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Slider(
                value = uiScaleController.scale,
                onValueChange = { uiScaleController.setScale(it) },
                valueRange = 0.7f..1.1f,
                modifier = Modifier.width(180.dp)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { uiScaleController.setScale(1f) }) { Text("重置") }
        }
        SwitchRow("关闭窗口时最小化到托盘", "点右上角关闭按钮后程序继续在托盘运行", closeToTray) { on ->
            closeToTray = on
            scope.launch { context.dataStore.edit { it[DesktopPrefs.closeToTray] = on } }
        }
        // 开机自启（2.0.5，C5）：启动文件夹 .lnk 路线，状态以 .lnk 是否存在为准
        var autoStart by remember { mutableStateOf(com.jussicodes.music.desktop.AutoStart.isEnabled()) }
        SwitchRow(
            "开机自启",
            "登录 Windows 后自动启动企鹅音乐（在启动文件夹放快捷方式）",
            autoStart
        ) { on ->
            val ok = com.jussicodes.music.desktop.AutoStart.setEnabled(on)
            autoStart = com.jussicodes.music.desktop.AutoStart.isEnabled()
            scope.launch { context.dataStore.edit { it[DesktopPrefs.autoStart] = autoStart } }
            Toast.makeText(
                context,
                if (ok) {
                    if (on) "已开启开机自启" else "已关闭开机自启"
                } else "设置失败（未找到程序本体或系统不支持）",
                Toast.LENGTH_SHORT
            ).show()
        }

        // ---------- 桌面歌词 ----------
        SettingsSection("桌面歌词")
        val lyricEnabled by DesktopLyricController.enabled.collectAsState()
        val lyricLocked by DesktopLyricController.locked.collectAsState()
        val lyricFontSize by DesktopLyricController.fontSize.collectAsState()
        val lyricOpacity by DesktopLyricController.opacity.collectAsState()
        val lyricPlayed by DesktopLyricController.playedColor.collectAsState()
        val lyricUnplayed by DesktopLyricController.unplayedColor.collectAsState()
        val lyricDouble by DesktopLyricController.doubleLine.collectAsState()
        val lyricWbw by DesktopLyricController.wordByWord.collectAsState()
        SwitchRow("显示桌面歌词", "独立置顶悬浮窗，播放时实时滚动", lyricEnabled) {
            DesktopLyricController.setEnabled(it)
        }
        SwitchRow("锁定桌面歌词", "锁定后鼠标穿透；解锁走托盘菜单或歌词窗口右键", lyricLocked) {
            DesktopLyricController.setLocked(it)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text("字号", style = MaterialTheme.typography.bodyLarge)
                Text("${lyricFontSize.toInt()} px", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(
                value = lyricFontSize,
                onValueChange = { DesktopLyricController.setFontSize(it) },
                valueRange = 18f..48f,
                modifier = Modifier.width(220.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text("背景透明度", style = MaterialTheme.typography.bodyLarge)
                Text("${(lyricOpacity * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(
                value = lyricOpacity,
                onValueChange = { DesktopLyricController.setOpacity(it) },
                valueRange = 0.3f..1f,
                modifier = Modifier.width(220.dp)
            )
        }
        ColorRow("已唱颜色", lyricPlayed, allowFollowArtwork = true) { DesktopLyricController.setPlayedColor(it) }
        ColorRow("未唱颜色", lyricUnplayed, allowFollowArtwork = false) { DesktopLyricController.setUnplayedColor(it) }
        SwitchRow("双行显示", "同时显示当前行与下一行", lyricDouble) {
            DesktopLyricController.setDoubleLine(it)
        }
        SwitchRow("逐字高亮", "有逐字歌词时按字变色（酷狗式卡拉OK效果）", lyricWbw) {
            DesktopLyricController.setWordByWord(it)
        }
        // 罗马音（2.0.5，C6）：与全屏歌词页同键（DesktopPrefs.lyricRoma）
        var romaOn by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            romaOn = context.dataStore.data.first()[DesktopPrefs.lyricRoma] ?: false
        }
        SwitchRow("歌词罗马音", "全屏歌词页在原文下方显示罗马音（歌曲带罗马音数据时）", romaOn) { on ->
            romaOn = on
            scope.launch { context.dataStore.edit { it[DesktopPrefs.lyricRoma] = on } }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text("歌词窗位置", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "窗口被拖出屏幕或找不到时，重置回屏幕底部居中",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = {
                DesktopLyricController.resetWindowPosition()
                Toast.makeText(context, "歌词窗位置已重置", Toast.LENGTH_SHORT).show()
            }) { Text("重置歌词窗位置") }
        }

        // ---------- 快捷键 ----------
        SettingsSection("快捷键")
        // 全局快捷键（2.0.5，C3）：窗口不聚焦时也生效（Windows 注册热键）
        var globalHotkeys by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            globalHotkeys = context.dataStore.data.first()[DesktopPrefs.globalHotkeys] ?: false
        }
        SwitchRow(
            "全局快捷键",
            "Ctrl+Alt+空格 播放/暂停 · ←/→ 上/下一首 · ↑/↓ 音量 · L 桌面歌词（窗口不聚焦时也生效）",
            globalHotkeys
        ) { on ->
            globalHotkeys = on
            scope.launch { context.dataStore.edit { it[DesktopPrefs.globalHotkeys] = on } }
            if (on) com.jussicodes.music.desktop.DesktopHotkeys.start()
            else com.jussicodes.music.desktop.DesktopHotkeys.stop()
        }
        listOf(
            "空格" to "播放 / 暂停（输入框聚焦时不触发）",
            "Ctrl + ← / →" to "上一首 / 下一首",
            "Ctrl + ↑ / ↓" to "音量加 / 减",
            "Ctrl + L" to "聚焦顶栏搜索框",
            "Alt + ← / →" to "后退 / 前进",
            "Esc" to "退出全屏播放页 / 关闭队列",
            "媒体键" to "键盘多媒体键（主窗口聚焦时有效）"
        ).forEach { (key, desc) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Box(
                    Modifier.clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) { Text(key, style = MaterialTheme.typography.labelMedium) }
                Spacer(Modifier.width(12.dp))
                Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---------- 缓存 ----------
        SettingsSection("下载与缓存")
        var cacheText by remember { mutableStateOf("计算中…") }
        LaunchedEffect(Unit) {
            val snap = runCatching { CacheManager.measure(context) }.getOrNull()
            cacheText = if (snap != null) {
                "音频 ${formatBytes(snap.audio.bytes)} · 图片 ${formatBytes(snap.image.bytes)} · 其他 ${formatBytes(snap.other.bytes)}（共 ${formatBytes(snap.totalBytes)}）"
            } else "读取失败"
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(cacheText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                scope.launch {
                    val ok = runCatching { CacheManager.clear(context) }.getOrDefault(false)
                    val snap = runCatching { CacheManager.measure(context) }.getOrNull()
                    if (snap != null) cacheText = "共 ${formatBytes(snap.totalBytes)}"
                    Toast.makeText(context, if (ok) "缓存已清理" else "清理失败", Toast.LENGTH_SHORT).show()
                }
            }) { Text("清理缓存") }
        }

        // ---------- 关于 ----------
        SettingsSection("关于")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text("企鹅音乐 PenguinMusic", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(
                    // 2.0.5（A7）：版本串走 BuildConfig 常量，不再硬编码
                    "版本 ${BuildConfig.VERSION_NAME} · 基于开源项目 jussichords ${BuildConfig.UPSTREAM_VERSION_NAME} 的桌面重制版",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = {
                scope.launch {
                    // 区分「检查失败」与「无新版」：此前 getOrNull() 把请求
                    // 失败（断网等）也吞成「已是最新版本」，误导用户。
                    val result = AppUpdateManager.checkUpdate()
                    val info = result.getOrNull()
                    when {
                        result.isFailure -> {
                            val reason = result.exceptionOrNull()?.message?.take(60)
                                ?: result.exceptionOrNull()?.javaClass?.simpleName
                                ?: "未知错误"
                            Toast.makeText(
                                context, "检查更新失败：$reason，请稍后重试",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        info != null -> {
                            Toast.makeText(
                                context, "发现新版本 ${info.versionName}，开始下载…",
                                Toast.LENGTH_SHORT
                            ).show()
                            com.jussicodes.music.utils.UpdateDownloadService
                                .start(context, info)
                        }
                        else -> {
                            Toast.makeText(context, "已是最新版本", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }) { Text("检查更新") }
        }
        // 打赏（2.1.3）：赞赏码图片打包在包内资源（donate.jpg），不外链。
        var showDonate by remember { mutableStateOf(false) }
        SettingNavRow("请作者喝杯咖啡 ☕", "觉得好用，可以请作者喝杯咖啡") { showDonate = true }
        if (showDonate) {
            DonateDialog(onDismiss = { showDonate = false })
        }
        // 恢复默认设置（2.2.0，L3）：只重置偏好，二次确认；保留清单
        // （登录态/本地曲库/队列/音源配置）见 resetPreferencesToDefaults。
        var showResetConfirm by remember { mutableStateOf(false) }
        SettingNavRow(
            "恢复默认设置",
            "重置播放 / 界面 / 歌词 / 音效等偏好；不影响登录状态、本地曲库与播放队列"
        ) { showResetConfirm = true }
        if (showResetConfirm) {
            AlertDialog(
                onDismissRequest = { showResetConfirm = false },
                title = { Text("恢复默认设置？") },
                text = {
                    Text(
                        "将把播放、界面、歌词、音效、代理与倍速等偏好重置为默认值。" +
                            "登录状态、本地曲库、播放队列与音源设置不受影响。部分设置重启后完全生效。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showResetConfirm = false
                        scope.launch {
                            resetPreferencesToDefaults(context)
                            Toast.makeText(
                                context, "已恢复默认设置（部分设置重启后完全生效）",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }) { Text("恢复默认") }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
                }
            )
        }
        Spacer(Modifier.height(30.dp))
    }
}

/**
 * 打赏对话框（2.1.3）：居中展示微信赞赏码整卡（包内资源 donate.jpg，
 * 由 make_fatjar.py 从 desktop/src/main/resources 打进 jar 根）。
 * 图片按原比例 Fit 显示、不裁切不变形，保证二维码可扫。
 */
@Composable
private fun DonateDialog(onDismiss: () -> Unit) {
    val bitmap = remember {
        runCatching {
            val bytes = Thread.currentThread().contextClassLoader
                ?.getResourceAsStream("donate.jpg")?.use { it.readBytes() }
                ?: ClassLoader.getSystemClassLoader()
                    ?.getResourceAsStream("donate.jpg")?.use { it.readBytes() }
            bytes?.let {
                org.jetbrains.skia.Image.makeFromEncoded(it)
                    .toComposeImageBitmap()
            }
        }.getOrNull()
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                Modifier.width(380.dp).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "觉得好用，可以请作者喝杯咖啡 ☕",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(14.dp))
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap,
                        contentDescription = "微信赞赏码",
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                } else {
                    Text(
                        "赞赏码图片加载失败",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(14.dp))
                Button(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String) {
    Spacer(Modifier.height(22.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(6.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private val QUALITY_OPTIONS: List<Pair<SongLevel, String>> = listOf(
    SongLevel.STANDARD to "标准",
    SongLevel.HIGHER to "较高",
    SongLevel.EXHIGH to "极高",
    SongLevel.LOSSLESS to "无损",
    SongLevel.HIRES to "Hi-Res"
)

@Composable
private fun QualityRow(label: String, current: SongLevel, onSelect: (SongLevel) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(QUALITY_OPTIONS.firstOrNull { it.first == current }?.second ?: current.value)
                Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(16.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                QUALITY_OPTIONS.forEach { (level, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        trailingIcon = {
                            if (level == current) Icon(Icons.Filled.Check, null, Modifier.size(16.dp))
                        },
                        onClick = { onSelect(level); open = false }
                    )
                }
            }
        }
    }
}

/** 网络代理选择行（2.2.0，L1）：直连 / 跟随系统，形态同 QualityRow。 */
@Composable
private fun ProxyRow(current: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text("网络代理", style = MaterialTheme.typography.bodyLarge)
            Text(
                "跟随系统时读取 Windows 系统代理设置（PAC 自动配置脚本不支持）；" +
                    "网页登录与 MV 播放不走此设置",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(if (current == NetProxy.MODE_SYSTEM) "跟随系统" else "直连")
                Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(16.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                listOf(
                    NetProxy.MODE_DIRECT to "直连",
                    NetProxy.MODE_SYSTEM to "跟随系统"
                ).forEach { (mode, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        trailingIcon = {
                            if (mode == current) {
                                Icon(Icons.Filled.Check, null, Modifier.size(16.dp))
                            }
                        },
                        onClick = { onSelect(mode); open = false }
                    )
                }
            }
        }
    }
}

/**
 * 恢复默认（2.2.0，L3）：只删「偏好类」键——
 *  - DesktopPrefs 全部键（窗口/界面/播放/歌词/2.2.0 新键）；
 *  - constants 中的播放/界面/歌词/音效/主页外观/缓存策略键；
 *  - EQ 六键（经 EqSettings.resetToDefaults 一并回内存默认）。
 * 明确保留（不在此列）：登录态（ncmCookieKey / userId）、账号与曲库
 * 缓存（library* / pinned* 数据键）、本地曲库（独立存储）、播放队列
 * （SongListUtil 文件）、音源与服务器配置（audioSource / directSource
 * / unblock / localSource / apiBaseUrl / apiServerList）、搜索历史
 * （独立 DataStore）。代理同时切回直连运行时态。
 */
private suspend fun resetPreferencesToDefaults(context: android.content.Context) {
    context.dataStore.edit { prefs ->
        val desktopKeys = listOf<androidx.datastore.preferences.core.Preferences.Key<*>>(
            DesktopPrefs.windowWidth, DesktopPrefs.windowHeight,
            DesktopPrefs.windowX, DesktopPrefs.windowY,
            DesktopPrefs.windowMaximized, DesktopPrefs.sidebarWidth,
            DesktopPrefs.lastRoute, DesktopPrefs.themeMode,
            DesktopPrefs.closeToTray, DesktopPrefs.globalHotkeys,
            DesktopPrefs.autoStart, DesktopPrefs.queuePanelOpen,
            DesktopPrefs.volume, DesktopPrefs.repeatMode, DesktopPrefs.shuffle,
            DesktopPrefs.downloadQuality, DesktopPrefs.playbackSpeed,
            DesktopPrefs.proxyMode, DesktopPrefs.mvClarity,
            DesktopPrefs.lyricLocked, DesktopPrefs.lyricFontSize,
            DesktopPrefs.lyricPlayedColor, DesktopPrefs.lyricUnplayedColor,
            DesktopPrefs.lyricOpacity, DesktopPrefs.lyricDoubleLine,
            DesktopPrefs.lyricWordByWord, DesktopPrefs.lyricRoma,
            DesktopPrefs.lyricTranslation,
            DesktopPrefs.lyricX, DesktopPrefs.lyricY, DesktopPrefs.lyricWidth
        )
        desktopKeys.forEach { prefs.remove(it) }
        prefs.remove(audioQualityKey)
        prefs.remove(autoSkipNextOnErrorKey)
        prefs.remove(themeColorSourceKey)
        prefs.remove(desktopLyricEnabledKey)
        prefs.remove(lyricTranslationEnabledKey)
        prefs.remove(wordLyricEnabledKey)
        prefs.remove(uiScaleKey)
        prefs.remove(use40DpIconKey)
        prefs.remove(audioEffectModeKey)
        prefs.remove(audioEffectEightDEnabledKey)
        prefs.remove(audioEffectReverbEnabledKey)
        prefs.remove(audioEffectIntensityKey)
        prefs.remove(audioEffectSpeedKey)
        prefs.remove(pinnedAlbumColumnsKey)
        prefs.remove(pinnedAlbumsHiddenKey)
        prefs.remove(topListEnabledKey)
        prefs.remove(homeBgImageKey)
        prefs.remove(homeBgScaleKey)
        prefs.remove(homeBgOpacityKey)
        prefs.remove(homeBgBlurKey)
        prefs.remove(homeBgOffsetXKey)
        prefs.remove(homeBgOffsetYKey)
        prefs.remove(homeBgTextColorKey)
        prefs.remove(homeTransparentCardKey)
        prefs.remove(cacheAutoCleanEnabledKey)
        prefs.remove(cacheAutoCleanThresholdKey)
    }
    EqSettings.resetToDefaults(context)
    NetProxy.apply(NetProxy.MODE_DIRECT)
}

private val LYRIC_COLOR_PRESETS: List<Long> = listOf(
    0xFFFFC53D, // 琥珀黄（酷狗风）
    0xFF39C5BB, // 青
    0xFF7C5CFF, // 紫
    0xFFE83E52, // 红
    0xFF4CC38A, // 绿
    0xFFFFFFFF // 白
)
private val UNPLAYED_PRESETS: List<Long> = listOf(
    0xFFFFFFFF, 0xFFD9D9D9, 0xFF9AA0A6, 0xFF5F6368
)

@Composable
private fun ColorRow(label: String, current: Long, allowFollowArtwork: Boolean, onPick: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (allowFollowArtwork) {
            val selected = current == DesktopLyricController.COLOR_FOLLOW_ARTWORK
            Box(
                Modifier.clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(
                        2.dp,
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        RoundedCornerShape(14.dp)
                    )
                    .clickable { onPick(DesktopLyricController.COLOR_FOLLOW_ARTWORK) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text("跟随封面", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.width(8.dp))
        }
        (LYRIC_COLOR_PRESETS + UNPLAYED_PRESETS).distinct().forEach { preset ->
            val selected = current == preset
            Box(
                Modifier.size(24.dp).clip(CircleShape)
                    .background(Color(preset))
                    .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f), CircleShape)
                    .clickable { onPick(preset) },
                contentAlignment = Alignment.Center
            ) {
                if (selected) Icon(Icons.Filled.Check, null, Modifier.size(13.dp), tint = Color.Black.copy(alpha = 0.65f))
            }
            Spacer(Modifier.width(7.dp))
        }
    }
}

@Composable
private fun ApiServerSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var urls by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentUrl by remember { mutableStateOf("") }
    var customUrl by remember { mutableStateOf("") }
    // 2.0.1：测速状态（此前 measureApiServers 从未被 UI 调用，用户无从知道哪台活着）
    var statuses by remember { mutableStateOf<Map<String, ApiServerStatus>>(emptyMap()) }
    var testingAll by remember { mutableStateOf(false) }
    var testingUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val prefs = context.dataStore.data.first()
        currentUrl = prefs[apiBaseUrlKey].orEmpty()
        urls = prefs[apiServerListKey]?.let {
            runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull()
        }.orEmpty()
    }
    Column(Modifier.fillMaxWidth()) {
        Text(
            "音源选「API 代理服务器」时，VIP/灰色歌曲的播放取链走所选服务器解灰" +
                "（GET /song/url/match）；免费歌曲与搜索/歌单等主数据仍直连官方。" +
                "测速与取链同口径（真实解灰端点），测通即代表该台能解灰。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (currentUrl.isBlank()) "当前未选择服务器（代理音源暂不可用，请选一台）" else "当前服务器：$currentUrl",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                enabled = urls.isNotEmpty() && !testingAll && testingUrl == null,
                onClick = {
                    testingAll = true
                    scope.launch {
                        val results = AppUpdateManager.measureApiServers(urls)
                        statuses = results.associateBy { it.server }
                        testingAll = false
                        Toast.makeText(context, "测速完成", Toast.LENGTH_SHORT).show()
                    }
                }
            ) { Text(if (testingAll) "测试中…" else "全部测试") }
        }
        Spacer(Modifier.height(4.dp))
        urls.forEach { url ->
            val status = statuses[url]
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                    .clickable {
                        currentUrl = url
                        scope.launch { context.dataStore.edit { it[apiBaseUrlKey] = url } }
                    }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(16.dp).clip(CircleShape)
                        .border(2.dp, if (currentUrl == url) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (currentUrl == url) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    }
                }
                Spacer(Modifier.width(9.dp))
                Text(url, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                // 状态点：灰=未测试，绿=可用，红=不可用/超时
                Box(
                    Modifier.size(9.dp).clip(CircleShape).background(
                        when {
                            status == null -> MaterialTheme.colorScheme.outline
                            status.available -> Color(0xFF4CC38A)
                            else -> Color(0xFFE83E52)
                        }
                    )
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    status?.message ?: "未测试",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    enabled = testingUrl == null && !testingAll,
                    onClick = {
                        testingUrl = url
                        scope.launch {
                            val result = AppUpdateManager.measureApiServers(listOf(url)).firstOrNull()
                            if (result != null) statuses = statuses + (url to result)
                            testingUrl = null
                        }
                    }
                ) { Text(if (testingUrl == url) "测试中…" else "测试") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(
                value = customUrl,
                onValueChange = { customUrl = it },
                label = { Text("自定义服务器地址") },
                placeholder = { Text("https://example.com") },
                singleLine = true,
                // #5：登记输入焦点，聚焦时全局空格键让行给输入，
                // 避免打字误触播放/暂停（与 SearchScreen 同写法）。
                modifier = Modifier.weight(1f)
                    .onFocusChanged { DesktopUiState.inputFocused.value = it.isFocused }
            )
            Spacer(Modifier.width(10.dp))
            Button(
                enabled = customUrl.startsWith("http"),
                onClick = {
                    val url = customUrl.trim().trimEnd('/')
                    val newList = (urls + url).distinct()
                    urls = newList
                    currentUrl = url
                    customUrl = ""
                    scope.launch {
                        context.dataStore.edit {
                            it[apiBaseUrlKey] = url
                            it[apiServerListKey] = Json.encodeToString(newList)
                        }
                    }
                    Toast.makeText(context, "已切换到自定义服务器", Toast.LENGTH_SHORT).show()
                }
            ) { Text("保存并使用") }
        }
    }
}

/** 设置页通用导航行：标题 + 副标题 + 右侧箭头，整行可点。 */
@Composable
private fun SettingNavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            Icons.Filled.ChevronRight, null,
            Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 音源设置组（2.0.2 从共享版 SettingsScreen 整组搬入，桌面 2.0/2.0.1 曾整组
 * 漏掉）：三选一音源与 API 服务器不可分离——PROXY 模式下播放取链就走所选
 * 服务器。切换语义与共享版一致：写 DataStore → 稍等落盘 → AudioSourceSwitcher
 * .apply（写运行时选择、更新 cacheKey 音源代号、发重载信号让当前歌曲重解析；
 * 桌面引擎自 2.0.2 起消费该信号并清直链缓存）。
 */
@Composable
private fun AudioSourceSection(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var audioSource by remember { mutableStateOf(AudioSources.DEFAULT) }
    var directSource by remember { mutableStateOf(LocalSources.AUTO) }
    var unblockSource by remember { mutableStateOf(UnblockSources.AUTO) }
    var apiBaseUrl by remember { mutableStateOf("") }
    var lxCount by remember { mutableStateOf(0) }
    var showLocalDialog by remember { mutableStateOf(false) }
    var showUnblockDialog by remember { mutableStateOf(false) }
    var showApiDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val prefs = context.dataStore.data.first()
        audioSource = AudioSources.fromKey(prefs[audioSourceKey].orEmpty())?.value
            ?: AudioSources.DEFAULT
        directSource = LocalSources.fromKey(prefs[directSourceKey].orEmpty())?.value
            ?: LocalSources.AUTO
        unblockSource = prefs[unblockSourceKey] ?: UnblockSources.AUTO
        apiBaseUrl = prefs[apiBaseUrlKey].orEmpty()
        runCatching { lxCount = LxPluginManager.plugins.first().size }
    }
    // 服务器管理对话框关掉后回读当前服务器（对话框内可能改了选择）
    LaunchedEffect(showApiDialog) {
        if (!showApiDialog) {
            apiBaseUrl = context.dataStore.data.first()[apiBaseUrlKey].orEmpty()
        }
    }

    fun reloadCurrentSong() {
        // 与共享版 reloadCurrentSong 同逻辑：DataStore 写盘是异步的，
        // 先落定再 apply，避免运行时值与落盘值赛跑。
        scope.launch {
            delay(150)
            AudioSourceSwitcher.apply(audioSource, directSource)
        }
    }

    val lxScriptSummary = if (lxCount > 0) {
        "已安装 $lxCount 个脚本，点击进入脚本管理"
    } else {
        "尚未安装脚本，点击进入脚本管理"
    }
    val directSourceSummary = if (directSource == LocalSources.AUTO) {
        "自动选择（六源依次尝试）"
    } else {
        LocalSources.fromKey(directSource)?.label ?: directSource
    }
    val apiServerSummary = if (apiBaseUrl.isBlank()) {
        "尚未选择服务器（代理音源需要先选一台）"
    } else {
        apiBaseUrl
    }
    val unblockLabel = UnblockSources.ALL_OPTIONS.firstOrNull { it.value == unblockSource }?.label
        ?: unblockSource

    Column(Modifier.fillMaxWidth()) {
        Text(
            "音源决定 VIP / 灰色歌曲的取链通道：所选音源优先，失败自动级联" +
                "官方直连与解灰匹配（2.0.3 起），切换后当前歌曲立即重解析。" +
                "免费歌曲始终走官方直连。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        AudioSourceSelector(
            currentSource = audioSource,
            onSourceSelected = { value ->
                if (value != audioSource) {
                    audioSource = value
                    scope.launch { context.dataStore.edit { it[audioSourceKey] = value } }
                    reloadCurrentSong()
                }
            }
        )
        Spacer(Modifier.height(6.dp))
        val inUseText = when (audioSource) {
            AudioSources.LX ->
                if (lxCount > 0) "正在使用：LX 插件音源（$lxCount 个脚本）"
                else "正在使用：LX 插件音源（暂无脚本，取链失败时回退官方直连）"
            AudioSources.DIRECT -> "正在使用：内置直连音源（$directSourceSummary）"
            else -> "正在使用：API 代理服务器（$apiServerSummary）"
        }
        Text(
            inUseText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        SettingNavRow("LX 脚本管理", lxScriptSummary) {
            navController.navigate(Screen.LxPlugins.route)
        }
        SettingNavRow("直连音源", "当前：$directSourceSummary") { showLocalDialog = true }
        SettingNavRow("API 服务器", "当前：$apiServerSummary") { showApiDialog = true }
        SettingNavRow("更换解灰源", "当前：$unblockLabel（代理音源的匹配源偏好）") {
            showUnblockDialog = true
        }
    }

    if (showLocalDialog) {
        LocalSourceDialog(
            currentSource = directSource,
            onDismiss = { showLocalDialog = false },
            onSourceSelected = { value ->
                directSource = value
                PlayerApi.DirectSourceSettings.SOURCE = value
                scope.launch { context.dataStore.edit { it[directSourceKey] = value } }
                reloadCurrentSong()
                showLocalDialog = false
            }
        )
    }
    if (showUnblockDialog) {
        UnblockSourceDialog(
            currentSource = unblockSource,
            onDismiss = { showUnblockDialog = false },
            onSourceSelected = { value ->
                unblockSource = value
                scope.launch { context.dataStore.edit { it[unblockSourceKey] = value } }
                showUnblockDialog = false
            }
        )
    }
    if (showApiDialog) {
        Dialog(onDismissRequest = { showApiDialog = false }) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    Modifier.width(680.dp)
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "API 服务器管理",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = { showApiDialog = false }) { Text("关闭") }
                    }
                    Spacer(Modifier.height(10.dp))
                    ApiServerSection()
                }
            }
        }
    }
}

/**
 * 音效对话框（原 EQ 对话框）：均衡器开关 + 预设 + 10 段滑杆（-12..+12 dB）
 * + 8D 环绕 + 混响（2.1.3，A4-05）。改动即时生效；滑杆拖动中只实时
 * 预览（A4-06），松手才落盘。2.1.3（A4-04）起为 internal：播放页顶栏
 * 与底部播放条的音效按钮打开的是同一个对话框。
 */
@Composable
internal fun EqDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(EqSettings.enabled) }
    var surround by remember { mutableStateOf(EqSettings.surround8d) }
    var reverb by remember { mutableStateOf(EqSettings.reverbEnabled) }
    var reverbIntensity by remember { mutableStateOf(EqSettings.reverbIntensity) }
    var preset by remember { mutableStateOf(EqSettings.preset) }
    var gains by remember { mutableStateOf(EqSettings.gains.copyOf()) }

    fun push() {
        EqSettings.apply(enabled, preset, gains)
        scope.launch { EqSettings.save(context) }
    }

    /** 滑杆拖动中的实时预览：只更新引擎内存值，不写盘不刷日志（A4-06）。 */
    fun preview() {
        EqSettings.previewApply(enabled, preset, gains)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                Modifier.width(580.dp).padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("音效（均衡器）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "10 段均衡 + 混响 + 8D 环绕，播放中调整即时生效",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = {
                        enabled = it
                        if (it && preset == EqSettings.PRESET_OFF) preset = "流行"
                        if (it && preset == "流行" && gains.all { g -> g == 0f }) {
                            gains = EqSettings.PRESETS.first { p -> p.first == "流行" }.second.copyOf()
                        }
                        push()
                    })
                }
                // 8D 环绕（2.0.5，C12）：PCM 层声像 LFO 自动旋转，与 EQ 解耦
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("8D 环绕", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "声像自动环绕旋转；仅立体声 PCM 链生效（MP3/FLAC 解码流）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = surround, onCheckedChange = {
                        surround = it
                        EqSettings.setSurround(it)
                        scope.launch { EqSettings.save(context) }
                    })
                }
                // 混响（2.1.3，A4-05）：Schroeder 混响（移植安卓
                // ReverbAudioProcessor），强度驱动 wet/dry/空间感/阻尼；
                // 与 EQ/8D 同管线，仅 PCM 链生效（MP3/FLAC 解码流）。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("混响", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "模拟空间回响；仅立体声 PCM 链生效（MP3/FLAC 解码流）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = reverb, onCheckedChange = {
                        reverb = it
                        EqSettings.setReverb(it)
                        scope.launch { EqSettings.save(context) }
                    })
                }
                if (reverb) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "混响强度",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(64.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = reverbIntensity,
                            onValueChange = { v ->
                                reverbIntensity = v
                                // 拖动中只实时预览（引擎即时跟随），不写盘。
                                EqSettings.updateReverbIntensity(v)
                            },
                            onValueChangeFinished = {
                                scope.launch { EqSettings.save(context) }
                            },
                            valueRange = 0f..1f,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${(reverbIntensity * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(52.dp)
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                EqSettings.PRESETS.chunked(3).forEach { rowPresets ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowPresets.forEach { (name, presetGains) ->
                            val selected = preset == name
                            Box(
                                Modifier.clip(RoundedCornerShape(16.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceContainerHigh
                                    )
                                    .clickable {
                                        preset = name
                                        gains = presetGains.copyOf()
                                        enabled = name != EqSettings.PRESET_OFF
                                        push()
                                    }
                                    .padding(horizontal = 14.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    name,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(4.dp))
                gains.forEachIndexed { index, gain ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${EqSettings.BAND_FREQS[index]} Hz",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(64.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = gain,
                            onValueChange = { newGain ->
                                val updated = gains.copyOf()
                                updated[index] = newGain
                                gains = updated
                                preset = "自定义"
                                if (!enabled) enabled = true
                                // A4-06：拖动中只预览（引擎只换系数不清
                                // 滤波状态、不落盘），松手才正式 apply+存盘。
                                preview()
                            },
                            onValueChangeFinished = { push() },
                            valueRange = -12f..12f,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "%+.0f dB".format(gain),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(52.dp)
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row {
                    OutlinedButton(onClick = {
                        preset = EqSettings.PRESET_OFF
                        gains = FloatArray(10)
                        enabled = false
                        push()
                    }) { Text("重置") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onDismiss) { Text("完成") }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / 1073741824.0)
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
