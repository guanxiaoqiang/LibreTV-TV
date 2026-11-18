package com.libretv.tv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.libretv.tv.R
import com.libretv.tv.core.USER_AGENT
import com.libretv.tv.ui.player.AdFilterPlaylistParserFactory
import com.libretv.tv.ui.player.AdFilterStats
import com.libretv.tv.ui.player.failureKind
import com.libretv.tv.domain.model.Episode
import com.libretv.tv.domain.model.PlaybackRecovery
import com.libretv.tv.domain.model.RecoveryAction
import com.libretv.tv.domain.model.SkipDecision
import com.libretv.tv.domain.model.SkipMark
import com.libretv.tv.domain.model.SkipMarks
import com.libretv.tv.domain.model.SkipSegment
import kotlin.math.abs
import kotlin.math.roundToInt

/** Playback speeds the user can cycle through. */
private val SPEED_STEPS = listOf(1.0f, 1.25f, 1.5f, 2.0f, 0.75f, 0.5f)

/** How long the control panel stays up without input before it hides again. */
private const val PANEL_IDLE_MS = 6000L

/** How much one press of the seek keys moves the playhead. */
private const val SEEK_STEP_MS = 10_000L

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * TV player with a full remote control surface.
 *
 * The layout follows the way living-room remotes are used:
 *
 *  - The video fills the screen. Left / right seek by 10 seconds, up or down
 *    raises the control panel, BACK leaves the player.
 *  - The panel is a focusable control strip, not a passive readout. The
 *    progress bar can be focused and scrubbed, the buttons act on the
 *    playback, and the D-pad walks the strip with an explicit focus chain so
 *    it never escapes the panel.
 *  - Every control is also a tap target, so the same screen works on a
 *    tablet held in the hand.
 *  - The panel hides itself after a few idle seconds so the video is not
 *    permanently covered, and any key press or tap brings it back.
 */
