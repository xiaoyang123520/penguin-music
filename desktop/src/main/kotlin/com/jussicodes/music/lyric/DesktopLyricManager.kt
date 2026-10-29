package com.jussicodes.music.lyric

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.jussicodes.music.constants.desktopLyricEnabledKey
import com.jussicodes.music.desktop.lyrics.DesktopLyricController
import com.jussicodes.music.utils.dataStore

/**
 * Desktop 2.0：桌面歌词是真悬浮窗（独立 Window，见 DesktopMain +
 * DesktopLyricController），不再需要安卓的悬浮窗权限概念——
 * canDrawOverlays 恒 true，开关直接转给桌面歌词控制器。
 * 保留本类是给播放引擎的老命令通道（DesktopPlayer 的桌面歌词命令）兜底。
 */
object DesktopLyricManager {

    fun canDrawOverlays(context: Context): Boolean = true

    fun requestOverlayPermission(context: Context) {
        // 桌面无悬浮窗权限概念，无需任何操作。
    }

    suspend fun setEnabled(
        context: Context,
        enabled: Boolean,
        requestPermissionIfNeeded: Boolean = false
    ): Boolean {
        context.applicationContext.dataStore.edit { it[desktopLyricEnabledKey] = enabled }
        DesktopLyricController.setEnabled(enabled)
        return true
    }

    fun syncService(context: Context) {
        // 桌面歌词由应用进程内的窗口承载，没有独立 Service 需要同步。
    }
}
