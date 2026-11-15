package com.libretv.tv.data

import kotlin.math.abs
import kotlin.math.max

/**
 * Pure-text HLS ad-segment filter, ported from LibreTV's `m3u8.ts`.
 *
 * The desktop app injects it into HLS.js through a custom loader that rewrites
 * manifest / level responses on success. On Android the equivalent hook is a
 * custom HlsPlaylistParserFactory, so the rewrite itself stays a dependency-free
 * function that can be unit tested against real playlist shapes.
 *
 * Four passes, in this exact order (later passes see the output of earlier ones):
 *
 *  1. [stripMarkedAdGroups]       segments whose URLs are all ad-like
 *  2. [stripInterstitialAdGroups] a tiny group sandwiched between two long ones
 *  3. [stripLeadAdGroup]          a short group inserted before the content
 *  4. [stripLeadingDisc]          DISCONTINUITY tags left before the first segment
 *
 * The DISCONTINUITY timeline of the real content is deliberately preserved.
 * Deleting those tags makes ExoPlayer treat independent PTS bases as one
 * continuous stream, which produces dropped frames and audio drift. That is the
 * single most important invariant here.
 */
object M3u8AdFilter {

    /** Matches `adjump`, `advert`, `/ad/` and `/ads/` (case-insensitive). */
    private val AD_URL = Regex("""adjump|advert|/ads?/""", RegexOption.IGNORE_CASE)

    private const val LEAD_AD_MAX_SECONDS = 90.0
    private const val LEAD_AD_MAX_SEGMENTS = 20
    private const val INTERSTITIAL_MAX_SECONDS = 30.0
    private const val INTERSTITIAL_NEIGHBOR_MIN_SECONDS = 120.0
    private const val HOMOGENEOUS_MIN_DISCS = 4

    data class Result(val playlist: String, val removedSegments: Int)

    fun stripAdGroups(content: String): String = stripAdGroupsWithStats(content).playlist

    fun stripAdGroupsWithStats(content: String): Result {
        if (content.isEmpty()) return Result(content, 0)
        // A BOM would make the default parser miss #EXTM3U.
        val text = content.removePrefix("\uFEFF")
        if (!text.contains("#EXT-X-DISCONTINUITY")) return Result(text, 0)

        var current = text
        var removed = 0
        val passes = listOf(
            ::stripMarkedAdGroups,
            ::stripInterstitialAdGroups,
            ::stripLeadAdGroup,
            ::stripLeadingDisc
        )
        for (pass in passes) {
            val (next, count) = pass(current)
            current = next
            removed += count
        }
        return Result(current, removed)
    }

    private fun isDisc(line: String): Boolean = line.trim() == "#EXT-X-DISCONTINUITY"

    /** A playable segment URL (or a nested playlist URI), not a comment/tag. */
    private fun isSegment(line: String): Boolean {
        val value = line.trim()
        return value.isNotEmpty() && !value.startsWith("#")
    }

    /**
     * These must survive even when the rest of the group is removed: a later
     * segment may reuse the AES key / IV or the fMP4 init segment declared here.
     */
    private fun isKeepTag(line: String): Boolean {
        val value = line.trim()
        return value.startsWith("#EXT-X-KEY") || value.startsWith("#EXT-X-MAP")
    }

    private fun extinfSeconds(line: String): Double =
        Regex("""^#EXTINF:([\d.]+)""").find(line.trim())
            ?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    private fun splitLines(text: String): List<String> = text.split(Regex("""\r?\n"""))

    private fun applyDrop(lines: List<String>, drop: BooleanArray): String =
        lines.filterIndexed { index, _ -> !drop[index] }.joinToString("\n")

    // ---------------------------------------------------------------- pass 1

    /**
     * Remove any group whose segment URLs *all* look like ads. Requiring every
     * URL to match is intentional: one real segment mixed in spares the whole
     * group, which is safer than guessing from durations.
     *
     * Only the DISCONTINUITY that opens the ad group is dropped. The following
     * one stays as the boundary of the real content, so no double tag appears.
     */
    private fun stripMarkedAdGroups(text: String): Pair<String, Int> {
        val lines = splitLines(text)
        val discs = lines.indices.filter { isDisc(lines[it]) }
        if (discs.isEmpty()) return text to 0

        val drop = BooleanArray(lines.size)
        var removed = 0
        for (cursor in discs.indices) {
            val start = discs[cursor]
            val end = if (cursor + 1 < discs.size) discs[cursor + 1] else lines.size
            val segments = (start + 1 until end).filter { isSegment(lines[it]) }
            if (segments.isEmpty() || !segments.all { AD_URL.containsMatchIn(lines[it]) }) continue
            drop[start] = true
            for (i in (start + 1) until end) {
                if (!isKeepTag(lines[i])) drop[i] = true
            }
            removed += segments.size
        }
        return if (removed == 0) text to 0 else applyDrop(lines, drop) to removed
    }

    // ---------------------------------------------------------------- pass 2

    private data class Group(
        val start: Int,
        val end: Int,
        val discLine: Int,
        val seconds: Double,
        val count: Int
    )

