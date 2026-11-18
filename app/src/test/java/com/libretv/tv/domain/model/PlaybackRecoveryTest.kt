package com.libretv.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放失败的恢复决策。
 *
 * 保护三条规则：同源只重试一次且要退避、换源最多 3 个、换源后进度按比例
 * 接着走而不是回到片头。
 */
class PlaybackRecoveryTest {

    private fun offer(name: String) = SourceOffer(
        sourceName = name,
        sourceUrl = "http://$name.com",
        vodId = "1",
        title = TitleRef(vodId = "1", vodName = "片子"),
        quality = QualityTier.HD,
        remark = "",
        latencyMs = 120L,
        isTrailer = false
    )

    private val offers = listOf(offer("a"), offer("b"), offer("c"), offer("d"))

    @Test
    fun `network failures retry the same source once with a backoff`() {
        val action = PlaybackRecovery.plan(
            kind = PlaybackFailureKind.NETWORK,
            sameSourceRetried = false,
            offers = offers,
            triedCount = 0,
            positionMs = 60_000L,
            durationMs = 600_000L,
            episodeIndex = 0
        )

        assertTrue(action is RecoveryAction.RetrySameSource)
        assertEquals(1_500L, (action as RecoveryAction.RetrySameSource).delayMs)
    }

    @Test
    fun `after the same source retry the next offer is used`() {
        val action = PlaybackRecovery.plan(
            kind = PlaybackFailureKind.NETWORK,
            sameSourceRetried = true,
            offers = offers,
            triedCount = 0,
            positionMs = 60_000L,
            durationMs = 600_000L,
            episodeIndex = 2
        )

        assertTrue(action is RecoveryAction.SwitchSource)
        val switch = action as RecoveryAction.SwitchSource
        assertEquals("a", switch.offer.sourceName)
        assertEquals(2, switch.episodeIndex)
        assertEquals(0.1f, switch.resumeRatio, 0.001f)
    }

    @Test
    fun `at most three offers are tried`() {
        val action = PlaybackRecovery.plan(
            kind = PlaybackFailureKind.TIMEOUT,
            sameSourceRetried = true,
            offers = offers,
            triedCount = 3,
            positionMs = 0L,
            durationMs = 600_000L,
            episodeIndex = 0
        )

        assertTrue(action is RecoveryAction.GiveUp)
    }

    @Test
    fun `no offers left means the viewer gets the last word`() {
        val action = PlaybackRecovery.plan(
            kind = PlaybackFailureKind.NETWORK,
            sameSourceRetried = true,
            offers = emptyList(),
            triedCount = 0,
            positionMs = 0L,
            durationMs = 600_000L,
            episodeIndex = 0
        )

        assertTrue(action is RecoveryAction.GiveUp)
    }

    @Test
    fun `unrecoverable errors are not retried`() {
        val action = PlaybackRecovery.plan(
            kind = PlaybackFailureKind.INVALID_SOURCE,
            sameSourceRetried = false,
            offers = offers,
            triedCount = 0,
            positionMs = 0L,
            durationMs = 600_000L,
            episodeIndex = 0
        )

        assertTrue(action is RecoveryAction.GiveUp)
    }

    @Test
    fun `resume ratio is clamped to the playable range`() {
        assertEquals(0f, PlaybackRecovery.resumeRatio(0L, 0L), 0.001f)
        assertEquals(0.5f, PlaybackRecovery.resumeRatio(300_000L, 600_000L), 0.001f)
        assertEquals(1f, PlaybackRecovery.resumeRatio(900_000L, 600_000L), 0.001f)
    }

    @Test
    fun `resume position follows the ratio on the new source`() {
        assertEquals(150_000L, PlaybackRecovery.resumePositionMs(0.5f, 300_000L))
        assertEquals(0L, PlaybackRecovery.resumePositionMs(0.5f, 0L))
    }
}
