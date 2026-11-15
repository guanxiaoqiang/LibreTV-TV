package com.libretv.tv

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.libretv.tv.data.AdFilterSettings
import com.libretv.tv.data.CmsClient
import com.libretv.tv.data.DoubanSubject
import com.libretv.tv.data.PlayRequest
import com.libretv.tv.data.TitleGroup
import com.libretv.tv.data.SourceConfig
import com.libretv.tv.data.SourceRepository
import com.libretv.tv.data.VodItem
import com.libretv.tv.data.WatchRecord
import com.libretv.tv.ui.CategoryHint
import com.libretv.tv.ui.DetailScreen
import com.libretv.tv.ui.FocusableTile
import com.libretv.tv.ui.HistoryPage
import com.libretv.tv.ui.HomeGenre
import com.libretv.tv.ui.HomeTab
import com.libretv.tv.ui.NavItem
import com.libretv.tv.ui.PlayerScreen
import com.libretv.tv.ui.ResultScreen
import com.libretv.tv.ui.Screen
import com.libretv.tv.ui.SearchScreen
import com.libretv.tv.ui.SourcePickerScreen
import com.libretv.tv.ui.SourcesScreen
import com.libretv.tv.ui.TvBackgroundBrush
import com.libretv.tv.ui.TvColors
import com.libretv.tv.ui.TvViewModel
import com.libretv.tv.ui.UiState
import com.libretv.tv.ui.UnifiedPosterCard
import com.libretv.tv.ui.genres
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(TvBackgroundBrush)
            ) {
                TvApp()
            }
        }
    }
}

/**
 * One entry of the left rail. Either a tab (`tab != null`) or an action entry
 * (`action` 0 = search screen, 1 = sources screen).
 */
private data class RailEntry(
    val label: String,
    val badge: String? = null,
    val icon: ImageVector,
    val tab: HomeTab? = null,
    val action: Int? = null
)

private fun tabIcon(tab: HomeTab): ImageVector = when (tab) {
    HomeTab.RECOMMEND -> Icons.Filled.ThumbUp
    HomeTab.HISTORY -> Icons.Filled.History
    HomeTab.MOVIE -> Icons.Filled.Movie
    HomeTab.SERIES -> Icons.Filled.Tv
    HomeTab.VARIETY -> Icons.Filled.Theaters
    HomeTab.ANIME -> Icons.Filled.Animation
    HomeTab.DOCUMENTARY -> Icons.Filled.Explore
    HomeTab.SHORT -> Icons.Filled.OndemandVideo
}

