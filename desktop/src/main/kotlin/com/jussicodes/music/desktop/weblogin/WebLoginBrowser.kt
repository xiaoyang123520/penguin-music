package com.jussicodes.music.desktop.weblogin

import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import org.cef.CefApp
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.callback.CefCookieVisitor
import org.cef.misc.BoolRef
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import java.awt.BorderLayout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * 网页登录内嵌浏览器（2.0.4，手机同款路线）。
 *
 * 安卓版用 WebView 打开 https://music.163.com/m/login、登录后抓 Cookie；
 * 桌面版 2.0.1–2.0.3 用 API 模拟登录硬刚网易云风控，真机一直提示
 * 「环境异常有安全风险」。本类用 JCEF（Chromium 132）内嵌同一个官方
 * 登录页，用户在页面里完成登录，本类只负责：
 * 1. 惰性初始化 CefApp（进程内仅一次，装到 <dataDir>/jcef 可写目录）；
 * 2. 轮询 music.163.com 与 y.music.163.com 两域 Cookie（与安卓版
 *    onPageFinished 抓两域的口径一致，轮询是对页面跳转时机的稳健化）；
 * 3. 出现 MUSIC_U 后把整表回调给登录页做归一化与 /user/account 真校验。
 *
 * 失败降级：JCEF 初始化失败（本地组件缺失等）时返回提示面板，不让整个
 * 登录页崩掉，用户仍可改用 Cookie 登录。
 *
 * 安全口径：产品不带任何绕证书参数。沙箱探针（run6/JcefProbe）里的
 * --ignore-certificate-errors 只存在于探针进程（本机出口是 TLS 中间人
 * 代理、自签 CA 不在 Chromium 信任库），绝不进产品，见 PORTING §7.13。
 */
object WebLoginBrowser {

    const val LOGIN_URL = "https://music.163.com/m/login"

    /** 与沙箱探针验证过的移动 UA 一致（m/login 是移动页，桌面 UA 会被导去 PC 页）。 */
    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val COOKIE_URLS = listOf("https://music.163.com", "https://y.music.163.com")

    @Volatile
    private var cefApp: CefApp? = null

    @Volatile
    private var initError: String? = null

    @Volatile
    private var shutdownHookRegistered = false

    private val initLock = Any()

    private fun dataDir(): File {
        val prop = System.getProperty("jussichords.dataDir")
        return if (prop != null) File(prop) else File(System.getProperty("user.home"), ".jussichords")
    }

    /**
     * 进程内共享的 CefApp（2.1.0：MV 播放窗口复用同一实例——CEF 在
     * 一个进程里只能初始化一次）。登录逻辑不动，这里只是公开转调。
     */
    fun sharedCefApp(): CefApp? = ensureCefApp()

    private fun ensureCefApp(): CefApp? {
        cefApp?.let { return it }
        synchronized(initLock) {
            cefApp?.let { return it }
            if (initError != null) return null
            return try {
                val builder = CefAppBuilder()
                builder.setInstallDir(File(dataDir(), "jcef"))
                // #14：给 CEF 一个落盘的缓存目录（此前未设 cache_path，
                // Cookie/缓存只在内存里，进程一退全丢）。放在数据目录下
                // 的 jcef-cache 子目录，与 jcef 本体同级、随数据目录走。
                builder.getCefSettings().cache_path =
                    File(dataDir(), "jcef-cache").absolutePath
                // MV 播放（2.1.0）：内嵌页面的 video autoplay 带声音会被
                // Chromium 自动播放策略拦截（实证：静音可播、带声卡死在
                // 0s 且 readyState=4）。桌面媒体应用惯例是关掉这个手势
                // 要求（Electron 同名选项）；只影响本进程内嵌浏览器，
                // 与证书校验无关（产品代码不带任何 ignore-cert 开关）。
                builder.addJcefArgs("--autoplay-policy=no-user-gesture-required")
                // 2.1.3 硬化（真机事故 2026-10-25）：老板真机跑 2.1.2 时
                // 整机假死被迫断电，其机器装有向日葵虚拟显卡驱动
                // （OrayIddDriver），chrome_debug.log 里有 JCEF/Chromium
                // 的 GPU adapter 错误（gpu_blocklist "Unable to get gpu
                // adapter" 等）。定性：JCEF 的 GPU 硬件加速与虚拟显卡
                // 驱动相撞是最大嫌疑触发点。此处强制禁用 GPU 与 GPU
                // 合成、走 SwiftShader 软件渲染——JCEF 在本应用只用于
                // 网页登录，不需要 GPU，性能无损。本段是硬编码强制项，
                // 不受应用内任何「硬件加速」类开关影响（那类开关只管
                // Compose/Skiko 渲染，与 CEF 是两条线）。
                builder.addJcefArgs(
                    "--disable-gpu",
                    "--disable-gpu-compositing",
                    "--use-angle=swiftshader"
                )
                builder.getCefSettings().windowless_rendering_enabled = false
                builder.getCefSettings().user_agent = MOBILE_UA
                builder.getCefSettings().log_severity =
                    CefSettings.LogSeverity.LOGSEVERITY_ERROR
                builder.setAppHandler(object : MavenCefAppHandlerAdapter() {})
                builder.build().also {
                    cefApp = it
                    registerShutdownHook()
                }
            } catch (t: Throwable) {
                initError = t.message ?: t.javaClass.simpleName
                System.err.println("[WebLogin] JCEF init failed: $t")
                null
            }
        }
    }

