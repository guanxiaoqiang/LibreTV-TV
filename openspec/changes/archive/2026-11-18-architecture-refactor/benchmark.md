# 参考项目对照分析：LibreSpark/LibreTV

> 对照对象：<https://github.com/LibreSpark/LibreTV>（Next.js 15 / TypeScript / 服务端聚合代理 / 25.2k Stars）
> 对照目的：找出本项目概要设计中**尚未覆盖但值得吸收**的能力。
> 结论分三类：① 修正现有设计 ② 值得单开变更的功能 ③ 明确不做的。

---

## 0. 定位差异（决定了借鉴边界）

| 维度 | LibreTV（参考） | LibreTV-TV（本项目） |
|---|---|---|
| 形态 | Web 服务端聚合 + 代理转发 | Android TV 端侧**直连**采集站 |
| 鉴权 | `PASSWORD` + Cookie 会话 + IP 限速 | 无鉴权（公开采集站） |
| 网络拓扑 | 服务端统一出口，可共享缓存/探测 | 每个用户的设备各自直连 30 源 |
| SSRF 风险 | 存在，故有 `ssrf.ts` / `host-gate.ts` | 端侧直连，无此风险 |
| 本地存储 | IndexedDB | SharedPreferences + SQLite |
| 部署 | Docker / GHCR / standalone | APK |
| 直播 | M3U + XMLTV EPG + HTTP-FLV 代理 | 无 |
| 播放器 | ArtPlayer + hls.js | Media3 ExoPlayer + HlsMediaSource |

**直接结论**：参考项目的**服务端能力**（鉴权、代理、SSRF、公开粘贴发布、直播转发）对我们**不适用**；它的**领域能力**（源健康分级、探测调度、画质实测、播放恢复、订阅同步、跳过标记）对 TV 端**高度适用**。

---

## 1. 修正现有设计的 5 项（对应概要设计的具体改动）

### B1 源健康：从二元状态升级为**阶梯冷却** ★★★

- **参考实现**：连续失败的源按阶梯时长自动停用 —— `30 分钟 → 24 小时 → 长期停用`；停用源可手动恢复。
- **我们当前设计**（`design-detail.md` §E.2）：`HEALTHY / DEGRADED / DOWN` + DOWN 状态 30 分钟后半开探测。
- **差距**：二元模型只有"停 30 分钟"和"永久停"两档；且健康度**未持久化**，重启即丢失，坏源每次启动都重新踩一遍。
- **改进设计**：

```kotlin
enum class HealthState { HEALTHY, COOLDOWN, SUSPENDED }

data class SourceHealth(
    val sourceUrl: String,
    val state: HealthState,
    val failureTier: Int,            // 0 正常 / 1 停 30 分钟 / 2 停 24 小时 / 3 长期停用
    val cooldownUntil: Long,         // 冷却到期时间戳
    val consecutiveFailures: Int,
    val totalOk: Int, val totalFail: Int,
    val lastLatencyMs: Long,
    val lastError: SourceError?,
    val lastCheckedAt: Long
)
```

| 场景 | 行为 |
|---|---|
| 请求成功 | `failureTier = 0`，`state = HEALTHY` |
| 连续失败 1 次 | `failureTier = 1`，冷却 30 分钟 |
| 冷却期内再失败 | `failureTier = 2`，冷却 24 小时 |
| 24 小时冷却期内再失败 | `failureTier = 3`，`SUSPENDED`（长期停用，需手动恢复） |
| 冷却到期 | 进入"半开"：允许 1 次探测请求，成功则复位，失败则 tier+1 |
| 手动恢复 | 强制 `failureTier = 0` 并立即探测 |

- **必须持久化**：写入 `SourceStore`（SP），否则重启后 tier 归零，阶梯冷却形同虚设。
- **落点**：`design-detail.md` §E.2 替换；`tasks.md` 新增 P1/P2 阶段任务。

### B2 探测调度：从"页内并发"升级为**全局探测队列** ★★

- **参考实现**：`quality-probe-queue.ts` —— 探测任务队列，控制并发与调度。
- **我们当前设计**：选源页对前 8 个 offer 直接并发探测，与首页扇出、播放流探测**互不知情**，会互相抢带宽。
- **改进设计**：

```kotlin
object ProbeScheduler {
    fun enqueue(sourceUrl: String, kind: ProbeKind, priority: Int, block: suspend () -> ProbeResult): Deferred<ProbeResult>
}
```

- 全局并发 **2**（探测是辅助任务，不能与播放抢带宽）；
- 优先级：用户主动触发的选源探测 > 源管理页"全部测试" > 后台健康巡检；
- **结果缓存**（对应参考项目 `video-cache.ts`）：`(sourceUrl, kind)` → 结果 + 时间戳，TTL 10 分钟，避免重复探测。

