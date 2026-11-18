package com.libretv.tv.ui.pages

import androidx.lifecycle.ViewModel
import com.libretv.tv.di.AppContainer
import com.libretv.tv.di.DetailSession
import com.libretv.tv.domain.model.PlayRequest
import com.libretv.tv.domain.model.SourceOffer
import kotlinx.coroutines.flow.StateFlow

/**
 * 详情页 ViewModel。
 *
 * 详情包、当前线路与集数属于「详情会话」（应用级），播放页也要读它们。
 */
class DetailViewModel(private val container: AppContainer) : ViewModel() {

    /** 详情会话。 */
    val session: StateFlow<DetailSession> = container.detailSession

    fun selectLine(index: Int) = container.selectLine(index)

    fun selectEpisode(index: Int) = container.selectEpisode(index)

    /**
     * 详情页里换源。
     *
     * 当前源拿不到线路时，详情页会把同片的其它源列出来；点了就按
     * 那条源重新拉详情。
     */
    fun switchSource(offer: SourceOffer) = container.switchDetailSource(offer)

    /**
     * 起播：把选中的一集拼成起播请求。
     *
     * 请求对象是可序列化的，交给导航栈传给播放页——进程被回收后
     * 播放页还能还原出同一集、同一进度。
     */
    fun buildPlayRequest(line: Int, episode: Int): PlayRequest? {
        val d = container.detailSession.value.detail ?: return null
        val episodeItem = d.lines.getOrNull(line)?.episodes?.getOrNull(episode) ?: return null
        val sourceUrl = container.detailSession.value.activeOffer?.sourceUrl?.takeIf { it.isNotBlank() }
            ?: container.sources.value.firstOrNull { it.name == d.sourceName }?.url.orEmpty()
        container.selectEpisode(episode)
        return PlayRequest(
            title = d.item.vodName,
            poster = d.item.vodPic,
            episodeName = episodeItem.name,
            rawUrl = episodeItem.url,
            sourceName = d.sourceName,
            sourceUrl = sourceUrl,
            vodId = d.item.vodId,
            lineIndex = line,
            episodeIndex = episode,
            typeName = d.item.typeName
        )
    }
}
