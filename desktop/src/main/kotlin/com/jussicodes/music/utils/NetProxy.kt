package com.jussicodes.music.utils

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * 出站代理（2.2.0，L1）：两种模式——
 *  - [MODE_DIRECT]：直连（默认，与旧版行为完全一致；openHttp 强制
 *    Proxy.NO_PROXY，不受系统/环境影响）。
 *  - [MODE_SYSTEM]：跟随系统代理。Windows 读 WinINet 注册表
 *    （HKCU\...\Internet Settings 的 ProxyEnable/ProxyServer）；其它
 *    平台回落 http_proxy/https_proxy 环境变量。静态代理可解析；
 *    PAC/WPAD 自动配置脚本**不支持**（无脚本引擎），解析不到时
 *    退化为默认连接（系统属性也不设置）。
 *
 * 覆盖面：HttpURLConnection 全量调用点（播放直链、更新、下载、
 * 封面等）经 [openHttp] 走本类；Ktor/OkHttp 数据面（API 请求）
 * 经 [apply] 同步 JVM 系统属性 http(s).proxyHost/Port（OkHttp 的
 * 默认 ProxySelector 读系统属性）。JCEF 网页登录与 libVLC（MV）
 * 各有自己的网络栈，不走本设置（设置页文案已注明）。
 *
 * 系统属性只在本进程内生效；apply(DIRECT) 会清掉本类设置过的属性。
 */
object NetProxy {

    const val MODE_DIRECT = "DIRECT"
    const val MODE_SYSTEM = "SYSTEM"

    @Volatile
    var mode: String = MODE_DIRECT
        private set

    /**
     * 2.3.0（A6-03）：代理缓存按 scheme 分两槽——此前单一槽导致
     * WinINet 分协议形态（http/https 不同 host:port）下 http 请求
     * 可能拿到先前为 https 解析的代理。槽内是（代理，解析时刻）。
     */
    @Volatile
    private var cachedProxies: Map<String, Pair<Proxy?, Long>> = emptyMap()

    private const val CACHE_TTL_MS = 30_000L

    /** 应用模式：存状态、清解析缓存、同步 JVM 系统属性（供 OkHttp/Ktor 数据面）。 */
    fun apply(modeValue: String?) {
        mode = if (modeValue == MODE_SYSTEM) MODE_SYSTEM else MODE_DIRECT
        cachedProxies = emptyMap()
        applySystemProperties()
    }

    private fun applySystemProperties() {
        // 2.3.0（A6-03）：http/https 分别解析、分别 setProperty——
        // 此前用 https 的结果同时填两组属性，分协议代理下 http 数据面
        // 永远走错代理。
        val keys = arrayOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort")
        if (mode != MODE_SYSTEM) {
            keys.forEach { System.clearProperty(it) }
            return
        }
        setProxyProperties("http", resolveSystemProxy("http"))
        setProxyProperties("https", resolveSystemProxy("https"))
    }

    private fun setProxyProperties(scheme: String, proxy: Proxy?) {
        val addr = proxy?.address() as? InetSocketAddress
        if (addr != null) {
            System.setProperty("$scheme.proxyHost", addr.hostString)
            System.setProperty("$scheme.proxyPort", addr.port.toString())
        } else {
            System.clearProperty("$scheme.proxyHost")
            System.clearProperty("$scheme.proxyPort")
        }
    }

    /** 打开 HttpURLConnection 并按当前模式选代理。 */
    fun openHttp(url: String): HttpURLConnection {
        val conn = when {
            mode == MODE_DIRECT -> URL(url).openConnection(Proxy.NO_PROXY)
            else -> {
                val scheme = runCatching { URL(url).protocol }.getOrDefault("https")
                val proxy = resolveSystemProxy(scheme)
                if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection()
            }
        }
        return conn as HttpURLConnection
    }

    /** 当前模式下的有效代理（SYSTEM 才解析；每 scheme 独立 30s 缓存，系统设置变更 apply 或过期后重读）。 */
    fun resolveSystemProxy(scheme: String): Proxy? {
        if (mode != MODE_SYSTEM) return null
        val key = scheme.lowercase()
        val now = System.currentTimeMillis()
        val cached = cachedProxies[key]
        if (cached != null && now - cached.second < CACHE_TTL_MS && cached.second != 0L) {
            return cached.first
        }
        val resolved = detectSystemProxy(key)
        cachedProxies = cachedProxies + (key to (resolved to now))
        return resolved
    }

    private fun detectSystemProxy(scheme: String): Proxy? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        if (os.contains("win")) {
            val enable = regQuery("ProxyEnable")?.trim()
            if (enable != "1") return null
            val server = regQuery("ProxyServer")?.trim().orEmpty()
            if (server.isBlank()) return null
            return parseProxyServerValue(server, scheme)
        }
        // 2.3.0（A6-03 续）：非 Windows 按 scheme 优先取同名环境变量
        // （此前不论 scheme 一律先取 https_proxy，分协议下 http 也被
        // 带偏；分槽缓存的前提是解析本身就分协议）。
        val env = if (scheme.lowercase() == "http") {
            System.getenv("http_proxy") ?: System.getenv("HTTP_PROXY")
                ?: System.getenv("https_proxy") ?: System.getenv("HTTPS_PROXY")
        } else {
            System.getenv("https_proxy") ?: System.getenv("HTTPS_PROXY")
                ?: System.getenv("http_proxy") ?: System.getenv("HTTP_PROXY")
        } ?: return null
        return parseProxyAddress(env.trim())
    }

    private fun regQuery(name: String): String? = runCatching {
        val process = ProcessBuilder(
            "reg", "query",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
            "/v", name
        ).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor()
        // 输出形如: "    ProxyEnable    REG_DWORD    0x1"
        text.lineSequence()
            .firstOrNull { it.contains(name) }
            ?.substringAfterLast("    ")
            ?.trim()
            ?.let { if (it.startsWith("0x")) it.substring(2).toIntOrNull(16)?.toString() ?: it else it }
    }.getOrNull()

    /**
     * 解析 WinINet ProxyServer 值：两种形态——
     *  ① 裸 "host:port"；② 按协议 "http=h:1;https=h:2;ftp=..."，优先取
     * 与 [scheme] 同名项，没有则退裸项/第一项。
     * 解析失败返回 null（调用方退化默认连接）。公开供探针单测。
     */
    fun parseProxyServerValue(value: String, scheme: String): Proxy? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.contains('=')) return parseProxyAddress(trimmed)
        val entries = trimmed.split(';').mapNotNull { part ->
            val kv = part.trim().split('=', limit = 2)
            if (kv.size == 2) kv[0].trim().lowercase() to kv[1].trim() else null
        }
        if (entries.isEmpty()) return null
        val chosen = entries.firstOrNull { it.first == scheme.lowercase() }?.second
            ?: entries.firstOrNull { it.second.isNotEmpty() }?.second
            ?: return null
        return parseProxyAddress(chosen)
    }

    /** 解析 "host:port" 或 "http://[user:pass@]host:port[/...]" 为 HTTP 代理。 */
    fun parseProxyAddress(raw: String): Proxy? {
        var v = raw.trim()
        if (v.isEmpty()) return null
        val schemeIdx = v.indexOf("://")
        if (schemeIdx >= 0) v = v.substring(schemeIdx + 3)
        v = v.substringBefore('/')
        val at = v.lastIndexOf('@')
        if (at >= 0) v = v.substring(at + 1)
        val colon = v.lastIndexOf(':')
        if (colon <= 0) return null
        val host = v.substring(0, colon).trim()
        val port = v.substring(colon + 1).trim().toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
    }
}
