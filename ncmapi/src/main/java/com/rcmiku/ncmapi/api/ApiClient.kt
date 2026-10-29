package com.rcmiku.ncmapi.api

import android.util.Log
import com.rcmiku.ncmapi.utils.CookieProvider
import com.rcmiku.ncmapi.utils.json
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.encodeURLParameter
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CacheControl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType

// NCM 数据面已改为直连官方接口（music.163.com / interfacepc.music.163.com），
// 不再经 ncmapi 代理。API_BASE_URL / UNBLOCK_SOURCE 现仅保留给「解灰音源 unblock」
// 一条仍走代理的通道（见 PlayerApi.tryUnblockUrl）；头像/歌单封面上传已改 NOS 直连。
// apiPostFile*（经 API_BASE_URL 的 multipart）已无人调用，仅保留以防 unblock 旁路复用。
// 默认不设服务器（空串）：首启不自动选默认，用户在设置里挑。本地音乐源开启时不依赖此值。
var API_BASE_URL = ""
var UNBLOCK_SOURCE = "AUTO"
@PublishedApi
internal val okHttpUploadClient: OkHttpClient = OkHttpClient.Builder()
    // 2.1.2（#15）：此前裸 OkHttpClient() 吃 OkHttp 默认超时（connect/read/write
    // 各 10s），大图上传易被 10s read/write 掐断且行为不可控。显式化：连接 15s、
    // 读/写各 60s（上传通道给宽些）。
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()

// （2.0.3 删除：历史遗留的 anonymousDeviceId——每进程随机、无任何使用点，
// 与「设备身份必须落盘稳定」的登录对齐方针冲突。实际发送的 deviceId 恒为
// DeviceIdentity 的落盘值。）

// NCM 官方域名（对齐 3.0.0）。
private const val NCM_WEB_DOMAIN = "https://music.163.com"
// 对齐代理 config.json 的 eapiDomain（当前生产在用的 eapi 目标域），不是 interface。
private const val NCM_API_DOMAIN = "https://interfacepc.music.163.com"

// 移动端（默认 weapi/eapi）User-Agent。
@PublishedApi
internal const val NCM_MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 10; Mi A3 Build/QQ3A.200705.002; wv) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Version/4.0 Chrome/143.0.7499.34 Mobile Safari/537.36 NeteaseMusic/9.4.32.251222163637"
// eapi 播放历史上报专用 macOS 桌面 UA（对齐 MeiloX playback-history profile）。
internal const val NCM_OSX_UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Safari/537.36"
// eapi 播放历史上报专用域（对齐 MeiloX：interface 域 + osx 桌面 profile）。
internal const val NCM_EAPI_HISTORY_DOMAIN = "https://interface.music.163.com"

// 一起听 eapi 设备身份 cookie（逐字对齐 MeiloX NeteaseInterceptor 非播放历史的
// buildCookieString(cookieMap)：os/appver/osver/channel/versioncode/mobilename/buildver/
// resolution + deviceId/sDeviceId + ntes_kaola_ad/_ntes_nuid/WNMCID/URS_APPID/WEVNSM/
// __csrf/NMDI/NMTID + MUSIC_U，共 19 字段；明文值、插入序，不做 URL-encode）。
// 官方客户端指纹常量（MeiloX 抓包值，逐字照抄）：
private const val LT_NMDI =
    "Q1NKTQkBDAAMIEF4coQMHcb6TLA7AAAAciOiJ%2F%2FOO4VQ7m%2FLvLJ1pD9CIsJP5mfzI4SusB%2BaNScGLpThEYBcPxGzj0pL5hLdZ7LqB2UVULdYgc0%3D"
private const val LT_URS_APPID =
    "F2219AE9D7828A7D73E2006D000C61031D196A37DB497E3885B8298504867886B6F0E44087D61EFC06BE92279CD6EEC6"
// MeiloX 一起听 eapi 的 __csrf 为硬编码常量（header 与 cookie 同源），不用登录 cookie 里的。
private const val LT_CSRF = "40ab38f0a305fc4c7ff68e636bcf34aa"

// 会话级缓存（MeiloX 同形：app 生命周期 lazy 一次，会话内保持稳定）。
private fun alnumKey(n: Int): String =
    (1..n).map { (('a'..'z') + ('A'..'Z') + ('0'..'9')).random() }.joinToString("")
private val LT_NUID = alnumKey(32)
private val LT_NMTID = alnumKey(16)
private val LT_WNMCID =
    (1..6).joinToString("") { "abcdefghijklmnopqrstuvwxyz".toCharArray().random().toString() } +
        ".${System.currentTimeMillis()}.01.0"

// MeiloX NeteaseInterceptor：一起听 eapi 每个请求都自报 X-Real-IP/X-Forwarded-For = 会话稳定的
// 随机中国 IP（fakeIP lazy 只算一次）。NCM 一起听「写共享队列」按自报国内 IP 信任放行写入；
// 之前 shape/信封/19字段cookie 的差异都试尽仍 result=false，唯独这个「MeiloX 有、jussichords 完全没发」
// 的 IP 自报头没试过。会话稳定（顶层 val 调一次），避免每请求换 IP 反而触发风控。
private val LT_FAKE_IP = ChineseIpUtils.generateRandomChineseIP()

// MeiloX EAPI_CONFIG 桌面 buildver 是常量 1768990079（非动态时间戳）；NCM eapi 客户端指纹认桌面固定 buildver。
private const val LT_BUILDVER = "1768990079"

// 登录族路由（2.0.1 新增）：扫码/验证码/密码登录走独立的桌面指纹通道
// （DeviceIdentity 持久设备身份 + 预热 Cookie + 桌面 UA），与数据面移动指纹
// 完全隔离——数据面请求行为不变，避免回归。见 DeviceIdentity 与 sendWithCookies。
@PublishedApi
internal val LOGIN_URIS = setOf(
    "/api/login/qrcode/unikey",
    "/api/login/qrcode/client/login",
    "/api/sms/captcha/sent",
    "/api/sms/captcha/verify",
    "/api/login/cellphone",
    "/api/login",
    "/api/register/anonimous",
)

@PublishedApi
internal fun isLoginRoute(uri: String): Boolean = uri in LOGIN_URIS

/** 登录 Cookie 里要剔除的会话指纹键（CookieProvider 注入的安卓值，与桌面 UA 矛盾）。 */
private val LOGIN_SESSION_FINGERPRINT_KEYS =
    setOf("os", "appver", "osver", "channel", "versioncode", "resolution", "mobilename")

val apiClient = HttpClient(OkHttp) {
    // 2.1.2（#15）：显式超时。此前无 HttpTimeout、无 engine 定制，慢/挂死的
    // 连接只能靠 OkHttp 默认 10s 兜底且 Ktor 层无请求级上限。现 Ktor 层装
    // HttpTimeout（connect 15s / request 30s / socket 30s），engine 层同步
    // 设置 OkHttp 的 connect/read/write，两层口径一致。
    engine {
        config {
            connectTimeout(15, TimeUnit.SECONDS)
            readTimeout(30, TimeUnit.SECONDS)
            writeTimeout(30, TimeUnit.SECONDS)
        }
    }
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 30_000
        socketTimeoutMillis = 30_000
    }
    install(Logging) {
        // 2.0.5 日志降级（B3）：此前 LogLevel.INFO 会把整页请求/响应头
        // （含 Set-Cookie、登录 Cookie）打进日志，属于凭证泄漏面。现只放行
        // Ktor 的请求行/响应行（METHOD / FROM / REQUEST / RESPONSE），
        // 头与体一律过滤掉——排障需要的方法+URL+状态码都还在。
        logger = object : Logger {
            override fun log(message: String) {
                val safe = message.lineSequence()
                    .filter { line ->
                        line.startsWith("REQUEST:") || line.startsWith("RESPONSE:") ||
                            line.startsWith("METHOD:") || line.startsWith("FROM:")
                    }
                    .joinToString(" ")
                    .trim()
                if (safe.isNotEmpty()) Log.d("KtorClient", safe)
            }
        }
        level = LogLevel.INFO
    }
    defaultRequest {
        header("User-Agent", NCM_MOBILE_UA)
        header("Accept", "application/json")
        header("Cache-Control", "no-cache, no-store, max-age=0")
        header("Pragma", "no-cache")
    }
}

/** NCM 数据面入口：按路由表把 jussichords 风格路径映射到官方 weapi/eapi 端点直连。 */
@PublishedApi
internal suspend fun requestNetease(path: String, params: Map<String, Any> = emptyMap()): String {
    if (path == "/api") return sendGenericPassthrough(params)
    val route = resolveRoute(path, params)
    return send(route)
}

/**
 * 同 [requestNetease]，但额外返回响应的 Set-Cookie 头列表。
 * 登录类接口（扫码确认 803 / 手机验证码 / 密码）靠 Set-Cookie 下发 MUSIC_U，
 * 响应 JSON 本身不带 cookie，必须从头里取（对齐 NeteaseCloudMusicApi 的做法）。
 */
@PublishedApi
internal suspend fun requestNeteaseWithCookies(
    path: String,
    params: Map<String, Any> = emptyMap()
): Pair<String, List<String>> {
    val route = resolveRoute(path, params)
    return sendWithCookies(route)
}

/**
 * 数据面风控拦截（2.2.0，A5-12）：在模型解码前检查顶层业务码。
 * ==-462 时先脱敏留痕（login.log）再抛数据面文案的 [RiskControlException]；
 * 非 JSON / 无 code / 其它码一律放行，由原解码路径处理（行为与旧版一致）。
 * 登录族走 requestNeteaseWithCookies、不经此路，不会双重处理。
 */
@PublishedApi
internal fun checkDataRiskControl(path: String, body: String) {
    val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return
    val code = root["code"]?.jsonPrimitive?.intOrNull ?: return
    if (code != -462) return
    val msg = root["message"]?.jsonPrimitive?.contentOrNull
        ?: root["msg"]?.jsonPrimitive?.contentOrNull
        ?: ""
    AuthDiagnostics.log("risk-control at data $path code=$code msg=$msg body=$body")
    throw RiskControlException(
        "网易云风控拦截（-462）：当前登录环境被服务器判定有安全风险，数据暂时拉不到。" +
            "可稍后重试，或退出后重新登录（网页登录 / Cookie 登录）。"
    )
}

