package com.libretv.tv.core

/**
 * 统一的 UA：与桌面 LibreTV 一致，避免被源站按 UA 识别。
 *
 * 放在 `core`：网络层与播放层都要用它，
 * 播放层不必为了一个常量去依赖数据层。
 */
const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
