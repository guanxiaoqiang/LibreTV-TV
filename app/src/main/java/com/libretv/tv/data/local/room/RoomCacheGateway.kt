package com.libretv.tv.data.local.room

import com.libretv.tv.domain.gateway.CacheGateway
import com.libretv.tv.domain.model.CacheFreshness
import com.libretv.tv.domain.model.CacheOrigin
import com.libretv.tv.domain.model.CacheResult

/**
 * 基于 Room 的缓存网关（local-first-storage 3.x）。
 *
 * 三态判定是纯领域逻辑（[com.libretv.tv.domain.model.CacheFreshness]），这里
 * 只负责取行、把元数据交给判定、再组装三态结果。载荷以 JSON 文本存取，
 * 反序列化交给调用方，保持领域层不依赖持久化技术。
 */
class RoomCacheGateway(
    private val db: LibreTvDatabase,
    private val maxRows: Int = 2000
) : CacheGateway {

    override suspend fun read(
        kind: String,
        key: String,
        now: Long
    ): CacheResult<String> {
        val row = db.httpCacheDao().get(kind, key) ?: return CacheResult.Missing
        return when (val verdict = CacheFreshness.judge(row.expiresAt, row.hardExpiresAt, now)) {
            is CacheFreshness.Verdict.Fresh ->
                CacheResult.Fresh(row.payload, CacheOrigin.DISK)
            is CacheFreshness.Verdict.Stale ->
                CacheResult.Stale(row.payload, refreshing = false)
            is CacheFreshness.Verdict.Expired ->
                CacheResult.Expired(row.payload, verdict.ageMs)
        }
    }

    override suspend fun write(
        kind: String,
        key: String,
        payload: String,
        savedAt: Long,
        softTtlMs: Long,
        hardTtlMs: Long,
        version: Int,
        sourceSignature: String
    ) {
        db.httpCacheDao().upsert(
            HttpCacheEntity(
                kind = kind,
                cacheKey = key,
                payload = payload,
                savedAt = savedAt,
                expiresAt = savedAt + softTtlMs,
                hardExpiresAt = savedAt + hardTtlMs,
                version = version,
                sourceSignature = sourceSignature,
                sizeBytes = payload.toByteArray().size
            )
        )
    }

    override suspend fun invalidate(kind: String, prefix: String): Int =
        db.httpCacheDao().invalidatePrefix(kind, prefix)

    override suspend fun invalidateByVersion(currentVersion: Int): Int =
        db.httpCacheDao().invalidateByVersion(currentVersion)

    override suspend fun clearAll() = db.httpCacheDao().clearAll()

    override suspend fun prune(now: Long): Int {
        val expired = db.httpCacheDao().deleteExpired(now)
        val trimmed = db.httpCacheDao().trimTo(maxRows)
        return expired + trimmed
    }

    override suspend fun sizeBytes(): Long = db.httpCacheDao().sizeBytes()
}