suspend inline fun <reified T> apiGet(path: String, params: Map<String, Any> = emptyMap()): Result<T> {
    return runCatching {
        val body = requestNetease(path, params)
        checkDataRiskControl(path, body)
        json.decodeFromString<T>(body)
    }
}

suspend inline fun <reified T> apiPost(path: String, body: Map<String, Any> = emptyMap()): Result<T> {
    return apiGet(path, body)
}

// ---------------------------------------------------------------------------
// 路由表：jussichords 代理路径 -> NCM 官方 uri + 加密方式 + data 形状。
// data 形状逐条对齐 api-enhanced 代理 module/*.js 的默认参数。
// ---------------------------------------------------------------------------

internal enum class Encryption { WEAPI, EAPI }

internal data class Route(
    val uri: String,
    val data: Map<String, Any>,
    val encryption: Encryption,
    /**
     * eapi 请求 profile（对齐 MeiloX 的 eapi profile 机制）：
     * 普通请求用 null（Android 移动端参数 + interfacepc 域 + 移动 UA）；
     * 播放历史上报用 [EAPI_PLAYBACK_HISTORY_PROFILE]（osx 桌面参数 + interface 域 + macOS UA，
     * 不带 __csrf、不发 X-Real-IP/X-Forwarded-For 假 IP 头）。
     */
    val eapiProfile: Map<String, Any>? = null
)

/** 播放历史上报 eapi profile（osx 桌面伪装，参数对齐 MeiloX NeteaseInterceptor）。 */
val EAPI_PLAYBACK_HISTORY_PROFILE: Map<String, Any> = mapOf(
    "os" to "osx",
    "osver" to "15.5",
    "appver" to "3.1.10.5100",
    "versioncode" to "140",
    "channel" to "netease",
    "resolution" to "1920x1080",
    "userAgent" to NCM_OSX_UA,
    "domain" to NCM_EAPI_HISTORY_DOMAIN,
)

/**
 * 用户资料修改（jussichords 路由 /user/update）eapi profile。
 * 对齐代理 createHeaderCookie：eapi 写接口按 Cookie 形态做风控——
 * 发完整登录 cookie 会被 NCM 静默 403 "illegal request"，
 * 发「合成 cookie」（eapi header 字段 URL-encode 后按 key 排序）才进入业务层（250 受理）。
 * os/域名保持移动端默认（interfacepc 域、移动 UA）；synthesizedCookie 开关让 send() 对该 profile
 * 发合成 cookie 而非完整登录 cookie（域名走默认 eapi 分支，无需在这里覆盖 domain）。
 */
val EAPI_USER_UPDATE_PROFILE: Map<String, Any> = mapOf(
    "synthesizedCookie" to true,
)

/**
 * 评论写（发/回复/删除，jussichords 路由 /comment/write）eapi profile：
 * 与播放历史同款的 osx 桌面信封（osx 域 + macOS UA + 空 csrf + 合成 cookie）。
 * send() 对 os=osx profile 自动走「合成 cookie + interface 域 + 空 __csrf」分支，
 * 风控认这组 osx 桌面身份；身份识别靠合成 cookie 里的 MUSIC_U。
 */
val EAPI_COMMENT_WRITE_PROFILE: Map<String, Any> = mapOf(
    "os" to "osx",
    "osver" to "15.5",
    "appver" to "3.1.10.5100",
    "versioncode" to "140",
    "channel" to "netease",
    "resolution" to "1920x1080",
    "userAgent" to NCM_OSX_UA,
    "domain" to NCM_EAPI_HISTORY_DOMAIN,
)

/**
 * 匿名注册（/register/anonimous）eapi profile（2.0.3）：这是 PC 客户端接口
 * （username 编码出自 cloudmusic DLL），api-enhanced 默认 cookie 集即 PC
 * 形态。用移动端 header（os=android）请求实测回 {"code":400}，故走 PC
 * profile：os=pc 全套字段 + interface 域。HTTP Cookie 头仍走登录链分支
 * （pcFingerprintFields + jar），与加密 header 的 os/appver 保持一致。
 */
val EAPI_ANONIMOUS_PROFILE: Map<String, Any> = mapOf(
    "os" to "pc",
    "osver" to "Microsoft-Windows-10-Professional-build-19045-64bit",
    "appver" to "3.1.17.204416",
    "versioncode" to "140",
    "channel" to "netease",
    "resolution" to "1920x1080",
    "domain" to NCM_EAPI_HISTORY_DOMAIN,
)

/**
 * 一起听 eapi 桌面 profile（对齐 MeiloX NeteaseInterceptor.EAPI_CONFIG：os=pc 桌面客户端
 * + interface 域 + NeteaseMusicDesktop UA + 合成 cookie）。
 * 一起听共享队列 REPLACE 写（/sync/list/command/report）在移动信封下被 NCM 静默拒
 * （result=false，而 play/command 仍 true）；MeiloX 桌面信封全通。
 * synthesizedCookie=true：Cookie 头与加密 header 同源（12 字段排序），避免
 * 「完整登录 cookie os=android 与 header os=pc 矛盾 → 静默丢弃」的打卡同坑。
 */
val EAPI_LISTEN_TOGETHER_PROFILE: Map<String, Any> = mapOf(
    "os" to "pc",
    "osver" to "Microsoft-Windows-10-Professional-build-22631-64bit",
    "appver" to "3.0.18.203152",
    "channel" to "netease",
    "versioncode" to "6006066",
    "mobilename" to "Mi+A3",
    "resolution" to "2268x1080",
    "userAgent" to "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/3.0.18.203152",
    "domain" to "https://interface.music.163.com",
    // MeiloX 同款 19 字段设备身份 cookie（NMDI/URS_APPID/WNMCID/NMTID/_ntes_nuid 等），
    // 与加密 header 同源（__csrf 同用常量）。风控对共享队列写校验的是这组身份字段。
    "ltDeviceCookie" to true,
)

