# 内容目录与后台采集：分层采纳方案

> 议题：是否把内容全量缓存进数据库，由后台采集器持续刷新资源。
> 结论：**分层采纳** —— 落库"可查询的内容元数据目录"，后台做"增量 + 按需"采集；**不做**全量常驻媒体库，**不落**播放地址。
> 关联：[`storage-and-cache.md`](./storage-and-cache.md)（持久化层与缓存语义）、[`design.md`](./design.md)、[`../architecture-refactor/benchmark.md`](../architecture-refactor/benchmark.md)（参考项目把采集放在服务端）。

---

## 0. 先厘清现状

| 维度 | 现状 | 本方案目标 |
|---|---|---|
| 缓存了什么 | `feed_cache` 表：首页各 tab 的**卡片绘制子集**（标题/封面/角标/源摘要），**不含**线路与剧集 | 增加**内容目录表**：元数据 + 集名 + 源清单，**不含**播放地址 |
| 什么时候有 | **用户访问过该 tab 之后**才有 | 首页 tab 的目录在后台预采集，用户打开即有 |
| 详情/选源 | **每次联网**解析 | 目录命中本地，只在播放地址上联网 |
| 搜索 | **每次扇出 30 源**（15s 预算内尽力而为） | **本地索引**命中，扇出降级为"后台补采" |
| 采集触发 | 用户触发 | 用户触发 **+** 后台增量采集 |

---

## 1. 为什么不能全盘采纳"本地媒体库"

参考项目 LibreTV 敢于全量采集，是因为它的采集在**服务端**：常驻进程、共享带宽、统一出口、有存储配额。**端侧没有这四个条件**，这是全部差异的根源。

| 约束 | 具体量级 | 后果 |
|---|---|---|
| **存储** | 一个完整影视站通常几万条标题、每条几十集；30 源 × 分类 × 分页 × 集名 ≈ **数百 MB 至 GB** | TV 设备存储有限；且集名/元数据变化率低，存全量收益递减 |
| **带宽** | 全量采集一轮：30 源 × 数十个分类 × 多页 ≈ **几十万次请求**、数 GB 流量 | 移动网络不可接受；即使 Wi-Fi 也是长时间占带宽 |
| **耗时** | 一轮全量采集按当前单源延迟估算 ≈ **数十分钟到数小时** | 采集完成前目录不完整，用户看到的是"半成品目录" |
| **上游封禁** | 后台持续爬取比用户触发的请求**更像爬虫**；公共免费站风控阈值低 | 源被封 → 所有用户受影响（对比：现在是用户触发、频率与真人接近） |
| **僵尸采集** | 用户不看的片被无限采集 | 纯浪费上游额度与设备资源 |
| **播放地址必失效** | CMS 播放地址普遍有有效期（数小时~数天），且与源健康状态绑定 | 存下来 = "点了播不出来"，比不存更糟 |

> 参考项目自己也踩过这条线，所以它有 `SEARCH_MAX_PAGES=5` 的分页上限、60s 的推荐接口缓存、以及"源失效靠健康检测停用" —— 它从不做全量无界采集。

---

## 2. 分层采纳方案

### 2.1 落库边界：元数据入库，地址不入库

| 数据 | 是否落库 | 理由 |
|---|---|---|
| 归一化片名、标题、别名 | ✅ | 搜索索引的基础 |
| 封面、年份、地区、类型 | ✅ | 变化率极低，一次采集长期有效 |
| 源清单（哪些源有这个片） | ✅ | 选源页直接读本地，不用逐源解析详情 |
| 线路名、集名、集数 | ✅ | 选集页直接读本地；集名**不含地址** |
| 质量、延迟、源健康度 | ✅ | 已有健康度模型，扩展为目录维度 |
| **播放地址** | ❌ | 有有效期；播放时才解析 |

> 这与 D5「缓存不持久化播放地址」一致。播放地址是**一次会话内**的东西，不是目录的一部分。

### 2.2 新增表结构

```kotlin
@Entity(tableName = "catalog_title", indices = [Index("titleKey"), Index("updatedAt")])
data class CatalogTitleEntity(
    @PrimaryKey val titleKey: String,        // 归一化片名，全局唯一
    val title: String,
    val aliases: String,                      // 逗号分隔的别名/译名
    val poster: String,
    val year: String,
    val area: String,
    val typeName: String,
    val doubanId: String?,                    // 豆瓣关联（可选，用于评分/条带）
    val offerCount: Int,
    val updatedAt: Long                       // ← 新鲜度锚点
)

@Entity(tableName = "catalog_offer",
        indices = [Index("titleKey"), Index(value = ["sourceUrl", "vodId"], unique = true)])
data class CatalogOfferEntity(
    @PrimaryKey val id: Long = 0,
    val titleKey: String,
    val sourceUrl: String,
    val sourceName: String,
    val vodId: String,
    val quality: String,                      // 由实测分辨率派生（benchmark B3）
    val remark: String,
    val latencyMs: Long,
    val lineCount: Int,
    val updatedAt: Long
)

@Entity(tableName = "catalog_episode",
        indices = [Index("offerId")])
data class CatalogEpisodeEntity(
    @PrimaryKey val id: Long = 0,
    val offerId: Long,                        // → catalog_offer.id
    val lineIndex: Int,
    val episodeIndex: Int,
    val episodeName: String,                  // 仅名称，无地址
    val updatedAt: Long
)

@Entity(tableName = "catalog_alias", indices = [Index("alias")])
data class CatalogAliasEntity(
    @PrimaryKey val alias: String,            // 小写去空格的归一化别名
    val titleKey: String
)
```