@Composable
fun PlayerScreen(
    title: String,
    episodeName: String,
    rawUrl: String,
    episodes: List<Episode> = emptyList(),
    currentEpisodeIndex: Int = 0,
    startPositionMs: Long = 0L,
    adFilterEnabled: Boolean = true,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    onBack: () -> Unit,
    onNextEpisode: (() -> Unit)? = null,
    onPrevEpisode: (() -> Unit)? = null,
    onSelectEpisode: ((Int) -> Unit)? = null,
    /** 换源续播的进度比例（0..1）；仅换源后的第一次起播生效。 */
    resumeRatio: Float = 0f,
    /** 同片的其他可播来源，播放失败时按序自动尝试。 */
    recoveryOffers: List<com.libretv.tv.domain.model.SourceOffer> = emptyList(),
    /** 已经换过几个源了（换源会重建播放页，所以计数放在会话里）。 */
    recoverySwitches: Int = 0,
    /** 自动换源回调：参数为下一个 offer、当前进度比例与当前集数下标。 */
    onSwitchSource: ((com.libretv.tv.domain.model.SourceOffer, Float, Int) -> Unit)? = null,
    /** 播放失败上报：计入源健康度。 */
    onPlaybackFailure: ((String) -> Unit)? = null,
    /** 跳过标记总开关（设置页）。 */
    skipMarksEnabled: Boolean = true,
    /** 当前影片的跳过标记。 */
    skipMark: SkipMark? = null,
    /** 保存跳过标记。 */
    onSaveSkipMark: (SkipMark) -> Unit = {},
    /** 自动换源用尽后，观众手动选源的入口。 */
    onManualSourcePick: (() -> Unit)? = null,
    /** 一次性状态提示（例如「已切换到 XX 源」）。 */
    statusNotice: String? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var errorText by remember { mutableStateOf<String?>(null) }
    var panelVisible by remember { mutableStateOf(true) }
    var episodePickerVisible by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var bufferedMs by remember { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var speedIndex by remember { mutableIntStateOf(0) }
    var muted by remember { mutableStateOf(false) }

    // 跳过标记：面板可见性与自动跳过提示。
    var skipSheetVisible by remember { mutableStateOf(false) }
    var skipNotice by remember { mutableStateOf<String?>(null) }
    // 换源提示（「已切换到 XX 源」）。
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // Bumped whenever the user does something, which restarts the auto-hide
    // timer for the control panel.
    var interactionTick by remember { mutableIntStateOf(0) }

    // Apple CMS sometimes packs several urls separated by '|'.
    val playUrl = remember(rawUrl) {
        rawUrl.substringBefore('|').trim()
    }

    // Keyed on the url so switching episodes loads the new media instead of
    // keeping the old one. A history entry carries its last position, which is
    // applied here so "continue watching" really resumes.
    //
    // HLS is built explicitly instead of going through setMediaItem: Media3's
    // setPlaylistParserFactory is the official seam for rewriting a playlist
    // before it is parsed, which is where LibreTV's ad-slice filter belongs.
    val player = remember(playUrl, adFilterEnabled) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        val mediaItem = MediaItem.fromUri(playUrl)
        val mediaSource = if (playUrl.contains(".m3u8", ignoreCase = true)) {
            HlsMediaSource.Factory(httpFactory)
                .setPlaylistParserFactory(
                    AdFilterPlaylistParserFactory(
                        enabled = { adFilterEnabled },
                        onFiltered = { count -> AdFilterStats.add(count) }
                    )
                )
                .createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
        }
        ExoPlayer.Builder(context).build().apply {
            setMediaSource(mediaSource, startPositionMs.coerceAtLeast(0L))
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
        }
    }

    // The count is per playback session, not per app lifetime.
    val removedAds by AdFilterStats.removed.collectAsState()
    LaunchedEffect(playUrl) { AdFilterStats.reset() }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            var sameSourceRetried = false
            var resumeApplied = false

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                // 换源后的进度按新时长换算；只有第一次 READY 生效。
                if (state == Player.STATE_READY && !resumeApplied && resumeRatio > 0f) {
                    resumeApplied = true
                    val target = (player.duration * resumeRatio).toLong()
                    if (target > 0L) player.seekTo(target)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                onPlaybackFailure?.invoke(error.errorCodeName)
                // 恢复策略在领域层：同源重试一次 → 换下一个 offer（最多 3 个）
                // → 都不行才把错误交给观众。
                val action = PlaybackRecovery.plan(
                    kind = error.failureKind(),
                    sameSourceRetried = sameSourceRetried,
                    offers = recoveryOffers,
                    triedCount = recoverySwitches,
                    positionMs = player.currentPosition,
                    durationMs = player.duration,
                    episodeIndex = currentEpisodeIndex
                )
                when (action) {
                    is RecoveryAction.RetrySameSource -> {
                        sameSourceRetried = true
                        val resumeAt = player.currentPosition
                        scope.launch {
                            delay(action.delayMs)
                            player.seekTo(resumeAt)
                            player.prepare()
                            player.play()
                        }
                    }

                    is RecoveryAction.SwitchSource -> {
                        onSwitchSource?.invoke(action.offer, action.resumeRatio, action.episodeIndex)
                    }

                    is RecoveryAction.GiveUp -> {
                        errorText = action.message
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player.addListener(listener)

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.pause()
                Lifecycle.Event.ON_RESUME -> player.play()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            // Report the last position before releasing the player. This is
            // also what saves the outgoing episode when the viewer switches
            // to the next one.
            onProgress(player.currentPosition, player.duration)
            player.release()
        }
    }

    // Poll the playback state for the readout. 400 ms is fine for a TV
    // progress bar and keeps the UI smooth on low-end hardware. The history
    // write is throttled to roughly every five seconds.
    //
    // The same loop watches the skip marks: when the playhead enters a marked
    // range it jumps straight to the end of that range. The jump is
    // self-limiting — after the seek the playhead sits outside the range, so
    // it cannot loop.
    LaunchedEffect(player) {
        var ticks = 0
        while (true) {
            positionMs = player.currentPosition
            durationMs = player.duration
            bufferedMs = player.bufferedPosition
            playing = player.isPlaying
            if (ticks % 12 == 0 && durationMs > 0L) {
                onProgress(positionMs, durationMs)
            }
            if (skipMarksEnabled) {
                val decision = SkipMarks.decide(
                    globalEnabled = true,
                    positionMs = positionMs,
                    segments = skipMark?.segments.orEmpty()
                )
                if (decision is SkipDecision.SkipTo && decision.targetMs > positionMs) {
                    player.seekTo(decision.targetMs)
                    skipNotice = "已跳过${skipKindLabel(decision.segment.kind)}"
                }
            }
            ticks++
            kotlinx.coroutines.delay(400)
        }
    }

    // 自动跳过的提示只停一会儿，别挡住画面。
    LaunchedEffect(skipNotice) {
        if (skipNotice != null) {
            kotlinx.coroutines.delay(2500)
            skipNotice = null
        }
    }

    // 换源提示同理：换源会重建播放页，所以提示由外部传进来。
    LaunchedEffect(statusNotice) {
        if (statusNotice != null) {
            statusMessage = statusNotice
            kotlinx.coroutines.delay(3000)
            statusMessage = null
        }
    }

    // The control panel hides itself when the viewer stops touching it.
    //
    // An error card is exempt: it owns the focus, and hiding the panel under a
    // dialog used to flip the root focusable on mid-dialog, which crashed the
    // focus transaction (see rootRequester below).
    LaunchedEffect(panelVisible, episodePickerVisible, errorText, interactionTick) {
        if (panelVisible && !episodePickerVisible && errorText == null) {
            kotlinx.coroutines.delay(PANEL_IDLE_MS)
            if (panelVisible && !episodePickerVisible && errorText == null) {
                panelVisible = false
            }
        }
    }

    // When the panel is hidden every focusable child leaves the composition,
    // which would leave the screen without a focus target and make all the
    // keys dead. The root keeps the focus so the remote stays in control.
    val rootRequester = remember { FocusRequester() }
    LaunchedEffect(panelVisible, episodePickerVisible, errorText) {
        if (!panelVisible && !episodePickerVisible && errorText == null) {
            // The root only becomes focusable in the same recomposition that
            // hides the panel, so the node can still be unattached when this
            // effect runs. Retry a few frames instead of crashing.
            repeat(3) {
                if (rootRequester.requestFocusSafely()) return@LaunchedEffect
                kotlinx.coroutines.delay(16)
            }
        }
    }

    // Registered inside the player, so it wins over the app-level back handler.
    // A dialog (episode picker / error card) consumes BACK and only closes
    // itself; the playback underneath keeps running. The control strip comes
    // back automatically because it leaves the composition while a dialog is
    // up and re-runs its focus request when the dialog closes.
    BackHandler(enabled = episodePickerVisible || errorText != null) {
        when {
            episodePickerVisible -> {
                episodePickerVisible = false
                interactionTick++
            }
            errorText != null -> {
                errorText = null
                interactionTick++
            }
        }
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
        interactionTick++
    }

    fun seekBy(deltaMs: Long) {
        val target = (player.currentPosition + deltaMs)
            .coerceIn(0L, durationMs.coerceAtLeast(0L))
        player.seekTo(target)
        interactionTick++
    }

    fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEED_STEPS.size
        player.setPlaybackSpeed(SPEED_STEPS[speedIndex])
        interactionTick++
    }

    fun toggleMute() {
        muted = !muted
        player.volume = if (muted) 0f else 1f
        interactionTick++
    }

    fun retry() {
        errorText = null
        player.prepare()
        player.play()
        interactionTick++
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootRequester)
            // The root only takes the focus while the panel is hidden, so it
            // acts as the anchor that keeps the remote in control without
            // stealing the focus from the panel buttons.
            .focusable(enabled = !panelVisible && !episodePickerVisible)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        // While the panel is up the focus system owns the
                        // horizontal keys, so seeking only happens in the
                        // full-screen mode.
                        if (!panelVisible) {
                            seekBy(-SEEK_STEP_MS)
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (!panelVisible) {
                            seekBy(SEEK_STEP_MS)
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        panelVisible = true
                        interactionTick++
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (panelVisible) {
                            panelVisible = false
                            episodePickerVisible = false
                        } else {
                            panelVisible = true
                        }
                        interactionTick++
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        // Confirm on the video itself toggles playback.
                        if (!panelVisible) {
                            togglePlay()
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        togglePlay()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_STOP -> {
                        onBack()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        seekBy(30_000)
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        seekBy(-30_000)
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                // Inflated from XML so the surface is a TextureView; a
                // SurfaceView would render above the Compose overlay and hide
                // the control panel.
                (android.view.LayoutInflater.from(ctx)
                    .inflate(R.layout.view_player, null) as PlayerView).apply {
                    this.player = player
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            update = { it.player = player },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // A tap on the video toggles the panel, like every other
                    // TV player.
                    detectTapGestures {
                        panelVisible = !panelVisible
                        interactionTick++
                    }
                }
        )

        // Top overlay: what is playing right now.
        if (panelVisible) {
            TopInfoBar(
                title = title,
                episodeName = episodeName,
                adFilterEnabled = adFilterEnabled,
                removedAds = removedAds,
                modifier = Modifier.align(Alignment.TopStart)
            )
        }

        if (buffering && errorText == null) {
            CircularProgressIndicator(
                color = TvColors.Primary,
                strokeWidth = 3.dp,
                modifier = Modifier
                    .width(46.dp)
                    .height(46.dp)
                    .align(Alignment.Center)
            )
        }

        if (errorText != null) {
            PlaybackErrorOverlay(
                message = errorText!!,
                onRetry = { retry() },
                onBack = onBack,
                onManualSourcePick = onManualSourcePick,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        if (panelVisible && errorText == null && !episodePickerVisible && !skipSheetVisible) {
            ControlPanel(
                title = title,
                episodeName = episodeName,
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                playing = playing,
                speed = SPEED_STEPS[speedIndex],
                muted = muted,
                hasPrev = onPrevEpisode != null,
                hasNext = onNextEpisode != null,
                hasEpisodePicker = onSelectEpisode != null && episodes.size > 1,
                skipSegments = skipMark?.segments.orEmpty(),
                onSeek = { target ->
                    player.seekTo(target.coerceIn(0L, durationMs.coerceAtLeast(0L)))
                    interactionTick++
                },
                onTogglePlay = { togglePlay() },
                onPrev = onPrevEpisode,
                onNext = onNextEpisode,
                onCycleSpeed = { cycleSpeed() },
                onToggleMute = { toggleMute() },
                onOpenEpisodes = {
                    episodePickerVisible = true
                    interactionTick++
                },
                onOpenSkipMarks = {
                    skipSheetVisible = true
                    interactionTick++
                },
                onBack = onBack,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // 跳过标记面板。
        if (skipSheetVisible) {
            val titleKey = remember(title) { SkipMarks.keyOf(title) }
            SkipMarkSheet(
                title = title,
                positionMs = positionMs,
                durationMs = durationMs,
                mark = skipMark,
                onMarkIntro = {
                    onSaveSkipMark(
                        SkipMarks.upsert(
                            skipMark ?: SkipMark(titleKey = titleKey, title = title),
                            SkipMarks.markIntro(positionMs),
                            System.currentTimeMillis()
                        )
                    )
                },
                onMarkOutro = {
                    onSaveSkipMark(
                        SkipMarks.upsert(
                            skipMark ?: SkipMark(titleKey = titleKey, title = title),
                            SkipMarks.markOutro(positionMs, durationMs),
                            System.currentTimeMillis()
                        )
                    )
                },
                onMarkMiddle = { startMs, endMs ->
                    onSaveSkipMark(
                        SkipMarks.upsert(
                            skipMark ?: SkipMark(titleKey = titleKey, title = title),
                            SkipMarks.markMiddle(startMs, endMs),
                            System.currentTimeMillis()
                        )
                    )
                },
                onAdjust = { segment, edge, delta ->
                    val base = skipMark ?: SkipMark(titleKey = titleKey, title = title)
                    val adjusted = SkipMarks.adjust(segment, edge, delta, System.currentTimeMillis())
                    onSaveSkipMark(SkipMarks.upsert(base, adjusted, System.currentTimeMillis()))
                },
                onUndo = { kind ->
                    val base = skipMark
                    if (base != null) {
                        onSaveSkipMark(SkipMarks.remove(base, kind, System.currentTimeMillis()))
                    }
                },
                onPreview = { segment ->
                    player.seekTo(segment.endMs.coerceIn(0L, durationMs.coerceAtLeast(0L)))
                    interactionTick++
                },
                onClose = {
                    skipSheetVisible = false
                    interactionTick++
                },
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 自动跳过的提示。
        skipNotice?.let { notice ->
            Text(
                notice,
                color = TvColors.Primary,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 96.dp)
            )
        }

        // 换源提示。
        statusMessage?.let { message ->
            Text(
                message,
                color = TvColors.Primary,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 96.dp)
            )
        }

        if (episodePickerVisible && episodes.isNotEmpty()) {
            EpisodePicker(
                episodes = episodes,
                currentIndex = currentEpisodeIndex,
                onPick = { index ->
                    episodePickerVisible = false
                    onSelectEpisode?.invoke(index)
                    interactionTick++
                },
                onClose = {
                    episodePickerVisible = false
                    interactionTick++
                },
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/** Small header that names the title and the episode being played. */
@Composable
private fun TopInfoBar(
    title: String,
    episodeName: String,
    adFilterEnabled: Boolean,
    removedAds: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0x99000000))
            .padding(horizontal = 34.dp, vertical = 18.dp)
    ) {
        Text(
            title,
            color = TvColors.OnSurface,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        Text(
            buildString {
                append(episodeName)
                if (adFilterEnabled) {
                    append(" · 广告切片过滤")
                    if (removedAds > 0) append(" · 已跳过 $removedAds 段")
                }
            },
            color = TvColors.OnSurfaceMuted,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The control strip.
 *
 * Focus order is declared explicitly: the progress bar sits above the button
 * row, the buttons run left to right, and `up` / `down` move between the two
 * rows. Without the explicit chain Compose's spatial focus can drop the
 * viewer outside the panel, which on a TV means the controls stop responding.
 */
@Composable
private fun ControlPanel(
    title: String,
    episodeName: String,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    playing: Boolean,
    speed: Float,
    muted: Boolean,
    hasPrev: Boolean,
    hasNext: Boolean,
    hasEpisodePicker: Boolean,
    skipSegments: List<SkipSegment>,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onCycleSpeed: () -> Unit,
    onToggleMute: () -> Unit,
    onOpenEpisodes: () -> Unit,
    onOpenSkipMarks: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Button ids in visual order; the requester list is parallel to it.
    val barRequester = remember { FocusRequester() }
    val buttonRequesters = remember { List(8) { FocusRequester() } }

    // Which logical button occupies which slot. The play button is always
    // first so the focus chain stays stable no matter which optional
    // controls are present.
    val slots = buildList {
        if (hasPrev) add("prev")
        add("play")
        if (hasNext) add("next")
        add("speed")
        add("mute")
        if (hasEpisodePicker) add("episodes")
        add("marks")
        add("back")
    }
    val playSlot = slots.indexOf("play")
    val prevSlot = slots.indexOf("prev")
    val nextSlot = slots.indexOf("next")
    val speedSlot = slots.indexOf("speed")
    val muteSlot = slots.indexOf("mute")
    val episodesSlot = slots.indexOf("episodes")
    val marksSlot = slots.indexOf("marks")
    val backSlot = slots.indexOf("back")
    val lastSlot = slots.lastIndex

    LaunchedEffect(Unit) {
        // Take focus on the play button when the panel appears.
        buttonRequesters[playSlot].requestFocusSafely()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xE60E1116))
            .padding(horizontal = 34.dp, vertical = 22.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = TvColors.OnSurface,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    episodeName,
                    color = TvColors.OnSurfaceMuted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                color = TvColors.OnSurfaceMuted,
                fontSize = 14.sp
            )
        }

        Spacer(Modifier.height(14.dp))

        SeekBar(
            positionMs = positionMs,
            durationMs = durationMs,
            bufferedMs = bufferedMs,
            skipSegments = skipSegments,
            onSeek = onSeek,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(barRequester)
                .focusProperties {
                    this.next = buttonRequesters[playSlot]
                    this.previous = buttonRequesters[lastSlot]
                    this.down = buttonRequesters[playSlot]
                    this.up = barRequester
                }
        )

        Spacer(Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (prevSlot >= 0) {
                PanelButton(
                    label = "上一集",
                    requester = buttonRequesters[prevSlot],
                    chain = buttonRequesters,
                    slot = prevSlot,
                    lastSlot = lastSlot,
                    bar = barRequester,
                    onClick = { onPrev?.invoke() }
                )
            }

            PanelButton(
                label = if (playing) "暂停" else "播放",
                requester = buttonRequesters[playSlot],
                chain = buttonRequesters,
                slot = playSlot,
                lastSlot = lastSlot,
                bar = barRequester,
                accent = true,
                onClick = onTogglePlay
            )

            if (nextSlot >= 0) {
                PanelButton(
                    label = "下一集",
                    requester = buttonRequesters[nextSlot],
                    chain = buttonRequesters,
                    slot = nextSlot,
                    lastSlot = lastSlot,
                    bar = barRequester,
                    onClick = { onNext?.invoke() }
                )
            }

            PanelButton(
                label = "${speed}x",
                requester = buttonRequesters[speedSlot],
                chain = buttonRequesters,
                slot = speedSlot,
                lastSlot = lastSlot,
                bar = barRequester,
                onClick = onCycleSpeed
            )

            PanelButton(
                label = if (muted) "取消静音" else "静音",
                requester = buttonRequesters[muteSlot],
                chain = buttonRequesters,
                slot = muteSlot,
                lastSlot = lastSlot,
                bar = barRequester,
                onClick = onToggleMute
            )

            if (episodesSlot >= 0) {
                PanelButton(
                    label = "选集",
                    requester = buttonRequesters[episodesSlot],
                    chain = buttonRequesters,
                    slot = episodesSlot,
                    lastSlot = lastSlot,
                    bar = barRequester,
                    onClick = onOpenEpisodes
                )
            }

            PanelButton(
                label = "标记",
                requester = buttonRequesters[marksSlot],
                chain = buttonRequesters,
                slot = marksSlot,
                lastSlot = lastSlot,
                bar = barRequester,
                onClick = onOpenSkipMarks
            )

            PanelButton(
                label = "返回",
                requester = buttonRequesters[backSlot],
                chain = buttonRequesters,
                slot = backSlot,
                lastSlot = lastSlot,
                bar = barRequester,
                onClick = onBack
            )
        }
    }
}

/**
 * Scrubbable progress bar.
 *
 * When focused, left and right move the playhead and are consumed so the
 * global key handler does not seek a second time. A tap or a horizontal drag
 * jumps straight to that position, which is the fastest way to use it with a
 * finger.
 */
@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    skipSegments: List<SkipSegment>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    var widthPx by remember { mutableIntStateOf(0) }

    val duration = durationMs.coerceAtLeast(1L)
    val playedFraction = (positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val bufferedFraction = (bufferedMs.toFloat() / duration).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .height(if (focused) 26.dp else 14.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onSeek(positionMs - SEEK_STEP_MS)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onSeek(positionMs + SEEK_STEP_MS)
                        true
                    }
                    else -> false
                }
            }
            .pointerInput(duration) {
                detectTapGestures { offset ->
                    if (widthPx > 0) {
                        onSeek((offset.x.toFloat() / widthPx * duration).toLong())
                    }
                }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    if (widthPx > 0) {
                        val delta = dragAmount / widthPx * duration
                        onSeek((positionMs + delta).toLong())
                    }
                }
            }
            .onSizeChanged { widthPx = it.width }
    ) {
        // Track
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(if (focused) 10.dp else 6.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(TvColors.SurfaceHigh)
        ) {
            // Buffered
            Box(
                modifier = Modifier
                    .fillMaxWidth(bufferedFraction)
                    .height(if (focused) 10.dp else 6.dp)
                    .background(TvColors.OnSurfaceMuted.copy(alpha = 0.5f))
            )
            // Played
            Box(
                modifier = Modifier
                    .fillMaxWidth(playedFraction)
                    .height(if (focused) 10.dp else 6.dp)
                    .background(TvColors.Primary)
            )
            // Marked ranges: the viewer sees where the player will jump.
            skipSegments.forEach { segment ->
                val start = (segment.startMs.toFloat() / duration).coerceIn(0f, 1f)
                val end = (segment.endMs.toFloat() / duration).coerceIn(0f, 1f)
                if (end > start) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(end - start)
                            .align(Alignment.CenterStart)
                            .offset {
                                IntOffset((start * widthPx).roundToInt(), 0)
                            }
                            .height(if (focused) 10.dp else 6.dp)
                            .background(TvColors.Warning.copy(alpha = 0.85f))
                    )
                }
            }
        }

        // Thumb
        if (widthPx > 0) {
            val thumbSize = if (focused) 18.dp else 12.dp
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset {
                        IntOffset(
                            (playedFraction * widthPx - thumbSize.toPx() / 2f).roundToInt(),
                            0
                        )
                    }
                    .width(thumbSize)
                    .height(thumbSize)
                    .clip(RoundedCornerShape(thumbSize / 2))
                    .background(if (focused) TvColors.Primary else TvColors.OnSurfaceMuted)
            )
        }
    }
}

/** One button in the control strip, wired into the explicit focus chain. */
@Composable
private fun PanelButton(
    label: String,
    requester: FocusRequester,
    chain: List<FocusRequester>,
    slot: Int,
    lastSlot: Int,
    bar: FocusRequester,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    FocusableTile(
        onClick = onClick,
        modifier = Modifier
            .focusRequester(requester)
            .focusProperties {
                // The chain wraps around: past the last button the focus goes
                // back to the progress bar and vice versa, so the viewer can
                // never end up with the focus outside the panel.
                this.next = if (slot < lastSlot) chain[slot + 1] else bar
                this.previous = if (slot > 0) chain[slot - 1] else bar
                this.up = bar
                this.down = bar
            }
    ) { f ->
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(
                    when {
                        f -> TvColors.Primary
                        accent -> TvColors.PrimaryDim
                        else -> TvColors.SurfaceHigh
                    }
                )
                .tvFocusable(f, RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 9.dp)
        ) {
            Text(
                label,
                color = if (f || accent) Color(0xFF14181D) else TvColors.OnSurface,
                fontSize = 13.sp,
                fontWeight = if (f) FontWeight.Medium else FontWeight.Normal
            )
        }
    }
}