@PublishedApi
internal fun resolveRoute(path: String, p: Map<String, Any>): Route {
    fun req(key: String): Any = requireNotNull(p[key]) { "Missing $key for $path" }
    fun long(key: String, def: Long): Long =
        (p[key] as? Number)?.toLong() ?: (p[key] as? String)?.toLongOrNull() ?: def
    fun int(key: String, def: Int): Int =
        (p[key] as? Number)?.toInt() ?: (p[key] as? String)?.toIntOrNull() ?: def
    fun str(key: String): String = p[key]?.toString().orEmpty()

    fun weapi(uri: String, data: Map<String, Any> = emptyMap()) = Route(uri, data, Encryption.WEAPI)
    fun eapi(uri: String, data: Map<String, Any> = emptyMap()) = Route(uri, data, Encryption.EAPI)
    // 播放历史上报专用：eapi + osx 桌面 profile（interface 域 + macOS UA + 空 csrf）。
    fun eapiHistory(uri: String, data: Map<String, Any> = emptyMap()) =
        Route(uri, data, Encryption.EAPI, eapiProfile = EAPI_PLAYBACK_HISTORY_PROFILE)
    // 用户资料修改：eapi + 合成 cookie profile（移动端参数，interfacepc 域）。
    fun eapiUserUpdate(uri: String, data: Map<String, Any> = emptyMap()) =
        Route(uri, data, Encryption.EAPI, eapiProfile = EAPI_USER_UPDATE_PROFILE)
    // 一起听：eapi + 桌面 pc 信封（对齐 MeiloX EAPI_CONFIG：interface 域 + 桌面 UA + 合成 cookie）。
    fun eapiListenTogether(uri: String, data: Map<String, Any> = emptyMap()) =
        Route(uri, data, Encryption.EAPI, eapiProfile = EAPI_LISTEN_TOGETHER_PROFILE)
    // 评论写（发/回复/删除）：eapi + osx 桌面信封（同播放历史，见 EAPI_COMMENT_WRITE_PROFILE）。
    fun eapiCommentWrite(uri: String, data: Map<String, Any> = emptyMap()) =
        Route(uri, data, Encryption.EAPI, eapiProfile = EAPI_COMMENT_WRITE_PROFILE)
    // 匿名注册：eapi + PC 桌面 profile（见 EAPI_ANONIMOUS_PROFILE）。
    fun eapiAnonimous(uri: String, data: Map<String, Any> = emptyMap()) =
        Route(uri, data, Encryption.EAPI, eapiProfile = EAPI_ANONIMOUS_PROFILE)

    val commentTypePrefix = when (int("type", 0)) {
        0 -> "R_SO_4_"
        1 -> "R_MV_5_"
        2 -> "A_PL_0_"
        3 -> "R_AL_3_"
        4 -> "A_DJ_1_"
        5 -> "R_VI_62_"
        6 -> "A_EV_2_"
        7 -> "A_DR_14_"
        else -> "R_SO_4_"
    }

    return when (path) {
        // 专辑
        "/album" -> weapi("/api/v1/album/${req("id")}", emptyMap())
        "/album/detail/dynamic" -> weapi("/api/album/detail/dynamic", mapOf("id" to req("id")))
        "/album/new" -> weapi(
            "/api/album/new",
            mapOf(
                "limit" to int("limit", 30),
                "offset" to long("offset", 0),
                "total" to true,
                "area" to (str("area").ifBlank { "ALL" })
            )
        )
        "/album/sub" -> {
            val sub = (int("t", 0) == 1)
            weapi("/api/album/${if (sub) "sub" else "unsub"}", mapOf("id" to req("id")))
        }
        "/album/sublist" -> weapi(
            "/api/album/sublist",
            mapOf(
                "limit" to int("limit", 25),
                "offset" to long("offset", 0),
                "total" to true
            )
        )
        // 歌手
        "/artist/album" -> weapi(
            "/api/artist/albums/${req("id")}",
            mapOf(
                "limit" to int("limit", 30),
                "offset" to long("offset", 0),
                "total" to true
            )
        )
        "/artist/detail" -> eapi("/api/artist/head/info/get", mapOf("id" to req("id")))
        "/artist/follow/count" -> eapi("/api/artist/follow/count/get", mapOf("id" to req("id")))
        "/artist/top/song" -> weapi("/api/artist/top/song", mapOf("id" to req("id")))
        "/artist/songs" -> eapi(
            "/api/v1/artist/songs",
            mapOf(
                "id" to req("id"),
                "private_cloud" to "true",
                "work_type" to 1,
                "order" to (str("order").ifBlank { "hot" }),
                "offset" to long("offset", 0),
                "limit" to int("limit", 100)
            )
        )
        "/artist/sub" -> {
            val sub = (int("t", 0) == 1)
            weapi(
                "/api/artist/${if (sub) "sub" else "unsub"}",
                mapOf("artistId" to req("id"), "artistIds" to ("[" + str("id") + "]"))
            )
        }
        "/artist/sublist" -> weapi(
            "/api/artist/sublist",
            mapOf(
                "limit" to int("limit", 25),
                "offset" to long("offset", 0),
                "total" to true
            )
        )
        "/simi/artist" -> weapi("/api/discovery/simiArtist", mapOf("artistid" to req("id")))
        // 搜索
        "/cloudsearch" -> eapi(
            "/api/cloudsearch/pc",
            mapOf(
                "s" to req("keywords"),
                "type" to int("type", 1),
                "limit" to int("limit", 30),
                "offset" to long("offset", 0),
                "total" to true
            )
        )
        "/search/suggest" -> weapi("/api/search/suggest/web", mapOf("s" to req("keywords")))
        // 电台
        "/dj/detail" -> weapi("/api/djradio/v2/get", mapOf("id" to req("rid")))
        "/dj/program" -> weapi(
            "/api/dj/program/byradio",
            mapOf(
                "radioId" to req("rid"),
                "limit" to int("limit", 30),
                "offset" to long("offset", 0),
                "asc" to (str("asc").toBoolean())
            )
        )
        // 歌词 / 歌曲链接
        "/lyric/new" -> eapi(
            "/api/song/lyric/v1",
            mapOf(
                "id" to req("id"),
                "cp" to false,
                "tv" to 0, "lv" to 0, "rv" to 0, "kv" to 0, "yv" to 0, "ytv" to 0, "yrv" to 0
            )
        )
        "/song/url/v1" -> eapi(
            "/api/song/enhance/player/url/v1",
            mapOf(
                "ids" to ("[" + str("id") + "]"),
                "level" to (str("level").ifBlank { "standard" }),
                "encodeType" to "flac"
            )
        )
        // 音源链路 P0 补齐（2.0.2，逐条对齐 api-enhanced module/song_url.js、
        // check_music.js、song_download_url(_v1).js、song_detail.js、lyric.js）：
        // 旧版取链（br 口径）：weapi /api/song/enhance/player/url，ids 为 JSON 字符串数组。
        "/song/url" -> weapi(
            "/api/song/enhance/player/url",
            mapOf(
                "ids" to ("[" + str("id").split(',').map { it.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString(",") { "\"$it\"" } + "]"),
                "br" to int("br", 999000)
            )
        )
        // 可用性检查：与 check_music.js 同端点同参数（ playable 判定在 PlayerApi.checkMusic
        // 里按 data[0].code==200 做，与 module 的后处理一致）。
        "/check/music" -> weapi(
            "/api/song/enhance/player/url",
            mapOf(
                "ids" to ("[" + str("id") + "]"),
                "br" to int("br", 999000)
            )
        )
        // 客户端下载链（br 口径 / level 口径各一条，参数逐字对齐 module）。
        "/song/download/url" -> weapi(
            "/api/song/enhance/download/url",
            mapOf("id" to req("id"), "br" to int("br", 999000))
        )
        "/song/download/url/v1" -> weapi(
            "/api/song/enhance/download/url/v1",
            mapOf(
                "id" to req("id"),
                "immerseType" to (str("immerseType").ifBlank { "c51" }),
                "level" to (str("level").ifBlank { "standard" })
            )
        )
        // 歌曲详情（ids 逗号分隔 → c=[{"id":..},..]，对齐 song_detail.js）。
        // 批量版 /song/detail/batch 是 c 直传口，两者并存。
        "/song/detail" -> weapi(
            "/api/v3/song/detail",
            mapOf(
                "c" to ("[" + str("ids").split(',').map { it.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString(",") { "{\"id\":$it}" } + "]")
            )
        )
        // 旧版歌词（只有 LRC 系；逐字走 /lyric/new）。参数对齐 lyric.js。
        "/lyric" -> weapi(
            "/api/song/lyric",
            mapOf(
                "id" to req("id"),
                "tv" to -1, "lv" to -1, "rv" to -1, "kv" to -1, "_nmclfl" to 1
            )
        )
        // 排行榜
        "/toplist" -> eapi("/api/toplist", emptyMap())
        // 账号 / 云盘 / 收藏
        "/user/account" -> weapi("/api/nuser/account/get", emptyMap())
        // 头像上传落库：NOS 直连三步链路第三步（imgid = uploadFileToNos 拿到的 docId）。
        // 走默认 eapi（interfacepc 域 + 完整登录 cookie）；探针确认该端点存在。
        "/user/avatar/upload" -> eapi(
            "/api/user/avatar/upload/v1",
            mapOf("imgid" to req("imgid"))
        )
        // NOS 预签名 token 申请（头像/歌单封面上传第一步，对齐代理 plugins/upload.js）。
        "/nos/token/alloc" -> eapi(
            "/api/nos/token/alloc",
            mapOf(
                "bucket" to str("bucket").ifBlank { "yyimgs" },
                "ext" to str("ext").ifBlank { "jpg" },
                "filename" to req("filename"),
                "local" to false,
                "nos_product" to 0,
                "return_body" to str("return_body").ifBlank { "{\"code\":200,\"size\":\"$(ObjectSize)\"}" },
                "type" to str("type").ifBlank { "other" }
            )
        )
        // 用户资料修改（我的页点昵称）：eapi 写接口，需合成 cookie profile（见 send 的 cookie 分支）。
        // 字段对齐代理 user_update.js：只透传调用方给的字段。本功能只改昵称/签名，
        // 故只发 nickname + signature —— gender/birthday/province/city 不带，NCM 视作不动，避免误清账号地区/生日。
        "/user/update" -> eapiUserUpdate(
            "/api/user/profile/update",
            mapOf(
                "nickname" to req("nickname"),
                "signature" to str("signature")
            )
        )
        "/user/cloud" -> weapi(
            "/api/v1/cloud/get",
            mapOf("limit" to int("limit", 30), "offset" to long("offset", 0))
        )
        "/user/detail" -> weapi("/api/v1/user/detail/${req("uid")}", emptyMap())
        "/user/follows" -> weapi(
            "/api/user/getfollows/${req("uid")}",
            mapOf(
                "offset" to long("offset", 0),
                "limit" to int("limit", 30),
                "order" to true
            )
        )
        "/user/record" -> weapi(
            "/api/v1/play/record",
            mapOf("uid" to req("uid"), "type" to int("type", 0))
        )
        "/user/playlist" -> weapi(
            "/api/user/playlist",
            mapOf(
                "uid" to req("uid"),
                "limit" to int("limit", 30),
                "offset" to long("offset", 0),
                "includeVideo" to true
            )
        )
        "/likelist" -> {
            val uid = p["uid"]
            eapi(
                "/api/song/like/get",
                if (uid == null) emptyMap() else mapOf("uid" to long("uid", 0))
            )
        }
        "/follow" -> {
            val follow = (int("t", 0) == 1)
            weapi("/api/user/${if (follow) "follow" else "delfollow"}/${req("id")}", emptyMap())
        }
        // 歌曲点赞
        "/song/like" -> {
            val data = mutableMapOf<String, Any>(
                "trackId" to req("id"),
                "like" to str("like").toBoolean()
            )
            if (p["uid"] != null) data["userid"] = long("uid", 0)
            eapi("/api/song/like", data)
        }
        "/song/like/check" -> eapi("/api/song/like/check", mapOf("trackIds" to req("ids")))
        // 歌单
        "/playlist/detail" -> eapi(
            "/api/v6/playlist/detail",
            mapOf("id" to req("id"), "n" to 100000, "s" to int("s", 8))
        )
        "/playlist/detail/dynamic" -> eapi(
            "/api/playlist/detail/dynamic",
            mapOf("id" to req("id"), "n" to 100000, "s" to int("s", 8))
        )
        "/playlist/subscribe" -> {
            val sub = (int("t", 0) == 1)
            eapi("/api/playlist/${if (sub) "subscribe" else "unsubscribe"}", mapOf("id" to req("id")))
        }
        // 歌单封面更新落库：NOS 直连三步链路第三步（coverImgId = uploadFileToNos 拿到的 docId）。
        // 走默认 eapi（interfacepc 域 + 完整登录 cookie）；探针确认该端点存在。
        "/playlist/cover/update" -> eapi(
            "/api/playlist/cover/update",
            mapOf(
                "id" to req("id"),
                "coverImgId" to req("coverImgId")
            )
        )
        "/playlist/create" -> weapi(
            "/api/playlist/create",
            mapOf(
                "name" to req("name"),
                "privacy" to (str("privacy").ifBlank { "0" }),
                "type" to (str("type").ifBlank { "NORMAL" })
            )
        )
        "/playlist/delete" -> weapi("/api/playlist/remove", mapOf("ids" to ("[" + str("id") + "]")))
        "/playlist/name/update" -> eapi(
            "/api/playlist/update/name",
            mapOf("id" to req("id"), "name" to req("name"))
        )
        "/playlist/desc/update" -> eapi(
            "/api/playlist/desc/update",
            mapOf("id" to req("id"), "desc" to str("desc"))
        )
        "/playlist/order/update" -> weapi("/api/playlist/order/update", mapOf("ids" to req("ids")))
        "/song/order/update" -> eapi(
            "/api/playlist/manipulate/tracks",
            mapOf("pid" to req("pid"), "trackIds" to str("ids"), "op" to "update")
        )
        "/playlist/tracks" -> {
            val ids = str("tracks").split(',').filter { it.isNotBlank() }
            val trackIds = if (ids.isEmpty()) "" else "[" + ids.joinToString(",") + "]"
            eapi(
                "/api/playlist/manipulate/tracks",
                mapOf(
                    "op" to (str("op").ifBlank { "add" }),
                    "pid" to req("pid"),
                    "trackIds" to trackIds,
                    "imme" to "true"
                )
            )
        }
        // 推荐
        "/recommend/songs" -> {
            val data = mutableMapOf<String, Any>()
            if (p["afresh"] != null) data["afresh"] = str("afresh").toBoolean()
            weapi("/api/v3/discovery/recommend/songs", data)
        }
        "/personalized" -> weapi(
            "/api/personalized/playlist",
            mapOf(
                "limit" to int("limit", 30),
                "total" to true,
                "n" to 1000
            )
        )
        "/personal_fm" -> weapi("/api/v1/radio/get", emptyMap())
        "/personal/fm/mode" -> {
            val data = mutableMapOf<String, Any>(
                "mode" to req("mode"),
                "limit" to int("limit", 3)
            )
            if (p["submode"] != null) data["subMode"] = str("submode")
            eapi("/api/v1/radio/get", data)
        }
        // 听歌记录（读端点）
        "/record/recent/song" -> weapi(
            "/api/play-record/song/list",
            mapOf("limit" to int("limit", 100))
        )
        "/record/recent/album" -> weapi(
            "/api/play-record/album/list",
            mapOf("limit" to int("limit", 100))
        )
        "/record/recent/playlist" -> weapi(
            "/api/play-record/playlist/list",
            mapOf("limit" to int("limit", 100))
        )
        // 听歌打卡（写端点）：eapi feedback/weblog，osx 桌面 profile。
        // 调用方一次性提交 startplay + play 两条日志（对齐 MeiloX 双事件模型）。
        "/scrobble/weblog" -> eapiHistory(
            "/api/feedback/weblog",
            mapOf("logs" to str("logs"))
        )
        // 评论
        "/comment/new" -> {
            val id = req("id")
            var sortType = int("sortType", 99)
            if (sortType == 1) sortType = 99
            val pageNo = int("pageNo", 1)
            val pageSize = int("pageSize", 20)
            val cursor = when (sortType) {
                99 -> ((pageNo - 1) * pageSize).toString()
                2 -> "normalHot#" + ((pageNo - 1) * pageSize)
                3 -> str("cursor").ifBlank { "0" }
                else -> ""
            }
            val threadId = if (str("type") == "6") str("threadId") else commentTypePrefix + id
            eapi(
                "/api/v2/resource/comments",
                mapOf(
                    "threadId" to threadId,
                    "pageNo" to pageNo,
                    "showInner" to true,
                    "pageSize" to pageSize,
                    "cursor" to cursor,
                    "sortType" to sortType
                )
            )
        }
        "/comment/like" -> {
            val id = req("id")
            val like = (int("t", 0) == 1)
            val threadId = if (str("type") == "6") str("threadId") else commentTypePrefix + id
            weapi(
                "/api/v1/comment/${if (like) "like" else "unlike"}",
                mapOf("threadId" to threadId, "commentId" to req("cid"))
            )
        }
        // 楼中楼（代理 comment_floor.js）：weapi /api/resource/comment/floor/get。
        // 翻页：time 传上一页最后一项的 time（毫秒），缺省 -1 取第一页；limit 默认 20。
        "/comment/floor" -> {
            val id = req("id")
            val threadId = if (str("type") == "6") str("threadId") else commentTypePrefix + id
            weapi(
                "/api/resource/comment/floor/get",
                mapOf(
                    "parentCommentId" to req("parentCommentId"),
                    "threadId" to threadId,
                    "time" to long("time", -1L),
                    "limit" to int("limit", 20)
                )
            )
        }
        // 发评论（type 6 动态用 threadId，其余用 前缀+id）；body 对齐代理 comment_add.js。
        "/comment/add" -> {
            val id = str("id")
            val threadId = if (str("type") == "6") str("threadId") else commentTypePrefix + id
            eapiCommentWrite(
                "/api/resource/comments/add",
                mapOf(
                    "threadId" to threadId,
                    "content" to req("content"),
                    "resourceType" to "0",
                    "expressionPicId" to "-1",
                    "bubbleId" to "-1"
                )
            )
        }
        // 回复评论：同 /comment/add 端点 + commentId（对齐代理 comment_reply.js）。
        "/comment/reply" -> {
            val id = str("id")
            val threadId = if (str("type") == "6") str("threadId") else commentTypePrefix + id
            eapiCommentWrite(
                "/api/v1/resource/comments/reply",
                mapOf(
                    "threadId" to threadId,
                    "commentId" to req("cid"),
                    "content" to req("content"),
                    "resourceType" to "0"
                )
            )
        }
        // 删除评论：body 对齐代理 comment_delete.js（threadId + commentId，代理传 query.cid）。
        "/comment/delete" -> {
            val id = str("id")
            val threadId = if (str("type") == "6") str("threadId") else commentTypePrefix + id
            eapiCommentWrite(
                "/api/resource/comments/delete",
                mapOf("threadId" to threadId, "commentId" to req("cid"))
            )
        }
        // 消息中心
        "/msg/comments" -> weapi(
            "/api/v1/user/comments/${req("uid")}",
            mapOf(
                "beforeTime" to (str("before").ifBlank { "-1" }),
                "limit" to int("limit", 30),
                "total" to "true",
                "uid" to req("uid")
            )
        )
        "/msg/forwards" -> weapi(
            "/api/forwards/get",
            mapOf("offset" to long("offset", 0), "limit" to int("limit", 30), "total" to "true")
        )
        "/msg/notices" -> weapi(
            "/api/msg/notices",
            mapOf("limit" to int("limit", 30), "time" to long("lasttime", -1))
        )
        "/msg/private" -> weapi(
            "/api/msg/private/users",
            mapOf("offset" to long("offset", 0), "limit" to int("limit", 30), "total" to "true")
        )
        "/msg/private/history" -> weapi(
            "/api/msg/private/history",
            mapOf(
                "userId" to req("uid"),
                "limit" to int("limit", 30),
                "time" to long("before", 0),
                "total" to "true"
            )
        )
        "/msg/recentcontact" -> weapi("/api/msg/recentcontact/get", emptyMap())
        "/send/song" -> eapi(
            "/api/msg/private/send",
            mapOf(
                "id" to req("id"),
                "msg" to str("msg"),
                "type" to "song",
                "userIds" to ("[" + str("user_ids") + "]")
            )
        )
        "/send/album" -> eapi(
            "/api/msg/private/send",
            mapOf(
                "id" to req("id"),
                "msg" to str("msg"),
                "type" to "album",
                "userIds" to ("[" + str("user_ids") + "]")
            )
        )
        "/send/playlist" -> eapi(
            "/api/msg/private/send",
            mapOf(
                "id" to req("playlist"),
                "type" to "playlist",
                "msg" to str("msg"),
                "userIds" to ("[" + str("user_ids") + "]")
            )
        )
        "/send/text" -> eapi(
            "/api/msg/private/send",
            mapOf(
                "type" to "text",
                "msg" to str("msg"),
                "userIds" to ("[" + str("user_ids") + "]")
            )
        )
        // 一起听（listen together）：状态读走 weapi，房间写 / 同步 / 心跳走 eapi（对齐 MeiloX 通道选择）。
        // 上游路径逐条对齐 api-enhanced 代理 module/listentogether_*.js（heartbeat 拼写为 NCM 原样 /heartbeat）。
        "/listen/together/status" -> weapi("/api/listen/together/status/get", emptyMap())
        "/listen/together/room/create" -> eapiListenTogether(
            "/api/listen/together/room/create",
            mapOf("refer" to str("refer").ifBlank { "songplay_more" })
        )
        "/listen/together/room/check" -> eapiListenTogether(
            "/api/listen/together/room/check",
            mapOf("roomId" to req("roomId"))
        )
        "/listen/together/invitation/accept" -> eapiListenTogether(
            "/api/listen/together/play/invitation/accept",
            mapOf(
                "refer" to str("refer").ifBlank { "inbox_invite" },
                "roomId" to req("roomId"),
                "inviterId" to req("inviterId")
            )
        )
        "/listen/together/play/command" -> eapiListenTogether(
            "/api/listen/together/play/command/report",
            mapOf("roomId" to req("roomId"), "commandInfo" to req("commandInfo"))
        )
        "/listen/together/sync/playlist" -> eapiListenTogether(
            "/api/listen/together/sync/playlist/get",
            mapOf("roomId" to req("roomId"))
        )
        "/listen/together/sync/list" -> eapiListenTogether(
            "/api/listen/together/sync/list/command/report",
            mapOf("roomId" to req("roomId"), "playlistParam" to req("playlistParam"))
        )
        "/listen/together/heartbeat" -> eapiListenTogether(
            "/api/listen/together/heartbeat",
            mapOf(
                "roomId" to req("roomId"),
                "songId" to req("songId"),
                "playStatus" to req("playStatus"),
                "progress" to req("progress")
            )
        )
        "/listen/together/end" -> eapiListenTogether(
            "/api/listen/together/end/v2",
            mapOf("roomId" to req("roomId"))
        )
        // 一起听远端队列重建：歌曲批量详情（NCM /v3/song/detail，c 为 [{id:...}] JSON 字符串）
        "/song/detail/batch" -> weapi(
            "/api/v3/song/detail",
            mapOf("c" to str("c"))
        )
        // ===== 2.0.2 数据层批量补齐（逐条对齐 api-enhanced module 的 uri/参数） =====
        // 搜索补充
        "/search" -> weapi(
            "/api/cloudsearch/pc",
            mapOf(
                "s" to req("keywords"), "type" to int("type", 1),
                "limit" to int("limit", 30), "offset" to int("offset", 0), "total" to true
            )
        )
        "/search/default" -> weapi("/api/search/defaultkeyword", emptyMap())
        "/search/hot/detail" -> weapi("/api/hotsearchlist/get", emptyMap())
        "/search/multimatch" -> weapi(
            "/api/search/suggest/multimatch",
            mapOf("type" to int("type", 1), "s" to req("keywords"))
        )
        // 榜单 / 歌单广场
        "/toplist/detail" -> weapi("/api/toplist/detail", emptyMap())
        "/toplist/artist" -> weapi(
            "/api/toplist/artist",
            mapOf(
                "type" to int("type", 1), "limit" to int("limit", 100),
                "offset" to int("offset", 0), "total" to true
            )
        )
        "/playlist/catlist" -> eapi("/api/playlist/catalogue", emptyMap())
        "/top/playlist" -> weapi(
            "/api/playlist/list",
            mapOf(
                "cat" to (str("cat").ifBlank { "全部" }),
                "order" to (str("order").ifBlank { "hot" }),
                "limit" to int("limit", 50), "offset" to int("offset", 0), "total" to true
            )
        )
        "/top/playlist/highquality" -> weapi(
            "/api/playlist/highquality/list",
            mapOf(
                "cat" to (str("cat").ifBlank { "全部" }),
                "limit" to int("limit", 50), "lasttime" to long("lasttime", 0), "total" to true
            )
        )
        "/playlist/highquality/tags" -> weapi("/api/playlist/highquality/tags", emptyMap())
        // 个性化 / 推荐补充
        "/personalized/newsong" -> weapi(
            "/api/personalized/newsong",
            mapOf("limit" to int("limit", 10), "areaId" to int("areaId", 0), "total" to true)
        )
        "/personalized/mv" -> weapi("/api/personalized/mv", emptyMap())
        "/personalized/djprogram" -> weapi("/api/personalized/djprogram", emptyMap())
        "/personalized/privatecontent" -> weapi("/api/personalized/privatecontent", emptyMap())
        "/recommend/resource" -> weapi("/api/v1/discovery/recommend/resource", emptyMap())
        "/history/recommend/songs" -> weapi(
            "/api/discovery/recommend/songs/history/recent", emptyMap()
        )
        // 签到（type: 0=安卓端 1=web/PC 端，对齐 daily_signin.js）
        "/daily_signin" -> weapi("/api/point/dailyTask", mapOf("type" to int("type", 0)))
        "/signin/progress" -> weapi(
            "/api/act/modules/signin/v2/progress",
            mapOf("moduleId" to (str("moduleId").ifBlank { "1207signin-1207timeline" }))
        )
        // 私人 FM 补充
        "/fm/trash" -> weapi(
            "/api/radio/trash/add",
            mapOf("songId" to req("id"), "time" to int("time", 25))
        )
        "/radio/like" -> weapi(
            "/api/radio/like",
            mapOf(
                "alg" to (str("alg").ifBlank { "itembased" }),
                "trackId" to req("trackId"), "like" to (str("like") != "false"), "time" to int("time", 25)
            )
        )
        // 云盘删除 / 详情（列表走已有 /user/cloud）
        "/user/cloud/del" -> weapi(
            "/api/cloud/del",
            mapOf("songIds" to ("[" + str("id") + "]"))
        )
        "/user/cloud/detail" -> weapi(
            "/api/v1/cloud/get/byids",
            mapOf("songIds" to ("[" + str("id") + "]"))
        )
        // 云盘上传四步链（2.0.5 数据层，对齐 api-enhanced module/cloud.js）：
        // check（md5 查重/秒传判定）→ /nos/token/alloc（bucket=
        // jd-musicrep-privatecloud-audio-public, nos_product=3, type=audio，
        // 走既有 /nos/token/alloc 路由传参）→ NOS 裸上传 → info 落元数据
        // → pub 发布。info/pub 的 songid 均取 check/token 阶段返回的云盘 songId。
        "/cloud/upload/check" -> eapi(
            "/api/cloud/upload/check",
            mapOf(
                "bitrate" to int("bitrate", 999000),
                "ext" to (str("ext").ifBlank { "mp3" }),
                "length" to long("length", 0),
                "md5" to req("md5"),
                "songId" to long("songId", 0),
                "version" to 1
            )
        )
        "/cloud/upload/info" -> eapi(
            "/api/cloud/upload/info/v2",
            mapOf(
                "md5" to req("md5"),
                "songid" to req("songid"),
                "filename" to str("filename"),
                "song" to str("song"),
                "album" to str("album"),
                "artist" to str("artist"),
                "bitrate" to int("bitrate", 999000),
                "resourceId" to req("resourceId")
            )
        )
        "/cloud/pub" -> weapi(
            "/api/cloud/pub/v2",
            mapOf("songid" to req("songid"))
        )
        "/cloud/import" -> weapi(
            "/api/v1/cloud/import",
            mapOf("songId" to req("songId"), "uid" to req("uid"))
        )
        // 用户补充
        "/user/subcount" -> weapi("/api/subcount", emptyMap())
        "/user/level" -> weapi("/api/user/level", emptyMap())
        "/event" -> weapi(
            "/api/v1/event/get",
            mapOf("pagesize" to int("pagesize", 20), "lasttime" to long("lasttime", -1))
        )
        // 心动模式
        "/playmode/intelligence/list" -> weapi(
            "/api/playmode/intelligence/list",
            mapOf(
                "songId" to req("id"), "playlistId" to req("pid"),
                "startMusicId" to (str("sid").ifBlank { str("id") }), "count" to int("count", 1)
            )
        )
        // MV / 视频（数据层；桌面引擎无视频渲染管线，UI 不接入，见 PORTING §7.11）
        "/mv/detail" -> weapi("/api/v1/mv/detail", mapOf("id" to req("mvid")))
        "/mv/url" -> weapi(
            "/api/song/enhance/play/mv/url",
            mapOf("id" to req("id"), "r" to int("r", 1080))
        )
        "/mv/sub" -> weapi(
            "/api/mv/sub",
            mapOf("mvid" to req("mvid"), "t" to int("t", 1))
        )
        "/mv/sublist" -> weapi(
            "/api/cloudvideo/allvideo/sublist",
            mapOf("limit" to int("limit", 25), "offset" to int("offset", 0), "total" to true)
        )
        "/mv/first" -> weapi(
            "/api/mv/first",
            mapOf(
                "area" to (str("area").ifBlank { "全部" }),
                "limit" to int("limit", 30), "total" to true
            )
        )
        "/top/mv" -> weapi(
            "/api/mv/toplist",
            mapOf(
                "area" to (str("area").ifBlank { "全部" }),
                "limit" to int("limit", 30), "offset" to int("offset", 0), "total" to true
            )
        )
        "/simi/mv" -> weapi("/api/discovery/simiMV", mapOf("mvid" to req("mvid")))
        "/video/detail" -> weapi("/api/cloudvideo/v1/video/detail", mapOf("id" to req("id")))
        "/video/url" -> weapi(
            "/api/cloudvideo/playurl",
            mapOf(
                "ids" to ("[\"" + str("id").replace(",", "\",\"") + "\"]"),
                "resolution" to int("resolution", 1080)
            )
        )
        "/video/group/list" -> weapi("/api/cloudvideo/group/list", emptyMap())
        "/video/category/list" -> weapi(
            "/api/cloudvideo/category/list",
            mapOf("limit" to int("limit", 30), "offset" to int("offset", 0), "total" to true)
        )
        "/video/timeline/recommend" -> weapi(
            "/api/videotimeline/get",
            mapOf(
                "offset" to int("offset", 0), "filterLives" to true,
                "withProgramInfo" to true, "needUrl" to true, "resolution" to 720
            )
        )
        // 歌手补充
        "/artist/desc" -> weapi("/api/artist/introduction", mapOf("id" to req("id")))
        "/artist/list" -> weapi(
            "/api/v1/artist/list",
            mapOf(
                "initial" to (str("initial").takeIf { it.isNotBlank() }
                    ?.let { s -> s.toIntOrNull() ?: s.uppercase().first().code } ?: -1),
                "offset" to int("offset", 0), "limit" to int("limit", 30),
                "type" to (str("type").ifBlank { "1" }), "area" to int("area", -1)
            )
        )
        "/artist/mv" -> weapi(
            "/api/artist/mvs",
            mapOf(
                "artistId" to req("id"), "limit" to int("limit", 30),
                "offset" to int("offset", 0), "total" to true
            )
        )
        "/artists" -> weapi("/api/v1/artist/" + req("id"), emptyMap())
        // 专辑补充
        "/album/newest" -> weapi("/api/discovery/newAlbum", emptyMap())
        "/album/privilege" -> weapi("/api/album/privilege", mapOf("id" to req("id")))
        // 电台补充
        "/dj/catelist" -> weapi("/api/djradio/category/get", emptyMap())
        "/dj/category/recommend" -> weapi("/api/djradio/home/category/recommend", emptyMap())
        "/dj/recommend/type" -> weapi("/api/djradio/recommend", mapOf("cateId" to req("type")))
        "/dj/today/perfered" -> weapi(
            "/api/djradio/home/today/perfered", mapOf("page" to int("page", 0))
        )
        "/dj/sublist" -> weapi(
            "/api/djradio/get/subed",
            mapOf("limit" to int("limit", 30), "offset" to int("offset", 0), "total" to true)
        )
        "/dj/sub" -> weapi("/api/djradio/sub", mapOf("rid" to req("rid"), "t" to int("t", 1)))
        "/dj/personalize/recommend" -> weapi(
            "/api/djradio/personalize/rcmd",
            mapOf("limit" to int("limit", 6))
        )
        "/dj/program/toplist" -> weapi(
            "/api/program/toplist/v1",
            mapOf("limit" to int("limit", 100), "offset" to int("offset", 0))
        )
        "/dj/banner" -> weapi("/api/djradio/banner/get", emptyMap())
        // 已购单曲
        "/song/purchased" -> weapi(
            "/api/single/mybought/song/list",
            mapOf("limit" to int("limit", 20), "offset" to int("offset", 0))
        )
        // ===== 2.0.2 数据层第二批（首页/歌手动态/电台榜单/动态写操作等） =====
        "/banner" -> weapi(
            "/api/v2/banner/get",
            mapOf(
                "clientType" to when (int("type", 0)) {
                    1 -> "android"; 2 -> "iphone"; 3 -> "ipad"; else -> "pc"
                }
            )
        )
        "/homepage/block/page" -> weapi(
            "/api/homepage/block/page",
            mapOf("refresh" to (str("refresh") == "true"), "cursor" to str("cursor"))
        )
        "/homepage/dragon/ball" -> weapi("/api/homepage/dragon/ball/static", emptyMap())
        "/artist/fans" -> weapi(
            "/api/artist/fans/get",
            mapOf("id" to req("id"), "limit" to int("limit", 20), "offset" to int("offset", 0))
        )
        "/artist/new/song" -> weapi(
            "/api/sub/artist/new/works/song/list",
            mapOf(
                "limit" to int("limit", 20),
                "startTimestamp" to long("before", System.currentTimeMillis())
            )
        )
        "/artist/new/mv" -> weapi(
            "/api/sub/artist/new/works/mv/list",
            mapOf(
                "limit" to int("limit", 20),
                "startTimestamp" to long("before", System.currentTimeMillis())
            )
        )
        "/artist/video" -> weapi(
            "/api/mlog/artist/video",
            mapOf(
                "artistId" to req("id"),
                "page" to "{\"size\":${int("size", 8)},\"cursor\":${long("cursor", 0)}}"
            )
        )
        "/dj/toplist" -> weapi(
            "/api/djradio/toplist",
            mapOf(
                "limit" to int("limit", 100), "offset" to int("offset", 0),
                "type" to int("type", 0)
            )
        )
        "/dj/toplist/hours" -> weapi(
            "/api/dj/toplist/hours", mapOf("limit" to int("limit", 100))
        )
        "/dj/toplist/popular" -> weapi(
            "/api/dj/toplist/popular", mapOf("limit" to int("limit", 100))
        )
        "/dj/toplist/newcomer" -> weapi(
            "/api/dj/toplist/newcomer",
            mapOf("limit" to int("limit", 100), "offset" to int("offset", 0))
        )
        "/dj/subscriber" -> weapi(
            "/api/djradio/subscriber",
            mapOf(
                "time" to long("time", -1), "id" to req("id"),
                "limit" to int("limit", 20), "total" to true
            )
        )
        "/dj/program/toplist/hours" -> weapi(
            "/api/djprogram/toplist/hours", mapOf("limit" to int("limit", 100))
        )
        "/dj/program/detail" -> weapi("/api/dj/program/detail", mapOf("id" to req("id")))
        "/event/del" -> weapi("/api/event/delete", mapOf("id" to req("id")))
        "/event/forward" -> weapi(
            "/api/event/forward",
            mapOf(
                "forwards" to str("forwards"), "id" to req("id"),
                "eventUserId" to req("eventUserId")
            )
        )
        "/cellphone/existence/check" -> eapi(
            "/api/cellphone/existence/check",
            mapOf("cellphone" to req("phone"), "countrycode" to int("countrycode", 86))
        )
        "/history/recommend/songs/detail" -> weapi(
            "/api/discovery/recommend/songs/history/detail",
            mapOf("date" to str("date"))
        )
        "/comment/hug/list" -> weapi(
            "/api/v2/resource/comments/hug/list",
            mapOf(
                "targetUserId" to req("targetUserId"), "commentId" to req("commentId"),
                "threadId" to req("threadId"), "pageNo" to int("pageNo", 1),
                "pageSize" to int("pageSize", 20), "cursor" to long("cursor", -1),
                "idCursor" to long("idCursor", -1)
            )
        )
        "/like" -> weapi(
            "/api/v1/radio/like",
            mapOf(
                "alg" to (str("alg").ifBlank { "itembased" }),
                "trackId" to req("trackId"), "like" to (str("like") != "false"),
                "time" to int("time", 25)
            )
        )
        // ===== 2.0.2 数据层第三批（相似推荐/歌单维护/评论历史） =====
        "/simi/song" -> weapi(
            "/api/v1/discovery/simiSong",
            mapOf("songid" to req("id"), "limit" to int("limit", 50), "offset" to int("offset", 0))
        )
        "/simi/playlist" -> weapi(
            "/api/discovery/simiPlaylist",
            mapOf("songid" to req("id"), "limit" to int("limit", 50), "offset" to int("offset", 0))
        )
        "/playlist/subscribers" -> weapi(
            "/api/playlist/subscribers",
            mapOf("id" to req("id"), "limit" to int("limit", 20), "offset" to int("offset", 0))
        )
        "/playlist/privacy" -> weapi(
            "/api/playlist/update/privacy",
            mapOf("id" to req("id"), "privacy" to int("privacy", 0))
        )
        "/playlist/tags/update" -> weapi(
            "/api/playlist/tags/update",
            mapOf("id" to req("id"), "tags" to str("tags"))
        )
        "/user/comment/history" -> weapi(
            "/api/comment/user/comment/history",
            mapOf(
                "user_id" to req("uid"), "limit" to int("limit", 20),
                "time" to long("time", 0), "compose_reminder" to true,
                "compose_hot_comment" to true
            )
        )

        // 登录（桌面版 2.0 新增；2.0.3 按 api-enhanced module 源码逐参数再对齐：
        // login_qr_key/login_qr_check 的 type=3（旧值 1 是老版 API 的缺省）、
        // captcha_sent 带 secrete、login_cellphone/login 走 /api/w/login* 新端点）：
        // 扫码：先取 unikey，二维码内容由客户端拼 https://music.163.com/login?codekey=<unikey>，
        // 再轮询 client/login（800 过期 / 801 待扫 / 802 已扫待确认 / 803 成功，cookie 在 Set-Cookie）。
        "/login/qr/key" -> weapi("/api/login/qrcode/unikey", mapOf("type" to 3))
        "/login/qr/check" -> weapi(
            "/api/login/qrcode/client/login",
            mapOf("key" to req("key"), "type" to 3)
        )
        // 匿名注册（游客令牌 MUSIC_A）：register_anonimous.js 的 username 编码
        // 在 DeviceIdentity.anonymousUsername；走 eapi（原版 API 的加密方式，
        // 本机 Enhanced 分支的 xeapi 需其自有 neapi 密钥体系，不适用直连客户端）。
        "/register/anonimous" -> eapiAnonimous(
            "/api/register/anonimous",
            mapOf("username" to req("username"))
        )

        "/captcha/sent" -> weapi(
            "/api/sms/captcha/sent",
            mapOf(
                "cellphone" to req("phone"),
                // captcha_sent.js 固定字段（PC 登录中间页标识），旧实现缺失。
                "secrete" to "music_middleuser_pclogin",
                "ctcode" to (str("ctcode").ifBlank { "86" })
            )
        )
        "/captcha/verify" -> weapi(
            "/api/sms/captcha/verify",
            mapOf(
                "cellphone" to req("phone"),
                "captcha" to req("captcha"),
                "ctcode" to (str("ctcode").ifBlank { "86" })
            )
        )
        // 手机号登录（login_cellphone.js：/api/w/login/cellphone，
        // type/https/remember/secureCaptcha 均为字符串字段；captcha 与
        // password 二选一由调用方决定，sca 透传易盾校验票据、缺省空串）。
        "/login/cellphone" -> weapi(
            "/api/w/login/cellphone",
            buildMap {
                put("type", "1")
                put("https", "true")
                put("phone", req("phone"))
                put("countrycode", str("ctcode").ifBlank { "86" })
                if (p["captcha"] != null) put("captcha", str("captcha"))
                if (p["password"] != null) put("password", str("password"))
                put("remember", "true")
                put("secureCaptcha", str("sca"))
            }
        )
        // 邮箱/账号密码登录（login.js：/api/w/login，password 为已 md5 值）。
        "/login" -> weapi(
            "/api/w/login",
            mapOf(
                "type" to "0",
                "https" to "true",
                "username" to req("username"),
                "password" to req("password"),
                "rememberLogin" to "true"
            )
        )
        // 电台推荐（桌面侧栏「电台」页数据源；老路由表没有，新增）。
        "/dj/recommend" -> weapi("/api/djradio/recommend", emptyMap())
        "/dj/hot" -> weapi(
            "/api/djradio/hot",
            buildMap {
                put("limit", int("limit", 30))
                put("offset", long("offset", 0))
                // cateId 传了才带：探针实测带 cateId=0 返回空列表，不带才有热门电台。
                if (p["cateId"] != null) put("cateId", int("cateId", 0))
            }
        )
        else -> error("Unsupported NetEase route: $path")
    }
}

/** 通用 /api 透传（现仅用于 /user/getfolloweds）：uri 直传、data 为 JSON 字符串，走 eapi。 */
@PublishedApi
internal suspend fun sendGenericPassthrough(p: Map<String, Any>): String {
    val uri = p["uri"]?.toString() ?: error("Generic /api passthrough missing uri")
    val dataStr = p["data"]?.toString()?.ifBlank { "{}" } ?: "{}"
    val element = runCatching { json.parseToJsonElement(dataStr) }.getOrNull()
    val data = (element as? JsonObject)?.mapValues { it.value } ?: emptyMap<String, JsonElement>()
    val finalData = data.entries.associate { it.key to (it.value as Any) }
    return send(Route(uri, finalData, Encryption.EAPI))
}

/** MeiloX encodeCookieComponent：cookie 键值 URL-encode（+→%20，%7E→~）。 */
private fun encodeCookieComponent(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8")
        .replace("+", "%20")
        .replace("%7E", "~")

/** 匿名注册上次尝试时刻（进程内节流，见 ensureAnonymousToken）。 */
@Volatile
private var lastAnonimousAttemptMs = 0L

/**
 * 确保匿名令牌 MUSIC_A 就绪（2.0.3）：jar 里已有、或已登录（有 MUSIC_U）
 * 时直接返回；否则调 /api/register/anonimous 用持久 deviceId 换取，响应
 * Set-Cookie 由 sendWithCookies 的登录分支自动吸收进 DeviceIdentity jar
 * 并落盘。失败只记诊断日志，不抛（登录主链继续，风控与否由业务码判定）。
 * 节流：注册接口对同 IP 有频率限制（沙箱实测：短时间多次注册后回
 * {"code":400}），故进程内 10 分钟最多真发一次——扫码轮询等高频登录
 * 请求不许反复敲它。
 */
@PublishedApi
internal suspend fun ensureAnonymousToken() {
    if (DeviceIdentity.hasMusicA()) return
    if (CookieProvider.getCookieMap().containsKey("MUSIC_U")) return
    val now = System.currentTimeMillis()
    if (now - lastAnonimousAttemptMs < 10 * 60 * 1000L) return
    lastAnonimousAttemptMs = now
    runCatching {
        val route = resolveRoute(
            "/register/anonimous",
            mapOf("username" to DeviceIdentity.anonymousUsername())
        )
        sendWithCookies(route)
    }.onFailure {
        AuthDiagnostics.log("register/anonimous failed: ${it.message}")
    }
    AuthDiagnostics.log(
        "anonimous attempt musicA=${DeviceIdentity.hasMusicA()} " +
            "jarKeys=${DeviceIdentity.warmCookies().keys.sorted()}"
    )
}

@PublishedApi
internal suspend fun send(route: Route): String = sendWithCookies(route).first

@PublishedApi
internal suspend fun sendWithCookies(route: Route): Pair<String, List<String>> {
    val isLogin = isLoginRoute(route.uri)
    if (isLogin) {
        // 登录前预热种 Cookie（每进程实际只跑一次；阻塞 IO 切到 IO 调度器）
        withContext(Dispatchers.IO) { DeviceIdentity.warmUpBlocking() }
        // 2.0.3：登录前确保匿名令牌 MUSIC_A 在 jar 里（官方 API 客户端的标准
        // 预热；缺 MUSIC_A 是真机「环境异常」的常见触发点之一）。匿名注册
        // 路由自身跳过，避免递归；失败静默（登录照常走，业务码透传）。
        if (route.uri != "/api/register/anonimous") {
            ensureAnonymousToken()
        }
    }
    val cookie = CookieProvider.getCookieMap()
    val csrf = cookie["__csrf"].orEmpty()
    // 登录链的 csrf 以预热 jar 为准（匿名时会话里还没有 __csrf）
    val effectiveCsrf = if (isLogin) {
        csrf.ifEmpty { DeviceIdentity.warmCookies()["__csrf"].orEmpty() }
    } else {
        csrf
    }
    val musicU = cookie["MUSIC_U"]
    val osValue = cookie["os"] ?: "android"
    val profile = route.eapiProfile
    // 仅「播放历史 osx profile」(os=osx) 走 osx 域/UA/空 csrf/去 mobilename。
    // 其余 eapi profile（如用户资料修改）保持移动端默认，只借用「合成 cookie」行为。
    val isHistoryProfile = profile?.get("os")?.toString() == "osx"
    // 一起听 eapi 的 MeiloX 同款 19 字段设备身份 cookie（NMDI/URS_APPID/WNMCID/NMTID/
    // _ntes_nuid/sDeviceId/WEVNSM/ntes_kaola_ad/__csrf=常量，与加密 header 同源）。
    val isLtProfile = profile?.get("ltDeviceCookie") == true
    // 合成 cookie（对齐代理 createHeaderCookie）：播放历史 + 用户资料修改都发它，
    // 完整登录 cookie 只在普通 eapi/weapi 读接口用。eapi 写接口发完整登录 cookie 会被 NCM 403。
    val synthesizedCookie = isHistoryProfile || profile?.get("synthesizedCookie") == true

    // eapi 身份 header（对齐代理 createHeaderCookie 的字段集）
    // 播放历史 profile：osx 桌面参数（对齐 MeiloX），__csrf 置空、buildver 用秒级时间戳。
    val header = buildMap<String, Any> {
        put("osver", profile?.get("osver")?.toString() ?: cookie["osver"] ?: "14")
        put("deviceId", cookie["deviceId"] ?: DeviceIdentity.deviceId)
        put("os", profile?.get("os")?.toString() ?: osValue)
        put("appver", profile?.get("appver")?.toString() ?: cookie["appver"] ?: "9.4.32.251222163637")
        put("versioncode", profile?.get("versioncode")?.toString() ?: cookie["versioncode"] ?: "6006066")
        if (!isHistoryProfile) {
            // 一起听桌面 profile 带 mobilename（对齐 MeiloX EAPI_CONFIG）；否则移动端默认。
            put("mobilename", profile?.get("mobilename")?.toString() ?: cookie["mobilename"] ?: "")
        }
        put("buildver", if (isLtProfile) LT_BUILDVER else (System.currentTimeMillis() / 1000).toString())
        put("resolution", profile?.get("resolution")?.toString() ?: cookie["resolution"] ?: "2268x1080")
        put("channel", profile?.get("channel")?.toString() ?: cookie["channel"] ?: "xiaomi")
        // MeiloX：requestId 后段 4 位补零（Math.random()*1000 取 0000–0999，padStart 4）；非 LT 保持 0–9999。
        put("requestId", "${System.currentTimeMillis()}_${if (isLtProfile) (0..999).random().toString().padStart(4, '0') else (0..9999).random()}")
        put("__csrf", when {
            isHistoryProfile -> ""
            isLtProfile -> LT_CSRF
            else -> effectiveCsrf
        })
        if (musicU != null) put("MUSIC_U", musicU)
    }

    val finalData = route.data.toMutableMap().apply {
        if (route.encryption == Encryption.EAPI) put("header", header) else put("csrf_token", effectiveCsrf)
        // 对齐代理：e_r=false 强制明文响应（config.json 的 encryptResponse=false），否则 NCM 可能回加密体。
        put("e_r", false)
    }
    val jsonBody = toJsonString(finalData)
    val fields = when (route.encryption) {
        Encryption.WEAPI -> NeteaseCrypto.weapi(jsonBody)
        Encryption.EAPI -> NeteaseCrypto.eapi(route.uri, jsonBody)
    }

    val rest = route.uri.removePrefix("/api/")
    val targetDomain = when {
        profile?.get("domain") != null -> profile["domain"].toString()
        route.encryption == Encryption.WEAPI -> NCM_WEB_DOMAIN
        else -> NCM_API_DOMAIN
    }
    val url = when (route.encryption) {
        Encryption.WEAPI -> "$targetDomain/weapi/$rest"
        Encryption.EAPI -> "$targetDomain/eapi/$rest"
    }

    // 请求 Cookie：
    // - 普通 eapi/weapi：对齐 3.0.0 发完整登录 cookie（含 MUSIC_U）；未登录时补稳定 deviceId，
    //   保证匿名读接口（toplist / 搜索等）也能握手成功。
    // - 播放历史 + 用户资料修改（synthesizedCookie）：对齐代理 createHeaderCookie 发「合成 cookie」
    //   （header 字段 URL-encode、按 key 排序）。eapi 写接口发完整登录 cookie 会被 NCM 403 静默丢弃。
    val cookieHeader = when {
        isLtProfile -> {
            // 逐字对齐 MeiloX NeteaseInterceptor.buildCookieString(cookieMap)（插入序、明文值、
            // 不做 URL-encode）：基础 8 + 身份/设备字段 + MUSIC_U。与加密 header 同源（同 os/
            // appver 桌面参数、同 __csrf 常量），避免「cookie os 与 header os 矛盾→静默丢弃」。
            val cfg = profile!!
            buildMap {
                put("os", cfg["os"].toString())
                put("appver", cfg["appver"].toString())
                put("osver", cfg["osver"].toString())
                put("channel", cfg["channel"].toString())
                put("versioncode", cfg["versioncode"].toString())
                put("mobilename", cfg["mobilename"].toString())
                put("buildver", LT_BUILDVER)
                put("resolution", cfg["resolution"].toString())
                put("deviceId", cookie["deviceId"] ?: DeviceIdentity.deviceId)
                put("sDeviceId", cookie["deviceId"] ?: DeviceIdentity.deviceId)
                put("ntes_kaola_ad", "1")
                put("_ntes_nuid", LT_NUID)
                put("WNMCID", LT_WNMCID)
                put("URS_APPID", LT_URS_APPID)
                put("WEVNSM", "1.0.0")
                put("__csrf", LT_CSRF)
                put("NMDI", LT_NMDI)
                put("NMTID", LT_NMTID)
                musicU?.let { put("MUSIC_U", it) }
            }.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        }
        synthesizedCookie -> header.entries
            .sortedBy { it.key }
            .joinToString("; ") { (k, v) -> "${encodeCookieComponent(k)}=${encodeCookieComponent(v.toString())}" }
        isLogin -> buildMap {
            // 登录链统一指纹（2.0.3 按 api-enhanced processCookieObject 对齐）：
            // ① DeviceIdentity.pcFingerprintFields（os=pc 字段集，值落盘稳定）
            // ② 预热/匿名 jar（服务端真下发 Cookie：__csrf、MUSIC_A、真 NMTID
            // 等，同名覆盖指纹字段）③ 会话 Cookie（剔除 CookieProvider 注入的
            // 安卓指纹键，防 os/UA 矛盾）。全链同一套、跨重启不变。
            // 2.0.1 消融教训仍有效：畸形自造 NMTID 会被验伪——本版 NMTID 只发
            // jar 里的服务端真值，指纹字段均为官方客户端同形的良构值。
            putAll(DeviceIdentity.pcFingerprintFields())
            putAll(DeviceIdentity.warmCookies())
            putAll(cookie.filterKeys { it !in LOGIN_SESSION_FINGERPRINT_KEYS })
            put("deviceId", DeviceIdentity.deviceId)
            // 有 MUSIC_U（已登录）时不带匿名令牌（api-enhanced 同规则）。
            if (containsKey("MUSIC_U")) remove("MUSIC_A")
        }.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        else -> buildMap {
            putAll(cookie)
            if (cookie["deviceId"] == null) put("deviceId", DeviceIdentity.deviceId)
        }.entries.joinToString("; ") { (k, v) -> "$k=$v" }
    }

    val response = apiClient.request(url) {
        method = HttpMethod.Post
        contentType(ContentType.Application.FormUrlEncoded)
        val ua = profile?.get("userAgent")?.toString()
            ?: when {
                isLogin -> DeviceIdentity.DESKTOP_UA
                isHistoryProfile -> NCM_OSX_UA
                else -> NCM_MOBILE_UA
            }
        header("User-Agent", ua)
        if (isLtProfile) {
            header("X-Real-IP", LT_FAKE_IP)
            header("X-Forwarded-For", LT_FAKE_IP)
        }
        if (route.encryption == Encryption.WEAPI) {
            header("Referer", NCM_WEB_DOMAIN)
        }
        if (isHistoryProfile) {
            header("Accept", "*/*")
        }
        header("Cookie", cookieHeader)
        setBody(fields.entries.joinToString("&") { (k, v) -> "${k.encodeURLParameter()}=${v.encodeURLParameter()}" })
    }
    val setCookies = response.headers.getAll(HttpHeaders.SetCookie) ?: emptyList()
    if (isLogin) {
        // 登录链中间态 Cookie（__csrf 等）进预热 jar，保证后续轮询/登录同指纹
        DeviceIdentity.absorbSetCookies(setCookies)
    }
    var body = response.bodyAsText()
    if (!response.status.isSuccess()) {
        throw Exception("HTTP ${response.status} ${response.status.description}: ${body.take(500)}")
    }
    // eapi 响应兜底：若 NCM 仍回 hex 加密体（非 JSON 开头），用 EAPI-key 解密（对齐 MeiloX 解密分支）。
    if (route.encryption == Encryption.EAPI) {
        val firstChar = body.firstOrNull { it > ' ' }
        if (firstChar != '{' && firstChar != '[') {
            val decoded = NeteaseCrypto.eapiDecryptHex(body.trim())
            if (decoded.isNotEmpty()) body = decoded
        }
    }
    return body to setCookies
}

@PublishedApi
internal fun toJsonString(map: Map<String, Any>): String =
    map.toJsonObject().toString()

@PublishedApi
internal fun Map<String, Any>.toJsonObject(): JsonObject =
    buildJsonObject {
        forEach { (k, v) -> put(k, toJsonElement(v)) }
    }

@PublishedApi
internal fun toJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value.toLong())
    is Boolean -> JsonPrimitive(value)
    is List<*> -> JsonArray(value.map { toJsonElement(it) })
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to toJsonElement(it.value) })
    else -> JsonPrimitive(value.toString())
}

