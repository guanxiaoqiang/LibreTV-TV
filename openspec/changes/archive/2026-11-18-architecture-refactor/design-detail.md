# 详细设计（概要设计的展开）

> 配套文档：`design.md`（概要设计）。本文只展开**可实施的契约级细节**，不重复总体结论。
> 本阶段只产出设计，不改动 `app/src/**` 任何代码。

---

## A. 决策记录

| # | 议题 | 决定 | 状态 | 备注 |
|---|---|---|---|---|
| D1 | 是否引入 Navigation Compose | **引入** | ✅ 已确认 | 手工 `Screen` 枚举 + `detailFrom/pickerFrom/resultFrom` 是状态层膨胀的直接成因 |
| D2 | 阶段执行顺序 | **先做设计，代码暂不动** | ✅ 已确认 | 本变更停留在设计态，实施另开变更 |
| D3 | Retrofit / Room / Hilt | **均不引入** | 待确认 | 手写接口层 + 原生 SQLite + 手动 DI |
| D4 | 方言策略是否按源可配 | **是**，写入 `SourceConfig.dialect` | 待确认 | 把回退链从"代码"变成"配置" |
| D5 | 缓存是否持久化播放地址 | **不持久化**，权威键 `sourceUrl+vodId` | 待确认 | 播放地址会过期 |
| D6 | 迁移期是否保留 v1 缓存读取 | **保留一个版本**（双读双写） | 待确认 | 避免冷启动空窗 |

### A.1 D1 的收益与代价（为什么必须做）

| 项 | 说明 |
|---|---|
| 收益 1 | 删除 `UiState.detailFrom` / `pickerFrom` / `resultFrom` 三个字段，"从哪来"由导航栈维护，返回错页的组合爆炸消失 |
| 收益 2 | 页面参数改为路由参数，`PlayerScreen` / `DetailScreen` 不再依赖 `UiState` 全量快照，UI 可独立预览与测试 |
| 收益 3 | 系统返回键与遥控器返回键统一走 `NavController.popBackStack()`，行为与 Android 惯例一致 |
| 代价 1 | 引入 `navigation-compose` 依赖（+约 300KB 方法数，需开启 minify 才有实际收益） |
| 代价 2 | 迁移期需保证返回路径与现状**逐条等价**，回归风险最高 → 已排在 P3 并单列验收清单（F.3） |
| 代价 3 | `MainActivity.kt` 的 `when(state.screen)` 路由要整体重写，不可增量混用两套路由 |

**结论**：代价集中在一次性重写（可控），收益是永久消除一类状态字段（结构性）。采纳。

---

## B. 领域模型契约

当前模型链 `VodItem → SearchResult → TitleGroup/SourceOffer → FeedCard → CachedCard` 有 5 层、4 次手工搬运。目标：**领域模型只有一套，缓存是它的"绘制最小子集"，由序列化器生成而非手写映射。**

### B.1 来源与健康度

```kotlin
data class SourceConfig(
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val dialect: String = "standard",   // standard | lenient
    val isCustom: Boolean = false
)

enum class HealthState { HEALTHY, DEGRADED, DOWN }

data class SourceHealth(
    val sourceUrl: String,
    val state: HealthState,
    val consecutiveFailures: Int,
    val totalOk: Int,
    val totalFail: Int,
    val lastLatencyMs: Long,
    val lastError: SourceError?,
    val lastCheckedAt: Long
)
```

### B.2 标题与来源

```kotlin
/** 远端条目的最小引用，替代 VodItem 直接外泄到 domain。 */
data class TitleRef(
    val sourceName: String,
    val sourceUrl: String,
    val vodId: String,
    val title: String,
    val poster: String,
    val year: String,
    val area: String,
    val typeName: String,
    val remarks: String,
    val content: String
)

data class SourceOffer(
    val sourceName: String,
    val sourceUrl: String,
    val vodId: String,
    val quality: QualityTier,
    val remark: String,
    val latencyMs: Long,
    val isTrailer: Boolean,
    val firstByteMs: Long? = null,
    val lineCount: Int = 0,
    val episodeCount: Int = 0
) {
    val key: String get() = "$sourceName|$vodId"
}

data class TitleGroup(
    val key: String, val title: String, val poster: String,
    val year: String, val area: String, val typeName: String,
    val offers: List<SourceOffer>
) {
    val bestQuality: QualityTier get() = offers.maxByOrNull { it.quality.rank }?.quality ?: QualityTier.UNKNOWN
    val fastestMs: Long
        get() = offers.filterNot { it.isTrailer }
            .mapNotNull { it.latencyMs.takeIf { ms -> ms > 0L } }.minOrNull() ?: 0L
    val playableCount: Int get() = offers.count { !it.isTrailer }
}
```

