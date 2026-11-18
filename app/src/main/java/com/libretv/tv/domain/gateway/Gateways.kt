package com.libretv.tv.domain.gateway

import com.libretv.tv.data.SourceConfig
import com.libretv.tv.data.SourceHealth

/**
 * 源清单的存取契约。
 *
 * ViewModel 只知道「有一份源清单 + 拼音索引」，不关心它来自 assets、
 * SharedPreferences 还是未来的数据库。
 */
interface SourceGateway {
    /** 读取全部内置采集源（含镜像解包与去重）。 */
    suspend fun loadSources(): List<SourceConfig>

    /** 读取内置的拼音首字母索引（"ldqd" -> "流浪地球"）。 */
    suspend fun loadTitleIndex(): Map<String, String>
}

/**
 * 内容消费契约：扇出搜索、分类树、详情与探测。
 *
 * ViewModel 通过这个接口编排页面数据；实现的并发预算（FanOut）、
 * 分类树缓存与探测引擎都藏在后面。
 */
interface ContentGateway {
    /** 全源并发搜索，结果带每源的往返延迟。 */
    suspend fun searchAll(sources: List<SourceConfig>, keyword: String): List<com.libretv.tv.data.SearchResult>

    /** 读取所有可用采集源的分类树（8 秒单项上限）。 */
    suspend fun allCategoryCatalogs(sources: List<SourceConfig>): List<com.libretv.tv.data.CategoryCatalog>

    /** 读一页分类货架，带源与延迟标记。 */
    suspend fun categoryShelf(source: SourceConfig, typeId: Int, page: Int = 1): List<com.libretv.tv.data.SearchResult>

    /** 拉取详情并解析线路；失败时用 [item] 兜底。 */
    suspend fun fetchDetail(source: SourceConfig, item: com.libretv.tv.data.VodItem): com.libretv.tv.data.DetailBundle?

    /** 对一个 offer 做轻量详情 + 首字节探测。 */
    suspend fun probeOffer(offer: com.libretv.tv.domain.model.SourceOffer): com.libretv.tv.domain.model.OfferProbe

    /** 单源探活（`?ac=list`），返回可达性与延迟。 */
    suspend fun probeWithLatency(source: SourceConfig): SourceHealth

    /** 全源探活。 */
    suspend fun probeAll(sources: List<SourceConfig>): Map<String, SourceHealth>

    /** 豆瓣发现条带（失败按空条带处理）。 */
    suspend fun doubanRecommendations(
        tag: String,
        pageSize: Int,
        type: String,
        start: Int
    ): com.libretv.tv.core.ApiResult<List<com.libretv.tv.data.remote.DoubanSubject>>
}

/**
 * 观看历史的存取契约。
 *
 * 历史是纯本地数据；换持久化实现（SP → Room）时 ViewModel 不受影响。
 */
interface HistoryGateway {
    /** 全部观看记录（按最近观看排序）。 */
    val records: kotlinx.coroutines.flow.StateFlow<List<com.libretv.tv.data.WatchRecord>>

    /** 插入或更新一条进度；看完的记录下次从 0 开始。 */
    fun upsert(record: com.libretv.tv.data.WatchRecord)

    /** 删除单条记录。 */
    fun delete(key: String)

    /** 清空全部历史。 */
    fun clear()
}
