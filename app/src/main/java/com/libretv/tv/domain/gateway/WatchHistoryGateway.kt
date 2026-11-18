package com.libretv.tv.domain.gateway

import com.libretv.tv.data.WatchRecord
import kotlinx.coroutines.flow.StateFlow

/**
 * 观看历史的持久化契约（local-first-storage 1.3）。
 *
 * 领域层纯接口——不出现 `import android.` / `import androidx.room.`。当前
 * [HistoryGateway] 继承它，兼容既有 SP 实现；Room 实现也只需实现本接口。
 * 跨源聚合用默认方法给出，任何实现自动获得该能力。
 */
interface WatchHistoryGateway {

    /** 全部观看记录（按最近观看排序，可观察）。 */
    val records: StateFlow<List<WatchRecord>>

    /** 插入或更新一条进度。 */
    fun upsert(record: WatchRecord)

    /** 删除单条。 */
    fun delete(key: String)

    /** 清空全部。 */
    fun clear()

    /** 历史上限。 */
    val maxRecords: Int get() = 200

    /**
     * 按归一化片名把同一影片在不同来源的记录聚合为一部（4.3）。
     *
     * 保留最近一次观看的剧集与进度作为代表，来源数用于卡片角标；其他来源
     * 的原始记录仍在 [records] 里单独保留。
     */
    fun aggregateByTitle(): List<TitleProgress> {
        val groups = LinkedHashMap<String, MutableList<WatchRecord>>()
        records.value.forEach { record ->
            val key = record.titleKey.ifBlank { record.title }
            if (key.isBlank()) return@forEach
            groups.getOrPut(key) { mutableListOf() }.add(record)
        }
        return groups.map { (titleKey, items) ->
            val latest = items.maxByOrNull { it.updatedAt }!!
            TitleProgress(
                titleKey = titleKey,
                title = latest.title,
                poster = latest.poster,
                sourceCount = items.map { it.sourceUrl }.toSet().size,
                latest = latest
            )
        }.sortedByDescending { it.latest.updatedAt }
    }
}

/** 一部影片的跨来源观看聚合。 */
data class TitleProgress(
    val titleKey: String,
    val title: String,
    val poster: String,
    val sourceCount: Int,
    val latest: WatchRecord
)