// ---------------------------------------------------------------------------
// 文件上传（头像 / 歌单封面）已直连 NCM，对齐 api-enhanced 代理 plugins/upload.js 的三步链路：
//   1. eapi /nos/token/alloc 申请预签名 token + objectKey + docId（bucket=yyimgs）；
//   2. 裸 POST 图片字节到 NOS 预签名 URL（x-nos-token 头，公开接口、无登录/无加密）；
//   3. eapi /user/avatar/upload（imgid=docId）或 /playlist/cover/update（coverImgId=docId）落库。
// 加密/cookie 全部走 send() 的默认 eapi 移动端通道，不再依赖代理 API_BASE_URL。
// ---------------------------------------------------------------------------

/** 直连 NCM NOS 裸上传：POST 原始字节到预签名 URL（x-nos-token 头，无登录/无加密）。 */
private fun postNosBytes(objectKey: String, token: String, file: File): String {
    val url = "https://nosup-hz1.127.net/yyimgs/$objectKey?offset=0&complete=true&version=1.0"
    val req = Request.Builder()
        .url(url.toHttpUrl())
        .header("x-nos-token", token)
        .header("Content-Type", "image/jpeg")
        .post(file.asRequestBody(ContentType.Image.JPEG.toString().toMediaType()))
        .build()
    val resp = okHttpUploadClient.newCall(req).execute()
    val body = resp.body?.string().orEmpty()
    // 2.1.2（#16）：日志只记状态与字节数，不再回显响应体（体里可能带签名/令牌类字段）。
    Log.d("ApiClient", "postNosBytes http=${resp.code} bodyBytes=${body.length}")
    if (!resp.isSuccessful) throw Exception("NOS 上传失败 HTTP ${resp.code}: ${body.take(200)}")
    resp.close()
    return body
}

