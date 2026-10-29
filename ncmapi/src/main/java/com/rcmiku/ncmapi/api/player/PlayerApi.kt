package com.rcmiku.ncmapi.api.player

import android.util.Log
import com.rcmiku.ncmapi.api.API_BASE_URL
import com.rcmiku.ncmapi.api.AudioSourceSettings
import com.rcmiku.ncmapi.api.AudioSources
import com.rcmiku.ncmapi.api.LocalSourceFetcher
import com.rcmiku.ncmapi.api.LocalSources
import com.rcmiku.ncmapi.api.LxSourceBridge
import com.rcmiku.ncmapi.api.UNBLOCK_SOURCE
import com.rcmiku.ncmapi.api.apiClient
import com.rcmiku.ncmapi.api.apiGet
import com.rcmiku.ncmapi.model.LyricResponse
import com.rcmiku.ncmapi.model.SongDownloadResponse
import com.rcmiku.ncmapi.model.SongUrl
import com.rcmiku.ncmapi.model.SongUrlResponse
import com.rcmiku.ncmapi.utils.CookieProvider
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 取链诊断轨迹（2.0.3）：记录最近一次解析的每一步结果（音源/官方/解灰，
 * 成功或失败原因），供播放器在终局失败时拼进 Toast——2.0.2 的静默失败
 * （点了没反应）在真机上无法定位，本版要求每条失败路径都留痕且可见。
 */
object ResolveTrace {
    @Volatile
    var lastSteps: List<String> = emptyList()
        private set

    @Volatile
    var lastSongId: Long = 0L
        private set

    internal fun begin(songId: Long) {
        lastSongId = songId
        lastSteps = emptyList()
    }

    internal fun step(text: String) {
        lastSteps = (lastSteps + text).takeLast(6)
    }

    fun summary(): String {
        // 2.1.3（A4-12）：相邻重复步去重——级联重试会把同一步（如「官方
        // 直连 无可播地址」）连记两次，Toast 里出现重复段观感不专业。
        // 只折叠相邻重复，保留真实的步骤顺序与非相邻重复。
        val deduped = lastSteps.fold(mutableListOf<String>()) { acc, step ->
            if (acc.lastOrNull() != step) acc.add(step)
            acc
        }
        return deduped.joinToString(" → ")
    }
}

object PlayerApi {

