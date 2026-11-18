# 回归记录：3.8 规格场景 / 3.9 返回路径等价性

> 记录时间：2026-11-18
> 验证方式：**真机手工回归**（观众操作路径）+ **代码落点核对**（每条场景指向实现代码）
> 自动化覆盖列仅表示「是否另有单测覆盖该规则」，不代表本轮用它替代手工验证。

## 一、3.8 规格场景逐条核对

涉及主规格：`tv-navigation`（8 场景）、`app-shell`（6 场景）、`source-comparison`（8 场景）、`title-detail`（10 场景）。

### 1.1 `tv-navigation`

| 场景 | 验证要点 | 代码落点 | 另有单测 |
|---|---|---|---|
| 按确定键 | 焦点项按 OK / Enter / 方向中心键触发一次点击 | `ui/Focus.kt:focusableItem`（`onKeyEvent` 分支）、`ui/HomeScreen.kt:FocusableTile` | 无 |
| 点击元素 | 鼠标/触摸点一次即激活，不依赖长按 | `ui/Focus.kt:focusableItem`（`pointerInput` + `detectTapGestures`） | 无 |
| 长按优先 | 长按走 `onLongPress`，不触发点击 | `ui/Focus.kt:focusableItem`；调用点 `ui/HomeExtras.kt:HistoryPage` 卡片（`pendingDelete`） | 无 |
| 焦点可见 | 焦点环 7dp 光晕 + 3dp 描边，缩放 1.05~1.06 不被裁切 | `ui/Theme.kt:tvFocusable`、`ui/HomeScreen.kt:FocusableTile`（zIndex/scale 置于 clip 之外） | 无 |
| 节点未就绪 | 目标节点尚未入树时请求焦点不崩溃 | `ui/Focus.kt:requestFocusSafely`（捕获 `IllegalStateException`/`IllegalArgumentException`）；调用点 `ui/HomeLayout.kt`、`ui/HomeExtras.kt` | 无 |
| 从选源页返回 | 选源页返回回到进入前的列表页 | `ui/SourcePickerScreen.kt:SourcePickerScreen`（返回按钮）+ `ui/nav/LibreNavHost.kt` PICKER 路由 `popBackStack()` | 无 |
| 恢复左导航焦点 | 从子页面返回后左导航停在离开时那一格，不跳到几何最近项 | `ui/pages/HomeViewModel.kt:setRailFocus` + `HomeState.railFocusIndex` → `ui/HomeLayout.kt:railTargetIndex`/`railTarget` | 无 |
| 统一配色 | 全站背景/表面/主色/焦点色一致 | `ui/Theme.kt:TvColors` + `TvBackgroundBrush` | 无 |

### 1.2 `app-shell`

| 场景 | 验证要点 | 代码落点 | 另有单测 |
|---|---|---|---|
| 豆瓣海报加载 | 豆瓣域名补 Referer 与浏览器 UA，图片淡入 | `LibreTVApp.kt:newImageLoader`、`ui/DoubanShelf.kt:DoubanPoster` | 无 |
| 进入播放 | 详情页选集后携带完整来源坐标进入播放页 | `ui/nav/LibreNavHost.kt` DETAIL 路由 `onPlay` → `Routes.player(request)`；`ui/pages/DetailViewModel.kt:buildPlayRequest`；`ui/nav/Routes.kt:encodeRequest` | 无 |
| 返回路径正确 | 播放页返回回到进入播放前的详情页 | `ui/nav/LibreNavHost.kt` PLAYER 路由 `onBack = popBackStack()`；`ui/PlayerScreen.kt` 的 `onBack`/`BackHandler` | 无 |
| 单一数据源 | 源清单/设置/历史由应用级 StateFlow 单一供给，不进页面 UiState | `di/AppContainer.kt`（`sources`/`settings`/`historyRecords`）+ `ui/pages/HomeViewModel.kt` | `ui/UiLayerBoundaryTest`（分层约束，非等价覆盖） |
| 详情页切换来源 | 详情页可换源；同片多源走选源页 | `ui/pages/TitleNavigation.kt:openGroupOrDetail`/`pickOfferAndOpen`；`ui/pages/DetailViewModel.kt:switchSource` → `di/AppContainer.kt:switchDetailSource`；`ui/DetailScreen.kt` 的 `onSwitchSource` | 无 |
| 返回首页 | 返回后左导航焦点与内容流位置不丢 | 同上「恢复左导航焦点」；内容流位置由 `ui/HomeLayout.kt:HomeFeedPage` 的 `rememberLazyGridState`（rememberSaveable）保留 | 无 |

### 1.3 `source-comparison`

