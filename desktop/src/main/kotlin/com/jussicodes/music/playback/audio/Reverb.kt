package com.jussicodes.music.playback.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * 混响（2.1.3，A4-05）：安卓 ReverbAudioProcessor（app
 * playback/audio/AudioProcessors.kt，media3 BaseAudioProcessor 形态）
 * 的 DSP 核心移植——Schroeder 混响：每声道 8 个阻尼梳状滤波并联 +
 * 4 个全通滤波串联扩散，wet/dry/roomSize/damping 由强度（0..1）驱动，
 * 参数映射与安卓端逐项一致（wet 0.08..0.32、dry 0.92..0.80、
 * roomSize 0.68..0.84、damping 0.34..0.68）。
 *
 * 桌面形态：与 [EqProcessor] 同插入点（DesktopPlayer 三条 PCM 管线
 * 「EQ 之后、写线缆之前」原地处理 ShortArray），开关/强度读 [EqSettings]
 * 的 reverbEnabled / reverbIntensity（与安卓遗留键 audioEffect* 完全
 * 隔离，见 AudioEffectMode.kt 的隔离说明）。未启用时立即返回，零开销。
 */
class ReverbProcessor {

    private var channels: Array<ReverbChannel> = emptyArray()
    private var configuredRate = 0
    private var configuredChannels = 0
    private var configuredIntensity = -1f

    private var wet = 0.22f
    private var dry = 0.82f
    private var roomSize = 0.76f
    private var damping = 0.42f

    /** 原地处理交错 PCM。未启用时立即返回（零开销）。 */
    fun process(buffer: ShortArray, length: Int, channelCount: Int, sampleRate: Int) {
        if (!EqSettings.reverbEnabled) return
        val chs = channelCount.coerceIn(1, 2)
        val intensity = EqSettings.reverbIntensity
        if (sampleRate != configuredRate || chs != configuredChannels) {
            configure(sampleRate, chs)
        }
        if (intensity != configuredIntensity) {
            applyAmount(intensity)
        }
        var ch = 0
        var i = 0
        while (i < length) {
            val inputSample = buffer[i].toFloat() / Short.MAX_VALUE
            val reverbOut = channels.getOrNull(ch)?.process(inputSample * INPUT_GAIN)
                ?: inputSample
            val out = inputSample * dry + reverbOut * wet
            buffer[i] = floatToPcm16(out)
            ch = (ch + 1) % chs
            i++
        }
    }

    private fun configure(sampleRate: Int, channelCount: Int) {
        configuredRate = sampleRate
        configuredChannels = channelCount
        val scale = sampleRate / 44100f
        channels = Array(channelCount) { channel ->
            ReverbChannel(
                sampleRate = sampleRate,
                combSizes = COMB_DELAYS.map { delay ->
                    ((delay + channel * STEREO_SPREAD) * scale).roundToInt().coerceAtLeast(1)
                },
                allPassSizes = ALL_PASS_DELAYS.map { delay ->
                    ((delay + channel * STEREO_SPREAD) * scale).roundToInt().coerceAtLeast(1)
                }
            )
        }
        configuredIntensity = -1f // 强制下一帧按当前强度重算参数
        println("[Reverb] configure sr=$sampleRate ch=$channelCount")
    }

    /** 强度→参数映射，与安卓 ReverbAudioProcessor.setAmount 逐项一致。 */
    private fun applyAmount(normalizedIntensity: Float) {
        val intensity = normalizedIntensity.coerceIn(0f, 1f)
        configuredIntensity = EqSettings.reverbIntensity
        wet = (0.08f + intensity * 0.24f).coerceIn(0.08f, 0.32f)
        dry = (0.92f - intensity * 0.12f).coerceIn(0.80f, 0.92f)
        roomSize = (0.68f + intensity * 0.16f).coerceIn(0.68f, 0.84f)
        damping = (0.34f + intensity * 0.34f).coerceIn(0.34f, 0.68f)
        channels.forEach { it.setFeedback(roomSize, damping) }
        println("[Reverb] amount intensity=$intensity wet=$wet dry=$dry room=$roomSize damping=$damping")
    }

    private fun floatToPcm16(sample: Float): Short {
        val limited = softClip(sample)
        return (limited * Short.MAX_VALUE)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }

    private fun softClip(sample: Float): Float {
        val x = sample.coerceIn(-SOFT_CLIP_LIMIT, SOFT_CLIP_LIMIT)
        return x * (1.5f - 0.5f * x * x)
    }

    private class ReverbChannel(
        sampleRate: Int,
        combSizes: List<Int>,
        allPassSizes: List<Int>
    ) {
        private val combFilters = combSizes.map(::CombFilter).toTypedArray()
        private val allPassFilters = allPassSizes.map(::AllPassFilter).toTypedArray()
        private val inputLowCut = OnePoleHighPass(sampleRate, WET_LOW_CUT_HZ)
        private val outputLowCut = OnePoleHighPass(sampleRate, WET_LOW_CUT_HZ)

        fun setFeedback(feedback: Float, damping: Float) {
            combFilters.forEach { it.setFeedback(feedback, damping) }
        }

        fun process(input: Float): Float {
            val filteredInput = inputLowCut.process(input)
            val combSum = combFilters.sumOf { it.process(filteredInput).toDouble() }.toFloat()
            val combOut = combSum * COMB_GAIN
            val diffused = allPassFilters.fold(combOut) { sample, filter -> filter.process(sample) }
            return outputLowCut.process(diffused)
        }
    }

    private class OnePoleHighPass(sampleRate: Int, cutoffHz: Float) {
        private val coefficient = 1f - exp(-TWO_PI_FLOAT * cutoffHz / sampleRate.coerceAtLeast(1)).toFloat()
        private var low = 0f

        fun process(input: Float): Float {
            low += coefficient * (input - low)
            return input - low
        }
    }

    private class CombFilter(size: Int) {
        private val buffer = FloatArray(size)
        private var index = 0
        private var feedback = 0.78f
        private var damping = 0.35f
        private var dampedSample = 0f

        fun setFeedback(feedback: Float, damping: Float) {
            this.feedback = feedback
            this.damping = damping
        }

        fun process(input: Float): Float {
            val output = buffer[index]
            dampedSample = output * (1f - damping) + dampedSample * damping
            buffer[index] = (input + dampedSample * feedback).sanitize()
            index = (index + 1) % buffer.size
            return output
        }
    }

    private class AllPassFilter(size: Int) {
        private val buffer = FloatArray(size)
        private var index = 0
        private val feedback = 0.5f

        fun process(input: Float): Float {
            val buffered = buffer[index]
            val output = -input + buffered
            buffer[index] = (input + buffered * feedback).sanitize()
            index = (index + 1) % buffer.size
            return output
        }
    }

    private companion object {
        val COMB_DELAYS = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        val ALL_PASS_DELAYS = intArrayOf(556, 441, 341, 225)
        const val STEREO_SPREAD = 23
        const val INPUT_GAIN = 0.62f
        const val COMB_GAIN = 0.125f
        const val SOFT_CLIP_LIMIT = 1f
        const val WET_LOW_CUT_HZ = 140f
        val TWO_PI_FLOAT = (2f * PI).toFloat()

        fun Float.sanitize(): Float = if (this.isFinite()) this.coerceIn(-4f, 4f) else 0f
    }
}
