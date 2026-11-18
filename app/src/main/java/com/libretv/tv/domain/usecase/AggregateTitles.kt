package com.libretv.tv.domain.usecase

import com.libretv.tv.data.SearchResult
import com.libretv.tv.domain.model.TitleGroup
import com.libretv.tv.domain.model.aggregate

/**
 * 把扇出搜索的原始结果折叠成标题组（聚合入口）。
 *
 * 直接委托 `domain.model.aggregate`：同名的采集结果合并成一张卡，
 * 预告/解说沉底，画质优先、延迟次之的顺序保证第一行就是合理默认。
 */
object AggregateTitles {

    operator fun invoke(results: List<SearchResult>): List<TitleGroup> = aggregate(results)
}