    suspend fun songPlayUrlV1(
        songId: String,
        songLevel: SongLevel = SongLevel.STANDARD,
        source: String? = null
    ): Result<SongUrlResponse> {
        val parsed = parseSongId(songId)
        val realId = parsed.first
        val fee = parsed.second

        // 只有 fee==1（VIP/黑胶）需要第三方解灰。fee=0（免费）、fee=8（普通）等其它值
        // 官方直连即可播放，完全不碰 API 服务器。
        // 用户确认：fee=1 是要解灰的 VIP，fee=8 是直接能听的普通歌。
        ResolveTrace.begin(realId.toLongOrNull() ?: 0L)
        if (fee != 1) {
            val official = apiGet<SongUrlResponse>(
                "/song/url/v1", songUrlParams(realId, songLevel, unblock = false)
            )
            if (official.hasPlayableUrl()) {
                // 官方步按 level 取链，理论上不会回错格式；真回 FLAC 而音质
                // 非无损时留痕（末位兜底仍返回，播放器可解 FLAC 或再降级）。
                if (songLevel != SongLevel.LOSSLESS && official.firstPlayableUrlIsFlac()) {
                    ResolveTrace.step("官方直连 返回 FLAC 与所选音质不符（兜底保留）")
                } else {
                    ResolveTrace.step("官方直连 成功")
                }
            } else {
                ResolveTrace.step("官方直连 无可播地址")
            }
            return official
        }

        // VIP（fee==1）取链级联（2.0.3 改：2.0.2 的「三选一互斥不串联」在坏配置
        // 下会静默失败——如 PROXY 选中但服务器已死、LX 选中但没装脚本，用户点
        // 播放毫无反应。本版按 所选音源 → 官方直连 → 解灰匹配 级联回退，每步
        // 结果写 ResolveTrace，终局失败由播放器 Toast 出步骤链）：
        // 1) 所选音源
        val selected = AudioSourceSettings.SELECTED
        when (selected) {
            AudioSources.LX -> {
                val resolver = LxSourceBridge.resolver
                if (resolver == null) {
                    // LX 引擎是 stub 时 resolver 恒为 null 就是这一步的失败原因，
                    // 必须留痕（2.0.2 时这里静默落到官方试听，用户以为「音源坏了」）。
                    ResolveTrace.step("LX音源 无可用脚本/引擎未装配")
                } else {
                    val url = runCatching { resolver.invoke(realId, songLevel.value) }
                        .onFailure { ResolveTrace.step("LX音源 异常 ${it.message?.take(40)}") }
                        .getOrNull()
                    if (!url.isNullOrBlank() && gateFormat("LX音源", url, songLevel)) {
                        ResolveTrace.step("LX音源 成功")
                        return Result.success(SongUrlResponse(data = listOf(
                            SongUrl(id = realId.toLong(), url = url, br = 320000)
                        )))
                    }
                    if (url.isNullOrBlank()) ResolveTrace.step("LX音源 未命中")
                }
            }
            AudioSources.DIRECT -> {
                // 2.0.4：格式闸门下沉到 tryDirectSourceUrl 内部逐源判定，
                // 某源回 FLAC 被跳过时还有机会轮询下一个源。
                val url = runCatching { tryDirectSourceUrl(realId, songLevel) }
                    .onFailure { ResolveTrace.step("直连音源 异常 ${it.message?.take(40)}") }
                    .getOrNull()
                if (!url.isNullOrBlank()) {
                    ResolveTrace.step("直连音源 成功")
                    return Result.success(SongUrlResponse(data = listOf(
                        SongUrl(id = realId.toLong(), url = url, br = 320000)
                    )))
                }
                ResolveTrace.step("直连音源 未命中")
            }
            else -> { // PROXY
                val unblockResult = tryUnblockUrl(realId, source)
                if (unblockResult.hasPlayableUrl()) {
                    val u = unblockResult.getOrNull()?.data?.firstOrNull { it.isPlayableFullUrl() }?.url
                    if (u != null && gateFormat("代理解灰", u, songLevel)) {
                        ResolveTrace.step("代理解灰 成功")
                        return unblockResult
                    }
                } else {
                    ResolveTrace.step("代理解灰 失败 ${unblockResult.exceptionOrNull()?.message?.take(40) ?: "无可播地址"}")
                }
            }
        }
        // 2) 官方直连兜底（账号有会员时 VIP 歌在这里拿完整 url；官方按 level
        // 取链，EXHIGH 必回 MP3——2.0.4 格式闸门后，前面被跳过的 FLAC 歌多
        // 在这一步按所选音质命中）
        val official = apiGet<SongUrlResponse>(
            "/song/url/v1", songUrlParams(realId, songLevel, unblock = false)
        )
        if (official.hasPlayableUrl()) {
            if (songLevel != SongLevel.LOSSLESS && official.firstPlayableUrlIsFlac()) {
                ResolveTrace.step("官方直连 返回 FLAC 与所选音质不符，跳过")
            } else {
                ResolveTrace.step("官方直连 成功")
                return official
            }
        } else {
            ResolveTrace.step("官方直连 无可播地址")
        }
        // 3) 解灰匹配（PROXY 已在所选音源步试过则不重复；未配置服务器跳过）
        if (selected != AudioSources.PROXY && API_BASE_URL.isNotBlank()) {
            val unblockResult = tryUnblockUrl(realId, source)
            if (unblockResult.hasPlayableUrl()) {
                val u = unblockResult.getOrNull()?.data?.firstOrNull { it.isPlayableFullUrl() }?.url
                if (u != null && gateFormat("解灰匹配", u, songLevel)) {
                    ResolveTrace.step("解灰匹配 成功")
                    return unblockResult
                }
            } else {
                ResolveTrace.step("解灰匹配 失败")
            }
        }
        // 全部落空：把官方结果交回播放器（多为 30 秒试听占位或空 url），
        // 由播放器决定跳歌并 Toast 出 ResolveTrace 步骤链，不在此静默。
        return official
    }

    private fun songUrlParams(songId: String, songLevel: SongLevel, unblock: Boolean): Map<String, Any> =
        mapOf("id" to songId, "level" to songLevel.value, "unblock" to unblock)