/** NOS 上传结果：docId 用于 eapi 落库（imgid/coverImgId），urlPre 为公开 CDN 地址。 */
data class NosUploadResult(
    val docId: String,
    val urlPre: String
)

/** 完成「申请预签名 token + 裸上传字节」两步，返回 docId / urlPre，供 eapi 落库使用。 */
suspend fun uploadFileToNos(file: File): Result<NosUploadResult> = runCatching {
    val alloc = apiGet<NosTokenAllocResponse>("/nos/token/alloc", mapOf(
        "bucket" to "yyimgs",
        "ext" to "jpg",
        "filename" to file.name,
        "local" to false,
        "nos_product" to 0,
        "return_body" to "{\"code\":200,\"size\":\"$(ObjectSize)\"}",
        "type" to "other"
    )).getOrThrow()
    val r = alloc.result ?: throw Exception("NOS alloc 未返回 result")
    if (r.token.isEmpty()) throw Exception("NOS alloc 返回空 token")
    if (r.objectKey.isEmpty()) throw Exception("NOS alloc 返回空 objectKey")
    withContext(Dispatchers.IO) { postNosBytes(r.objectKey, r.token, file) }
    NosUploadResult(docId = r.docId, urlPre = "https://p1.music.126.net/${r.objectKey}")
}

