# 概要设计：LibreTV-TV 架构重构

> 目标：在**不改变任何可见行为**的前提下，把"能跑"的结构升级为"可扩展、可测试、可诊断"的结构。
> 回归基线：`openspec/specs/**` 现有 12 份能力规格。
> 本阶段只产出设计，不改动 `app/src/**` 任何代码。
> 配套：[`design-detail.md`](./design-detail.md)（领域模型契约、导航路由表、缓存迁移、验收清单）、[`benchmark.md`](./benchmark.md)（与参考项目 LibreSpark/LibreTV 的对照分析）

---

## 0. 决策记录

| # | 议题 | 决定 | 状态 |
|---|---|---|---|
| D1 | 是否引入 Navigation Compose | **引入** | ✅ 已确认 |
| D2 | 阶段执行顺序 | **先做设计，代码暂不动** | ✅ 已确认 |
| D3 | Retrofit / Room / Hilt | 均不引入 | 待确认 |
| D4 | 方言策略按源可配（`SourceConfig.dialect`） | 是 | 待确认 |
| D5 | 缓存不持久化播放地址 | 权威键 `sourceUrl+vodId` | 待确认 |
| D6 | 迁移期保留 v1 缓存读取（双读双写） | 保留一个版本 | 待确认 |

D1 的收益/代价分析见 `design-detail.md` §A.1。

## 1. 设计目标与约束

### 1.1 目标

| 编号 | 目标 | 可验证标志 |
|---|---|---|
| G1 | 分层依赖单向，无跨层直连 | 静态检查：UI 层不出现 `CmsClient`/`SourceRepository` 引用 |
| G2 | 全部后端动作有统一接口定义 | 新增一个采集动作只需改 1 个接口 + 1 个方言实现 |
| G3 | 失败可诊断 | 任一采集源失败时，能区分超时/HTTP 错误/协议不兼容/返回空/解析失败 |
| G4 | 业务规则可测 | 分类映射、标题归一化、排序、目标解析等规则有独立单测，且不依赖 Android |
| G5 | 单文件可控 | 单个 ViewModel ≤ 400 行；单个 UI 文件 ≤ 600 行 |
| G6 | 缓存语义明确 | 每条缓存都有 TTL、版本、源集签名；失效不需要手工清库 |
| G7 | 并发有预算 | 任何一轮扇出都有并发上限、单源超时、整体预算 |
| G8 | 可观测 | 关键链路有分级日志，可在设置页开启 |

### 1.2 约束

- 单 Gradle 模块（`app`），不拆模块，避免过度工程。
- minSdk 23 / targetSdk 34 / compileSdk 35，Kotlin 1.9.x，Compose BOM 2024.09.02。
- 不引入注解处理器（Hilt/Room/Retrofit/Moshi-kotlin）：编译期收益不足以抵消构建复杂度与体积成本。
- 不破坏 `openspec/specs/**` 已定义的任何可见行为。

---

## 2. 现状诊断

### 2.1 分级问题清单

#### P0 — 正确性与稳定性