### B3 画质判定：从"备注文本推断"升级为**manifest 实测** ★★★

- **参考实现**：`codec-parse.ts` 解析编码/分辨率，`quality.ts` 定义质量等级并单独测试。
- **我们当前状况**：`QualityTier` 来自源备注文本（"高清"/"超清"/"蓝光"），**跨源不可比**——A 源写"超清"实际 480p、B 源写"高清"实际 1080p，导致选源页"最佳画质"排序失真。
- **改进设计**：

| 流类型 | 探测方式 | 得到的真实指标 |
|---|---|---|
| HLS master | 解析 manifest 的 `RESOLUTION` 与 `BANDWIDTH` | 宽×高、码率 |
| HLS media | 无 master 时的单码率 | 码率 |
| MP4 | `Range: bytes=0-1023` + `Content-Length` | 分辨率（需容器解析）、文件大小 |

- 画质等级改为**由实测分辨率/码率派生**（`QualityTier.fromResolution(width, height)`），备注文本仅在探测失败时**降级兜底**，并标注"推断"。
- 该能力同时修复了 P0-8（m3u8 首包探测语义错误）——两件事共用同一次 manifest 解析。

### B4 播放失败**自动换源恢复** ★★★

- **参考实现**：`playback-recovery.ts` —— 播放失败后的切换/恢复策略。
- **我们当前状况**：`PlayerScreen.kt:183-185` 仅 `errorText = "播放失败：${error.errorCodeName}"`，用户需手动退回选源页重选。
- **改进设计**：

```
ExoPlayer onPlayerError
   ├─ 非网络类错误（如解码不支持）→ 直接报错，不重试
   ├─ 网络/超时类错误 → 进入 Recovery：
   │    ├─ 1. 同一源重试一次（指数退避 1.5s）
   │    ├─ 2. 换下一个 offer（保留剧集与进度，见 B5）
   │    ├─ 3. 最多尝试 N=3 个 offer
   │    └─ 全部失败 → 展示"所有源均不可用"并提供"手动选源"入口
   └─ 记录该源健康度失败（接入 B1 阶梯冷却）
```

- 与 B1 联动：一次播放失败即计入该源 `consecutiveFailures`，坏源会自动降权，用户体验从"每次都踩"变为"自动绕开"。

### B5 换源时**保留剧集与播放位置** ★★

- **参考实现**："切换播放源时尽量保留当前剧集和播放位置"。
- **我们当前状况**：`PlayRequest(sourceUrl, vodId, lineIndex, episodeIndex)` **不含进度**；换源需回详情页重选集，进度归零。
- **改进设计**：
  - `PlayRequest` 增加 `resumePositionMs: Long`；
  - 选源页进入时由 `PlayerViewModel` 传入当前进度；
  - 新源解析成功后 `seekTo(resumePositionMs)`（按比例映射更稳妥：`pos / oldDuration * newDuration`）。

### B6 聚合参数对齐参考项目的量级 ★

参考项目环境变量给出了成熟量级，可直接作为我们的默认值依据：

| 参考项目变量 | 默认 | 我们的对应 | 建议 |
|---|---|---|---|
| `SEARCH_SOURCE_TIMEOUT_MS` | 10000 | `FanOut.perItemTimeoutMs` | 保持 10s ✓ |
| `REQUEST_TIMEOUT` | 8000 | `HttpEngine` 基础超时 | 8s ✓ |
| `MAX_RETRIES` | 1 | 方言回退链之外的**网络重试**次数 | 明确区分"方言回退"≠"重试" |
| `SEARCH_MAX_PAGES` | 5（1–50） | 搜索/分类最大页数 | 设为可配，默认 5 |
| 代理整体预算 | — | `FanOut.totalTimeoutMs` | 15s ✓ |

> 值得注意：参考项目把"方言/协议回退"与"网络重试"是**两件事**。我们当前 `CmsDialect` 的候选链是**协议回退**，不应与"重试同一请求"混为一谈 —— 这点我们的设计已天然区分（`CmsDialect` 候选 ≠ `HttpEngine` 重试），需在文档中写明。

---

## 2. 建议单开变更的功能（按 TV 端价值排序）

