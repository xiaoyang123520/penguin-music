package com.jussicodes.music.viewModel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jussicodes.music.data.searchHistoryDataStore
import com.jussicodes.music.utils.ApiServerErrorNotifier
import com.rcmiku.ncmapi.api.playlist.PlaylistApi
import com.rcmiku.ncmapi.api.search.SearchApi
import com.rcmiku.ncmapi.api.search.SearchType
import com.rcmiku.ncmapi.model.Playlist
import com.rcmiku.ncmapi.model.SearchResources
import com.rcmiku.ncmapi.model.SearchSuggestKeywordResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _searchType = MutableStateFlow(SearchType.Song)
    val searchType: StateFlow<SearchType> = _searchType.asStateFlow()

    private val _searchValue = MutableStateFlow("")
    private val _searchResults = MutableStateFlow<List<SearchResources>>(emptyList())
    val searchResults: StateFlow<List<SearchResources>> = _searchResults.asStateFlow()

    // #8（2.1.2）：最近一次搜索的失败信息；非 null 且结果为空时 UI 显示失败态+重试，
    // 与「真的没有结果」的空态区分。请求成功时清空。
    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    // 2.2.0（L2）：搜索分页——接口每页上限 100 条，旧实现只取第一页，
    // 热门关键词的大量结果被静默截断。hasMore 来自接口的 more 标记；
    // loadingMore 防重复触发；searchSeq 是搜索代次守卫（关键词/类型
    // 变化后，迟到的翻页响应不得把新搜索的结果污染）。
    private val _searchHasMore = MutableStateFlow(false)
    val searchHasMore: StateFlow<Boolean> = _searchHasMore.asStateFlow()
    private val _searchLoadingMore = MutableStateFlow(false)
    val searchLoadingMore: StateFlow<Boolean> = _searchLoadingMore.asStateFlow()
    // 2.3.0（A6-05）：加载更多失败的行内提示——与 _searchError 分开，
    // 已有结果时失败不再让按钮直接消失、也不碰 searching 状态；
    // 保留 hasMore 让按钮可重试（文案改「重试」）。
    private val _searchLoadMoreError = MutableStateFlow<String?>(null)
    val searchLoadMoreError: StateFlow<String?> = _searchLoadMoreError.asStateFlow()

    @Volatile
    private var searchOffset = 0

    @Volatile
    private var searchSeq = 0

    private val _topLists = MutableStateFlow<List<Playlist>>(emptyList())
    val topLists: StateFlow<List<Playlist>> = _topLists.asStateFlow()

    private val _suggestKeywordResponse = MutableStateFlow<SearchSuggestKeywordResponse?>(null)
    val suggestKeywordResponse: StateFlow<SearchSuggestKeywordResponse?> =
        _suggestKeywordResponse.asStateFlow()
    private var suggestJob: Job? = null

    init {
        fetchTopLists()
    }

    fun updateSearchType(searchType: SearchType) {
        _searchType.value = searchType
        fetchSearchResults()
    }

    fun updateSearchValue(searchValue: String) {
        _searchValue.value = searchValue
        saveSearch(searchValue)
        fetchSearchResults()
    }

    fun fetchSearchKeyword(searchValue: String) {
        suggestJob?.cancel()
        suggestJob = viewModelScope.launch {
            delay(300)
            _suggestKeywordResponse.value = SearchApi.searchSuggestKeyword(searchValue).getOrNull()
        }
    }

    /** 供 UI 失败态「重试」按钮调用：按当前关键词/类型重新搜索。 */
    fun retrySearch() {
        fetchSearchResults()
    }

    private fun fetchSearchResults() {
        val keyword = _searchValue.value.trim()
        // 代次自增：任何在途的翻页响应自此作废（见 loadMoreSearchResults）。
        searchSeq++
        searchOffset = 0
        _searchHasMore.value = false
        _searchLoadingMore.value = false
        _searchLoadMoreError.value = null
        if (keyword.isEmpty()) {
            _searchResults.value = emptyList()
            _searchError.value = null
            return
        }
        val seq = searchSeq
        viewModelScope.launch {
            _searchError.value = null
            _searchLoadMoreError.value = null
            val result = SearchApi.search(
                offset = 0,
                limit = 100,
                keyword = keyword,
                type = _searchType.value
            )
            if (seq != searchSeq) return@launch
            // 搜索请求失败 => 已配置的 API 服务器大概率失效/填错，
            // 弹一次节流的 toast 引导用户去设置更换后端（不刷屏）。
            result.onFailure {
                ApiServerErrorNotifier.notifyServerUnreachable(context)
                // #8：失败只记错误态，不清 _searchResults，避免把旧结果误清成
                // 「没有找到相关结果」的假象；UI 凭 searchError 区分失败/真空。
                _searchError.value = it.message?.takeIf { m -> m.isNotBlank() } ?: "搜索失败"
            }
            result.onSuccess {
                _searchResults.value = it.data.resources
                searchOffset = it.data.resources.size
                _searchHasMore.value = it.data.more
            }
        }
    }

    /**
     * 加载下一页搜索结果并追加（2.2.0，L2；2.3.0，A6-05）。
     * 无更多/正在加载时直接返回；追加按歌曲 id 去重（保序，见
     * mergeSearchPage）；失败时保留已有结果与 hasMore，行内提示
     * searchLoadMoreError，按钮文案改「重试」可再点（旧实现关
     * hasMore、按钮直接消失，用户以为到底了且无重试机会）。
     */
    fun loadMoreSearchResults() {
        if (_searchLoadingMore.value || !_searchHasMore.value) return
        val keyword = _searchValue.value.trim()
        if (keyword.isEmpty()) return
        val seq = searchSeq
        val type = _searchType.value
        val offset = searchOffset
        _searchLoadingMore.value = true
        viewModelScope.launch {
            val result = SearchApi.search(
                offset = offset,
                limit = 100,
                keyword = keyword,
                type = type
            )
            _searchLoadingMore.value = false
            if (seq != searchSeq) return@launch
            result.onSuccess {
                val page = it.data.resources
                if (page.isNotEmpty()) {
                    _searchResults.value = mergeSearchPage(_searchResults.value, page)
                    searchOffset += page.size
                }
                _searchLoadMoreError.value = null
                // 空页却报 more（总数未知时的接口口径）→ 就此收尾，
                // 防按钮点了没反应式的死循环。
                _searchHasMore.value = it.data.more && page.isNotEmpty()
            }
            result.onFailure {
                _searchLoadMoreError.value =
                    it.message?.takeIf { m -> m.isNotBlank() } ?: "加载更多失败"
            }
        }
    }

    private fun fetchTopLists() {
        viewModelScope.launch {
            _topLists.value = PlaylistApi.topList().getOrNull()?.list.orEmpty()
        }
    }

    val searchHistory: Flow<List<String>> = getSearchHistory(context).stateIn(
        viewModelScope,
        SharingStarted.Lazily,
        emptyList()
    )

    private fun saveSearch(query: String) {
        viewModelScope.launch {
            saveSearchQuery(context, query)
        }
    }

    private suspend fun saveSearchQuery(context: Context, query: String) {
        context.searchHistoryDataStore.updateData { currentHistory ->
            val historyList = currentHistory.historyList.toMutableList()
            historyList.remove(query)
            historyList.add(0, query)
            if (historyList.size > 10) historyList.removeAt(historyList.lastIndex)
            currentHistory.toBuilder().clearHistory().addAllHistory(historyList).build()
        }
    }

    private fun getSearchHistory(context: Context): Flow<List<String>> {
        return context.searchHistoryDataStore.data.map { it.historyList }
    }

    fun deleteSearchQuery(query: String) {
        viewModelScope.launch {
            removeSearchQuery(context, query)
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            context.searchHistoryDataStore.updateData { currentHistory ->
                currentHistory.toBuilder().clearHistory().build()
            }
        }
    }

    private suspend fun removeSearchQuery(context: Context, query: String) {
        context.searchHistoryDataStore.updateData { currentHistory ->
            val historyList = currentHistory.historyList.toMutableList()
            historyList.remove(query)
            currentHistory.toBuilder().clearHistory().addAllHistory(historyList).build()
        }
    }
}
