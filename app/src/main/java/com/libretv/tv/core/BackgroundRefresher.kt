package com.libretv.tv.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 应用内后台刷新调度器（local-first-storage 3.4 / 3.5）。
 *
 * 规则：
 *  1. 按 kind 去重——同一时刻同一种类只允许一个刷新任务，切页签不堆叠；
 *  2. 失败不降级——刷新失败保留旧缓存，只用[onFailure]上报健康度；
 *  3. 配额隔离——后台任务与用户请求分属不同并发额度；用户请求期间
 *     [userActive] 为 true 时后台任务主动让路（暂停等待）。
 *
 * 纯 Kotlin 协程实现，无 Android 依赖，可 JVM 单测。
 */
class BackgroundRefresher(
    private val scope: CoroutineScope,
    private val userActive: () -> Boolean = { false },
    private val yieldDelayMs: Long = 10L
) {

    private val mutex = Mutex()
    private val running = mutableMapOf<String, Job>()

    /** 当前正在刷新的种类（供界面显示角标 / 单测断言）。 */
    suspend fun runningKinds(): Set<String> = mutex.withLock { running.keys.toSet() }

    /** 某类是否正在刷新。 */
    suspend fun isRunning(kind: String): Boolean = mutex.withLock { running.containsKey(kind) }

    /**
     * 调度一次刷新；若该 kind 已有任务在跑则忽略（去重）。
     *
     * @return true 表示新任务已启动；false 表示被去重。
     */
    suspend fun schedule(
        kind: String,
        onRefresh: suspend () -> Unit,
        onFailure: (Throwable) -> Unit = {}
    ): Boolean = mutex.withLock {
        if (running.containsKey(kind)) return@withLock false
        val job = scope.launch {
            // 用户请求期间让路：等到前台空闲再跑。
            while (userActive()) delay(yieldDelayMs)
            runCatching { onRefresh() }
                .onFailure(onFailure)
            mutex.withLock { running.remove(kind) }
        }
        running[kind] = job
        true
    }

    /** 取消某类的刷新（例如强制刷新时重来）。 */
    suspend fun cancel(kind: String) {
        mutex.withLock { running.remove(kind)?.cancel() }
    }

    /** 取消全部。 */
    suspend fun cancelAll() {
        mutex.withLock {
            running.values.forEach { it.cancel() }
            running.clear()
        }
    }
}

/**
 * 后台采集配额（3.5 / content-catalog D7）：并发与每来源速率上限，
 * 并与用户请求隔离。纯逻辑，便于单测验证「超限被拒」。
 */
class HarvestQuota(
    private val maxConcurrent: Int = 2,
    private val perSourcePerHour: Int = 20
) {
    private var inFlight = 0
    private val sourceCounts = mutableMapOf<String, MutableList<Long>>()

    /** 用户请求是否正在进行；为 true 时后台采集让路。 */
    @Volatile
    var userActive: Boolean = false

    /** 后台采集总开关（设置项，7.2）；关闭后拒绝一切采集请求。 */
    @Volatile
    var harvestEnabled: Boolean = true

    /** 能否发起一个后台请求。 */
    fun canAcquire(sourceUrl: String, now: Long): Boolean {
        if (!harvestEnabled) return false
        if (userActive) return false
        if (inFlight >= maxConcurrent) return false
        val times = sourceCounts.getOrPut(sourceUrl) { mutableListOf() }
        times.removeAll { now - it >= HOUR_MS }
        return times.size < perSourcePerHour
    }

    /** 占用一个额度。 */
    fun acquire(sourceUrl: String, now: Long) {
        inFlight++
        sourceCounts.getOrPut(sourceUrl) { mutableListOf() }.add(now)
    }

    /** 释放一个并发额度。 */
    fun release() {
        if (inFlight > 0) inFlight--
    }

    companion object {
        private const val HOUR_MS = 3_600_000L
    }
}