| # | 功能 | 参考实现 | TV 端价值 | 难度 | 建议 |
|---|---|---|---|---|---|
| F1 | **远程订阅源**（订阅 URL 自动同步） | `subscription-sync.ts` | ★★★ 不用更新 APK 即可换源/加源 | 中 | **做** |
| F2 | **片头/片尾跳过标记** | `skip-marks.ts` | ★★★ TV 端观影体验提升明显 | 中高 | **做** |
| F3 | **源列表 / 设置 / 历史 导出导入** | `source-list-publish.ts`、`persist-storage.ts` | ★★ 备份与换机迁移 | 低 | **做** |
| F4 | **TVBOX / SourceList 格式导入** | `tvbox-parser.ts` | ★★ 复用 TV 生态现成格式 | 中 | **做** |
| F5 | 播放失败自动换源 + 进度保持 | `playback-recovery.ts` | ★★★ | 中 | **做**（已并入 B4/B5） |
| F6 | 多推荐源（Bangumi / 豆瓣周榜 / 热播榜） | `bangumi.ts`、`douban-weekly.ts` | ★★ 首页内容更丰富 | 低 | 可选 |
| F7 | 下一集预取 / 播放缓存 | `video-prefetcher.ts` | ★★ | 中 | 可选 |
| F8 | 直播 / IPTV / XMLTV EPG | `m3u-parser.ts`、`xmltv.ts` | ★ 与现有"点播聚合"定位不同 | 高 | **不做** |
| F9 | 鉴权 / SSRF 防护 / 反向代理 | `auth.ts`、`ssrf.ts` | — 端侧直连无此风险 | — | **不做** |
| F10 | PWA / 双主题 / 主题闪烁 | — | — 不适用 | — | **不做** |
| F11 | "发布为链接"公开粘贴 | `source-list-publish.ts` | 公开外网暴露源列表，TV 端无收益且有风险 | — | **不做** |

### F1 远程订阅：可直接沿用参考实现的语义

- **格式**：兼容 LibreTV `SourceList` JSON v2（`sources[]` / `liveSources[]`，我们只取 `sources`），并支持裸数组。
- **刷新周期**：关闭 / 1 天 / 3 天 / 7 天 / 14 天 / 30 天。
- **调度**：仅在应用可见时检查；单次最多刷新 5 个订阅；并发 2；后台刷新不阻塞首屏。
- **失败处理**：连续失败 3 次自动暂停订阅；手动同步成功或修改周期后恢复计数。
- **归属边界**：订阅源以远端为准（手动编辑会被下次同步覆盖）；手动添加的源不受订阅影响；删除订阅才删除其源。

### F2 跳过标记：与现有 M3u8 广告过滤的分工

| 机制 | 触发方 | 粒度 | 存储 |
|---|---|---|---|
| 现有 `M3u8AdFilterPlaylistParserFactory` | 源侧自动识别 | 分片级 | 无（运行时改写 manifest） |
| 新增跳过标记 | 用户手动 | 时间区间级 | 本地，按 `归一化片名` 复用 |

- 手动标记：片头结束点、片尾起点、中段区间；进度条着色；边界可微调、可试跳、可撤销。
- 复用：同影片后续剧集与同名资源自动继承标记。
- 落地：`ExoPlayer.seekTo()` 跳过 + 进度条区间渲染。

### F4 TVBOX / SourceList 导入的边界（照搬参考实现的限制）

- TVBOX `sites` 中符合 Apple CMS 特征的导入为点播源；`lives` 忽略。
- 跳过 Spider、XML 接口、不可直接调用的外链 JSON。
- 不支持 Base64 / 压缩包装的分享链接；不支持 TVBOX 多仓 `urls` 顶层结构。
- 单个订阅最多导入 100 个点播源，按 URL 去重。

---

## 3. 参考项目也没解决、我们仍需自己解决的

| 事项 | 现状 |
|---|---|
| 端侧共享缓存 | 参考项目在**服务端**共享探测与缓存；我们每台设备各自直连 → **健康度与探测结果必须落盘**（B1、B2） |
| TV 焦点系统 | 参考项目是鼠标/触屏交互；我们的 TV 焦点导航是自有能力，参考项目无可借鉴 |
| ExoPlayer 广告过滤 | 参考项目用 hls.js 改写；我们已有专门单测覆盖，行为不变（仅位置迁移） |
| 探活语义 | 参考项目同样存在探活污染问题；我们的 `ac=list` 方案更优，保留 |

---

## 4. 结论

| 类别 | 条目 | 落点 |
|---|---|---|
| 修正现有设计 | B1 阶梯冷却、B2 探测队列、B3 画质实测、B4 播放恢复、B5 进度保持、B6 参数对齐 | 更新 `design-detail.md` §E、§F，并追加任务 |
| 单开变更（建议） | F1 订阅源、F2 跳过标记、F3 导出导入、F4 TVBOX 导入 | 新建 `openspec/changes/` 变更 |
| 可选 | F6 多推荐源、F7 预取 | 视优先级 |
| 不做 | F8 直播、F9 鉴权/SSRF、F10 PWA、F11 公开发布 | 记录理由 |
