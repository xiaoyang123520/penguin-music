package com.rcmiku.ncmapi.api.mv

import com.rcmiku.ncmapi.api.apiGet
import kotlinx.serialization.Serializable

/**
 * MV 数据层（2.1.0 补）：ApiClient 早已有 /mv/detail 与 /mv/url 两个
 * weapi 路由（此前注释「桌面无视频渲染管线，UI 不接入」），本文件是
 * 它们的类型化封装。桌面端由 JCEF HTML5 video 播放返回的地址。
 */

@Serializable
data class MvBr(
    val br: Int = 0,
    // 接口返回浮点（如 1234567.0），写 Long 会整段解码失败（实证）。
    val size: Double = 0.0
)

@Serializable
data class MvDetail(
    val id: Long = 0,
    val name: String = "",
    val artistName: String = "",
    val cover: String = "",
    val briefDesc: String? = null,
    val desc: String? = null,
    val duration: Long = 0,
    val playCount: Long = 0,
    val brs: List<MvBr> = emptyList()
)

@Serializable
data class MvDetailResponse(
    val data: MvDetail? = null,
    val code: Int = 0
)

@Serializable
data class MvUrlData(
    val id: Long = 0,
    val url: String? = null,
    val r: Int = 0,
    val size: Long = 0,
    val md5: String? = null
)

@Serializable
data class MvUrlResponse(
    val data: MvUrlData? = null,
    val code: Int = 0
)

object MvApi {

    /** MV 详情（名称/歌手/封面/可用码率）。路由 /mv/detail 入参 mvid。 */
    suspend fun mvDetail(mvId: Long): Result<MvDetailResponse> =
        apiGet("/mv/detail", mapOf("mvid" to mvId))

    /** MV 播放地址。路由 /mv/url 入参 id + r（分辨率，默认 1080）。 */
    suspend fun mvUrl(mvId: Long, r: Int = 1080): Result<MvUrlResponse> =
        apiGet("/mv/url", mapOf("id" to mvId, "r" to r))
}
