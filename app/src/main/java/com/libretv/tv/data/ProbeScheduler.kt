package com.libretv.tv.data

import com.libretv.tv.core.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 探测调度器：全局并发上限 + 优先级队列 + 结果缓存。
 *
 * 探测（读详情、数首字节、读主清单）是最容易被误用的一类请求——选源页一打开
 * 就会同时问十几个源，而用户真正想看的那一个往往排在最后。这里给所有探测
 * 收敛到一条通道：
 *
 * - **并发上限**：同时最多 [maxConcurrent] 个探测在跑；
 * - **优先级**：数字越大越先执行，用户主动触发的探测排在后台补采前面；
 * - **结果缓存**：同一 key 的结果在 TTL 内复用，避免重复请求同一份详情；
 * - **在途去重**：同一个 key 正在探测时，后续请求等待同一个结果而不是再发一次。
 *
 * 槽位的交接不靠「把令牌塞给某个等待者」，而是**唤醒所有等待者重新竞争**：
 * 这样即使某个等待者在交接的瞬间被取消，槽位也不会凭空消失（那个等待者
 * 醒来后发现自己抢不到，就会重新排队）。
 */
class ProbeScheduler(
    private val maxConcurrent: Int = DEFAULT_CONCURRENCY,
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS
) {
    private class CacheEntry(val value: Any, val expiresAt: Long)

    private class Waiter(
        val priority: Int,
        val sequence: Long
    ) {
        /** 唤醒信号：完成即表示槽位已预留给本等待者。 */
        val signal = CompletableDeferred<Unit>()
    }

    private val lock = Mutex()
    private val pending = mutableListOf<Waiter>()
    private val sequence = AtomicLong(0L)
    private var active = 0

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Any?>>()

    /**
     * 提交一次探测。
     *
     * @param key 去重与缓存的键，通常是 `来源|影片ID`。
     * @param priority 越大越优先。
     * @return 探测结果；失败、超时或被取消时返回 `null`。
     */
    suspend fun <T : Any> submit(
        key: String,
        priority: Int = 0,
        block: suspend () -> T?
    ): T? {
        cached(key)?.let { return it as? T }

        val signal = CompletableDeferred<Any?>()
        val existing = inFlight.putIfAbsent(key, signal)
        if (existing != null) {
            // 同一个目标正在探测，直接等它的结果。
            return existing.await() as? T
        }

        var acquired = false
        try {
            acquire(priority)
            acquired = true
            val value = try {
                block()
            } catch (e: CancellationException) {
                signal.complete(null)
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "探测失败：$key", e)
                null
            }
            if (value != null) {
                cache[key] = CacheEntry(value, System.currentTimeMillis() + cacheTtlMs)
            }
            signal.complete(value)
            return value
        } catch (e: CancellationException) {
            // 排队期间被取消：放掉在途标记，别让等待者一直挂着。
            signal.complete(null)
            throw e
        } finally {
            if (acquired) release()
            inFlight.remove(key)
        }
    }

    /** 让某个键的缓存立即失效。 */
    fun invalidate(key: String) {
        cache.remove(key)
    }

    /** 清空全部缓存。 */
    fun clear() {
        cache.clear()
    }

    /** 当前正在运行的探测数。 */
    fun activeCount(): Int = active

    /** 当前排队等待的探测数。 */
    fun pendingCount(): Int = pending.size

    private fun cached(key: String): Any? {
        val entry = cache[key] ?: return null
        if (entry.expiresAt <= System.currentTimeMillis()) {
            cache.remove(key)
            return null
        }
        return entry.value
    }

    /**
     * 占一个槽位。
     *
     * 有空位且没有更高优先级的等待者时直接占用；否则排队等待。
     * 被唤醒即表示槽位已经**预留给它**，此时即使它被取消，也会把槽位还回去。
     */
    private suspend fun acquire(priority: Int) {
        val waiter = Waiter(priority, sequence.getAndIncrement())
        val granted = lock.withLock {
            val higherPending = pending.any { it.priority > waiter.priority }
            if (active < maxConcurrent && !higherPending) {
                active++
                true
            } else {
                pending.add(waiter)
                false
            }
        }
        if (granted) return

        try {
            waiter.signal.await()
        } catch (e: CancellationException) {
            // 排队中被取消：如果槽位已经预留给它，就得还回去；
            // `complete` 返回 false 说明是被别人（release）唤醒的，也就是预留成立。
            val reserved = !waiter.signal.complete(Unit)
            lock.withLock { pending.remove(waiter) }
            if (reserved) release()
            throw e
        }
    }

    /**
     * 归还槽位。
     *
     * 槽位优先交给排队中优先级最高的等待者；交接时如果那个等待者刚好被取消，
     * 槽位会继续往下传，不会凭空消失。
     */
    private suspend fun release() {
        while (true) {
            val picked = lock.withLock {
                active--
                // 已经被取消的等待者直接丢弃。
                pending.removeAll { it.signal.isCompleted }
                val best = pending.maxWithOrNull(
                    compareBy<Waiter> { it.priority }.thenByDescending { it.sequence }
                )
                if (best != null) {
                    pending.remove(best)
                    // 槽位预留给被唤醒的等待者。
                    active++
                }
                best
            } ?: return

            // complete 返回 false = 它在交接前就被取消了，槽位继续传下去。
            if (picked.signal.complete(Unit)) return
            lock.withLock { active-- }
        }
    }

    companion object {
        private const val TAG = "ProbeScheduler"

        /** 同时最多两个探测在跑：再多会挤占播放请求的连接。 */
        const val DEFAULT_CONCURRENCY = 2

        /** 探测结果缓存 10 分钟。 */
        const val DEFAULT_CACHE_TTL_MS = 10 * 60 * 1000L
    }
}
