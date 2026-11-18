# 存储与缓存设计：Room + Stale-While-Revalidate

> 本文回答两个问题：① 是否引入 Room ② 「本地先展示 + 后台刷新」怎么落地。
> 关联：[`design.md`](./design.md)、[`content-catalog.md`](./content-catalog.md)、[`../architecture-refactor/design-detail.md`](../architecture-refactor/design-detail.md) §D、`../architecture-refactor/benchmark.md`（参考项目对照）、`../feature-parity/`（订阅/跳过标记/导出导入）。
> 本阶段只做设计，不改动 `app/src/**`。

---

## 1. 结论

| 问题 | 结论 |
|---|---|
| 是否引入 Room | **引入**，但延后到 P4 阶段，且必须与「缓存语义层」一起落地 |
| 是否把历史/收藏/云端请求缓存都落盘先展示 | **是**，采用 Stale-While-Revalidate，但必须显式区分 Fresh / Stale / Expired 三种语义 |
| 是否引入 WorkManager | **仅用于订阅同步等必须保证的持久任务**，其余后台刷新走应用内协程 |

### 1.1 为什么原决策 D3「不引入 Room」需要翻转

原决策的理由是：**「1 张表 1 行 payload」**。这个前提在扩大存储范围后已经不成立：

| 目标实体 | 表数 | 真实查询需求 | SP+JSON 能否满足 |
|---|---|---|---|
| 观影记录 | 1 | 按 `titleKey` 聚合多源同片、按 `updatedAt` 排序、限制条数、自动清理已看完 | 勉强（整表读改写，40 条尚可，几百条开始抖） |
| 收藏记录 | 1 | 按 `titleKey` 唯一性校验、收藏时间排序、是否已收藏 | 否（要维护第二份 JSON 数组 + 索引） |
| 云端请求缓存 | 1（多 kind） | 按 kind + key 读、TTL 判定、版本/源签名失效、按 kind 批量清理 | 否（要维护按 kind 分桶的多份 JSON） |
| 源健康度 | 1 | 按 url 读、按 tier/延迟排序、批量 upsert | 否 |
| 订阅配置 | 1 | 列表、按刷新周期分组、失败计数 | 否 |
| 跳过标记 | 1 | 按归一化片名读 | 否 |
| 搜索历史 | 1 | 列表 + 上限 | 勉强 |
| 设置 | 1 | 单行键值 | 可以（保留 SP） |

合计 **7–8 张表 + 跨表关系**（收藏 ↔ 历史 ↔ 缓存 ↔ 源），外加：
- **可靠迁移**：现状 `FeedCacheDb.onUpgrade` 是 `DROP TABLE`（`FeedCacheDb.kt:100-103`）—— 只要给缓存加一列，用户全部缓存就没了。这是真 bug，不是洁癖问题。
- **响应式**：`Flow<List<Favorite>>` 直接驱动 UI；SP 方案要自己维护 `MutableStateFlow` + 手工 `commit`，扩展到 8 张表后必然出现"某处漏了刷新 UI"。
- **事务**：批量写缓存/健康度时，SP 无事务，逐条 `apply()` 可能部分失败导致状态不一致。

### 1.2 Room 的成本（诚实列出）

| 成本 | 评估 | 缓解 |
|---|---|---|
| 需要 KSP 注解处理器 | 主要顾虑：构建变慢、Kotlin/KSP 版本耦合 | 当前 Compose compiler `1.5.14` ⇒ Kotlin 1.9.x，配 `KSP 1.9.24-1.0.20` + `Room 2.6.1`；`minSdk 23` 满足 Room 2.6.x 要求 |
| 实体与领域模型不能共用 | 需要一层映射 | 持久化实体只存"绘制最小子集"，与 §B 的领域模型分工明确；映射用 `kotlinx.serialization` 而非手写 |
| DAO 样板代码 | 新增约 300–400 行 | 可接受，且换来迁移/查询/事务 |

### 1.3 什么情况下**不**该引入（否决条件）

若最终确定以下任一项，维持手写 `SQLiteOpenHelper` 即可，不引 KSP：

- 项目明确「不做收藏功能」，且云端缓存只保留首页 feed 一张表；
- 团队不接受 KSP；
- 决定不做后台刷新（缓存只做冷启动首屏加速）。