/** Episode picker shown on top of the video. */
@Composable
private fun EpisodePicker(
    episodes: List<Episode>,
    currentIndex: Int,
    onPick: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    // One requester per episode, kept outside the grid so the list can be
    // virtualised without recreating focus handles.
    val episodeRequesters = remember(episodes.size) {
        List(episodes.size) { FocusRequester() }
    }
    val closeRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        episodeRequesters.firstOrNull()?.requestFocusSafely()
    }

    Column(
        modifier = modifier
            .width(720.dp)
            .height(520.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xF20E1116))
            .padding(26.dp)
    ) {
        Text(
            "选集（${episodes.size}）",
            color = TvColors.OnSurface,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "当前：${episodes.getOrNull(currentIndex)?.name ?: "-"}",
            color = TvColors.OnSurfaceMuted,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(16.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(episodes.size) { i ->
                val selected = i == currentIndex
                val requester = episodeRequesters[i]
                val first = episodeRequesters.first()
                val last = episodeRequesters.last()
                FocusableTile(
                    onClick = { onPick(i) },
                    modifier = Modifier
                        .focusRequester(requester)
                        .focusProperties {
                            // The grid wraps: the last episode links to the
                            // close button and back to the first episode.
                            this.next = if (i < episodeRequesters.lastIndex) {
                                episodeRequesters[i + 1]
                            } else {
                                closeRequester
                            }
                            this.previous = if (i > 0) {
                                episodeRequesters[i - 1]
                            } else {
                                closeRequester
                            }
                            this.up = first
                            this.down = last
                        }
                ) { f ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    f -> TvColors.Primary
                                    selected -> TvColors.PrimaryDim
                                    else -> TvColors.Surface
                                }
                            )
                            .tvFocusable(f, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            episodes[i].name,
                            color = if (f || selected) Color(0xFF14181D) else TvColors.OnSurface,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        FocusableTile(
            onClick = onClose,
            modifier = Modifier
                .focusRequester(closeRequester)
                .focusProperties {
                    val first = episodeRequesters.firstOrNull() ?: closeRequester
                    val last = episodeRequesters.lastOrNull() ?: closeRequester
                    this.next = first
                    this.previous = last
                    this.up = last
                    this.down = first
                }
        ) { f ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (f) TvColors.Primary else TvColors.SurfaceHigh)
                    .tvFocusable(f, RoundedCornerShape(8.dp))
                    .padding(horizontal = 18.dp, vertical = 9.dp)
            ) {
                Text(
                    "关闭",
                    color = if (f) Color(0xFF14181D) else TvColors.OnSurface,
                    fontSize = 13.sp
                )
            }
        }
    }
}

