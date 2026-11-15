package com.libretv.tv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.libretv.tv.data.CategoryCatalog
import com.libretv.tv.data.CmsClient
import com.libretv.tv.data.DetailBundle
import com.libretv.tv.data.DoubanClient
import com.libretv.tv.data.DoubanSubject
import com.libretv.tv.data.OfferProbe
import com.libretv.tv.data.PlayRequest
import com.libretv.tv.data.SearchResult
import com.libretv.tv.data.SourceOffer
import com.libretv.tv.data.TitleGroup
import com.libretv.tv.data.SourceConfig
import com.libretv.tv.data.SourceRepository
import com.libretv.tv.data.VodClass
import com.libretv.tv.data.WatchHistoryStore
import com.libretv.tv.data.WatchRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class Screen { HOME, SEARCH, RESULT, SOURCE_PICKER, DETAIL, PLAYER, SOURCES }

/**
 * Home screen tabs.
 *
 * The first ones map onto the collector's own category tree; RECOMMEND keeps
 * the Douban discovery shelves and HISTORY shows the local continue-watching
 * list. doubanType / doubanTags feed a fallback shelf so a tab never ends up
 * completely blank when a collector is unreachable.
 */
enum class HomeTab(
    val label: String,
    val doubanType: String,
    val doubanTags: List<String>
) {
    RECOMMEND("推荐", "movie", emptyList()),
    MOVIE("电影", "movie", listOf("热门", "高分", "喜剧")),
    SERIES("剧集", "tv", listOf("热门", "国产剧")),
    VARIETY("综艺", "tv", listOf("综艺", "最新")),
    ANIME("动漫", "movie", listOf("动画", "高分")),
    DOCUMENTARY("纪录片", "movie", listOf("纪录片", "高分")),
    SHORT("短剧", "tv", listOf("最新")),
    HISTORY("历史", "movie", emptyList())
}

data class UiState(
    val screen: Screen = Screen.HOME,
    val sources: List<SourceConfig> = emptyList(),
    val query: String = "",
    val t9Buffer: String = "",
    val t9LastDigit: Int? = null,
    val results: List<SearchResult> = emptyList(),
    val searching: Boolean = false,
    val searchedKeyword: String = "",
    val detail: DetailBundle? = null,
    val activeLine: Int = 0,
    val activeEpisode: Int = 0,
    val detailFrom: Screen = Screen.SEARCH,
    val error: String? = null,
    val health: Map<String, Boolean> = emptyMap(),
    val checkingHealth: Boolean = false,
    val doubanRows: List<DoubanRow> = emptyList(),
    val doubanLoading: Boolean = false,
    val homeTab: HomeTab = HomeTab.RECOMMEND,
    val categorySource: SourceConfig? = null,
    val categoryGroups: Map<HomeTab, List<VodClass>> = emptyMap(),
    val categoryRows: List<CategoryRow> = emptyList(),
    val categoryLoading: Boolean = false,
    val tabDoubanRows: List<DoubanRow> = emptyList(),
    val history: List<WatchRecord> = emptyList(),
    /** The title whose sources are being compared, and their async probes. */
    val selectedGroup: TitleGroup? = null,
    val offerProbes: Map<String, OfferProbe> = emptyMap(),
    /** The source chosen last, shown as a badge on the detail page. */
    val activeOffer: SourceOffer? = null
)

/** One Douban recommendation shelf: a label plus the titles under it. */
data class DoubanRow(
    val tag: String,
    val label: String,
    val subjects: List<DoubanSubject> = emptyList()
)

/** One shelf of a category tab: a sub-category name plus its playable titles. */
data class CategoryRow(
    val label: String,
    val items: List<SearchResult> = emptyList()
)

/**
 * Map a collector's category tree onto the app's tabs.
 *
 * Type ids differ between collectors, so matching is done on names. Top-level
 * nodes (parentId == 0) usually have no titles of their own on Apple CMS, so
 * the shelves are built from their children instead.
 */