一旦「收藏 + 多类缓存 + 后台刷新」三者中有两项以上成立，**建议引入**。

---

## 2. 分层隔离：Room 不污染 domain

领域层规则 D2（`domain/**` 不出现 `import android.`）必须保持。Room 的注解是 data 层的事，因此**接口在 domain，实现在 data**：

```
domain/gateway/                     （纯 Kotlin，无 Android 依赖）
  WatchHistoryGateway.kt     interface { fun observe(): Flow<List<WatchRecord>>; suspend fun upsert(...); ... }
  FavoriteGateway.kt
  CacheGateway.kt             interface { suspend fun <T> read(kind, key): CacheEntry<T>?; suspend fun write(...); suspend fun invalidate(prefix) }
  SourceHealthGateway.kt
  SubscriptionGateway.kt
        ▲ 实现
        │
data/local/room/                   （Room 注解只出现在这里）
  LibreTvDatabase.kt
  entity/WatchRecordEntity.kt  favorite/HttpCacheEntity/SourceHealthEntity/...
  dao/WatchRecordDao.kt  FavoriteDao.kt  HttpCacheDao.kt  ...
  RoomWatchHistoryGateway.kt  ← 实现 domain/gateway 的接口
```

- **好处**：domain 保持纯 Kotlin，可用 JVM 单测（用 Fake 实现）；Room 只在 data 层；P2 的领域层单测不受影响。
- **注意**：Room 的 `@Entity` 与 domain model **不是同一个类**，映射规则写在 `data/local/room/` 内的 mapper 中，且用 `kotlinx.serialization` 做 JSON 中转（与 §B 的"缓存是领域模型的绘制最小子集"一致）。

### 2.1 Room 实体 ↔ domain model 的分工

| 场景 | 用什么 |
|---|---|
| 缓存（http_cache） | **序列化后的领域模型 JSON**（payload 列），读取时反序列化为 domain model |
| 观影记录 / 收藏 / 源健康 | **结构化列**（列即字段），因为需要按列查询与排序 |

即：**要查询的用列，要整块取用的用 JSON**。这是判断"是否该拆表"的通用准则。

---

## 3. Room Schema 草案

```kotlin
@Entity(tableName = "watch_record")
data class WatchRecordEntity(
    @PrimaryKey val key: String,              // "$sourceName|$vodId|$lineIndex|$episodeIndex"
    val titleKey: String,                     // 归一化片名，用于跨源聚合
    val title: String,
    val poster: String,
    val sourceName: String,
    val sourceUrl: String,
    val vodId: String,
    val episodeName: String,
    val lineIndex: Int,
    val episodeIndex: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
)
```

```kotlin
@Entity(tableName = "favorite")
data class FavoriteEntity(
    @PrimaryKey val titleKey: String,         // 归一化片名 → 全局唯一
    val title: String,
    val poster: String,
    val year: String,
    val area: String,
    val typeName: String,
    val sourceUrl: String,                    // 首选播放源（可空：用户只收藏不指定源）
    val vodId: String,
    val createdAt: Long
)
```

```kotlin
@Entity(tableName = "http_cache",
        indices = [Index(value = ["kind", "cacheKey"], unique = true),
                   Index(value = ["kind", "savedAt"])])
data class HttpCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,                         // feed / category / detail / search / douban / probe
    val cacheKey: String,                     // v2|TAB|genre|<signature> 等
    val payload: String,                      // JSON
    val savedAt: Long,
    val expiresAt: Long,                      // 软 TTL
    val hardExpiresAt: Long,                  // 硬过期（仍可兜底展示）
    val version: Int,
    val sourceSignature: String,
    val sizeBytes: Int
)
```

```kotlin
@Entity(tableName = "source_health")
data class SourceHealthEntity(
    @PrimaryKey val sourceUrl: String,
    val state: String,                        // HEALTHY / COOLDOWN / SUSPENDED
    val failureTier: Int,
    val cooldownUntil: Long,
    val consecutiveFailures: Int,
    val totalOk: Int,
    val totalFail: Int,
    val lastLatencyMs: Long,
    val lastError: String?,
    val lastCheckedAt: Long
)
```

其余三表（`subscription`、`skip_mark`、`search_history`）结构简单，从略。

