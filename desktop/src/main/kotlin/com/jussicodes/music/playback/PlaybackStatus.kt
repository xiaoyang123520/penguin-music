package com.jussicodes.music.playback

import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播放状态可观测性（2.1.3，A4-03）：
 *
 * 1. [PlaybackStatus] 常驻错误条：引擎错误停留（不跳歌、停在当前曲）时
 *    只有 4 秒 Toast，用户切走窗口就错过、回来只面对「停了且按播放没
 *    反应」的死界面（audit4 B 案实证）。改为除 Toast 外再置一条常驻
 *    错误状态，由 PlayBar 渲染成错误条（原因 + 重试 / 跳下一首 / 关闭），
 *    直到下一次会话启动（重试/切歌/seek 都会走 startCurrentLocked 清除）
 *    或用户手动关闭才消失。
 *
 * 2. [PauseDiagnostics] 暂停原因落盘：每次「播放→不播」的状态变化都把
 *    原因写进 <dataDir>/logs/pauses.log 并同步 println（沙箱/日志可查）。
 *    引擎内部路径（错误停留、熔断、EOF）直接记录；外部调用方（睡眠定时、
 *    媒体键、打开 MV、一起听同步）在 pause() 前先 [PauseDiagnostics.note]
 *    声明原因，playWhenReady setter 转 false 时优先消费该声明，否则记
 *    USER（用户/界面手动暂停）。
 */
object PlaybackStatus {

    data class StickyError(
        val title: String,
        val detail: String?,
        val reason: String,
        val atMs: Long
    )

    val stickyError = MutableStateFlow<StickyError?>(null)

    fun show(title: String, detail: String?, reason: String) {
        stickyError.value = StickyError(
            title = title,
            detail = detail?.takeIf { it.isNotBlank() }?.take(160),
            reason = reason,
            atMs = System.currentTimeMillis()
        )
        println("[StickyError] $title detail=${detail?.take(120)} reason=$reason")
    }

    fun clear() {
        if (stickyError.value != null) {
            stickyError.value = null
            println("[StickyError] cleared")
        }
    }
}

object PauseDiagnostics {

    const val USER = "USER"                 // 用户/界面手动暂停
    const val TIMER = "TIMER"               // 睡眠定时到期
    const val MEDIA_KEY = "MEDIA_KEY"       // 系统媒体键（STOP/PLAY_PAUSE）
    const val OPEN_MV = "OPEN_MV"           // 打开 MV 自动暂停主播放
    const val LISTEN_TOGETHER = "LISTEN_TOGETHER" // 一起听远端同步暂停
    const val DEVICE_ERROR = "DEVICE_ERROR" // 音频输出设备异常
    const val NETWORK_ERROR = "NETWORK_ERROR" // 网络中断/取流失败
    const val RESOLVE_ERROR = "RESOLVE_ERROR" // 取链失败
    const val DECODE_ERROR = "DECODE_ERROR" // 解码失败（含解码期 OOM）
    const val EOF_PREMATURE = "EOF_PREMATURE" // 流提前 EOF 被当自然结束
    const val TRACK_END = "TRACK_END"       // 自然播完（队列尾）
    const val TAIL_RESUME_OK = "TAIL_RESUME_OK" // 2.2.0 A5-17：尾部断流续播成功、尾巴补齐
    const val TAIL_RESUME_FALLBACK = "TAIL_RESUME_FALLBACK" // 2.2.0 A5-17：尾部续播失败回落按播完处理
    const val BREAKER = "BREAKER"           // 连续失败熔断停播

    /** 最近一次暂停原因（内存镜像，日志文件是权威记录）。 */
    @Volatile var lastReason: String = ""
        private set
    @Volatile var lastDetail: String = ""
        private set
    @Volatile var lastAtMs: Long = 0L
        private set

    /** 外部调用方在 pause() 前声明的原因（带时间戳，过期不认）。 */
    @Volatile private var pendingReason: String? = null
    @Volatile private var pendingAtMs: Long = 0L

    /** 声明下一次暂停的原因（睡眠定时/媒体键/开 MV/一起听在 pause 前调用）。 */
    fun note(reason: String) {
        pendingReason = reason
        pendingAtMs = System.currentTimeMillis()
    }

    /** 消费声明：3 秒内的声明有效，消费后清空。 */
    fun consumePending(): String? {
        val r = pendingReason ?: return null
        val at = pendingAtMs
        pendingReason = null
        return if (System.currentTimeMillis() - at < 3_000) r else null
    }

    /** 记录一次暂停/停播：更新内存镜像 + 追加日志文件 + 控制台一行。 */
    fun record(reason: String, detail: String = "") {
        lastReason = reason
        lastDetail = detail
        lastAtMs = System.currentTimeMillis()
        val line = "${timestamp()} reason=$reason detail=${detail.take(200)}"
        println("[PauseDiag] $line")
        // 2.2.0（A5-10）：改走 DesktopLog 的 1MB 轮转通道（路径口径
        // 不变：<dataDir>/logs/pauses.log）。旧实现直接 appendText
        // 无上限，长时间运行日志无限增长。
        runCatching { com.jussicodes.music.desktop.DesktopLog.pauses(line) }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