**关键精简**：`SourceOffer` 移除当前的 `item: VodItem` 字段。

现状 `FeedCacheMapping.kt:86-94` 之所以要"伪造一个 `VodItem`"，正是因为 `SourceOffer` 里塞了完整远端对象，导致：
- 缓存要重复存一份 title/poster/year/area/typeName/remarks；
- 打开标题时无法确认缓存里的 `VodItem` 是否仍然有效。

移除后：详情由 `FetchTitleDetail(sourceUrl, vodId)` 按需重取，缓存只存身份与元信息。

### B.3 卡片模型

```kotlin
sealed interface CardTarget {
    data class Grouped(val group: TitleGroup) : CardTarget      // 多源 → 选源；单源 → 详情
    data class Discovery(val item: DiscoveryItem) : CardTarget  // 豆瓣发现 → 反查全源
}

data class FeedBadge(val text: String, val kind: FeedBadgeKind, val tag: BadgeTag?)

data class FeedCard(
    val key: String,
    val title: String,
    val poster: String,
    val badges: List<FeedBadge>,
    val cornerBadge: String?,
    val target: CardTarget
)

data class FeedSnapshot(
    val cards: List<FeedCard>,
    val rawResults: List<TitleRef>,
    val seenResultKeys: Set<String>,
    val seenDoubanIds: Set<String>,
    val nextSourcePage: Int,
    val nextDoubanStart: Int,
    val hasMore: Boolean,
    val totalItems: Int
)
```

**`group`/`subject` 双可空 → `CardTarget` 密封类**：现状 `FeedCard.kt:57-65` 的两个可空字段可同时为 null，约束只写在注释里。密封类把"点击卡片该去哪"变成可穷举的 `when`，编译期即排除非法组合。

### B.4 播放模型

```kotlin
data class PlayLine(val name: String, val episodes: List<Episode>)
data class Episode(val index: Int, val name: String, val url: String)

data class DetailPayload(
    val sourceUrl: String,
    val vodId: String,
    val title: String,
    val poster: String,
    val year: String,
    val area: String,
    val typeName: String,
    val remarks: String,
    val content: String,
    val lines: List<PlayLine>
)

data class PlayRequest(
    val sourceUrl: String,
    val vodId: String,
    val lineIndex: Int,
    val episodeIndex: Int
)
```

`PlayRequest` **不携带播放地址**：地址在 `PlayerViewModel` 内按 `sourceUrl + vodId + lineIndex + episodeIndex` 重新解析，解决 P1-10 的"rawUrl 与 sourceUrl+vodId 双轨"。

### B.5 缓存最小子集

| 字段 | 缓存 | 说明 |
|---|---|---|
| `key` / `title` / `poster` | ✅ | 卡片主体 |
| `badges` | ✅ | 角标文本（显示开关在设置里，故缓存而非重算） |
| `cornerBadge` | ✅ | 角标 |
| `group` 身份字段 | ✅ | `year/area/typeName` + `offers` 的 `sourceName/sourceUrl/vodId/quality/remark/latencyMs/isTrailer` |
| `group.content` | ❌ | 仅详情页使用，按需重取 |
| `offer.item` | ❌ | 随 `SourceOffer` 精简移除 |
| `nextSourcePage`/`nextDoubanStart`/`hasMore`/`totalItems` | ✅ | 分页续接 |

序列化改为 **`kotlinx.serialization` 直接标注领域模型**，删除 `FeedCacheMapping.kt` 全部手写映射（现有 26-130 行）。旧缓存用 `ignoreUnknownKeys = true` + 字段默认值兼容读取。