    /**
     * #14：进程退出时 dispose CefApp。CEF 在一个进程里只能初始化一次
     * （见 sharedCefApp 口径），dispose 是终局动作、只应在进程退出时
     * 调用，故这里不清空 [cefApp] 引用、不提供同进程内重初始化路径。
     * 内部已在 CefApp 建成时注册 JVM shutdown hook 自行调用，外部
     * （如将来 DesktopMain 的退出路径）也可显式调本方法，重复调用
     * 由 runCatching 与 CEF 自身状态兜底。
     */
    fun shutdown() {
        val app = cefApp ?: return
        runCatching { app.dispose() }
            .onFailure { System.err.println("[WebLogin] CefApp dispose failed: $it") }
    }

    private fun registerShutdownHook() {
        if (shutdownHookRegistered) return
        shutdownHookRegistered = true
        runCatching {
            Runtime.getRuntime().addShutdownHook(Thread({ shutdown() }, "jcef-shutdown"))
        }.onFailure { System.err.println("[WebLogin] addShutdownHook failed: $it") }
    }

    /**
     * #3：清掉 JCEF 侧的全部 Cookie（退出登录时调用；此前退出只清
     * DataStore + CookieProvider，内嵌浏览器里的 MUSIC_U 还在，下次
     * 打开网页登录会直接带着旧登录态）。走全局 CefCookieManager：
     * url 与 name 都传空串 = 删除全部域的全部 Cookie（java-cef
     * CefCookieManager.deleteCookies 的契约）。删除后 flushStore
     * 把结果落盘，避免 cache_path 里的持久化副本把旧 Cookie 带回来。
     * 本方法不抛异常：失败只记日志，调用方（退出流程）不被阻断。
     * CEF 尚未初始化（从未打开过网页登录）时没有 Cookie 可清，直接返回。
     */
    fun clearCookies() {
        runCatching {
            val cm = CefCookieManager.getGlobalManager() ?: return
            val deleted = cm.deleteCookies("", "")
            println("[WebLogin] clearCookies deleteAll=$deleted")
            runCatching { cm.flushStore(null) }
                .onFailure { System.err.println("[WebLogin] flushStore failed: $it") }
        }.onFailure { System.err.println("[WebLogin] clearCookies failed: $it") }
    }

    /**
     * 创建网页登录组件。Cookie 轮询随组件显示开始、随组件移除停止；
     * 捕获到 MUSIC_U 时 [onCookies] 在轮询线程回调一次（整表，未归一化）。
     */
    /** 当前活跃会话（2.2.0，A5-11）：供上层校验失败时复位捕获去重。 */
    @Volatile
    private var activeSession: Session? = null

    /**
     * 复位当前会话的捕获去重（2.2.0，A5-11）：上层（LoginScreen）对
     * 捕获到的 cookie 校验失败时调用——下一轮轮询会把页面里同一
     * MUSIC_U 再次回调，等价于自动重试；不调用的话同一 cookie 值
     * 永远不会被第二次上报，校验失败即死局（audit5 #11）。
     */
    fun resetCaptureDedup() {
        activeSession?.resetDedup()
    }