    /**
     * 官方直达取链（2.0.4 新增）：绕过音源级联，只走官方 /song/url/v1 按
     * 指定 level 取链。供播放器「FLAC 解码失败自动降音质」兜底使用——2.0.3
     * 的降级重走整条级联、又命中同源同一条 FLAC，是假降级；本入口不读
     * 播放器的直链缓存键，返回值也由调用方自行校验格式。
     * 不重置 ResolveTrace（保留主级联轨迹），只追加一步记录。
     */
    suspend fun songPlayUrlOfficial(
        songId: String,
        songLevel: SongLevel
    ): Result<SongUrlResponse> {
        val realId = parseSongId(songId).first
        val official = apiGet<SongUrlResponse>(
            "/song/url/v1", songUrlParams(realId, songLevel, unblock = false)
        )
        ResolveTrace.step(
            if (official.hasPlayableUrl()) "官方直达(${songLevel.value}) 成功"
            else "官方直达(${songLevel.value}) 无可播地址"
        )
        return official
    }

    /** URL 是否指向 FLAC 文件（按路径后缀判定，与播放器 isFlacUrl 同口径）。 */
    internal fun isFlacUrl(url: String): Boolean =
        url.substringBefore('?').endsWith(".flac", ignoreCase = true)

    /**
     * 格式闸门（2.0.4）：用户所选音质不是无损时，音源步返回的 FLAC 不算
     * 命中——这类源不看 level、给什么全看源端库存（VIP 歌常回 FLAC），
     * 2.0.3 时用户设 EXHIGH 仍被塞无损、引擎又解不了，直接死停。
     * 返回 true 表示可采用；被闸门拦下时写 ResolveTrace 并返回 false。
     * level == LOSSLESS 时 FLAC 是用户点名要的格式，放行。
     */
    private fun gateFormat(step: String, url: String, level: SongLevel): Boolean {
        if (level != SongLevel.LOSSLESS && isFlacUrl(url)) {
            ResolveTrace.step("$step 返回 FLAC 与所选音质不符，跳过")
            return false
        }
        return true
    }

    /** 响应里第一条可播 URL 是否为 FLAC（官方步闸门判定用）。 */
    private fun Result<SongUrlResponse>.firstPlayableUrlIsFlac(): Boolean {
        val u = getOrNull()?.data?.firstOrNull { it.isPlayableFullUrl() }?.url
        return u != null && isFlacUrl(u)
    }

    /** 旧版取链（br 口径，2.0.2 补齐，对齐 api-enhanced song_url.js）。 */
    suspend fun songUrl(songId: String, br: Int = 999000): Result<SongUrlResponse> =
        apiGet("/song/url", mapOf("id" to songId, "br" to br))

    /**
     * 可用性检查（对齐 check_music.js 的后处理：data[0].code==200 才算可播）。
     * 注意它与取链同端点：官方对无版权/地域受限歌曲回 data[0].code=404/403，
     * 这类是「官方回 null」而非接口不通，调用方不要混为一谈。
     */
    suspend fun checkMusic(songId: String, br: Int = 999000): Result<Boolean> {
        val raw = apiGet<JsonObject>("/check/music", mapOf("id" to songId, "br" to br))
        return raw.map { root ->
            val first = (root["data"] as? JsonArray)?.firstOrNull() as? JsonObject
            first?.get("code")?.jsonPrimitive?.intOrNull == 200
        }
    }

    /** 客户端下载链（br 口径 / level 口径，2.0.2 补齐；data 为单对象）。 */
    suspend fun songDownloadUrl(songId: String, br: Int = 999000): Result<SongDownloadResponse> =
        apiGet("/song/download/url", mapOf("id" to songId, "br" to br))

    suspend fun songDownloadUrlV1(
        songId: String,
        songLevel: SongLevel = SongLevel.EXHIGH
    ): Result<SongDownloadResponse> =
        apiGet("/song/download/url/v1", mapOf("id" to songId, "level" to songLevel.value))

    /**
     * 直连音源：用户指定单源就只试它；「自动选择」按 [LocalSources.DIRECT_KEYS] 顺序轮询
     * 六条，命中即返回（照 Melodia 六源）。
     * 2.0.4：逐源过格式闸门——某源回 FLAC 且音质非无损时跳过该源继续轮询
     * （各源库存格式不同，跳过一个 FLAC 源常能在下一个源拿到 MP3）。
     */
    private fun tryDirectSourceUrl(songId: String, songLevel: SongLevel): String? {
        val chosen = LocalSources.fromKey(DirectSourceSettings.SOURCE)?.value
            ?: LocalSources.AUTO
        val chain = if (chosen == LocalSources.AUTO) LocalSources.DIRECT_KEYS else listOf(chosen)
        for (m in chain) {
            val url = LocalSourceFetcher.fetch(m, songId)
            if (url != null) {
                if (!gateFormat("直连音源($m)", url, songLevel)) continue
                Log.d("PlayerApi", "Local source '$m' hit for songId=$songId")
                return url
            }
        }
        return null
    }