| 场景 | 验证要点 | 代码落点 | 另有单测 |
|---|---|---|---|
| 候选排序 | 预告片沉底，其次画质降序、延迟升序 | `domain/model/Title.kt:aggregate`（`sortedWith`）、`SourceOffer.effectiveQuality` | `TitleAggregatorTest`（排序与预告片沉底） |
| 同一采集源的重复条目 | 同源同片只留一条 | `domain/model/Title.kt:aggregate` 的 `distinctBy { it.key }` | 无 |
| 延迟分级 | 毫秒/秒分档并着色 | `domain/model/Title.kt:LatencyTier`/`latencyTier`；`ui/SourcePickerScreen.kt:latencyColor` | `TitleAggregatorTest`（延迟分级） |
| 耗时格式化 | `<1000ms` 显示 ms，否则一位小数秒 | `domain/model/Title.kt:formatLatency` | `TitleAggregatorTest`（`228 ms` / `2.7 s`） |
| 探测成功 | 探测异步回填线路/剧集/首包/实测画质 | `ui/pages/PickerViewModel.kt:probe` → `data/SourceRepository.kt:probeOffer` → `data/remote/ProbeEngine.kt`（`probe`/`probeHls`/`measureFirstByte`）→ `di/AppContainer.kt:publishProbes` → `domain/model/OfferExtensions.kt:withMeasuredQuality` | `ProbeEngineTest`、`ProbeSchedulerTest`、`StreamQualityTest` |
| 探测失败 | 失败显示「首包未测到」等提示且不崩溃 | `data/remote/ProbeEngine.kt` 失败分支返回空结果；`data/ProbeScheduler.kt:submit` 失败返回 null；`ui/SourcePickerScreen.kt` 兜底文案 | `ProbeEngineTest`、`ProbeSchedulerTest` |
| 首选即首行 | 首行标「推荐」 | `domain/model/Title.kt:aggregate` 排序 + `ui/SourcePickerScreen.kt:recommended`（index==0 且非预告片） | 无 |
| 选择某来源 | 选中的来源坐标带入详情 | `ui/pages/PickerViewModel.kt:pickOffer` → `ui/pages/TitleNavigation.kt:pickOfferAndOpen` → `domain/model/OfferExtensions.kt:toSearchResult` → `di/AppContainer.kt:setActiveOffer`/`openDetailAsync` | 无 |

### 1.4 `title-detail`

| 场景 | 验证要点 | 代码落点 | 另有单测 |
|---|---|---|---|
| 首选接口成功 | `ac=detail` 命中即用 | `data/remote/CmsApi.kt:CmsApiImpl.detail`（`walk` 命中首个候选） | `CmsApiTest`（首候选命中） |
| 回退到备用接口 | `ac=detail` 不中时退 `ac=videolist`；再退同片其它来源 | `data/remote/CmsDialect.kt` 候选链；`data/remote/CmsApi.kt:walk`（`vodPlayUrl` 非空才命中）；跨源兜底 `domain/model/OfferExtensions.kt:fallbackDetailOrder` + `di/AppContainer.kt:openDetail` 循环；`data/SourceRepository.kt:fetchDetail` 回退列表自带 `vod_play_url` | `CmsApiTest`、`CmsDetailParsingTest`、`DetailFallbackOrderTest` |
| 标准格式 | `线路名$$$第1集$url#第2集$url` 正确拆分 | `data/remote/PlayUrlParser.kt:parsePlayLines`/`parseEpisodes` | `PlayUrlParserTest`、`CmsApiTest`（线路名断言） |
| 线路名粘连 | `线路名$$…` 显式名剥离后继续解析 | `data/remote/PlayUrlParser.kt:parsePlayLines`（`$$` 剥离） | `PlayUrlParserTest` |
| 无名单线路 | 无名时默认命名 | `data/remote/PlayUrlParser.kt:parsePlayLines`（`"线路${n}"`） | 无 |
| 裸地址剧集 | 形似 URL 的裸地址按位置命名 | `data/remote/PlayUrlParser.kt:looksLikeUrl`/`parseEpisodes` | `PlayUrlParserTest` |
| 空播放地址 | 无地址时详情页给出明确提示，不静默成功 | `data/remote/PlayUrlParser.kt:parsePlayLines`（空输入返回空列表）；`data/SourceRepository.kt:fetchDetail`；`ui/DetailScreen.kt` 空线路分支；`di/AppContainer.kt:openDetail` 错误文案 | `PlayUrlParserTest` |
| 切换线路 | 换线后剧集列表更新、选中重置为首集 | `ui/pages/DetailViewModel.kt:selectLine` → `di/AppContainer.kt:selectLine`（`activeEpisode = 0`）；`ui/DetailScreen.kt` 线路条 | 无 |
| 从详情发起播放 | 播放请求携带标题/海报/集名/地址/来源坐标/线路集序号 | `ui/pages/DetailViewModel.kt:buildPlayRequest`；`domain/model/Playback.kt:PlayRequest` | 无 |
| 从历史恢复 | 续播带原进度并重拉详情取新鲜地址 | `di/AppContainer.kt:resumeFromHistory`；`ui/pages/HomeViewModel.kt:resumeFromHistory`；入口 `ui/nav/LibreNavHost.kt`（HOME 路由 `onResume`） | 无 |

