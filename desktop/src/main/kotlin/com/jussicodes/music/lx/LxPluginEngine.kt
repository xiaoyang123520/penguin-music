package com.jussicodes.music.lx

import android.content.Context
import com.jussicodes.music.desktop.DesktopLog
import com.rcmiku.ncmapi.utils.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.graalvm.polyglot.Context as GraalContext
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.io.IOAccess
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 最近一次 LX 脚本错误（供桌面接线层把错误浮出到 ResolveTrace）。
 * 引擎本身不抛异常穿过 LxPluginManager（与安卓版「逐脚本试、未命中
 * 返回 null」的循环语义一致），错误统一记这里 + logs/lx.log。
 */
object LxEngineStatus {
    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var lastErrorAtMs: Long = 0L
        private set

    fun record(message: String) {
        lastError = message
        lastErrorAtMs = System.currentTimeMillis()
        DesktopLog.lx(message)
    }

    /** maxAgeMs 内的新鲜错误（避免把陈年错误误报到无关歌曲的 trace 上）。 */
    fun freshError(maxAgeMs: Long = 20_000L): String? {
        val err = lastError ?: return null
        return if (System.currentTimeMillis() - lastErrorAtMs <= maxAgeMs) err else null
    }
}

/**
 * LX Music 自定义音源脚本的桌面执行引擎（2.1.0，替代 1.x–2.0.5 的 stub）。
 *
 * 安卓版用 WebView 沙盒（见 app 版同名类），桌面无 WebView，改用
 * GraalJS（23.0.5，纯 JVM 解释执行）：
 * - 一插件一 Context，全部求值收敛到该沙盒的单线程执行器
 *   （polyglot Context 非线程安全，串行化最稳）；
 * - 垫片与安卓共用同一份 [LxShim]（window.lx 协议层零改动），
 *   `window` 别名到 globalThis（LX 脚本按 globalThis.lx 取垫片）；
 * - 沙盒隔离：allowIO(NONE)、禁原生/线程/进程/类查找，宿主只暴露
 *   LxHostBridge 五个方法（httpRequest/onEvent/onResolveResult/md5/
 *   setTimer/log）；脚本不能读本地文件，出网只能经宿主桥，且桥只
 *   放行 http(s)；
 * - 定时器由宿主 ScheduledExecutor 驱动（垫片的 setTimeout 补丁），
 *   按 ms 准时回火（安卓 WebView 有原生计时器，桌面这里补齐语义）；
 * - 脚本错误记 [LxEngineStatus] + logs/lx.log，解析失败不假成功。
 *
 * env 传 "desktop"：内置玉宁熙脚本自述双端通用，qdy（全豆要）等公开
 * 脚本在 desktop 分支实证可解析（run8 证据）。
 */
class LxPluginEngine(private val context: Context) {

    var itemProvider: (String) -> LxPluginItem? = { null }

    private val active = ConcurrentHashMap<String, Sandbox>()
    private val nextCallId = AtomicInteger(0)

