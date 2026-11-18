package com.libretv.tv.ui.player

import androidx.media3.common.PlaybackException
import com.libretv.tv.domain.model.PlaybackFailureKind

/**
 * 播放错误分类。
 *
 * 只有播放器知道 ExoPlayer 的错误码，所以分类放在这一层；领域层拿到的只是
 * 「网络类 / 解码类 / 地址失效」这种与播放器无关的说法。
 */
internal fun PlaybackException.failureKind(): PlaybackFailureKind = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> PlaybackFailureKind.NETWORK

    PlaybackException.ERROR_CODE_TIMEOUT -> PlaybackFailureKind.TIMEOUT

    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> PlaybackFailureKind.INVALID_SOURCE

    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> PlaybackFailureKind.DECODE

    else -> PlaybackFailureKind.NETWORK
}
