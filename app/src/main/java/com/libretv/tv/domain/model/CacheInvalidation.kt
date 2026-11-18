package com.libretv.tv.domain.model

/**
 * 缓存失效矩阵（local-first-storage 3.3）。
 *
 * 三类机制并存、缺一不可：
 *  - 签名型：来源集合变化 → 依赖来源集的内容整体失效；
 *  - 前缀型：单个来源/分类变化 → 仅相关缓存失效，其余保留；
 *  - 版本型：模型升级 → 全部旧版本缓存失效。
 *
 * 纯领域逻辑：给出「某条缓存是否命中某次失效事件」的判定，data 层据此
 * 执行删除。放在 domain 便于纯 JVM 单测覆盖三种场景。
 */
object CacheInvalidation {

    sealed class Event {
        /** 来源集合（增/删/启停）发生变化，携带新签名。 */
        data class SourceSetChanged(val newSignature: String) : Event()

        /** 单个来源被删除或失效，携带来源地址。 */
        data class SourceRemoved(val sourceUrl: String) : Event()

        /** 某分类内容被重新采集，携带缓存键前缀。 */
        data class CategoryRefreshed(val keyPrefix: String) : Event()

        /** 缓存模型版本升级。 */
        data class ModelUpgraded(val currentVersion: Int) : Event()
    }

    /** 缓存条目的元数据（判定失效所需）。 */
    data class EntryMeta(
        val kind: String,
        val cacheKey: String,
        val sourceSignature: String,
        val version: Int,
        val sourceUrl: String = ""
    )

    /** 该条目是否被这次事件判为失效。 */
    fun isInvalidated(entry: EntryMeta, event: Event): Boolean = when (event) {
        is Event.SourceSetChanged ->
            // 依赖来源集的缓存（feed/category/detail）签名与当前不符即失效。
            entry.kind in SOURCE_DEPENDENT_KINDS && entry.sourceSignature != event.newSignature
        is Event.SourceRemoved ->
            entry.kind in SOURCE_SCOPED_KINDS && entry.sourceUrl == event.sourceUrl
        is Event.CategoryRefreshed ->
            entry.cacheKey.startsWith(event.keyPrefix)
        is Event.ModelUpgraded ->
            entry.version != event.currentVersion
    }

    private val SOURCE_DEPENDENT_KINDS = setOf("feed", "category", "detail")
    private val SOURCE_SCOPED_KINDS = setOf("detail", "probe")
}