---

## C. 导航与状态详细设计

### C.1 路由表

| 路由 | 参数 | 进入条件 | 返回行为 |
|---|---|---|---|
| `home` | — | 启动 / 从其它页返回 | 保留左导航焦点与内容流进度（现状能力，必须保留） |
| `search` | — | 首页触发搜索 | 回 `home`，恢复焦点位置 |
| `result` | `keyword` | 搜索提交 | 回 `search` |
| `picker` | `groupKey` | 卡片为多源标题 | 回 `home` |
| `detail` | `sourceUrl`, `vodId`, `title` | 单源标题 / 选源后选定 | **从 `picker` 进入则回 `picker`，否则回 `home`** |
| `player` | `sourceUrl`, `vodId`, `lineIndex`, `epIndex` | 详情页确认播放 | 回 `detail` |
| `settings` | — | 设置入口 | 回来源页 |
| `sources` | — | 源管理入口 | 回来源页 |

> **核心收益**：`detail` 的"从 picker 进入就回 picker"语义，当前由 `UiState.detailFrom` 手工维护（状态字段组合）。改造后由 **NavController 栈**天然保证——`picker → detail` 是压栈操作，`popBackStack()` 自然回到 `picker`。`detailFrom` / `pickerFrom` / `resultFrom` 三个字段可直接删除，不再有"字段组合不一致导致返回错页"的可能。

### C.2 参数传递

- 所有参数为**字符串**，通过 `navArgument` 声明；中文标题需 `Uri.encode`。
- `picker` 传 `groupKey`（归一化片名）而非整个 `TitleGroup`：选源页从应用级共享的 `groupsByKey` 取回，避免对象序列化。
- `player` 只传**标识与索引**，不传播放地址（见 B.4）。

### C.3 导航职责边界

| 层 | 职责 |
|---|---|
| ViewModel | 只产出**状态**与**意图**（如 `shouldOpenPicker`、事件流），**不持有 NavController**（规则 D5） |
| Composable | 依据 VM 意图调用 `navController.navigate(...)` / `popBackStack()` |

好处：ViewModel 可在无 NavController 环境下单测；导航行为可在 UI 层单独验证。

### C.4 返回键映射（现状 → 目标）

| 场景 | 现状 | 目标 |
|---|---|---|
| 播放页返回 | `Screen.PLAYER → DETAIL` | `popBackStack()` → `detail`（栈顶是 detail） |
| 详情页返回（来自选源） | 读 `detailFrom == PICKER` | 栈顶是 `picker`，直接 `popBackStack()` |
| 详情页返回（来自首页） | 读 `detailFrom == HOME` | 栈顶是 `home`，直接 `popBackStack()` |
| 选源页返回 | `Screen.PICKER → HOME` | 栈顶是 `home` |
| 结果页返回 | `Screen.RESULT → SEARCH` | 栈顶是 `search` |
| 搜索页返回 | `Screen.SEARCH → HOME` + 恢复焦点 | 栈顶是 `home`；焦点位置由 `HomeViewModel` 持有的焦点 key 恢复 |
| 设置/源管理返回 | 回到进入前的页面 | 栈顶即来源页 |

**结论**：目标态下返回行为 100% 由栈决定，现状靠 3 个字段 + `when` 分支决定。行为等价性由 F.3 验收清单逐条核对。

### C.5 ViewModel 拆分与状态契约

| ViewModel | UiState 字段（≤12） | 主要方法 |
|---|---|---|
| `HomeViewModel` | `tab`, `genre`, `chips`, `snapshot`, `loading`, `refreshing`, `hasMore`, `loadingMore`, `notice` | `openTab`, `selectGenre`, `loadMore`, `refresh` |
| `SearchViewModel` | `query`, `suggestions`, `history`, `loading`, `showKeyboard`, `notice` | `onQueryChange`, `submit`, `clearHistory`, `pickSuggestion` |
| `ResultViewModel` | `keyword`, `groups`, `loading`, `sort`, `notice`, `probing` | `search`, `reSort`, `probe` |
| `PickerViewModel` | `group`, `probes`, `loading`, `notice`, `refreshing` | `load`, `retry`, `pick(offer)` |
| `DetailViewModel` | `detail`, `lineIndex`, `episodeIndex`, `loading`, `notice`, `error` | `load`, `selectLine`, `selectEpisode`, `play` |
| `PlayerViewModel` | `request`, `playUrl`, `error`, `positionMs`, `durationMs`, `buffering` | `start`, `retry`, `reportProgress` |
| `SettingsViewModel` | `settings`, `notice` | `update`, `reset` |
| `SourcesViewModel` | `sources`, `health`, `editing`, `notice` | `add`, `remove`, `toggle`, `probe` |