**关键点**：`catalog_episode` 只存**集名**。选集页读它即可（选集是高频、无需联网的操作），播放时才用 `sourceUrl + vodId + lineIndex + episodeIndex` 去解析地址 —— 这与 §D.6「观看历史权威键」完全一致。

### 2.3 采集调度：按优先级 + 增量 + 配额

| 优先级 | 任务 | 触发 | 说明 |
|---|---|---|---|
| **P0** | 当前播放的下一集信息 | 播放中 / 本集播完 | 用户马上要用，延迟不可接受 |
| **P1** | 首页各 tab 的目录 | 缓存 TTL 到期（6h）/ 源集变化 / 应用启动后空闲 | 保证首页内容新鲜 |
| **P2** | 搜索命中但未点开的标题详情 | 搜索完成后后台补 | 提升"点开不用等"的比例 |
| **P3** | 源健康巡检 | 定期 | 已有健康度模型（§E.2） |

**增量原则**（避免重复劳动）

1. 优先采集**上次失败**、**已过期**、**有新集**的条目；
2. 每条记录带 `updatedAt`，同一 `titleKey` 在 TTL 内不重复采集；
3. 若上游支持 `ETag` / `Last-Modified`，用条件请求，避免重复下载全量 JSON；
4. 采集只取**需要的页**：详情页只抓该片的线路与集名，不遍历该源全部分类。

**配额隔离**（关键工程约束）

| 维度 | 用户触发 | 后台采集 |
|---|---|---|
| 并发 | FanOut 并发 6、单源 10s、整体 15s | 独立配额：并发 1–2，每源每小时 ≤ N 次请求 |
| 预算 | 15s 硬上限 | 无实时预算，但有**小时级速率上限** |
| 优先级 | 最高 | 最低，**必须让路**给用户触发 |
| 失败 | 计入源健康度 | 同样计入（后台也可能是源真的挂了） |

> 后台采集**绝不能**占用前台的 FanOut 预算。否则后台一跑，用户搜索就变慢 —— 这是"采集与使用冲突"的具体形态。

**执行机制**

- **WorkManager 周期任务**：`NetworkType.CONNECTED`、指数退避、`setRequiresDeviceIdle`（TV 上耗电约束较弱，可放宽）；
- **应用内协程**：首屏渲染完成后 + 焦点空闲时触发 P1/P2；
- **不做常驻前台服务**：TV 上常驻前台服务耗电且用户反感。

### 2.4 搜索改造：本地索引优先

这是本方案**收益最大**的一处。

| | 现状 | 目标 |
|---|---|---|
| 流程 | 输入关键词 → 扇出 30 源 → 15s 预算内聚合 → 返回 | 输入关键词 → **本地索引查**（毫秒级）→ 后台补采缺失源 |
| 结果完整性 | 受最慢源拖累，部分源超时则结果不全 | 本地结果**立即完整**（历史积累），后台持续补全 |
| 上游压力 | 每次搜索打 30 源 | 只打**本地没有的**部分，且异步 |
| 冷启动首次搜索 | 全量扇出 | 走原有扇出路径（作为回退），同时把关键词写入"待补采"队列 |

**实现**：`catalog_alias.alias` 建索引（FTS 可选，中文分词收益有限，普通 LIKE + 索引足够），查询 `alias LIKE ?` 或前缀匹配。

**注意**：`search` 规格的 8 个场景需重新定义"结果来源"——这属于**可见行为变化**，必须在 spec 里显式写明，不能悄悄改。

### 2.5 新鲜度可见

每条目录数据带 `updatedAt`。UI 规则：

| 场景 | 表现 |
|---|---|
| 采集正常 | 不打扰用户 |
| 数据超过 24h 未刷新 | 源管理页显示"目录已 N 小时未更新" |
| 后台采集中 | 角标小圆点（不遮挡内容） |
| 采集中且该片数据陈旧 | 详情页提示"正在更新，稍后可能有新集" |

> 参考项目把"源健康"做成可见的（`health` 面板），我们同理 —— **不可见的陈旧数据比没有数据更危险**，用户会以为"没更新集"。

---

## 3. 与现有设计的关系

