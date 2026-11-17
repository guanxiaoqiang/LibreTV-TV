package com.libretv.tv

import android.app.Activity
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.libretv.tv.ui.SettingsScreen
import com.libretv.tv.ui.SourcePickerScreen
import com.libretv.tv.ui.SourcesScreen
import com.libretv.tv.ui.TvBackgroundBrush
import com.libretv.tv.ui.TvColors
import com.libretv.tv.ui.TvViewModel
import com.libretv.tv.ui.UiState
import com.libretv.tv.ui.UnifiedPosterCard
import com.libretv.tv.ui.requestFocusSafely
import com.libretv.tv.ui.genres
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val icon: ImageVector,
    val tab: HomeTab? = null,
    val action: Int? = null
)

/** Shared left inset of every content row, aligned with the first grid card. */
private val ContentStart = 24.dp

/**
 * The inputs of the infinite-scroll decision.
 *
 * They are bundled into one value so the snapshot flow can compare them and
 * re-evaluate whenever the feed state changes, not only when the grid moves.
 */
private data class FeedTrigger(
    val hasMore: Boolean,
    val loading: Boolean,
    val loadingMore: Boolean,
    val visible: Int,
    val lastVisible: Int
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

    // On the home screen the rail owns the BACK key (see HomeLayout): the
    // first press returns the focus to the tabs, a second one asks whether
    // the app should really close. Every other screen goes back as usual.
    BackHandler(enabled = state.screen != Screen.HOME) {
        if (state.screen == Screen.PLAYER) playTarget = null
        vm.back()
    }

    when (state.screen) {
        Screen.HOME -> HomeLayout(
            state = state,
            onSearch = { vm.navigate(Screen.SEARCH) },
            onSources = { vm.navigate(Screen.SETTINGS) },
            onSelectTab = { vm.setHomeTab(it) },
            onRailFocus = { vm.setRailFocus(it) },
            onSelectGenre = { vm.setGenre(it) },
            onLoadMore = { vm.loadMore() },
            onScrollPaused = { vm.setFeedPaused(it) },
            onPickDouban = { vm.searchDoubanTitle(it) },
            onPickGroup = { vm.openGroupOrDetail(it, Screen.HOME) },
            onResume = resumeRecord,
            onDeleteHistory = { vm.deleteHistory(it) },
            onClearHistory = { vm.clearHistory() }
        )

        Screen.SEARCH -> SearchScreen(
            query = state.query,
            candidates = vm.candidateTitles(),
            searchHistory = state.searchHistory,
            onQueryChange = { vm.setQuery(it) },
            onInput = { vm.inputChar(it) },
            onBackspace = { vm.deleteLastChar() },
            onSubmit = { vm.submitSearch() },
            onClear = { vm.clearInput() },
            onPickCandidate = { vm.pickCandidate(it) },
            onPickHistory = { vm.searchKeyword(it) },
            onDeleteHistory = { vm.deleteSearchHistory(it) },
            onClearHistory = { vm.clearSearchHistory() }
        )

        Screen.RESULT -> ResultScreen(
            keyword = state.searchedKeyword,
            results = state.results,
            searching = state.searching,
            badgeVisibility = state.badgeVisibility,
            // Douban-driven results return to the recommend page they came
            // from; keyboard-driven results keep the normal results <- detail
            // <- search chain.
            onOpenGroup = { vm.openGroupOrDetail(it, vm.resultBackTarget()) },
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
                    adFilterEnabled = state.adFilterEnabled,
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

        Screen.SETTINGS -> SettingsScreen(
            adFilterEnabled = state.adFilterEnabled,
            badgeVisibility = state.badgeVisibility,
            sourceCount = state.sourceEntries.size,
            enabledSourceCount = state.sourceEntries.count { it.enabled },
            onToggleAdFilter = { vm.setAdFilterEnabled(!state.adFilterEnabled) },
            onToggleBadge = { tag, visible -> vm.setBadgeVisible(tag, visible) },
            onOpenSources = { vm.navigate(Screen.SOURCES) },
            onBack = { vm.back() }
        )

        Screen.SOURCES -> SourcesScreen(
            entries = state.sourceEntries,
            health = state.sourceHealth,
            testingUrl = state.testingSource,
            onToggleSource = { url, enabled -> vm.setSourceEnabled(url, enabled) },
            onTestSource = { vm.testSource(it) },
            onTestAll = { vm.testAllSources() },
            onRemoveSource = { vm.removeSource(it) },
            onAddSource = { name, url -> vm.addSource(name, url) },
            onResetSources = { vm.resetSources() },
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
    onRailFocus: (Int) -> Unit,
    onSelectGenre: (String) -> Unit,
    onLoadMore: () -> Unit,
    /** True while the content grid is being scrolled, so new data is held back. */
    onScrollPaused: (Boolean) -> Unit,
    onPickDouban: (DoubanSubject) -> Unit,
    onPickGroup: (TitleGroup) -> Unit,
    onResume: (WatchRecord) -> Unit,
    onDeleteHistory: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    val tabs = remember { HomeTab.entries.toList() }
    // The activity behind the composition — the exit question closes it.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The whole navigation lives on the left rail: search, then every home tab
    // in display order, then source management. One requester per entry, with
    // wrap-around up/down, keeps the D-pad cycling inside the rail.
    // No count badges on 历史 / 源管理: the numbers changed on every history
    // update and made the rail look noisy. The rail itself is static.
    val railEntries = remember {
        buildList {
            add(RailEntry("搜索", icon = Icons.Filled.Search, action = 0))
            tabs.forEach { tab ->
                add(RailEntry(label = tab.label, icon = tabIcon(tab), tab = tab))
            }
            add(RailEntry("设置", icon = Icons.Filled.Settings, action = 1))
        }
    }
    val railRequesters = remember { List(railEntries.size) { FocusRequester() } }
    val railBringers = remember { List(railEntries.size) { BringIntoViewRequester() } }
    val railScroll = rememberScrollState()

    // The rail slot the viewer left last. Both a BACK from the content page and
    // a return from another screen use this exact requester, so focus never
    // jumps to whichever nav item happens to be geometrically closest.
    val railTargetIndex = state.railFocusIndex.coerceIn(0, railEntries.lastIndex)
    val railTarget = railRequesters[railTargetIndex]

    // Focus hand-off into the content page. Clicking a rail tab bumps the tick;
    // the content page turns that into a focus request for its own top row.
    val historyLead = remember { FocusRequester() }
    var enterContentTick by remember { mutableStateOf(0) }

    // True while any focusable in the right content page holds focus. BACK then
    // hands focus back to the rail instead of leaving the screen.
    var contentHasFocus by remember { mutableStateOf(false) }

    // BACK with the focus on the rail asks before the app is closed — closing
    // a TV app by accident is not recoverable for the viewer.
    var showExitConfirm by remember { mutableStateOf(false) }

    // BACK inside the content page returns to the left rail — the exact tab
    // entry the viewer came from — rather than jumping screens. The rail is
    // only reachable through this gesture, which keeps arrow navigation inside
    // the grid deterministic.
    BackHandler(enabled = contentHasFocus) {
        contentHasFocus = false
        railTarget.requestFocusSafely()
    }

    // BACK on the rail: ask first, leave only after the viewer confirms.
    BackHandler(enabled = !contentHasFocus) {
        showExitConfirm = true
    }

    LaunchedEffect(Unit) {
        railRequesters[railTargetIndex].requestFocus()
    }

    // A tab click (OK on a focused rail entry) hands focus to the content page.
    // The genre chips run their own hand-off (they have to scroll the strip
    // home first); the history page has no chips, so it does it here.
    LaunchedEffect(enterContentTick) {
        if (enterContentTick > 0 && state.homeTab == HomeTab.HISTORY) {
            historyLead.requestFocusSafely()
        }
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
                // 124 dp = 116 dp rail + 8 dp slack for the 1.05x focus scale,
                // so the enlarged nav tile is not clipped by the scroll view.
                .width(124.dp)
                .verticalScroll(railScroll)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                "LibreTV",
                color = TvColors.OnSurface,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 12.dp, bottom = 12.dp)
            )

            railEntries.forEachIndexed { index, entry ->
                FocusableTile(
                    onClick = {
                        when {
                            entry.action == 0 -> onSearch()
                            entry.action == 1 -> onSources()
                            // Clicking a tab does two things: switch the content
                            // page, and hand focus to its top chip strip. BACK
                            // from there returns to this exact rail slot.
                            entry.tab != null -> {
                                onRailFocus(index)
                                onSelectTab(entry.tab)
                                enterContentTick++
                            }
                        }
                    },
                    scaleWhenFocused = 1.05f,
                    modifier = Modifier
                        .focusRequester(railRequesters[index])
                        .onFocusChanged { if (it.isFocused) onRailFocus(index) }
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
                        selected = entry.tab == state.homeTab,
                        icon = entry.icon
                    )
                }
            }
        }

        // Breathing room between the rail and the first grid column: a focused
        // card grows ~9% in every direction, so it needs clear space on the
        // left as well as above the first row.
        Spacer(Modifier.width(8.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                // hasFocus covers every focusable below (chips, cards, header
                // buttons): while any of them is focused, BACK belongs to the
                // content page and returns to the rail.
                .onFocusChanged { contentHasFocus = it.hasFocus }
        ) {
            if (state.homeTab == HomeTab.HISTORY) {
                HistoryPage(
                    records = state.history,
                    onResume = onResume,
                    onDelete = onDeleteHistory,
                    onClear = onClearHistory,
                    leftTarget = railTarget,
                    leadTarget = historyLead,
                    modifier = Modifier.weight(1f)
                )
            } else {
                HomeFeedPage(
                    state = state,
                    leftTarget = railTarget,
                    focusLeadTick = enterContentTick,
                    onSelectGenre = onSelectGenre,
                    onPickGroup = onPickGroup,
                    onPickDouban = onPickDouban,
                    onLoadMore = onLoadMore,
                    onScrollPaused = onScrollPaused
                )
            }
        }
    }

    // The exit question lives in its own window, so it covers the whole app
    // and keeps the focus inside itself.
    if (showExitConfirm) {
        ExitConfirmDialog(
            onConfirm = {
                showExitConfirm = false
                (context as? Activity)?.finish()
            },
            onCancel = {
                showExitConfirm = false
                // The dialog window needs a moment to close before the focus
                // can move back to the rail.
                scope.launch {
                    delay(160)
                    railTarget.requestFocusSafely()
                }
            }
        )
    }
}

