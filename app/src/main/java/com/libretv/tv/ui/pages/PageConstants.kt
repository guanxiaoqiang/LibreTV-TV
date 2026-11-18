package com.libretv.tv.ui.pages

/** 首页每次拉取的条目数（一次滚动露出几张卡片）。 */
internal const val FEED_PAGE_SIZE = 24

/** 豆瓣条带每次拉取的条目数。 */
internal const val DOUBAN_PAGE_SIZE = 20

/** 豆瓣接口的分页上限：超过这个 offset 就不再继续请求。 */
internal const val MAX_DOUBAN_START = 100

/** 探测优先级基数：候选越靠前，优先级越高。 */
internal const val PROBE_PRIORITY_BASE = 100

/** 首页刷新时的防抖窗口：切标签时不至于把旧请求也带进缓存。 */
internal const val FEED_REFRESH_DELAY_MS = 500L

/** 滚动暂停的最长保持时间：超过它就强制提交一次。 */
internal const val PAUSE_HOLD_MAX_MS = 2_000L

/** 日志标签：首页 ViewModel。 */
internal const val TAG = "HomeViewModel"