**索引要点**：`http_cache` 的 `(kind, cacheKey)` 唯一索引支撑"按 kind + key 读"；`(kind, savedAt)` 支撑"按 kind 清理过期"。没有这两个索引，清理会全表扫。

---

## 4. 版本与迁移

| 版本 | 内容 | 迁移 |
|---|---|---|
| v1 | 现状 `feed_cache` 表（3 列） | `Migration(1, 2)`：新建 7 张表 → 逐行读 v1 payload → 写 `http_cache`（`version=2`、`kind='feed'`）→ 重建索引 → 删除 v1 表 |
| v2 | 本设计全部表 | 后续加列一律用 `Migration`，**禁止 `fallbackToDestructiveMigration`** |
| v3+ | 预留 | — |

**三条硬规则**

1. **禁止破坏性迁移**。现状的 `DROP TABLE` 必须去掉 —— 缓存丢一次可以忍，用户的观影记录和收藏丢了不能忍。
2. **迁移必须可测**。引入 `room.schemaLocation` ksp 参数导出 schema JSON，用 `MigrationTestHelper` 写迁移测试（可放到 P5 的 CI 里）。
3. **SP 数据也要迁移**：现有 4 个 SP 文件里的历史/搜索历史在首次启动时导入对应表，导入成功后写 `StorageKeys.MIGRATED_TO_DB = true`，**导入后不删除 SP 原件**（保留一个版本，出问题可回退，参见 D6）。

---

## 5. 缓存语义：Fresh / Stale / Expired

这是「本地先展示 + 后台刷新」最容易做错的地方。若只返回"数据"，UI 无法区分「这是最新的」和「这是 3 小时前的」，就会出现**闪屏**（先空后有）或**骨架屏闪烁**，直接违反 `content-discovery` 规格的「卡片不跳动」。

```kotlin
sealed interface CacheResult<out T> {
    /** 网络或进程内缓存直接命中 */
    data class Fresh<T>(val value: T, val origin: CacheOrigin) : CacheResult<T>

    /** 命中本地缓存，后台正在刷新 */
    data class Stale<T>(val value: T, val refresh: Deferred<T?>) : CacheResult<T>

    /** 超过硬过期：仍展示，但提示"内容可能已过期" */
    data class Expired<T>(val value: T, val ageMs: Long) : CacheResult<T>
}

enum class CacheOrigin { NETWORK, DISK, MEMORY }
```

### 5.1 UI 契约（必须写进规格）

| 结果 | UI 行为 | 禁止 |
|---|---|---|
| `Fresh` | 正常展示 | — |
| `Stale` | **立即展示本地内容，不显示 loading 骨架**；后台完成后静默替换 | ❌ 不得先清空再填充 |
| `Expired` | 展示本地内容 + 顶部提示"内容可能已过期" | ❌ 不得隐藏内容 |
| 全部失败且无缓存 | 空态 + 错误分类文案 | — |

### 5.2 静默替换的防抖规则

后台刷新完成后**不能无条件重建列表**，否则用户正在看的卡片会跳动。规则：

1. 计算新旧内容的**内容指纹**（如 `卡片 key 列表 + 各卡片关键字段的 hash`）；
2. 指纹相同 → **完全不动 UI**；
3. 指纹不同 → 只替换变化的部分，且**保持滚动锚点**（记住当前可见卡片的 key，替换后滚回该 key）。

> 这条直接对应 `content-discovery` 规格里"卡片不跳动"的场景，必须有单测覆盖。

---

## 6. 缓存分级与 TTL

| 数据 | kind | 软 TTL | 硬过期 | 后台刷新时机 |
|---|---|---|---|---|
| 源清单（订阅同步结果） | `source_list` | 24h | 7d | WorkManager 每日 / 手动 |
| 分类树（`ac=list`） | `category` | 12h | 48h | 启动后空闲时 |
| 首页 feed 卡片 | `feed` | 6h | 24h | 首屏渲染后 |
| 详情（线路/剧集） | `detail` | 6h | 24h | 进入详情页时 |
| 搜索结果 | `search` | 30min | 2h | 不后台（用户主动） |
| 选源探测结果 | `probe` | 10min | 1h | 不后台 |
| 豆瓣推荐 | `douban` | 6h | 24h | 首屏后空闲时 |
| 播放地址 | — | **不落盘** | — | — |

