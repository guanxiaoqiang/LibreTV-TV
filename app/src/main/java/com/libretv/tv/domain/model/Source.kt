package com.libretv.tv.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class SourceConfig(
    val name: String = "",
    val url: String = "",
    val detail: String = "",
    /**
     * 该源使用的接口方言（`standard` | `lenient`）。
     *
     * Apple CMS 的实现五花八门：标准方言覆盖绝大多数采集源，宽松方言
     * 给那些参数理解更随性的源多几次被理解的机会。空值按标准方言处理。
     */
    val dialect: String = "",
    /**
     * 归属的订阅 id；空表示「不是订阅带来的」（内置源或观众手工添加）。
     *
     * 同步时按它精确增删：订阅远端删掉的源要跟着删，手工源一个都不能动。
     */
    val subscriptionId: String = "",
    /** 归属类型（手工 / 订阅）。 */
    val origin: SourceOrigin = SourceOrigin.MANUAL
)

/** 采集源的归属。 */
@Serializable
enum class SourceOrigin {
    /** 观众手工添加（或内置清单里的源）。 */
    MANUAL,

    /** 由某个订阅同步引入。 */
    SUBSCRIPTION
}

/** The source whose category tree drives the home screen, plus that tree. */
data class CategoryCatalog(
    val source: SourceConfig,
    val classes: List<com.libretv.tv.data.VodClass>
)