    fun createComponent(onCookies: (Map<String, String>) -> Unit): JComponent {
        val panel = JPanel(BorderLayout())
        val hint = JLabel("正在加载网易云官方登录页…", SwingConstants.CENTER)
        panel.add(hint, BorderLayout.CENTER)

        val session = Session(onCookies)
        activeSession = session
        panel.addHierarchyListener { e ->
            if ((e.changeFlags and
                    java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L &&
                !panel.isShowing
            ) {
                session.close()
            }
        }

        Thread({
            val app = ensureCefApp()
            SwingUtilities.invokeLater {
                if (app == null) {
                    hint.text = "内嵌浏览器初始化失败（${initError ?: "未知原因"}），请改用 Cookie 登录"
                    return@invokeLater
                }
                runCatching {
                    val client = app.createClient()
                    val browser = client.createBrowser(LOGIN_URL, false, false)
                    session.attach(browser)
                    panel.remove(hint)
                    panel.add(browser.uiComponent, BorderLayout.CENTER)
                    panel.revalidate()
                    session.startPolling()
                }.onFailure {
                    hint.text = "内嵌浏览器打开失败：${it.message ?: it.javaClass.simpleName}"
                    System.err.println("[WebLogin] createBrowser failed: $it")
                }
            }
        }, "weblogin-init").apply { isDaemon = true }.start()

        return panel
    }

    private class Session(
        private val onCookies: (Map<String, String>) -> Unit
    ) {
        private val closed = AtomicBoolean(false)
        private val fired = AtomicBoolean(false)
        private val pollingActive = AtomicBoolean(false)

        /** 上次已回调过的 MUSIC_U 值：同一捕获只回调一次的依据（#2）。 */
        @Volatile
        private var lastFiredMusicU: String? = null

        @Volatile
        private var browser: CefBrowser? = null

        fun attach(b: CefBrowser) {
            browser = b
        }

        /** 清掉已回调标记：下一轮轮询会把同一 MUSIC_U 再回调一次（A5-11 重试路径）。 */
        fun resetDedup() {
            lastFiredMusicU = null
        }

        fun startPolling() {
            if (closed.get()) return
            // 防重入：已有轮询线程在跑时重复调用直接返回，不起双线程。
            if (!pollingActive.compareAndSet(false, true)) return
            Thread({
                try {
                    while (!closed.get()) {
                        try {
                            Thread.sleep(2500)
                        } catch (_: InterruptedException) {
                            break
                        }
                        if (closed.get()) break
                        val map = collectCookies() ?: continue
                        val musicU = map["MUSIC_U"]?.takeIf { it.isNotBlank() }
                        if (musicU == null) {
                            // 页面里未登录/已退出：清掉上次捕获标记，
                            // 下次登录出现 MUSIC_U 时可再次触发。
                            lastFiredMusicU = null
                            continue
                        }
                        // 同一捕获（MUSIC_U 值未变）只回调一次。
                        if (musicU == lastFiredMusicU) continue
                        if (fired.compareAndSet(false, true)) {
                            lastFiredMusicU = musicU
                            println("[WebLogin] MUSIC_U captured, cookies=${map.keys}")
                            runCatching { onCookies(map) }
                            // #2：捕获回调后立即复位 fired，不再永久置位。
                            // 此前 fired 一旦置位整个 Session 再无复活路径：
                            // 上层校验失败（如断网）后，用户在页面里重新
                            // 登录也触发不了第二次回调。复位后靠上面的
                            // lastFiredMusicU 去重保证单次捕获仍只回调
                            // 一次，值变化（重新登录）时能再次触发。
                            fired.set(false)
                        }
                    }
                } finally {
                    // 一轮轮询结束（组件关闭/线程中断）时复位状态，
                    // 使同一 Session 再次 startPolling 能重新工作。
                    fired.set(false)
                    pollingActive.set(false)
                }
            }, "weblogin-cookie-poll").apply { isDaemon = true }.start()
        }

        /** 两域 Cookie 汇总（后者覆盖前者，与安卓版 putAll 顺序一致）。 */
        private fun collectCookies(): Map<String, String>? {
            val cm = CefCookieManager.getGlobalManager() ?: return null
            val out = LinkedHashMap<String, String>()
            for (url in COOKIE_URLS) {
                val latch = CountDownLatch(1)
                val per = LinkedHashMap<String, String>()
                val accepted = runCatching {
                    cm.visitUrlCookies(url, true, object : CefCookieVisitor {
                        override fun visit(
                            cookie: CefCookie,
                            count: Int,
                            total: Int,
                            delete: BoolRef
                        ): Boolean {
                            per[cookie.name] = cookie.value
                            if (count == total - 1) latch.countDown()
                            return true
                        }
                    })
                }.getOrDefault(false)
                if (accepted) latch.await(1500, TimeUnit.MILLISECONDS)
                out.putAll(per)
            }
            return out
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            val b = browser
            browser = null
            if (b != null) {
                SwingUtilities.invokeLater { runCatching { b.close(true) } }
            }
        }
    }
}
