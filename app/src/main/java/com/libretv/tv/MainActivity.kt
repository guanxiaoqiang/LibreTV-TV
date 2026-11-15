package com.libretv.tv

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.libretv.tv.data.AdFilterSettings
import com.libretv.tv.data.CmsClient
import com.libretv.tv.data.DoubanSubject
import com.libretv.tv.data.PlayRequest
import com.libretv.tv.data.SearchResult
import com.libretv.tv.data.SourceConfig
import com.libretv.tv.data.SourceRepository
import com.libretv.tv.data.VodItem
import com.libretv.tv.data.WatchRecord
import com.libretv.tv.ui.CategoryHint
import com.libretv.tv.ui.CategoryShelf
import com.libretv.tv.ui.CategoryTabRow
import com.libretv.tv.ui.DetailScreen
import com.libretv.tv.ui.DoubanLoadingHint
import com.libretv.tv.ui.DoubanShelf
import com.libretv.tv.ui.FocusableTile
import com.libretv.tv.ui.HistoryPage
import com.libretv.tv.ui.HistoryShelf
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
import com.libretv.tv.ui.tvFocusable
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

private data class NavEntry(
    val label: String,
    val badge: String? = null,
    val action: Int,
    val icon: ImageVector
)

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
            onHistory = { vm.setHomeTab(HomeTab.HISTORY) },
            onSelectTab = { vm.setHomeTab(it) },
            onPickDouban = { vm.searchDoubanTitle(it) },
            onPickCategoryItem = { vm.openDetail(it, Screen.HOME) },
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
            onOpenGroup = { vm.openGroup(it) },
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

