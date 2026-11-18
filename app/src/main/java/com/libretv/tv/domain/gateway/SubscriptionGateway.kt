package com.libretv.tv.domain.gateway

import com.libretv.tv.domain.model.SourceConfig
import com.libretv.tv.domain.model.Subscription

/**
 * 订阅网关：订阅配置与「按归属增删源」的唯一入口。
 *
 * 单独抽成接口有两个原因：
 *
 *  - 同步逻辑要能被单测覆盖，而实现的存储部分依赖 Android 的 SharedPreferences；
 *  - 「按归属精确增删」是业务规则，不该散落到界面层。
 */
interface SubscriptionGateway {

    /** 全部订阅。 */
    fun subscriptions(): List<Subscription>

    /** 覆盖保存订阅列表。 */
    fun saveSubscriptions(list: List<Subscription>)

    /** 全部自定义源（含订阅引入的）。 */
    fun customSources(): List<SourceConfig>

    /**
     * 用远端内容替换某个订阅引入的源。
     *
     * 返回被移除的源地址（远端不再提供的那些）。
     */
    fun replaceSubscriptionSources(subscriptionId: String, sources: List<SourceConfig>): List<String>

    /** 移除某个订阅引入的全部源；返回被移除的源地址。 */
    fun removeSubscriptionSources(subscriptionId: String): List<String>
}
