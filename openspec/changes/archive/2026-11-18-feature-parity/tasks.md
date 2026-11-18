# Tasks: source-lifecycle（源生命周期与观影体验补齐）

> 依赖：`architecture-refactor`（接口层与领域模型分层）与 `../local-first-storage/`（持久化层）完成后启动。
> 每阶段完成条件：对应场景的新增规格 + 既有 `openspec/specs/**` 全部仍成立。
> 收藏相关实现见 `../local-first-storage/tasks.md` 第 4 组。

## S1 阶段：远程订阅源

- [x] S1.1 `domain/model/Subscription.kt`：`Subscription(id, name, url, refreshDays, status, failureCount, lastSyncAt)`
- [x] S1.2 `data/remote/SubscriptionApi.kt` + `SourceListParser`：解析 SourceList v2 / 裸数组；按 URL 去重；上限 100
- [x] S1.3 `data/local/SubscriptionStore.kt`：持久化订阅配置、失败计数、暂停状态
- [x] S1.4 `SourceStore` 记录源的**归属**（`subscriptionId` 或 `manual`），同步时按归属精确增删
- [x] S1.5 `SubscriptionSync`：仅前台检查、单次最多 5 个、并发 2、后台不阻塞首屏
- [x] S1.6 连续失败 3 次 → 自动暂停；手动同步成功或改周期 → 计数清零
- [x] S1.7 源管理页：订阅列表（状态/周期/上次同步）、添加/编辑/删除、立即同步
- [x] S1.8 单测：解析各种格式、去重、上限截断、失败计数、归属边界（订阅源被覆盖、手动源不受影响）

## S2 阶段：TVBOX / SourceList 导入

- [x] S2.1 `data/remote/TvboxParser.kt`：解析 `sites` / `lives`；识别 Apple CMS 特征；跳过 Spider / XML / 外链 JSON
- [x] S2.2 导入预览 UI：可导入数量、跳过条目及原因、确认后执行
- [x] S2.3 单测：合法配置、含 Spider、含外链、空配置、非法 JSON

## S3 阶段：导出与导入

- [x] S3.1 `domain/model/BackupBundle.kt`：源列表 / 订阅 / 设置 / 历史，带 `version`
- [x] S3.2 导出：写 JSON 文件并调系统分享
- [x] S3.3 导入：文件选择器 → 解析 → 预览差异 → 合并（URL 去重、历史可选不覆盖）
- [x] S3.4 兼容旧版本备份；版本不识别时明确报错
- [x] S3.5 单测：往返一致性、合并去重、版本兼容

## S4 阶段：片头 / 片尾跳过标记

- [x] S4.1 `domain/model/SkipMarks.kt`：`titleKey → List<TimeRange>`（片头/中段/片尾区间）
- [x] S4.2 `data/local/SkipMarkStore.kt`：持久化，按**归一化片名**为键
- [x] S4.3 播放页交互：标记片头结束 / 片尾开始 / 中段区间；进度条着色渲染区间
- [x] S4.4 边界微调、试跳、撤销；全局开关（设置页）
- [x] S4.5 命中区间自动 seek 跳过（`ExoPlayer.seekTo`），与现有 M3u8 广告过滤并存且广告过滤优先
- [x] S4.6 单测：区间匹配、跨集复用、撤销、与广告过滤的优先级

## S5 阶段：播放失败自动换源与进度保持（用户可见行为）

> 实现主体在 `architecture-refactor`（`PlaybackRecovery` 状态机、`PlayRequest.resumePositionMs`），本阶段只补用户可见行为与规格。

- [x] S5.1 播放失败分类：网络/超时类可恢复，编解码/文件非法类直接报错
- [x] S5.2 恢复流程：同源重试 1 次（退避 1.5s）→ 换下一 offer（最多 3 个）→ 全部失败才报错
- [x] S5.3 换源保留剧集与进度（比例映射），并提示"已切换到 <源名>"
- [x] S5.4 全部失败时提供"手动选源"入口
- [x] S5.5 单测：恢复决策树、进度映射、offer 耗尽

## S6 阶段：收藏纳入备份包

> 收藏本身（实体、列表页、入口）由 `../local-first-storage/` 的 `favorites` 能力实现，本阶段只补备份覆盖。

- [x] S6.1 `BackupBundle` 增加收藏条目（归一化片名、海报、来源、收藏时间）
- [x] S6.2 导出包含收藏；导入时收藏按归一化片名合并去重，历史与收藏均默认不覆盖
- [x] S6.3 单测：备份往返后收藏一致、重复导入不产生重复收藏
