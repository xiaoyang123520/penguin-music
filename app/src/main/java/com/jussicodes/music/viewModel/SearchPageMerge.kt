package com.jussicodes.music.viewModel

import com.rcmiku.ncmapi.model.SearchResources

/**
 * 搜索翻页追加去重（2.3.0，A6-05）。
 *
 * 网易云搜索 offset 分页在结果集变动时可返回与上一页重叠的条目；
 * 旧实现直接 `_searchResults + page`，重叠项会在列表出现两次。
 * 本函数保序追加：只追加去重键未在已有结果中出现过的条目。
 * 纯函数、无 Android 依赖，供探针直接单测；SearchViewModel.loadMore
 * 调用它（offset 仍按原始页大小推进，offset 是服务端分页语义，
 * 与展示去重无关）。
 */
fun mergeSearchPage(
    existing: List<SearchResources>,
    page: List<SearchResources>,
): List<SearchResources> {
    val seen = existing.mapTo(HashSet()) { it.searchDedupKey() }
    val out = existing.toMutableList()
    for (item in page) {
        if (seen.add(item.searchDedupKey())) out += item
    }
    return out
}

/**
 * 去重键：按资源类型取 id（歌曲/专辑/歌手/歌单/用户/声音各取其一，
 * 带类型前缀防跨类型 id 撞车）；全空资源退化为对象哈希（不误去重）。
 */
fun SearchResources.searchDedupKey(): String {
    song?.let { return "song:${it.id}" }
    album?.let { return "album:${it.id}" }
    artist?.let { return "artist:${it.id}" }
    playlist?.let { return "playlist:${it.id}" }
    user?.let { return "user:${it.id}" }
    voice?.let { return "voice:${it.id}" }
    baseInfo?.let { return "voice:${it.id}" }
    return "other:${hashCode()}"
}