    // 沙盒出网用的独立客户端：不能带 NCM cookie / 加密 / 代理 base url
    // （同 LocalSourceFetcher 原则），与安卓版同超时口径。
    // 超时取舍（2.1.0 验收实证）：聚合类脚本（如全豆要）的 musicUrl
    // 是 allSettled 语义——所有上游都落定才收尾。若某上游「连得上但
    // 不回包」，读超时就是整轮解析的下限：15s 读超时 > 12s 解析上限
    // (RESOLVE_TIMEOUT_MS，与安卓一致)，脚本永远差 3 秒收不了尾。
    // 故读/写压到 8s：死源 8s 内失败让路，活源正常都在 1-3s 返回。
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val timerPool = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "lx-timer").apply { isDaemon = true }
    }

    // ------------------------------------------------------------------
    // 对外 API（与安卓版同签名，LxPluginManager 无改动复用）
    // ------------------------------------------------------------------

    /** 脚本体检：临时沙盒跑一遍，等 inited 事件拿支持平台；失败返回空表（与安卓版一致）。 */
    suspend fun verify(item: LxPluginItem): List<String> {
        val sandbox = createSandbox(item) ?: return emptyList()
        return try {
            withTimeoutOrNull(INIT_TIMEOUT_MS) { sandbox.inited.await() }.orEmpty()
        } finally {
            sandbox.destroy()
        }
    }

    /** 按 id 取直链：沙盒懒创建，脚本未命中/超时/异常返回 null（错误已记 LxEngineStatus）。 */
    suspend fun resolveUrl(pluginId: String, musicInfoJson: String, quality: String): String? {
        val sandbox = active[pluginId] ?: run {
            val item = itemProvider(pluginId) ?: return null
            val created = createSandbox(item) ?: return null
            active[pluginId] = created
            created
        }
        val callId = nextCallId.incrementAndGet()
        val deferred = CompletableDeferred<String?>()
        sandbox.pending[callId] = deferred
        val dispatched = sandbox.onJs(DISPATCH_TIMEOUT_MS) {
            sandbox.eval(
                "window.__lx_resolve_music_url($callId, 'wy', " +
                    quote(musicInfoJson) + ", " + quote(quality) + ")"
            )
            true
        } ?: false
        if (!dispatched) {
            sandbox.pending.remove(callId)
            return null
        }
        return try {
            withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            null
        } finally {
            sandbox.pending.remove(callId)
        }
    }

    /** 预建沙盒（只建不解析），避免首播时多等一次脚本初始化。 */
    suspend fun warmUp(pluginId: String) {
        if (active.containsKey(pluginId)) return
        val item = itemProvider(pluginId) ?: return
        createSandbox(item)?.let { active[pluginId] = it }
    }

    fun deactivate(pluginId: String) {
        active.remove(pluginId)?.destroy()
    }

    fun reset() {
        val all = active.values.toList()
        active.clear()
        all.forEach { it.destroy() }
    }

    // ------------------------------------------------------------------
    // 沙盒
    // ------------------------------------------------------------------

    // 与 Bridge 同理不能 private：public Bridge 的构造签名引用本类。
    // 本类不直接暴露给 JS（只 Bridge 作为成员注入），public 无副作用。
    inner class Sandbox(val item: LxPluginItem) {
        val jsExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "lx-js-${item.id.take(8)}").apply { isDaemon = true }
        }
        lateinit var ctx: GraalContext
        val pending = ConcurrentHashMap<Int, CompletableDeferred<String?>>()
        val inited = CompletableDeferred<List<String>>()

        @Volatile
        var destroyed = false

        /** 在沙盒线程上执行并等待结果；超时/异常返回 null。 */
        fun <T> onJs(timeoutMs: Long, block: () -> T): T? {
            if (destroyed) return null
            return try {
                jsExecutor.submit(Callable { block() }).get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                null
            }
        }

        /** 在沙盒线程上异步求值（HTTP 回注、定时器回火用），不等待。 */
        fun post(js: String) {
            if (destroyed) return
            jsExecutor.execute {
                if (destroyed) return@execute
                try {
                    ctx.eval("js", js)
                } catch (e: Exception) {
                    LxEngineStatus.record("[${item.name}] 沙盒求值异常：${e.message?.take(120)}")
                }
            }
        }

        fun eval(js: String) {
            ctx.eval("js", js)
        }

        fun destroy() {
            destroyed = true
            pending.values.forEach { it.complete(null) }
            pending.clear()
            runCatching {
                jsExecutor.submit { runCatching { if (::ctx.isInitialized) ctx.close() } }
                    .get(3, TimeUnit.SECONDS)
            }
            jsExecutor.shutdown()
        }
    }

    private fun createSandbox(item: LxPluginItem): Sandbox? {
        val sandbox = Sandbox(item)
        val ok = sandbox.onJs(CREATE_TIMEOUT_MS) {
            try {
                val hostAccess = HostAccess.newBuilder(HostAccess.EXPLICIT).build()
                val ctx = GraalContext.newBuilder("js")
                    .allowHostAccess(hostAccess)
                    .allowHostClassLookup { false }
                    .allowIO(IOAccess.NONE)
                    .allowNativeAccess(false)
                    .allowCreateThread(false)
                    .allowCreateProcess(false)
                    .option("engine.WarnInterpreterOnly", "false")
                    .build()
                sandbox.ctx = ctx
                // 桥先注入，垫片/脚本引用时已就位。
                ctx.getBindings("js").putMember("LxHostBridge", Bridge(sandbox))
                ctx.eval("js", "globalThis.window = globalThis;")
                ctx.eval("js", CONSOLE_SHIM)
                ctx.eval("js", LxShim.script(ENV))
                ctx.eval(
                    "js",
                    "window.lx.currentScriptInfo = { name: ${quote(item.name)}, " +
                        "description: ${quote(item.description)}, " +
                        "version: ${quote(item.version)}, " +
                        "author: ${quote(item.author)} };"
                )
                ctx.eval("js", item.rawScript)
                true
            } catch (e: PolyglotException) {
                LxEngineStatus.record(
                    "[${item.name}] 脚本加载失败：${e.message?.take(160)}"
                )
                false
            } catch (e: Exception) {
                LxEngineStatus.record(
                    "[${item.name}] 沙盒创建失败：${e.javaClass.simpleName} ${e.message?.take(120)}"
                )
                false
            }
        } ?: false
        if (!ok) {
            sandbox.destroy()
            return null
        }
        return sandbox
    }

    // ------------------------------------------------------------------
    // 宿主桥（与安卓 SandboxBridge 同签名，垫片零改动）
    // ------------------------------------------------------------------

    // 注意：不能是 private——Graal HostAccess.EXPLICIT 按 public 可见性
    // 反射成员，private 类的方法对 JS 全部表现为 Unknown identifier
    // （2.1.0 首轮验收实证：玉宁熙 init 调 lx.onEvent 即因此失败）。
    inner class Bridge(private val sandbox: Sandbox) {

        @HostAccess.Export
        fun httpRequest(id: Int, url: String, method: String, headersJson: String, body: String) {
            val pluginName = sandbox.item.name
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                deliverHttp(sandbox, id, "不支持的协议（仅 http/https）", null, null)
                return
            }
            Thread({
                val startedAt = System.currentTimeMillis()
                try {
                    val headers = mutableMapOf<String, String>()
                    if (headersJson.isNotBlank()) {
                        runCatching {
                            json.parseToJsonElement(headersJson).jsonObject
                                .forEach { (k, v) -> headers[k] = v.jsonPrimitive.content }
                        }
                    }
                    val builder = Request.Builder().url(url)
                    headers.forEach { (k, v) -> builder.header(k, v) }
                    if (!method.equals("GET", true) && body.isNotBlank()) {
                        builder.method(
                            method.uppercase(),
                            body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                        )
                    } else {
                        builder.get()
                    }
                    httpClient.newCall(builder.build()).execute().use { resp ->
                        val text = resp.body?.string().orEmpty()
                        // 成功也留一行（只记 host+状态码+耗时，不记全 URL，
                        // 与 2.0.5 的 Ktor 日志隐私口径一致）：聚合脚本多路
                        // 并发取链时，失败日志 alone 看不出哪路在工作。
                        DesktopLog.lx(
                            "[$pluginName] http $method " +
                                "${runCatching { java.net.URI(url).host }.getOrNull() ?: "?"} " +
                                "-> ${resp.code} in ${System.currentTimeMillis() - startedAt}ms"
                        )
                        deliverHttp(
                            sandbox, id, null,
                            """{"statusCode":${resp.code}}""", text
                        )
                    }
                } catch (e: Exception) {
                    DesktopLog.lx(
                        "[$pluginName] http $method " +
                            "${runCatching { java.net.URI(url).host }.getOrNull() ?: "?"} " +
                            "失败：${e.javaClass.simpleName}"
                    )
                    deliverHttp(sandbox, id, e.message ?: "网络错误", null, null)
                }
            }, "lx-http").apply { isDaemon = true }.start()
        }

        @HostAccess.Export
        fun onEvent(event: String, dataJson: String) {
            if (event != "inited") return
            val sources = mutableListOf<String>()
            runCatching {
                json.parseToJsonElement(dataJson).jsonObject["sources"]
                    ?.jsonObject?.keys?.forEach { sources.add(it) }
            }
            sandbox.inited.complete(sources)
        }

        @HostAccess.Export
        fun onResolveResult(callId: Int, url: String?, error: String?) {
            if (!error.isNullOrBlank()) {
                LxEngineStatus.record("[${sandbox.item.name}] 解析失败：${error.take(160)}")
            }
            sandbox.pending[callId]?.complete(url?.takeIf { it.isNotBlank() })
        }

        @HostAccess.Export
        fun md5(input: String): String = runCatching {
            MessageDigest.getInstance("MD5").digest(input.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.getOrDefault("")

        @HostAccess.Export
        fun setTimer(id: Int, ms: Int) {
            timerPool.schedule(
                { sandbox.post("window.__lx_fire_timer($id)") },
                ms.toLong().coerceAtLeast(0L), TimeUnit.MILLISECONDS
            )
        }

        @HostAccess.Export
        fun log(message: String) {
            DesktopLog.lx("[${sandbox.item.name}] JS ${message.take(300)}")
        }
    }

    private fun deliverHttp(sandbox: Sandbox, id: Int, err: String?, respJson: String?, body: String?) {
        val js = "window.__lx_handle_http_response($id," +
            (err?.let { quote(it) } ?: "null") + "," +
            (respJson?.let { quote(it) } ?: "null") + "," +
            (body?.let { quote(it) } ?: "null") + ")"
        sandbox.post(js)
    }

    companion object {
        /** 垫片 env：桌面端如实报 desktop（公开脚本双端分支均实证可用）。 */
        private const val ENV = "desktop"
        private const val INIT_TIMEOUT_MS = 8000L
        private const val RESOLVE_TIMEOUT_MS = 12000L
        /** 脚本求值在解释模式下可达数秒（147KB 混淆脚本实测约 5s），创建给足。 */
        private const val CREATE_TIMEOUT_MS = 45_000L
        private const val DISPATCH_TIMEOUT_MS = 30_000L

        private const val CONSOLE_SHIM = """
            globalThis.console = (function() {
                function make(level) {
                    return function() {
                        var parts = [];
                        for (var i = 0; i < arguments.length; i++) {
                            var a = arguments[i];
                            try { parts.push(typeof a === 'string' ? a : JSON.stringify(a)); }
                            catch (e) { parts.push(String(a)); }
                        }
                        LxHostBridge.log(level + ': ' + parts.join(' '));
                    };
                }
                return { log: make('log'), info: make('info'), warn: make('warn'),
                         error: make('error'), debug: make('debug') };
            })();
        """

        /** JSON 字符串字面量（等价 org.json.JSONObject.quote，桌面无 org.json）。 */
        fun quote(s: String): String {
            val sb = StringBuilder(s.length + 2)
            sb.append('"')
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '' -> sb.append("\\f")
                    else -> if (c.code < 0x20 || c.code == 0x2028 || c.code == 0x2029) {
                        sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(c)
                    }
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}
