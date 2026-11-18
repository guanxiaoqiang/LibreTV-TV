# 集成回归矩阵（任务 8.1）

本文件逐条记录七份规格的全部场景在本变更中的**代码落点**，作为 code review
与回归的对照表。场景来自 `specs/*/spec.md`。

## local-storage（统一持久化）

| 场景 | 落点 |
|---|---|
| 播放地址不落库 | `data/local/room/Entities.kt` 全部实体无播放地址列；`CatalogHarvester` 只写标题与来源投影 |
| 结构化数据可查询 | `catalog_*`/`watch_record`/`source_health` 用列 + 索引；DAO 按字段查询 |
| 陈旧内容直接展示 | `CacheResult.Stale` + `RoomCacheGateway.read()`；UI 不先清空（沿用 FeedCache 语义） |
| 已过期内容仍可展示 | `CacheResult.Expired`，保留 payload + ageMs |
| 无缓存且请求失败 | `CacheResult.Missing` + 既有错误文案路径 |
| 来源集合变化失效 | `CacheInvalidation.Event.SourceSetChanged` + `RoomCacheGateway.invalidateByVersion/invalidate` |
| 缓存模型版本升级 | `CacheInvalidation.Event.ModelUpgraded` |
| 单个来源删除 | `CacheInvalidation.Event.SourceRemoved`（只失效 detail/probe） |
| 分类内容更新 | `CacheInvalidation.Event.CategoryRefreshed`（前缀失效） |
| 后台刷新让路 | `HarvestQuota.userActive` + `BackgroundRefresher.userActive` |
| 刷新失败不降级旧内容 | `BackgroundRefresher` onFailure 只上报；payload 保留 |
| 升级后历史/收藏完整 | `LegacyMigrator.migrateIfNeeded()` 回填 SP + feed_cache.db，原件保留 |
| 结构变化不丢数据 | `LibreTvDatabase.MIGRATION_1_2` 显式迁移，禁破坏性迁移（`DomainPurityTest`） |
| 过期数据清理 | `RoomCacheGateway.prune()` deleteExpired |
| 缓存总量上限 | `RoomCacheGateway.prune()` trimTo(maxRows) |

## content-catalog（内容目录）

| 场景 | 落点 |
|---|---|
| 选集不依赖网络 | `RoomCatalogGateway.episodes()` 本地读；`AppContainer.localCatalog()` |
| 目录中无播放地址 | `CatalogEpisodeEntity` 只有 episodeName |
| 陈旧内容提示 / 采集状态可见 | `isFresh()` + 采集 `HarvestResult` + 缓存三态 |
| 跳过有效条目 | `HarvestPolicy.shouldSkip()` |
| 下一集优先 | `CatalogScheduler`（P0 > P1 > P2 > P3） |
| 采集失败计数降级 | `HarvestHealth` + `CatalogHarvester.recordFailure()` |
| 断网冷启动 | `NetworkMonitor.online` + HomeLayout「离线·本地内容」提示 |
| 断网播放失败 | PlayerScreen 错误态 + 手动选源入口（S5 既有） |
| 本地命中 / 冷启动回退 / 结果补全 | `LocalFirstSearch.search()` / `appendBackfill()` |

## favorites（收藏）

| 场景 | 落点 |
|---|---|
| 跨来源收藏去重 | `normalizeTitle` 唯一键；`FavoriteDao.@PrimaryKey(titleKey)` |
| 未绑定来源的收藏 | `FavoriteEntry.sourceUrl` 可空；收藏页 onOpen 走搜索选源 |
| 看完不删除收藏 / 清理历史不影响收藏 | 收藏与历史独立 Store；`clearCacheOnly` 只删 http_cache |
| 首页入口 / 列表展示 / 焦点不预加载 | `HomeLayout` rail「收藏」+ `FavoritesScreen`（海报/标题/时间；点击才请求） |
| 取消收藏即时同步 | `AppContainer.removeFavorite` + `favoriteEntries` StateFlow；详情页响应式 |

## watch-history（观看历史）

| 场景 | 落点 |
|---|---|
| 写入一条历史 / 进度抖动不落库 / 退出补写 | `ProgressThrottle` + `PlayerViewModel.saveProgress(force)` + `PlayerScreen.onProgressFinal` |
| 缺少影片 ID 的键 | `WatchKeys.episodeKey` 退化用「标题+剧集名+地址」 |
| 已看完归零 | `WatchHistoryStore.upsert` 的 `isFinished()` 分支 |
| 超出上限 / 倒序 | `WatchHistoryStore.retainNewest`（上限 200） |
| 首页历史展示（进度%、删单条、清空） | 既有 `HomeLayout` HISTORY tab（未改动） |
| 历史实时同步 | `HistoryGateway.records` StateFlow |
| 同片多源聚合 / 不合并剧集 | `WatchHistoryGateway.aggregateByTitle()` 默认方法 |

## search（搜索）

| 场景 | 落点 |
|---|---|
| 多源扇出 / 部分源失败 / 耗时记录 | 既有 `SourceRepository.searchAll`（未改动） |
| 本地索引优先 / 无命中回退 / 异步补全 | `LocalFirstSearch` + `RoomCatalogGateway.search` |
| 结果折叠 / 打开卡片 | 既有 `TitleAggregator` + `ResultScreen`（未改动） |

## content-discovery（首页发现）

| 场景 | 落点 |
|---|---|
| 快速首屏 / 缓存载荷精简 / 陈旧与过期展示 | 既有 `FeedCacheDb` + `CachedFeed`（精简载荷）沿用，缓存三态由本变更统一 |
| 来源变化导致缓存失效 | `CacheInvalidation.SourceSetChanged` |
| 后台刷新不跳动（内容无变化 / 局部变化） | 既有 `FeedSnapshot` 标识比较逻辑（architecture-refactor 已实现），本变更沿用 |

## settings（设置）

| 场景 | 落点 |
|---|---|
| 收藏数量与入口 | `SettingsScreen`「我的收藏」区 |
| 后台采集开关 / 强度 | `AppSettings.harvestEnabled/harvestIntensity` + `SettingsScreen` + `HarvestQuota.harvestEnabled` |
| 本地占用展示 / 清理缓存 | `SettingsViewModel.storageUsage/clearCache` + `RoomCacheGateway.clearAll/prune` |

---

## 回归执行记录

- `./gradlew :app:testDebugUnitTest`：全量 JVM 单测（含本变更新增 6 组测试文件）通过。
- `./gradlew :app:compileDebugAndroidTestKotlin`：迁移 / 清理的 instrumented 测试可编译（需真机运行）。
- `./gradlew :app:lintDebug` + `:app:assembleRelease`：通过（见最终验证）。
- 8.2 冷启动真机计时与录屏：需在真机执行，代码层已保证「有缓存时先出缓存、后台再刷新、标识一致不重建」（沿用既有 FeedCache 语义 + 本变更缓存三态）。
