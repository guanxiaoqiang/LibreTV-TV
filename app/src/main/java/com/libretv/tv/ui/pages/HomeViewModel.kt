package com.libretv.tv.ui.pages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.libretv.tv.core.FanOut
import com.libretv.tv.core.Logger
import com.libretv.tv.di.AppContainer
import com.libretv.tv.domain.model.CategoryCatalog
import com.libretv.tv.domain.model.DoubanSubject
import com.libretv.tv.domain.model.FeedCard
import com.libretv.tv.domain.model.FeedCachePolicy
import com.libretv.tv.domain.model.FeedSnapshot
import com.libretv.tv.domain.model.HomeGenre
import com.libretv.tv.domain.model.HomeTab
import com.libretv.tv.domain.model.PlayRequest
import com.libretv.tv.domain.model.SearchResult
import com.libretv.tv.domain.model.SourceConfig
import com.libretv.tv.domain.model.TitleGroup
import com.libretv.tv.domain.model.WatchRecord
import com.libretv.tv.domain.model.doubanCard
import com.libretv.tv.domain.model.genres
import com.libretv.tv.domain.model.toCachedFeed
import com.libretv.tv.domain.model.toSnapshot
import com.libretv.tv.domain.usecase.BuildFeedSnapshot
import com.libretv.tv.domain.usecase.ResolveGenreTargets
import com.libretv.tv.ui.nav.Routes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 首页状态：标签、题材与聚合货架。 */
data class HomeState(
    val homeTab: HomeTab = HomeTab.RECOMMEND,
    val genre: String = "",
    val feed: List<FeedCard> = emptyList(),
    val feedVisible: Int = 0,
    val feedLoading: Boolean = false,
    val feedLoadingMore: Boolean = false,
    val feedRefreshing: Boolean = false,
    val feedHasMore: Boolean = true,
    val feedNote: String = "",
    val feedTotalItems: Int = 0,
    val feedDoneTargets: Int = 0,
    val feedTotalTargets: Int = 0,
    val categoryCatalogReady: Boolean = false,
    val categorySourceCount: Int = 0,
    val railFocusIndex: Int = 0
)

/**
 * 首页 ViewModel：只管首页这一页的状态与动作。
 *
 * 源清单、设置与历史都是应用级的（[AppContainer]），首页只订阅自己关心的部分。
 */