### C.6 状态约定

1. 每个页面状态类**只含该页字段**，字段数 ≤ 12。
2. 一次性事件（错误提示、Toast）走 `Channel(BUFFERED).receiveAsFlow()`，不再占用状态字段；解决当前 `UiState.error` 在导航时被隐式清零的约定。
3. 共享数据（源清单、设置、历史、健康度）通过 `AppContainer` 暴露的**应用级 StateFlow** 提供，不进入页面 UiState。
4. `FeedSnapshot` 是首页唯一的大对象；分页只追加卡片，不重建快照（避免 `copy()` 反复搬运大列表）。

### C.7 依赖注入

不引入 Hilt，手动容器：

```kotlin
class AppContainer(context: Context) {
    val logger: Logger by lazy { AndroidLogger() }
    val httpEngine: HttpEngine by lazy { HttpEngine(logger) }
    val cmsApi: CmsApi by lazy { CmsApiImpl(httpEngine, logger) }
    val doubanApi: DoubanApi by lazy { DoubanApiImpl(httpEngine, logger) }
    val sourceStore: SourceStore by lazy { SourceStore(context, logger) }
    val feedCacheDao: FeedCacheDao by lazy { FeedCacheDao(context, logger) }
    val contentRepository: ContentRepository by lazy { ContentRepository(cmsApi, doubanApi, logger) }
    val sourceRepository: SourceRepository by lazy { SourceRepository(sourceStore, logger) }
    val historyRepository: HistoryRepository by lazy { HistoryRepository(context, logger) }
}
```

ViewModel 通过 `viewModelFactory { initializer { ... } }` 注入，测试时替换为 Fake。

---

## D. 缓存与存储详细设计

> **持久化层与缓存语义已拆至独立变更 [`../local-first-storage/`](../local-first-storage/)。**
> 完整论证、8 张表 schema、缓存三态语义、失效矩阵、后台刷新调度、收藏与内容目录见
> [`../local-first-storage/storage-and-cache.md`](../local-first-storage/storage-and-cache.md) 与
> [`../local-first-storage/content-catalog.md`](../local-first-storage/content-catalog.md)。
> 本节仅保留**不依赖新持久化层即可落地**的最小子集：缓存键升级与源签名自然失效。

### D.1 三层缓存

| 层 | 载体 | TTL | 失效条件 |
|---|---|---|---|
| L1 内存 | `FeedSnapshot` map | 会话内 | 源集签名变化 |
| L2 持久 | Room `http_cache` 表 | 按 kind 分级（feed 6h / category 12h / detail 6h / probe 10min） | TTL 到期 / `version` 不匹配 / `source_signature` 变化 / 失效矩阵触发 |
| L3 网络 | — | — | — |

### D.2 缓存键与表结构

```
旧键： "TAB|genre"
新键： "v2|TAB|genre|<sourceSignature>"
```

`sourceSignature` = 已启用源 URL 集合的哈希。源集一变 → key 变 → 旧数据自然过期，**不再需要手工 `feedDb.clear()`**（解决 P0-5）。

> 表结构见 `../local-first-storage/storage-and-cache.md` §3：`http_cache(kind, cacheKey, payload, savedAt, expiresAt, hardExpiresAt, version, sourceSignature)`，
> 唯一索引 `(kind, cacheKey)`，清理索引 `(kind, savedAt)`。
> **原手写 `feed_cache` 的 3 列 DDL 作废**，改为 Room 实体（由 `local-first-storage` 变更落地）。

### D.3 迁移策略

