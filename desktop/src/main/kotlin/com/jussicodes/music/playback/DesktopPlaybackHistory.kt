package com.jussicodes.music.playback

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.jussicodes.music.BuildConfig
import com.jussicodes.music.constants.MediaSessionConstants
import com.rcmiku.ncmapi.api.DeviceIdentity
import com.rcmiku.ncmapi.ncbl.NcblDeviceInfo
import com.rcmiku.ncmapi.ncbl.NeteaseClientLogClient
import com.rcmiku.ncmapi.utils.CookieProvider

/**
 * 播放历史云端上报（2.0.5，C2）：把安卓 PlaybackService 里的同套接线搬到
 * 桌面引擎——PlaybackHistorySession 计时（只算真正在播的时长）+
 * PlaybackHistoryReporter 双通道上报（eapi weblog + NCBL clientlogsf）。
 * 此前桌面从不实例化 reporter，桌面播的歌不进网易云听歌记录/年度报告/
 * 云端最近播放。
 *
 * 与安卓一致的降级语义：未登录时 begin 直接跳过；reporter 内部任何异常
 * 都被它自己的串行队列吞掉打日志，绝不影响播放主流程。NCBL 设备信息用
 * 桌面值（cookie 里的 deviceId 优先，缺失时用 DeviceIdentity 落盘值）。
 */
object DesktopPlaybackHistory {

    @Volatile
    private var attached = false

    fun attach(player: DesktopPlayer, context: Context) {
        if (attached) return
        attached = true
        val reporter = PlaybackHistoryReporter(
            ncblClient = NeteaseClientLogClient(),
            ncblDevice = NcblDeviceInfo(
                deviceId = deviceId(),
                osVersion = System.getProperty("os.version").orEmpty(),
                model = "Windows PC",
                brand = "",
                processName = "PenguinMusic",
                buildType = BuildConfig.BUILD_TYPE,
                pid = ProcessHandle.current().pid().toInt(),
                buildId = "",
            ),
            ncblDeviceId = { deviceId() },
        )
        val session = PlaybackHistorySession()

        fun reportDuration(completed: PlaybackHistorySession.CompletedSession) {
            reporter.end(completed, endedAtMs = System.currentTimeMillis())
        }

        fun startPlaybackHistory(mediaItem: MediaItem, startedAtMs: Long) {
            if (!CookieProvider.isLoggedIn()) return
            val source = mediaItem.playbackHistorySourceContext() ?: return
            val durationMs = mediaItem.mediaMetadata.extras
                ?.getLong(MediaSessionConstants.EXTRA_DURATION_MS, 0L)
                ?.coerceAtLeast(0L) ?: 0L
            reporter.begin(
                mediaId = mediaItem.mediaId,
                songId = source.songId,
                sourceId = source.sourceId,
                source = source.source,
                startedAtMs = startedAtMs,
                songName = mediaItem.mediaMetadata.title?.toString().orEmpty(),
                songArtist = mediaItem.mediaMetadata.artist?.toString().orEmpty(),
                songDurationMs = durationMs,
            )
        }

        fun onStateChange() {
            val mediaItem = player.currentMediaItem
            val update = session.update(
                mediaId = mediaItem?.mediaId,
                isPlaying = player.isPlaying,
                wallClockMs = System.currentTimeMillis(),
                realtimeMs = SystemClock.elapsedRealtime(),
            )
            update.completed?.let { reportDuration(it) }
            if (update.startedAtMs != null && mediaItem != null) {
                startPlaybackHistory(mediaItem, update.startedAtMs)
            }
        }

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                session.onMediaItemTransition(
                    mediaId = mediaItem?.mediaId,
                    reason = reason,
                    realtimeMs = SystemClock.elapsedRealtime(),
                )?.let { reportDuration(it) }
                onStateChange()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onStateChange()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> session
                        .finish(SystemClock.elapsedRealtime(), "playend")
                        ?.let { reportDuration(it) }
                    Player.STATE_IDLE -> session
                        .finish(SystemClock.elapsedRealtime())
                        ?.let { reportDuration(it) }
                }
                onStateChange()
            }
        })
        println("[history] desktop playback history reporter attached")
    }

    private fun deviceId(): String =
        CookieProvider.getCookieMap()["deviceId"]?.takeIf { it.isNotBlank() }
            ?: runCatching { DeviceIdentity.deviceId }.getOrDefault("")
}