class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    /** 每个题材的货架快照：切回来时直接复用，不重新拉网。 */
    private val feedCache = mutableMapOf<String, FeedSnapshot>()

    /** 只有最新打开的货架才允许写进状态。 */
    private var activeFeedKey: String = ""

    /** 各源的分类树；切换标签时不必重新探测。 */
    private var allCatalogs: List<CategoryCatalog> = emptyList()

    /** 当前正在进行的首页请求。 */
    private var feedJob: Job? = null

    /** 滚动中：先攒着，停下来再一次性提交。 */
    private var feedPaused: Boolean = false
    private var pendingPublish: PendingPublish? = null
    private var pauseFlushJob: Job? = null

    init {
        viewModelScope.launch {
            // 源清单变化（开关 / 增删源）后重新探测分类树。
            container.sources.collect { sources ->
                if (sources.isNotEmpty()) reloadCatalogs()
            }
        }
        // 推荐条带不依赖分类树，先把它打开。
        openFeed(HomeTab.RECOMMEND, HomeTab.RECOMMEND.genres().first())
    }

    // ---------- home tabs, genre chips & segmented grid ----------

    /**
     * 缓存键：`v2|TAB|genre|<源签名>`。
     *
     * 签名进键（§D.2）：源集变化后键直接变，内存与磁盘的旧条目
     * 都不再被命中，不需要任何手工失效。
     */
    private fun feedKey(tab: HomeTab, genre: String) =
        FeedCachePolicy.cacheKey(tab.name, genre, container.sourceSignature)

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
        openFeed(tab, genre)
    }

    fun setGenre(label: String) {
        val tab = _state.value.homeTab
        if (tab == HomeTab.HISTORY || _state.value.genre == label) return
        val genre = tab.genres().firstOrNull { it.label == label } ?: return
        feedJob?.cancel()
        openFeed(tab, genre)
    }

    /**
     * Show the strip of (tab, genre) as fast as possible, then refresh it.
     *
     * Two caches work together here. The in-memory one keeps the strips the
     * viewer already saw in this session; the local database keeps them
     * across restarts. Either way the cards are on screen before the network
     * answers.
     *
     * The refresh runs after [FEED_REFRESH_DELAY_MS]: the viewer browses with
     * the D-pad and only a tab that is actually watched deserves a fan-out
     * request. The refresh never blanks the grid — the cached cards stay
     * visible until the new strip is complete.
     */
    private fun openFeed(tab: HomeTab, genre: HomeGenre) {
        val key = feedKey(tab, genre.label)
        activeFeedKey = key
        // A new page must never wait for a hold-back that belongs to the page
        // the viewer just left.
        releasePause()
        _state.value = _state.value.copy(
            homeTab = tab,
            genre = genre.label,
            feedLoadingMore = false,
            feedRefreshing = false,
            feedTotalTargets = 0
        )
        val cached = feedCache[key]
        if (cached != null) {
            // Re-entering a cached tab must not blank the grid and start a
            // fresh network round: that produced a visible flash on every
            // D-pad move.
            publish(key, cached, minOf(FEED_PAGE_SIZE, cached.cards.size), false, false, persist = false)
        } else {
            _state.value = _state.value.copy(
                feed = emptyList(),
                feedVisible = 0,
                feedLoading = true,
                feedHasMore = true,
                feedNote = "正在加载「${genre.label}」…",
                feedTotalItems = 0,
                feedDoneTargets = 0
            )
        }
        feedJob = viewModelScope.launch {
            if (feedCache[key] == null) {
                val stored = container.feedCache.read(key, container.sourceSignature)
                if (stored != null && isActive(key)) {
                    val snapshot = stored.toSnapshot()
                    feedCache[key] = snapshot
                    publish(
                        key,
                        snapshot,
                        minOf(FEED_PAGE_SIZE, snapshot.cards.size),
                        false,
                        false,
                        persist = false
                    )
                }
            }
            delay(FEED_REFRESH_DELAY_MS)
            if (!isActive(key)) return@launch
            refreshFeed(tab, genre, key)
        }
    }

    /**
     * Re-read one strip from the network without disturbing the grid.
     *
     * The cached cards stay on screen while the collectors answer; only when
     * the new strip is complete does it replace the old one. That keeps the
     * grid stable for a viewer who is already scrolling.
     */
    private suspend fun refreshFeed(tab: HomeTab, genre: HomeGenre, key: String) {
        val previous = feedCache[key]
        _state.value = _state.value.copy(feedRefreshing = true)
        try {
            if (tab == HomeTab.RECOMMEND) {
                loadDoubanPage(tab, genre, 0, append = false, key = key, quiet = true)
            } else {
                loadCategoryPage(tab, genre, 1, append = false, key = key, quiet = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed round must not blank the strip the cache had painted.
            return
        } finally {
            // The flag must also go down when the round was cancelled —
            // otherwise the infinite scroll stays blocked for the rest of the
            // session.
            _state.value = _state.value.copy(feedRefreshing = false)
        }
        if (previous != null && previous.cards.isNotEmpty() && _state.value.feed.isEmpty()) {
            publish(
                key,
                previous,
                refreshVisible(previous.cards.size),
                false,
                false,
                persist = false
            )
        }
    }

    /**
     * Called by the grid when the viewer reaches the bottom of the list.
     *
     * Two things happen at once: the cards that are already loaded are
     * revealed (that costs no network), and the next page of source results /
     * Douban recommendations is fetched. The new cards are appended below the
     * ones already on screen — the list is never rebuilt or re-sorted.
     */
    fun loadMore() {
        val s = _state.value
        // A background refresh must not block the viewer: the append below
        // replaces it. The refresh flag is not a reason to sit still at the
        // bottom of the grid.
        if (s.feedLoading || s.feedLoadingMore || !s.feedHasMore ||
            s.homeTab == HomeTab.HISTORY
        ) return
        val key = activeFeedKey
        val snapshot = feedCache[key] ?: return
        if (s.feedVisible < s.feed.size) {
            // Already loaded cards: show them right away.
            _state.value = s.copy(
                feedVisible = minOf(s.feedVisible + FEED_PAGE_SIZE, s.feed.size)
            )
        }
        val tab = s.homeTab
        val genre = tab.genres().firstOrNull { it.label == s.genre } ?: return
        // Keep the reveal above: the state is read again so the freshly
        // revealed cards are not dropped by the loading flag.
        _state.value = _state.value.copy(feedLoadingMore = true)
        feedJob?.cancel()
        feedJob = viewModelScope.launch {
            try {
                if (tab == HomeTab.RECOMMEND) {
                    loadDoubanPage(tab, genre, snapshot.nextDoubanStart, append = true, key = key)
                } else {
                    loadCategoryPage(tab, genre, snapshot.nextSourcePage, append = true, key = key)
                }
            } finally {
                // The hint at the bottom of the grid must never stick: a
                // failed round has to release the grid again.
                if (isActive(key)) {
                    _state.value = _state.value.copy(feedLoadingMore = false)
                }
            }
        }
    }

    /** Douban-backed strip used by 推荐 (and as a discovery fallback elsewhere). */
    private suspend fun loadDoubanPage(
        tab: HomeTab,
        genre: HomeGenre,
        pageStart: Int,
        append: Boolean,
        key: String,
        quiet: Boolean = false
    ) {
        var subjects = withContext(Dispatchers.IO) {
            container.content.doubanRecommendations(genre.doubanTag, DOUBAN_PAGE_SIZE, tab.doubanType, pageStart)
                .getOrNull().orEmpty()
        }
        if (subjects.isEmpty() && genre.doubanFallbackTag != genre.doubanTag) {
            subjects = withContext(Dispatchers.IO) {
                container.content.doubanRecommendations(
                    genre.doubanFallbackTag, DOUBAN_PAGE_SIZE, tab.doubanType, 0
                ).getOrNull().orEmpty()
            }
        }
        if (!isActive(key)) return
        // Same rule as the source pages: continue from the strip that is
        // already on screen and only append.
        val old = feedCache[key]
        val seen = old?.seenDoubanIds.orEmpty().toMutableSet()
        val fresh = subjects.filter { seen.add(it.id.ifBlank { it.title }) }
        val cards = (old?.cards.orEmpty() + fresh.map { doubanCard(it) })
            .distinctBy { it.key }
        // The cursor only moves forward, a refresh never rewinds it.
        val nextStart = maxOf(pageStart + DOUBAN_PAGE_SIZE, old?.nextDoubanStart ?: 0)
        val computedHasMore = fresh.isNotEmpty() &&
            subjects.size >= DOUBAN_PAGE_SIZE / 2 &&
            nextStart <= MAX_DOUBAN_START &&
            cards.size < 160
        // A quiet refresh must not take the paging away from a strip that
        // still has more pages.
        val hasMore = if (quiet) old?.hasMore ?: computedHasMore else computedHasMore
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
        } else if (quiet) {
            // A refresh must not move the grid under the viewer's finger.
            refreshVisible(cards.size)
        } else {
            minOf(FEED_PAGE_SIZE, cards.size)
        }
        publish(key, snapshot, visible, false, false)
    }

    /**
     * How many cards stay visible after a background refresh.
     *
     * The viewer may already be deep in the grid when the refresh lands.
     * Keeping the current scroll depth avoids a jump back to the top.
     */
    private fun refreshVisible(cardCount: Int): Int = minOf(
        cardCount,
        maxOf(_state.value.feedVisible, minOf(FEED_PAGE_SIZE, cardCount))
    )

    /** All-source fan-out for one tab + genre, one source page at a time. */
    private suspend fun loadCategoryPage(
        tab: HomeTab,
        genre: HomeGenre,
        page: Int,
        append: Boolean,
        key: String,
        quiet: Boolean = false
    ) {
        val (targets, keywordFilter) = targetsFor(tab, genre)
        if (!isActive(key)) return
        if (!quiet) {
            _state.value = _state.value.copy(
                feedTotalTargets = targets.size,
                feedDoneTargets = 0,
                feedNote = if (targets.isEmpty()) "没有匹配的子分类，正在读取豆瓣推荐…"
                else "正在汇总 ${targets.size} 个采集源的「${genre.label}」片单…"
            )
        }

        // The strip is append-only: every round continues from what is already
        // on screen and only adds to it. Rebuilding the whole list from scratch
        // re-sorts the grid and reads as a global refresh on a TV.
        val old = feedCache[key]
        val raw = old?.rawResults.orEmpty().toMutableList()
        val seen = old?.seenResultKeys.orEmpty().toMutableSet()
        // The head of the grid is fixed once it has been shown. Every later
        // collector response may only append cards; it must never re-sort or
        // rebuild the first screen.
        var stableCards = old?.cards.orEmpty()
        var publishedVisible = if (append) _state.value.feedVisible else 0

        var done = 0
        // 扇出走统一预算：并发上限、单项超时与总预算都在 [FanOut] 里，
        // 增量上屏由 `onEach` 回调完成——第一个采集源回来就能上屏。
        FanOut.forEach(
            items = targets,
            perItemTimeoutMs = 8_000L,
            onEach = { part ->
                if (!isActive(key)) return@forEach
                raw.addAll(part.filter { seen.add("${it.sourceUrl}|${it.item.vodId}") })
                done++
                // Aggregation is CPU work (grouping + sorting hundreds of
                // entries per collector answer); keep it off the main thread
                // so the grid stays responsive while a round is in flight.
                val snapshot = withContext(Dispatchers.Default) {
                    buildSnapshot(genre, raw, seen, old, keywordFilter, page, stableCards)
                }
                stableCards = snapshot.cards
                // Publish only while the visible first screen is still filling.
                // Once it is full, keep aggregating in the background and do a
                // single final publish — no repeated rebuild of the head cards.
                // feedLoading stays true until the round completes so the
                // infinite-scroll trigger cannot cancel this job mid-flight.
                if (!append && !quiet) {
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
            },
            block = { (source, typeId) ->
                try {
                    container.content.categoryShelf(source, typeId, page)
                } catch (e: Exception) {
                    Logger.w(TAG, "分类片单失败：${source.name}（${e.message}）")
                    emptyList()
                }
            }
        )
        if (!isActive(key)) return

        // One, and only one, metadata refresh after the round: merge the extra
        // sources into existing groups while keeping their slots untouched.
        // An appending round never touches the cards that are already on
        // screen — it only adds new ones below them.
        var snapshot = withContext(Dispatchers.Default) {
            buildSnapshot(
                genre, raw, seen, old, keywordFilter, page, stableCards,
                refreshExisting = !append
            )
        }
        // A quiet refresh must never take the paging away from a strip that
        // still has more pages.
        if (quiet && !snapshot.hasMore && old?.hasMore == true) {
            snapshot = snapshot.copy(hasMore = true)
        }
        // When a collector has no such sub-category, Douban still gives the
        // chip a meaningful grid (e.g. 美剧 / 英剧 / 韩剧).
        if (snapshot.cards.size < 12 && !append) {
            snapshot = supplementWithDouban(tab, genre, snapshot)
        }
        val visible = if (append) {
            minOf(snapshot.cards.size, _state.value.feedVisible + FEED_PAGE_SIZE)
        } else if (quiet) {
            // A refresh must not move the grid under the viewer's finger.
            refreshVisible(snapshot.cards.size)
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
    ): FeedSnapshot = BuildFeedSnapshot(
        genre = genre,
        raw = raw,
        seen = seen,
        old = old,
        keywordFilter = keywordFilter,
        page = page,
        previousCards = previousCards,
        refreshExisting = refreshExisting
    )

    /** Fill a thin strip with Douban discoveries so a genre is never empty. */
    private suspend fun supplementWithDouban(
        tab: HomeTab,
        genre: HomeGenre,
        snapshot: FeedSnapshot
    ): FeedSnapshot {
        var subjects = withContext(Dispatchers.IO) {
            container.content.doubanRecommendations(genre.doubanTag, DOUBAN_PAGE_SIZE, tab.doubanType, 0)
                .getOrNull().orEmpty()
        }
        if (subjects.isEmpty()) {
            subjects = withContext(Dispatchers.IO) {
                container.content.doubanRecommendations(
                    genre.doubanFallbackTag, DOUBAN_PAGE_SIZE, tab.doubanType, 0
                ).getOrNull().orEmpty()
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
            nextDoubanStart = maxOf(snapshot.nextDoubanStart, DOUBAN_PAGE_SIZE)
        )
    }

    /**
     * Resolve a genre into (source, type_id) pairs; delegates to the use case.
     */
    private fun targetsFor(
        tab: HomeTab,
        genre: HomeGenre
    ): Pair<List<Pair<SourceConfig, Int>>, Boolean> =
        ResolveGenreTargets(allCatalogs, tab, genre)

    /** Commit one strip into the UI state and keep it cached for chip switches. */
    private fun publish(
        key: String,
        snapshot: FeedSnapshot,
        visible: Int,
        loading: Boolean,
        loadingMore: Boolean,
        doneTargets: Int = 0,
        persist: Boolean = true
    ) {
        if (!isActive(key)) return
        feedCache[key] = snapshot
        // While the grid is being scrolled, a new card list would force the
        // LazyGrid to rebuild mid-gesture and hitch the frame. Hold the strip
        // back and commit it the moment the viewer stops scrolling.
        if (feedPaused) {
            // The flags must stay current even while the cards are held back:
            // a stale "loading" flag would block the infinite scroll and leave
            // an empty grid behind.
            _state.value = _state.value.copy(
                feedLoading = loading,
                feedLoadingMore = loadingMore,
                feedHasMore = snapshot.hasMore,
                feedTotalItems = snapshot.totalItems,
                feedDoneTargets = doneTargets
            )
            pendingPublish = PendingPublish(key, snapshot, visible, loading, loadingMore, doneTargets, persist)
            return
        }
        publishNow(key, snapshot, visible, loading, loadingMore, doneTargets, persist)
    }

    /** The paused publish, flushed as-is once scrolling stops. */
    private data class PendingPublish(
        val key: String,
        val snapshot: FeedSnapshot,
        val visible: Int,
        val loading: Boolean,
        val loadingMore: Boolean,
        val doneTargets: Int,
        val persist: Boolean
    )

    private fun publishNow(
        key: String,
        snapshot: FeedSnapshot,
        visible: Int,
        loading: Boolean,
        loadingMore: Boolean,
        doneTargets: Int,
        persist: Boolean
    ) {
        if (!isActive(key)) return
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
        // A finished strip is written to the local database, so the next start
        // and the next tab switch paint cards without touching the network.
        if (persist && !loading) {
            val signature = container.sourceSignature
            viewModelScope.launch {
                container.feedCache.write(
                    snapshot.toCachedFeed(key, System.currentTimeMillis()),
                    signature
                )
            }
        }
    }

    /**
     * Pause / resume UI publication of feed data.
     *
     * The grid sets this while it is being scrolled. Aggregation keeps running
     * in the background — the work is never wasted — but the new cards are
     * committed only after the gesture ends, so the list never rebuilds under
     * the viewer's finger.
     */
    fun setFeedPaused(paused: Boolean) {
        if (feedPaused == paused) return
        feedPaused = paused
        pauseFlushJob?.cancel()
        pauseFlushJob = null
        if (paused) {
            // Safety net: a page that leaves the composition while it is being
            // scrolled never reports "stopped" again, so the hold-back has to
            // expire on its own. Without it the next tab would wait for data
            // that is never committed.
            pauseFlushJob = viewModelScope.launch {
                delay(PAUSE_HOLD_MAX_MS)
                releasePause()
            }
        } else {
            flushPendingPublish()
        }
    }

    /** Drop the hold-back and commit whatever was waiting. */
    private fun releasePause() {
        pauseFlushJob?.cancel()
        pauseFlushJob = null
        feedPaused = false
        flushPendingPublish()
    }

    /** Commit the held-back strip, if there is one. */
    private fun flushPendingPublish() {
        val pending = pendingPublish ?: return
        pendingPublish = null
        publishNow(
            pending.key,
            pending.snapshot,
            pending.visible,
            pending.loading,
            pending.loadingMore,
            pending.doneTargets,
            pending.persist
        )
    }
    private fun reloadCatalogs(invalidateCache: Boolean = false) {
        viewModelScope.launch {
            allCatalogs = container.content.allCategoryCatalogs(container.sources.value)
            _state.value = _state.value.copy(
                categorySourceCount = allCatalogs.size,
                categoryCatalogReady = true
            )
            if (invalidateCache) {
                // The cached strips were built from the old source set. The DB
                // rows expire on their own (signature in the key, §D.2); the
                // in-memory map is keyed by the same signature-bearing key, so
                // dropping the local entries is enough here.
                feedCache.clear()
            }
            val tab = _state.value.homeTab
            // Use the *currently selected* chip: the viewer may have switched
            // genre while the category trees were still being probed.
            val genre = tab.genres().firstOrNull { it.label == _state.value.genre }
            if (tab != HomeTab.HISTORY && tab != HomeTab.RECOMMEND && genre != null) {
                openFeed(tab, genre)
            }
        }
    }

    // ---------- 标题 → 下一站 ----------

    /**
     * 打开一个标题组：只有单一来源时直接进详情，多个来源时先进选源页比较。
     */
    fun openGroupOrDetail(group: TitleGroup) {
        val route = container.openGroupOrDetail(group)
        if (route.isNotEmpty()) container.navigate(route)
    }

    /**
     * 豆瓣卡片点进来：按片名跨源搜索。
     *
     * 结果页的返回目标是首页（而不是键盘搜索页），这由导航栈记录。
     */
    fun searchDoubanTitle(subject: DoubanSubject) {
        val title = subject.title.trim()
        if (title.isEmpty()) return
        container.addSearchHistory(title)
        container.beginSearch(title)
        container.navigate(Routes.RESULT)
        viewModelScope.launch {
            val found = container.content.searchAll(container.sources.value, title)
            container.publishSearchResults(found)
        }
    }

    // ---------- 历史 ----------

    /**
     * 恢复一条历史记录：重新解析详情并给出起播请求。
     *
     * 历史里存的地址会过期，所以不盲用旧 url——详情重新拉一遍，
     * 拿到的地址才作数。
     */
    suspend fun resumeFromHistory(record: WatchRecord): PlayRequest? =
        container.resumeFromHistory(record)

    /** 删除一条观看历史。 */
    fun deleteHistory(key: String) = container.deleteHistory(key)

    /** 清空观看历史。 */
    fun clearHistory() = container.clearHistory()
}