1. 旧行 `version = 0`，读取时**视为过期**。
2. 写入只写 v2 键空间。
3. 首次成功刷新后执行一次性清理过期行。
4. 保留 v1 读取路径一个版本（D6），确认无冷启动空窗后删除。
5. **禁止 `fallbackToDestructiveMigration`**：现状 `FeedCacheDb.onUpgrade` 是 `DROP TABLE`（`FeedCacheDb.kt:100-103`），
   加一列即清空全部缓存；改为显式 `Migration(1, 2)`，并把 4 个 SP 文件的历史/搜索历史导入对应表。
   详见 `../local-first-storage/storage-and-cache.md` §4。

### D.4 失效判定

```
valid = savedAt + ttlMinutes*60_000 > now
     && version == CURRENT_VERSION
     && sourceSignature == currentSignature
```

### D.5 本地存储键名集中化

新增 `data/local/StorageKeys.kt`（SP 仍保留给设置与少量偏好项，数据库类数据迁到 Room）：

```kotlin
object StorageKeys {
    const val SCHEMA_VERSION = 2
    const val SOURCES = "libretv_sources"
    const val SETTINGS = "libretv_settings"
    const val WATCH_HISTORY = "libretv_watch_history"
    const val SEARCH_HISTORY = "libretv_search_history"
    // 各 SP 内部的键名也集中在此
}
```

迁移：读取时若 `SCHEMA_VERSION` 缺失或小于当前值，执行对应 Store 的 `migrate()`（当前各 Store 数据结构简单，迁移主要是补默认值）。

### D.6 观看历史权威键

| 字段 | 角色 | 说明 |
|---|---|---|
| `sourceUrl` + `vodId` | **权威键** | 唯一标识一个可播放副本，续播据此重新解析地址 |
| `rawUrl` | 快照 | 仅用于"同一次播放内重试"，不作为恢复依据 |
| `title` / `poster` / `positionMs` / `durationMs` / `updatedAt` | 展示与进度 | — |

---

## E. 并发与健康度详细设计

### E.1 扇出工具

```kotlin
class FanOut<T, R>(
    private val concurrency: Int = 6,
    private val perItemTimeoutMs: Long = 10_000,
    private val totalTimeoutMs: Long = 15_000
) {
    fun flow(items: List<T>, block: suspend (T) -> ApiResult<R>): Flow<Pair<T, ApiResult<R>>>
}
```

1. **全局并发上限 6**（`Semaphore` 或 `Dispatchers.IO.limitedParallelism`）→ 30 源不再同时打满连接。
2. **单源超时 10s**，**整体预算 15s**；超预算的剩余源直接判定 `Timeout`。
3. **超时从外到内**：外层 `withTimeout(total)`，内层单源 `withTimeout(perItem)`。
4. **逐条 `Flow` 返回** → 增量聚合，消除 `TvViewModel.kt:635-637` 的 O(n²) 全量重算。

参数取值理由：现状单源 `callTimeout = 18s` 且最多 3 次方言回退，最坏 ≈ 54s；新策略把单源压到 10s、整体 15s，且回退链在 `CmsDialect` 内共享同一预算（**每次请求共享 10s，而非每次回退各享 10s**）。

### E.2 健康度状态机（阶梯冷却）

> 参考项目 `LibreSpark/LibreTV` 采用「连续失败 → 停用 30 分钟 → 停用 24 小时 → 长期停用」的阶梯降级，本设计采纳并扩展为**可持久化**的冷却模型（对照分析见 `benchmark.md` B1）。

```kotlin
enum class HealthState { HEALTHY, COOLDOWN, SUSPENDED }

data class SourceHealth(
    val sourceUrl: String,
    val state: HealthState,
    val failureTier: Int,            // 0 正常 / 1 停 30 分钟 / 2 停 24 小时 / 3 长期停用
    val cooldownUntil: Long,
    val consecutiveFailures: Int,
    val totalOk: Int, val totalFail: Int,
    val lastLatencyMs: Long,
    val lastError: SourceError?,
    val lastCheckedAt: Long
)
```

**状态迁移**

