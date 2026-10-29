package com.jussicodes.music

/**
 * Desktop stand-in for the AGP-generated BuildConfig.
 *
 * 2.0.5 起 VERSION_NAME 是**桌面版自己的版本**（企鹅音乐版本线），全端
 * 统一引用（设置页关于、更新检查、UA 标识）；上游 jussichords 版本是
 * 2.4.5，只作来源标注，不再冒充本产品版本——2.0.4 及以前更新检查拿
 * 上游 GitHub releases 与 2.4.5 比对，盯错产品（A7），根源即在此。
 */
object BuildConfig {
    const val APPLICATION_ID: String = "com.jussicodes.music"
    const val BUILD_TYPE: String = "release"
    const val DEBUG: Boolean = false
    const val VERSION_CODE: Int = 51
    const val VERSION_NAME: String = "2.2.0"
    /** 上游开源项目版本（来源标注用）。 */
    const val UPSTREAM_VERSION_NAME: String = "2.4.5"
}