@Composable
fun TvApp(vm: TvViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val scope = rememberCoroutineScope()
    val repo = remember { SourceRepository(app) }
    val client = remember { CmsClient() }
    val adFilterSettings = remember { AdFilterSettings(app) }
    var adFilterEnabled by remember { mutableStateOf(adFilterSettings.enabled) }

    // The whole playback request lives here rather than as loose strings: the
    // player needs the url, while the history store needs the source and the
    // episode coordinates so the entry can be resumed later.
    var playTarget by remember { mutableStateOf<PlayRequest?>(null) }

    /** Rebuild the detail state behind a history entry, then jump to the player. */
    val resumeRecord: (WatchRecord) -> Unit = { record ->
        vm.applyResume(null, 0, 0)
        vm.navigate(Screen.DETAIL)
        scope.launch {
            val source = SourceConfig(record.sourceName, record.sourceUrl)
            val item = VodItem(
                vodId = record.vodId,
                vodName = record.title,
                vodPic = record.poster,
                typeName = record.typeName
            )
            val bundle = if (record.sourceUrl.isNotBlank() && record.vodId.isNotBlank()) {
                withContext(Dispatchers.IO) { repo.fetchDetail(source, item, client) }
            } else {
                null
            }
            vm.applyResume(bundle, record.lineIndex, record.episodeIndex)
            val freshUrl = bundle?.lines?.getOrNull(record.lineIndex)
                ?.episodes?.getOrNull(record.episodeIndex)?.url
                ?.takeIf { it.isNotBlank() }
            playTarget = PlayRequest(
                title = record.title,
                poster = record.poster,
                episodeName = record.episodeName,
                rawUrl = freshUrl ?: record.rawUrl,
                sourceName = record.sourceName,
                sourceUrl = record.sourceUrl,
                vodId = record.vodId,
                lineIndex = record.lineIndex,
                episodeIndex = record.episodeIndex,
                startPositionMs = record.positionMs,
                typeName = record.typeName
            )
            vm.navigate(Screen.PLAYER)
        }
    }

    BackHandler(enabled = state.screen != Screen.HOME || state.homeTab != HomeTab.RECOMMEND) {
        if (state.screen != Screen.HOME) {
            if (state.screen == Screen.PLAYER) playTarget = null
            vm.back()
        } else {
            vm.setHomeTab(HomeTab.RECOMMEND)
        }
    }

    when (state.screen) {
        Screen.HOME -> HomeLayout(
            state = state,
            onSearch = { vm.navigate(Screen.SEARCH) },
            onSources = { vm.navigate(Screen.SOURCES) },
            onSelectTab = { vm.setHomeTab(it) },
            onSelectGenre = { vm.setGenre(it) },
            onLoadMore = { vm.loadMore() },
            onPickDouban = { vm.searchDoubanTitle(it) },
            onPickGroup = { vm.openGroupOrDetail(it, Screen.HOME) },
            onResume = resumeRecord,
            onDeleteHistory = { vm.deleteHistory(it) },
            onClearHistory = { vm.clearHistory() }
        )

        Screen.SEARCH -> SearchScreen(
            query = state.query,
            candidates = vm.candidateTitles(),
            onInput = { vm.inputLetter(it) },
            onBackspace = { vm.t9Backspace() },
            onSubmit = { vm.submitSearch() },
            onClear = { vm.t9Clear() },
            onPickCandidate = { vm.pickCandidate(it) }
        )

        Screen.RESULT -> ResultScreen(
            keyword = state.searchedKeyword,
            results = state.results,
            searching = state.searching,
            onOpenGroup = { vm.openGroupOrDetail(it, Screen.RESULT) },
            onBack = { vm.back() }
        )

        Screen.SOURCE_PICKER -> {
            val group = state.selectedGroup
            if (group == null) {
                vm.back()
            } else {
                SourcePickerScreen(
                    group = group,
                    probes = state.offerProbes,
                    onPick = { vm.pickOffer(it) },
                    onBack = { vm.back() }
                )
            }
        }

        Screen.DETAIL -> DetailScreen(
            detail = state.detail,
            loading = state.detail == null && state.error == null,
            error = state.error,
            activeLine = state.activeLine,
            onSelectLine = { vm.selectLine(it) },
            onPlay = { line, ep ->
                val d = state.detail ?: return@DetailScreen
                val episode = d.lines.getOrNull(line)?.episodes?.getOrNull(ep)
                    ?: return@DetailScreen
                val sourceUrl = state.activeOffer?.sourceUrl?.takeIf { it.isNotBlank() }
                    ?: state.sources.firstOrNull { it.name == d.sourceName }?.url.orEmpty()
                vm.selectEpisode(ep)
                playTarget = PlayRequest(
                    title = d.item.vodName,
                    poster = d.item.vodPic,
                    episodeName = episode.name,
                    rawUrl = episode.url,
                    sourceName = d.sourceName,
                    sourceUrl = sourceUrl,
                    vodId = d.item.vodId,
                    lineIndex = line,
                    episodeIndex = ep,
                    typeName = d.item.typeName
                )
                vm.navigate(Screen.PLAYER)
            },
            onBack = { vm.back() },
            offer = state.activeOffer,
            onSwitchSource = if (
                state.detailFrom == Screen.SOURCE_PICKER && state.selectedGroup != null
            ) {
                { vm.navigate(Screen.SOURCE_PICKER) }
            } else null
        )

        Screen.PLAYER -> {
            val request = playTarget
            if (request == null) {
                vm.back()
            } else {
                val d = state.detail
                val curLine = state.activeLine
                val eps = d?.lines?.getOrNull(curLine)?.episodes.orEmpty()
                val curIdx = state.activeEpisode
                PlayerScreen(
                    title = request.title,
                    episodeName = request.episodeName,
                    rawUrl = request.rawUrl,
                    episodes = eps,
                    currentEpisodeIndex = curIdx,
                    startPositionMs = request.startPositionMs,
                    adFilterEnabled = adFilterEnabled,
                    onProgress = { position, duration ->
                        vm.saveProgress(request, position, duration)
                    },
                    onBack = {
                        playTarget = null
                        vm.back()
                    },
                    onNextEpisode = if (curIdx < eps.size - 1) {
                        {
                            val nxt = eps[curIdx + 1]
                            vm.selectEpisode(curIdx + 1)
                            playTarget = request.copy(
                                episodeName = nxt.name,
                                rawUrl = nxt.url,
                                episodeIndex = curIdx + 1,
                                startPositionMs = 0L
                            )
                        }
                    } else null,
                    onPrevEpisode = if (curIdx > 0) {
                        {
                            val prv = eps[curIdx - 1]
                            vm.selectEpisode(curIdx - 1)
                            playTarget = request.copy(
                                episodeName = prv.name,
                                rawUrl = prv.url,
                                episodeIndex = curIdx - 1,
                                startPositionMs = 0L
                            )
                        }
                    } else null,
                    onSelectEpisode = { index ->
                        eps.getOrNull(index)?.let { picked ->
                            vm.selectEpisode(index)
                            playTarget = request.copy(
                                episodeName = picked.name,
                                rawUrl = picked.url,
                                episodeIndex = index,
                                startPositionMs = 0L
                            )
                        }
                    }
                )
            }
        }

        Screen.SOURCES -> SourcesScreen(
            sources = state.sources,
            health = state.health,
            checking = state.checkingHealth,
            adFilterEnabled = adFilterEnabled,
            onToggleAdFilter = {
                adFilterEnabled = !adFilterEnabled
                adFilterSettings.enabled = adFilterEnabled
            },
            onCheck = { vm.checkHealth() },
            onBack = { vm.back() }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HomeLayout(
    state: UiState,
    onSearch: () -> Unit,
    onSources: () -> Unit,
    onSelectTab: (HomeTab) -> Unit,
    onSelectGenre: (String) -> Unit,
    onLoadMore: () -> Unit,
    onPickDouban: (DoubanSubject) -> Unit,
    onPickGroup: (TitleGroup) -> Unit,
    onResume: (WatchRecord) -> Unit,
    onDeleteHistory: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    val tabs = remember { HomeTab.entries.toList() }
    // The whole navigation lives on the left rail: search, then every home tab
    // in display order, then source management. One requester per entry, with
    // wrap-around up/down, keeps the D-pad cycling inside the rail.
    val railEntries = remember(state.history.size, state.sources.size) {
        buildList {
            add(RailEntry("搜索", icon = Icons.Filled.Search, action = 0))
            tabs.forEach { tab ->
                add(
                    RailEntry(
                        label = tab.label,
                        badge = if (tab == HomeTab.HISTORY && state.history.isNotEmpty()) {
                            "${state.history.size}"
                        } else null,
                        icon = tabIcon(tab),
                        tab = tab
                    )
                )
            }
            add(RailEntry("源管理", badge = "${state.sources.size}", icon = Icons.Filled.Storage, action = 1))
        }
    }
    val railRequesters = remember { List(railEntries.size) { FocusRequester() } }
    val railBringers = remember { List(railEntries.size) { BringIntoViewRequester() } }
    val railScroll = rememberScrollState()

    LaunchedEffect(Unit) {
        railRequesters[0].requestFocus()
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            // Keep the left rail clear of the TV overscan area. Without this
            // start inset the focused nav tile (and its scale-up glow) sits
            // flush against the physical screen edge.
            .padding(start = 24.dp, top = 22.dp, bottom = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .width(178.dp)
                .verticalScroll(railScroll),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "LibreTV",
                color = TvColors.OnSurface,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 14.dp, bottom = 12.dp)
            )

            railEntries.forEachIndexed { index, entry ->
                FocusableTile(
                    onClick = {
                        when {
                            entry.action == 0 -> onSearch()
                            entry.action == 1 -> onSources()
                            entry.tab != null -> onSelectTab(entry.tab)
                        }
                    },
                    scaleWhenFocused = 1.05f,
                    modifier = Modifier
                        .focusRequester(railRequesters[index])
                        .focusProperties {
                            up = railRequesters[(index - 1 + railEntries.size) % railEntries.size]
                            down = railRequesters[(index + 1) % railEntries.size]
                        }
                ) { focused ->
                    if (focused) {
                        // Keep the focused entry visible while scrolling the
                        // rail; tab entries select on focus, matching the old
                        // top tab strip behaviour.
                        LaunchedEffect(entry.tab) {
                            railBringers[index].bringIntoView()
                            entry.tab?.let(onSelectTab)
                        }
                    }
                    NavItem(
                        label = entry.label,
                        focused = focused,
                        badge = entry.badge,
                        selected = entry.tab == state.homeTab,
                        icon = entry.icon
                    )
                }
            }
        }

        Column(modifier = Modifier.weight(1f).fillMaxSize()) {
            if (state.homeTab == HomeTab.HISTORY) {
                HistoryPage(
                    records = state.history,
                    onResume = onResume,
                    onDelete = onDeleteHistory,
                    onClear = onClearHistory,
                    modifier = Modifier.weight(1f)
                )
            } else {
                HomeFeedPage(
                    state = state,
                    onSelectGenre = onSelectGenre,
                    onPickGroup = onPickGroup,
                    onPickDouban = onPickDouban,
                    onLoadMore = onLoadMore
                )
            }
        }
    }
}

/**
 * Content page shared by every tab but 历史: a genre strip on top and one
 * vertically scrolling grid below it. The grid only reveals one segment at a
 * time and asks for the next one when focus approaches the last visible card.
 */
@Composable
private fun HomeFeedPage(
    state: UiState,
    onSelectGenre: (String) -> Unit,
    onPickGroup: (TitleGroup) -> Unit,
    onPickDouban: (DoubanSubject) -> Unit,
    onLoadMore: () -> Unit
) {
    val genres = remember(state.homeTab) { state.homeTab.genres() }
    val chipRequesters = remember(genres.size) { List(genres.size) { FocusRequester() } }
    val gridState = rememberLazyGridState()

    // A tab / genre switch always starts at the top of its own grid.
    LaunchedEffect(state.homeTab, state.genre) {
        gridState.scrollToItem(0)
    }

    // Infinite scroll: read the newest state from inside a single long-lived
    // flow instead of restarting the collector on every recomposition.
    val latestState by rememberUpdatedState(state)
    val latestLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(gridState) {
        snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        }.collect { last ->
            val s = latestState
            if (s.feedHasMore && !s.feedLoading && !s.feedLoadingMore &&
                s.feedVisible > 0 && last >= s.feedVisible - 6
            ) {
                latestLoadMore()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        FeedHeader(state)
        GenreStrip(
            genres = genres,
            current = state.genre,
            requesters = chipRequesters,
            onSelect = onSelectGenre
        )

        if (state.feedLoading && state.feed.isEmpty()) {
            FeedCenteredLoading(
                text = state.feedNote.ifBlank { "正在加载「${state.genre}」…" } +
                    if (state.feedTotalTargets > 0) {
                        " (${state.feedDoneTargets}/${state.feedTotalTargets})"
                    } else ""
            )
        } else if (state.feed.isEmpty() && !state.feedHasMore && !state.feedLoading) {
            CategoryHint("该类型暂时没有片单，可切换上方类型或使用遥控器搜索")
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = gridState,
            contentPadding = PaddingValues(
                start = 40.dp, end = 40.dp, top = 8.dp, bottom = 40.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            gridItems(state.feed.take(state.feedVisible), key = { it.key }) { card ->
                FocusableTile(
                    onClick = {
                        when {
                            card.group != null -> onPickGroup(card.group)
                            card.subject != null -> onPickDouban(card.subject)
                        }
                    }
                ) { focused ->
                    UnifiedPosterCard(
                        card = card,
                        focused = focused,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (state.feedLoadingMore || (state.feedLoading && state.feed.isNotEmpty())) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    FeedLoadingFooter("正在加载下一批…")
                }
            } else if (!state.feedHasMore && state.feed.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    FeedLoadingFooter("已经到底啦 · 共 ${state.feed.size} 部", spinning = false)
                }
            }
        }
    }
}

@Composable
private fun FeedHeader(state: UiState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp, end = 40.dp, top = 8.dp, bottom = 12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${state.homeTab.label} · ${state.genre}",
                color = TvColors.OnSurface,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(3.dp))
            Text(
                state.feedNote.ifBlank { "选择上方类型，网格向下浏览" },
                color = TvColors.OnSurfaceMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(14.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(TvColors.Surface)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                "${state.feedVisible}/${state.feed.size} 部",
                color = TvColors.OnSurfaceMuted,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun GenreStrip(
    genres: List<HomeGenre>,
    current: String,
    requesters: List<FocusRequester>,
    onSelect: (String) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(start = 40.dp, end = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        itemsIndexed(genres, key = { _, g -> g.label }) { index, genre ->
            FocusableTile(
                onClick = { onSelect(genre.label) },
                scaleWhenFocused = 1.06f,
                modifier = Modifier.focusRequester(requesters[index])
            ) { focused ->
                if (focused) {
                    // Genre chips select on focus, just like the left rail tabs.
                    LaunchedEffect(genre.label) { onSelect(genre.label) }
                }
                GenreChip(
                    label = genre.label,
                    focused = focused,
                    selected = genre.label == current
                )
            }
        }
    }
}

@Composable
private fun GenreChip(label: String, focused: Boolean, selected: Boolean) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                when {
                    focused -> TvColors.SurfaceHigh
                    selected -> TvColors.PrimaryDim
                    else -> TvColors.Surface
                }
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) TvColors.FocusRing else Color(0x22FFFFFF),
                shape = shape
            )
            .padding(horizontal = 18.dp, vertical = 9.dp)
    ) {
        Text(
            label,
            color = when {
                focused || selected -> Color.White
                else -> TvColors.OnSurfaceMuted
            },
            fontSize = 13.sp,
            fontWeight = if (focused || selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

@Composable
private fun FeedCenteredLoading(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp, top = 22.dp, bottom = 10.dp)
    ) {
        CircularProgressIndicator(
            color = TvColors.Primary,
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp
        )
        Spacer(Modifier.width(10.dp))
        Text(text, color = TvColors.OnSurfaceMuted, fontSize = 13.sp)
    }
}

@Composable
private fun FeedLoadingFooter(text: String, spinning: Boolean = true) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
    ) {
        if (spinning) {
            CircularProgressIndicator(
                color = TvColors.Primary,
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = TvColors.OnSurfaceMuted, fontSize = 12.sp)
    }
}
