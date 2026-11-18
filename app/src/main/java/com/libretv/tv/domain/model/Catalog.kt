package com.libretv.tv.domain.model

/**
 * 内容目录的领域模型（local-first-storage / content-catalog）。
 *
 * 目录只保存**可查询的元数据与集名**，不含播放地址。播放地址按
 * 「来源 + 影片 ID + 线路 + 剧集」在开始播放时实时解析（D5）。
 */

/** 一条目录标题（以归一化片名为全局唯一键）。 */
data class CatalogTitle(
    val titleKey: String,
    val title: String,
    val aliases: List<String> = emptyList(),
    val poster: String = "",
    val year: String = "",
    val area: String = "",
    val typeName: String = "",
    val doubanId: String = "",
    val updatedAt: Long = 0L
)

/** 一个可播放来源在目录中的投影（不含地址）。 */
data class CatalogOffer(
    val id: Long = 0L,
    val titleKey: String,
    val sourceUrl: String,
    val sourceName: String,
    val vodId: String,
    val quality: String = "",
    val remark: String = "",
    val latencyMs: Long = 0L,
    val lineCount: Int = 0,
    val updatedAt: Long = 0L
)

/** 一条集名（不含地址）。 */
data class CatalogEpisode(
    val id: Long = 0L,
    val offerId: Long,
    val lineIndex: Int,
    val episodeIndex: Int,
    val episodeName: String,
    val updatedAt: Long = 0L
)

/** 采集任务优先级（content-catalog §2.3）。数值越小越优先。 */
enum class CatalogPriority(val rank: Int) {
    P0_NEXT_EPISODE(0),
    P1_HOME_SHELF(1),
    P2_SEARCHED_DETAIL(2),
    P3_HEALTH_CHECK(3)
}

/** 一个待采集任务。 */
data class CatalogTask(
    val priority: CatalogPriority,
    val sourceUrl: String,
    val titleKey: String = "",
    val typeId: Int = 0,
    val page: Int = 1,
    val enqueuedAt: Long = 0L
)
