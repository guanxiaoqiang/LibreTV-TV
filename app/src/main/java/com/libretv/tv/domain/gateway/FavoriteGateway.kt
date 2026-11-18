package com.libretv.tv.domain.gateway

import com.libretv.tv.domain.model.FavoriteEntry

/**
 * 收藏的存储接口。
 *
 * 收藏界面本身由 `local-first-storage` 变更实现；这里先把「收藏能进备份
 * 包」这条路打通（feature-parity S6），数据层换一个实现即可。
 */
interface FavoriteGateway {

    /** 全部收藏。 */
    fun favorites(): List<FavoriteEntry>

    /** 新增或覆盖一条收藏（按归一化片名去重）。 */
    fun save(entry: FavoriteEntry)

    /** 删除一条收藏。 */
    fun remove(titleKey: String)

    /** 整体替换（导入备份时用）。 */
    fun replaceAll(entries: List<FavoriteEntry>)
}