/**
 * Asks before the app is closed.
 *
 * On a TV the remote's BACK key is muscle memory, so closing the app by
 * accident has to be impossible: the safe choice keeps the focus and only an
 * explicit confirmation leaves.
 */
@Composable
private fun ExitConfirmDialog(
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val cancelRequester = remember { FocusRequester() }
    val confirmRequester = remember { FocusRequester() }

    // BACK inside the question dismisses it instead of closing the app.
    BackHandler { onCancel() }

    LaunchedEffect(Unit) {
        cancelRequester.requestFocusSafely()
    }

    Dialog(
        onDismissRequest = { onCancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xE6000000)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .width(460.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(TvColors.Surface)
                    .border(1.dp, TvColors.SurfaceHigh, RoundedCornerShape(16.dp))
                    .padding(horizontal = 28.dp, vertical = 26.dp)
            ) {
                Text(
                    "退出应用？",
                    color = TvColors.OnSurface,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "确认后 LibreTV-TV 会退出，已保存的观看记录不会丢失。",
                    color = TvColors.OnSurfaceMuted,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(26.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FocusableTile(
                        onClick = onCancel,
                        scaleWhenFocused = 1.04f,
                        modifier = Modifier
                            .focusRequester(cancelRequester)
                            .focusProperties { right = confirmRequester }
                    ) { focused ->
                        ExitDialogButton("取消", focused)
                    }
                    FocusableTile(
                        onClick = onConfirm,
                        scaleWhenFocused = 1.04f,
                        modifier = Modifier
                            .focusRequester(confirmRequester)
                            .focusProperties { left = cancelRequester }
                    ) { focused ->
                        ExitDialogButton("确认退出", focused, danger = true)
                    }
                }
            }
        }
    }
}