/** NOS 分配响应（eapi /nos/token/alloc → result 块，字段对齐代理 plugins/upload.js 消费的结构）。 */
@Serializable
internal data class NosTokenAllocResponse(
    val code: Int = 0,
    val message: String? = null,
    val msg: String? = null,
    val result: NosTokenAllocResult? = null
)

@Serializable
internal data class NosTokenAllocResult(
    val token: String = "",
    @SerialName("objectKey") val objectKey: String = "",
    @SerialName("docId") val docId: String = ""
)

// ---------------------------------------------------------------------------
// 遗留：经代理 API_BASE_URL 的 multipart 上传（保留供 unblock 等仍走代理的通道复用；
// 头像/歌单封面已改直连，不再调用这两个函数）。
// ---------------------------------------------------------------------------

suspend inline fun <reified T> apiPostFile(
    path: String,
    fieldName: String,
    file: File,
    contentType: ContentType = ContentType.Image.Any,
    params: Map<String, Any> = emptyMap(),
    includeCommonParams: Boolean = true,
    includeCsrfToken: Boolean = includeCommonParams
): Result<T> {
    return try {
        val response = apiClient.request("$API_BASE_URL$path") {
            method = HttpMethod.Post
            CookieProvider.cookie.takeIf { it.isNotEmpty() }?.let {
                header("Cookie", it)
            }
            if (includeCommonParams) {
                parameter("timestamp", System.currentTimeMillis())
                parameter("_", System.nanoTime())
                parameter("randomCNIP", true)
            }
            params.forEach { (key, value) -> parameter(key, value) }
            setBody(
                MultiPartFormDataContent(
                    formData {
                        val csrf = CookieProvider.getCookieMap()["__csrf"]
                        if (includeCsrfToken && !csrf.isNullOrEmpty()) {
                            append("csrf_token", csrf)
                        }
                        append(
                            fieldName,
                            file.readBytes(),
                            Headers.build {
                                append(
                                    HttpHeaders.ContentDisposition,
                                    "form-data; name=\"$fieldName\"; filename=\"${file.name}\""
                                )
                                append(HttpHeaders.ContentType, contentType.toString())
                            }
                        )
                    }
                )
            )
        }
        val responseBody = response.bodyAsText()
        // 2.1.2（#16）：原先全量打印 headers+body 会把 Cookie/Set-Cookie 等凭证
        // 写进日志；改为只记状态、路径与响应字节数。失败分支的异常体保持原样
        // （那是返回给调用方的业务错误，不是日志）。
        Log.d(
            "ApiClient",
            "apiPostFile path=$path status=${response.status} bodyBytes=${responseBody.length} file=${file.name} size=${file.length()}"
        )
        if (response.status.isSuccess()) {
            val result = json.decodeFromString<T>(responseBody)
            Result.success(result)
        } else {
            Result.failure(Exception("API error: ${response.status}: $responseBody"))
        }
    } catch (e: Exception) {
        Log.e("ApiClient", "apiPostFile failed path=$path file=${file.name}", e)
        Result.failure(e)
    }
}