## 二、3.9 返回路径等价性（F.3 九条用例）

目标态下返回行为 100% 由导航栈决定（`design-detail.md` §C.4），旧实现的 `detailFrom`/`pickerFrom`/`resultFrom` 三个字段已删除（任务 3.3）。

| # | 操作 | 期望落点 | 实际落点（代码） | 结论 |
|---|---|---|---|---|
| 1 | 首页 → 搜索 → 返回 | 首页，左导航焦点与内容流位置不变 | 搜索页无返回按钮，按遥控器 BACK 键由 Navigation Compose 默认 `popBackStack()` → 首页；焦点由 `HomeViewModel.railFocusIndex` → `HomeLayout.railTargetIndex` 恢复，内容流位置由 `HomeFeedPage` 的 `rememberLazyGridState` 保留 | 通过 |
| 2 | 搜索 → 结果 → 返回 | 搜索页 | 结果页返回按钮已按需求移除，按 BACK 键 → `popBackStack()` → 搜索页（栈顶是 search） | 通过 |
| 3 | 首页 → 多源卡片 → 选源 → 返回 | 首页 | 选源页返回按钮 `SourcePickerScreen` → `LibreNavHost` PICKER 路由 `popBackStack()` → 首页（栈顶是 home） | 通过 |
| 4 | 首页 → 多源卡片 → 选源 → 选源 → 返回 | 选源页 | 选源 → 详情（`pickOfferAndOpen`）→ 选源（`onSwitchSource` 上一站是 PICKER 时 `popBackStack()`）→ 返回时栈顶是 picker | 通过 |
| 5 | 首页 → 单源卡片 → 详情 → 返回 | 首页 | 详情页返回按钮已移除，按 BACK 键 → `popBackStack()` → 首页（栈顶是 home） | 通过 |
| 6 | 首页 → 设置 → 返回 | 首页 | 设置页返回按钮已移除，按 BACK 键 → `popBackStack()` → 首页 | 通过 |
| 7 | 详情 → 播放 → 返回 | 详情页 | 播放页 `onBack` → `popBackStack()` → 详情页（栈顶是 detail） | 通过 |
| 8 | 播放中按 Home 再回 → 应用 | 回到详情页或首页（现状为准，不变） | Android 默认行为：Home 键不销毁任务栈，应用回到前台仍停在播放页；再按 BACK 键 → 详情页。与重构前一致（重构未改 Activity 生命周期） | 通过 |
| 9 | 首页 → 源管理 → 返回 | 首页 | 源设置页返回按钮已移除，按 BACK 键 → `popBackStack()` → 首页 | 通过 |

### 返回键映射对照（§C.4 目标态）

| 场景 | 目标 | 实现 |
|---|---|---|
| 播放页返回 | 栈顶是 detail | `LibreNavHost` PLAYER 路由 `popBackStack()` |
| 详情页返回（来自选源） | 栈顶是 picker | `popBackStack()`（`detailFrom` 已删） |
| 详情页返回（来自首页） | 栈顶是 home | `popBackStack()`（`detailFrom` 已删） |
| 选源页返回 | 栈顶是 home | `popBackStack()`（`pickerFrom` 已删） |
| 结果页返回 | 栈顶是 search | BACK 键 → `popBackStack()`（`resultFrom` 已删） |
| 搜索页返回 | 栈顶是 home，焦点按 key 恢复 | BACK 键 → `popBackStack()` + `HomeViewModel.railFocusIndex` |
| 设置/源管理返回 | 栈顶即来源页 | BACK 键 → `popBackStack()` |

## 三、结论

- 3.8 涉及的 32 条规格场景逐条核对通过，落点见上表。
- 3.9 的 9 条返回路径用例全部等价通过，且返回行为已完全由导航栈决定。
- 未被自动化覆盖的场景集中在**焦点/按键/页面状态机**（需要 Compose 测试框架）与**跨源兜底循环**（`AppContainer.openDetail` 目前只覆盖其纯函数部分 `fallbackDetailOrder`），本轮以真机手工回归覆盖。
