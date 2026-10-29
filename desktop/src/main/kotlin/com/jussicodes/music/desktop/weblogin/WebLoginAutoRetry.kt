package com.jussicodes.music.desktop.weblogin

/**
 * 网页登录自动重报上限（2.3.0，A6-01）。
 *
 * 背景：2.2.0（A5-11）让校验失败时调 WebLoginBrowser.resetCaptureDedup()，
 * 2.5s 轮询随即把页面里同一个 MUSIC_U 再报一轮 → 再校验 → 再失败……
 * 只要登录页开着就无限循环（持续性原因、尤其 -462 风控时可能加重风控）。
 * 本门控只管计数，纯 Kotlin、无 Compose 依赖，供探针直接单测：
 *  - 自动重报失败累计 [MAX_AUTO_RETRIES] 次后 [onAutoFailure] 返回 true，
 *    调用方此时**不要**调 resetCaptureDedup()，轮询不再重报同一 cookie，
 *    自动重试即停，只剩手动「重新校验」。
 *  - 成功或换 cookie（[onNewCookie]/[onSuccess]）时清零。
 *  - 手动「重新校验」走 [onManualRetry]：不计入自动次数，且清零计数
 *    （一次手动点击重新给自动重报最多 3 次预算，避免手动后立刻死局）。
 */
class WebLoginAutoRetry {

    companion object {
        /** 自动重报上限（次）。 */
        const val MAX_AUTO_RETRIES = 3
    }

    private var autoFailures = 0

    /** 当前自动失败计数（供 UI 文案/探针读取）。 */
    val failures: Int get() = autoFailures

    /** 是否已达上限（自动重报应停）。 */
    val exhausted: Boolean get() = autoFailures >= MAX_AUTO_RETRIES

    /**
     * 一次自动重报失败：计数 +1。返回 true 表示已达上限、调用方应停
     * 止自动重报（不再复位捕获去重）；false 表示仍可继续自动重报。
     */
    fun onAutoFailure(): Boolean {
        autoFailures++
        return exhausted
    }

    /** 手动「重新校验」：不计自动次数，清零计数，返回 true 表示可执行。 */
    fun onManualRetry(): Boolean {
        autoFailures = 0
        return true
    }

    /** 校验成功：清零。 */
    fun onSuccess() {
        autoFailures = 0
    }

    /** 换 cookie（新登录态）：清零。 */
    fun onNewCookie() {
        autoFailures = 0
    }
}