/** Error card with a retry action. */
@Composable
private fun PlaybackErrorOverlay(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onManualSourcePick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val retryRequester = remember { FocusRequester() }
    val backRequester = remember { FocusRequester() }
    val pickRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        retryRequester.requestFocusSafely()
    }

    Column(
        modifier = modifier
            .width(420.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xF21A1F27))
            .padding(26.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            message,
            color = TvColors.Danger,
            fontSize = 15.sp,
            maxLines = 3
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FocusableTile(
                onClick = onRetry,
                modifier = Modifier
                    .focusRequester(retryRequester)
                    .focusProperties {
                        this.next = backRequester
                        this.previous = backRequester
                    }
            ) { f ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (f) TvColors.Primary else TvColors.SurfaceHigh)
                        .tvFocusable(f, RoundedCornerShape(8.dp))
                        .padding(horizontal = 18.dp, vertical = 9.dp)
                ) {
                    Text(
                        "重试",
                        color = if (f) Color(0xFF14181D) else TvColors.OnSurface,
                        fontSize = 13.sp
                    )
                }
            }
            FocusableTile(
                onClick = onBack,
                modifier = Modifier
                    .focusRequester(backRequester)
                    .focusProperties {
                        this.next = retryRequester
                        this.previous = retryRequester
                    }
            ) { f ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (f) TvColors.Primary else TvColors.SurfaceHigh)
                        .tvFocusable(f, RoundedCornerShape(8.dp))
                        .padding(horizontal = 18.dp, vertical = 9.dp)
                ) {
                    Text(
                        "返回详情",
                        color = if (f) Color(0xFF14181D) else TvColors.OnSurface,
                        fontSize = 13.sp
                    )
                }
            }
            // 自动换源用尽之后，手动选源是观众最后的自救手段。
            if (onManualSourcePick != null) {
                FocusableTile(
                    onClick = onManualSourcePick,
                    modifier = Modifier
                        .focusRequester(pickRequester)
                        .focusProperties {
                            this.next = retryRequester
                            this.previous = retryRequester
                        }
                ) { f ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (f) TvColors.Primary else TvColors.SurfaceHigh)
                            .tvFocusable(f, RoundedCornerShape(8.dp))
                            .padding(horizontal = 18.dp, vertical = 9.dp)
                    ) {
                        Text(
                            "手动选源",
                            color = if (f) Color(0xFF14181D) else TvColors.OnSurface,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}