/** One button of the exit question. */
@Composable
private fun ExitDialogButton(label: String, focused: Boolean, danger: Boolean = false) {
    val accent = if (danger) TvColors.Danger else TvColors.Primary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) accent.copy(alpha = 0.22f) else TvColors.SurfaceHigh)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) accent else TvColors.SurfaceHigh,
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 26.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (focused) accent else TvColors.OnSurfaceMuted,
            fontSize = 15.sp,
            fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal
        )
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
    leftTarget: FocusRequester?,
    focusLeadTick: Int,
    onSelectGenre: (String) -> Unit,
    onPickGroup: (TitleGroup) -> Unit,
    onPickDouban: (DoubanSubject) -> Unit,
    onLoadMore: () -> Unit,
    onScrollPaused: (Boolean) -> Unit
) {
    val genres = remember(state.homeTab) { state.homeTab.genres() }
    val chipRequesters = remember(genres.size) { List(genres.size) { FocusRequester() } }
    val gridState = rememberLazyGridState()

    // Remember which top chip the viewer came down from. Going back UP from a
    // card must land on that exact chip instead of the geometrically nearest
    // one (which is what the default 2D focus search would pick).
    var lastChipIndex by remember(state.homeTab) { mutableStateOf(0) }
    val upChip = chipRequesters.getOrNull(lastChipIndex) ?: chipRequesters.firstOrNull()

    // Only the top row of the grid is wired to the chip strip; deeper rows
    // keep the normal row-by-row D-pad behaviour.
    val firstRowIndices: Set<Int> by remember(gridState) {
        derivedStateOf<Set<Int>> {
            if (gridState.firstVisibleItemIndex != 0) return@derivedStateOf emptySet()
            val visible = gridState.layoutInfo.visibleItemsInfo
            val topY = visible.minOfOrNull { it.offset.y } ?: return@derivedStateOf emptySet()
            visible.filter { it.offset.y == topY }.map { it.index }.toSet()
        }
    }

    // A tab / genre switch always starts at the top of its own grid.
    LaunchedEffect(state.homeTab, state.genre) {
        gridState.scrollToItem(0)
    }

    // While the viewer scrolls, fresh feed data is held back (see
    // TvViewModel.setFeedPaused): publishing a new card list mid-scroll forces
    // the grid to rebuild and hitch. The flush happens the moment scrolling
    // stops.
    val latestPaused by rememberUpdatedState(onScrollPaused)
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { paused -> latestPaused(paused) }
    }

    // Leaving the page (history tab, search, player) cancels the collector
    // above, so the hold-back has to be released with it — otherwise the next
    // page would wait for data that is never committed.
    DisposableEffect(Unit) {
        onDispose { latestPaused(false) }
    }

    // Infinite scroll: re-evaluated whenever the layout *or* the feed state
    // changes. Reading the state inside the flow is what makes a freshly
    // landed card list ask for the next page even when the visible items did
    // not move.
    val latestState by rememberUpdatedState(state)
    val latestLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(gridState) {
        snapshotFlow {
            val s = latestState
            FeedTrigger(
                hasMore = s.feedHasMore,
                loading = s.feedLoading,
                loadingMore = s.feedLoadingMore,
                visible = s.feedVisible,
                lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            )
        }.distinctUntilChanged().collect { t ->
            if (t.hasMore && !t.loading && !t.loadingMore && t.visible > 0 &&
                t.lastVisible >= t.visible - 6
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
            leftTarget = leftTarget,
            focusLeadTick = focusLeadTick,
            onSelect = onSelectGenre,
            onFocused = { lastChipIndex = it }
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
                // 20 dp above the first row leaves room for the 1.09x focus
                // scale; the older 8 dp clipped the top of focused posters.
                start = ContentStart, end = 40.dp, top = 20.dp, bottom = 40.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            gridItemsIndexed(
                items = state.feed.take(state.feedVisible),
                key = { _, card -> card.key }
            ) { index, card ->
                // DOWN from a chip is left to Compose's 2D focus search, which
                // already picks the nearest card below. UP is explicit so the
                // viewer returns to the chip they originally left.
                // LEFT is deliberately unbound: arrow keys never leave the grid
                // for the rail — BACK is the only way back to the tabs.
                val upTarget = upChip
                val navigationModifier = Modifier.focusProperties {
                    if (upTarget != null && index in firstRowIndices) up = upTarget
                }
                FocusableTile(
                    onClick = {
                        when {
                            card.group != null -> onPickGroup(card.group)
                            card.subject != null -> onPickDouban(card.subject)
                        }
                    },
                    modifier = navigationModifier
                ) { focused ->
                    UnifiedPosterCard(
                        card = card,
                        focused = focused,
                        badgeVisibility = state.badgeVisibility,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            // The hint at the very bottom of the grid: it tells the viewer
            // that more is coming and that the new cards are appended below.
            if (state.feedLoadingMore || (state.feedLoading && state.feed.isNotEmpty())) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    FeedLoadingFooter("正在加载更多…")
                }
            } else if (state.feedRefreshing) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    FeedLoadingFooter("正在更新…")
                }
            } else if (state.feedVisible < state.feed.size) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    FeedLoadingFooter(
                        "继续下滑加载更多 · 还有 ${state.feed.size - state.feedVisible} 部",
                        spinning = false
                    )
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
            .padding(start = ContentStart, end = 40.dp, top = 8.dp, bottom = 12.dp)
    ) {
        Text(
            "${state.homeTab.label} · ${state.genre}",
            color = TvColors.OnSurface,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(14.dp))
        // Plain text on purpose: a boxed count read as a button on a TV, and
        // viewers kept trying to focus it.
        Text(
            "${state.feedVisible}/${state.feed.size} 部",
            color = TvColors.OnSurfaceMuted,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun GenreStrip(
    genres: List<HomeGenre>,
    current: String,
    requesters: List<FocusRequester>,
    leftTarget: FocusRequester?,
    focusLeadTick: Int,
    onSelect: (String) -> Unit,
    onFocused: (Int) -> Unit
) {
    val stripState = rememberLazyListState()
    // Bind only the left-most *visible* chip: after a horizontal scroll the
    // first chip is recycled and its requester is no longer attached.
    val leadingChipIndex by remember(stripState) {
        derivedStateOf {
            stripState.layoutInfo.visibleItemsInfo.minOfOrNull { it.index } ?: 0
        }
    }

    // A new tab starts at its first category, and the strip goes back to the
    // beginning with it — otherwise the viewer would land on a chip of the
    // previous tab's scroll position.
    LaunchedEffect(genres) {
        stripState.scrollToItem(0)
    }

    // The hand-off from a rail tab click: focus lands on the first category
    // chip of the freshly switched page. The strip is scrolled home first and
    // one frame is awaited, because the chip only becomes focusable once it
    // has been composed.
    LaunchedEffect(focusLeadTick) {
        if (focusLeadTick > 0) {
            stripState.scrollToItem(0)
            withFrameNanos { }
            requesters.getOrNull(0)?.requestFocusSafely()
        }
    }

    LazyRow(
        state = stripState,
        // Vertical padding keeps focused chips (1.06x scale) inside the row and
        // adds a gap between the chip strip and the first poster row.
        contentPadding = PaddingValues(
            start = ContentStart, end = 40.dp, top = 8.dp, bottom = 10.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        itemsIndexed(genres, key = { _, g -> g.label }) { index, genre ->
            val rail = leftTarget
            FocusableTile(
                onClick = { onSelect(genre.label) },
                scaleWhenFocused = 1.06f,
                modifier = Modifier
                    .focusRequester(requesters[index])
                    .focusProperties {
                        if (rail != null && index == leadingChipIndex) left = rail
                    }
            ) { focused ->
                if (focused) {
                    // Genre chips select on focus, just like the left rail tabs.
                    // Remember the slot as well, so UP from the grid returns
                    // here rather than to a neighbouring chip.
                    LaunchedEffect(genre.label, index) {
                        onFocused(index)
                        onSelect(genre.label)
                    }
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
            .padding(start = ContentStart, top = 22.dp, bottom = 10.dp)
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
