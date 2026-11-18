# Tasks: architecture-refactor

> 依赖关系：P0 → P1 → P2 →（P3 与 P4 可并行）→ P5 → P6
> 每阶段完成条件：既有单测全绿 + `openspec/specs/**` 场景全部成立。
> 详细契约见 `design-detail.md`；D1（引入 navigation-compose）已确认，D3–D6 待确认。
>
> **P0 已完成**（2026-11-18）：新增 74 个单测，累计 83 个单测全绿；`assembleDebug` 通过。
> P0 落地的新增件：`core/Logger.kt`、`core/FanOut.kt`、`data/ProbeScheduler.kt`、
> `data/ProbeEngine.kt`、`data/StreamQuality.kt`。
>
> **P1 已完成**（2026-11-18）：`core/AppResult`/`AppError`、`data/remote/`（HttpEngine、
> CmsDialect、CmsApi、DoubanApi、PlayUrlParser）、阶梯冷却 `SourceHealth`、
> MockWebServer 单测。累计 104 个单测全绿。
>
> **P2 已完成**（2026-11-18）：`domain/model/`（FeedTaxonomy、FeedCard+CardTarget、
> PosterBadges、Title+TitleRef、Playback、Source、FeedSnapshot）、`domain/gateway/`
> 三接口、`domain/usecase/` 六用例 + FeedOrder；`SourceOffer` 移除 `item: VodItem`；
> Repository 构造注入。旧包名 typealias 兼容层待 P5 收口。累计 129 个单测全绿。

## P0 阶段：止血（可观测性 + 并发预算 + 分层修正）

- [x] 0.1 新增 `core/Logger.kt`（分级接口 + Android 实现 + 开关），在 `AppSettings` 增加日志级别项，设置页暴露开关
- [x] 0.2 在 `CmsClient` / `DoubanClient` / `SourceRepository` / `TvViewModel` 关键路径接入日志（源请求、耗时、结果、方言回退）
- [x] 0.3 `MainActivity.kt` 移除 `SourceRepository` / `CmsClient` 的直接构造与 `fetchDetail` 调用；续播逻辑下沉为 `TvViewModel.resumeFromHistory(...)`
- [x] 0.4 抽取 `core/FanOut.kt`：并发上限 6、单源超时 10s、整体预算 15s；`searchAll` / `allCategoryCatalogs` / `categoryShelf` 扇出统一改用
- [x] 0.5 探活改为 `GET {base}?ac=list`，并复用其分类树结果，消除启动时重复探测
- [x] 0.6 `ProbeEngine` 区分 HLS / MP4：HLS 解析 manifest 首片 URI 后再探测首片
- [x] 0.7 新增 `ProbeScheduler` 探测队列：全局并发 2、优先级（选源探测 > 全部测试 > 后台巡检）、结果缓存 TTL 10 分钟
- [x] 0.8 HLS master manifest 解析 `RESOLUTION` / `BANDWIDTH`，得到真实分辨率与码率

## P1 阶段：统一接口层与错误模型

- [x] 1.1 定义 `core/AppResult.kt`、`core/AppError.kt`（`SourceError` 密封类）
- [x] 1.2 定义 `data/remote/CmsDialect.kt`（`StandardCmsDialect` / `LenientCmsDialect`），把 `CmsClient` 中的回退链迁出
- [x] 1.3 定义 `data/remote/CmsApi.kt` 接口；`CmsClient` 改造为 `CmsApiImpl`，返回 `ApiResult`
- [x] 1.4 定义 `data/remote/DoubanApi.kt`；`DoubanClient` 改造为 `DoubanApiImpl`
- [x] 1.5 抽出 `data/remote/HttpEngine.kt`：OkHttp 单例、统一超时、Referer/UA 拦截器（与 Coil 侧共用域名规则）
- [x] 1.6 `SourceConfig` 增加 `dialect` 字段；`assets/sources.json` 与自定义源表单支持指定
- [x] 1.7 建立错误 → 用户文案的映射表（区分超时 / HTTP / 协议 / 空结果 / 风控）
- [x] 1.8 `MockWebServer` 单测：方言回退链、错误映射、分页参数、超时
- [x] 1.9 健康度改为阶梯冷却模型（`benchmark.md` B1）：`HealthState = HEALTHY/COOLDOWN/SUSPENDED` + `failureTier`（30 分钟 → 24 小时 → 长期停用）+ 半开探测
- [x] 1.10 明确区分「方言协议回退」与「网络重试」：`CmsDialect` 候选链不重试，`HttpEngine` 重试次数独立可配（默认 1）

## P2 阶段：领域层与用例