| 事件 | 迁移 |
|---|---|
| 请求成功 | `failureTier = 0`，`state = HEALTHY` |
| 连续失败 1 次 | `failureTier = 1`，`COOLDOWN` 30 分钟 |
| 冷却期内再次失败 | `failureTier = 2`，`COOLDOWN` 24 小时 |
| 24 小时冷却期内再次失败 | `failureTier = 3`，`SUSPENDED`（长期停用，需手动恢复） |
| 冷却到期 | **半开**：允许 1 次探测，成功则复位，失败则 tier+1 |
| 用户手动恢复 | 强制 `failureTier = 0` 并立即探测 |

**各状态下的系统行为**

| 状态 | 扇出行为 | 源管理页 | 选源页 |
|---|---|---|---|
| HEALTHY | 正常参与 | 绿色标记 | 正常显示 |
| COOLDOWN | **本轮跳过**，到期后半开探测 | 黄色标记 + 剩余冷却时间 | 显示"源暂时停用，剩余 X" |
| SUSPENDED | 永久跳过 | 红色标记 + 一键恢复 | 显示"源已停用" |

**必须持久化**：`SourceHealth` 写入 `SourceStore`（SP），否则重启后 tier 归零，阶梯冷却失效，坏源每次启动都重新踩一遍。

> 这同时解决 P0-3（已知失效源每次仍被请求），并把"源坏了"的处理从"每次重试"改为"越坏越久"。

### E.3 错误到文案的映射

| 错误 | 用户可见文案 | 本轮重试 | 计入降级 |
|---|---|---|---|
| `Timeout` | 该源响应超时 | 否 | ✅ |
| `Unreachable` | 无法连接该源 | 否 | ✅ |
| `HttpFailure(4xx)` | 该源拒绝了请求 | 否 | ✅ |
| `HttpFailure(5xx)` | 该源服务异常 | 下次刷新 | ✅ |
| `Blocked` | 该源触发了风控 | 否 | ✅ |
| `Malformed` | 该源返回格式无法识别 | 否 | ✅ |
| `UnsupportedDialect` | 该源接口不兼容 | 否 | ✅ |
| `EmptyResult` | （静默） | — | ❌ |

`EmptyResult` 是合法结果，不算失败、不计入降级。

### E.4 探活与首包探测

| 场景 | 现状 | 目标 |
|---|---|---|
| 源可达性 | `search(url,"test")`（污染上游） | `GET {base}?ac=list` 取分类树；成功即视为可达，且**复用该分类树**，消除启动时重复探测 |
| 流起播延迟 | 对 m3u8 发 Range（语义错） | HLS → 解析 manifest 首个分片 URI 再探测；MP4 → 现有 Range 方式 |
| 画质真实指标 | 仅从备注文本推断"高清/超清" | 解析 HLS master 的 `RESOLUTION`/`BANDWIDTH` 得到真实分辨率与码率；MP4 用 `Content-Length` 估算。探测失败时降级为备注文本推断并标注"推断" |
| 结果用途 | 仅展示 | 写入 `SourceHealth`，参与扇出排序与过滤 |

> **画质实测**（对照 `benchmark.md` B3）修正了一个隐性错误：现按备注文本排序"最佳画质"，而不同源的"高清"实际分辨率可能相差数倍，跨源不可比。改为由实测分辨率派生 `QualityTier` 后，选源页排序才真正可信。该能力与 P0-8 共用同一次 manifest 解析，无额外请求成本。

### E.5 探测调度队列

探活、流探测、画质实测统一走全局调度器，避免与首页扇出、正在进行的播放抢带宽：

```kotlin
object ProbeScheduler {
    fun enqueue(sourceUrl: String, kind: ProbeKind, priority: Int, block: suspend () -> ProbeResult): Deferred<ProbeResult>
}
```

| 规则 | 值 |
|---|---|
| 全局并发 | 2（探测是辅助任务，优先级低于播放与首页扇出） |
| 优先级 | 用户主动选源探测 > 源管理页"全部测试" > 后台健康巡检 |
| 结果缓存 | `(sourceUrl, kind)` → 结果 + 时间戳，TTL 10 分钟，避免重复探测 |
| 预取复用 | 选源页探测已拿到 manifest 时，详情页不再重复拉取 |

### E.6 播放失败自动恢复与进度保持

