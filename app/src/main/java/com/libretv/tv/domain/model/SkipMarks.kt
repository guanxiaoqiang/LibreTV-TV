package com.libretv.tv.domain.model

import kotlinx.serialization.Serializable

/**
 * 跳过标记：片头 / 中段 / 片尾的区间（design D6）。
 *
 * 键是**归一化片名**，不是「来源 + 影片 id」——按后者标记的话，换个源或
 * 换一集就失效了，观众得反复标。按归一化片名标记则能跨源跨集继承。
 */
@Serializable
data class SkipMark(
    /** 归一化片名（唯一键）。 */
    val titleKey: String,
    /** 展示用片名。 */
    val title: String = "",
    /** 标记的区间列表。 */
    val segments: List<SkipSegment> = emptyList(),
    /** 最后修改时间（毫秒）。 */
    val updatedAt: Long = 0L
)

/** 跳过区间的类型。 */
@Serializable
enum class SkipKind {
    /** 片头：区间从 0 开始。 */
    INTRO,

    /** 中段广告 / 插播。 */
    MIDDLE,

    /** 片尾：区间到结尾。 */
    OUTRO
}

/** 一段要跳过的时间区间（毫秒，左闭右开）。 */
@Serializable
data class SkipSegment(
    val kind: SkipKind = SkipKind.MIDDLE,
    val startMs: Long = 0L,
    val endMs: Long = 0L
) {
    /** 区间长度。 */
    val lengthMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

    /** 播放位置是否落在区间内。 */
    fun contains(positionMs: Long): Boolean = positionMs >= startMs && positionMs < endMs
}

/** 自动跳过的决策。 */
sealed class SkipDecision {

    /** 总开关关闭：任何影片都不自动跳过。 */
    object Disabled : SkipDecision()

    /** 该位置落在广告过滤负责的区间里：交给广告过滤处理。 */
    object AdFilterWins : SkipDecision()

    /** 命中用户标记：跳到 [targetMs]。 */
    data class SkipTo(val targetMs: Long, val segment: SkipSegment) : SkipDecision()

    /** 没有命中任何区间。 */
    object NoMatch : SkipDecision()
}

/**
 * 跳过标记的规则。
 *
 * 全部是纯函数：区间匹配、区间加减、撤销与「广告过滤优先」的优先级都放在
 * 领域层，界面只负责画和存。
 */
object SkipMarks {

    /** 微调边界的步长（5 秒）。 */
    const val ADJUST_STEP_MS = 5_000L

    /** 归一化片名：与聚合层、收藏共用同一套键规则（[normalizeTitle]）。 */
    fun keyOf(title: String): String = normalizeTitle(title)

    /**
     * 标记片头：片头结束点就是当前播放位置。
     *
     * 片头从 0 开始，所以区间是 `[0, position)`。
     */
    fun markIntro(positionMs: Long): SkipSegment =
        SkipSegment(SkipKind.INTRO, 0L, positionMs.coerceAtLeast(0L))

    /** 标记片尾：片尾起点就是当前播放位置，区间到结尾（用时长兜底）。 */
    fun markOutro(positionMs: Long, durationMs: Long): SkipSegment =
        SkipSegment(SkipKind.OUTRO, positionMs.coerceAtLeast(0L), durationMs.coerceAtLeast(positionMs))

    /** 标记中段区间。 */
    fun markMiddle(startMs: Long, endMs: Long): SkipSegment =
        SkipSegment(SkipKind.MIDDLE, minOf(startMs, endMs), maxOf(startMs, endMs))

    /** 命中区间：返回包含 [positionMs] 的第一个区间。 */
    fun match(positionMs: Long, segments: List<SkipSegment>): SkipSegment? =
        segments.firstOrNull { it.contains(positionMs) }

    /**
     * 追加或替换一个区间。
     *
     * 同类型的区间只保留一个：再标一次片头就是改边界，不是叠加两段片头。
     */
    fun upsert(mark: SkipMark?, segment: SkipSegment, updatedAt: Long): SkipMark {
        val base = mark ?: SkipMark(titleKey = "", title = "")
        val others = base.segments.filterNot { it.kind == segment.kind }
        return base.copy(
            segments = (others + segment).sortedBy { it.startMs },
            updatedAt = updatedAt
        )
    }

    /** 撤销某个类型的区间；撤销后剩下的区间继续生效。 */
    fun remove(mark: SkipMark, kind: SkipKind, updatedAt: Long): SkipMark =
        mark.copy(
            segments = mark.segments.filterNot { it.kind == kind },
            updatedAt = updatedAt
        )

    /** 撤销全部区间。 */
    fun clear(mark: SkipMark, updatedAt: Long): SkipMark =
        mark.copy(segments = emptyList(), updatedAt = updatedAt)

    /**
     * 微调区间边界。
     *
     * [edge] 为 `START` 时调整起点，`END` 时调整终点；调整后不允许区间
     * 反过来或变成空区间。
     */
    fun adjust(
        segment: SkipSegment,
        edge: SkipEdge,
        deltaMs: Long
    ): SkipSegment {
        val adjusted = when (edge) {
            SkipEdge.START -> segment.copy(startMs = segment.startMs + deltaMs)
            SkipEdge.END -> segment.copy(endMs = segment.endMs + deltaMs)
        }
        // 反过来就退回原值：宁可不动，也不要一个负长度的区间。
        if (adjusted.endMs <= adjusted.startMs) return segment
        return adjusted
    }

    /**
     * 从用户区间里扣掉广告过滤命中的区间。
     *
     * 广告过滤是**源侧**行为，优先于用户侧标记：广告区间由广告过滤处理，
     * 用户标记不得把播放头推过广告区间的边界（design D6）。
     */
    fun subtract(segment: SkipSegment, ads: List<SkipSegment>): List<SkipSegment> {
        if (ads.isEmpty()) return listOf(segment)
        var pieces = listOf(segment)
        for (ad in ads) {
            pieces = pieces.flatMap { piece ->
                if (!overlaps(piece, ad)) {
                    listOf(piece)
                } else {
                    val head = piece.copy(endMs = minOf(piece.endMs, ad.startMs))
                    val tail = piece.copy(startMs = maxOf(piece.startMs, ad.endMs))
                    listOf(head, tail).filter { it.lengthMs > 0L }
                }
            }
        }
        return pieces
    }

    /**
     * 自动跳过决策。
     *
     * @param globalEnabled 跳过标记总开关（设置页）。
     * @param positionMs 当前播放位置。
     * @param segments 该影片的标记区间。
     * @param adSegments 广告过滤命中的区间（源侧，优先）。
     */
    fun decide(
        globalEnabled: Boolean,
        positionMs: Long,
        segments: List<SkipSegment>,
        adSegments: List<SkipSegment> = emptyList()
    ): SkipDecision {
        if (!globalEnabled) return SkipDecision.Disabled
        // 播放头在广告区间里：交给广告过滤，用户标记不插手。
        if (adSegments.any { it.contains(positionMs) }) return SkipDecision.AdFilterWins
        val effective = segments.flatMap { subtract(it, adSegments) }
        val hit = match(positionMs, effective) ?: return SkipDecision.NoMatch
        return SkipDecision.SkipTo(hit.endMs, hit)
    }

    /** 两个区间是否相交。 */
    fun overlaps(a: SkipSegment, b: SkipSegment): Boolean =
        a.startMs < b.endMs && b.startMs < a.endMs
}

/** 区间的哪一端可以微调。 */
enum class SkipEdge {
    START,
    END
}
