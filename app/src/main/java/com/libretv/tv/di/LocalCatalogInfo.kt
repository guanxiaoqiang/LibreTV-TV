package com.libretv.tv.di

import com.libretv.tv.domain.model.CatalogTitle

/** 从本地目录读到的影片概览（5.5），不含播放地址。 */
data class LocalCatalogInfo(
    val title: CatalogTitle?,
    val offerCount: Int,
    val sources: List<String>
) {
    val isEmpty: Boolean get() = title == null && offerCount == 0
}