```
ExoPlayer onPlayerError
   ├─ 非网络类错误（解码不支持 / 文件非法）→ 直接报错，不重试
   └─ 网络/超时类错误 → Recovery：
        ├─ 1. 同一源重试一次（退避 1.5s）
        ├─ 2. 换下一个 offer（保留剧集与进度）
        ├─ 3. 最多尝试 3 个 offer
        └─ 全部失败 → 提示"所有源均不可用" + "手动选源"入口
```

配套：`PlayRequest` 增加 `resumePositionMs`，换源后按 `pos / oldDuration * newDuration` 比例映射恢复进度。

失败同时计入该源健康度（§E.2），坏源自动降权 —— 用户体验从"每次都踩同一个坏源"变为"自动绕开"。

---

## F. 验收清单

### F.1 单文件与依赖约束（自动化）

- [ ] `ui/**` 中不出现 `import com.libretv.tv.data.`（G1）
- [ ] `domain/**` 中不出现 `import android.`（D2）
- [ ] 单个 ViewModel ≤ 400 行，单个 UI 文件 ≤ 600 行（G5）
- [ ] 既有 9 个单测全绿

### F.2 规格回归（12 份 spec 逐条）

| 规格 | 场景数 | 重点核对 |
|---|---|---|
| `app-shell` | 5 | 状态恢复、返回路径 |
| `content-discovery` | 12 | 卡片不跳动、缓存先渲染、分类映射 |
| `search` | 8 | 扇出、排序、候选 |
| `title-detail` | 7 | 线路解析、选集 |
| `source-comparison` | 5 | 多源排序、探测结果 |
| `watch-history` | 5 | 续播、进度 |
| `source-management` | 6 | 增删源、失效标记 |
| `playback` | 6 | 起播、广告过滤、清晰度 |
| `settings` | 5 | 角标开关、缓存时长 |
| `ad-filtering` | 5 | 广告过滤行为不变 |
| `tv-navigation` | 5 | 焦点移动、返回 |
| `douban-recommendation` | 3 | 豆瓣条带 |

### F.3 返回路径等价性（D1 的核心验收）

逐条操作并记录返回落点：

| # | 操作 | 期望落点 |
|---|---|---|
| 1 | 首页 → 搜索 → 返回 | 首页，且左导航焦点与内容流位置不变 |
| 2 | 搜索 → 结果 → 返回 | 搜索页 |
| 3 | 首页 → 多源卡片 → 选源 → 返回 | 首页 |
| 4 | 首页 → 多源卡片 → 选源 → 选源 → 返回 | 选源页 |
| 5 | 首页 → 单源卡片 → 详情 → 返回 | 首页 |
| 6 | 首页 → 设置 → 返回 | 首页 |
| 7 | 详情 → 播放 → 返回 | 详情页 |
| 8 | 播放中按 Home 再回 → 应用 | 回到详情页或首页（现状为准，不变） |
| 9 | 首页 → 源管理 → 返回 | 首页 |

### F.4 性能与稳定性

- [ ] 30 源全开时并发连接数 ≤ 6（抓包或日志确认）
- [ ] 一轮首页聚合总耗时 ≤ 15s，超时后正常出画
- [ ] 冷启动有缓存时，首屏卡片出现时间不劣化于现状
- [ ] 缓存 TTL 到期后自动刷新，不需要手工清库
- [ ] 禁用一个源后，缓存 key 变化，旧缓存不再被展示

---

## G. 实施顺序（与 `tasks.md` 对齐）

```
P0 止血（日志 + 分层修正 + 并发预算）        ← 不改结构，先获得可诊断性
P1 接口层（CmsApi / CmsDialect / ApiResult）  ← 新旧并存，用接口包住旧实现
P2 领域层（model + usecase + 单测）           ← 行为等价的规则搬迁
P3 导航与状态（Navigation Compose + 拆 VM）  ← 回归风险最高，单独验收 F.3
P4 缓存与存储（TTL / 签名 / 键名集中）        ← 可与 P3 并行
P5 清理与工程化（死代码 / 签名 / CI）         ← 最后
```

**每阶段准入**：既有 9 个单测全绿 + 对应 spec 场景全成立 + 上一阶段验收项通过。

