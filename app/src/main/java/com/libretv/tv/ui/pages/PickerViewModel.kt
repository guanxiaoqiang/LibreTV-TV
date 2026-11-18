package com.libretv.tv.ui.pages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.libretv.tv.di.AppContainer
import com.libretv.tv.di.PickerSession
import com.libretv.tv.domain.model.SourceOffer
import com.libretv.tv.domain.model.TitleGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 选源页 ViewModel：比较同一个标题在不同源上的画质与可用性。
 *
 * 探测是异步的，探测结果回到会话里，页面只读不改。
 */
class PickerViewModel(private val container: AppContainer) : ViewModel() {

    /** 当前选源会话：标题与探测结果。 */
    val session: StateFlow<PickerSession> = container.pickerSession

    private var probeJob: Job? = null

    init {
        viewModelScope.launch {
            container.pickerSession
                .map { it.group }
                .distinctUntilChanged()
                .collect { group ->
                    if (group != null) probe(group)
                }
        }
    }

    /** 并发探测候选来源；实测画质比采集站自报的备注更可信。 */
    private fun probe(group: TitleGroup) {
        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            val probes = coroutineScope {
                group.offers.take(8).mapIndexed { index, offer ->
                    async(Dispatchers.IO) {
                        val probe = container.probeScheduler.submit(
                            key = "offer|${offer.key}",
                            priority = PROBE_PRIORITY_BASE - index
                        ) {
                            container.content.probeOffer(offer)
                        }
                        probe?.let { offer.key to it }
                    }
                }.awaitAll()
            }.filterNotNull().toMap()
            container.publishProbes(group, probes)
        }
    }

    /** 选中一个来源并打开详情。 */
    fun pickOffer(offer: SourceOffer) {
        val route = container.pickOfferAndOpen(offer)
        container.navigate(route)
    }
}
