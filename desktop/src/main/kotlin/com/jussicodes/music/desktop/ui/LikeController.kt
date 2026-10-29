package com.jussicodes.music.desktop.ui

import android.content.Context
import com.jussicodes.music.utils.FavoriteSongAction
import com.jussicodes.music.utils.FavoriteSongIdsUtil
import com.rcmiku.ncmapi.utils.CookieProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 红心（喜欢）状态：本地集合给 UI 即时反馈，写操作走应用既有的
 * FavoriteSongAction（本地落盘 + 服务端同步队列），不另起一套。
 */
object LikeController {
    val likedIds = MutableStateFlow<Set<Long>>(emptySet())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        scope.launch {
            runCatching {
                likedIds.value = FavoriteSongIdsUtil.getAllSongIds(context).toSet()
            }
        }
    }

    fun refresh(context: Context) {
        scope.launch {
            runCatching {
                likedIds.value = FavoriteSongIdsUtil.getAllSongIds(context).toSet()
            }
        }
    }

    /** 返回 false = 未登录，调用方负责提示并引导登录。 */
    fun toggle(context: Context, songId: Long): Boolean {
        if (!CookieProvider.isLoggedIn()) return false
        val nowLiked = songId !in likedIds.value
        likedIds.value = if (nowLiked) likedIds.value + songId else likedIds.value - songId
        scope.launch {
            runCatching { FavoriteSongAction.setLiked(context, songId, nowLiked) }
            refresh(context)
        }
        return true
    }
}