| # | 问题 | 证据 | 影响 |
|---|---|---|---|
| P0-1 | UI 层越过 ViewModel 直连数据层 | `MainActivity.kt:174-175` 自建 `SourceRepository`/`CmsClient`，`:195` 直接 `repo.fetchDetail` | 依赖倒置；续播逻辑绕过 VM，与 VM 内状态不同步；不可测试 |
| P0-2 | 错误被大面积吞掉 | `SourceRepository.kt:74-78,158-164,250-260`、`CmsClient.kt:65-75` | 源集体失效时 UI 只显示"没有片单"；维护者无法定位 |
| P0-3 | 源健康度只用于展示，不参与决策 | `SourceHealth` 仅写入 `UiState.sourceHealth` | 已知失效的源仍在每次扇出中被请求，浪费带宽与时间 |
| P0-4 | 扇出无并发上限与整体预算 | `SourceRepository.kt:70-90`（30 源全开）、`TvViewModel.kt:603-612`（N 源全开）；单源 `callTimeout=18s` × 最多 3 次方言回退 | 最坏一轮 ≈ 54s；连接池耗尽；OOM/ANR 风险 |
| P0-5 | 缓存无 TTL、无版本、无源集签名 | `FeedCacheDb.kt` 有 `saved_at` 但从不判断；key 仅 `"TAB\|genre"` | 冷启动长期展示过期内容；源集变更只能靠手工 `feedDb.clear()` |
| P0-6 | 单体状态树 + 手动路由 | `TvViewModel.kt:64-132`（UiState 40+ 字段）、`:46`（Screen 枚举）、`:1060-1073`（手写 back） | 每加一个页面/一种返回路径，组合复杂度线性上升 |
| P0-7 | 探活语义污染上游 | `SourceRepository.kt:200,218` 用 `search(url,"test")` 判活 | 每次测速都向采集站发真实关键词搜索，污染对方日志且结果无意义 |
| P0-8 | m3u8 首包探测语义错误 | `CmsClient.kt:82-103` 对 m3u8 发 `Range: bytes=0-1023` | 测到的是 manifest 文本下载时间，不是起播时间，选源页排序失真 |

#### P1 — 可维护性

| # | 问题 | 证据 |
|---|---|---|
| P1-1 | 无统一接口层，方言硬编码在客户端 | `CmsClient.kt:107,120-124,136-148,154-164` |
| P1-2 | 业务规则藏在 ViewModel 私有方法里，不可测 | `TvViewModel.kt:183-238`（classify）、`:784-803`（targetsFor）、`:805-817`（matchesGenre）、`:822-839`（sortGroups） |
| P1-3 | 模型四重复制 | `VodItem` → `SearchResult` → `TitleGroup/SourceOffer` → `FeedCard` → `CachedCard`，靠 `FeedCacheMapping.kt` 手工映射 |
| P1-4 | Repository 无抽象、依赖经方法参数注入 | `SourceRepository` 具体类；每个方法都带 `client: CmsClient` 参数 |
| P1-5 | 本地存储键名硬编码、无 schema 版本 | `"libretv_sources"` / `"libretv_settings"` / `"libretv_watch_history"` / `"libretv_search_history"` 散落各处 |
| P1-6 | 零日志、零指标 | 全项目无 `android.util.Log` 调用 |
| P1-7 | 派生子协程不可取消、不可去重 | `TvViewModel.kt:1248,1289,1345` 的 `viewModelScope.launch` 未持有 Job 引用 |
| P1-8 | 聚合重复计算 | `TvViewModel.kt:635-637`：每收到一个源结果就全量 `aggregate(raw)` |
| P1-9 | UI 文件过大、TV 交互逻辑散落 | `MainActivity.kt` 43KB、`PlayerScreen.kt` 41KB、`SearchScreen.kt` 29KB |
| P1-10 | 播放进度双轨存储 | `WatchRecord` 同时存 `rawUrl`（易失效）与 `sourceUrl+vodId`（可重解析），未定义权威来源 |
| P1-11 | release 使用 debug 签名、`minify` 关闭 | `app/build.gradle.kts:19-24` |

#### P2 — 细节

| # | 问题 |
|---|---|
| P2-1 | `assets/douban_titles.txt` 无任何引用的死文件 |
| P2-2 | `SourceRepository.loadCategoryCatalog` 与 `allCategoryCatalogs` 功能重叠，前者只被测试引用 |
| P2-3 | 无 CI 流水线、无 lint 基线 |

### 2.2 根因归纳

所有问题可归到三条根因：

1. **缺少"接口层"与"领域层"**：数据访问协议与业务规则只能写在客户端实现类和 ViewModel 里，没有可替换、可配置、可测试的接缝。
2. **缺少"统一的结果与错误模型"**：成功是数据、失败是 `null`，导致调用方无法区分，决策（是否降权、是否重试、是否提示）无处安放。
3. **缺少"生命周期与并发治理"**：所有并发都是"发出去就等"，没有预算、没有取消、没有去重。

