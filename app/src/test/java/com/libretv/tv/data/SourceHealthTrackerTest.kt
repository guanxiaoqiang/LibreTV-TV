package com.libretv.tv.data

import com.libretv.tv.core.SourceError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the tiered-cooldown source health state machine. */
class SourceHealthTrackerTest {

    private val now = 1_000_000L

    @Test
    fun `success resets the tier`() {
        val broken = SourceHealth(
            reachable = false,
            state = HealthState.COOLDOWN,
            failureTier = 2,
            cooldownUntil = now + 1000
        )
        val healthy = SourceHealthTracker.onSuccess(broken)
        assertEquals(HealthState.HEALTHY, healthy.state)
        assertEquals(0, healthy.failureTier)
        assertEquals(0L, healthy.cooldownUntil)
    }

    @Test
    fun `one failure starts a 30 minute cooldown`() {
        val next = SourceHealthTracker.onFailure(SourceHealth(reachable = true), SourceError.Timeout, now)
        assertEquals(1, next.failureTier)
        assertEquals(HealthState.COOLDOWN, next.state)
        assertEquals(CooldownTiers.TIER_1_MS, next.cooldownUntil - now)
    }

    @Test
    fun `a second failure escalates to 24 hours`() {
        val first = SourceHealthTracker.onFailure(SourceHealth(reachable = true), SourceError.Timeout, now)
        val second = SourceHealthTracker.onFailure(first, SourceError.Timeout, now)
        assertEquals(2, second.failureTier)
        assertEquals(CooldownTiers.TIER_2_MS, second.cooldownUntil - now)
    }

    @Test
    fun `a third failure suspends the source`() {
        var state = SourceHealth(reachable = true)
        repeat(3) { state = SourceHealthTracker.onFailure(state, SourceError.Unreachable, now) }
        assertEquals(3, state.failureTier)
        assertEquals(HealthState.SUSPENDED, state.state)
        assertTrue(state.isSkippable(now))
    }

    @Test
    fun `tier does not escalate past suspension`() {
        var state = SourceHealth(reachable = true)
        repeat(5) { state = SourceHealthTracker.onFailure(state, SourceError.Unreachable, now) }
        assertEquals(3, state.failureTier)
        assertEquals(HealthState.SUSPENDED, state.state)
    }

    @Test
    fun `an empty result is not a failure`() {
        val next = SourceHealthTracker.onFailure(SourceHealth(reachable = true), SourceError.EmptyResult, now)
        assertEquals(HealthState.HEALTHY, next.state)
        assertEquals(0, next.failureTier)
    }

    @Test
    fun `a missing error is not a failure either`() {
        val next = SourceHealthTracker.onFailure(SourceHealth(reachable = true), null, now)
        assertEquals(0, next.failureTier)
    }

    @Test
    fun `cooldown expires into a half-open state`() {
        val cooled = SourceHealth(
            reachable = false,
            state = HealthState.COOLDOWN,
            failureTier = 1,
            cooldownUntil = now - 1
        )
        assertFalse("冷却到期后不再跳过，允许一次半开探测", cooled.isSkippable(now))
    }

    @Test
    fun `half-open success recovers, half-open failure escalates`() {
        val cooled = SourceHealth(
            reachable = false,
            state = HealthState.COOLDOWN,
            failureTier = 1,
            cooldownUntil = now - 1
        )

        val recovered = SourceHealthTracker.onHalfOpen(cooled, succeeded = true, now = now)
        assertEquals(HealthState.HEALTHY, recovered.state)
        assertEquals(0, recovered.failureTier)

        val escalated = SourceHealthTracker.onHalfOpen(cooled, succeeded = false, now = now)
        assertEquals(2, escalated.failureTier)
        assertEquals(HealthState.COOLDOWN, escalated.state)
    }

    @Test
    fun `manual reset clears everything`() {
        val suspended = SourceHealth(reachable = false, state = HealthState.SUSPENDED, failureTier = 3)
        val reset = SourceHealthTracker.onManualReset(suspended)
        assertEquals(HealthState.HEALTHY, reset.state)
        assertEquals(0, reset.failureTier)
    }

    @Test
    fun `skippable respects each state`() {
        val now = System.currentTimeMillis()
        assertTrue(
            SourceHealth(
                reachable = false,
                state = HealthState.SUSPENDED,
                cooldownUntil = Long.MAX_VALUE / 2
            ).isSkippable(now)
        )
        assertTrue(
            SourceHealth(
                reachable = false,
                state = HealthState.COOLDOWN,
                cooldownUntil = now + 1000
            ).isSkippable(now)
        )
        assertFalse(
            SourceHealth(
                reachable = false,
                state = HealthState.COOLDOWN,
                cooldownUntil = now - 1000
            ).isSkippable(now)
        )
        assertFalse(SourceHealth(reachable = true, state = HealthState.HEALTHY).isSkippable(now))
    }
}
