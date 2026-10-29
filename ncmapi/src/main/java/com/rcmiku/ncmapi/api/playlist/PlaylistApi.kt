package com.rcmiku.ncmapi.api.playlist

import android.util.Log
import com.rcmiku.ncmapi.api.apiGet
import com.rcmiku.ncmapi.api.apiPost
import com.rcmiku.ncmapi.api.uploadFileToNos
import com.rcmiku.ncmapi.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

object PlaylistApi {
    private const val TAG = "PlaylistApi"
    private const val PLAYLIST_DETAIL_CACHE_TTL_MS = 5 * 60 * 1000L
    // 2.1.3（A4-08）：补拉部分失败的结果只短缓存 30 秒——旧实现无条件当
    // 完整版缓存 5 分钟，缺的歌在 TTL 内怎么刷新都补不回来；短缓存让
    // 瞬时失败很快自愈，又不至于每次打开都重跑全量补拉。
    private const val PLAYLIST_DETAIL_PARTIAL_CACHE_TTL_MS = 30 * 1000L
    private const val PLAYLIST_INFO_CACHE_TTL_MS = 60 * 1000L
    // 2.1.2 #7：/song/detail 单批补拉大小（服务端单批上限口径，见 fillMissingTracks）。
    private const val SONG_DETAIL_BATCH_SIZE = 1000
    // 2.1.2 #7：trackIds 异常上限护栏——超过则截断补拉，避免病态响应拖死详情页。
    private const val MAX_TRACK_IDS = 20_000
    // 存入时刻 -> (响应, 该条目 TTL)：完整版 5 分钟、部分版 30 秒（A4-08）。
    private val playlistDetailCache =
        mutableMapOf<Long, Triple<Long, PlaylistDetailResponse, Long>>()
    private val playlistInfoCache = mutableMapOf<Long, Pair<Long, PlaylistInfoResponse>>()

    /**
     * 大歌单补拉进度（2.1.3，A4-08）：非 null 时表示正在分批补齐曲目，
     * 值为人话进度文案（如「正在补齐曲目 3000/10933」），补完/无需补拉
     * 时为 null。详情页据此显示进度提示，不再让用户对着半成品列表干等
     * 而不知情（旧实现补拉 11 批顺序执行、UI 零提示）。
     */
    val fillProgress = MutableStateFlow<String?>(null)

    suspend fun playlistDetail(id: Long, limit: Int): Result<PlaylistDetailResponse> =
        cachedPlaylistDetail(id)

    suspend fun playlistV6Detail(id: Long): Result<PlaylistDetailResponse> =
        cachedPlaylistDetail(id)

    suspend fun playlistInfo(id: Long): Result<PlaylistInfoResponse> =
        playlistInfoCache[id]
            ?.takeIf { System.currentTimeMillis() - it.first < PLAYLIST_INFO_CACHE_TTL_MS }
            ?.let { Result.success(it.second) }
            ?: apiGet<PlaylistInfoResponse>("/playlist/detail/dynamic", mapOf("id" to id))
                .onSuccess { playlistInfoCache[id] = System.currentTimeMillis() to it }

    suspend fun topList(): Result<TopListResponse> =
        apiGet("/toplist")

    suspend fun playlistSub(id: Long, isSub: Boolean): Result<ApiCodeResponse> {
        val t = if (isSub) 1 else 0
        return apiGet<ApiCodeResponse>("/playlist/subscribe", mapOf("id" to id, "t" to t))
            .onSuccess { playlistInfoCache.remove(id) }
    }

    suspend fun createPlaylist(
        name: String,
        privacy: String? = null,
        type: String? = null
    ): Result<ApiCodeResponse> =
        apiGet(
            "/playlist/create",
            buildMap {
                put("name", name)
                privacy?.let { put("privacy", it) }
                type?.let { put("type", it) }
            }
        )

    suspend fun deletePlaylist(id: Long): Result<ApiCodeResponse> =
        apiGet("/playlist/delete", mapOf("id" to id))

    suspend fun updatePlaylistName(id: Long, name: String): Result<ApiCodeResponse> =
        apiGet<ApiCodeResponse>("/playlist/name/update", mapOf("id" to id, "name" to name))
            .onSuccess { clearPlaylistCaches(id) }

    suspend fun updatePlaylistDescription(id: Long, description: String): Result<ApiCodeResponse> =
        apiGet<ApiCodeResponse>("/playlist/desc/update", mapOf("id" to id, "desc" to description))
            .onSuccess { clearPlaylistCaches(id) }

    /**
     * 歌单封面更新：NOS 直连三步链路（对齐 api-enhanced 代理 plugins/upload.js + playlist_cover_update.js）。
     *  1) uploadFileToNos：eapi /nos/token/alloc 申请预签名 token + 裸 POST 图片字节到 NOS → docId；
     *  2) eapi /playlist/cover/update（coverImgId=docId）落库。
     * 不再依赖代理 API_BASE_URL。imgSize/imgX/imgY 参数保留仅为兼容旧调用方，直连链路 NCM 端点不收。
     */
    suspend fun updatePlaylistCover(
        id: Long,
        file: File,
        imgSize: Int = 300,
        imgX: Int = 0,
        imgY: Int = 0
    ): Result<ApiCodeResponse> =
        runCatching {
            val nos = uploadFileToNos(file).getOrThrow()
            apiPost<ApiCodeResponse>(
                "/playlist/cover/update",
                mapOf("id" to id, "coverImgId" to nos.docId)
            ).getOrThrow()
        }.onSuccess { clearPlaylistCaches(id) }

    suspend fun updatePlaylistOrder(ids: List<Long>): Result<ApiCodeResponse> =
        apiGet<ApiCodeResponse>("/playlist/order/update", mapOf("ids" to ids.joinToString(prefix = "[", postfix = "]")))

