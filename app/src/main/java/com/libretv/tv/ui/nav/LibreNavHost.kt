package com.libretv.tv.ui.nav

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.runtime.collectAsState
import com.libretv.tv.core.UiEffect
import com.libretv.tv.di.AppContainer
import com.libretv.tv.ui.DetailScreen
import com.libretv.tv.ui.HomeLayout
import com.libretv.tv.ui.PlayerScreen
import com.libretv.tv.ui.ResultScreen
import com.libretv.tv.ui.SearchScreen
import com.libretv.tv.ui.FavoritesScreen
import com.libretv.tv.ui.SettingsScreen
import com.libretv.tv.ui.SkipMarksScreen
import com.libretv.tv.ui.SourcePickerScreen
import com.libretv.tv.ui.SourcesScreen
import com.libretv.tv.ui.pages.DetailViewModel
import com.libretv.tv.ui.pages.HomeViewModel
import com.libretv.tv.ui.pages.PickerViewModel
import com.libretv.tv.ui.pages.PlayerViewModel
import com.libretv.tv.ui.pages.ResultViewModel
import com.libretv.tv.ui.pages.SearchViewModel
import com.libretv.tv.ui.pages.SettingsViewModel
import com.libretv.tv.ui.pages.SourcesViewModel
import com.libretv.tv.domain.model.SkipMarks
import kotlinx.coroutines.launch
import java.io.File

/**
 * 应用导航图。
 *
 * 返回栈由 navigation-compose 维护：ViewModel 只发出「要跳到哪」的意图，
 * 真正的跳转在这里执行（规则 D5：ViewModel 不持有 NavController）。
 *
 * 每个页面有自己的 ViewModel，页面之间的共享状态（搜索会话、详情会话、
 * 选源会话、设置、源清单）都在 [AppContainer] 里。
 */
