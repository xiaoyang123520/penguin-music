package com.jussicodes.music.playback.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * PCM 层输出特效（2.0.5 新增）：淡入淡出 + 8D 环绕，与 EQ 并列挂在
 * DesktopPlayer 两条解码管线（MP3 decodeLoop / FLAC playPcmLoop）的
 * 「EQ 之后、写线缆之前」插入点，不改解码与取链逻辑。
 *
 * 淡入淡出：增益状态机——beginTrack 时增益从 0 线性爬升到 1（起播淡入，
 * 约 [FADE_MS] ms）；fadeOut 把目标置 0，解码循环据 [gain] 降到 ~0 后才
 * 真正 stop 线缆并泊车（暂停淡出）；恢复时 fadeIn 爬回。切歌/停止由
 * DesktopPlayer 在关旧线缆前先 fadeOut 并短等增益落下，实现切歌淡出。
 *
 * 8D 环绕：等功率声像 LFO（约 0.12 Hz 整圈旋转），开关在 EqSettings
 * （设置页 EQ 对话框），仅立体声生效。
 */
class PcmEffects {

    @Volatile
    var gain: Float = 1f
        private set

    @Volatile
    private var target: Float = 1f

    private var phase: Double = 0.0

    fun beginTrack() {
        gain = 0f
        target = 1f
    }

    fun fadeIn() {
        target = 1f
    }

    fun fadeOut() {
        target = 0f
    }

    /** 原地处理交错 PCM：先过淡入淡出增益斜坡。增益恒为 1 时零开销直通。 */
    fun process(buffer: ShortArray, length: Int, channelCount: Int, sampleRate: Int) {
        if (gain == 1f && target == 1f) return
        val ch = maxOf(1, channelCount)
        val step = 1f / (FADE_MS / 1000f * maxOf(1, sampleRate))
        var g = gain
        var i = 0
        while (i < length) {
            if (g != target) {
                g = if (g < target) minOf(target, g + step) else maxOf(target, g - step)
            }
            val frameEnd = minOf(length, i + ch)
            for (j in i until frameEnd) {
                buffer[j] = (buffer[j] * g).toInt().toShort()
            }
            i += ch
            if (g == target && target == 1f) break // 后段满增益，无需再乘
        }
        gain = g
    }

    /** 8D 环绕：声像随 LFO 在左右间旋转（等功率声像 + 增益补偿，钳位防削波）。 */
    fun processSurround(buffer: ShortArray, length: Int, channelCount: Int, sampleRate: Int) {
        if (!EqSettings.surround8d || channelCount < 2) return
        val frames = length / channelCount
        var ph = phase
        val dPhase = 2.0 * PI * LFO_HZ / maxOf(1, sampleRate)
        var i = 0
        for (f in 0 until frames) {
            val pan = sin(ph) // -1..1
            val angle = (pan + 1.0) * PI / 4.0 // 0..π/2
            val lGain = cos(angle) * BOOST
            val rGain = sin(angle) * BOOST
            val li = i
            val ri = i + 1
            buffer[li] = (buffer[li] * lGain).toInt().coerceIn(-32768, 32767).toShort()
            buffer[ri] = (buffer[ri] * rGain).toInt().coerceIn(-32768, 32767).toShort()
            i += channelCount
            ph += dPhase
            if (ph > 2.0 * PI) ph -= 2.0 * PI
        }
        phase = ph
    }

    companion object {
        const val FADE_MS = 260f
        private const val LFO_HZ = 0.12
        private const val BOOST = 1.25
    }
}