    suspend fun updateSongOrder(playlistId: Long, ids: List<Long>): Result<ApiCodeResponse> =
        apiGet<ApiCodeResponse>(
            "/song/order/update",
            mapOf(
                "pid" to playlistId,
                "ids" to ids.joinToString(prefix = "[", postfix = "]")
            )
        ).onSuccess { clearPlaylistCaches(playlistId) }

    fun clearPlaylistCaches(id: Long? = null) {
        if (id == null) {
            playlistDetailCache.clear()
            playlistInfoCache.clear()
        } else {
            playlistDetailCache.remove(id)
            playlistInfoCache.remove(id)
        }
    }

    private suspend fun cachedPlaylistDetail(id: Long): Result<PlaylistDetailResponse> {
        playlistDetailCache[id]
            ?.takeIf { System.currentTimeMillis() - it.first < it.third }
            ?.let { return Result.success(it.second) }

        val result = apiGet<PlaylistDetailResponse>("/playlist/detail", mapOf("id" to id))
        val response = result.getOrNull() ?: return result
        // 2.1.2 #7：补齐被 v6 detail 截断的 tracks 后再进缓存，缓存里存的必须是补齐版。
        // 2.1.3（A4-08）：补拉有批次失败（或被上限截断）时结果是部分版，
        // 只短缓存 30 秒，不再冒充完整版占满 5 分钟 TTL。
        val (filledPlaylist, fillComplete) = fillMissingTracks(response.playlist)
        val completed = response.copy(playlist = filledPlaylist)
        val ttl = if (fillComplete) {
            PLAYLIST_DETAIL_CACHE_TTL_MS
        } else {
            PLAYLIST_DETAIL_PARTIAL_CACHE_TTL_MS
        }
        playlistDetailCache[id] = Triple(System.currentTimeMillis(), completed, ttl)
        return Result.success(completed)
    }

    /**
     * 2.1.2 #7 大歌单截断修复：v6 /playlist/detail 只回少量 tracks（实测歌单
     * id=8482341734、trackCount=10933 时 tracks 仅 10 首），全量有序 id 在
     * trackIds 里。当 trackIds 多于已拿到的 tracks 时，按 trackIds 顺序分批调
     * /song/detail 补齐，再按 trackIds 原序拼装（id→Song 映射回填，含同曲重复
     * 出现的位次）；个别 id 拉不到/已下架就跳过该位，不让整单失败。
     *
     * 降级：某批补拉失败只记日志并跳过该批，最终返回「已拿到的 tracks + 已成功
     * 批次」的部分完整结果；全部补拉失败（补齐后不比现状多）则原样返回原始
     * detail，保持现状行为，绝不让补拉把原本能看的歌单页打成报错。
     *
     * 批大小 1000：NCM /api/v3/song/detail 的 c 参数单批上限口径（api-enhanced
     * song_detail.js 及社区实现均以 1000 量级分批），取上限值减少往返；10933 首
     * 约 11 批。批次顺序执行，不并发轰炸服务端。
     *
     * 2.1.3（A4-08）：返回值带 complete 标志——全部批次成功才算完整
     *（个别 id 源端下架拿不到是服务端现实，不算不完整）；有批次失败或
     * 被 MAX_TRACK_IDS 截断即部分版，调用方据此缩短缓存 TTL。每批完成
     * 后更新 [fillProgress] 供详情页显示补拉进度，结束必清。
     */
    private suspend fun fillMissingTracks(
        playlist: PlaylistDetail
    ): Pair<PlaylistDetail, Boolean> {
        val existing = playlist.getAllTracks()
        var orderedIds = playlist.trackIds.map { it.id }.filter { it > 0 }
        if (orderedIds.size <= existing.size) return playlist to true
        var truncated = false
        if (orderedIds.size > MAX_TRACK_IDS) {
            Log.w(TAG, "trackIds=${orderedIds.size} exceeds cap $MAX_TRACK_IDS, truncating fill")
            orderedIds = orderedIds.take(MAX_TRACK_IDS)
            truncated = true
        }

        val byId = HashMap<Long, Song>(existing.size + orderedIds.size)
        existing.forEach { byId[it.id] = it }

        var failedBatches = 0
        try {
            val batches = orderedIds.chunked(SONG_DETAIL_BATCH_SIZE)
            batches.forEachIndexed { batchIndex, batch ->
                // 已由 detail 直回或前批拿到的 id 不重复请求。
                val missing = batch.filter { it !in byId }
                if (missing.isNotEmpty()) {
                    apiGet<SongDetailResponse>(
                        "/song/detail", mapOf("ids" to missing.joinToString(","))
                    )
                        .onSuccess { resp -> resp.songs.forEach { song -> byId[song.id] = song } }
                        .onFailure {
                            failedBatches++
                            Log.w(TAG, "song/detail fill batch failed (${missing.size} ids): ${it.message}")
                        }
                }
                val doneCount = minOf(
                    (batchIndex + 1) * SONG_DETAIL_BATCH_SIZE, orderedIds.size
                )
                fillProgress.value = "正在补齐曲目 $doneCount/${orderedIds.size} 首"
            }
        } finally {
            fillProgress.value = null
        }

        val filled = orderedIds.mapNotNull { byId[it] }
        val complete = failedBatches == 0 && !truncated
        // 没比现状多拿到任何一首（如全部批次失败）→ 返回原始 detail，不劣化。
        if (filled.size <= existing.size) return playlist to complete
        if (!complete) {
            Log.w(TAG, "playlist ${playlist.id} partially filled: ${filled.size}/${orderedIds.size} tracks, $failedBatches batch(es) failed, truncated=$truncated")
        }
        return playlist.copy(tracks = filled) to complete
    }
}