| 现有设计 | 变化 |
|---|---|
| `http_cache` 表（`feed` kind） | **保留**，继续做首页卡片快绘；详情/选源数据改从 `catalog_*` 表读 |
| `FanOut` | 使用场景从"用户实时搜索"扩展为"后台采集"，**新增独立配额** |
| `SourceHealth` | 增加"目录新鲜度"维度（每源最后采集时间与产出条数） |
| `WatchHistory` 权威键 `sourceUrl+vodId` | **不变**，播放地址仍不落库 |
| `search` 规格（8 场景） | 需重写"结果来源"语义（本地优先 + 后台补全） |
| `content-discovery` 规格 | 首页渲染路径改为"目录表 → 卡片"，需回归"卡片不跳动" |

---

## 4. 收益与代价总账

| | 全量本地媒体库 | 分层增量目录（本方案） |
|---|---|---|
| 存储占用 | 数百 MB ~ GB | 数十 MB |
| 首轮采集耗时 | 数十分钟~小时 | 数分钟 |
| 上游封禁风险 | 高 | 低（请求量可控、频率接近真人） |
| 搜索响应 | 毫秒级 | 毫秒级（同等） |
| 详情打开速度 | 瞬时（地址可能已失效） | 瞬时（线路集名）+ 播放时一次地址解析 |
| 播放成功率 | **可能下降**（用了过期地址） | 不变（地址实时解析） |
| 僵尸采集 | 有 | 无（按优先级 + TTL 收敛） |

**结论**：分层方案拿到了全量方案 90% 的收益（搜索与首屏速度），却没有它的存储、带宽、封禁与僵尸采集代价，且**播放成功率更高**。

---

## 5. 决策点

| # | 议题 | 建议 | 状态 |
|---|---|---|---|
| D13 | 引入内容目录（catalog）分层存储 | 是 | 待确认 |
| D14 | 搜索改为本地索引优先 + 后台补采 | 是 | 待确认 |
| D15 | 播放地址不入目录 | 是（与 D5 一致） | 待确认 |
| D16 | 后台采集使用 WorkManager 周期任务 + 独立配额 | 是 | 待确认 |
| D17 | `search` / `content-discovery` 规格按"本地优先"重写 | 是 | 待确认 |

> **D14 是唯一有可见行为变化的决策**：搜索结果的来源与完整性语义会变。需在 spec 中显式定义，并确认可接受。

---

## 6. 实施顺序建议

| 阶段 | 内容 | 依赖 |
|---|---|---|
| C1 | 建 `catalog_*` 表 + DAO + `CatalogRepository`（domain 纯接口） | Room 落地（`storage-and-cache.md` §3） |
| C2 | 采集器：P1 任务（首页 tab 目录）+ 增量 + 配额 | C1 |
| C3 | 详情/选源改为读目录（保留联网回退） | C2 |
| C4 | 本地索引 + 搜索改造 | C2 |
| C5 | P0/P2/P3 任务 + 新鲜度可见 | C2 |

> C1–C3 的收益最直接（详情与选源不再每次联网）；C4 收益最大但改动最可见；建议 C4 单独验收。

---

## 7. 采集调度实现落点（任务 5.8）

本文前面是设计；下表把优先级、配额与让路规则映射到本变更的代码落点，便于 code review 逐条确认设计未漂移：

| 设计项 | 本文位置 | 代码落点 |
|---|---|---|
| 目录不含播放地址（D5） | §2 | `data/local/room/Entities.kt` 的 `catalog_*` 表无地址列；`CatalogHarvester.store()` 只写标题与来源投影 |
| P0→P1→P2→P3 优先级 | §2.3 | `core/CatalogScheduler.kt`：`PriorityQueue` 按 `CatalogPriority.rank` 出队，同级 FIFO |
| 增量采集（有效期跳过 + 分页上限） | §2.2 | `core/HarvestPolicy.shouldSkip()/pagesToFetch()`；`CatalogHarvester.harvestCategory()` |
| 并发 1–2 + 每源每小时上限 + 用户请求让路（D7） | §2.3 / §5 | `core/HarvestQuota.kt`：`canAcquire()` 三条件；`BackgroundRefresher.userActive` 让路 |
| 采集失败阶梯降级 | §5.7 | `domain/model/HarvestHealth.kt`：优先级惩罚 + 连续失败 3 次暂停采集；`CatalogHarvester.recordFailure()` |
| 新鲜度可见（更新时间 + 超期提示 + 采集中角标） | §3 | `RoomCatalogGateway.isFresh()` + 采集器 `HarvestResult`；UI 用缓存三态展示角标 |
| 离线优先读目录 | §4 | `AppContainer.localCatalog()`：离线时展示已缓存来源/集名，播放地址仍需联网解析 |

让路规则细节：后台采集与用户搜索共用 `HarvestQuota.userActive` 闸门——用户请求进行中，`canAcquire()` 返回 false，后台任务暂停占用新额度；用户请求结束后恢复。这与 `BackgroundRefresher` 的 `userActive()` 让路语义一致，避免后台采集抢占 TV 端有限的带宽。