    /** Split by DISCONTINUITY, keeping the tag line that opened each group. */
    private fun scanGroups(lines: List<String>): List<Group> {
        val discs = lines.indices.filter { isDisc(lines[it]) }
        val groups = mutableListOf<Group>()
        var start = 0
        var discLine = -1
        for (disc in discs) {
            groups.add(makeGroup(lines, start, disc, discLine))
            start = disc + 1
            discLine = disc
        }
        groups.add(makeGroup(lines, start, lines.size, discLine))
        return groups
    }

    private fun makeGroup(lines: List<String>, start: Int, end: Int, discLine: Int): Group {
        var seconds = 0.0
        var count = 0
        for (i in start until end) {
            seconds += extinfSeconds(lines[i])
            if (isSegment(lines[i])) count++
        }
        return Group(start, end, discLine, seconds, count)
    }

    /**
     * "Two long pieces with a very short one in between": the classic mid-roll
     * injection with no ad URL to detect. Groups are evaluated against the
     * *original* playlist in one pass, never cascaded. A trailing short group
     * has no following neighbour and is left alone.
     */
    private fun stripInterstitialAdGroups(text: String): Pair<String, Int> {
        val lines = splitLines(text)
        val discs = lines.indices.filter { isDisc(lines[it]) }
        if (discs.size < 2) return text to 0

        val groups = scanGroups(lines)
        val drop = BooleanArray(lines.size)
        var removed = 0
        for (k in 1 until groups.size - 1) {
            val group = groups[k]
            if (group.discLine < 0 || group.count == 0) continue
            val prev = groups[k - 1]
            val next = groups[k + 1]
            if (group.seconds > 0.0 &&
                group.seconds <= INTERSTITIAL_MAX_SECONDS &&
                prev.seconds >= INTERSTITIAL_NEIGHBOR_MIN_SECONDS &&
                next.seconds >= INTERSTITIAL_NEIGHBOR_MIN_SECONDS
            ) {
                drop[group.discLine] = true
                for (i in group.start until group.end) {
                    if (!isKeepTag(lines[i])) drop[i] = true
                }
                removed += group.count
            }
        }
        return if (removed == 0) text to 0 else applyDrop(lines, drop) to removed
    }

    // ---------------------------------------------------------------- pass 3

    /**
     * Pre-roll insertion with no URL signature. Only fires when:
     *  - there are at least two DISCONTINUITY tags,
     *  - no content segment precedes the first one,
     *  - the candidate group is 1..20 segments and at most 90 seconds,
     *  - it is *not* structurally similar to the other groups.
     *
     * The homogeneity guard matters: some collectors wrap every 5 segments in a
     * DISCONTINUITY. Without it a completely normal first group would be read
     * as an ad and deleted.
     */
    private fun stripLeadAdGroup(text: String): Pair<String, Int> {
        val lines = splitLines(text)
        val discs = lines.indices.filter { isDisc(lines[it]) }
        if (discs.size < 2) return text to 0

        val firstDisc = discs[0]
        if ((0 until firstDisc).any { isSegment(lines[it]) }) return text to 0
        val nextDisc = discs[1]

        if (discs.size >= HOMOGENEOUS_MIN_DISCS) {
            val groups = scanGroups(lines)
            if (groups.size >= 3) {
                val leadSize = groups[1].count
                val others = groups.drop(2).map { it.count }.sorted()
                if (others.isNotEmpty()) {
                    val median = others[others.size / 2]
                    if (abs(leadSize - median) <= max(3.0, median * 0.5)) {
                        return text to 0
                    }
                }
            }
        }

        var seconds = 0.0
        var count = 0
        for (i in (firstDisc + 1) until nextDisc) {
            seconds += extinfSeconds(lines[i])
            if (isSegment(lines[i])) count++
        }
        if (count == 0 || count > LEAD_AD_MAX_SEGMENTS ||
            seconds <= 0.0 || seconds > LEAD_AD_MAX_SECONDS
        ) return text to 0

        // Keep everything outside the ad group, plus KEY / MAP inside it. The
        // second DISCONTINUITY is still in `kept`; the loop below removes it if
        // it now sits before the first real segment and has lost its meaning.
        val kept = lines.filterIndexed { index, line ->
            index < firstDisc || index >= nextDisc || isKeepTag(line)
        }
        val out = mutableListOf<String>()
        var seenSegment = false
        for (line in kept) {
            if (!seenSegment && isDisc(line)) continue
            if (isSegment(line)) seenSegment = true
            out += line
        }
        return out.joinToString("\n") to count
    }

    // ---------------------------------------------------------------- pass 4

    /**
     * A DISCONTINUITY before the first media segment has no meaning (the HLS
     * discontinuity counter starts at zero). This is also the safety net for
     * tags orphaned by the previous passes.
     */
    private fun stripLeadingDisc(text: String): Pair<String, Int> {
        val lines = splitLines(text)
        val firstSegment = lines.indexOfFirst { isSegment(it) }
        if (firstSegment <= 0) return text to 0
        var dropped = false
        val out = lines.filterIndexed { index, line ->
            if (index < firstSegment && isDisc(line)) {
                dropped = true
                false
            } else true
        }
        return if (dropped) out.joinToString("\n") to 0 else text to 0
    }
}