@Composable
private fun HomeLayout(
    state: UiState,
    onSearch: () -> Unit,
    onSources: () -> Unit,
    onHistory: () -> Unit,
    onSelectTab: (HomeTab) -> Unit,
    onPickDouban: (DoubanSubject) -> Unit,
    onPickCategoryItem: (SearchResult) -> Unit,
    onResume: (WatchRecord) -> Unit,
    onDeleteHistory: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    val tabs = remember { HomeTab.entries.toList() }
    // Three rail entries and one requester per tab. The explicit chain is what
    // keeps a D-pad from getting stranded between the left rail and the wide
    // content area, which have very little geometric overlap.
    val navRequesters = remember { List(3) { FocusRequester() } }
    val tabRequesters = remember { List(tabs.size) { FocusRequester() } }
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        navRequesters[0].requestFocus()
    }
    // A category switch should always start at the top of its own shelves.
    LaunchedEffect(state.homeTab) {
        scrollState.scrollTo(0)
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            // Keep the left rail clear of the TV overscan area. Without this
            // start inset the focused nav tile (and its 5% scale-up glow) sits
            // flush against the physical screen edge.
            .padding(start = 24.dp, top = 22.dp, bottom = 20.dp)
    ) {
        Column(
            modifier = Modifier.width(230.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "LibreTV",
                color = TvColors.OnSurface,
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
                // Lines up with the icon chip inside NavItem (24.dp inner inset
                // on top of the 24.dp rail inset).
                modifier = Modifier.padding(start = 24.dp, bottom = 14.dp)
            )

            val entries = listOf(
                NavEntry("搜索影片", null, 0, Icons.Filled.Search),
                NavEntry("源管理", "${state.sources.size}", 1, Icons.Filled.Storage),
                NavEntry("历史记录", "${state.history.size}", 2, Icons.Filled.History)
            )

            entries.forEachIndexed { index, entry ->
                FocusableTile(
                    onClick = {
                        when (entry.action) {
                            0 -> onSearch()
                            1 -> onSources()
                            else -> onHistory()
                        }
                    },
                    scaleWhenFocused = 1.05f,
                    modifier = Modifier
                        .focusRequester(navRequesters[index])
                        .focusProperties {
                            up = navRequesters[(index + 2) % entries.size]
                            down = navRequesters[(index + 1) % entries.size]
                            // Returning from the rail lands on the tab that is
                            // actually showing, so "focus = select" does not
                            // silently jump the viewer back to 推荐.
                            right = tabRequesters[
                                state.homeTab.ordinal.coerceIn(0, tabs.lastIndex)
                            ]
                        }
                ) { focused ->
                    NavItem(
                        label = entry.label,
                        focused = focused,
                        badge = entry.badge,
                        icon = entry.icon
                    )
                }
            }
        }

        Column(modifier = Modifier.weight(1f).fillMaxSize()) {
            CategoryTabRow(
                tabs = tabs,
                selected = state.homeTab,
                requesters = tabRequesters,
                railRequester = navRequesters[0],
                onSelect = onSelectTab,
                onSearch = onSearch
            )

            if (state.homeTab == HomeTab.HISTORY) {
                HistoryPage(
                    records = state.history,
                    onResume = onResume,
                    onDelete = onDeleteHistory,
                    onClear = onClearHistory,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                ) {
                    if (state.homeTab == HomeTab.RECOMMEND) {
                        HeroBlock(sourceCount = state.sources.size, onSearch = onSearch)
                        Spacer(Modifier.height(12.dp))

                        HistoryShelf(records = state.history.take(12), onResume = onResume)
                        if (state.history.isNotEmpty()) Spacer(Modifier.height(18.dp))

                        if (state.doubanLoading && state.doubanRows.isEmpty()) {
                            DoubanLoadingHint()
                        }
                        state.doubanRows.forEach { row ->
                            DoubanShelf(
                                label = row.label,
                                subjects = row.subjects,
                                onPick = onPickDouban
                            )
                            Spacer(Modifier.height(18.dp))
                        }
                        if (!state.doubanLoading && state.doubanRows.isEmpty()) {
                            CategoryHint("豆瓣推荐加载失败，可使用遥控器搜索")
                        }
                    } else {
                        CategoryTabContent(
                            state = state,
                            onPickCategoryItem = onPickCategoryItem,
                            onPickDouban = onPickDouban,
                            onResume = onResume
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroBlock(sourceCount: Int, onSearch: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp, end = 40.dp, bottom = 6.dp)
    ) {
        Column(Modifier.align(Alignment.CenterStart)) {
            Text(
                "看点什么？",
                color = TvColors.OnSurface,
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "内置 $sourceCount 个采集接口 · 按电影 / 剧集 / 综艺分类浏览，也能遥控器搜索",
                color = TvColors.OnSurfaceMuted,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(16.dp))
            FocusableTile(onClick = onSearch) { focused ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (focused) TvColors.Primary else TvColors.SurfaceHigh)
                        .tvFocusable(focused, RoundedCornerShape(10.dp))
                        .padding(horizontal = 30.dp, vertical = 13.dp)
                ) {
                    Text(
                        "开始搜索",
                        color = if (focused) Color(0xFF14181D) else TvColors.OnSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryTabContent(
    state: UiState,
    onPickCategoryItem: (SearchResult) -> Unit,
    onPickDouban: (DoubanSubject) -> Unit,
    onResume: (WatchRecord) -> Unit
) {
    val sourceName = state.categorySource?.name ?: "内置采集源"
    Row(
        modifier = Modifier.padding(start = 40.dp, end = 40.dp, top = 10.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${state.homeTab.label} · 分类片单",
            color = TvColors.OnSurface,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.width(14.dp))
        Text("来自 $sourceName", color = TvColors.OnSurfaceMuted, fontSize = 12.sp)
    }

    // Continue-watching stays one D-pad press away on every category tab.
    if (state.history.isNotEmpty()) {
        HistoryShelf(records = state.history.take(6), onResume = onResume)
        Spacer(Modifier.height(18.dp))
    }

    if (state.categoryLoading) {
        CategoryHint("正在从 $sourceName 加载${state.homeTab.label}片单…")
    }

    state.categoryRows.forEach { row ->
        CategoryShelf(row = row, onPick = onPickCategoryItem)
        Spacer(Modifier.height(18.dp))
    }

    state.tabDoubanRows.forEach { row ->
        DoubanShelf(
            label = "${state.homeTab.label} · ${row.label}推荐",
            subjects = row.subjects,
            onPick = onPickDouban
        )
        Spacer(Modifier.height(18.dp))
    }

    if (!state.categoryLoading &&
        state.categoryRows.isEmpty() &&
        state.tabDoubanRows.isEmpty()
    ) {
        CategoryHint("该分类暂时没有片单，可切换到「推荐」或使用遥控器搜索")
    }
}