@Composable
fun LibreNavHost(
    container: AppContainer,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 一次性事件 → 导航动作。
    LaunchedEffect(container) {
        container.effects.collect { effect ->
            when (effect) {
                is UiEffect.Navigate -> {
                    val current = navController.currentBackStackEntry?.destination?.route
                    val previous = navController.previousBackStackEntry?.destination?.route
                    when {
                        // 自动换源：播放页已在栈顶，替换它而不是再压一层。
                        effect.replaceCurrent && current != null -> {
                            navController.navigate(effect.route) {
                                popUpTo(current) { inclusive = true }
                            }
                        }
                        // 重复进入结果页：替换当前结果页，避免返回时层层叠加。
                        effect.route == Routes.RESULT && current == Routes.RESULT -> {
                            navController.navigate(effect.route) {
                                popUpTo(Routes.RESULT) { inclusive = true }
                            }
                        }
                        // 豆瓣推荐进入的结果页：再往下一层时直接回到首页，
                        // 与旧实现里 detailFrom / pickerFrom = HOME 的语义一致。
                        current == Routes.RESULT && previous == Routes.HOME &&
                            (effect.route == Routes.DETAIL || effect.route == Routes.PICKER) -> {
                            navController.navigate(effect.route) {
                                popUpTo(Routes.RESULT) { inclusive = true }
                            }
                        }
                        else -> navController.navigate(effect.route)
                    }
                }
                // 瞬时提示目前只在页面内以文案呈现，无需全局弹层。
                is UiEffect.Message -> Unit
                // 导出备份：交给系统分享面板（文件在 cacheDir 下，用 FileProvider 授权）。
                is UiEffect.ShareFile -> {
                    val file = File(effect.path)
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = effect.mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(intent, "分享备份"))
                    }.onFailure {
                        container.message("没有找到可以分享的应用")
                    }
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier
    ) {
        composable(Routes.HOME) {
            val homeVm: HomeViewModel = containerViewModel(container) { HomeViewModel(it) }
            val homeState by homeVm.state.collectAsState()
            val settings by container.settings.collectAsState()
            val history by container.historyRecords.collectAsState()
            val online by container.online.collectAsState()
            HomeLayout(
                homeState = homeState,
                history = history,
                badgeVisibility = settings.badgeVisibility,
                online = online,
                onSearch = { navController.navigate(Routes.SEARCH) },
                onSources = { navController.navigate(Routes.SETTINGS) },
                onFavorites = { navController.navigate(Routes.FAVORITES) },
                onSelectTab = { homeVm.setHomeTab(it) },
                onRailFocus = { homeVm.setRailFocus(it) },
                onSelectGenre = { homeVm.setGenre(it) },
                onLoadMore = { homeVm.loadMore() },
                onScrollPaused = { homeVm.setFeedPaused(it) },
                onPickDouban = { homeVm.searchDoubanTitle(it) },
                onPickGroup = { homeVm.openGroupOrDetail(it) },
                onResume = { record ->
                    // 恢复播放先回到详情页（详情与集数列表要重新解析），
                    // 再进入播放页，返回路径与旧实现一致。
                    navController.navigate(Routes.DETAIL)
                    scope.launch {
                        val request = homeVm.resumeFromHistory(record)
                        if (request != null) {
                            navController.navigate(Routes.player(request))
                        }
                    }
                },
                onDeleteHistory = { homeVm.deleteHistory(it) },
                onClearHistory = { homeVm.clearHistory() }
            )
        }

        composable(Routes.SEARCH) {
            val searchVm: SearchViewModel = containerViewModel(container) { SearchViewModel(it) }
            val searchState by searchVm.state.collectAsState()
            val searchHistory by container.searchHistory.collectAsState()
            SearchScreen(
                query = searchState.query,
                candidates = searchVm.candidateTitles(),
                searchHistory = searchHistory,
                onQueryChange = { searchVm.setQuery(it) },
                onInput = { searchVm.inputChar(it) },
                onBackspace = { searchVm.deleteLastChar() },
                onSubmit = { searchVm.submitSearch() },
                onClear = { searchVm.clearInput() },
                onPickCandidate = { searchVm.pickCandidate(it) },
                onPickHistory = { searchVm.searchKeyword(it) },
                onDeleteHistory = { searchVm.deleteSearchHistory(it) },
                onClearHistory = { searchVm.clearSearchHistory() }
            )
        }

        composable(Routes.RESULT) {
            val resultVm: ResultViewModel = containerViewModel(container) { ResultViewModel(it) }
            val session by resultVm.session.collectAsState()
            val settings by container.settings.collectAsState()
            ResultScreen(
                keyword = session.keyword,
                results = session.results,
                searching = session.searching,
                badgeVisibility = settings.badgeVisibility,
                onOpenGroup = { resultVm.openGroup(it) }
            )
        }

        composable(Routes.PICKER) {
            val pickerVm: PickerViewModel = containerViewModel(container) { PickerViewModel(it) }
            val session by pickerVm.session.collectAsState()
            val group = session.group
            if (group == null) {
                // 没有会话就回退：选源页永远由某个标题组带进来。
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                SourcePickerScreen(
                    group = group,
                    probes = session.probes,
                    onPick = { pickerVm.pickOffer(it) },
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable(Routes.DETAIL) {
            val detailVm: DetailViewModel = containerViewModel(container) { DetailViewModel(it) }
            val session by detailVm.session.collectAsState()
            val detail = session.detail
            val favoriteKeys by container.favoriteEntries.collectAsState()
            DetailScreen(
                detail = detail,
                loading = detail == null && session.error == null,
                error = session.error,
                activeLine = session.activeLine,
                onSelectLine = { detailVm.selectLine(it) },
                onPlay = { line, ep ->
                    detailVm.buildPlayRequest(line, ep)?.let { request ->
                        navController.navigate(Routes.player(request))
                    }
                },
                offer = session.activeOffer,
                // 当前源没线路时，把同片的其它源摆出来让观众直接点。
                alternatives = session.alternatives,
                onPickAlternative = { detailVm.switchSource(it) },
                onSwitchSource = if (
                    navController.previousBackStackEntry?.destination?.route == Routes.PICKER &&
                    container.pickerSession.value.group != null
                ) {
                    { navController.popBackStack() }
                } else null,
                isFavorite = detail?.item?.vodName?.let {
                    com.libretv.tv.domain.model.normalizeTitle(it) in favoriteKeys.map { f -> f.titleKey }
                } ?: false,
                onToggleFavorite = detail?.let { bundle ->
                    {
                        val titleKey = com.libretv.tv.domain.model.normalizeTitle(bundle.item.vodName)
                        if (container.isFavorite(titleKey)) {
                            container.removeFavorite(titleKey)
                        } else {
                            container.addFavorite(
                                com.libretv.tv.domain.model.FavoriteEntry(
                                    titleKey = titleKey,
                                    title = bundle.item.vodName,
                                    poster = bundle.item.vodPic,
                                    sourceUrl = session.activeOffer?.sourceUrl ?: "",
                                    vodId = session.activeOffer?.vodId ?: "",
                                    createdAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }
            )
        }

        composable(
            route = Routes.PLAYER_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_REQUEST) { type = NavType.StringType }
            )
        ) { entry ->
            val playerVm: PlayerViewModel = playerViewModel(container, entry)
            val request by playerVm.request.collectAsState()
            val session by container.detailSession.collectAsState()
            val current = request
            if (current == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                val d = session.detail
                val curLine = session.activeLine
                val eps = d?.lines?.getOrNull(curLine)?.episodes.orEmpty()
                val curIdx = session.activeEpisode
                val settings by container.settings.collectAsState()
                val skipMarks by container.skipMarksState.collectAsState()
                val skipKey = remember(current.title) { SkipMarks.keyOf(current.title) }
                val recoveryNotice by container.recoveryNotice.collectAsState()
                val pickerSession by container.pickerSession.collectAsState()
                PlayerScreen(
                    title = current.title,
                    episodeName = current.episodeName,
                    rawUrl = current.rawUrl,
                    episodes = eps,
                    currentEpisodeIndex = curIdx,
                    startPositionMs = current.startPositionMs,
                    adFilterEnabled = settings.adFilterEnabled,
                    onProgress = { position, duration ->
                        playerVm.saveProgress(current, position, duration)
                    },
                    onProgressFinal = { position, duration ->
                        playerVm.saveProgress(current, position, duration, force = true)
                    },
                    onBack = { navController.popBackStack() },
                    onNextEpisode = if (curIdx < eps.size - 1) {
                        {
                            val nxt = eps[curIdx + 1]
                            container.selectEpisode(curIdx + 1)
                            playerVm.update(
                                current.copy(
                                    episodeName = nxt.name,
                                    rawUrl = nxt.url,
                                    episodeIndex = curIdx + 1,
                                    startPositionMs = 0L,
                                    resumeRatio = 0f
                                )
                            )
                        }
                    } else null,
                    onPrevEpisode = if (curIdx > 0) {
                        {
                            val prv = eps[curIdx - 1]
                            container.selectEpisode(curIdx - 1)
                            playerVm.update(
                                current.copy(
                                    episodeName = prv.name,
                                    rawUrl = prv.url,
                                    episodeIndex = curIdx - 1,
                                    startPositionMs = 0L,
                                    resumeRatio = 0f
                                )
                            )
                        }
                    } else null,
                    onSelectEpisode = { index ->
                        eps.getOrNull(index)?.let { picked ->
                            container.selectEpisode(index)
                            playerVm.update(
                                current.copy(
                                    episodeName = picked.name,
                                    rawUrl = picked.url,
                                    episodeIndex = index,
                                    startPositionMs = 0L,
                                    resumeRatio = 0f
                                )
                            )
                        }
                    },
                    resumeRatio = current.resumeRatio,
                    recoveryOffers = session.activeOffer
                        ?.let { active ->
                            pickerSession.group?.offers.orEmpty().filter {
                                it.key != active.key &&
                                    !it.isTrailer &&
                                    it.key !in pickerSession.failedOfferKeys
                            }
                        }
                        .orEmpty(),
                    recoverySwitches = pickerSession.recoverySwitches,
                    onSwitchSource = { offer, ratio, episodeIndex ->
                        playerVm.switchSource(offer, ratio, episodeIndex)
                    },
                    onPlaybackFailure = { errorCode ->
                        playerVm.reportPlaybackFailure(current.sourceUrl, errorCode)
                    },
                    skipMarksEnabled = settings.skipMarksEnabled,
                    skipMark = skipMarks.firstOrNull { it.titleKey == skipKey },
                    onSaveSkipMark = { mark -> container.saveSkipMark(mark) },
                    onManualSourcePick = {
                        navController.navigate(Routes.PICKER)
                    },
                    statusNotice = recoveryNotice
                )
            }
        }

        composable(Routes.SETTINGS) {
            val settingsVm: SettingsViewModel = containerViewModel(container) { SettingsViewModel(it) }
            val settings by settingsVm.settings.collectAsState()
            val entries by container.sourceEntries.collectAsState()
            val importState by settingsVm.importState.collectAsState()
            val favorites by container.favoriteEntries.collectAsState()
            val storageUsage by settingsVm.storageUsage.collectAsState()
            SettingsScreen(
                adFilterEnabled = settings.adFilterEnabled,
                badgeVisibility = settings.badgeVisibility,
                sourceCount = entries.size,
                enabledSourceCount = entries.count { it.enabled },
                debugLogEnabled = settings.debugLogEnabled,
                skipMarksEnabled = settings.skipMarksEnabled,
                harvestEnabled = settings.harvestEnabled,
                harvestIntensityLabel = settings.harvestIntensity.label,
                storageBytes = storageUsage.first,
                catalogCount = storageUsage.second,
                favoriteCount = favorites.size,
                importState = importState,
                onToggleAdFilter = { settingsVm.setAdFilterEnabled(!settings.adFilterEnabled) },
                onToggleBadge = { tag, visible -> settingsVm.setBadgeVisible(tag, visible) },
                onToggleDebugLog = { settingsVm.setDebugLogEnabled(!settings.debugLogEnabled) },
                onToggleSkipMarks = { settingsVm.setSkipMarksEnabled(!settings.skipMarksEnabled) },
                onToggleHarvest = { settingsVm.setHarvestEnabled(!settings.harvestEnabled) },
                onCycleHarvestIntensity = {
                    val next = when (settings.harvestIntensity) {
                        com.libretv.tv.data.HarvestIntensity.RUNNING_ONLY -> com.libretv.tv.data.HarvestIntensity.PERIODIC
                        com.libretv.tv.data.HarvestIntensity.PERIODIC -> com.libretv.tv.data.HarvestIntensity.AGGRESSIVE
                        com.libretv.tv.data.HarvestIntensity.AGGRESSIVE -> com.libretv.tv.data.HarvestIntensity.RUNNING_ONLY
                    }
                    settingsVm.setHarvestIntensity(next)
                },
                onClearCache = { settingsVm.clearCache() },
                onExportBackup = { settingsVm.exportBackup() },
                onImportBackupText = { settingsVm.importBackupText(it) },
                onImportOptionsChange = { settingsVm.setImportOptions(it) },
                onConfirmImport = { settingsVm.confirmImport() },
                onDismissImport = { settingsVm.dismissImport() },
                onOpenSources = { navController.navigate(Routes.SOURCES) },
                onOpenSkipMarks = { navController.navigate(Routes.SKIP_MARKS) },
                onOpenFavorites = { navController.navigate(Routes.FAVORITES) }
            )
        }

        composable(Routes.SKIP_MARKS) {
            val marks by container.skipMarksState.collectAsState()
            SkipMarksScreen(
                marks = marks,
                onDelete = { container.removeSkipMark(it) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.FAVORITES) {
            val favorites by container.favoriteEntries.collectAsState()
            FavoritesScreen(
                favorites = favorites,
                onOpen = { entry ->
                    // 收藏不强制绑定来源（D9）：以片名发起搜索，进结果页当场选源。
                    // 播放地址不落库，无法凭收藏直接起播，这是刻意的设计取舍。
                    val keyword = entry.title.ifBlank { entry.titleKey }
                    container.beginSearch(keyword)
                    navController.navigate(Routes.RESULT)
                },
                onRemove = { container.removeFavorite(it.titleKey) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SOURCES) {
            val sourcesVm: SourcesViewModel = containerViewModel(container) { SourcesViewModel(it) }
            val sourcesState by sourcesVm.state.collectAsState()
            val entries by sourcesVm.entries.collectAsState()
            val health by sourcesVm.health.collectAsState()
            val subscriptions by sourcesVm.subscriptions.collectAsState()
            val syncingSubscriptions by sourcesVm.syncingSubscriptions.collectAsState()
            SourcesScreen(
                entries = entries,
                health = health,
                testingUrl = sourcesState.testingSource,
                onToggleSource = { url, enabled -> sourcesVm.setSourceEnabled(url, enabled) },
                onTestSource = { sourcesVm.testSource(it) },
                onTestAll = { sourcesVm.testAllSources() },
                onResumeSource = { sourcesVm.resumeSource(it) },
                onRemoveSource = { sourcesVm.removeSource(it) },
                onAddSource = { name, url, dialect -> sourcesVm.addSource(name, url, dialect) },
                onResetSources = { sourcesVm.resetSources() },
                subscriptions = subscriptions,
                syncingSubscriptions = syncingSubscriptions,
                onAddSubscription = { name, url, days -> sourcesVm.addSubscription(name, url, days) },
                onUpdateSubscription = { id, name, url, days ->
                    sourcesVm.updateSubscription(id, name, url, days)
                },
                onDeleteSubscription = { sourcesVm.deleteSubscription(it) },
                onSyncSubscription = { sourcesVm.syncSubscription(it) },
                onParseConfig = { sourcesVm.parseConfig(it) },
                onImportSources = { sourcesVm.importSources(it) }
            )
        }
    }
}
