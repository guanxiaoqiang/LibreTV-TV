package com.libretv.tv.di

import android.content.Context
import com.libretv.tv.core.Logger
import com.libretv.tv.core.SourceError
import com.libretv.tv.core.UiEffect
import com.libretv.tv.data.AppSettings
import com.libretv.tv.data.BackupCodec
import com.libretv.tv.data.FeedCacheDb
import com.libretv.tv.data.ProbeScheduler
import com.libretv.tv.data.SearchHistoryStore
import com.libretv.tv.data.SourceHealthLedger
import com.libretv.tv.data.SourceMerger
import com.libretv.tv.data.SourceRepository
import com.libretv.tv.data.SourceStore
import com.libretv.tv.data.SubscriptionSync
import com.libretv.tv.data.WatchHistoryStore
import com.libretv.tv.data.local.BackupFiles
import com.libretv.tv.data.local.FavoriteStore
import com.libretv.tv.data.local.SkipMarkStore
import com.libretv.tv.data.local.SubscriptionStore
import com.libretv.tv.data.remote.CmsApiImpl
import com.libretv.tv.data.remote.DoubanApiImpl
import com.libretv.tv.data.remote.HttpEngine
import com.libretv.tv.data.remote.SubscriptionApi
import com.libretv.tv.data.remote.TvboxParser
import com.libretv.tv.domain.gateway.ContentGateway
import com.libretv.tv.domain.gateway.HistoryGateway
import com.libretv.tv.domain.gateway.SourceGateway
import com.libretv.tv.domain.model.BackupBundle
import com.libretv.tv.domain.model.BackupMergeOptions
import com.libretv.tv.domain.model.BackupMergePlan
import com.libretv.tv.domain.model.BackupMerger
import com.libretv.tv.domain.model.BackupSettings
import com.libretv.tv.domain.model.BadgeTag
import com.libretv.tv.domain.model.BadgeVisibility
import com.libretv.tv.domain.model.DetailBundle
import com.libretv.tv.domain.model.FeedCachePolicy
import com.libretv.tv.domain.model.FavoriteEntry
import com.libretv.tv.domain.model.OfferProbe
import com.libretv.tv.domain.model.PlayRequest
import com.libretv.tv.domain.model.SearchResult
import com.libretv.tv.domain.model.SkipMark
import com.libretv.tv.domain.model.SourceConfig
import com.libretv.tv.domain.model.SourceEntry
import com.libretv.tv.domain.model.SourceHealth
import com.libretv.tv.domain.model.SourceOffer
import com.libretv.tv.domain.model.SourceOrigin
import com.libretv.tv.domain.model.Subscription
import com.libretv.tv.domain.model.SubscriptionIntervals
import com.libretv.tv.domain.model.SubscriptionStatus
import com.libretv.tv.domain.model.TitleGroup
import com.libretv.tv.domain.model.TvboxParseResult
import com.libretv.tv.domain.model.VodItem
import com.libretv.tv.domain.model.WatchRecord
import com.libretv.tv.domain.model.asSource
import com.libretv.tv.domain.model.fallbackDetailOrder
import com.libretv.tv.domain.model.toSearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 搜索会话：关键词、结果与加载态（搜索页与结果页共用）。 */
data class SearchSession(
    val keyword: String = "",
    val results: List<SearchResult> = emptyList(),
    val searching: Boolean = false
)

/** 详情会话：详情包、当前线路 / 集数与来源角标（选源页与详情页共用）。 */
data class DetailSession(
    val detail: DetailBundle? = null,
    val activeLine: Int = 0,
    val activeEpisode: Int = 0,
    val error: String? = null,
    val activeOffer: SourceOffer? = null,
    /**
     * 同一个标题的其它来源。
     *
     * 详情页拿不到线路时把它们直接摊给观众点——而不是只留一句
     * 「无播放信息」让人干瞪眼。
     */
    val alternatives: List<SourceOffer> = emptyList()
)

/** 选源会话：正在比较的标题与它的探测结果。 */
data class PickerSession(
    val group: TitleGroup? = null,
    val probes: Map<String, OfferProbe> = emptyMap(),
    /**
     * 本次播放已经换过几个源。
     *
     * 换源会重建播放页，所以计数必须放在会话里，否则每次换源都会从头
     * 开始「同源重试一次」，永远试不完。
     */
    val recoverySwitches: Int = 0,
    /** 已经试过并失败的源（不再自动回到它们）。 */
    val failedOfferKeys: Set<String> = emptySet()
)

/** 应用级设置快照。 */
data class SettingsSnapshot(
    val adFilterEnabled: Boolean = true,
    val badgeVisibility: BadgeVisibility = BadgeVisibility(),
    val debugLogEnabled: Boolean = false,
    val skipMarksEnabled: Boolean = true
)

