package com.libretv.tv.domain.model

/**
 * 缓存读取的三种语义（local-first-storage D3）。
 *
 * UI 必须据此决定如何渲染：Fresh 正常展示；Stale 立即展示旧内容并在后台
 * 刷新（禁止先清空再填充）；Expired 仍展示但提示可能陈旧。把这层语义放
 * 在 domain，界面就不必猜测「这份数据是几小时的」。
 */
sealed interface CacheResult<out T> {

    /** 数据足够新，可直接使用。 */
    data class Fresh<T>(val value: T, val origin: CacheOrigin) : CacheResult<T>

    /** 命中本地缓存，后台正在刷新；[refreshing] 仅用于展示角标。 */
    data class Stale<T>(val value: T, val refreshing: Boolean = true) : CacheResult<T>

    /** 超过硬过期：仍展示，但提示内容可能陈旧。 */
    data class Expired<T>(val value: T, val ageMs: Long) : CacheResult<T>

    /** 本地无缓存。 */
    object Missing : CacheResult<Nothing>
}

/** 一次 Fresh 结果的来源。 */
enum class CacheOrigin { NETWORK, DISK, MEMORY }