suspend inline fun <reified T> apiPostFileOkHttp(
    path: String,
    fieldName: String,
    file: File,
    contentType: ContentType = ContentType.Image.Any,
    params: Map<String, Any> = emptyMap(),
    includeCommonParams: Boolean = true,
    includeCsrfToken: Boolean = includeCommonParams
): Result<T> = withContext(Dispatchers.IO) {
    try {
        val urlBuilder = "$API_BASE_URL$path".toHttpUrl().newBuilder()
        if (includeCommonParams) {
            urlBuilder.addQueryParameter("timestamp", System.currentTimeMillis().toString())
            urlBuilder.addQueryParameter("_", System.nanoTime().toString())
            urlBuilder.addQueryParameter("randomCNIP", "true")
        }
        params.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value.toString()) }

        val multipartBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
        val csrf = CookieProvider.getCookieMap()["__csrf"]
        if (includeCsrfToken && !csrf.isNullOrEmpty()) {
            multipartBuilder.addFormDataPart("csrf_token", csrf)
        }
        multipartBuilder.addFormDataPart(
            fieldName,
            file.name,
            file.asRequestBody(contentType.toString().toMediaType())
        )

        val requestBuilder = Request.Builder()
            .url(urlBuilder.build())
            .post(multipartBuilder.build())
            .header("User-Agent", NCM_MOBILE_UA)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache, no-store, max-age=0")
            .header("Pragma", "no-cache")
            .cacheControl(CacheControl.FORCE_NETWORK)

        CookieProvider.cookie.takeIf { it.isNotEmpty() }?.let { cookie ->
            requestBuilder.header("Cookie", cookie)
        }

        val request = requestBuilder.build()
        val response = okHttpUploadClient.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()
        // 2.1.2（#16）：同 apiPostFile——不再全量打印 headers/body（凭证泄漏面），
        // 只记状态、路径与字节数；URL 只记 host+path，不带 query（query 里有
        // csrf/timestamp 等参数）。失败分支异常体为业务返回，保持原样。
        Log.d(
            "ApiClient",
            "apiPostFileOkHttp path=$path url=${request.url.host}${request.url.encodedPath} status=${response.code} bodyBytes=${responseBody.length} file=${file.name} size=${file.length()}"
        )
        if (response.isSuccessful) {
            val result = json.decodeFromString<T>(responseBody)
            Result.success(result)
        } else {
            Result.failure(Exception("API error: ${response.code}: $responseBody"))
        }
    } catch (e: Exception) {
        Log.e("ApiClient", "apiPostFileOkHttp failed path=$path file=${file.name}", e)
        Result.failure(e)
    }
}
