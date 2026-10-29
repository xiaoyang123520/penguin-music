package com.rcmiku.ncmapi.api

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Properties
import java.util.UUID

/**
 * 桌面登录指纹（2.0.1 新增）：持久设备身份 + 预热 Cookie jar。
 *
 * 背景：2.0 的登录请求指纹「裸奔」——deviceId 每进程随机、匿名 Cookie 只有
 * deviceId、UA 是安卓 WebView，与 Windows 真机环境自相矛盾，真机上扫码/验证码
 * 被网易云风控判「环境异常」。本持有者把 deviceId 生成一次后落盘（应用数据
 * 目录下的 device-identity.properties），跨重启稳定复用；并负责用桌面 UA 预热
 * GET 官网收集服务端下发的初始 Cookie 存 jar，供登录族请求原样带上。
 * 2.0.3 修订：登录 Cookie 集合改为对齐 api-enhanced 的 processCookieObject
 * （见 [pcFingerprintFields]）——_ntes_nuid/_ntes_nnid/WNMCID 均为与官方
 * 客户端同形的良构值且落盘稳定；2.0.1 消融翻车的自造 NMTID 是畸形短串
 * （16 位字母数字，真 NMTID 为 00O+38hex），本版 NMTID 仍只发服务端真
 * 下发进 jar 的那份，绝不自造。只服务登录族与匿名 deviceId，数据面其它
 * 请求的指纹（移动端 eapi/weapi）不动，避免回归。
 */
object DeviceIdentity {

    /** 登录族请求统一使用的 Windows 桌面 Chrome UA（与 os=pc 环境自洽）。 */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val lock = Any()
    private var storeFile: File? = null
    private var props: Properties? = null

    /** 预热 Cookie jar（服务端下发，含 _ntes_nuid/JSESSIONID-WYYY/__csrf 等）。 */
    private val jar = linkedMapOf<String, String>()

    @Volatile
    private var warmedThisProcess = false

    /** 由桌面壳在进程启动时调用一次：指定落盘文件并加载（不存在则生成并落盘）。 */
    fun init(file: File) {
        synchronized(lock) {
            if (storeFile == file && props != null) return
            storeFile = file
            val p = Properties()
            if (file.isFile) {
                runCatching { file.inputStream().use { p.load(it) } }
            }
            var dirty = false
            fun ensure(key: String, gen: () -> String): String {
                val cur = p.getProperty(key)
                if (!cur.isNullOrBlank()) return cur
                val v = gen()
                p.setProperty(key, v)
                dirty = true
                return v
            }
            // 2.0.3：新身份的 deviceId 形态对齐 api-enhanced generateDeviceId
            // （52 位大写十六进制）；已落盘的老身份（32 位）原样保留不迁移。
            ensure("deviceId") { hexUpper(52) }
            ensure("_ntes_nuid") { hex(32) }
            // _ntes_nnid = "<_ntes_nuid>,<毫秒时间戳>"（api-enhanced processCookieObject 同形），
            // 与 _ntes_nuid 同批生成、一起落盘，跨重启稳定。
            ensure("_ntes_nnid") {
                val nuid = p.getProperty("_ntes_nuid") ?: hex(32).also { p.setProperty("_ntes_nuid", it) }
                "$nuid,${System.currentTimeMillis()}"
            }
            ensure("NMTID") { alnum(16) }
            ensure("WNMCID") {
                (1..6).joinToString("") { "abcdefghijklmnopqrstuvwxyz".random().toString() } +
                    ".${System.currentTimeMillis()}.01.0"
            }
            // 恢复预热 jar
            jar.clear()
            for (name in p.stringPropertyNames()) {
                if (name.startsWith("jar.")) {
                    p.getProperty(name)?.takeIf { it.isNotEmpty() }
                        ?.let { jar[name.removePrefix("jar.")] = it }
                }
            }
            props = p
            if (dirty) saveLocked()
        }
    }

    private fun hex(n: Int): String =
        (1..n).map { "0123456789abcdef".random() }.joinToString("")

    private fun hexUpper(n: Int): String =
        (1..n).map { "0123456789ABCDEF".random() }.joinToString("")

    private fun alnum(n: Int): String =
        (1..n).map { (('a'..'z') + ('A'..'Z') + ('0'..'9')).random() }.joinToString("")