private fun classify(classes: List<VodClass>): Map<HomeTab, List<VodClass>> {
    val tops = classes.filter { it.parentId == 0 }
    val kidsOf = { id: Int -> if (id <= 0) emptyList() else classes.filter { it.parentId == id } }
    val out = linkedMapOf<HomeTab, List<VodClass>>()

    val movieTop = tops.firstOrNull { it.typeName.contains("电影") }
    val movieKids = kidsOf(movieTop?.typeId ?: -1)
    val movie = buildList {
        addAll(movieKids.filterNot {
            it.typeName.contains("纪录片") || it.typeName.contains("动画")
        }.take(5))
        if (isEmpty() && movieTop != null) add(movieTop)
    }.distinctBy { it.typeId }
    if (movie.isNotEmpty()) out[HomeTab.MOVIE] = movie

    val documentary = (tops.filter { it.typeName.contains("纪录片") } +
        movieKids.filter { it.typeName.contains("纪录片") })
        .distinctBy { it.typeId }.take(3)
    if (documentary.isNotEmpty()) out[HomeTab.DOCUMENTARY] = documentary

    val animeTop = tops.firstOrNull {
        it.typeName.contains("动漫") || it.typeName.contains("动画")
    }
    val anime = buildList {
        addAll(kidsOf(animeTop?.typeId ?: -1).take(4))
        addAll(movieKids.filter { it.typeName.contains("动画") })
        if (isEmpty() && animeTop != null) add(animeTop)
    }.distinctBy { it.typeId }.take(5)
    if (anime.isNotEmpty()) out[HomeTab.ANIME] = anime

    val seriesTop = tops.firstOrNull {
        it.typeName.contains("剧") && !it.typeName.contains("短剧")
    }
    val series = buildList {
        addAll(kidsOf(seriesTop?.typeId ?: -1)
            .filterNot { it.typeName.contains("短剧") }.take(5))
        if (isEmpty() && seriesTop != null) add(seriesTop)
    }.distinctBy { it.typeId }
    if (series.isNotEmpty()) out[HomeTab.SERIES] = series

    val varietyTop = tops.firstOrNull { it.typeName.contains("综艺") }
    val variety = buildList {
        addAll(kidsOf(varietyTop?.typeId ?: -1).take(4))
        if (isEmpty() && varietyTop != null) add(varietyTop)
    }.distinctBy { it.typeId }
    if (variety.isNotEmpty()) out[HomeTab.VARIETY] = variety

    val shortTops = tops.filter { it.typeName.contains("短剧") }
    val short = buildList {
        shortTops.forEach { top -> addAll(kidsOf(top.typeId).take(4)) }
        if (isEmpty()) addAll(shortTops)
    }.distinctBy { it.typeId }.take(5)
    if (short.isNotEmpty()) out[HomeTab.SHORT] = short

    return out
}

class TvViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SourceRepository(app)
    private val client = CmsClient()
    private val douban = DoubanClient()
    val historyStore = WatchHistoryStore(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var categoryJob: Job? = null
    private var tabDoubanJob: Job? = null

    /** pinyin-initial -> Chinese title, so a TV remote alone can reach a title. */
    private var titleIndex: Map<String, String> = emptyMap()

    init {
        viewModelScope.launch {
            val loaded = repo.loadSources()
            titleIndex = repo.loadTitleIndex()
            _state.value = _state.value.copy(sources = loaded)

            val catalog: CategoryCatalog? = repo.loadCategoryCatalog(loaded, client)
            if (catalog != null) {
                _state.value = _state.value.copy(
                    categorySource = catalog.source,
                    categoryGroups = classify(catalog.classes)
                )
                // A tab may have been selected before the catalog arrived.
                val tab = _state.value.homeTab
                if (tab != HomeTab.RECOMMEND && tab != HomeTab.HISTORY) {
                    loadCategoryTab(tab)
                }
            } else {
                _state.value = _state.value.copy(categoryLoading = false)
            }
        }
        viewModelScope.launch {
            historyStore.records.collect { records ->
                _state.value = _state.value.copy(history = records)
            }
        }
        loadDouban()
    }

    // ---------- home tabs & categories ----------

    fun setHomeTab(tab: HomeTab) {
        // Focus alone selects a tab now, so the same tab can be requested by a
        // focus event and a touch tap within a few milliseconds. Bail out on a
        // no-op change instead of dropping the freshly loaded shelves twice.
        if (_state.value.homeTab == tab) return
        val needsCatalog = tab != HomeTab.RECOMMEND && tab != HomeTab.HISTORY
        val catalogReady = _state.value.categorySource != null
        _state.value = _state.value.copy(
            homeTab = tab,
            categoryRows = emptyList(),
            tabDoubanRows = emptyList(),
            categoryLoading = needsCatalog && !catalogReady
        )
        if (!needsCatalog) return
        loadTabDouban(tab)
        if (catalogReady) loadCategoryTab(tab)
    }

    /** Load the collector shelves for one tab, keeping the configured order. */
    private fun loadCategoryTab(tab: HomeTab) {
        val source = _state.value.categorySource ?: return
        val classes = _state.value.categoryGroups[tab].orEmpty()
        categoryJob?.cancel()
        if (classes.isEmpty()) {
            _state.value = _state.value.copy(categoryRows = emptyList(), categoryLoading = false)
            return
        }
        _state.value = _state.value.copy(categoryRows = emptyList(), categoryLoading = true)
        categoryJob = viewModelScope.launch {
            val rows = coroutineScope {
                classes.map { c ->
                    async(Dispatchers.IO) {
                        CategoryRow(c.typeName, repo.categoryShelf(source, c.typeId, client))
                    }
                }.awaitAll()
            }.filter { it.items.isNotEmpty() }
            _state.value = _state.value.copy(categoryRows = rows, categoryLoading = false)
        }
    }

    /** Discovery fallback for category tabs when the collector is thin. */
    private fun loadTabDouban(tab: HomeTab) {
        tabDoubanJob?.cancel()
        val tags = tab.doubanTags
        if (tags.isEmpty()) {
            _state.value = _state.value.copy(tabDoubanRows = emptyList())
            return
        }
        tabDoubanJob = viewModelScope.launch {
            val rows = coroutineScope {
                tags.map { tag ->
                    async(Dispatchers.IO) {
                        tag to douban.recommendations(tag, 16, tab.doubanType)
                    }
                }.awaitAll()
            }.mapNotNull { (tag, subjects) ->
                if (subjects.isEmpty()) null else DoubanRow(tag, tag, subjects)
            }
            _state.value = _state.value.copy(tabDoubanRows = rows)
        }
    }

    // ---------- watch history ----------

    /** Called by the player on a timer and when it is left. */
    fun saveProgress(request: PlayRequest, positionMs: Long, durationMs: Long) {
        historyStore.upsert(
            WatchRecord(
                key = request.historyKey,
                title = request.title,
                poster = request.poster,
                sourceName = request.sourceName,
                sourceUrl = request.sourceUrl,
                vodId = request.vodId,
                episodeName = request.episodeName,
                rawUrl = request.rawUrl,
                lineIndex = request.lineIndex,
                episodeIndex = request.episodeIndex,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                updatedAt = System.currentTimeMillis(),
                typeName = request.typeName
            )
        )
    }

    fun deleteHistory(key: String) = historyStore.delete(key)

    fun clearHistory() = historyStore.clear()

    /**
     * Seed the detail state from a history entry so the player still has its
     * episode list (next / previous / picker) when resuming.
     */
    fun applyResume(bundle: DetailBundle?, line: Int, episode: Int) {
        _state.value = _state.value.copy(
            detail = bundle,
            activeLine = line.coerceAtLeast(0),
            activeEpisode = episode.coerceAtLeast(0),
            detailFrom = Screen.HOME,
            error = null
        )
    }

    // ---------- Douban discovery ----------

    /**
     * Load the recommendation shelves shown on the home screen. Rows are loaded
     * one at a time and appended as they arrive so the first shelf shows up
     * without waiting for the slowest tag.
     */
    fun loadDouban(tabs: List<Pair<String, String>> = DoubanClient.RECOMMEND_TABS) {
        if (_state.value.doubanLoading) return
        _state.value = _state.value.copy(doubanLoading = true)
        viewModelScope.launch {
            // Load the shelves concurrently but keep the configured order, so the
            // layout stays stable no matter which tag answers first.
            val loaded = tabs.map { (label, tag) ->
                async(Dispatchers.IO) { label to douban.recommendations(tag) }
            }.awaitAll()

            val rows = loaded.mapNotNull { (label, subjects) ->
                if (subjects.isEmpty()) null else DoubanRow(label, label, subjects)
            }
            _state.value = _state.value.copy(doubanRows = rows, doubanLoading = false)
        }
    }

    /** Search a Douban title across the bundled sources and jump to the results. */
    fun searchDoubanTitle(subject: DoubanSubject) {
        val title = subject.title.trim()
        if (title.isEmpty()) return
        _state.value = _state.value.copy(
            query = title,
            t9Buffer = "",
            t9LastDigit = null,
            screen = Screen.RESULT,
            searching = true,
            results = emptyList(),
            searchedKeyword = title,
            error = null
        )
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val sources = _state.value.sources
            val found = repo.searchAll(sources, client, title)
            _state.value = _state.value.copy(results = found, searching = false)
        }
    }

    fun navigate(screen: Screen) {
        _state.value = _state.value.copy(screen = screen, error = null)
    }

    fun back() {
        val s = _state.value
        _state.value = when (s.screen) {
            Screen.PLAYER -> s.copy(screen = Screen.DETAIL)
            Screen.DETAIL -> s.copy(screen = s.detailFrom)
            Screen.SOURCE_PICKER -> s.copy(screen = Screen.RESULT)
            Screen.RESULT -> s.copy(screen = Screen.SEARCH)
            Screen.SEARCH -> s.copy(screen = Screen.HOME)
            Screen.SOURCES -> s.copy(screen = Screen.HOME)
            Screen.HOME -> s
        }
    }

    // ---------- Keyboard search input ----------

    /**
     * Type one letter. The buffer holds raw letters; the visible query is the
     * Chinese title the pinyin index resolves them to, when there is a hit.
     */
    fun inputLetter(letter: String) {
        val s = _state.value
        val buffer = s.t9Buffer + letter.lowercase()
        _state.value = s.copy(
            t9Buffer = buffer,
            t9LastDigit = null,
            query = resolveQuery(buffer)
        )
    }

    fun t9Backspace() {
        val s = _state.value
        val buf = s.t9Buffer
        if (buf.isEmpty()) return
        val trimmed = buf.dropLast(1)
        _state.value = s.copy(
            t9Buffer = trimmed,
            t9LastDigit = null,
            query = resolveQuery(trimmed)
        )
    }

    fun t9Clear() {
        _state.value = _state.value.copy(t9Buffer = "", t9LastDigit = null, query = "")
    }

    /** Titles whose pinyin initials start with what has been typed. */
    fun candidateTitles(): List<String> {
        val lower = _state.value.t9Buffer.lowercase()
        if (lower.isEmpty()) return emptyList()
        return titleIndex.entries
            .filter { (initials, _) -> initials.startsWith(lower) }
            .sortedByDescending { it.key.length }
            .map { it.value }
            .distinct()
            .take(8)
    }

    /** Use a candidate title directly as the search term. */
    fun pickCandidate(title: String) {
        _state.value = _state.value.copy(
            query = title,
            t9Buffer = "",
            t9LastDigit = null
        )
    }

    /**
     * Latin input alone cannot match Chinese collectors, so map the typed letters
     * onto a bundled pinyin-initial index. Exact initials win; otherwise the
     * longest prefix match is used. When nothing matches, the raw letters stay
     * and the search falls back to them.
     */
    private fun resolveQuery(letters: String): String {
        val lower = letters.lowercase()
        if (lower.isEmpty()) return ""
        if (lower.all { it.isLetter() }) {
            titleIndex[lower]?.let { return it }
            val hit = titleIndex.entries
                .filter { (initials, _) -> initials.startsWith(lower) }
                .maxByOrNull { it.key.length }
            if (hit != null) return hit.value
        }
        return letters
    }

    /** The letters as typed, kept for a fallback search pass. */
    private fun rawLetters(): String = _state.value.t9Buffer.lowercase()

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q, t9Buffer = "", t9LastDigit = null)
    }

    fun submitSearch(keyword: String = _state.value.query) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        val letters = rawLetters()
        _state.value = _state.value.copy(
            screen = Screen.RESULT,
            query = kw,
            searching = true,
            results = emptyList(),
            searchedKeyword = kw,
            error = null
        )
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val sources = _state.value.sources
            var found = repo.searchAll(sources, client, kw)
            // The pinyin index produced a Chinese title but nothing matched;
            // retry with the literal initials so the remote is never a dead end.
            if (found.isEmpty() && letters.isNotBlank() && letters != kw) {
                found = repo.searchAll(sources, client, letters)
            }
            _state.value = _state.value.copy(results = found, searching = false)
        }
    }

    // ---------- aggregated sources ----------

    /**
     * Open the source picker for one title and, in parallel, probe each offer.
     * The picker shows the coarse (search) latency immediately and fills in the
     * line / episode count and stream first-byte time as the probes land.
     */
    fun openGroup(group: TitleGroup) {
        _state.value = _state.value.copy(
            screen = Screen.SOURCE_PICKER,
            selectedGroup = group,
            offerProbes = emptyMap(),
            error = null
        )
        viewModelScope.launch {
            val probes = coroutineScope {
                group.offers.take(8).map { offer ->
                    async(Dispatchers.IO) { offer.key to repo.probeOffer(offer, client) }
                }.awaitAll()
            }.toMap()
            // A newer selection may have replaced this group while probing.
            if (_state.value.selectedGroup?.key == group.key) {
                _state.value = _state.value.copy(offerProbes = probes)
            }
        }
    }

    fun pickOffer(offer: SourceOffer) {
        _state.value = _state.value.copy(activeOffer = offer)
        openDetail(
            SearchResult(
                sourceName = offer.sourceName,
                item = offer.item,
                sourceUrl = offer.sourceUrl,
                latencyMs = offer.latencyMs
            ),
            from = Screen.SOURCE_PICKER
        )
    }

    // ---------- detail ----------

    fun openDetail(result: SearchResult, from: Screen = _state.value.screen) {
        // A stale offer badge would describe the previous search's source when
        // the viewer opens a title from a category shelf or history instead.
        val matchingOffer = _state.value.activeOffer?.takeIf {
            it.sourceUrl == result.sourceUrl && it.vodId == result.item.vodId
        }
        _state.value = _state.value.copy(
            screen = Screen.DETAIL,
            detail = null,
            error = null,
            detailFrom = from,
            activeOffer = matchingOffer
        )
        viewModelScope.launch {
            // Results produced by the fan-out search already carry the exact
            // base URL; name lookups are only needed for older call sites.
            val source = if (result.sourceUrl.isNotBlank()) {
                SourceConfig(result.sourceName, result.sourceUrl)
            } else {
                _state.value.sources.firstOrNull { it.name == result.sourceName }
                    ?: return@launch
            }
            val bundle = repo.fetchDetail(source, result.item, client)
            if (bundle == null) {
                _state.value = _state.value.copy(
                    detail = DetailBundle(result.sourceName, result.item, emptyList()),
                    error = "详情获取失败，可能该源已失效"
                )
            } else {
                _state.value = _state.value.copy(detail = bundle)
            }
        }
    }

    fun selectLine(index: Int) {
        _state.value = _state.value.copy(activeLine = index, activeEpisode = 0)
    }

    fun selectEpisode(index: Int) {
        _state.value = _state.value.copy(activeEpisode = index)
    }

    // ---------- source health ----------

    fun checkHealth() {
        if (_state.value.checkingHealth) return
        _state.value = _state.value.copy(checkingHealth = true, health = emptyMap())
        viewModelScope.launch {
            val sources = _state.value.sources
            val results = sources.associate { source ->
                source.name to repo.probe(source, client)
            }
            _state.value = _state.value.copy(health = results, checkingHealth = false)
        }
    }
}
