package com.libretv.tv.data

import com.libretv.tv.core.ApiResult
import com.libretv.tv.core.userMessage
import com.libretv.tv.data.remote.SourceListParser
import com.libretv.tv.data.remote.SourceListResult
import com.libretv.tv.domain.gateway.SubscriptionGateway
import com.libretv.tv.domain.model.SourceConfig
import com.libretv.tv.domain.model.Subscription
import com.libretv.tv.domain.model.SubscriptionRules
import com.libretv.tv.domain.model.SubscriptionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * 订阅同步。
 *
 * 规则（design D1/D2）：
 *
 *  - **只在应用在前台时**做自动同步——后台同步既费电又没人在看，
 *    更不能拖慢首屏；
 *  - 一轮最多处理 [SubscriptionRules.MAX_SUBSCRIPTIONS_PER_RUN] 个订阅，
 *    并发 [SubscriptionRules.SYNC_CONCURRENCY]，避免把弱网压垮；
 *  - 抓取在并发段完成，落盘在串行段完成——两个订阅同时改订阅列表会互相覆盖。
 *
 * 这类逻辑要能被单测覆盖，所以它不碰 Android：存储走 [SubscriptionGateway]
 * 接口，抓取走注入的 [fetch] 函数。
 */
class SubscriptionSync(
    private val gateway: SubscriptionGateway,
    private val fetch: suspend (String) -> ApiResult<String>,
    private val isForeground: () -> Boolean = { true },
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    /** 一轮同步的结果。 */
    data class SyncReport(
        /** 同步成功的订阅数。 */
        val synced: Int = 0,
        /** 同步失败的订阅数。 */
        val failed: Int = 0,
        /** 新增的源数量。 */
        val added: Int = 0,
        /** 因远端不再提供而移除的源数量。 */
        val removed: Int = 0,
        /** 因超出上限被截断的源数量。 */
        val truncated: Int = 0,
        /** 因应用在后台而整体跳过。 */
        val skipped: Boolean = false
    )

    /** 单个订阅的同步结果。 */
    data class SyncOutcome(
        val success: Boolean,
        val message: String,
        val added: Int = 0,
        val removed: Int = 0,
        val truncated: Int = 0
    )

    /**
     * 自动同步：只处理到期且未暂停的订阅。
     *
     * 应用在后台时直接跳过，返回 [SyncReport] 的 `skipped` 为真。
     */
    suspend fun syncDue(): SyncReport {
        if (!isForeground()) return SyncReport(skipped = true)
        val due = gateway.subscriptions()
            .filter { it.isDue(now()) }
            .take(SubscriptionRules.MAX_SUBSCRIPTIONS_PER_RUN)
        if (due.isEmpty()) return SyncReport()
        return runAll(due)
    }

    /** 手动同步某个订阅（即使它已暂停也会尝试）。 */
    suspend fun syncNow(subscriptionId: String): SyncOutcome {
        val target = gateway.subscriptions().firstOrNull { it.id == subscriptionId }
            ?: return SyncOutcome(false, "订阅不存在")
        val result = runOne(target, fetchOne(target))
        applyOutcome(target, result)
        return result
    }

    /** 手动同步全部订阅（忽略周期与暂停状态）。 */
    suspend fun syncAll(): SyncReport {
        val all = gateway.subscriptions().take(SubscriptionRules.MAX_SUBSCRIPTIONS_PER_RUN)
        if (all.isEmpty()) return SyncReport()
        return runAll(all)
    }

    /** 抓取并解析一个订阅（并发段）。 */
    private suspend fun fetchOne(subscription: Subscription): FetchOutcome {
        val result = try {
            withTimeout(FETCH_TIMEOUT_MS) { fetch(subscription.url) }
        } catch (e: Exception) {
            return FetchOutcome.Failed("请求超时")
        }
        return when (result) {
            is ApiResult.Ok -> when (val parsed = SourceListParser.parse(result.value)) {
                is SourceListResult.Ok ->
                    FetchOutcome.Parsed(parsed.sources, parsed.truncated)
                is SourceListResult.Err -> FetchOutcome.Failed(parsed.message)
            }
            is ApiResult.Err -> FetchOutcome.Failed(result.detail ?: result.error.userMessage)
        }
    }

    private suspend fun runAll(targets: List<Subscription>): SyncReport {
        val outcomes = supervisorScope {
            val gate = Semaphore(SubscriptionRules.SYNC_CONCURRENCY)
            targets.map { subscription ->
                async(Dispatchers.IO) {
                    gate.withPermit { subscription to fetchOne(subscription) }
                }
            }.awaitAll()
        }

        var synced = 0
        var failed = 0
        var added = 0
        var removed = 0
        var truncated = 0
        outcomes.forEach { (subscription, outcome) ->
            val result = runOne(subscription, outcome)
            applyOutcome(subscription, result)
            if (result.success) {
                synced++
                added += result.added
                removed += result.removed
                truncated += result.truncated
            } else {
                failed++
            }
        }
        return SyncReport(synced = synced, failed = failed, added = added, removed = removed, truncated = truncated)
    }

    /** 把一次抓取结果变成「替换源 + 更新状态」的动作。 */
    private fun runOne(subscription: Subscription, fetched: FetchOutcome): SyncOutcome {
        return when (fetched) {
            is FetchOutcome.Failed -> SyncOutcome(false, fetched.message)
            is FetchOutcome.Parsed -> {
                val before = gateway.customSources().size
                val removedUrls = gateway.replaceSubscriptionSources(subscription.id, fetched.sources)
                val after = gateway.customSources().size
                SyncOutcome(
                    success = true,
                    message = "已同步 " + fetched.sources.size + " 个源",
                    added = (after - before).coerceAtLeast(0),
                    removed = removedUrls.size,
                    truncated = fetched.truncated
                )
            }
        }
    }

    /**
     * 把结果写回订阅列表。
     *
     * 成功：失败计数清零、记录同步时间；
     * 失败：连续失败达到上限就自动暂停（观众可以手动同步或改周期恢复）。
     */
    private fun applyOutcome(subscription: Subscription, outcome: SyncOutcome) {
        val current = gateway.subscriptions().firstOrNull { it.id == subscription.id } ?: subscription
        val updated = if (outcome.success) {
            current.copy(
                failureCount = 0,
                status = SubscriptionStatus.ACTIVE,
                lastSyncAt = now()
            )
        } else {
            val count = current.failureCount + 1
            current.copy(
                failureCount = count,
                status = if (count >= SubscriptionRules.MAX_FAILURES) {
                    SubscriptionStatus.PAUSED
                } else {
                    current.status
                }
            )
        }
        gateway.saveSubscriptions(
            gateway.subscriptions().map { if (it.id == updated.id) updated else it }
        )
    }

    private sealed interface FetchOutcome {
        data class Parsed(val sources: List<SourceConfig>, val truncated: Int) : FetchOutcome
        data class Failed(val message: String) : FetchOutcome
    }

    private companion object {
        const val FETCH_TIMEOUT_MS = 15_000L
    }
}
