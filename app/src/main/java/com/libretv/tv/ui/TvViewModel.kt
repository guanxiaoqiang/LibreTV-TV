package com.libretv.tv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.libretv.tv.data.AppSettings
import com.libretv.tv.data.BadgeTag
import com.libretv.tv.data.BadgeVisibility
import com.libretv.tv.data.CategoryCatalog
import com.libretv.tv.data.aggregate
import com.libretv.tv.data.CmsClient
import com.libretv.tv.data.DetailBundle
import com.libretv.tv.data.DoubanClient
import com.libretv.tv.data.DoubanSubject
import com.libretv.tv.data.OfferProbe
import com.libretv.tv.data.PlayRequest
import com.libretv.tv.data.SearchHistoryStore
import com.libretv.tv.data.SearchResult
import com.libretv.tv.data.SearchSuggestions
import com.libretv.tv.data.SourceEntry
import com.libretv.tv.data.SourceHealth
import com.libretv.tv.data.SourceMerger
import com.libretv.tv.data.SourceOffer
import com.libretv.tv.data.TitleGroup
import com.libretv.tv.data.SourceConfig
import com.libretv.tv.data.SourceRepository
import com.libretv.tv.data.SourceStore
import com.libretv.tv.data.VodClass
import com.libretv.tv.data.WatchHistoryStore
import com.libretv.tv.data.WatchRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { HOME, SEARCH, RESULT, SOURCE_PICKER, DETAIL, PLAYER, SETTINGS, SOURCES }

/**
 * Pages in left-rail order. [doubanType] selects the movie / tv feed used by
 * the recommend page and by the discovery fallback when a collector has no
 * matching sub-category for the selected genre.
 */
enum class HomeTab(val label: String, val doubanType: String) {
    RECOMMEND("推荐", "movie"),
    HISTORY("历史", "movie"),
    MOVIE("电影", "movie"),
    SERIES("剧集", "tv"),
    VARIETY("综艺", "tv"),
    ANIME("动漫", "movie"),
    DOCUMENTARY("纪录片", "movie"),
    SHORT("短剧", "tv")
}

data class UiState(
    val screen: Screen = Screen.HOME,
    /** Enabled collectors, used by every fan-out search and category probe. */
    val sources: List<SourceConfig> = emptyList(),
    /** Every collector, including the disabled ones, for the source settings page. */
    val sourceEntries: List<SourceEntry> = emptyList(),
    /** Per-source test result, keyed by the source URL. */
    val sourceHealth: Map<String, SourceHealth> = emptyMap(),
    /** URL of the source currently being tested, if any. */
    val testingSource: String? = null,
    /** Strip ad segments out of HLS playlists. */
    val adFilterEnabled: Boolean = true,
    /** Which poster metadata tags are visible. */
    val badgeVisibility: BadgeVisibility = BadgeVisibility(),
    /**
     * The visible search text. The on-screen board types straight into it,
     * so the input field always mirrors the keyboard and the suggestion
     * strip below reacts to the very same string.
     */
    val query: String = "",
    /** Recent search keywords, newest first, persisted in SharedPreferences. */
    val searchHistory: List<String> = emptyList(),
    val results: List<SearchResult> = emptyList(),
    val searching: Boolean = false,
    val searchedKeyword: String = "",
    /** Where the result page was opened from: SEARCH keyboard or HOME (Douban). */
    val resultFrom: Screen = Screen.SEARCH,
    val detail: DetailBundle? = null,
    val activeLine: Int = 0,
    val activeEpisode: Int = 0,
    val detailFrom: Screen = Screen.SEARCH,
    val error: String? = null,
    val homeTab: HomeTab = HomeTab.RECOMMEND,
    /**
     * Last focused slot on the left rail. Keeping it in the view model (rather
     * than a local remember) survives leaving HOME for search / playback, so
     * LEFT from the content grid and a return from another page both land on
     * the entry the viewer actually came from.
     */
    val railFocusIndex: Int = 0,
    /** Selected chip at the top of the content page. */
    val genre: String = "热门",
    /** Cards currently loaded for this tab + genre (all of them, not just visible). */
    val feed: List<FeedCard> = emptyList(),
    /** How many of [feed] the grid is allowed to show; grows in segments. */
    val feedVisible: Int = 0,
    val feedLoading: Boolean = false,
    val feedLoadingMore: Boolean = false,
    val feedHasMore: Boolean = true,
    val feedNote: String = "",
    val feedTotalItems: Int = 0,
    val feedDoneTargets: Int = 0,
    val feedTotalTargets: Int = 0,
    val categoryCatalogReady: Boolean = false,
    val categorySourceCount: Int = 0,
    val history: List<WatchRecord> = emptyList(),
    /** The title whose sources are being compared, and their async probes. */
    val selectedGroup: TitleGroup? = null,
    /** Where the source picker was opened from, so BACK returns correctly. */
    val pickerFrom: Screen = Screen.RESULT,
    val offerProbes: Map<String, OfferProbe> = emptyMap(),
    /** The source chosen last, shown as a badge on the detail page. */
    val activeOffer: SourceOffer? = null
)