---

## 3. 目标架构

### 3.1 分层与依赖方向

```
┌──────────────────────────────────────────────────────────────┐
│ ui/            Compose 页面 + 每页独立 ViewModel + Navigation │
│  home/ search/ result/ picker/ detail/ player/ settings/     │
│  nav/  navigation 路由（替代 Screen 枚举）                    │
└───────────────┬──────────────────────────┬───────────────────┘
                │ 依赖                      │ 依赖
                ▼                          ▼
┌──────────────────────────────┐  ┌────────────────────────────┐
│ domain/    纯 Kotlin，无 Android│  │ core/    Result/Error/Logger│
│  model/    领域模型            │  │  dispatch/ 调度器提供        │
│  usecase/  业务规则用例        │  │  container/ AppContainer(DI) │
└───────────────┬──────────────┘  └────────────────────────────┘
                │ 依赖（接口反转，domain 不引用实现）
                ▼
┌──────────────────────────────────────────────────────────────┐
│ data/                                                              │
│  remote/  CmsApi, CmsDialect, DoubanApi, HttpEngine, ProbeEngine │
│  local/   FeedCacheDao, SourceStore, SettingsStore, HistoryStore │
│  repository/ SourceRepository, FeedRepository, DetailRepository  │
└──────────────────────────────────────────────────────────────────┘
```

**依赖规则（强制）**

| 规则 | 说明 |
|---|---|
| D1 | `ui` 只能依赖 `domain` + `core`，**不得** import `data.*` 的任何实现类 |
| D2 | `domain` 不得 import `android.*` 与 `data.*`，只依赖 `core` 与 Kotlin 标准库 |
| D3 | `data` 实现 `domain` 定义的接口（如 `SourceGateway`），依赖方向反转 |
| D4 | 跨层传递的模型必须来自 `domain.model`，不得使用远端 DTO（如 `VodItem` 仅存在于 `data.remote.dto`） |

### 3.2 包结构（目标）

```
com.libretv.tv
├── core/
│   ├── AppResult.kt          // Success / Failure 密封类
│   ├── AppError.kt           // 错误分类（网络/协议/数据/业务）
│   ├── Logger.kt             // 分级日志接口 + Android 实现 + 开关
│   └── AppContainer.kt       // 手动 DI 容器
├── domain/
│   ├── model/
│   │   ├── Source.kt         // SourceConfig, SourceEntry, SourceHealth
│   │   ├── Title.kt          // TitleGroup, SourceOffer, TitleRef
│   │   ├── Playback.kt       // PlayLine, Episode, PlayRequest, DetailBundle
│   │   ├── Feed.kt           // FeedCard, FeedBadge, FeedSnapshot, FeedSort
│   │   └── Catalog.kt        // CategoryCatalog, Genre, HomeTab
│   ├── gateway/
│   │   ├── SourceGateway.kt      // 采集源读写接口
│   │   ├── ContentGateway.kt     // 内容获取接口
│   │   └── HistoryGateway.kt     // 历史/设置接口
│   └── usecase/
│       ├── ClassifyCategories.kt
│       ├── ResolveGenreTargets.kt
│       ├── AggregateTitles.kt
│       ├── SortTitleGroups.kt
│       ├── MatchGenreKeywords.kt
│       ├── LoadFeed.kt
│       ├── SearchAllSources.kt
│       ├── FetchTitleDetail.kt
│       └── ProbeOffers.kt
├── data/
│   ├── remote/
│   │   ├── dto/  VodListDto, VodItemDto, VodClassDto, DoubanDto
│   │   ├── CmsApi.kt           // 统一采集接口
│   │   ├── CmsDialect.kt       // 方言策略（标准/宽松/自定义）
│   │   ├── DoubanApi.kt
│   │   ├── HttpEngine.kt       // OkHttp 单例 + 拦截器 + 超时策略
│   │   ├── ProbeEngine.kt      // 源健康 / 首包探测
│   │   └── ResultMapper.kt     // DTO → 领域模型 / 错误映射
│   ├── local/
│   │   ├── FeedCacheDao.kt     // SQLite，增加 ttl/version/source_signature
│   │   ├── SourceStore.kt      // SP，键名集中化
│   │   ├── SettingsStore.kt
│   │   ├── HistoryStore.kt
│   │   └── StorageKeys.kt      // 所有 SP 键名与 schema 版本常量
│   └── repository/
│       ├── SourceRepository.kt
│       ├── ContentRepository.kt
│       └── HistoryRepository.kt
└── ui/
    ├── nav/  LibreNavHost.kt, Routes.kt
    ├── home/ HomeScreen.kt, HomeViewModel.kt, HomeUiState.kt
    ├── search/ SearchScreen.kt, SearchViewModel.kt
    ├── result/ ResultScreen.kt, ResultViewModel.kt
    ├── picker/ SourcePickerScreen.kt, PickerViewModel.kt
    ├── detail/ DetailScreen.kt, DetailViewModel.kt
    ├── player/ PlayerScreen.kt, PlayerViewModel.kt
    ├── settings/ SettingsScreen.kt, SettingsViewModel.kt
    └── sources/ SourcesScreen.kt, SourcesViewModel.kt
```