> 播放地址不落盘的理由：地址有有效期且绑定源健康状态，缓存下来只会带来"点了播不出来"。这与 D5「缓存不持久化播放地址」一致。

---

## 7. 失效矩阵（最容易被漏掉的一环）

缓存不只按 TTL 过期，还有**依赖失效**。必须显式建模：

| 触发事件 | 受影响缓存 |
|---|---|
| 源集变化（增/删/启停） | 全部 `feed` / `category` / `detail`（`sourceSignature` 变化自动失效） |
| 某源被删除 | 该源相关的 `detail` / `probe` |
| 某源健康度进入 COOLDOWN | 该源的 `probe` 与 `detail`（选源页排序会变） |
| 订阅同步完成 | 该订阅名下所有源相关的缓存 |
| 分类树更新 | 该分类下的 `feed` |
| 应用升级（缓存模型 version 变化） | 全部 `http_cache` 行 |

实现方式：

- **签名型失效**：`sourceSignature` 参与缓存键与有效性判定（现有设计已有，保留）；
- **前缀型失效**：`CacheGateway.invalidate(kind, keyPrefix)`，例如 `invalidate(kind="detail", prefix="$sourceUrl|")`；
- **版本型失效**：`version` 不匹配即过期。

三者缺一不可：只做 TTL 会漏掉"源刚删掉但缓存还在展示"；只做签名会漏掉"分类内容变了"。

---

## 8. 后台刷新调度

### 8.1 职责切分

| 任务 | 机制 | 理由 |
|---|---|---|
| 首屏后的 feed / douban / category 刷新 | **应用内协程**（`ProcessLifecycleOwner` + 首屏渲染完成后触发） | TV 应用不常驻，WorkManager 在这里没有价值，还增加延迟 |
| 选源探测、详情刷新 | 应用内协程（用户触发） | 同上 |
| **订阅源同步** | **WorkManager**（约束 `NetworkType.CONNECTED`，`setBackoffCriteria` 指数退避） | 用户明确配置的持久行为，必须保证即使应用被杀掉也能执行 |
| 源健康巡检 | 应用内协程（启动后空闲时） | TV 场景不需要持久巡检 |
| 缓存清理（过期行） | 应用内协程 + 每次启动时一次 | 行数有限，不必上 WorkManager |

### 8.2 应用内后台刷新规范

```kotlin
// 触发时机：首屏数据已渲染 + 焦点空闲
class BackgroundRefresher(
    private val scope: CoroutineScope,          // SupervisorJob + Dispatchers.IO
    private val clock: Clock
) {
    fun scheduleRefresh(job: suspend () -> Unit, delayMs: Long = 0)
}
```

规则：
1. 同一 `kind` 的后台刷新**同一时刻只允许一个**（按 kind 去重，避免切页签时堆叠 5 个刷新）；
2. 刷新失败**不降级缓存**（保留旧内容，只更新健康度）；
3. 刷新成功后写缓存 + 触发失效矩阵中的级联失效；
4. 刷新任务不阻塞 UI 线程，不影响首屏出画时间。

---

## 9. 观影记录：从 SP 到表

| 项 | 现状 | 目标 |
|---|---|---|
| 存储 | SP JSON 数组，≤40 条 | `watch_record` 表 |
| 写入时机 | 每 12 tick（约 5s）写一次 | 改为**节流写**：`positionMs` 变化 > 5s 或退出播放时写；避免高频写盘 |
| 键 | `sourceName|vodId|lineIndex|episodeIndex` | 保留为 `key`，另加 `titleKey` 用于跨源聚合 |
| 上限 | 40 条 | 保留条数上限（如 200）+ 按 `updatedAt` 淘汰 + 已看完的记录可选择清理 |
| 查询 | 全量反序列化 | `Flow<List<WatchRecordEntity>> ORDER BY updatedAt DESC LIMIT 200` |
| 迁移 | — | 首次启动从 SP 导入（见 §4 规则 3） |

**新增能力（SP 做不到）**：跨源聚合"同一部剧在 3 个源都看过"的观看进度 —— 这正是 `titleKey` 的价值。

---

## 10. 收藏记录（新增功能）

### 10.1 定位

收藏是**用户主动表达的兴趣**，与"看过"（系统记录的行为）语义不同，两者独立存储、独立页面。

