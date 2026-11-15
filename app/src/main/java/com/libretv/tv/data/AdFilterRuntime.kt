package com.libretv.tv.data

import android.content.Context
import android.net.Uri
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayInputStream
import java.io.InputStream

/** Running count of removed ad segments, shown on the player's top bar. */
object AdFilterStats {
    private val _removed = MutableStateFlow(0)
    val removed: StateFlow<Int> = _removed.asStateFlow()

    fun reset() {
        _removed.value = 0
    }

    fun add(count: Int) {
        if (count > 0) _removed.value += count
    }
}

/**
 * Media3 hook for the ad filter.
 *
 * LibreTV installs this in HLS.js by wrapping the loader's manifest and level
 * responses. Media3 exposes the equivalent seam officially:
 * `HlsMediaSource.Factory.setPlaylistParserFactory(...)`. Every master and media
 * playlist goes through this parser, so rewriting the text here covers both the
 * initial manifest and live / VOD media playlist refreshes.
 */
class AdFilterPlaylistParserFactory(
    private val enabled: () -> Boolean = { true },
    private val onFiltered: (Int) -> Unit = {}
) : HlsPlaylistParserFactory {

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
        FilteringParser(HlsPlaylistParser(), enabled, onFiltered)

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?
    ): ParsingLoadable.Parser<HlsPlaylist> =
        FilteringParser(
            HlsPlaylistParser(multivariantPlaylist, previousMediaPlaylist),
            enabled,
            onFiltered
        )

    private class FilteringParser(
        private val delegate: HlsPlaylistParser,
        private val enabled: () -> Boolean,
        private val onFiltered: (Int) -> Unit
    ) : ParsingLoadable.Parser<HlsPlaylist> {

        override fun parse(uri: Uri, inputStream: InputStream): HlsPlaylist {
            val raw = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val result = if (enabled()) {
                M3u8AdFilter.stripAdGroupsWithStats(raw)
            } else {
                M3u8AdFilter.Result(raw, 0)
            }
            if (result.removedSegments > 0) onFiltered(result.removedSegments)
            return delegate.parse(
                uri,
                ByteArrayInputStream(result.playlist.toByteArray(Charsets.UTF_8))
            )
        }
    }
}