    private fun Result<SongUrlResponse>.hasPlayableUrl(): Boolean =
        getOrNull()?.data?.any { it.isPlayableFullUrl() } == true

    private fun SongUrl.isPlayableFullUrl(): Boolean =
        !url.isNullOrEmpty() &&
            // NCM 占位/外链（未真正解锁成功时返回），实测返回反爬 HTML 而非音频
            !url.contains("outer/url") &&
            // 试听 30 秒占位（time 单位 ms：30040）
            time != 30_040L
            // 注意：freeTrial* 字段 NCM 对可播歌曲也常回非 null 的空对象，不能用作判据

    private fun parseSongId(raw: String): Triple<String, Int, Int> {
        val queryIndex = raw.indexOf('?')
        if (queryIndex == -1) return Triple(raw, 0, 0)
        val id = raw.substring(0, queryIndex)
        val query = raw.substring(queryIndex + 1)
        val params = query.split("&").associate {
            val eq = it.indexOf('=')
            if (eq > 0) it.substring(0, eq) to it.substring(eq + 1) else it to ""
        }
        val fee = params["fee"]?.toIntOrNull() ?: 0
        val pl = params["pl"]?.toIntOrNull() ?: 0
        return Triple(id, fee, pl)
    }

    private suspend fun tryUnblockUrl(songId: String, sourceOverride: String?): Result<SongUrlResponse> {
        // 2.0.3：base 先规范化（去空白/尾斜杠）；空 base 直接失败留痕，不发注定畸形的请求。
        val base = API_BASE_URL.trim().trimEnd('/')
        if (base.isBlank()) {
            ResolveTrace.step("解灰跳过 未配置API服务器")
            return Result.failure(Exception("未配置 API 服务器地址"))
        }
        // Per-song override wins; otherwise fall back to the global setting.
        // "AUTO" (or null) means "let the server pick".
        val primary = sourceOverride?.takeIf { it.isNotBlank() && it != "AUTO" }
            ?: UNBLOCK_SOURCE.takeIf { it != "AUTO" }

        if (primary == null) {
            val matchResult = requestUnblockUrl("$base/song/url/match", songId, source = null)
            if (matchResult.isSuccess) return matchResult
            return requestUnblockUrl("$base/match", songId, source = null)
        }

        // Explicit source: try it first, then the remaining known sources in
        // picker order until one yields a playable URL (auto-fallback).
        val chain = listOfNotNull(primary) +
            KNOWN_UNBLOCK_SOURCES.filter { it != primary }
        var last = Result.failure<SongUrlResponse>(Exception("No unblock URL found for any source"))
        for (src in chain) {
            val matchResult = requestUnblockUrl("$base/song/url/match", songId, source = src)
            if (matchResult.isSuccess) {
                Log.d("PlayerApi", "Unblock fallback settled on source=$src for songId=$songId")
                return matchResult
            }
            val legacyResult = requestUnblockUrl("$base/match", songId, source = src)
            if (legacyResult.isSuccess) {
                Log.d("PlayerApi", "Unblock fallback settled on source=$src (legacy) for songId=$songId")
                return legacyResult
            }
            last = legacyResult
        }
        return last
    }

    /**
     * Fallback chain for unblock sources, mirroring the order of the app's
     * source picker (shared in ncmapi's UnblockSources so UI and fallback
     * never drift).
     */
    private val KNOWN_UNBLOCK_SOURCES: List<String> =
        com.rcmiku.ncmapi.api.UnblockSources.FALLBACK_VALUES

    /** 直连源选择（「音源设置」里选中「直连音源」时的子设置）。 */
    object DirectSourceSettings {
        var SOURCE: String = LocalSources.AUTO
    }

