package com.jussicodes.music.playback.audio

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jussicodes.music.utils.dataStore
import kotlinx.coroutines.flow.first
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 10 段参数均衡器（2.0.2 新增，对标网易云/QQ 的均衡器入口）。
 *
 * 管线位置：DesktopPlayer.decodeLoop 里 JLayer 解出的 PCM shorts 在转字节
 * 进 SourceDataLine 之前原地过一遍本处理器——解码与输出之间唯一的 PCM
 * 插入点。RBJ Audio EQ Cookbook 系数：band0 低架、band9 高架、其余钟形
 * (Q=1.0)。增益变化时按当前采样率重算系数（configure 打一行 [EQ] 日志，
 * 探针据此验收），播放不中断。
 *
 * 2.1.3（A4-05）：本对象同为桌面音效的**唯一权威设置源**——EQ、8D 环绕、
 * 混响（reverbEnabled/reverbIntensity，DSP 见 ReverbProcessor）都在这里。
 * 安卓遗留键（audioEffectMode / audioEffectEightDEnabled /
 * audioEffectReverbEnabled / audioEffectIntensity / audioEffectSpeed）
 * 桌面引擎从不读、永不写，与此隔离（见 AudioEffectMode.kt 隔离说明），
 * 不要把新音效接到那些键上。
 */
object EqSettings {

    val BAND_FREQS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    const val PRESET_OFF = "关闭"
    val PRESETS: List<Pair<String, FloatArray>> = listOf(
        PRESET_OFF to FloatArray(10),
        "流行" to floatArrayOf(-1f, 0f, 1f, 2f, 4f, 4f, 2f, 1f, 0f, -1f),
        "摇滚" to floatArrayOf(4f, 3f, 2f, 0f, -1f, 0f, 2f, 3f, 4f, 4f),
        "古典" to floatArrayOf(4f, 3f, 2f, 1f, 0f, 0f, -1f, -2f, -3f, -3f),
        "爵士" to floatArrayOf(3f, 2f, 1f, 1f, 0f, 1f, 2f, 2f, 3f, 3f),
        "人声" to floatArrayOf(-3f, -2f, -1f, 1f, 3f, 4f, 3f, 1f, -1f, -2f),
        "低音增强" to floatArrayOf(6f, 5f, 4f, 3f, 1f, 0f, 0f, 0f, 0f, 0f),
        "高音增强" to floatArrayOf(0f, 0f, 0f, 0f, 1f, 3f, 4f, 5f, 6f, 6f),
        "电子" to floatArrayOf(5f, 4f, 2f, 0f, -1f, 1f, 3f, 4f, 5f, 5f)
    )

    @Volatile var enabled: Boolean = false
    @Volatile var preset: String = PRESET_OFF
    @Volatile var gains: FloatArray = FloatArray(10)

    /** 8D 环绕开关（2.0.5）：PCM 层声像 LFO，与 EQ 独立，见 PcmEffects。 */
    @Volatile var surround8d: Boolean = false

    /** 混响开关（2.1.3，A4-05）：Schroeder 混响，见 ReverbProcessor。 */
    @Volatile var reverbEnabled: Boolean = false

    /** 混响强度 0..1（2.1.3，A4-05）：驱动 wet/dry/roomSize/damping。 */
    @Volatile var reverbIntensity: Float = 0.5f

    /** 每次增益/开关变化自增，EqProcessor 据此判断是否需要重算系数。 */
    @Volatile var revision: Int = 0
        private set

    /** 混响参数变化自增，ReverbProcessor 据此重算 wet/dry 等参数。 */
    @Volatile var reverbRevision: Int = 0
        private set

    private val enabledKey = booleanPreferencesKey("eqEnabled")
    private val presetKey = stringPreferencesKey("eqPreset")
    private val gainsKey = stringPreferencesKey("eqGains")
    private val surroundKey = booleanPreferencesKey("eqSurround8d")
    private val reverbEnabledKey = booleanPreferencesKey("eqReverbEnabled")
    private val reverbIntensityKey = floatPreferencesKey("eqReverbIntensity")

    fun setSurround(on: Boolean) {
        surround8d = on
        revision++
        println("[EQ] surround8d=$on")
    }

    fun setReverb(on: Boolean) {
        reverbEnabled = on
        reverbRevision++
        println("[EQ] reverbEnabled=$on")
    }

    /** 更新混响强度（函数名避开属性 setter 的 JVM 签名冲突）。 */
    fun updateReverbIntensity(value: Float) {
        reverbIntensity = value.coerceIn(0f, 1f)
        reverbRevision++
    }

    fun apply(enabled: Boolean, preset: String, gains: FloatArray) {
        applyInternal(enabled, preset, gains, quiet = false)
    }

