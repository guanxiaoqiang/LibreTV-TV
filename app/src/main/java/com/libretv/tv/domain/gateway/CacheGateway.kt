package com.libretv.tv.domain.gateway

import com.libretv.tv.domain.model.CacheResult

/**
 * 云端响应缓存的存取契约（local-first-storage 1.3）。
 *
 * 领域层纯接口。持久化技术（Room）只在 data 层实现；缓存载荷以 JSON 文本
 * 存取，反序列化交给调用方，保持领域层不依赖具体序列化细节。
 */
interface CacheGateway {

    /**
     * 读取一份缓存载荷。
     *
     * @param kind 数据种类（feed / category / detail / search / probe ...）
     * @param key 缓存键（可含来源签名）
     * @param now 当前时间，便于纯 JVM 单测注入
     * @return 三态结果；无缓存时 [CacheResult.Missing]
     */
    suspend fun read(
        kind: String,
        key: String,
        now: Long = System.currentTimeMillis()
    ): CacheResult<String>

    /** 写入一份载荷。 */
    suspend fun write(
        kind: String,
        key: String,
        payload: String,
        savedAt: Long = System.currentTimeMillis(),
        softTtlMs: Long,
        hardTtlMs: Long,
        version: Int,
        sourceSignature: String
    )

    /** 前缀型失效：删除 kind 下 key 以 [prefix] 开头的缓存。返回删除行数。 */
    suspend fun invalidate(kind: String, prefix: String): Int

    /** 版本型失效：删除所有版本不等于 [currentVersion] 的行。 */
    suspend fun invalidateByVersion(currentVersion: Int): Int

    /** 全部失效（缓存模型升级 / 手动清理），不影响历史与收藏。 */
    suspend fun clearAll()

    /** 清理过期行（硬过期）并按保存时间淘汰到总量上限以下。 */
    suspend fun prune(now: Long = System.currentTimeMillis()): Int

    /** 估算缓存占用字节数（设置页展示）。 */
    suspend fun sizeBytes(): Long
}
