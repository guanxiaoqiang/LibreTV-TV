package com.libretv.tv.core

/**
 * 统一的请求结果。
 *
 * 之前接口层失败一律返回 `null`，调用方拿到 `null` 时既不知道是「没结果」还是
 * 「源死了」，更没法把失败计入健康度。`ApiResult` 把这两种情况分开：
 *
 * - [Ok]：请求成功，值可以为空集合（空是合法结果）；
 * - [Err]：请求失败，[error] 说明了死因。
 */
sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    data class Err(val error: SourceError, val detail: String? = null) : ApiResult<Nothing>()

    /** 成功时取值，失败时返回 `null`。 */
    fun getOrNull(): T? = (this as? Ok)?.value

    /** 成功时返回 `true`。 */
    val isOk: Boolean get() = this is Ok
}

/** 把一个可能抛异常的调用包成 [ApiResult]，失败按 [SourceError] 归类。 */
inline fun <reified T> catching(block: () -> T): ApiResult<T> = try {
    ApiResult.Ok(block())
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    ApiResult.Err(classifyNetworkError(e), e.message)
}