    /**
     * 实时预览（2.1.3，A4-06）：滑杆拖动中每 tick 调用——只更新内存值与
     * revision 让引擎即时跟随，不打日志；落盘与正式 apply 由松手时的
     * [apply] 完成，避免拖动期日志刷屏与写盘抖动。
     */
    fun previewApply(enabled: Boolean, preset: String, gains: FloatArray) {
        applyInternal(enabled, preset, gains, quiet = true)
    }

    private fun applyInternal(enabled: Boolean, preset: String, gains: FloatArray, quiet: Boolean) {
        this.enabled = enabled
        this.preset = preset
        this.gains = gains.copyOf(10)
        revision++
        if (!quiet) {
            println("[EQ] apply enabled=$enabled preset=$preset gains=${gains.joinToString(",") { it.toString() }}")
        }
    }

    fun applyPreset(name: String) {
        val gains = PRESETS.firstOrNull { it.first == name }?.second ?: FloatArray(10)
        // 选「关闭」即关 EQ；选任何实际预设即开 EQ（与三家播放器一致的手感）。
        apply(enabled = name != PRESET_OFF, preset = name, gains = gains)
    }

    suspend fun load(context: Context) {
        runCatching {
            val prefs = context.dataStore.data.first()
            val savedGains = prefs[gainsKey]?.split(',')?.mapNotNull { it.toFloatOrNull() }
            enabled = prefs[enabledKey] ?: false
            surround8d = prefs[surroundKey] ?: false
            reverbEnabled = prefs[reverbEnabledKey] ?: false
            // 2.2.0（A5-08）：读档消毒——gains 字符串手改/旧档损坏可混入
            // NaN/Infinity（toFloatOrNull 对 "NaN" 是返回 NaN 而非 null）
            // 或极端值，NaN 进 biquad 系数会污染整段输出且无法自愈。
            // 逐值校验：非有限回 0、有限钳制 ±12dB；混响强度同理钳 0..1。
            reverbIntensity = (prefs[reverbIntensityKey] ?: 0.5f).let {
                if (it.isFinite()) it.coerceIn(0f, 1f) else 0.5f
            }
            reverbRevision++
            preset = prefs[presetKey] ?: PRESET_OFF
            gains = if (savedGains != null && savedGains.size == 10) {
                FloatArray(10) { sanitizeGain(savedGains[it]) }
            } else {
                PRESETS.firstOrNull { it.first == preset }?.second?.copyOf() ?: FloatArray(10)
            }
            revision++
            println("[EQ] loaded enabled=$enabled preset=$preset")
        }
    }

    /** EQ 增益消毒（2.2.0，A5-08）：非有限值回 0，有限值钳制 ±12dB。 */
    private fun sanitizeGain(value: Float): Float =
        if (value.isFinite()) value.coerceIn(-12f, 12f) else 0f

    /**
     * 恢复默认（2.2.0，L3）：删全部 EQ 落盘键，并把内存态同步回默认
     * （关 EQ / 预设关 / 增益全 0 / 8D 关 / 混响关 / 强度 0.5），
     * 让「恢复默认」对正在播的音频也即时生效，不必等重启。
     */
    suspend fun resetToDefaults(context: Context) {
        runCatching {
            context.dataStore.edit { prefs ->
                prefs.remove(enabledKey)
                prefs.remove(presetKey)
                prefs.remove(gainsKey)
                prefs.remove(surroundKey)
                prefs.remove(reverbEnabledKey)
                prefs.remove(reverbIntensityKey)
            }
        }
        enabled = false
        preset = PRESET_OFF
        gains = FloatArray(10)
        revision++
        surround8d = false
        reverbEnabled = false
        reverbIntensity = 0.5f
        reverbRevision++
        println("[EQ] reset to defaults")
    }

    suspend fun save(context: Context) {
        runCatching {
            context.dataStore.edit { prefs ->
                prefs[enabledKey] = enabled
                prefs[presetKey] = preset
                prefs[gainsKey] = gains.joinToString(",")
                prefs[surroundKey] = surround8d
                prefs[reverbEnabledKey] = reverbEnabled
                prefs[reverbIntensityKey] = reverbIntensity
            }
        }
    }
}

/** 单个 biquad 段，双声道各自的状态（Direct Form I）。 */
private class Biquad {
    var b0 = 1.0; var b1 = 0.0; var b2 = 0.0; var a1 = 0.0; var a2 = 0.0
    private val x1 = DoubleArray(2); private val x2 = DoubleArray(2)
    private val y1 = DoubleArray(2); private val y2 = DoubleArray(2)

    fun reset() {
        x1.fill(0.0); x2.fill(0.0); y1.fill(0.0); y2.fill(0.0)
    }

    fun process(x: Double, ch: Int): Double {
        val y = b0 * x + b1 * x1[ch] + b2 * x2[ch] - a1 * y1[ch] - a2 * y2[ch]
        x2[ch] = x1[ch]; x1[ch] = x
        y2[ch] = y1[ch]; y1[ch] = y
        return y
    }
}

