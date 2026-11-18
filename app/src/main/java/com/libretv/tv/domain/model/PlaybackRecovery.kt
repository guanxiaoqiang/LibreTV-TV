package com.libretv.tv.domain.model

/**
 * 播放失败后的恢复决策（design D7 / S5）。
 *
 * 现实里播放失败分两类：
 *
 *  - **可恢复**：网络抖动、超时——重试一下或者换个源就好了；
 *  - **不可恢复**：地址失效、内容被删——再试一百次也一样。
 *
 * 把这个判断放在领域层，界面只照着决策走：重试有退避、换源有上限、
 * 换源后进度按比例接着走。
 */
sealed class RecoveryAction {

    /** 同一个源重试一次（网络类错误，带退避）。 */
    data class RetrySameSource(val delayMs: Long) : RecoveryAction()

    /** 换到下一个 offer 继续播，进度按比例映射。 */
    data class SwitchSource(
        val offer: SourceOffer,
        /** 进度比例（0f..1f）。 */
        val resumeRatio: Float,
        /** 续播的剧集序号。 */
        val episodeIndex: Int
    ) : RecoveryAction()

    /** 换源也用完了，只能让观众自己选。 */
    data class GiveUp(val message: String) : RecoveryAction()
}

/** 播放失败的分类。 */
enum class PlaybackFailureKind {
    /** 网络不可达。 */
    NETWORK,

    /** 超时。 */
    TIMEOUT,

    /** 解码失败。 */
    DECODE,

    /** 播放地址本身无效（内容已失效）。 */
    INVALID_SOURCE
}

/**
 * 播放恢复规则。
 */
object PlaybackRecovery {

    /** 同源重试前的退避时间。 */
    const val RETRY_DELAY_MS = 1_500L

    /** 最多换几个 offer（design D7：最多 3 个）。 */
    const val MAX_OFFERS = 3

    /**
     * 这类错误值不值得重试 / 换源。
     *
     * 分类本身发生在播放器边缘（只有 ExoPlayer 知道自己的错误码），领域层
     * 只处理「这类错误该怎么办」。
     */
    fun isRecoverable(kind: PlaybackFailureKind): Boolean = when (kind) {
        PlaybackFailureKind.NETWORK, PlaybackFailureKind.TIMEOUT -> true
        PlaybackFailureKind.DECODE, PlaybackFailureKind.INVALID_SOURCE -> false
    }

    /**
     * 进度比例映射。
     *
     * 换源之后播放器的时长可能完全不同，所以记住的是**比例**而不是绝对
     * 毫秒：换源不会把观众甩回片头。
     */
    fun resumeRatio(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    /** 按比例换算新的播放位置。 */
    fun resumePositionMs(ratio: Float, newDurationMs: Long): Long {
        if (newDurationMs <= 0L) return 0L
        return (ratio.coerceIn(0f, 1f) * newDurationMs).toLong()
    }

    /**
     * 恢复决策。
     *
     * @param kind 错误分类。
     * @param sameSourceRetried 同源是否已经重试过。
     * @param offers 可换的 offer（按顺序）。
     * @param triedCount 已经换过几个 offer。
     * @param positionMs 当前播放位置。
     * @param durationMs 当前时长。
     * @param episodeIndex 当前剧集序号。
     */
    fun plan(
        kind: PlaybackFailureKind,
        sameSourceRetried: Boolean,
        offers: List<SourceOffer>,
        triedCount: Int,
        positionMs: Long,
        durationMs: Long,
        episodeIndex: Int
    ): RecoveryAction {
        if (!isRecoverable(kind)) {
            return RecoveryAction.GiveUp("这个地址已经失效了，换个源试试")
        }
        if (!sameSourceRetried) {
            return RecoveryAction.RetrySameSource(RETRY_DELAY_MS)
        }
        val next = offers.getOrNull(triedCount)
        if (next == null || triedCount >= MAX_OFFERS) {
            return RecoveryAction.GiveUp("几个源都试过了，手动选一个吧")
        }
        return RecoveryAction.SwitchSource(
            offer = next,
            resumeRatio = resumeRatio(positionMs, durationMs),
            episodeIndex = episodeIndex
        )
    }
}
