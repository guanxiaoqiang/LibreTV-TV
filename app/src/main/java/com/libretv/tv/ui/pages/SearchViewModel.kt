package com.libretv.tv.ui.pages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.libretv.tv.core.Logger
import com.libretv.tv.di.AppContainer
import com.libretv.tv.domain.model.DoubanSubject
import com.libretv.tv.domain.usecase.SearchSuggestions
import com.libretv.tv.ui.nav.Routes
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 搜索页状态：只有输入框本身属于这一页。 */
data class SearchState(
    val query: String = ""
)

/**
 * 搜索页 ViewModel。
 *
 * 结果本身属于「搜索会话」（应用级），因为结果页也要读它；搜索页只管输入。
 */
class SearchViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    /** 搜索历史（应用级，跨页面共享）。 */
    val history: StateFlow<List<String>> = container.searchHistory

    private var searchJob: Job? = null

    fun inputChar(ch: String) {
        if (ch.isEmpty()) return
        _state.value = _state.value.copy(query = _state.value.query + ch)
    }

    fun deleteLastChar() {
        val s = _state.value
        if (s.query.isEmpty()) return
        _state.value = s.copy(query = s.query.dropLast(1))
    }

    fun clearInput() {
        _state.value = _state.value.copy(query = "")
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    /**
     * 候选词：优先拼音首字母，其次历史。
     *
     * 候选来自「正在输入的字母」，所以输入到一半就有提示。
     */
    fun candidateTitles(): List<String> {
        val s = _state.value
        val initials = SearchSuggestions.pinyinInitials(s.query)
        val pinyin = SearchSuggestions.suggest(
            titleIndex = container.titleIndex.value,
            history = container.searchHistory.value,
            initials = initials,
            query = s.query
        )
        if (pinyin.isNotEmpty()) return pinyin
        return SearchSuggestions.suggest(
            titleIndex = container.titleIndex.value,
            history = container.searchHistory.value,
            initials = "",
            query = s.query
        )
    }

    fun pickCandidate(title: String) = startSearch(title, resolvePinyin = false)

    /** 按回车：用拼音首字母反查片名，查不到就按原文搜。 */
    fun submitSearch() {
        val typed = _state.value.query.trim()
        if (typed.isEmpty()) return
        startSearch(typed, resolvePinyin = true)
    }

    fun searchKeyword(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        startSearch(kw, resolvePinyin = false)
    }

    fun deleteSearchHistory(keyword: String) = container.deleteSearchHistory(keyword)

    fun clearSearchHistory() = container.clearSearchHistory()

    /**
     * 豆瓣卡片点进来：按片名跨源搜索。
     *
     * 结果页的返回目标是首页（而不是键盘搜索页），这由导航栈记录。
     */
    fun searchDoubanTitle(subject: DoubanSubject) {
        val title = subject.title.trim()
        if (title.isEmpty()) return
        container.addSearchHistory(title)
        _state.value = _state.value.copy(query = title)
        container.beginSearch(title)
        container.navigate(Routes.RESULT)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val found = container.content.searchAll(container.sources.value, title)
            container.publishSearchResults(found)
        }
    }

    /** 拼音首字母反查片名；查不到就按原文搜。 */
    private fun resolveQuery(text: String): String {
        val trimmed = text.trim()
        val letters = trimmed.replace(" ", "")
        if (letters.isNotEmpty() && letters.all { it in 'a'..'z' || it in 'A'..'Z' }) {
            val lower = letters.lowercase()
            val index = container.titleIndex.value
            index[lower]?.let { return it }
            val hit = index.entries
                .filter { (initials, _) -> initials.startsWith(lower) }
                .maxByOrNull { it.key.length }
            if (hit != null) return hit.value
        }
        return trimmed
    }

    private fun startSearch(keyword: String, resolvePinyin: Boolean) {
        val kw = (if (resolvePinyin) resolveQuery(keyword) else keyword).trim()
        if (kw.isEmpty()) return
        container.addSearchHistory(kw)
        val letters = if (resolvePinyin) keyword.replace(" ", "").lowercase() else ""
        _state.value = _state.value.copy(query = kw)
        container.beginSearch(kw)
        container.navigate(Routes.RESULT)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val sources = container.sources.value
            val started = System.currentTimeMillis()
            var found = container.content.searchAll(sources, kw)
            if (found.isEmpty() && letters.isNotBlank() && letters != kw) {
                Logger.i(TAG, "搜索「$kw」无结果，改用首字母「$letters」重试")
                found = container.content.searchAll(sources, letters)
            }
            Logger.i(
                TAG,
                "搜索「$kw」完成：${found.size} 条结果，耗时 ${System.currentTimeMillis() - started}ms"
            )
            container.publishSearchResults(found)
        }
    }

    private companion object {
        const val TAG = "SearchViewModel"
    }
}
