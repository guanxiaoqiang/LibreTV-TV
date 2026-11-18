package com.libretv.tv.core

/**
 * 一次性事件：ViewModel 发出、UI 层执行的动作。
 *
 * 导航请求走这里而不是 ViewModel 直接改「当前页面」：返回栈由
 * navigation-compose 维护，ViewModel 不再持有 NavController（规则 D5）。
 *
 * 放在 `core` 而不是 `ui` ：依赖容器（`di`）也要发导航与提示，
 * 放在 `core` 才不会让下层依赖上层。
 */
sealed interface UiEffect {
    /** 跳转到某个路由；[replaceCurrent] 用于替换当前页（自动换源重新起播）。 */
    data class Navigate(val route: String, val replaceCurrent: Boolean = false) : UiEffect

    /** 瞬时提示（一次性，不留在页面状态里）。 */
    data class Message(val text: String) : UiEffect

    /** 把一个文件交给系统分享（导出备份用）。 */
    data class ShareFile(val path: String, val mimeType: String = "application/json") : UiEffect
}
