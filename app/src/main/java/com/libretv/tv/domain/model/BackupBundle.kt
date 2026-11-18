package com.libretv.tv.domain.model

import kotlinx.serialization.Serializable

/**
 * 备份包：换机迁移时导出的全部内容（design D7）。
 *
 * 只装「配置与记录」：源列表（含订阅）、应用设置、观看历史与收藏。
 * **不放任何播放地址与本地缓存**——备份文件是要发出去的，泄露播放地址
 * 既没有意义也让人不安。
 */
@Serializable
data class BackupBundle(
    /** 备份格式版本；导入时按它判断兼容性。 */
    val version: Int = CURRENT_VERSION,
    /** 导出时间（毫秒）。 */
    val exportedAt: Long = 0L,
    /** 采集源清单。 */
    val sources: List<SourceConfig> = emptyList(),
    /** 远程订阅。 */
    val subscriptions: List<Subscription> = emptyList(),
    /** 应用设置。 */
    val settings: BackupSettings = BackupSettings(),
    /** 观看历史。 */
    val history: List<WatchRecord> = emptyList(),
    /** 收藏。 */
    val favorites: List<FavoriteEntry> = emptyList()
) {
    companion object {
        /** 当前备份格式版本。 */
        const val CURRENT_VERSION = 1

        /** 可识别的备份版本。 */
        val SUPPORTED_VERSIONS = setOf(1)
    }
}

/** 备份包里的应用设置。 */
@Serializable
data class BackupSettings(
    val adFilterEnabled: Boolean = true,
    val posterBadgesEnabled: Boolean = true,
    val debugLogEnabled: Boolean = false,
    val skipMarksEnabled: Boolean = true
)

/**
 * 收藏条目。
 *
 * 键是**归一化片名**：同一部片子在不同来源下是同一条收藏（与跳过标记
 * 同一套键规则），来源只作为展示信息保留。
 */
@Serializable
data class FavoriteEntry(
    /** 归一化片名（唯一键）。 */
    val titleKey: String,
    /** 展示用片名。 */
    val title: String = "",
    /** 海报地址。 */
    val poster: String = "",
    /** 来源名（可空：不同来源的同一部片子是同一条收藏）。 */
    val sourceName: String = "",
    /** 来源地址。 */
    val sourceUrl: String = "",
    /** 影片 id。 */
    val vodId: String = "",
    /** 收藏时间（毫秒）。 */
    val createdAt: Long = 0L
)

/** 导入时的合并选项。 */
data class BackupMergeOptions(
    /** 是否用备份里的历史覆盖本地历史。默认不覆盖（历史是用户资产）。 */
    val overwriteHistory: Boolean = false,
    /** 是否用备份里的收藏覆盖本地收藏。默认不覆盖。 */
    val overwriteFavorites: Boolean = false,
    /** 是否导入应用设置。 */
    val importSettings: Boolean = false
)

/** 合并结果：合并后的数据 + 差异统计（用于导入预览）。 */
data class BackupMergePlan(
    val sources: List<SourceConfig>,
    val subscriptions: List<Subscription>,
    val settings: BackupSettings,
    val history: List<WatchRecord>,
    val favorites: List<FavoriteEntry>,
    /** 新增的源数量。 */
    val addedSourceCount: Int,
    /** 因地址重复而跳过的源数量。 */
    val duplicateSourceCount: Int,
    /** 新增的历史条数。 */
    val addedHistoryCount: Int,
    /** 新增的收藏条数。 */
    val addedFavoriteCount: Int,
    /** 新增的订阅数量。 */
    val addedSubscriptionCount: Int
)

/**
 * 备份合并。
 *
 * 规则很简单，但每条都对应一个真实的用户意图：
 *
 *  - **源与订阅按地址去重**：重复的地址不重复导入，其余追加（源管理规格）。
 *  - **历史与收藏默认不覆盖**：本地已有的记录优先，备份只补本地没有的；
 *    观众可以在导入预览里显式选择覆盖。
 */
object BackupMerger {

    /** 合并两份备份：本地为底，备份为增量。 */
    fun merge(
        current: BackupBundle,
        incoming: BackupBundle,
        options: BackupMergeOptions = BackupMergeOptions()
    ): BackupMergePlan {
        val existingSourceUrls = current.sources.map { it.url }.toSet()
        val freshSources = incoming.sources.filter { it.url.isNotBlank() && it.url !in existingSourceUrls }
        val duplicateSources = incoming.sources.count { it.url.isNotBlank() && it.url in existingSourceUrls }

        val existingSubUrls = current.subscriptions.map { it.url }.toSet()
        val freshSubscriptions = incoming.subscriptions.filter { it.url !in existingSubUrls }

        val history = mergeHistory(current.history, incoming.history, options.overwriteHistory)
        val favorites = mergeFavorites(current.favorites, incoming.favorites, options.overwriteFavorites)

        return BackupMergePlan(
            sources = current.sources + freshSources,
            subscriptions = current.subscriptions + freshSubscriptions,
            settings = if (options.importSettings) incoming.settings else current.settings,
            history = history,
            favorites = favorites,
            addedSourceCount = freshSources.size,
            duplicateSourceCount = duplicateSources,
            addedHistoryCount = incoming.history.count { it.key !in current.history.map { r -> r.key } },
            addedFavoriteCount = incoming.favorites.count {
                it.titleKey !in current.favorites.map { f -> f.titleKey }
            },
            addedSubscriptionCount = freshSubscriptions.size
        )
    }

    /**
     * 合并观看历史。
     *
     * 默认不覆盖：本地已有的同一条记录保留，备份只补本地没有的。
     * 选覆盖时以备份为准（本地没有的仍然补进来）。
     */
    fun mergeHistory(
        current: List<WatchRecord>,
        incoming: List<WatchRecord>,
        overwrite: Boolean
    ): List<WatchRecord> {
        if (overwrite) {
            val incomingKeys = incoming.map { it.key }.toSet()
            return current.filterNot { it.key in incomingKeys } + incoming
        }
        val localKeys = current.map { it.key }.toSet()
        return current + incoming.filterNot { it.key in localKeys }
    }

    /**
     * 合并收藏：按归一化片名去重。
     *
     * 重复导入同一份备份不会产生第二条收藏——键是归一化片名，与收藏本身的
     * 主键规则一致。
     */
    fun mergeFavorites(
        current: List<FavoriteEntry>,
        incoming: List<FavoriteEntry>,
        overwrite: Boolean
    ): List<FavoriteEntry> {
        val localKeys = current.map { it.titleKey }.toSet()
        val incomingKeys = incoming.map { it.titleKey }.toSet()
        return if (overwrite) {
            current.filterNot { it.titleKey in incomingKeys } + incoming
        } else {
            current + incoming.filterNot { it.titleKey in localKeys }
        }
    }
}
