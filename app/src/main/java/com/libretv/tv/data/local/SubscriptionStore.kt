package com.libretv.tv.data.local

import android.content.Context
import com.libretv.tv.data.SourceStore
import com.libretv.tv.domain.gateway.SubscriptionGateway
import com.libretv.tv.domain.model.SourceConfig
import com.libretv.tv.domain.model.SourceOrigin
import com.libretv.tv.domain.model.Subscription
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 订阅的本地存储。
 *
 * 订阅本身很小（几条记录），跟源清单放在同一个 SP 文件里——它们本来
 * 就是一件事：观众在「源管理」页里管理的采集源。
 *
 * 注：`local-first-storage` 变更落地后，订阅与跳过标记会迁进 Room 的
 * `subscription` / `skip_mark` 表；这里是过渡形态，键名集中在
 * [StorageKeys]，迁移时只需搬这一处的键。
 */
class SubscriptionStore(
    context: Context,
    private val sourceStore: SourceStore
) : SubscriptionGateway {

    private val prefs = context.applicationContext
        .getSharedPreferences(StorageKeys.SOURCES, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun subscriptions(): List<Subscription> {
        val raw = prefs.getString(StorageKeys.Sources.SUBSCRIPTIONS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<Subscription>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun saveSubscriptions(list: List<Subscription>) {
        prefs.edit()
            .putString(StorageKeys.Sources.SUBSCRIPTIONS, json.encodeToString(list))
            .apply()
    }

    override fun customSources(): List<SourceConfig> = sourceStore.customSources

    /**
     * 用远端内容替换某个订阅带来的源。
     *
     * 两条边界规则：
     *
     *  - 只动属于这个订阅的源——观众手工加的源一个都不碰；
     *  - 地址与手工源重复的条目直接跳过，手工源优先（同一地址只能有一份）。
     */
    override fun replaceSubscriptionSources(
        subscriptionId: String,
        sources: List<SourceConfig>
    ): List<String> {
        val existing = sourceStore.customSources
        val removed = existing.filter { it.subscriptionId == subscriptionId }.map { it.url }
        val kept = existing.filterNot { it.subscriptionId == subscriptionId }
        val keptUrls = kept.map { it.url }.toSet()
        val tagged = sources
            .filter { it.url !in keptUrls }
            .map { it.copy(subscriptionId = subscriptionId, origin = SourceOrigin.SUBSCRIPTION) }
        sourceStore.customSources = kept + tagged
        return removed
    }

    override fun removeSubscriptionSources(subscriptionId: String): List<String> {
        val existing = sourceStore.customSources
        val removed = existing.filter { it.subscriptionId == subscriptionId }
        sourceStore.customSources = existing.filterNot { it.subscriptionId == subscriptionId }
        return removed.map { it.url }
    }
}
