package com.jussicodes.music.desktop

import com.rcmiku.ncmapi.api.player.PlayerApi
import com.rcmiku.ncmapi.api.recommend.RecommendApi
import com.rcmiku.ncmapi.api.search.SearchApi
import com.rcmiku.ncmapi.api.search.SearchType
import com.rcmiku.ncmapi.utils.CookieProvider
import com.rcmiku.ncmapi.utils.FileProvider
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 数据链冒烟：匿名（无登录）真实请求网易云接口。
 * 1) 搜索 2) 个性化歌单（首页推荐） 3) 歌曲直链解析（播放链）
 */
fun main() = runBlocking {
    FileProvider.init(File(System.getProperty("java.io.tmpdir"), "jussichords-smoke"))
    CookieProvider.init(emptyMap())

    val search = SearchApi.search(offset = 0, limit = 5, keyword = "周杰伦", type = SearchType.Song)
    println("SMOKE search success=${search.isSuccess}")
    search.onSuccess { w ->
        println("SMOKE search resources=${w.data.resources.size}")
        w.data.resources.firstOrNull()?.let { println("SMOKE search first=${it.toString().take(300)}") }
    }.onFailure { println("SMOKE search error=${it.message}") }

    val personalized = RecommendApi.personalizedPlaylist()
    println("SMOKE personalized success=${personalized.isSuccess}")
    personalized.onSuccess { r ->
        println("SMOKE personalized count=${r.result.size}")
        r.result.take(3).forEach { println("SMOKE playlist id=${it.id} name=${it.name}") }
    }.onFailure { println("SMOKE personalized error=${it.message}") }

    // 晴天 - 周杰伦（经典免费曲目）验证直链解析
    val url = PlayerApi.songPlayUrlV1("186106")
    println("SMOKE songUrl success=${url.isSuccess}")
    url.onSuccess { r ->
        val first = r.data.firstOrNull()
        println("SMOKE songUrl url=${first?.url?.take(110)}")
        println("SMOKE songUrl type=${first?.type} br=${first?.br} level=${first?.level}")
    }.onFailure { println("SMOKE songUrl error=${it.message}") }

    println("SMOKE DONE")
}
