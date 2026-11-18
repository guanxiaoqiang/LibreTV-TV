# Change: architecture-refactor

## Why

现有实现功能可用，但架构约束已经失效：UI 层越过 ViewModel 直连数据层、网络层没有统一接口抽象、业务规则不可测试、错误被吞成空集合、单文件膨胀到 1455 行。继续按当前结构加功能（直播、投屏、多源切换续播）会让每一次改动都要触碰 `TvViewModel.kt` 与 `MainActivity.kt`，回归风险与协作成本同步上升。

## What Changes

- **引入统一接口层**：定义 `CmsApi` / `CmsDialect` / `DoubanApi`，把 `ac=` 参数与各采集站方言从客户端实现中抽出，改为可配置策略。
- **引入统一错误模型**：`SourceError` 密封类型 + `ApiResult<T>`，替代当前"返回 null / 空集合"的吞错策略，并让源健康度参与排序与过滤决策，而不只是展示。
- **引入领域层与用例层**：把 `classify` / `targetsFor` / `matchesGenre` / `sortGroups` / `aggregate` 等业务规则从 ViewModel 私有方法中抽出为纯函数用例，使其可单元测试。
- **收敛状态管理**：用 Navigation Compose 替代 `Screen` 枚举 + `detailFrom/pickerFrom/resultFrom` 手动返回栈；按页面拆分 ViewModel，拆分 `UiState`。
- **修正分层方向**：UI 只依赖 ViewModel；数据访问全部收敛到 ViewModel / 用例；引入 `AppContainer` 作为手动 DI 容器。
- **治理并发与缓存**：扇出请求增加全局并发上限、单源超时与整体预算；缓存增加 TTL、缓存版本与源集签名（source signature），以自然过期替代手工 `clear()`。
- **补齐可观测性**：引入分级 `Logger`，关键链路（源健康、聚合、缓存命中、播放解析）可追踪。
- **补齐测试**：为用例层、缓存策略、扇出策略补充单测；引入 `FakeCmsApi` 用于 ViewModel 测试。

## Impact

- Affected specs: `tv-navigation`（返回落点由导航栈决定）、`source-management`（健康状态机、探活方式、缓存自然失效）、`source-comparison`（清晰度实测派生、探测队列、健康状态参与排序）、`playback`（失败自动恢复、换源保留进度）、`content-discovery`（清晰度实测派生、错误分类提示）、`search`（扇出超时预算与降级）
- 其余规格（`app-shell` / `title-detail` / `watch-history` / `settings` 等）以现有内容为回归基线，本变更不修改其要求
- Affected code:
  - `app/src/main/java/com/libretv/tv/data/**`（重组为 remote / local / repository）
  - `app/src/main/java/com/libretv/tv/ui/TvViewModel.kt`（拆分并瘦身）
  - `app/src/main/java/com/libretv/tv/MainActivity.kt`（移除对数据层的直接依赖）
  - `app/build.gradle.kts`（新增 navigation-compose、mockwebserver 等测试依赖）
- 行为基线：以 `openspec/specs/**` 现有 12 份规格为回归基线；除上述 delta 列出的行为变化外，重构期间不得改变既有可见行为。

## Out of Scope

- 不引入 Hilt/Dagger/Retrofit（保持零注解处理器与低构建复杂度，收益不足）
- 持久化层统一、缓存语义、收藏、内容目录与后台采集已拆至 [`../local-first-storage/`](../local-first-storage/)，本变更只提供其依赖的领域层契约
- 不做直播（Live）能力、投屏、DLNA 等新功能
- 不改动采集源清单 `assets/sources.json` 的内容与生成方式（仅新增可选的 `dialect` 字段）
- 不做 UI 视觉改版与主题重设计
- 不改动广告过滤（M3u8）算法的行为，仅调整其所在层级

## 决策记录

| # | 议题 | 决定 | 状态 |
|---|---|---|---|
| D1 | 引入 Navigation Compose | 是 | ✅ 已确认 |
| D2 | 本变更仅产出设计，暂不改代码 | 是 | ✅ 已确认 |
| D3 | 引入 Retrofit | 否 | 待确认 |
| D3b | 引入 Hilt | 否 | 待确认 |
| D3c | 引入 Room（持久化层统一） | **是**（已拆至 `../local-first-storage/`） | 见该变更决策记录 |
| D10–D17 | 缓存语义、收藏、内容目录、后台采集、搜索本地化 | 已拆至 `../local-first-storage/` | 见该变更决策记录 |
| D4 | 方言策略按源可配 | 是 | 待确认 |
| D5 | 缓存持久化播放地址 | 否 | 待确认 |
| D6 | 迁移期保留 v1 缓存读取 | 是（一个版本） | 待确认 |
| D7 | 源健康采用阶梯冷却（30 分钟 → 24 小时 → 长期停用）并持久化 | 是 | 待确认（据 `benchmark.md` B1） |
| D8 | 画质等级改由 manifest 实测分辨率/码率派生 | 是 | 待确认（据 `benchmark.md` B3） |
| D9 | 功能类补齐（订阅源、跳过标记、导出导入、TVBOX 导入）单开变更 | 是 | 已拆分至 `../feature-parity/` |

## 参考项目对照

见 [`benchmark.md`](./benchmark.md)：与 [LibreSpark/LibreTV](https://github.com/LibreSpark/LibreTV) 的逐项对照，识别出 5 项需修正现有设计、4 项建议单开变更的功能、4 项明确不做。

## 存储与缓存、内容目录

已拆分为独立变更 [`../local-first-storage/`](../local-first-storage/)：Room 引入论证、8 张表 schema、Stale-While-Revalidate 缓存语义、失效矩阵、后台刷新调度、观影记录与收藏、内容目录与后台增量采集、搜索本地索引优先。

本变更保留的缓存相关内容仅限于「首页卡片缓存的键升级与源签名自然失效」这一最小子集，用于在持久化层落地前先行消除手工 `clear()`。