### 3.3 统一接口层设计

#### 3.3.1 采集接口（`CmsApi`）

```kotlin
interface CmsApi {
    suspend fun search(base: String, keyword: String, page: Int = 1): ApiResult<List<TitleRef>>
    suspend fun categories(base: String): ApiResult<List<CategoryNode>>
    suspend fun categoryItems(base: String, typeId: Int, page: Int = 1): ApiResult<List<TitleRef>>
    suspend fun detail(base: String, id: String): ApiResult<DetailPayload>
}
```

`ApiResult` 携带**延迟**与**生效方言**，让上层可观测、可决策：

```kotlin
sealed interface ApiResult<out T> {
    data class Success<T>(
        val data: T,
        val latencyMs: Long,
        val dialect: String? = null,   // 实际生效的方言，用于回退链诊断
    ) : ApiResult<T>
    data class Failure(val error: SourceError, val latencyMs: Long = 0L) : ApiResult<Nothing>
}

sealed interface SourceError {
    data object Timeout : SourceError            // 单源超时
    data class HttpFailure(val code: Int) : SourceError
    data object Unreachable : SourceError        // DNS/连接失败
    data object Malformed : SourceError          // 解析失败/结构不符
    data object UnsupportedDialect : SourceError // 方言全部回退失败
    data object EmptyResult : SourceError        // 源返回 0 条
    data object Blocked : SourceError            // 418/403 等风控
}
```

#### 3.3.2 方言策略（`CmsDialect`）

把当前散落在 `CmsClient` 里的回退链显式化为策略对象，并允许**按源覆盖**：

```kotlin
interface CmsDialect {
    val id: String
    fun searchParams(keyword: String, page: Int): List<QueryParam>
    fun categoryParams(typeId: Int, page: Int): List<QueryParam>
    fun detailParams(id: String): List<QueryParam>
    /** 分类树候选：按顺序尝试，命中第一个非空即停。 */
    fun categoryCandidates(): List<List<QueryParam>>
}

object StandardCmsDialect : CmsDialect {          // ac=videolist / ac=list / ac=detail
object LenientCmsDialect : CmsDialect {           // 额外候选 ac=class、ac=list 无参
}
```

- 默认方言 = `StandardCmsDialect`；`SourceConfig.dialect` 可指定 `standard` / `lenient`。
- `dialect` 字段进入 `assets/sources.json` 与用户自定义源表单，形成**可观测、可解释**的回退链。