/**
 * 应用级依赖容器。
 *
 * 这里是唯一允许直接接触数据层的地方：页面 ViewModel 只拿到领域模型
 * 与 StateFlow，既不 new 仓库，也不持有 Context（规则 D3/D5）。
 *
 * 跨页面共享的状态（搜索会话、详情会话、选源会话）也放在这里，
 * 所以「结果页 → 选源页 → 详情页 → 播放页」这条链路上，各页面
 * 看到的是同一份数据，而不需要互相持有引用。
 */
class AppContainer(context: Context) {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ---------- gateways ----------

    /** 内容网关：搜索、分类树、详情与探测。 */
    val content: ContentGateway = SourceRepository(
        app,
        CmsApiImpl(HttpEngine()),
        DoubanApiImpl(HttpEngine())
    )

    /** 源清单网关（与 [content] 同一个实现）。 */
    val sourceGateway: SourceGateway = content as SourceGateway

    /** 观看历史网关。 */
    val history: HistoryGateway = WatchHistoryStore(app)

    /** 首页卡片缓存。 */
    val feedCache = FeedCacheDb(app)

    /** 阶梯冷却台账。 */
    val healthLedger = SourceHealthLedger(SourceStore(app))

    /** 探测调度器：全局并发上限 + 优先级 + 结果缓存。 */
    val probeScheduler = ProbeScheduler()

    private val sourceStore = SourceStore(app)
    private val appSettings = AppSettings(app)
    private val searchHistoryStore = SearchHistoryStore(app)

    // ---------- subscriptions ----------

    /** 订阅网关：订阅配置 + 按归属增删源。 */
    private val subscriptionStore = SubscriptionStore(app, sourceStore)

    /** 订阅抓取。 */
    private val subscriptionApi = SubscriptionApi(HttpEngine())

