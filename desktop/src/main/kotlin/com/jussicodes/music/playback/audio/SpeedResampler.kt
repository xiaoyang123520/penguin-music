package com.jussicodes.music.playback.audio

/**
 * 变速重采样（2.2.0，L5）：简单线性插值重采样实现 0.5×–2.0× 倍速。
 *
 * 语义：第 n 个输出帧取输入位置 n*speed 处的线性插值。speed>1 输出帧
 * 变少（快放）、speed<1 变多（慢放）。代价（已在 PORTING 注明）：简单
 * 重采样会改变音调——0.5× 声音低沉、2× 呈「花栗鼠」音色；不变调的
 * 时间拉伸（OLA/相位声码器）不在本版范围。
 *
 * 跨调用状态：插值需要「下一个输入帧」，每块末尾未消费的输入帧
 * （从 floor(phase) 起）连同小数相位结转到下一块，保证块边界连续、
 * 无咔哒声。speed==1f 时直通返回原数组（零拷贝零开销），与未启用
 * 时的 PCM 链完全一致。
 *
 * 注意：最后一帧在流结束时可能因缺少后继帧被留在 pending（最多 1
 * 帧 ≈26ms），beginTrack/reset 时丢弃，无感知影响。
 *
 * 非线程安全：只在解码线程内使用（与 EqProcessor 等同约束）。
 */
class SpeedResampler {

    private var pending = ShortArray(0)
    private var pendingFrames = 0
    private var phase = 0.0

    fun reset() {
        pending = ShortArray(0)
        pendingFrames = 0
        phase = 0.0
    }

    /**
     * @param input 交错 16-bit PCM（长度 [length] 个采样，即 length/channels 帧）
     * @return 输出采样数组 to 输出长度（采样数）。speed==1 时返回 (input, length)。
     */
    fun process(
        input: ShortArray,
        length: Int,
        channels: Int,
        speed: Float
    ): Pair<ShortArray, Int> {
        if (speed == 1f || channels <= 0 || length <= 0) return input to length
        val inFrames = length / channels
        val totalFrames = pendingFrames + inFrames
        if (totalFrames < 2) {
            // 帧太少不足以插值：整块结转，等下一块拼起来再处理。
            val np = ShortArray(totalFrames * channels)
            System.arraycopy(pending, 0, np, 0, pendingFrames * channels)
            System.arraycopy(input, 0, np, pendingFrames * channels, inFrames * channels)
            pending = np
            pendingFrames = totalFrames
            return EMPTY to 0
        }
        val work: ShortArray = if (pendingFrames > 0) {
            val w = ShortArray(totalFrames * channels)
            System.arraycopy(pending, 0, w, 0, pendingFrames * channels)
            System.arraycopy(input, 0, w, pendingFrames * channels, inFrames * channels)
            w
        } else {
            input
        }
        val maxOut = (totalFrames / speed).toInt() + 2
        val out = ShortArray(maxOut * channels)
        var outFrames = 0
        var p = phase
        val lastIdx = totalFrames - 1
        while (true) {
            val i0 = p.toInt()
            if (i0 >= lastIdx) break
            val frac = (p - i0).toFloat()
            val b0 = i0 * channels
            val b1 = (i0 + 1) * channels
            val ob = outFrames * channels
            for (ch in 0 until channels) {
                val a = work[b0 + ch].toFloat()
                val b = work[b1 + ch].toFloat()
                out[ob + ch] = (a + (b - a) * frac).toInt().toShort()
            }
            outFrames++
            p += speed
        }
        val consumed = minOf(p.toInt(), totalFrames)
        val leftFrames = totalFrames - consumed
        pending = if (leftFrames > 0) {
            val np = ShortArray(leftFrames * channels)
            System.arraycopy(work, consumed * channels, np, 0, leftFrames * channels)
            np
        } else {
            ShortArray(0)
        }
        pendingFrames = leftFrames
        phase = p - consumed
        return out to outFrames * channels
    }

    private companion object {
        val EMPTY = ShortArray(0)
    }
}
