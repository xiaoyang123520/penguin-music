package com.jussicodes.music.playback

import android.widget.ToastSink
import java.util.concurrent.ConcurrentHashMap

/**
 * 播放链专用 Toast 通道（2.0.3）：播放器在引擎层（无 Compose 上下文），
 * 失败反馈经 android.widget.Toast 垫片 → ToastSink → DesktopToast 展示。
 * 同文案 4 秒内去重，防止自动跳歌时连续失败刷屏。
 */
object PlaybackToast {

    private val lastShownAt = ConcurrentHashMap<String, Long>()
    private const val DEDUP_MS = 4_000L

    fun show(message: String) {
        val now = System.currentTimeMillis()
        val last = lastShownAt[message] ?: 0L
        if (now - last < DEDUP_MS) return
        lastShownAt[message] = now
        runCatching { ToastSink.handler(message) }
        println("[Toast] $message")
    }

    /** 取链/播放终局失败（文案自带步骤细节）。 */
    fun fail(message: String) = show(message)
}
