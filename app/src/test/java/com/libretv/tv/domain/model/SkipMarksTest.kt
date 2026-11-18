package com.libretv.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跳过标记的规则。
 *
 * 这里保护的是观众的三个直觉：标记一次之后下次还认得（跨集复用）、撤销
 * 之后立刻不跳、广告过滤和用户标记同时存在时广告过滤说了算。
 */
class SkipMarksTest {

    private val intro = SkipSegment(SkipKind.INTRO, 0L, 90_000L)
    private val outro = SkipSegment(SkipKind.OUTRO, 2_700_000L, 3_000_000L)

    @Test
    fun `range matching is half open`() {
        assertEquals(intro, SkipMarks.match(0L, listOf(intro)))
        assertEquals(intro, SkipMarks.match(89_999L, listOf(intro)))
        // 结束点本身已经不在区间里：跳过去之后不能再跳一次。
        assertNull(SkipMarks.match(90_000L, listOf(intro)))
    }

    @Test
    fun `mark key is the normalized title so it carries over across sources`() {
        val key = SkipMarks.keyOf("剧名 第01集")

        // 空白、全半角括号与大小写都被抹平：同一部片子的标记能继承。
        assertEquals(SkipMarks.keyOf("剧名　第01集"), key)
        assertEquals(SkipMarks.keyOf("剧名（第01集）"), SkipMarks.keyOf("剧名(第01集)"))
        assertEquals(SkipMarks.keyOf("MOVIE"), SkipMarks.keyOf("movie"))
    }

    @Test
    fun `marking the same kind replaces the old range`() {
        val mark = SkipMark(titleKey = "k", title = "片")
        val first = SkipMarks.upsert(mark, SkipMarks.markIntro(60_000L), 1L)
        val second = SkipMarks.upsert(first, SkipMarks.markIntro(120_000L), 2L)

        assertEquals(1, second.segments.size)
        assertEquals(120_000L, second.segments.first().endMs)
    }

    @Test
    fun `undo removes only the chosen range`() {
        val mark = SkipMark(titleKey = "k", segments = listOf(intro, outro))
        val after = SkipMarks.remove(mark, SkipKind.INTRO, 2L)

        assertEquals(1, after.segments.size)
        assertEquals(SkipKind.OUTRO, after.segments.first().kind)
    }

    @Test
    fun `clearing removes every range`() {
        val mark = SkipMark(titleKey = "k", segments = listOf(intro, outro))
        assertTrue(SkipMarks.clear(mark, 2L).segments.isEmpty())
    }

    @Test
    fun `boundary adjustment never inverts the range`() {
        val adjusted = SkipMarks.adjust(intro, SkipEdge.END, -120_000L)
        // 终点被拉到起点之前：保持原值，宁可不动也不要负长度。
        assertEquals(intro, adjusted)

        val moved = SkipMarks.adjust(intro, SkipEdge.END, 5_000L)
        assertEquals(95_000L, moved.endMs)
    }

    @Test
    fun `auto skip jumps to the end of the range`() {
        val decision = SkipMarks.decide(true, 30_000L, listOf(intro))

        assertTrue(decision is SkipDecision.SkipTo)
        assertEquals(90_000L, (decision as SkipDecision.SkipTo).targetMs)
    }

    @Test
    fun `auto skip does nothing when the switch is off`() {
        val decision = SkipMarks.decide(false, 30_000L, listOf(intro))

        assertEquals(SkipDecision.Disabled, decision)
    }

    @Test
    fun `auto skip does nothing outside any range`() {
        val decision = SkipMarks.decide(true, 500_000L, listOf(intro, outro))

        assertEquals(SkipDecision.NoMatch, decision)
    }

    @Test
    fun `ad filter wins over user marks on the same area`() {
        val ad = SkipSegment(SkipKind.MIDDLE, 60_000L, 120_000L)

        // 播放头在广告区间里：交给广告过滤。
        assertEquals(SkipDecision.AdFilterWins, SkipMarks.decide(true, 90_000L, listOf(intro), listOf(ad)))
        // 用户区间与广告区间重叠的部分被扣掉，不会把播放头推过广告边界。
        val decision = SkipMarks.decide(true, 30_000L, listOf(intro), listOf(ad))
        assertTrue(decision is SkipDecision.SkipTo)
        assertEquals(60_000L, (decision as SkipDecision.SkipTo).targetMs)
    }

    @Test
    fun `subtracting ads keeps the parts outside the ad range`() {
        val pieces = SkipMarks.subtract(intro, listOf(SkipSegment(SkipKind.MIDDLE, 30_000L, 60_000L)))

        assertEquals(2, pieces.size)
        assertEquals(0L, pieces[0].startMs)
        assertEquals(30_000L, pieces[0].endMs)
        assertEquals(60_000L, pieces[1].startMs)
        assertEquals(90_000L, pieces[1].endMs)
    }
}
