package com.libretv.tv.ui.pages

import androidx.lifecycle.ViewModel
import com.libretv.tv.di.AppContainer
import com.libretv.tv.di.SearchSession
import com.libretv.tv.domain.model.TitleGroup
import kotlinx.coroutines.flow.StateFlow

/**
 * 结果页 ViewModel。
 *
 * 结果属于搜索会话（应用级），这一页只负责「打开一个标题」。
 */
class ResultViewModel(private val container: AppContainer) : ViewModel() {

    /** 当前搜索会话：关键词、结果与加载态。 */
    val session: StateFlow<SearchSession> = container.searchSession

    /** 打开一个标题：只有单一来源时直接进详情，否则先进选源页。 */
    fun openGroup(group: TitleGroup) {
        val route = container.openGroupOrDetail(group)
        if (route.isNotEmpty()) container.navigate(route)
    }
}
