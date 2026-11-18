package com.libretv.tv.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.Collections

/**
 * 统一的扇出预算。
 *
 * 应用的每一次「同时问 N 个采集源」都受同一份预算约束：
 *
 * - 并发上限 [MAX_CONCURRENCY]：避免同时打开几十条连接把弱网环境压垮；
 * - 单项超时 [PER_ITEM_TIMEOUT_MS]：一个死源不能拖住整轮；
 * - 总预算 [TOTAL_BUDGET_MS]：整轮最多等这么久，超时返回**已拿到的部分结果**，
 *   而不是把已有结果一起丢掉。
 *
 * 失败与超时的项返回 `null`，调用方按「结果缺项」处理。
 */
object FanOut {
    const val MAX_CONCURRENCY = 6
    const val PER_ITEM_TIMEOUT_MS = 10_000L
    const val TOTAL_BUDGET_MS = 15_000L

    private const val TAG = "FanOut"

    /**
     * 并发执行 [block]，返回成功项（顺序与 [items] 一致）。
     *
     * 单项超时或抛异常都会被吞掉并记一条日志；总预算耗尽时返回已完成的项。
     *
     * 预算不是靠「到点把整轮取消」实现的——那会连已经拿到的结果一起丢掉。
     * 这里用的是共享截止时间：每一项能用的时间 = 单项上限与剩余预算的较小值，
     * 排在前面的项把预算用完时，后面的项直接判超时。
     */
    suspend fun <T, R : Any> map(
        items: List<T>,
        perItemTimeoutMs: Long = PER_ITEM_TIMEOUT_MS,
        totalBudgetMs: Long = TOTAL_BUDGET_MS,
        maxConcurrency: Int = MAX_CONCURRENCY,
        block: suspend (T) -> R?
    ): List<R> {
        if (items.isEmpty()) return emptyList()

        val deadlineNanos = System.nanoTime() + totalBudgetMs * 1_000_000L
        // 已完成的结果单独记在这里，保证输出顺序与输入一致。
        val collected = Collections.synchronizedList(mutableListOf<Pair<Int, R>>())

        supervisorScope {
            val gate = Semaphore(maxConcurrency)
            items.mapIndexed { index, item ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L
                        if (remainingMs <= 0L) {
                            Logger.w(TAG, "总预算已耗尽，跳过：${label(item)}")
                            return@withPermit null
                        }
                        val budgetMs = minOf(perItemTimeoutMs, remainingMs)
                        val value = try {
                            withTimeout(budgetMs) { block(item) }
                        } catch (e: TimeoutCancellationException) {
                            Logger.w(TAG, "单项超时（${budgetMs}ms）：${label(item)}")
                            null
                        } catch (e: CancellationException) {
                            // 调用方取消整轮时不要吞掉。
                            throw e
                        } catch (e: Exception) {
                            Logger.w(TAG, "单项失败：${label(item)}", e)
                            null
                        }
                        if (value != null) collected += index to value
                        value
                    }
                }
            }.awaitAll()
        }

        return synchronized(collected) { collected.toList() }
            .sortedBy { it.first }
            .map { it.second }
    }

    /**
     * 并发执行 [block]，每完成一项就回调一次 [onEach]。
     *
     * 与 [map] 的区别是**增量**：回调在每一项完成时立刻触发，而不是等整轮结束。
     * 首页的分类片单需要这个特性——第一个采集源回来就能上屏，用户不用等
     * 最慢的那个。
     *
     * 回调按**完成顺序**触发（谁先回来先上屏），调用方不能假设输入顺序。
     */
    suspend fun <T, R : Any> forEach(
        items: List<T>,
        perItemTimeoutMs: Long = PER_ITEM_TIMEOUT_MS,
        totalBudgetMs: Long = TOTAL_BUDGET_MS,
        maxConcurrency: Int = MAX_CONCURRENCY,
        onEach: suspend (R) -> Unit,
        block: suspend (T) -> R?
    ) {
        if (items.isEmpty()) return
        val deadlineNanos = System.nanoTime() + totalBudgetMs * 1_000_000L

        supervisorScope {
            val gate = Semaphore(maxConcurrency)
            val channel = Channel<R>(Channel.UNLIMITED)
            val jobs = items.map { item ->
                async(Dispatchers.IO) {
                    val value = gate.withPermit {
                        val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L
                        if (remainingMs <= 0L) {
                            Logger.w(TAG, "总预算已耗尽，跳过：${label(item)}")
                            return@withPermit null
                        }
                        val budgetMs = minOf(perItemTimeoutMs, remainingMs)
                        try {
                            withTimeout(budgetMs) { block(item) }
                        } catch (e: TimeoutCancellationException) {
                            Logger.w(TAG, "单项超时（${budgetMs}ms）：${label(item)}")
                            null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Logger.w(TAG, "单项失败：${label(item)}", e)
                            null
                        }
                    }
                    if (value != null) channel.send(value)
                }
            }
            val closer = launch {
                jobs.awaitAll()
                channel.close()
            }
            try {
                for (value in channel) onEach(value)
            } finally {
                closer.cancel()
                channel.close()
                jobs.forEach { it.cancel() }
            }
        }
    }

    private fun label(item: Any?): String = item?.toString()?.take(120) ?: "?"
}