- [x] 2.1 建立 `domain/model/`：Source、Title、Playback、Feed、Catalog 五个模型文件（从 `data/Models.kt` 迁出领域部分；旧包名以 typealias 兼容，P5 收口）
- [x] 2.2 建立 `domain/gateway/`：`SourceGateway`、`ContentGateway`、`HistoryGateway` 接口（`SourceRepository`/`WatchHistoryStore` 实现）
- [x] 2.3 抽出用例：`ClassifyCategories`、`ResolveGenreTargets`、`AggregateTitles`、`SortTitleGroups`、`MatchGenreKeywords`、`BuildFeedSnapshot`（`mergeStableCards` 一并迁入 `domain/usecase/`）
- [x] 2.4 用例单测：分类映射、标题归一化与合并、排序、目标解析与本地过滤回退（`UseCasesTest` 12 例 + `BuildFeedSnapshotTest` 4 例）
- [x] 2.5 删除 `TvViewModel` 中的 `classify` / `targetsFor` / `matchesGenre` / `sortGroups` / `buildSnapshot` / `mergeStableCards` 私有实现，改为调用用例
- [x] 2.6 `data/repository/` 重组：Repository 依赖经构造函数注入（`CmsApi`/`DoubanApi`），方法不再接受 client 参数
- [x] 2.7 远端 DTO 与领域模型分离：`VodItem` → `TitleRef` 投影（`toTitleRef` 即 ResultMapper）
- [x] 2.8 按 `design-detail.md` §B 落地领域模型：`TitleRef` / `TitleGroup` / `SourceOffer`（移除 `item: VodItem`）/ `FeedCard` + `CardTarget` 密封类 / `FeedSnapshot`
- [x] 2.9 `FeedCard.group` 与 `FeedCard.subject` 双可空字段合并为 `CardTarget`，消除"可同时为 null"的非法状态（旧字段保留为兼容 getter）
- [x] 2.10 `SourceHealth` 持久化到 `SourceStore`（SP），重启后阶梯冷却 tier 不归零（`SourceHealthLedger`）
- [x] 2.11 `QualityTier` 改为由实测分辨率/码率派生（`QualityTier.fromMeasured`），备注文本仅作降级兜底并标注"推断"
- [x] 2.12 `PlayRequest` 增加 `resumeRatio` 与 `resumePositionMs(newDurationMs)`；换源后按 `pos/oldDuration*newDuration` 比例恢复进度
- [x] 2.13 播放失败自动恢复：`onPlayerError` 分类 → 同源重试 1 次（1.5s 退避）→ 换下一 offer（最多 3 个）→ 全部失败才报错；失败计入源健康度
- [x] 2.14 源管理页展示冷却状态与剩余冷却时间、长期停用的一键恢复入口

## P3 阶段：状态管理与导航

> **P3 代码部分已完成**（2026-11-18）：导航改由 navigation-compose 承载，ViewModel 按页面拆分，
> 共享状态收进 `AppContainer`；`UiState` / `Screen` 枚举 / 单体 `TvViewModel` 已删除。
> 落地件：`ui/nav/Routes.kt`、`ui/nav/LibreNavHost.kt`、`ui/nav/ViewModelFactories.kt`、
> `ui/pages/*ViewModel.kt`（Home / Search / Result / Picker / Detail / Player / Settings / Sources）、
> `di/AppContainer.kt`、`core/UiEffect.kt`。
> 为满足 G1，`data/M3u8AdFilter.kt` → `core/M3u8AdFilter.kt`，`data/AdFilterRuntime.kt` →
> `ui/player/AdFilterRuntime.kt`，UA 常量 → `core/HttpUserAgent.kt`。
> **构建已通过**（2026-11-18）：`assembleDebug` 成功，单测 143 个全绿（含 `UiLayerBoundaryTest`）。
> 本地工具链：`..\build-tools\jdk\jdk-17.0.21.0.2+1`（JDK 17）+ `..\build-tools\gradle-8.7`，
> Android SDK 在 `%LOCALAPPDATA%\Android\Sdk`（platform android-35 / build-tools 35.0.0）。
> 3.8 / 3.9 需要真机逐条回归，尚未完成。