| 维度 | 观影记录 | 收藏 |
|---|---|---|
| 触发 | 播放自动写入 | 用户点击收藏按钮 |
| 主键 | 播放副本（`sourceUrl+vodId`） | **影片**（`titleKey`） |
| 生命周期 | 按 `updatedAt` 淘汰 | 永久保留，直到用户删除 |
| 排序 | 最近观看 | 收藏时间（可再按片名） |

### 10.2 关键规则

1. **主键是 `titleKey`（归一化片名）**，不是 `sourceUrl+vodId` —— 否则同一影片在 5 个源会收藏出 5 条。归一化规则必须与聚合层 `AggregateTitles` 完全一致（同一函数，避免两处实现漂移）。
2. **收藏不强制指定源**：`sourceUrl`/`vodId` 可空，只收藏"想看"；点击播放时走选源流程当场选源。
3. **收藏 → 播放的入口**：从收藏页点击 → 若记录了 `sourceUrl` 直接进详情，否则进选源页。
4. **与历史页分离**：首页增加两个入口（"继续观看" / "我的收藏"），互不影响。
5. **导出包含收藏**（见 `../feature-parity/` 的备份包设计）。

### 10.3 收藏页 UI 要点（TV 端）

- 网格展示海报 + 标题 + 收藏时间；
- 焦点移动时**不预加载详情**（TV 端带宽紧张），进入详情才解析；
- 长按/菜单键提供"取消收藏"。

---

## 11. 离线行为

TV 场景断网常见。要求：

| 场景 | 行为 |
|---|---|
| 冷启动无网络 | 展示上次缓存的首页内容 + 顶部"离线，显示上次内容"提示 |
| 收藏/历史页无网络 | **完全可用**（本地数据） |
| 详情无网络 | 若有缓存则展示缓存（Expired 语义），否则提示离线 |
| 播放 | 明确失败提示，提供"手动选源" |

---

## 11.5 与内容目录的关系

本文的 `http_cache` 表负责**卡片快绘**（首页首屏不等待网络），而**内容元数据目录**（标题、封面、线路、集名、源清单）由独立的一套表负责 —— 见 [`content-catalog.md`](./content-catalog.md)。

| 数据 | 载体 | 寿命 |
|---|---|---|
| 首页卡片绘制子集 | `http_cache`（kind=`feed`） | TTL 6h，随源签名失效 |
| 内容元数据目录 | `catalog_title` / `catalog_offer` / `catalog_episode` / `catalog_alias` | 长期，靠增量采集保鲜 |
| 播放地址 | **两者都不存** | 播放时实时解析 |

两者共享 Room 实例，但**生命周期与失效逻辑不同**，不要合并成一张表。

---

## 12. 验收清单

- [ ] `domain/**` 中不出现 `import androidx.room.`（Room 只在 data 层）
- [ ] `domain/**` 中不出现 `import android.`（规则 D2 保持）
- [ ] 迁移测试：`MigrationTestHelper` 覆盖 1→2 迁移，历史与收藏零丢失
- [ ] 缓存读取区分 Fresh / Stale / Expired，Stale **不显示骨架屏**
- [ ] 后台刷新内容指纹相同 → UI 完全不变（单测）
- [ ] 源集变化 → 所有 feed 缓存失效（单测）
- [ ] 过期缓存行在启动时被清理
- [ ] 断网冷启动仍能展示首页缓存、收藏、历史
- [ ] 观影记录节流写入：5s 内的进度抖动不落盘
- [ ] 禁止 `fallbackToDestructiveMigration`（代码检查）
- [ ] 播放地址不落盘（代码检查：http_cache 无 play 类 kind）

---

## 13. 风险

| 风险 | 缓解 |
|---|---|
| KSP 与 Kotlin 版本不匹配导致构建失败 | 先用一个最小 PR 验证 KSP + Room 可编译，再开始实体开发 |
| 缓存表无限增长 | 按 `kind + savedAt` 索引，启动时清理过期行；单表行数上限（如 2000） |
| 后台刷新把首屏拖慢 | 后台刷新在首屏渲染完成后才启动，且与首屏请求共享 FanOut 预算 |
| Room 实体与 domain model 漂移 | 缓存类实体用 JSON 序列化，domain 模型有单测锁定序列化契约 |
| 迁移丢数据 | 迁移前自动备份 SP 原件一个版本；迁移测试纳入 CI |