    /** 订阅同步（前台才跑，单轮最多 5 个、并发 2）。 */
    private val subscriptionSync = SubscriptionSync(
        gateway = subscriptionStore,
        fetch = { url -> subscriptionApi.fetch(url) },
        isForeground = { foregroundCount > 0 }
    )

    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())

    /** 全部远程订阅。 */
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    /** 前台计数：>0 表示应用在前台，自动同步才允许发起请求。 */
    @Volatile
    private var foregroundCount = 0

    /** 正在同步的订阅 id（供界面显示进度）。 */
    private val _syncingSubscriptions = MutableStateFlow<Set<String>>(emptySet())

    /** 正在同步的订阅。 */
    val syncingSubscriptions: StateFlow<Set<String>> = _syncingSubscriptions.asStateFlow()

    // ---------- app-level state ----------

    private val _sources = MutableStateFlow<List<SourceConfig>>(emptyList())
    /** 启用的采集源（所有扇出都用它）。 */
    val sources: StateFlow<List<SourceConfig>> = _sources.asStateFlow()

    private val _sourceEntries = MutableStateFlow<List<SourceEntry>>(emptyList())
    /** 全部采集源（含被禁用的），供源管理页展示。 */
    val sourceEntries: StateFlow<List<SourceEntry>> = _sourceEntries.asStateFlow()

    private val _sourceHealth = MutableStateFlow<Map<String, SourceHealth>>(emptyMap())
    /** 源健康度（探活结果 + 冷却档位）。 */
    val sourceHealth: StateFlow<Map<String, SourceHealth>> = _sourceHealth.asStateFlow()

    private val _titleIndex = MutableStateFlow<Map<String, String>>(emptyMap())
    /** 拼音首字母索引。 */
    val titleIndex: StateFlow<Map<String, String>> = _titleIndex.asStateFlow()

    private val _settings = MutableStateFlow(
        SettingsSnapshot(
            adFilterEnabled = appSettings.adFilterEnabled,
            badgeVisibility = appSettings.badgeVisibility,
            debugLogEnabled = appSettings.debugLogEnabled,
            skipMarksEnabled = appSettings.skipMarksEnabled
        )
    )
    /** 应用级设置。 */
    val settings: StateFlow<SettingsSnapshot> = _settings.asStateFlow()

    /** 搜索历史。 */
    val searchHistory: StateFlow<List<String>> = searchHistoryStore.keywords

    /** 观看历史。 */
    val historyRecords: StateFlow<List<WatchRecord>> = history.records

    // ---------- sessions ----------

    private val _searchSession = MutableStateFlow(SearchSession())
    val searchSession: StateFlow<SearchSession> = _searchSession.asStateFlow()

    private val _detailSession = MutableStateFlow(DetailSession())
    val detailSession: StateFlow<DetailSession> = _detailSession.asStateFlow()

    private val _pickerSession = MutableStateFlow(PickerSession())
    val pickerSession: StateFlow<PickerSession> = _pickerSession.asStateFlow()

    // ---------- one-shot events ----------

    private val _effects = Channel<UiEffect>(Channel.BUFFERED)
    /** 应用级一次性事件（导航、提示）。 */
    val effects: Flow<UiEffect> = _effects.receiveAsFlow()

    fun navigate(route: String, replaceCurrent: Boolean = false) {
        _effects.trySend(UiEffect.Navigate(route, replaceCurrent))
    }

    fun message(text: String) {
        _effects.trySend(UiEffect.Message(text))
    }

    init {
        Logger.level = appSettings.logLevel
        scope.launch { loadSources() }
        refreshSubscriptions()
        refreshSkipMarks()
    }

    // ---------- sources ----------

    /** 读取内置源并合成视图层的源清单。 */
    suspend fun loadSources() {
        val loaded = sourceGateway.loadSources()
        bundled = loaded
        _titleIndex.value = sourceGateway.loadTitleIndex()
        applySourceEntries(loaded)
    }

    /** 内置源清单（不含用户改动）。 */
    private var bundled: List<SourceConfig> = emptyList()

    /** 重新计算源清单（内置 + 自定义 - 已删除 - 已禁用）。 */
    private fun applySourceEntries(bundled: List<SourceConfig>) {
        val entries = SourceMerger.merge(
            bundled = bundled,
            custom = sourceStore.customSources,
            removedUrls = sourceStore.removedUrls,
            disabledUrls = sourceStore.disabledUrls
        )
        _sourceEntries.value = entries
        // 被禁用的采集源不参与任何扇出。
        _sources.value = entries.filter { it.enabled }.map { it.config }
    }

    /**
     * 已启用源集合的签名；进缓存键的一部分（§D.2）。
     *
     * 源集一变签名就变 → 缓存键变 → 旧条目既读不到也判无效，
     * 所以这里不需要任何「源变了就清缓存」的手工路径。
     */
    val sourceSignature: String
        get() = FeedCachePolicy.sourceSignature(_sources.value.map { it.url })

    /** 开关一个采集源。 */
    fun setSourceEnabled(url: String, enabled: Boolean) {
        val disabled = sourceStore.disabledUrls.toMutableSet()
        if (enabled) disabled.remove(url) else disabled.add(url)
        sourceStore.disabledUrls = disabled
        applySourceEntries(bundledSources())
    }

    /** 删除一个采集源（内置或自定义）。 */
    fun removeSource(url: String) {
        val custom = sourceStore.customSources
        if (custom.any { it.url == url }) {
            sourceStore.customSources = custom.filterNot { it.url == url }
        } else {
            sourceStore.removedUrls = sourceStore.removedUrls + url
        }
        sourceStore.disabledUrls = sourceStore.disabledUrls - url
        applySourceEntries(bundledSources())
    }

    /** 手动添加一个采集源。 */
    fun addSource(name: String, url: String, dialect: String = "standard"): Boolean {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty() || !cleanUrl.startsWith("http")) return false
        if (_sourceEntries.value.any { it.url == cleanUrl }) return false
        val cleanName = name.trim().ifEmpty { cleanUrl.substringAfter("://").substringBefore("/") }
        sourceStore.customSources = sourceStore.customSources + SourceConfig(
            name = cleanName,
            url = cleanUrl,
            dialect = dialect
        )
        applySourceEntries(bundledSources())
        return true
    }

    /** 撤销全部源编辑，回到内置清单。 */
    fun resetSources() {
        sourceStore.reset()
        applySourceEntries(bundledSources())
    }

    /** 内置源清单（不含用户改动）。 */
    private fun bundledSources(): List<SourceConfig> = bundled

    /** 记录一次探活结果。 */
    fun recordHealth(url: String, probe: SourceHealth): SourceHealth {
        val health = healthLedger.record(url, probe)
        _sourceHealth.value = _sourceHealth.value + (url to health)
        return health
    }

    /** 记录一批探活结果。 */
    fun recordHealth(results: Map<String, SourceHealth>) {
        _sourceHealth.value = _sourceHealth.value + results
    }

    /** 清掉一个源的冷却记录。 */
    fun clearHealth(url: String) {
        healthLedger.reset(url)
        _sourceHealth.value = _sourceHealth.value - url
    }

    /** 源管理页展示的冷却状态。 */
    fun cooldownState(url: String): SourceHealth = healthLedger.restore(url)

    // ---------- subscriptions ----------

    /**
     * 应用切到前台 / 后台。
     *
     * 自动同步只在前台发起：后台同步费电、没人在看，还可能和首屏抢带宽
     * （design D2）。
     */
    fun setForeground(foreground: Boolean) {
        foregroundCount = if (foreground) foregroundCount + 1 else (foregroundCount - 1).coerceAtLeast(0)
        if (foreground) syncDueSubscriptions()
    }

    /** 读取订阅列表。 */
    fun loadSubscriptions() {
        refreshSubscriptions()
    }

    /** 同步到期的订阅（前台调用）。 */
    fun syncDueSubscriptions() {
        scope.launch {
            val report = subscriptionSync.syncDue()
            if (report.skipped) return@launch
            refreshSubscriptions()
            if (report.synced > 0 || report.removed > 0) applySourceEntries(bundled)
            if (report.synced > 0 || report.failed > 0) {
                val text = buildString {
                    append("订阅同步：成功 ")
                    append(report.synced)
                    append(" 个")
                    if (report.failed > 0) append("，失败 ").append(report.failed).append(" 个")
                    if (report.truncated > 0) append("，超出上限丢弃 ").append(report.truncated).append(" 个")
                }
                message(text)
            }
        }
    }

    /**
     * 添加订阅；成功后立即同步一次，让观众马上看到结果。
     */
    fun addSubscription(name: String, url: String, refreshDays: Int): Boolean {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http")) return false
        if (!SubscriptionIntervals.isAllowed(refreshDays)) return false
        val list = subscriptionStore.subscriptions()
        if (list.any { it.url == cleanUrl }) return false
        val cleanName = name.trim().ifEmpty { cleanUrl.substringAfter("://").substringBefore("/") }
        val subscription = Subscription(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            url = cleanUrl,
            refreshDays = refreshDays
        )
        subscriptionStore.saveSubscriptions(list + subscription)
        refreshSubscriptions()
        syncSubscriptionNow(subscription.id)
        return true
    }

    /**
     * 修改订阅：改名、换地址或改刷新周期。
     *
     * 改了就等于观众重新认可了这个订阅——失败计数清零，自动检查恢复
     * （design D2）。
     */
    fun updateSubscription(id: String, name: String, url: String, refreshDays: Int): Boolean {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http")) return false
        if (!SubscriptionIntervals.isAllowed(refreshDays)) return false
        val list = subscriptionStore.subscriptions()
        val current = list.firstOrNull { it.id == id } ?: return false
        val cleanName = name.trim().ifEmpty { current.name }
        val updated = current.copy(
            name = cleanName,
            url = cleanUrl,
            refreshDays = refreshDays,
            failureCount = 0,
            status = SubscriptionStatus.ACTIVE
        )
        subscriptionStore.saveSubscriptions(list.map { if (it.id == id) updated else it })
        refreshSubscriptions()
        return true
    }

    /** 删除订阅，并把它带来的源一并移除。 */
    fun deleteSubscription(id: String) {
        val list = subscriptionStore.subscriptions()
        subscriptionStore.saveSubscriptions(list.filterNot { it.id == id })
        subscriptionStore.removeSubscriptionSources(id)
        refreshSubscriptions()
        applySourceEntries(bundled)
    }

    /**
     * 解析一份 TVBOX 配置。
     *
     * 解析器在 `data` 层，结果模型在 `domain` 层：ui 层只通过这里拿到解析结果，
     * 不直接依赖 `data`（规则 G1）。
     */
    fun parseTvboxConfig(text: String): TvboxParseResult = TvboxParser.parse(text)

    /**
     * 批量导入采集源（TVBOX 配置解析出来的）。
     *
     * 按 URL 去重后并入自定义源；已存在的源不动。返回新增的数量。
     */
    fun importSources(sources: List<SourceConfig>): Int {
        val existing = sourceStore.customSources.map { it.url }.toSet()
        val fresh = sources.filter { it.url.isNotBlank() && it.url !in existing }
        if (fresh.isNotEmpty()) {
            sourceStore.customSources = sourceStore.customSources + fresh
            applySourceEntries(bundled)
        }
        return fresh.size
    }

    /** 立即同步某个订阅。 */
    fun syncSubscriptionNow(id: String) {
        if (id in _syncingSubscriptions.value) return
        _syncingSubscriptions.value = _syncingSubscriptions.value + id
        scope.launch {
            try {
                val outcome = subscriptionSync.syncNow(id)
                refreshSubscriptions()
                applySourceEntries(bundled)
                val extra = if (outcome.truncated > 0) "（超出上限丢弃 ${outcome.truncated} 个）" else ""
                message(
                    if (outcome.success) "${outcome.message}${extra}"
                    else "订阅同步失败：${outcome.message}"
                )
            } finally {
                _syncingSubscriptions.value = _syncingSubscriptions.value - id
            }
        }
    }

    /** 立即同步全部订阅。 */
    fun syncAllSubscriptions() {
        scope.launch {
            val report = subscriptionSync.syncAll()
            refreshSubscriptions()
            applySourceEntries(bundled)
            message("订阅同步：成功 ${report.synced} 个，失败 ${report.failed} 个")
        }
    }

    /** 把订阅列表重新读进状态流。 */
    private fun refreshSubscriptions() {
        _subscriptions.value = subscriptionStore.subscriptions()
    }

    // ---------- backup ----------

    /** 收藏存储（备份包要带上收藏）。 */
    private val favoriteStore = FavoriteStore(app)

    /** 跳过标记存储。 */
    private val skipMarkStore = SkipMarkStore(app)

    /** 备份文件落地。 */
    private val backupFiles = BackupFiles(app)

    /**
     * 导出备份包。
     *
     * 备份只装「配置与记录」：源列表、订阅、设置、观看历史与收藏——不含
     * 任何播放地址与缓存（design D7）。
     */
    fun exportBackup(): BackupBundle = BackupBundle(
        version = BackupBundle.CURRENT_VERSION,
        exportedAt = System.currentTimeMillis(),
        sources = _sourceEntries.value.map { it.config },
        subscriptions = subscriptionStore.subscriptions(),
        settings = BackupSettings(
            adFilterEnabled = appSettings.adFilterEnabled,
            posterBadgesEnabled = appSettings.posterBadgesEnabled,
            debugLogEnabled = appSettings.debugLogEnabled,
            skipMarksEnabled = appSettings.skipMarksEnabled
        ),
        history = history.records.value,
        favorites = favoriteStore.favorites()
    )

    /** 当前本地数据的备份快照（导入时作为合并的底）。 */
    private fun currentBackup(): BackupBundle = BackupBundle(
        version = BackupBundle.CURRENT_VERSION,
        exportedAt = System.currentTimeMillis(),
        sources = _sourceEntries.value.map { it.config },
        subscriptions = subscriptionStore.subscriptions(),
        settings = BackupSettings(
            adFilterEnabled = appSettings.adFilterEnabled,
            posterBadgesEnabled = appSettings.posterBadgesEnabled,
            debugLogEnabled = appSettings.debugLogEnabled,
            skipMarksEnabled = appSettings.skipMarksEnabled
        ),
        history = history.records.value,
        favorites = favoriteStore.favorites()
    )

    /**
     * 导入预览：算出「会新增什么、会跳过什么」，但先不落盘。
     *
     * 观众在预览里看清差异再决定，这是导入这种破坏性操作的底线。
     */
    fun previewBackup(
        bundle: BackupBundle,
        options: BackupMergeOptions = BackupMergeOptions()
    ): BackupMergePlan = BackupMerger.merge(currentBackup(), bundle, options)

    /**
     * 执行导入：把合并结果写回本地。
     *
     * 规则：源与订阅按地址去重后追加；历史与收藏默认**不覆盖**本地已有
     * 数据，除非预览里勾了覆盖。
     */
    fun applyBackup(
        bundle: BackupBundle,
        options: BackupMergeOptions = BackupMergeOptions()
    ): BackupMergePlan {
        val plan = previewBackup(bundle, options)

        // 源：备份里多出来的地址写成自定义源（内置源不用写回）。
        val knownUrls = _sourceEntries.value.map { it.url }.toSet()
        val newSources = plan.sources.filterNot { it.url in knownUrls }
        if (newSources.isNotEmpty()) {
            sourceStore.customSources = sourceStore.customSources + newSources.map {
                it.copy(subscriptionId = "", origin = SourceOrigin.MANUAL)
            }
            applySourceEntries(bundled)
        }

        // 订阅：本地没有的地址补进来，id 重新生成，失败计数清零。
        val localSubs = subscriptionStore.subscriptions()
        val localSubUrls = localSubs.map { it.url }.toSet()
        val newSubs = plan.subscriptions.filterNot { it.url in localSubUrls }
        if (newSubs.isNotEmpty()) {
            subscriptionStore.saveSubscriptions(
                localSubs + newSubs.map {
                    it.copy(
                        id = UUID.randomUUID().toString(),
                        status = SubscriptionStatus.ACTIVE,
                        failureCount = 0,
                        lastSyncAt = 0L
                    )
                }
            )
            refreshSubscriptions()
        }

        // 历史：plan.history 已按 overwrite 选项合并好（覆盖模式下同一 key
        // 以备份为准），整体写回即可；upsert 对已有 key 是覆盖写。
        plan.history.forEach { history.upsert(it) }

        // 收藏：按归一化片名合并后的结果整体写回。
        favoriteStore.replaceAll(plan.favorites)

        if (options.importSettings) {
            setAdFilterEnabled(plan.settings.adFilterEnabled)
            setPosterBadgesEnabled(plan.settings.posterBadgesEnabled)
            setSkipMarksEnabled(plan.settings.skipMarksEnabled)
            setDebugLogEnabled(plan.settings.debugLogEnabled)
        }

        return plan
    }

    /**
     * 导出备份并交给系统分享。
     *
     * 文件写在 cacheDir 下，UI 层只负责把 Intent 发出去。
     */
    fun exportBackupFile() {
        val bundle = exportBackup()
        val file = backupFiles.write(BackupCodec.encode(bundle), bundle.exportedAt)
        _effects.trySend(UiEffect.ShareFile(file.absolutePath))
        message("备份已生成：${file.name}")
    }

    /** 解析一份备份文件文本。 */
    fun decodeBackup(text: String): Result<BackupBundle> = BackupCodec.decode(text)

    private val _recoveryNotice = MutableStateFlow<String?>(null)

    /** 换源提示（「已切换到 XX 源」）；播放页读一次就消费掉。 */
    val recoveryNotice: StateFlow<String?> = _recoveryNotice.asStateFlow()

    // ---------- skip marks ----------

    private val _skipMarks = MutableStateFlow<List<SkipMark>>(emptyList())

    /** 全部跳过标记（管理页用）。 */
    val skipMarksState: StateFlow<List<SkipMark>> = _skipMarks.asStateFlow()

    /** 全部跳过标记。 */
    fun skipMarks(): List<SkipMark> = skipMarkStore.marks()

    /** 某部影片的跳过标记。 */
    fun skipMark(titleKey: String): SkipMark? = skipMarkStore.mark(titleKey)

    /** 保存一条跳过标记。 */
    fun saveSkipMark(mark: SkipMark) {
        skipMarkStore.save(mark)
        refreshSkipMarks()
    }

    /** 删除某部影片的跳过标记。 */
    fun removeSkipMark(titleKey: String) {
        skipMarkStore.remove(titleKey)
        refreshSkipMarks()
    }

    /** 把跳过标记重新读进状态流。 */
    private fun refreshSkipMarks() {
        _skipMarks.value = skipMarkStore.marks()
    }

    /** 开关跳过标记总开关。 */
    fun setSkipMarksEnabled(enabled: Boolean) {
        appSettings.skipMarksEnabled = enabled
        _settings.value = _settings.value.copy(skipMarksEnabled = enabled)
    }

    // ---------- favorites ----------

    /** 全部收藏。 */
    fun favorites(): List<FavoriteEntry> = favoriteStore.favorites()

    // ---------- settings ----------

    fun setAdFilterEnabled(enabled: Boolean) {
        appSettings.adFilterEnabled = enabled
        _settings.value = _settings.value.copy(adFilterEnabled = enabled)
    }

    fun setDebugLogEnabled(enabled: Boolean) {
        appSettings.debugLogEnabled = enabled
        Logger.level = appSettings.logLevel
        _settings.value = _settings.value.copy(debugLogEnabled = enabled)
        Logger.i(TAG, "调试日志：${if (enabled) "开启" else "关闭"}")
    }

    fun setPosterBadgesEnabled(enabled: Boolean) {
        appSettings.posterBadgesEnabled = enabled
        _settings.value = _settings.value.copy(badgeVisibility = appSettings.badgeVisibility)
    }

    /** 开关单个海报标签。 */
    fun setBadgeVisible(tag: BadgeTag, visible: Boolean) {
        appSettings.badgeVisibility = appSettings.badgeVisibility.with(tag, visible)
        _settings.value = _settings.value.copy(badgeVisibility = appSettings.badgeVisibility)
    }

    // ---------- search history ----------

    fun addSearchHistory(keyword: String) = searchHistoryStore.add(keyword)

    fun deleteSearchHistory(keyword: String) = searchHistoryStore.delete(keyword)

    fun clearSearchHistory() = searchHistoryStore.clear()

    // ---------- sessions ----------

    /** 开始一次搜索：先清空旧结果，再通知结果页。 */
    fun beginSearch(keyword: String) {
        _searchSession.value = SearchSession(keyword = keyword, searching = true)
    }

    /** 搜索结果落地。 */
    fun publishSearchResults(results: List<SearchResult>) {
        _searchSession.value = _searchSession.value.copy(results = results, searching = false)
    }

    /** 选中一个标题组（进入选源页）。 */
    fun selectGroup(group: TitleGroup) {
        _pickerSession.value = PickerSession(group = group)
    }

    /** 清空选源会话：单一来源直达详情时，不留下上一个标题组。 */
    fun clearPickerSession() {
        _pickerSession.value = PickerSession()
    }

    /** 写回探测结果（实测画质优先于备注声明）。 */
    fun publishProbes(group: TitleGroup, probes: Map<String, OfferProbe>) {
        if (_pickerSession.value.group?.key != group.key) return
        _pickerSession.value = _pickerSession.value.copy(
            probes = probes,
            group = group.withMeasuredQuality(probes)
        )
    }

    /**
     * 打开一个标题的详情。
     *
     * 旧的来源角标会被清掉：它描述的是上一个搜索结果的来源，
     * 从分类货架或历史打开时留着会张冠李戴。
     *
     * [fallbackOffers] 是同一标题的其他来源：详情请求在第一个来源上
     * 拿不到可播线路（地址失效 / 响应失败 / 没解析出行）时，按延迟
     * 从低到高逐个退到它们，而不是把「该源未提供播放信息」甩给观众。
     */
    suspend fun openDetail(
        result: SearchResult,
        fallbackOffers: List<SourceOffer> = emptyList()
    ) {
        val matchingOffer = _detailSession.value.activeOffer?.takeIf {
            it.sourceUrl == result.sourceUrl && it.vodId == result.item.vodId
        }
        _detailSession.value = DetailSession(activeOffer = matchingOffer)
        val candidates = listOf(result) + fallbackDetailOrder(result, fallbackOffers)
        var lastBundle: DetailBundle? = null
        for (candidate in candidates) {
            val source = if (candidate.sourceUrl.isNotBlank()) {
                SourceConfig(candidate.sourceName, candidate.sourceUrl)
            } else {
                _sources.value.firstOrNull { it.name == candidate.sourceName } ?: continue
            }
            val bundle = content.fetchDetail(source, candidate.item)
            if (bundle != null && bundle.lines.isNotEmpty()) {
                _detailSession.value = _detailSession.value.copy(
                    detail = bundle,
                    // 其它来源留给详情页：万一这个源的线路也点不动，
                    // 观众能直接换一条，不用退回搜索结果重来。
                    alternatives = fallbackOffers.filterNot {
                        it.sourceUrl == candidate.sourceUrl && it.vodId == candidate.item.vodId
                    }
                )
                return
            }
            // 有响应但没线路，或请求直接失败：记住元数据，退到下一个来源。
            if (bundle != null) lastBundle = bundle
            Logger.w(TAG, "详情没有可播线路，退到下一个来源：${candidate.sourceName}")
        }
        // 所有来源都拿不到线路：保留最后一份元数据（海报/简介还能看），
        // 并给出明确的失败原因，而不是静默的成功假象。
        _detailSession.value = _detailSession.value.copy(
            detail = lastBundle ?: DetailBundle(result.sourceName, result.item, emptyList()),
            error = "详情获取失败，可能该源已失效",
            alternatives = fallbackOffers
        )
    }

    /**
     * 详情页里换一条来源线路。
     *
     * 观众在详情页直接点了另一个源：把它设成当前角标，再按同样的
     * 兜底链重新拉一次详情。
     */
    fun switchDetailSource(offer: SourceOffer) {
        setActiveOffer(offer)
        val alternatives = _detailSession.value.alternatives
        scope.launch { openDetail(offer.toSearchResult(), alternatives) }
    }

    /**
     * 记住当前选中的来源角标。
     *
     * 详情页与播放页都要用它标注「这个地址来自哪个采集站」，
     * 换源时也会被新的角标覆盖。
     */
    fun setActiveOffer(offer: SourceOffer?) {
        _detailSession.value = _detailSession.value.copy(activeOffer = offer)
    }

    /** 清掉来源角标：从分类货架或历史打开标题时用。 */
    fun clearActiveOffer() {
        _detailSession.value = _detailSession.value.copy(activeOffer = null)
    }

    /**
     * 异步打开详情。
     *
     * 页面先跳过去，详情在后台拉取——详情页自己有「正在获取详情…」的
     * 状态，不该让用户在原地等待。
     */
    fun openDetailAsync(result: SearchResult, fallbackOffers: List<SourceOffer> = emptyList()) {
        scope.launch { openDetail(result, fallbackOffers) }
    }

    /** 选中线路。 */
    fun selectLine(index: Int) {
        _detailSession.value = _detailSession.value.copy(
            activeLine = index.coerceAtLeast(0),
            activeEpisode = 0
        )
    }

    /** 选中集数。 */
    fun selectEpisode(index: Int) {
        _detailSession.value = _detailSession.value.copy(activeEpisode = index.coerceAtLeast(0))
    }

    /**
     * 从历史恢复播放：重新拉一次详情，把新鲜的播放地址交给播放页。
     *
     * 采集站地址会过期，所以不盲用历史里存的 url；详情拿不到时才退回旧地址。
     */
    suspend fun resumeFromHistory(record: WatchRecord): PlayRequest? {
        val source = SourceConfig(record.sourceName, record.sourceUrl)
        val item = VodItem(
            vodId = record.vodId,
            vodName = record.title,
            vodPic = record.poster,
            typeName = record.typeName
        )
        val bundle = if (record.sourceUrl.isNotBlank() && record.vodId.isNotBlank()) {
            withContext(Dispatchers.IO) {
                try {
                    content.fetchDetail(source, item)
                } catch (e: Exception) {
                    Logger.w(TAG, "恢复播放失败：${record.title}（${e.message}）")
                    null
                }
            }
        } else {
            null
        }
        _detailSession.value = _detailSession.value.copy(
            detail = bundle,
            activeLine = record.lineIndex.coerceAtLeast(0),
            activeEpisode = record.episodeIndex.coerceAtLeast(0),
            error = null
        )
        val freshUrl = bundle?.lines?.getOrNull(record.lineIndex)
            ?.episodes?.getOrNull(record.episodeIndex)?.url
            ?.takeIf { it.isNotBlank() }
        if (bundle == null && record.rawUrl.isBlank()) {
            _detailSession.value = _detailSession.value.copy(error = "无法解析该影片，请稍后重试")
            return null
        }
        return PlayRequest(
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
    }

    // ---------- history ----------

    /** 删除一条观看历史。 */
    fun deleteHistory(key: String) = history.delete(key)

    /** 清空观看历史。 */
    fun clearHistory() = history.clear()

    /**
     * 播放失败：把失败记到这个源的账上（阶梯冷却据此升级）。
     */
    fun reportPlaybackFailure(sourceUrl: String, errorKind: String = "") {
        if (sourceUrl.isBlank()) return
        Logger.w(TAG, "播放失败：$sourceUrl（$errorKind），计入健康度")
        healthLedger.record(
            sourceUrl,
            SourceHealth(reachable = false, lastError = SourceError.Unreachable)
        )
    }

    /**
     * 播放失败后的自动换源：解析 [offer] 的详情，起播同位置的集数。
     *
     * 进度以**比例**传递（旧位置 / 旧时长），起播后按新时长换算——不同源
     * 的片头长度不同，直接搬毫秒会跳错位置。
     */
    suspend fun switchSourceForRecovery(
        offer: SourceOffer,
        resumeRatio: Float,
        episodeIndex: Int
    ): PlayRequest? {
        _detailSession.value = _detailSession.value.copy(activeOffer = offer)
        // 记下换过的源：换源会重建播放页，计数与失败记录必须留在会话里。
        _pickerSession.value = _pickerSession.value.copy(
            recoverySwitches = _pickerSession.value.recoverySwitches + 1,
            failedOfferKeys = _pickerSession.value.failedOfferKeys + offer.key
        )
        _recoveryNotice.value = "已切换到 ${offer.sourceName}"
        val detail = content.fetchDetail(offer.asSource(), VodItem(vodId = offer.vodId))
        val lines = detail?.lines.orEmpty()
        val line = lines.firstOrNull() ?: run {
            Logger.w(TAG, "自动换源失败：${offer.sourceName} 无可用线路")
            return null
        }
        // 同位置集数：新源集数可能更少，取不超过当前位置的最后一集。
        val targetIndex = episodeIndex.coerceAtMost(line.episodes.size - 1)
        val episode = line.episodes.getOrNull(targetIndex) ?: return null
        // 换源后详情与集数列表一起更新，播放页的集数条才能跟上。
        _detailSession.value = _detailSession.value.copy(
            detail = detail,
            activeLine = 0,
            activeEpisode = targetIndex
        )
        return PlayRequest(
            title = detail?.item?.vodName ?: offer.title.vodName,
            poster = detail?.item?.vodPic ?: offer.title.vodPic,
            episodeName = episode.name,
            rawUrl = episode.url,
            sourceName = offer.sourceName,
            sourceUrl = offer.sourceUrl,
            vodId = offer.vodId,
            lineIndex = 0,
            episodeIndex = targetIndex,
            typeName = detail?.item?.typeName ?: offer.title.typeName,
            resumeRatio = resumeRatio
        )
    }

    private companion object {
        const val TAG = "AppContainer"
    }
}

/**
 * 把实测画质写回候选来源。
 *
 * 采集站写在备注里的清晰度只是声明，实测到的分辨率才可信；实测失败时保留
 * 文本推断值。
 */
private fun TitleGroup.withMeasuredQuality(probes: Map<String, OfferProbe>): TitleGroup =
    copy(
        offers = offers.map { offer ->
            val probe = probes[offer.key] ?: return@map offer
            offer.copy(
                measuredQuality = probe.measuredQuality,
                bandwidthBps = probe.bandwidthBps
            )
        }
    )