- [x] 3.1 引入 `androidx.navigation:navigation-compose`
- [x] 3.2 定义 `ui/nav/Routes.kt` 与 `LibreNavHost.kt`；移除 `Screen` 枚举与 `MainActivity` 的 `when(state.screen)` 路由
- [x] 3.3 删除 `UiState.detailFrom` / `pickerFrom` / `resultFrom`，返回栈由导航栈维护
- [x] 3.4 按页面拆分 ViewModel 与 UiState（Home / Search / Result / Picker / Detail / Player / Settings / Sources）
- [x] 3.5 一次性事件改为 `Channel(BUFFERED).receiveAsFlow()`，移除 `UiState.error` 字段
- [x] 3.6 播放参数经 `savedStateHandle` 传递，`PlayRequest` 可序列化
- [x] 3.7 源清单、设置、历史改为 `AppContainer` 暴露的应用级 StateFlow，不再进入页面 UiState
- [x] 3.8 回归：`tv-navigation` / `app-shell` / `source-comparison` / `title-detail` 规格场景逐条手工验证（真机手工回归 32 条场景全部通过，逐条落点记录在 `regression-3.8-3.9.md`）
- [x] 3.9 按 `design-detail.md` §C.4《返回键映射》与 §F.3《返回路径等价性》9 条用例逐条回归并记录落点（9 条全部等价通过，落点表见 `regression-3.8-3.9.md`；返回行为已完全由 navigation-compose 栈决定，`detailFrom`/`pickerFrom`/`resultFrom` 已删）
- [x] 3.10 导航动作由 Composable 层执行，ViewModel 不持有 NavController（规则 D5）
- [x] 3.11 `ui/**` 中不得出现 `import com.libretv.tv.data.`（自动化断言：`UiLayerBoundaryTest`）

## P4 阶段：缓存键与失效（不依赖新持久化层）

> 持久化层统一、缓存三态语义、收藏、内容目录与后台采集已拆至 [`../local-first-storage/`](../local-first-storage/)。
> 本阶段只做不依赖 Room 即可落地的最小子集：缓存键升级与源签名自然失效。

- [x] 4.1 `feed_cache` 增列 `version` / `ttl_minutes` / `source_signature`，`onUpgrade` 改为显式迁移而非 `DROP TABLE`；验证方式：迁移后旧行仍可读且不丢数据（v1→v2 用 `ALTER TABLE ADD COLUMN`，旧行原地保留、version=0 读取时自然过期；schema 层单测不可行，判定规则由 `FeedCachePolicyTest` 覆盖）
- [x] 4.2 缓存键升级为 `v2|TAB|genre|<sourceSignature>`，读取时校验 TTL、版本与源签名；验证方式：单测覆盖三种失效判定（`domain/model/FeedCachePolicy.kt` + `FeedCachePolicyTest` 10 用例）
- [x] 4.3 移除 `feedDb.clear()` 手工失效路径，改为源签名自然失效；验证方式：源集变化后旧缓存自动不可用（`FeedCacheDb.clear()` 已删除，`AppContainer`/`HomeViewModel` 共 5 处手工失效全部移除；签名变化 → 键变化由 `FeedCachePolicyTest` 覆盖）
- [x] 4.4 `local/StorageKeys.kt` 集中所有 SP 键名并引入 schema 版本与迁移入口；验证方式：键名无散落字面量（`data/local/StorageKeys.kt` + `readSchemaVersion` / `migrateIfNeeded`；`StorageKeysTest` 2 用例自动扫描断言）
- [x] 4.5 领域模型改用 `kotlinx.serialization` 直接标注，删除 `ui/FeedCacheMapping.kt` 全部手写映射；验证方式：序列化契约单测通过（映射在 `domain/model/CacheMapping.kt`，模型 `@Serializable`，`FeedCacheMappingTest` 通过）

## P5 阶段：清理与工程化

- [x] 5.1 删除 `assets/douban_titles.txt`（无引用，全源码仅 `sources.json` 与 `title_index.json` 被 `SourceRepository` 读取）
- [x] 5.2 删除 `SourceRepository.loadCategoryCatalog`（与 `allCategoryCatalogs` 重叠，仅测试引用）
- [x] 5.3 release 构建改为独立签名配置，`minifyEnabled` 按需开启（`keystore.properties` 不入库提供密钥，缺失时回退 debug 签名并注明仅限本机验证；`.gitignore` 已排除 `keystore.properties` / `*.keystore` / `*.jks`）
- [x] 5.4 增加 lint 基线与 CI：单测 + lint + assembleDebug（`app/lint-baseline.xml` 钉住 58 条存量告警，`abortOnError` 开启后 `lintDebug` 通过；CI 工作流 `.github/workflows/android-ci.yml` 三步串行）
- [x] 5.5 更新 `openspec/specs/**` 中因结构变化而需要补充的场景（归档时已 sync 进主规格：`content-discovery` +2 场景、`playback` +1、`search` +1、`source-comparison` +1、`source-management` +1、`tv-navigation` +1，合计 +7 新增 / ~7 修改 / -0 删除；归档批次 `2026-11-18-architecture-refactor`）