class EqProcessor {

    private val bands = Array(10) { Biquad() }
    private var configuredRate = 0
    private var configuredChannels = 0
    private var configuredRevision = -1

    /** 原地处理交错 PCM。未启用时立即返回（零开销）。 */
    fun process(buffer: ShortArray, length: Int, channelCount: Int, sampleRate: Int) {
        if (!EqSettings.enabled) return
        if (sampleRate != configuredRate || channelCount != configuredChannels ||
            EqSettings.revision != configuredRevision
        ) {
            configure(sampleRate, channelCount, EqSettings.gains)
        }
        val chs = minOf(channelCount, 2)
        for (ch in 0 until chs) {
            var i = ch
            while (i < length) {
                var x = buffer[i].toDouble() / 32768.0
                for (b in 0 until 10) x = bands[b].process(x, ch)
                buffer[i] = (x.coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort()
                i += channelCount
            }
        }
    }

    private fun configure(sampleRate: Int, channelCount: Int, gains: FloatArray) {
        val structureChanged = sampleRate != configuredRate || channelCount != configuredChannels
        configuredRate = sampleRate
        configuredChannels = channelCount
        configuredRevision = EqSettings.revision
        for (band in 0 until 10) {
            val freq = EqSettings.BAND_FREQS[band].toDouble()
            val gainDb = gains.getOrElse(band) { 0f }.toDouble()
            val f = bands[band]
            when {
                gainDb == 0.0 -> { // 直通段
                    f.b0 = 1.0; f.b1 = 0.0; f.b2 = 0.0; f.a1 = 0.0; f.a2 = 0.0
                }
                band == 0 -> shelf(f, freq, gainDb, sampleRate, low = true)
                band == 9 -> shelf(f, freq, gainDb, sampleRate, low = false)
                else -> peaking(f, freq, gainDb, sampleRate)
            }
            // 2.1.3（A4-06）：只有采样率/声道数变化（换流）才清滤波历史；
            // 播放中拖滑杆改增益只换系数、保留状态——旧实现每 tick 全段
            // reset，滤波响应被反复清零，听感咔哒、且每 ~93ms 缓冲重配一次。
            if (structureChanged) f.reset()
        }
        println(
            "[EQ] configure sr=$sampleRate ch=$channelCount gains=" +
                gains.joinToString(",") { it.toString() } +
                " b0=" + bands.joinToString(",") { "%.4f".format(it.b0) } +
                if (structureChanged) "" else " (coeff-only)"
        )
    }

    private fun peaking(f: Biquad, freq: Double, gainDb: Double, sampleRate: Int) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * freq / sampleRate
        val alpha = sin(w0) / (2.0 * 1.0) // Q = 1.0
        val cosW = cos(w0)
        val b0 = 1.0 + alpha * a
        val b1 = -2.0 * cosW
        val b2 = 1.0 - alpha * a
        val a0 = 1.0 + alpha / a
        val a1 = -2.0 * cosW
        val a2 = 1.0 - alpha / a
        f.b0 = b0 / a0; f.b1 = b1 / a0; f.b2 = b2 / a0; f.a1 = a1 / a0; f.a2 = a2 / a0
    }

    private fun shelf(f: Biquad, freq: Double, gainDb: Double, sampleRate: Int, low: Boolean) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * freq / sampleRate
        val cosW = cos(w0)
        val alpha = sin(w0) / 2.0 * sqrt((ln2() / 2.0) * 2.0) // S = 1 近似
        val sq = 2.0 * sqrt(a) * alpha
        val b0: Double; val b1: Double; val b2: Double
        val a0: Double; val a1: Double; val a2: Double
        if (low) {
            b0 = a * ((a + 1) - (a - 1) * cosW + sq)
            b1 = 2 * a * ((a - 1) - (a + 1) * cosW)
            b2 = a * ((a + 1) - (a - 1) * cosW - sq)
            a0 = (a + 1) + (a - 1) * cosW + sq
            a1 = -2 * ((a - 1) + (a + 1) * cosW)
            a2 = (a + 1) + (a - 1) * cosW - sq
        } else {
            b0 = a * ((a + 1) + (a - 1) * cosW + sq)
            b1 = -2 * a * ((a - 1) + (a + 1) * cosW)
            b2 = a * ((a + 1) + (a - 1) * cosW - sq)
            a0 = (a + 1) - (a - 1) * cosW + sq
            a1 = 2 * ((a - 1) - (a + 1) * cosW)
            a2 = (a + 1) - (a - 1) * cosW - sq
        }
        f.b0 = b0 / a0; f.b1 = b1 / a0; f.b2 = b2 / a0; f.a1 = a1 / a0; f.a2 = a2 / a0
    }

    private fun ln2() = 0.6931471805599453
}
