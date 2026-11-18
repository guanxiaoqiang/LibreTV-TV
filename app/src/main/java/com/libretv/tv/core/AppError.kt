package com.libretv.tv.core

/**
 * 源错误的统一分类。
 *
 * 一个采集源失败有很多种死法，把它们混成一个字符串（"null" / "timeout" / HTTP 500）
 * 没法做两件事：告诉用户「这个源到底怎么了」，以及决定「这次失败要不要计入健康度」。
 * 这个密封类把两者都固化下来。
 *
 * 语义约定（`design-detail.md` §E.3）：
 * - [EmptyResult] 是**合法结果**，不是失败，不计入健康度降级；
 * - 其余错误都计入降级；
 * - 方言候选链不重试（换候选不是重试），网络层重试独立配置。
 */
sealed class SourceError {
    /** 连接或响应超时。 */
    data object Timeout : SourceError()

    /** 连不上（DNS 失败、连接被拒、TLS 失败）。 */
    data object Unreachable : SourceError()

    /** 服务端返回了非 2xx 状态码。 */
    data class HttpFailure(val code: Int) : SourceError()

    /** 触发了风控（412 / 403 / 验证页）。 */
    data object Blocked : SourceError()

    /** 响应不是预期的格式，无法解析。 */
    data object Malformed : SourceError()

    /** 该源不兼容当前方言。 */
    data object UnsupportedDialect : SourceError()

    /** 查询成功但没有结果——合法结果，不算失败。 */
    data object EmptyResult : SourceError()
}

/**
 * 从异常归类出 [SourceError]。
 *
 * OkHttp 的异常类型已经把「网络层死了」分得很清楚，这里只做映射，
 * 不做任何重试决策。
 */
fun classifyNetworkError(e: Throwable): SourceError = when (e) {
    is kotlinx.coroutines.TimeoutCancellationException -> SourceError.Timeout
    is java.net.SocketTimeoutException -> SourceError.Timeout
    is java.net.ConnectException,
    is java.net.UnknownHostException,
    is javax.net.ssl.SSLException -> SourceError.Unreachable
    else -> SourceError.Unreachable
}

/** 用户可见的失败原因。 */
val SourceError.userMessage: String
    get() = when (this) {
        SourceError.Timeout -> "该源响应超时"
        SourceError.Unreachable -> "无法连接该源"
        is SourceError.HttpFailure -> "该源拒绝了请求"
        SourceError.Blocked -> "该源触发了风控"
        SourceError.Malformed -> "该源返回格式无法识别"
        SourceError.UnsupportedDialect -> "该源接口不兼容"
        SourceError.EmptyResult -> "该源没有相关内容"
    }
