package com.libretv.tv.ui.pages

import com.libretv.tv.di.AppContainer
import com.libretv.tv.domain.model.SourceOffer
import com.libretv.tv.domain.model.TitleGroup
import com.libretv.tv.domain.model.toSearchResult
import com.libretv.tv.ui.nav.Routes

/**
 * 标题 → 下一站的公共决策。
 *
 * 只有一个可用来源时直接进详情；多个来源时先进选源页比较。
 * 首页卡片、结果页与历史都走这里，行为一致。
 */
internal fun AppContainer.openGroupOrDetail(group: TitleGroup): String {
    if (group.playableCount <= 1) {
        val offer = group.offers.firstOrNull { !it.isTrailer }
            ?: group.offers.firstOrNull()
            ?: return ""
        clearActiveOffer()
        clearPickerSession()
        // 主来源拿不到详情时按延迟顺序退到同组其他来源，别让观众
        // 盯着「该源未提供播放信息」没有出路。
        openDetailAsync(offer.toSearchResult(), group.offers)
        return Routes.DETAIL
    }
    selectGroup(group)
    return Routes.PICKER
}

/** 选源页选中一个来源：记住角标并打开详情。 */
internal fun AppContainer.pickOfferAndOpen(offer: SourceOffer): String {
    setActiveOffer(offer)
    // 选源页会话里还留着这个标题组：用户挑的源详情失败时，
    // 兜底名单就是同一组的其他来源。
    openDetailAsync(offer.toSearchResult(), pickerSession.value.group?.offers.orEmpty())
    return Routes.DETAIL
}