#### 3.3.3 探活与首包探测（`ProbeEngine`）

| 场景 | 现状 | 目标 |
|---|---|---|
| 源可达性 | `search(url,"test")`（污染上游） | `GET {base}?ac=list` 取分类树；成功即视为可达，同时**复用**该分类树（消除启动时重复探测） |
| 流起播延迟 | 对 m3u8 发 Range 请求（语义错） | 判定类型：HLS → 下载 manifest 并解析首个分片 URI，再对分片做 1KB Range 探测；MP4 → 现有 Range 方式 |
| 结果 | 只展示 | 写入 `SourceHealth`，并作为**扇出排序与过滤输入**（见 3.5） |

#### 3.3.4 豆瓣接口（`DoubanApi`）

保持现状形状，纳入统一 `ApiResult`：

```kotlin
interface DoubanApi {
    suspend fun recommendations(tag: String, type: String, limit: Int, start: Int): ApiResult<List<DiscoveryItem>>
}
```

`Referer` 由 `HttpEngine` 的域名策略拦截器统一注入，与 Coil 侧策略共用同一份域名规则表。

### 3.4 领域层与用例

把 ViewModel 中的纯规则抽为单参数用例，输入输出全部是 `domain.model`：

| 用例 | 输入 | 输出 | 替代的现有代码 |
|---|---|---|---|
| `ClassifyCategories` | `List<CategoryNode>` | `Map<HomeTab, List<CategoryNode>>` | `TvViewModel.classify` |
| `ResolveGenreTargets` | 目录集合 + tab + genre | `GenreTargets(命中列表, 是否本地过滤)` | `TvViewModel.targetsFor` |
| `AggregateTitles` | `List<TitleRef>` | `List<TitleGroup>` | `TitleAggregator.aggregate` |
| `SortTitleGroups` | groups + sort | 有序 groups | `TvViewModel.sortGroups` |
| `MatchGenreKeywords` | group + keywords | Boolean | `TvViewModel.matchesGenre` |
| `BuildFeedSnapshot` | 原始结果 + 历史快照 + 稳定序 | `FeedSnapshot` | `TvViewModel.buildSnapshot` + `mergeStableCards` |

全部为**纯函数**，不依赖 Android，可直接 JVM 单测。

### 3.5 并发与降级治理

```kotlin
class FanOut<T>(
    private val concurrency: Int = 6,          // 全局并发上限
    private val perItemTimeoutMs: Long = 10_000,
    private val totalTimeoutMs: Long = 15_000
) {
    suspend fun run(items: List<T>, block: suspend (T) -> ApiResult<R>): List<Pair<T, ApiResult<R>>>
}
```

规则：

1. **全局并发上限 6**（可用 `Dispatchers.IO.limitedParallelism(6)` 或 `Semaphore`），30 源不再同时打满连接。
2. **单源超时 10s**，**整体预算 15s**；超预算的剩余源直接判定 `Timeout`。
3. **超时从外到内**：外层 `withTimeout(total)` 包裹，内层单源 `withTimeout(perItem)`。
4. **健康度参与决策**：`SourceHealth` 记录最近失败次数与成功率；连续失败 ≥ N 次的源在本轮被跳过，并在源管理页标记"自动降级"。
5. **结果逐条返回**：使用 `Flow` 而非 `Channel` + 全量重算；每条到达只做**增量聚合**，避免 P1-8 的 O(n²)。

### 3.6 缓存策略

三层缓存，每层语义明确：

| 层 | 载体 | TTL | 失效条件 |
|---|---|---|---|
| L1 内存 | `FeedSnapshot` map | 会话内 | 源集签名变化 |
| L2 持久 | SQLite `feed_cache` | 默认 6 小时（`ttl_minutes` 可配） | TTL 到期 / `schema_version` 不匹配 / `source_signature` 变化 |
| L3 网络 | — | — | — |

