package com.libretv.tv.ui.pages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.libretv.tv.di.AppContainer
import com.libretv.tv.domain.model.SourceConfig
import com.libretv.tv.domain.model.SourceEntry
import com.libretv.tv.domain.model.SourceHealth
import com.libretv.tv.domain.model.Subscription
import com.libretv.tv.domain.model.TvboxParseResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 源管理页状态：正在探测的源是页面局部的。 */
data class SourcesState(
    /** 正在探测的源地址；空串表示「全部探测」。 */
    val testingSource: String? = null
)

/**
 * 源管理页 ViewModel：开关、增删、探活与冷却恢复。
 */
class SourcesViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SourcesState())
    val state: StateFlow<SourcesState> = _state.asStateFlow()

    /** 全部采集源（含被禁用的）。 */
    val entries: StateFlow<List<SourceEntry>> = container.sourceEntries

    /** 探活结果与冷却档位。 */
    val health: StateFlow<Map<String, SourceHealth>> = container.sourceHealth

    /** 开关一个采集源：被禁用的源不再参与任何扇出。 */
    fun setSourceEnabled(url: String, enabled: Boolean) = container.setSourceEnabled(url, enabled)

    /** 删除一个采集源（内置或自定义）。 */
    fun removeSource(url: String) = container.removeSource(url)

    /**
     * 手动添加一个采集源。名字可省，地址必填。
     *
     * [dialect] 决定怎么查这个站（`standard` | `lenient`）：标准方言覆盖多数
     * 采集站，宽松方言给怪一点的站多留几条回退候选。
     */
    fun addSource(name: String, url: String, dialect: String = "standard"): Boolean =
        container.addSource(name, url, dialect)

    /** 撤销全部源编辑，回到内置清单。 */
    fun resetSources() = container.resetSources()

    /** 测一个采集源：记下可达性与往返耗时。 */
    fun testSource(url: String) {
        val source = entries.value.firstOrNull { it.url == url }?.config ?: return
        if (_state.value.testingSource != null) return
        _state.value = _state.value.copy(testingSource = url)
        viewModelScope.launch {
            val probe = container.content.probeWithLatency(source)
            container.recordHealth(url, probe)
            _state.value = _state.value.copy(testingSource = null)
        }
    }

    /** 一次测完所有采集源，每行各自保留结果。 */
    fun testAllSources() {
        if (_state.value.testingSource != null) return
        val targets = entries.value.map { it.config }
        if (targets.isEmpty()) return
        _state.value = _state.value.copy(testingSource = "")
        viewModelScope.launch {
            val results = container.content.probeAll(targets).mapValues { (url, probe) ->
                container.recordHealth(url, probe)
            }
            container.recordHealth(results)
            _state.value = _state.value.copy(testingSource = null)
        }
    }

    /** 用户手动恢复一个被停用的源：清零冷却档位并立即重测。 */
    fun resumeSource(url: String) {
        container.clearHealth(url)
        testSource(url)
    }

    /** 源管理页展示的冷却状态（跳过扇出的源）。 */
    fun cooldownState(url: String): SourceHealth = container.cooldownState(url)

    // ---------- 远程订阅 ----------

    /** 全部远程订阅。 */
    val subscriptions: StateFlow<List<Subscription>> = container.subscriptions

    /** 正在同步的订阅 id。 */
    val syncingSubscriptions: StateFlow<Set<String>> = container.syncingSubscriptions

    /** 添加一个订阅；保存后立即同步一次。 */
    fun addSubscription(name: String, url: String, refreshDays: Int): Boolean =
        container.addSubscription(name, url, refreshDays)

    /** 修改订阅：改名、换地址或改周期（失败计数随之清零）。 */
    fun updateSubscription(id: String, name: String, url: String, refreshDays: Int): Boolean =
        container.updateSubscription(id, name, url, refreshDays)

    /** 删除订阅，并移除它带来的源。 */
    fun deleteSubscription(id: String) = container.deleteSubscription(id)

    /** 立即同步某个订阅。 */
    fun syncSubscription(id: String) = container.syncSubscriptionNow(id)

    /** 批量导入采集源；返回新增的数量。 */
    fun importSources(sources: List<SourceConfig>): Int = container.importSources(sources)

    /**
     * 解析一份 TVBOX 配置。
     *
     * 解析器在 `data` 层，页面不能直接碰——这里代理一次，ui 只看 domain 的结果。
     */
    fun parseConfig(text: String): TvboxParseResult = container.parseTvboxConfig(text)
}
