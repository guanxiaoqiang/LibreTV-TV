package com.libretv.tv.ui.pages

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.libretv.tv.di.AppContainer
import com.libretv.tv.domain.model.ProgressThrottle
import com.libretv.tv.domain.model.PlayRequest
import com.libretv.tv.domain.model.SourceOffer
import com.libretv.tv.domain.model.WatchRecord
import com.libretv.tv.domain.model.normalizeTitle
import com.libretv.tv.ui.nav.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 播放页 ViewModel。
 *
 * 起播请求经导航栈的 [SavedStateHandle] 传递：进程被回收后重建，
 * 播放页仍能还原同一集、同一进度，而不是从头开始。
 */
class PlayerViewModel(
    private val handle: SavedStateHandle,
    private val container: AppContainer
) : ViewModel() {

    /**
     * 当前起播请求；参数缺失或损坏时为 null。
     *
     * 首次取值是同步的：参数就在导航条目里，不必等协程派发，
     * 否则页面会先闪一帧「没有请求」。
     */
    private val _request = MutableStateFlow(Routes.decodeRequest(handle[Routes.ARG_REQUEST]))

    /** 当前起播请求。 */
    val request: StateFlow<PlayRequest?> = _request.asStateFlow()

    /** 切换到相邻集时更新入口参数（URL 与进度一起换）。 */
    fun update(request: PlayRequest) {
        handle[Routes.ARG_REQUEST] = Routes.encodeRequestForState(request)
        _request.value = request
        // 切集/换源：新条目第一次上报进度即可落库，不被上一集的节流状态挡住。
        progressThrottle.reset()
    }

    /** 进度写入节流器：位置抖动不落盘，切集/退出强制补写。 */
    private val progressThrottle = ProgressThrottle()

    /**
     * 保存进度。
     *
     * 4.2：仅在播放超过 10 秒、且位置相对上次落库变化超过 5 秒时写盘；
     * 切集（key 变化）或 [force]（退出补写）不受节流限制。
     */
    fun saveProgress(request: PlayRequest, positionMs: Long, durationMs: Long, force: Boolean = false) {
        if (!progressThrottle.shouldWrite(request.historyKey, positionMs, force)) return
        viewModelScope.launch {
            container.history.upsert(
                WatchRecord(
                    key = request.historyKey,
                    titleKey = normalizeTitle(request.title),
                    title = request.title,
                    poster = request.poster,
                    sourceName = request.sourceName,
                    sourceUrl = request.sourceUrl,
                    vodId = request.vodId,
                    episodeName = request.episodeName,
                    rawUrl = request.rawUrl,
                    lineIndex = request.lineIndex,
                    episodeIndex = request.episodeIndex,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    updatedAt = System.currentTimeMillis(),
                    typeName = request.typeName
                )
            )
        }
    }

    /**
     * 播放失败后的自动换源：解析 [offer] 的详情，起播同位置的集数。
     *
     * 进度以**比例**传递（旧位置 / 旧时长），起播后按新时长换算——不同源
     * 的片头长度不同，直接搬毫秒会跳错位置。
     */
    fun switchSource(offer: SourceOffer, resumeRatio: Float, episodeIndex: Int) {
        viewModelScope.launch {
            val next = container.switchSourceForRecovery(offer, resumeRatio, episodeIndex)
            if (next == null) {
                container.message("其他来源也没有可用地址")
                return@launch
            }
            // 播放页已经在栈顶：替换它，而不是再压一层。
            container.navigate(Routes.player(next), replaceCurrent = true)
        }
    }

    /** 播放失败：把失败记到这个源的账上（阶梯冷却据此升级）。 */
    fun reportPlaybackFailure(sourceUrl: String, errorCode: String) {
        viewModelScope.launch {
            container.reportPlaybackFailure(sourceUrl, errorCode)
        }
    }
}