**缓存键升级**：

```
旧： "TAB|genre"
新： "v2|TAB|genre|<sourceSignature>"
```

`sourceSignature` = 已启用源 URL 集合的哈希。源集一变，key 自然变化，旧数据自然过期，**不再需要手工 `feedDb.clear()`**。

**表结构升级**（`FeedCacheDb.onUpgrade` 保留旧数据清理逻辑）：

```sql
ALTER TABLE feed_cache ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE feed_cache ADD COLUMN ttl_minutes INTEGER NOT NULL DEFAULT 360;
ALTER TABLE feed_cache ADD COLUMN source_signature TEXT NOT NULL DEFAULT '';
```

**缓存失效判定**：`savedAt + ttl > now && version == CURRENT && sourceSignature == current`。

### 3.7 状态管理重构

#### 3.7.1 导航

- 引入 `navigation-compose`，用 `NavHost` + 路由字符串替代 `Screen` 枚举。
- 移除 `detailFrom` / `pickerFrom` / `resultFrom` 三个"来源页"字段——**返回栈由导航栈自身维护**。
- 播放参数通过 `savedStateHandle` 传递（`PlayRequest` 需可序列化或以参数形式携带）。

#### 3.7.2 ViewModel 拆分

| ViewModel | 职责 | 状态 |
|---|---|---|
| `HomeViewModel` | 首页 tab/chip、条带加载、无限滚动 | `HomeUiState` |
| `SearchViewModel` | 键盘输入、候选、搜索历史 | `SearchUiState` |
| `ResultViewModel` | 搜索结果聚合与排序 | `ResultUiState` |
| `PickerViewModel` | 选源对比、offer 探测 | `PickerUiState` |
| `DetailViewModel` | 详情、线路、选集 | `DetailUiState` |
| `PlayerViewModel` | 播放请求、进度上报 | `PlayerUiState` |
| `SettingsViewModel` / `SourcesViewModel` | 设置与源管理 | 各自 UiState |

**共享数据**（源清单、设置、历史）通过 `AppContainer` 暴露的**应用级 StateFlow** 提供，页面 VM 订阅，不再塞进单一 `UiState`。

#### 3.7.3 状态约定

- 每个页面的状态类**只含该页字段**，且字段数 ≤ 12。
- 一次性事件（错误提示、Toast）走 `Channel(BUFFERED).receiveAsFlow()`，不再占用状态字段；解决当前 `error` 字段在 `navigate()` 中被清零的隐式约定。
- 副作用入口统一命名为动词（`loadFeed` / `search` / `pickOffer` / `selectEpisode`），便于 UI 直接绑定。

### 3.8 依赖注入

不引入 Hilt，使用**手动容器**：

```kotlin
class AppContainer(context: Context) {
    val logger: Logger by lazy { AndroidLogger() }
    val httpEngine: HttpEngine by lazy { HttpEngine(logger) }
    val cmsApi: CmsApi by lazy { CmsApiImpl(httpEngine, logger) }
    val doubanApi: DoubanApi by lazy { DoubanApiImpl(httpEngine, logger) }
    val sourceStore: SourceStore by lazy { SourceStore(context, logger) }
    val feedCacheDao: FeedCacheDao by lazy { FeedCacheDao(context, logger) }
    val sourceRepository: SourceRepository by lazy { ... }
    // ...
}
```

`ViewModelProvider.Factory` 通过 `viewModelFactory { initializer { ... } }` 注入，测试时可替换为 Fake。

### 3.9 可观测性

```kotlin
interface Logger {
    fun d(tag: String, msg: String); fun i(...); fun w(tag: String, msg: String, t: Throwable? = null); fun e(...)
}
```

- 默认级别 `WARN`，设置页可调 `OFF/WARN/INFO/DEBUG`。
- 关键埋点：源请求（url 摘要、耗时、结果）、方言回退链、聚合规模（源数/条目数/分组数）、缓存命中与过期、播放解析结果、扇出预算消耗。
- 日志中不打印完整 URL 查询串中的敏感参数（当前无鉴权，但保留规范）。