/** Number of cards revealed per scroll segment. */
private const val FEED_PAGE_SIZE = 24
private const val DOUBAN_PAGE_SIZE = 20
private const val MAX_SOURCE_PAGE = 3
private const val MAX_DOUBAN_START = 100

/**
 * Everything needed to resume a (tab, genre) strip: the deduplicated raw
 * results (so later source pages can re-aggregate with earlier ones), the
 * already built cards, and the cursors for the two possible load-more paths.
 */
private data class FeedSnapshot(
    val cards: List<FeedCard>,
    val rawResults: List<SearchResult>,
    val seenResultKeys: Set<String>,
    val seenDoubanIds: Set<String>,
    val nextSourcePage: Int,
    val nextDoubanStart: Int,
    val hasMore: Boolean,
    val totalItems: Int
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
    private val appSettings = AppSettings(app)
    private val sourceStore = SourceStore(app)
    val historyStore = WatchHistoryStore(app)
    private val searchHistoryStore = SearchHistoryStore(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var feedJob: Job? = null
    /** Category trees of every usable source, cached so tab switches are cheap. */
    private var allCatalogs: List<CategoryCatalog> = emptyList()
    /** Built strips, keyed by "TAB|genre", so chip switches are instant. */
    private val feedCache = mutableMapOf<String, FeedSnapshot>()
    /** Only the newest strip is allowed to publish into [UiState]. */
    private var activeFeedKey: String = ""

    /** pinyin-initial -> Chinese title, so a TV remote alone can reach a title. */
    private var titleIndex: Map<String, String> = emptyMap()

    /** The read-only list shipped in assets, before the viewer's edits. */
    private var bundledSources: List<SourceConfig> = emptyList()

    init {
        _state.value = _state.value.copy(
            adFilterEnabled = appSettings.adFilterEnabled,
            badgeVisibility = appSettings.badgeVisibility
        )
        viewModelScope.launch {
            val loaded = repo.loadSources()
            titleIndex = repo.loadTitleIndex()
            applySourceEntries(loaded)
            reloadCatalogs()
        }
        viewModelScope.launch {
            historyStore.records.collect { records ->
                _state.value = _state.value.copy(history = records)
            }
        }
        viewModelScope.launch {
            searchHistoryStore.keywords.collect { words ->
                _state.value = _state.value.copy(searchHistory = words)
            }
        }
        // The recommend strip does not depend on the category trees.
        val first = HomeTab.RECOMMEND.genres().first()
        startFeed(HomeTab.RECOMMEND, first, reset = true)
    }

    // ---------- home tabs, genre chips & segmented grid ----------

    private fun feedKey(tab: HomeTab, genre: String) = "${tab.name}|$genre"

    private fun isActive(key: String): Boolean = activeFeedKey == key

    /** Remember the rail slot the viewer left before moving into the content. */
    fun setRailFocus(index: Int) {
        if (index < 0 || _state.value.railFocusIndex == index) return
        _state.value = _state.value.copy(railFocusIndex = index)
    }

    fun setHomeTab(tab: HomeTab) {
        // Focus alone selects a tab, so a focus event and a touch tap can ask
        // for the same tab within milliseconds. Bail out on a no-op change.
        if (_state.value.homeTab == tab) return
        val genre = tab.genres().firstOrNull()
        feedJob?.cancel()
        if (genre == null) {
            activeFeedKey = ""
            _state.value = _state.value.copy(
                homeTab = tab,
                genre = "热门",
                feed = emptyList(),
                feedVisible = 0,
                feedLoading = false,
                feedLoadingMore = false,
                feedHasMore = true,
                feedNote = "",
                feedTotalItems = 0,
                feedDoneTargets = 0,
                feedTotalTargets = 0
            )
            return
        }
        val key = feedKey(tab, genre.label)
        activeFeedKey = key
        // Re-entering a cached tab must not blank the grid and start a fresh
        // network round: that produced a visible flash on every D-pad move.
        val cached = feedCache[key]
        if (cached != null) {
            _state.value = _state.value.copy(
                homeTab = tab,
                genre = genre.label,
                feedLoading = false,
                feedLoadingMore = false,
                feedTotalTargets = 0
            )
            publish(key, cached, minOf(FEED_PAGE_SIZE, cached.cards.size), false, false)
            return
        }
        _state.value = _state.value.copy(
            homeTab = tab,
            genre = genre.label,
            feed = emptyList(),
            feedVisible = 0,
            feedLoading = true,
            feedLoadingMore = false,
            feedHasMore = true,
            feedNote = "",
            feedTotalItems = 0,
            feedDoneTargets = 0,
            feedTotalTargets = 0
        )
        selectGenreInternal(tab, genre)
    }

    fun setGenre(label: String) {
        val tab = _state.value.homeTab
        if (tab == HomeTab.HISTORY || _state.value.genre == label) return
        val genre = tab.genres().firstOrNull { it.label == label } ?: return
        feedJob?.cancel()
        val key = feedKey(tab, genre.label)
        activeFeedKey = key
        val cached = feedCache[key]
        if (cached != null) {
            _state.value = _state.value.copy(
                genre = label,
                feedLoading = false,
                feedLoadingMore = false,
                feedTotalTargets = 0
            )
            publish(key, cached, minOf(FEED_PAGE_SIZE, cached.cards.size), false, false)
            return
        }
        _state.value = _state.value.copy(
            genre = label,
            feed = emptyList(),
            feedVisible = 0,
            feedLoading = true,
            feedLoadingMore = false,
            feedHasMore = true,
            feedNote = "",
            feedTotalItems = 0,
            feedDoneTargets = 0,
            feedTotalTargets = 0
        )
        selectGenreInternal(tab, genre)
    }

    private fun selectGenreInternal(tab: HomeTab, genre: HomeGenre) {
        val key = feedKey(tab, genre.label)
        val cached = feedCache[key]
        if (cached != null) {
            feedJob?.cancel()
            activeFeedKey = key
            publish(key, cached, minOf(FEED_PAGE_SIZE, cached.cards.size), false, false)
            return
        }
        if (tab == HomeTab.RECOMMEND || _state.value.categoryCatalogReady) {
            startFeed(tab, genre, reset = true)
        } else {
            // Category trees are still loading; init will start this strip.
            feedJob?.cancel()
            activeFeedKey = key
        }
    }

    private fun startFeed(tab: HomeTab, genre: HomeGenre, reset: Boolean) {
        feedJob?.cancel()
        val key = feedKey(tab, genre.label)
        activeFeedKey = key
        if (reset) {
            _state.value = _state.value.copy(
                feedLoading = true,
                feedLoadingMore = false,
                feed = emptyList(),
                feedVisible = 0,
                feedHasMore = true,
                feedNote = "正在加载「${genre.label}」…"
            )
        }
        feedJob = viewModelScope.launch {
            if (tab == HomeTab.RECOMMEND) {
                loadDoubanPage(tab, genre, 0, append = false, key = key)
            } else {
                loadCategoryPage(tab, genre, 1, append = false, key = key)
            }
        }
    }

    /**
     * Called by the grid when focus is close to the last visible card.
     *
     * First it reveals another local segment (no network at all); only when
     * every already-loaded card is on screen does it fetch the next page of
     * source results / Douban recommendations.
     */
    fun loadMore() {
        val s = _state.value
        if (s.feedLoading || s.feedLoadingMore || !s.feedHasMore ||
            s.homeTab == HomeTab.HISTORY
        ) return
        val key = activeFeedKey
        val snapshot = feedCache[key] ?: return
        if (s.feedVisible < s.feed.size) {
            _state.value = s.copy(
                feedVisible = minOf(s.feedVisible + FEED_PAGE_SIZE, s.feed.size)
            )
            return
        }
        val tab = s.homeTab
        val genre = tab.genres().firstOrNull { it.label == s.genre } ?: return
        _state.value = s.copy(feedLoadingMore = true)
        feedJob?.cancel()
        feedJob = viewModelScope.launch {
            if (tab == HomeTab.RECOMMEND) {
                loadDoubanPage(tab, genre, snapshot.nextDoubanStart, append = true, key = key)
            } else {
                loadCategoryPage(tab, genre, snapshot.nextSourcePage, append = true, key = key)
            }
        }
    }

    /** Douban-backed strip used by 推荐 (and as a discovery fallback elsewhere). */
    private suspend fun loadDoubanPage(
        tab: HomeTab,
        genre: HomeGenre,
        pageStart: Int,
        append: Boolean,
        key: String
    ) {
        var subjects = withContext(Dispatchers.IO) {
            douban.recommendations(genre.doubanTag, DOUBAN_PAGE_SIZE, tab.doubanType, pageStart)
        }
        if (subjects.isEmpty() && genre.doubanFallbackTag != genre.doubanTag) {
            subjects = withContext(Dispatchers.IO) {
                douban.recommendations(
                    genre.doubanFallbackTag, DOUBAN_PAGE_SIZE, tab.doubanType, 0
                )
            }
        }
        if (!isActive(key)) return
        val old = if (append) feedCache[key] else null
        val seen = old?.seenDoubanIds.orEmpty().toMutableSet()
        val fresh = subjects.filter { seen.add(it.id.ifBlank { it.title }) }
        val cards = (old?.cards.orEmpty() + fresh.map { doubanCard(it) })
            .distinctBy { it.key }
        val nextStart = pageStart + DOUBAN_PAGE_SIZE
        val hasMore = fresh.isNotEmpty() &&
            subjects.size >= DOUBAN_PAGE_SIZE / 2 &&
            nextStart <= MAX_DOUBAN_START &&
            cards.size < 160
        val snapshot = FeedSnapshot(
            cards = cards,
            rawResults = emptyList(),
            seenResultKeys = emptySet(),
            seenDoubanIds = seen.toSet(),
            nextSourcePage = 1,
            nextDoubanStart = nextStart,
            hasMore = hasMore,
            totalItems = cards.size
        )
        val visible = if (append) {
            minOf(cards.size, _state.value.feedVisible + FEED_PAGE_SIZE)
        } else {
            minOf(FEED_PAGE_SIZE, cards.size)
        }
        publish(key, snapshot, visible, false, false)
    }

    /** All-source fan-out for one tab + genre, one source page at a time. */
    private suspend fun loadCategoryPage(
        tab: HomeTab,
        genre: HomeGenre,
        page: Int,
        append: Boolean,
        key: String
    ) {
        val (targets, keywordFilter) = targetsFor(tab, genre)
        if (!isActive(key)) return
        _state.value = _state.value.copy(
            feedTotalTargets = targets.size,
            feedDoneTargets = 0,
            feedNote = if (targets.isEmpty()) "没有匹配的子分类，正在读取豆瓣推荐…"
            else "正在汇总 ${targets.size} 个采集源的「${genre.label}」片单…"
        )

        val old = if (append) feedCache[key] else null
        val raw = old?.rawResults.orEmpty().toMutableList()
        val seen = old?.seenResultKeys.orEmpty().toMutableSet()
        // The head of the grid is fixed once it has been shown. Every later
        // collector response may only append cards; it must never re-sort or
        // rebuild the first screen.
        var stableCards = old?.cards.orEmpty()
        var publishedVisible = if (append) _state.value.feedVisible else 0

        val channel = Channel<List<SearchResult>>(Channel.UNLIMITED)
        coroutineScope {
            val jobs = targets.map { (source, typeId) ->
                async(Dispatchers.IO) {
                    val part = try {
                        repo.categoryShelf(source, typeId, client, page)
                    } catch (e: Exception) {
                        emptyList()
                    }
                    channel.send(part)
                }
            }
            launch {
                try {
                    jobs.awaitAll()
                } catch (ignored: Exception) {
                    // One collector's failure must never leave the channel open
                    // and freeze the strip.
                } finally {
                    channel.close()
                }
            }

            var done = 0
            for (part in channel) {
                if (!isActive(key)) {
                    jobs.forEach { it.cancel() }
                    return@coroutineScope
                }
                raw.addAll(part.filter { seen.add("${it.sourceUrl}|${it.item.vodId}") })
                done++
                val snapshot = buildSnapshot(
                    genre, raw, seen, old, keywordFilter, page, stableCards
                )
                stableCards = snapshot.cards
                // Publish only while the visible first screen is still filling.
                // Once it is full, keep aggregating in the background and do a
                // single final publish — no repeated rebuild of the head cards.
                // feedLoading stays true until the round completes so the
                // infinite-scroll trigger cannot cancel this job mid-flight.
                if (!append) {
                    val desired = minOf(FEED_PAGE_SIZE, snapshot.cards.size)
                    if (desired > publishedVisible) {
                        publishedVisible = desired
                        publish(
                            key = key,
                            snapshot = snapshot,
                            visible = desired,
                            loading = true,
                            loadingMore = false,
                            doneTargets = done
                        )
                    }
                }
            }
        }
        if (!isActive(key)) return

        // One, and only one, metadata refresh after the round: merge the extra
        // sources into existing groups while keeping their slots untouched.
        var snapshot = buildSnapshot(
            genre, raw, seen, old, keywordFilter, page, stableCards,
            refreshExisting = true
        )
        // When a collector has no such sub-category, Douban still gives the
        // chip a meaningful grid (e.g. 美剧 / 英剧 / 韩剧).
        if (snapshot.cards.size < 12 && !append) {
            snapshot = supplementWithDouban(tab, genre, snapshot)
        }
        val visible = if (append) {
            minOf(snapshot.cards.size, _state.value.feedVisible + FEED_PAGE_SIZE)
        } else {
            minOf(FEED_PAGE_SIZE, snapshot.cards.size)
        }
        publish(
            key = key,
            snapshot = snapshot,
            visible = visible,
            loading = false,
            loadingMore = false,
            doneTargets = targets.size
        )
    }

    /**
     * Merge a page of raw results into one ordered card strip.
     *
     * [previousCards] is the order already on screen and is preserved verbatim:
     * "first loaded stays in front". Aggregation still combines the same title
     * from more collectors, but the head of the grid is never re-sorted. Brand
     * new titles are appended in the genre's sort order. [refreshExisting]
     * updates offer metadata of those head cards exactly once, at the end of a
     * round, and only for cards whose data actually changed.
     */
    private fun buildSnapshot(
        genre: HomeGenre,
        raw: List<SearchResult>,
        seen: Set<String>,
        old: FeedSnapshot?,
        keywordFilter: Boolean,
        page: Int,
        previousCards: List<FeedCard>,
        refreshExisting: Boolean = false
    ): FeedSnapshot {
        var groups = aggregate(raw).filter { it.playableCount > 0 }
        if (keywordFilter && genre.keywords.isNotEmpty()) {
            groups = groups.filter { matchesGenre(it, genre.keywords) }
        }
        groups = sortGroups(groups, genre.sort)
        val cards = mergeStableCards(
            previous = previousCards,
            incoming = groups.map { groupCard(it) },
            refreshExisting = refreshExisting
        )

        return FeedSnapshot(
            cards = cards,
            rawResults = raw.toList(),
            seenResultKeys = seen,
            seenDoubanIds = old?.seenDoubanIds.orEmpty(),
            nextSourcePage = page + 1,
            nextDoubanStart = old?.nextDoubanStart ?: 0,
            hasMore = page < MAX_SOURCE_PAGE && raw.isNotEmpty(),
            totalItems = raw.size
        )
    }

    /** Fill a thin strip with Douban discoveries so a genre is never empty. */
    private suspend fun supplementWithDouban(
        tab: HomeTab,
        genre: HomeGenre,
        snapshot: FeedSnapshot
    ): FeedSnapshot {
        var subjects = withContext(Dispatchers.IO) {
            douban.recommendations(genre.doubanTag, DOUBAN_PAGE_SIZE, tab.doubanType, 0)
        }
        if (subjects.isEmpty()) {
            subjects = withContext(Dispatchers.IO) {
                douban.recommendations(
                    genre.doubanFallbackTag, DOUBAN_PAGE_SIZE, tab.doubanType, 0
                )
            }
        }
        val seen = snapshot.seenDoubanIds.toMutableSet()
        val existingTitles = snapshot.cards.map { it.title }.toHashSet()
        val fresh = subjects.filter {
            existingTitles.add(it.title) && seen.add(it.id.ifBlank { it.title })
        }
        if (fresh.isEmpty()) return snapshot
        return snapshot.copy(
            cards = (snapshot.cards + fresh.map { doubanCard(it) }).distinctBy { it.key },
            seenDoubanIds = seen.toSet(),
            nextDoubanStart = DOUBAN_PAGE_SIZE
        )
    }

    /**
     * Resolve a genre into (source, type_id) pairs.
     *
     * Sorting-only genres use the tab's usual sub-categories; named genres
     * look for a node whose name matches the aliases in *each* source's own
     * tree. If no collector exposes that node, the caller filters the merged
     * titles locally with [HomeGenre.keywords] instead.
     */
    private fun targetsFor(
        tab: HomeTab,
        genre: HomeGenre
    ): Pair<List<Pair<SourceConfig, Int>>, Boolean> {
        val defaultTargets = allCatalogs.flatMap { catalog ->
            classify(catalog.classes)[tab].orEmpty().take(2).map { c ->
                catalog.source to c.typeId
            }
        }.distinctBy { (source, typeId) -> "${source.url}|$typeId" }

        if (genre.isSortOnly()) return defaultTargets to false
        val matched = allCatalogs.flatMap { catalog ->
            catalog.classes
                .filter { c -> genre.categoryAliases.any { c.typeName.contains(it) } }
                .take(2)
                .map { c -> catalog.source to c.typeId }
        }.distinctBy { (source, typeId) -> "${source.url}|$typeId" }

        return if (matched.isNotEmpty()) matched to false else defaultTargets to true
    }

    private fun matchesGenre(group: TitleGroup, keywords: List<String>): Boolean {
        val haystack = buildString {
            append(group.typeName).append(' ').append(group.area).append(' ')
            append(group.title).append(' ')
            group.offers.take(2).forEach { offer ->
                append(offer.item.typeName).append(' ')
                append(offer.item.vodArea).append(' ')
                append(offer.item.vodRemarks).append(' ')
                append(offer.item.vodContent.take(60)).append(' ')
            }
        }.lowercase()
        return keywords.any { haystack.contains(it.lowercase()) }
    }

    private fun yearNumber(year: String): Int =
        year.filter { it.isDigit() }.take(4).toIntOrNull() ?: 0

    private fun sortGroups(groups: List<TitleGroup>, sort: FeedSort): List<TitleGroup> =
        when (sort) {
            FeedSort.NEW -> groups.sortedWith(
                compareByDescending<TitleGroup> { yearNumber(it.year) }
                    .thenByDescending { it.playableCount }
            )
            FeedSort.CLASSIC -> groups.sortedWith(
                compareBy<TitleGroup> {
                    val y = yearNumber(it.year)
                    if (y in 1900..2026) y else 9999
                }.thenByDescending { it.playableCount }
            )
            FeedSort.HOT -> groups.sortedWith(
                compareByDescending<TitleGroup> { it.playableCount }
                    .thenByDescending { it.bestQuality.rank }
                    .thenBy { if (it.fastestMs > 0L) it.fastestMs else Long.MAX_VALUE }
            )
        }

    private fun groupCard(group: TitleGroup): FeedCard = group.asFeedCard()

    private fun doubanCard(subject: DoubanSubject): FeedCard = FeedCard(
        key = "d|${subject.id.ifBlank { subject.title }}",
        title = subject.title,
        poster = subject.cover,
        cornerBadge = subject.rate.takeIf { it.isNotBlank() },
        badges = listOf(
            FeedBadge(subject.year.ifBlank { "新片" }, FeedBadgeKind.NEUTRAL, BadgeTag.YEAR),
            FeedBadge("豆瓣推荐", FeedBadgeKind.ACCENT, BadgeTag.DOUBAN),
            FeedBadge("全网搜源", FeedBadgeKind.LATENCY, BadgeTag.SOURCES)
        ),
        subject = subject
    )

    /** Commit one strip into the UI state and keep it cached for chip switches. */
    private fun publish(
        key: String,
        snapshot: FeedSnapshot,
        visible: Int,
        loading: Boolean,
        loadingMore: Boolean,
        doneTargets: Int = 0
    ) {
        if (!isActive(key)) return
        feedCache[key] = snapshot
        val tab = _state.value.homeTab
        val note = if (tab == HomeTab.RECOMMEND) {
            "豆瓣「${_state.value.genre}」· 已加载 ${snapshot.cards.size} 部 · " +
                "点击后自动在全部采集源里搜索播放地址"
        } else {
            val aggregated = snapshot.cards.count { it.group != null }
            "全源聚合「${_state.value.genre}」· ${snapshot.totalItems} 条资源合并为 " +
                "$aggregated 部（同名已合并）· 多源选源 / 单源直达播放"
        }
        _state.value = _state.value.copy(
            feed = snapshot.cards,
            feedVisible = visible,
            feedLoading = loading,
            feedLoadingMore = loadingMore,
            feedHasMore = snapshot.hasMore,
            feedTotalItems = snapshot.totalItems,
            feedNote = note,
            feedDoneTargets = doneTargets
        )
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

    /** Search a Douban title across the bundled sources and jump to the results. */
    fun searchDoubanTitle(subject: DoubanSubject) {
        val title = subject.title.trim()
        if (title.isEmpty()) return
        searchHistoryStore.add(title)
        _state.value = _state.value.copy(
            query = title,
            screen = Screen.RESULT,
            searching = true,
            results = emptyList(),
            searchedKeyword = title,
            // A Douban card was clicked on the recommend page, so BACK from
            // these results (and from anything opened out of them) must land
            // back on that page — never on the keyboard search screen.
            resultFrom = Screen.HOME,
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
            Screen.SOURCE_PICKER -> s.copy(screen = s.pickerFrom)
            Screen.RESULT -> s.copy(screen = s.resultFrom)
            // The source list is a sub-page of the settings page.
            Screen.SOURCES -> s.copy(screen = Screen.SETTINGS)
            Screen.SETTINGS -> s.copy(screen = Screen.HOME)
            Screen.SEARCH -> s.copy(screen = Screen.HOME)
            Screen.HOME -> s
        }
    }

    /**
     * Back target for a card opened from the results page. When the results
     * came from a Douban recommendation, detail / source-picker pages return
     * straight to the recommend page instead of unwinding through the results
     * and the keyboard search screen.
     */
    fun resultBackTarget(): Screen =
        if (_state.value.resultFrom == Screen.HOME) Screen.HOME else Screen.RESULT

    // ---------- Keyboard search input ----------

    /**
     * Type one character on the on-screen board (letter, digit or space). It
     * goes straight into the visible query, so the input field always mirrors
     * what the keyboard produced and the suggestion strip below reacts to the
     * very same text.
     */
    fun inputChar(ch: String) {
        if (ch.isEmpty()) return
        val s = _state.value
        _state.value = s.copy(query = s.query + ch)
    }

    /** Delete the last character of the visible query. */
    fun deleteLastChar() {
        val s = _state.value
        if (s.query.isEmpty()) return
        _state.value = s.copy(query = s.query.dropLast(1))
    }

    /** Empty the visible query. */
    fun clearInput() {
        _state.value = _state.value.copy(query = "")
    }

    /**
     * Suggestions for the strip directly under the input field. The text in
     * the field drives the strip: plain latin resolves through the pinyin
     * index, anything else matches bundled titles and recent keywords.
     */
    fun candidateTitles(): List<String> {
        val s = _state.value
        val initials = SearchSuggestions.pinyinInitials(s.query)
        val pinyin = SearchSuggestions.suggest(
            titleIndex = titleIndex,
            history = s.searchHistory,
            initials = initials,
            query = s.query
        )
        if (pinyin.isNotEmpty()) return pinyin
        // No pinyin hit: fall back to plain text matching, so a keyword that
        // really was typed still shows up in the strip.
        return SearchSuggestions.suggest(
            titleIndex = titleIndex,
            history = s.searchHistory,
            initials = "",
            query = s.query
        )
    }

    /**
     * A suggestion chip is a final choice: it must not merely fill the input
     * field — it jumps straight to the search results for that title.
     */
    fun pickCandidate(title: String) {
        startSearch(title, resolvePinyin = false)
    }

    /**
     * Latin input alone cannot match Chinese collectors, so map the typed
     * letters onto a bundled pinyin-initial index. Exact initials win;
     * otherwise the longest prefix match is used. When nothing matches, the
     * raw text stays and the search falls back to it.
     */
    private fun resolveQuery(text: String): String {
        val trimmed = text.trim()
        val letters = trimmed.replace(" ", "")
        if (letters.isNotEmpty() && letters.all { it in 'a'..'z' || it in 'A'..'Z' }) {
            val lower = letters.lowercase()
            titleIndex[lower]?.let { return it }
            val hit = titleIndex.entries
                .filter { (initials, _) -> initials.startsWith(lower) }
                .maxByOrNull { it.key.length }
            if (hit != null) return hit.value
        }
        return trimmed
    }

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
    }

    fun deleteSearchHistory(keyword: String) {
        searchHistoryStore.delete(keyword)
    }

    fun clearSearchHistory() {
        searchHistoryStore.clear()
    }

    /** Search the text currently in the input field. */
    fun submitSearch() {
        val typed = _state.value.query.trim()
        if (typed.isEmpty()) return
        startSearch(typed, resolvePinyin = true)
    }

    /** Search a keyword exactly as given, e.g. a chip in the history list. */
    fun searchKeyword(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        startSearch(kw, resolvePinyin = false)
    }

    private fun startSearch(keyword: String, resolvePinyin: Boolean) {
        val kw = (if (resolvePinyin) resolveQuery(keyword) else keyword).trim()
        if (kw.isEmpty()) return
        searchHistoryStore.add(kw)
        // The plain letters are kept for a fallback search pass.
        val letters = if (resolvePinyin) keyword.replace(" ", "").lowercase() else ""
        _state.value = _state.value.copy(
            screen = Screen.RESULT,
            query = kw,
            searching = true,
            results = emptyList(),
            searchedKeyword = kw,
            resultFrom = Screen.SEARCH,
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
     * One card, two destinations: several playable sources open the comparison
     * page, while a single-source title skips the extra step and goes straight
     * to the detail / episode picker. [from] keeps BACK wired to the page that
     * actually opened the card (home tab or search results).
     */
    fun openGroupOrDetail(group: TitleGroup, from: Screen = _state.value.screen) {
        if (group.playableCount <= 1) {
            val offer = group.offers.firstOrNull { !it.isTrailer }
                ?: group.offers.firstOrNull() ?: return
            _state.value = _state.value.copy(activeOffer = null, selectedGroup = null)
            openDetail(
                SearchResult(
                    sourceName = offer.sourceName,
                    item = offer.item,
                    sourceUrl = offer.sourceUrl,
                    latencyMs = offer.latencyMs
                ),
                from = from
            )
            return
        }
        _state.value = _state.value.copy(
            screen = Screen.SOURCE_PICKER,
            selectedGroup = group,
            pickerFrom = from,
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

    // ---------- settings ----------

    /** Rebuild the source list from the bundled list plus the viewer's edits. */
    private fun applySourceEntries(bundled: List<SourceConfig>) {
        bundledSources = bundled
        val entries = SourceMerger.merge(
            bundled = bundled,
            custom = sourceStore.customSources,
            removedUrls = sourceStore.removedUrls,
            disabledUrls = sourceStore.disabledUrls
        )
        _state.value = _state.value.copy(
            sourceEntries = entries,
            // Disabled collectors are skipped by every fan-out.
            sources = entries.filter { it.enabled }.map { it.config }
        )
    }

    /**
     * Re-probe the category trees of the currently enabled collectors.
     *
     * Every collector numbers its categories differently, so the home screen
     * needs each source's own name -> id tree. Enabling or disabling a
     * collector changes that set, so the trees are read again and the strip
     * of the tab the viewer is looking at is rebuilt.
     */
    private fun reloadCatalogs() {
        viewModelScope.launch {
            allCatalogs = repo.allCategoryCatalogs(_state.value.sources, client)
            _state.value = _state.value.copy(
                categorySourceCount = allCatalogs.size,
                categoryCatalogReady = true
            )
            // The cached strips were built from the old source set.
            feedCache.clear()
            val tab = _state.value.homeTab
            // Use the *currently selected* chip: the viewer may have switched
            // genre while the category trees were still being probed.
            val genre = tab.genres().firstOrNull { it.label == _state.value.genre }
            if (tab != HomeTab.HISTORY && tab != HomeTab.RECOMMEND && genre != null) {
                startFeed(tab, genre, reset = true)
            }
        }
    }

    fun setAdFilterEnabled(enabled: Boolean) {
        appSettings.adFilterEnabled = enabled
        _state.value = _state.value.copy(adFilterEnabled = enabled)
    }

    fun setPosterBadgesEnabled(enabled: Boolean) {
        appSettings.posterBadgesEnabled = enabled
        _state.value = _state.value.copy(badgeVisibility = appSettings.badgeVisibility)
    }

    /** Switch a single poster tag on or off. */
    fun setBadgeVisible(tag: BadgeTag, visible: Boolean) {
        appSettings.badgeVisibility = appSettings.badgeVisibility.with(tag, visible)
        _state.value = _state.value.copy(badgeVisibility = appSettings.badgeVisibility)
    }

    // ---------- source management ----------

    /** Switch one collector on or off. Disabled collectors stop being queried. */
    fun setSourceEnabled(url: String, enabled: Boolean) {
        val disabled = sourceStore.disabledUrls.toMutableSet()
        if (enabled) disabled.remove(url) else disabled.add(url)
        sourceStore.disabledUrls = disabled
        applySourceEntries(bundledSources)
        reloadCatalogs()
    }

    /** Delete a collector (bundled or custom). */
    fun removeSource(url: String) {
        val custom = sourceStore.customSources
        val isCustom = custom.any { it.url == url }
        if (isCustom) {
            sourceStore.customSources = custom.filterNot { it.url == url }
        } else {
            sourceStore.removedUrls = sourceStore.removedUrls + url
        }
        sourceStore.disabledUrls = sourceStore.disabledUrls - url
        applySourceEntries(bundledSources)
        reloadCatalogs()
    }

    /** Add a collector by hand. The name is optional; the URL is required. */
    fun addSource(name: String, url: String): Boolean {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty() || !cleanUrl.startsWith("http")) return false
        val exists = _state.value.sourceEntries.any { it.url == cleanUrl }
        if (exists) return false
        val cleanName = name.trim().ifEmpty { cleanUrl.substringAfter("://").substringBefore("/") }
        sourceStore.customSources = sourceStore.customSources + SourceConfig(cleanName, cleanUrl)
        applySourceEntries(bundledSources)
        reloadCatalogs()
        return true
    }

    /** Undo every source edit and go back to the bundled list. */
    fun resetSources() {
        sourceStore.reset()
        applySourceEntries(bundledSources)
        reloadCatalogs()
    }

    /** Test one collector and store its reachability and round-trip time. */
    fun testSource(url: String) {
        val source = _state.value.sourceEntries.firstOrNull { it.url == url }?.config ?: return
        if (_state.value.testingSource != null) return
        _state.value = _state.value.copy(testingSource = url)
        viewModelScope.launch {
            val health = repo.probeWithLatency(source, client)
            _state.value = _state.value.copy(
                sourceHealth = _state.value.sourceHealth + (url to health),
                testingSource = null
            )
        }
    }

    /** Test every collector at once; each row keeps its own result. */
    fun testAllSources() {
        if (_state.value.testingSource != null) return
        val targets = _state.value.sourceEntries.map { it.config }
        if (targets.isEmpty()) return
        _state.value = _state.value.copy(testingSource = "")
        viewModelScope.launch {
            val results = repo.probeAll(targets, client)
            _state.value = _state.value.copy(
                sourceHealth = _state.value.sourceHealth + results,
                testingSource = null
            )
        }
    }
}

