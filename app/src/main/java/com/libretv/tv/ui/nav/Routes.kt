package com.libretv.tv.ui.nav

import android.net.Uri
import com.libretv.tv.domain.model.PlayRequest
import kotlinx.serialization.json.Json

/**
 * 导航路由表。
 *
 * 路由名是稳定的字符串契约，页面之间只通过这里跳转；
 * 返回栈由 navigation-compose 维护，不再由 ViewModel 记录「从哪来」。
 */
object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val RESULT = "result"
    const val PICKER = "picker"
    const val DETAIL = "detail"
    const val PLAYER = "player"
    const val SETTINGS = "settings"
    const val SOURCES = "sources"
    const val SKIP_MARKS = "skipMarks"

    /** 播放页的路由参数名：序列化的 [PlayRequest]。 */
    const val ARG_REQUEST = "request"

    /** 路由模板：`player?request={request}`。 */
    const val PLAYER_PATTERN = "$PLAYER?$ARG_REQUEST={$ARG_REQUEST}"

    private val json = Json { ignoreUnknownKeys = true }

    /** 把起播请求编码进播放路由。 */
    fun player(request: PlayRequest): String =
        "$PLAYER?$ARG_REQUEST=" + encodeRequest(request)

    /** 只编码请求本身（写入 SavedStateHandle 时用）。 */
    fun encodeRequest(request: PlayRequest): String =
        Uri.encode(json.encodeToString(PlayRequest.serializer(), request))

    /**
     * 写入 SavedStateHandle 的形式：不带 URI 编码。
     *
     * SavedStateHandle 存的是普通字符串，不走 URI 解析；多编一次反而
     * 会在还原时把 '%' 之类的字符解坏。
     */
    fun encodeRequestForState(request: PlayRequest): String =
        json.encodeToString(PlayRequest.serializer(), request)

    /**
     * 从路由参数还原起播请求；参数缺失或损坏时返回 null。
     *
     * 两种来源都兼容：导航参数已经是解码过的原始 JSON，
     * SavedStateHandle 里可能是自己写进去的原始 JSON。
     */
    fun decodeRequest(encoded: String?): PlayRequest? {
        if (encoded.isNullOrBlank()) return null
        val serializer = PlayRequest.serializer()
        return runCatching { json.decodeFromString(serializer, encoded) }.getOrNull()
            ?: runCatching { json.decodeFromString(serializer, Uri.decode(encoded)) }.getOrNull()
    }
}