### 3.10 测试策略

| 层 | 手段 | 目标 |
|---|---|---|
| 用例层 | JVM 单测（纯函数） | 分类映射、聚合、排序、目标解析、关键词匹配 —— 覆盖 P1-2 全部规则 |
| 数据层 | `MockWebServer` | 方言回退链、错误映射、超时、分页参数正确性 |
| 缓存层 | JVM 单测 + 内存 DB | TTL 过期、源签名失效、版本不匹配 |
| VM 层 | `kotlinx-coroutines-test` + `FakeCmsApi` | 首屏先渲染缓存后刷新、分页追加不跳动、错误态文案 |
| 现有 | 保留 9 个既有单测 | 作为重构不变量基线 |

依赖新增：`androidx.navigation:navigation-compose`、`com.squareup.okhttp3:mockwebserver`（test）、`kotlinx-coroutines-test`（已有）。

---

## 4. 目标数据流

```
        ┌──────────────────────────── UI (Compose) ────────────────────────────┐
        │  HomeScreen   SearchScreen   PickerScreen   DetailScreen   PlayerScreen │
        └───────┬────────────┬─────────────┬────────────┬────────────┬─────────┘
                │ StateFlow  │             │            │            │
        ┌───────▼────────────▼─────────────▼────────────▼────────────▼─────────┐
        │  ViewModel (每页一个)                                                │
        │   HomeViewModel ── LoadFeed ──┐                                     │
        │   PickerViewModel ─ ProbeOffers┤                                    │
        │   DetailViewModel ─ FetchDetail┤                                    │
        └───────────────────────────────┼─────────────────────────────────────┘
                                        │ 调用用例（纯函数）
        ┌───────────────────────────────▼─────────────────────────────────────┐
        │  domain/usecase  LoadFeed · AggregateTitles · ClassifyCategories …   │
        │  domain/gateway  SourceGateway · ContentGateway · HistoryGateway     │
        └───────┬───────────────────────────────┬─────────────────────────────┘
                │ 实现                          │ 实现
        ┌───────▼──────────┐          ┌─────────▼─────────────────────────────┐
        │ data/repository  │          │ data/local                            │
        │  ContentRepo     │◄────────►│  FeedCacheDao · SourceStore · History │
        │  SourceRepo      │          │  SettingsStore                        │
        └───────┬──────────┘          └───────────────────────────────────────┘
                │
        ┌───────▼───────────────────────┐
        │ data/remote                   │
        │  CmsApi + CmsDialect          │──HTTP──► 采集站 (Apple CMS v10)
        │  DoubanApi                    │──HTTP──► movie.douban.com
        │  HttpEngine (+拦截器/预算)     │
        │  ProbeEngine                  │──HTTP──► 播放流首片
        └───────────────────────────────┘
```

**首页数据流（重构后）**

```
打开 tab/chip
   │
   ├─► L1 内存命中 ──────────────────────► 立即渲染（不闪白）
   ├─► L2 SQLite 命中且未过期 ───────────► 渲染 + 标记"正在更新…"
   └─► 延迟 500ms ─► 网络扇出
                    ├─ SourceHealth 过滤（跳过自动降级源）
                    ├─ FanOut(concurrency=6, perItem=10s, total=15s)
                    ├─ 逐条增量聚合（AggregateTitles）
                    ├─ 首次填满 24 张即发布一次
                    ├─ 全部完成后发布最终快照
                    └─ 写回 L2（带 ttl/version/sourceSignature）
```

**播放数据流（重构后）**