    private suspend fun requestUnblockUrl(url: String, songId: String, source: String?): Result<SongUrlResponse> {
        // 2.0.3 硬超时：2.0.2 时解灰请求走 apiClient 默认超时，坏地址（不可达/
        // 垃圾/已下线）能挂几十秒到几分钟，播放器还重试——真机表现为「点了
        // 没反应」。单请求 6 秒封顶，超时即视为该步失败、继续级联。
        val timed = kotlinx.coroutines.withTimeoutOrNull(UNBLOCK_TIMEOUT_MS) {
            requestUnblockUrlInner(url, songId, source)
        }
        return timed ?: Result.failure(Exception("解灰请求超时（${UNBLOCK_TIMEOUT_MS / 1000}秒） url=$url"))
    }

    private const val UNBLOCK_TIMEOUT_MS = 6_000L

    private suspend fun requestUnblockUrlInner(url: String, songId: String, source: String?): Result<SongUrlResponse> {
        return try {
            val response = apiClient.request(url) {
                method = HttpMethod.Get
                parameter("id", songId)
                if (!source.isNullOrEmpty()) {
                    parameter("source", source)
                }
                CookieProvider.cookie.takeIf { it.isNotEmpty() }?.let {
                    header("Cookie", it)
                }
            }
            if (response.status.isSuccess()) {
                val body = response.bodyAsText()
                val unblockData = parseUnblockResponse(body)
                if (unblockData != null) {
                    Log.d("PlayerApi", "Unblock resolved songId=$songId source=$source url=${unblockData.url}")
                    Result.success(SongUrlResponse(data = listOf(
                        SongUrl(id = songId.toLong(), url = unblockData.url, br = unblockData.br)
                    )))
                } else {
                    Log.w("PlayerApi", "No unblock URL found for songId=$songId source=$source body=$body")
                    Result.failure(Exception("No unblock URL found: $body"))
                }
            } else {
                val body = response.bodyAsText()
                Log.w("PlayerApi", "Unblock service returned ${response.status} for songId=$songId body=$body")
                Result.failure(Exception("Unblock service returned ${response.status}: $body"))
            }
        } catch (e: Exception) {
            Log.w("PlayerApi", "Unblock request failed for songId=$songId source=$UNBLOCK_SOURCE url=$url", e)
            Result.failure(e)
        }
    }

    private data class UnblockData(val url: String, val br: Int)

    private fun parseUnblockResponse(body: String): UnblockData? {
        return try {
            val json = kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
            val jsonObj = json.parseToJsonElement(body).jsonObject
            parseUnblockData(jsonObj)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseUnblockData(jsonObj: JsonObject): UnblockData? {
        // api-enhanced /song/url/match 的响应形态：{code, data:"<直链>", proxyUrl:""}。
        // proxyUrl 非空时是服务端要求走代理的变体（如 kuwo 源需经 PROXY_URL），优先用它，
        // 与 song_url_match.js 的返回语义一致。
        val proxyUrl = (jsonObj["proxyUrl"] as? JsonPrimitive)?.contentOrNull
        if (!proxyUrl.isNullOrBlank()) {
            return UnblockData(proxyUrl, 320000)
        }
        val candidates = listOfNotNull(
            jsonObj["data"],
            jsonObj["result"],
            jsonObj["url"],
        )
        return candidates.firstNotNullOfOrNull(::parseUnblockElement)
    }

    private fun parseUnblockElement(element: JsonElement): UnblockData? =
        when (element) {
            is JsonPrimitive -> {
                val url = element.contentOrNull
                if (!url.isNullOrEmpty()) UnblockData(url, 320000) else null
            }
            is JsonArray -> element.firstNotNullOfOrNull(::parseUnblockElement)
            is JsonObject -> {
                if (element.isTrialUrl()) {
                    null
                } else {
                    val url = element["url"]?.jsonPrimitive?.contentOrNull
                        ?: element["data"]?.let(::parseUnblockElement)?.url
                    val br = element["br"]?.jsonPrimitive?.intOrNull ?: 320000
                    if (!url.isNullOrEmpty()) UnblockData(url, br) else null
                }
            }
            else -> null
        }

    private fun JsonObject.isTrialUrl(): Boolean =
        containsKey("freeTrialInfo") ||
            containsKey("freeTrialPrivilege") ||
            containsKey("freeTimeTrialPrivilege") ||
            this["time"]?.jsonPrimitive?.contentOrNull == "30040"

    suspend fun songLyric(musicId: Long): Result<LyricResponse> =
        apiGet("/lyric/new", mapOf("id" to musicId))
}
