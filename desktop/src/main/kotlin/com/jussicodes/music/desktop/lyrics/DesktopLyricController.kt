package com.jussicodes.music.desktop.lyrics

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.jussicodes.music.constants.desktopLyricEnabledKey
import com.jussicodes.music.desktop.DesktopPrefs
import com.jussicodes.music.playback.PlayerState
import com.jussicodes.music.utils.dataStore
import com.jussicodes.music.utils.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 桌面歌词控制器：设置状态（全部落 DataStore）+ 歌词同步引擎
 * （轮询播放位置 → 当前行/下一行/位置流，供悬浮窗渲染逐字变色）。
 * 窗口本体是纯 AWT 的 DesktopLyricAwtWindow（2.0.3 起），按 [enabled] 显隐。
 */
object DesktopLyricController {
    /** playedColor 的哨兵值：0 = 跟随当前封面取色（主题 primary）。 */
    const val COLOR_FOLLOW_ARTWORK: Long = 0L

    val enabled = MutableStateFlow(false)
    val locked = MutableStateFlow(false)
    val fontSize = MutableStateFlow(30f)
    val playedColor = MutableStateFlow(COLOR_FOLLOW_ARTWORK)
    val unplayedColor = MutableStateFlow(0xFFFFFFFFL)
    val opacity = MutableStateFlow(0.9f)
    val doubleLine = MutableStateFlow(true)
    val wordByWord = MutableStateFlow(true)

    val entries = MutableStateFlow<List<LyricEntry>>(emptyList())
    val currentIndex = MutableStateFlow(-1)
    val positionMs = MutableStateFlow(0L)
    val currentSongId = MutableStateFlow(0L)

    /** 窗口位置/宽度（屏幕像素），-1 = 未设置（用默认底部居中）。 */
    val windowX = MutableStateFlow(-1)
    val windowY = MutableStateFlow(-1)
    val windowWidth = MutableStateFlow(760)

    @Volatile var playerProvider: () -> PlayerState? = { null }
    @Volatile private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var engineStarted = false

    fun init(context: Context) {
        appContext = context.applicationContext
        val store = appContext!!.dataStore
        enabled.value = store.get(desktopLyricEnabledKey, false)
        locked.value = store.get(DesktopPrefs.lyricLocked, false)
        fontSize.value = store.get(DesktopPrefs.lyricFontSize, 30f)
        playedColor.value = store.get(DesktopPrefs.lyricPlayedColor, COLOR_FOLLOW_ARTWORK)
        unplayedColor.value = store.get(DesktopPrefs.lyricUnplayedColor, 0xFFFFFFFFL)
        opacity.value = store.get(DesktopPrefs.lyricOpacity, 0.9f)
        doubleLine.value = store.get(DesktopPrefs.lyricDoubleLine, true)
        wordByWord.value = store.get(DesktopPrefs.lyricWordByWord, true)
        windowX.value = store.get(DesktopPrefs.lyricX, -1)
        windowY.value = store.get(DesktopPrefs.lyricY, -1)
        windowWidth.value = store.get(DesktopPrefs.lyricWidth, 760)
        startEngine()
    }

    private fun startEngine() {
        if (engineStarted) return
        engineStarted = true
        scope.launch {
            var loadedForSong = 0L
            while (isActive) {
                val ps = playerProvider()
                if (ps == null || !enabled.value) {
                    delay(400)
                    continue
                }
                val songId = ps.currentMediaItem?.mediaId?.toLongOrNull() ?: 0L
                if (songId != loadedForSong) {
                    loadedForSong = songId
                    currentSongId.value = songId
                    entries.value = emptyList()
                    currentIndex.value = -1
                    if (songId > 0) {
                        val loaded = runCatching { LyricRepository.load(songId) }.getOrDefault(emptyList())
                        if (loadedForSong == songId) entries.value = loaded
                    }
                }
                val pos = ps.player.currentPosition.coerceAtLeast(0)
                positionMs.value = pos
                if (entries.value.isNotEmpty()) {
                    currentIndex.value = LyricRepository.indexAt(entries.value, pos)
                }
                delay(200)
            }
        }
    }

    private fun persist(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        val ctx = appContext ?: return
        scope.launch(Dispatchers.IO) { ctx.dataStore.edit { block(it) } }
    }

    fun setEnabled(value: Boolean) {
        enabled.value = value
        // 2.0.2 诊断：开关动作留痕（真机「点了不出窗」可直接查
        // ~/.jussichords/logs/lyric-window.log）。
        com.jussicodes.music.desktop.DesktopLog.lyric(
            "setEnabled($value) savedPos=(${windowX.value},${windowY.value}) w=${windowWidth.value}"
        )
        // 2.0.3：直接驱动 AWT 歌词窗（不等 Compose 桥接，保证点击必有反馈）。
        DesktopLyricAwtWindow.setVisible(value)
        persist { it[desktopLyricEnabledKey] = value }
    }

    fun setLocked(value: Boolean) {
        locked.value = value
        persist { it[DesktopPrefs.lyricLocked] = value }
    }

    fun setFontSize(value: Float) {
        fontSize.value = value
        persist { it[DesktopPrefs.lyricFontSize] = value }
    }

    fun setPlayedColor(value: Long) {
        playedColor.value = value
        persist { it[DesktopPrefs.lyricPlayedColor] = value }
    }

    fun setUnplayedColor(value: Long) {
        unplayedColor.value = value
        persist { it[DesktopPrefs.lyricUnplayedColor] = value }
    }

    fun setOpacity(value: Float) {
        opacity.value = value
        persist { it[DesktopPrefs.lyricOpacity] = value }
    }

    fun setDoubleLine(value: Boolean) {
        doubleLine.value = value
        persist { it[DesktopPrefs.lyricDoubleLine] = value }
    }

    fun setWordByWord(value: Boolean) {
        wordByWord.value = value
        persist { it[DesktopPrefs.lyricWordByWord] = value }
    }

    /** 重置歌词窗位置（2.0.1）：X/Y 置回 -1（壳层按底部居中打开）、宽度回默认。 */
    fun resetWindowPosition() {
        windowX.value = -1
        windowY.value = -1
        windowWidth.value = 760
        persist {
            it[DesktopPrefs.lyricX] = -1
            it[DesktopPrefs.lyricY] = -1
            it[DesktopPrefs.lyricWidth] = 760
        }
    }

    fun saveWindowBounds(x: Int, y: Int, width: Int) {
        windowX.value = x
        windowY.value = y
        windowWidth.value = width
        persist {
            it[DesktopPrefs.lyricX] = x
            it[DesktopPrefs.lyricY] = y
            it[DesktopPrefs.lyricWidth] = width
        }
    }
}
