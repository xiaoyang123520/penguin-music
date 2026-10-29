package com.jussicodes.music.playback.audio

/**
 * The AudioEffectMode enum lives in playback/audio/AudioProcessors.kt in the
 * Android build, next to the media3 audio processors that have no desktop
 * equivalent. The enum itself is pure data (the settings UI persists it), so
 * it is provided here identically.
 *
 * 隔离说明（2.1.3，A4-05）：本枚举与安卓遗留 DataStore 键
 * （audioEffectMode / audioEffectEightDEnabled / audioEffectReverbEnabled /
 * audioEffectIntensity / audioEffectSpeed）**桌面引擎一律不读不写**——
 * 编进发货包的安卓音效页（PlayerMenuBottomSheet）在桌面不可达，不要把
 * 它接活，调了也不会出声。桌面音效的唯一权威实现与设置源是
 * [EqSettings]（EQ 十段 + 8D 环绕 + 混响，键名 eq* 前缀）+ EqProcessor /
 * PcmEffects / ReverbProcessor 三个 PCM 处理器。本枚举仅为编译兼容
 * 保留，新代码不要引用它做任何音效决策。
 */
enum class AudioEffectMode {
    OFF,
    EIGHT_D,
    REVERB
}