    private fun saveLocked() {
        val f = storeFile ?: return
        val p = props ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.outputStream().use { p.store(it, "penguinmusic device identity (do not share)") }
        }
    }

    /** 稳定 deviceId（未 init 时退化为进程内随机值，保证安卓/探针等旧调用方不崩）。 */
    val deviceId: String
        get() = synchronized(lock) {
            props?.getProperty("deviceId") ?: fallbackDeviceId
        }

    private val fallbackDeviceId: String by lazy {
        UUID.randomUUID().toString().replace("-", "")
    }

    /** 持久设备字段（deviceId/sDeviceId/_ntes_nuid/NMTID/WNMCID），登录 Cookie 用。 */
    fun deviceFields(): Map<String, String> = synchronized(lock) {
        val p = props
        if (p == null) {
            mapOf("deviceId" to fallbackDeviceId, "sDeviceId" to fallbackDeviceId)
        } else {
            linkedMapOf(
                "deviceId" to p.getProperty("deviceId"),
                "sDeviceId" to p.getProperty("deviceId"),
                "_ntes_nuid" to p.getProperty("_ntes_nuid"),
                "NMTID" to p.getProperty("NMTID"),
                "WNMCID" to p.getProperty("WNMCID"),
            )
        }
    }

    /** 预热 jar 的快照。 */
    fun warmCookies(): Map<String, String> = synchronized(lock) { LinkedHashMap(jar) }

    /** 匿名令牌 MUSIC_A 是否已在 jar 里（register/anonimous 成功后由服务端下发）。 */
    fun hasMusicA(): Boolean = synchronized(lock) { !jar["MUSIC_A"].isNullOrBlank() }

    /**
     * 登录族请求的 PC 指纹字段集（2.0.3，逐项对齐 api-enhanced
     * util/request.js 的 processCookieObject + osMap.pc）：
     * __remember_me / ntes_kaola_ad / _ntes_nuid / _ntes_nnid / WNMCID /
     * WEVNSM / os=pc / appver / osver / channel=netease，值全部来自落盘的
     * 持久身份（跨重启稳定，不逐请求随机）。NMTID 不在此列——只发服务端
     * 真下发进 jar 的那份（2.0.1 消融教训：自造的畸形 NMTID 会被验伪）。
     */
    fun pcFingerprintFields(): Map<String, String> = synchronized(lock) {
        val p = props ?: return mapOf("deviceId" to fallbackDeviceId)
        linkedMapOf(
            "__remember_me" to "true",
            "ntes_kaola_ad" to "1",
            "_ntes_nuid" to (p.getProperty("_ntes_nuid") ?: ""),
            "_ntes_nnid" to (p.getProperty("_ntes_nnid") ?: ""),
            "WNMCID" to (p.getProperty("WNMCID") ?: ""),
            "WEVNSM" to "1.0.0",
            "osver" to "Microsoft-Windows-10-Professional-build-19045-64bit",
            "deviceId" to (p.getProperty("deviceId") ?: fallbackDeviceId),
            "os" to "pc",
            "channel" to "netease",
            "appver" to "3.1.17.204416",
        ).filterValues { it.isNotEmpty() }
    }

    /** 吸收响应 Set-Cookie 进 jar 并落盘（登录链的中间态 Cookie 也走这里）。 */
    fun absorbSetCookies(setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        synchronized(lock) {
            var dirty = false
            for (header in setCookies) {
                val pair = header.substringBefore(';').trim()
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val name = pair.substring(0, eq).trim()
                    val value = pair.substring(eq + 1).trim()
                    if (name.isNotEmpty() && value.isNotEmpty() && jar[name] != value) {
                        jar[name] = value
                        props?.setProperty("jar.$name", value)
                        dirty = true
                    }
                }
            }
            if (dirty) saveLocked()
        }
    }

    /**
     * register/anonimous 的 username 编码（逐字对齐 api-enhanced
     * register_anonimous.js + cloudmusic_dll_encode_id）：
     * base64( deviceId + " " + base64( md5raw( deviceId XOR "3go8&$8*3*3h0k(2)2" ) ) )。
     */
    fun anonymousUsername(): String {
        val id = deviceId
        val key = "3go8&\$8*3*3h0k(2)2"
        val xored = ByteArray(id.length) { i ->
            (id[i].code xor key[i % key.length].code).toByte()
        }
        val md5 = java.security.MessageDigest.getInstance("MD5").digest(xored)
        val encodedId = java.util.Base64.getEncoder().encodeToString(md5)
        return java.util.Base64.getEncoder()
            .encodeToString("$id $encodedId".toByteArray(Charsets.UTF_8))
    }

    private val warmClient by lazy { OkHttpClient() }

    /**
     * 预热：用桌面 UA GET 官网 /discover（首页 / 实测不下发 Cookie，/discover 会），
     * 把服务端下发的初始 Cookie 种进 jar。请求只带 deviceId——自造的身份字段
     * 服务端能验出真伪（见 ApiClient 登录 Cookie 分支的消融结论）。
     * 每进程只真正执行一次（后续调用直接返回）；失败静默（登录仍可继续，
     * 服务端业务码照常透传）。阻塞 IO，调用方须在 IO 线程执行。
     */
    fun warmUpBlocking() {
        if (warmedThisProcess) return
        warmedThisProcess = true
        runCatching {
            val req = Request.Builder()
                .url("https://music.163.com/discover")
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Cookie", "deviceId=$deviceId")
                .get()
                .build()
            warmClient.newCall(req).execute().use { resp ->
                absorbSetCookies(resp.headers("Set-Cookie"))
                println(
                    "[identity] warmup http=${resp.code} jar=${warmCookies().keys} " +
                        "deviceId=${deviceId.take(12)}..."
                )
            }
        }.onFailure {
            println("[identity] warmup failed: ${it.message}")
        }
    }
}