```
点击卡片
   ├─ 单源 ──────────────────────────────► 详情页
   └─ 多源 ─► 选源页（并发 ProbeOffers：分类树/详情/首片）──► 选源 ─► 详情页
详情页 ─► FetchTitleDetail ─► DetailBundle(线路/选集) ─► 选集 ─► 播放页
播放页 ─► PlayerViewModel 持有 PlayRequest
        ├─ ExoPlayer + HlsMediaSource + 广告过滤（现有 M3u8AdFilter 不变）
        └─ 进度：每 5s 上报 HistoryGateway.upsert(权威键 = sourceUrl+vodId)
```

---

## 5. 迁移路线

| 阶段 | 内容 | 产出 | 风险 |
|---|---|---|---|
| **P0 阶段：止血** | ① 移除 `MainActivity` 对数据层的直接依赖（续播逻辑下沉到 VM）② 引入 `Logger` 与分级开关 ③ 探活改为 `ac=list` 并复用分类树 ④ 扇出加并发上限与整体预算 | 行为不变，可观测性提升 | 低 |
| **P1 阶段：接口层** | 提取 `CmsApi` / `CmsDialect` / `ApiResult` / `SourceError`；`CmsClient` 降级为 `CmsApiImpl`；`DoubanClient` → `DoubanApiImpl` | 统一接口与错误模型 | 中（需保证回退链行为等价） |
| **P2 阶段：领域层** | 抽出 `domain/model` 与 `domain/usecase`；删除 ViewModel 中的 `classify/targetsFor/matchesGenre/sortGroups`；补齐用例单测 | 业务规则可测 | 中 |
| **P3 阶段：状态与导航** | 引入 Navigation Compose；按页面拆分 ViewModel；拆分 `UiState`；事件改用 Channel | 单文件瘦身 | 高（返回路径回归风险最高） |
| **P4 阶段：缓存与存储** | 缓存表加 version/ttl/source_signature；缓存键升级；SP 键名集中 + schema 版本 + 迁移 | 缓存语义明确 | 中 |
| **P5 阶段：清理** | 删除死文件与死代码（`douban_titles.txt`、`loadCategoryCatalog`）；release 签名与 lint 配置 | 技术债清理 | 低 |

**阶段准入规则**：每个阶段结束后，`openspec/specs/**` 12 份规格的场景全部仍然成立，且既有 9 个单测全绿。

---

## 6. 取舍与不做的事

| 议题 | 决定 | 理由 |
|---|---|---|
| 是否引入 Retrofit | **不引入** | 采集接口只有 4 个动作且方言高度定制，注解式收益低；手写 `CmsApi` + `CmsDialect` 更贴合"方言可配置"的目标 |
| 是否引入 Room | **不引入** | 仅 1 张表、1 行 JSON payload，`SQLiteOpenHelper` 足够 |
| 是否引入 Hilt | **不引入** | 手动 `AppContainer` 足以表达依赖，且便于测试替换 |
| 是否引入 Navigation Compose | **引入** | 手工 `Screen` 枚举 + 三个 `from` 字段是 P0-6 的直接成因 |
| 缓存是否持久化播放地址 | **不持久化** | 播放地址会过期；权威键为 `sourceUrl+vodId`，恢复时重新解析 |
| 是否保留 M3u8 广告过滤实现 | **保留，仅迁移位置** | 已有专门单测覆盖，行为不变 |

---

## 7. 风险登记

| 风险 | 影响 | 缓解 |
|---|---|---|
| 导航重构改变返回路径 | 高 | 以 `tv-navigation` / `app-shell` 规格的场景为验收清单，逐条手工回归 |
| 方言策略重写引入行为差异 | 高 | 用 `MockWebServer` 对旧实现与新实现做同请求断言比对 |
| 缓存键升级导致首屏空窗 | 中 | 保留 v1 读取路径一个版本，双读双写后下线 |
| 拆分 VM 后出现状态不一致 | 中 | 每个页面 VM 的状态以 spec 场景为单位补测 |
| 并发上限导致结果变少 | 中 | 整体预算 15s 远大于原最坏路径的可用时间，且失败源被健康度跳过，可观测验证 |
