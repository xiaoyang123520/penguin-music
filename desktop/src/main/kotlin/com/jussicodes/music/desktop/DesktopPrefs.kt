package com.jussicodes.music.desktop

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * 桌面版 2.0 自己的偏好键（与 1.x 的键同住一个 settings.preferences_pb，
 * 老 Cookie/设置/队列文件格式一律不动，老用户升级不断档）。
 */
object DesktopPrefs {
    // 窗口几何（逻辑 dp / 屏幕像素坐标）
    val windowWidth = floatPreferencesKey("desktop.windowWidth")
    val windowHeight = floatPreferencesKey("desktop.windowHeight")
    val windowX = intPreferencesKey("desktop.windowX")
    val windowY = intPreferencesKey("desktop.windowY")
    val windowMaximized = booleanPreferencesKey("desktop.windowMaximized")
    val sidebarWidth = floatPreferencesKey("desktop.sidebarWidth")
    val lastRoute = stringPreferencesKey("desktop.lastRoute")

    // 界面
    /** SYSTEM / LIGHT / DARK */
    val themeMode = stringPreferencesKey("desktop.themeMode")
    val closeToTray = booleanPreferencesKey("desktop.closeToTray")

    // 系统集成（2.0.5）
    /** 全局快捷键总开关（Ctrl+Alt 组合，见 DesktopHotkeys）。 */
    val globalHotkeys = booleanPreferencesKey("desktop.globalHotkeys")
    /** 开机自启（启动文件夹 .lnk 的存在与否为准，此键仅镜像 UI 状态）。 */
    val autoStart = booleanPreferencesKey("desktop.autoStart")
    val queuePanelOpen = booleanPreferencesKey("desktop.queuePanelOpen")

    // 播放（音量/模式持久化；队列本身仍走 SongListUtil 老通道）
    val volume = floatPreferencesKey("desktop.volume")
    val repeatMode = intPreferencesKey("desktop.repeatMode")
    val shuffle = booleanPreferencesKey("desktop.shuffle")

    // 2.2.0 新增偏好
    /** 播放倍速（L5）：0.5–2.0，默认 1.0；音乐 PCM 链与 MV 共用同一落盘值。 */
    val playbackSpeed = floatPreferencesKey("desktop.playbackSpeed")
    /** 网络代理模式（L1）：DIRECT（默认）/ SYSTEM，见 NetProxy。 */
    val proxyMode = stringPreferencesKey("desktop.proxyMode")
    /** MV 清晰度（L6）：选定的 br（如 1080）；0 = 自动（最高 ≤1080 否则最大）。 */
    val mvClarity = intPreferencesKey("desktop.mvClarity")
    /** 全屏歌词译文开关落盘（A5-14；此前 remember 内存态、重启即丢）。 */
    val lyricTranslation = booleanPreferencesKey("desktop.lyricTranslation")

    // 下载音质（与在线音质 audioQualityKey 分开存，值域同 SongLevel.value）
    val downloadQuality = stringPreferencesKey("desktop.downloadQuality")

    // 桌面歌词
    val lyricLocked = booleanPreferencesKey("desktop.lyricLocked")
    val lyricFontSize = floatPreferencesKey("desktop.lyricFontSize")
    val lyricPlayedColor = longPreferencesKey("desktop.lyricPlayedColor")
    val lyricUnplayedColor = longPreferencesKey("desktop.lyricUnplayedColor")
    val lyricOpacity = floatPreferencesKey("desktop.lyricOpacity")
    val lyricDoubleLine = booleanPreferencesKey("desktop.lyricDoubleLine")
    val lyricWordByWord = booleanPreferencesKey("desktop.lyricWordByWord")
    /** 歌词罗马音显示（2.0.5，全屏歌词页开关，设置页歌词组同键）。 */
    val lyricRoma = booleanPreferencesKey("desktop.lyricRoma")
    val lyricX = intPreferencesKey("desktop.lyricX")
    val lyricY = intPreferencesKey("desktop.lyricY")
    val lyricWidth = intPreferencesKey("desktop.lyricWidth")

    const val THEME_SYSTEM = "SYSTEM"
    const val THEME_LIGHT = "LIGHT"
    const val THEME_DARK = "DARK"
}

/** 倍速档位（2.2.0，L5）：播放条与 MV 窗口共用同一组档位与落盘键。 */
val PLAYBACK_RATES: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** 倍速展示文案：1.0× / 0.5× / 1.25×（去掉无意义的尾零）。 */
fun formatPlaybackSpeed(speed: Float): String {
    val v = if (speed == speed.toInt().toFloat()) {
        speed.toInt().toString()
    } else {
        speed.toString().trimEnd('0').trimEnd('.')
    }
    return "$v×"
}
