package com.libretv.tv.data.remote

import com.libretv.tv.core.ApiResult
import com.libretv.tv.core.Logger
import com.libretv.tv.core.SourceError
import com.libretv.tv.core.classifyNetworkError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * 统一的 HTTP 出口。
 *
 * 之前 `CmsClient`、`DoubanClient`、`ProbeEngine` 各自持有一个 OkHttpClient，
 * 超时、UA、Referer 各配各的，改一处漏两处。这里收敛成一个：
 *
 * - **一个 client**：超时与重定向规则只有一份；
 * - **统一的 UA / Referer**：拦截器按域名补齐，调用方不用再操心；
 * - **失败归类**：网络层异常在这里映射成 [SourceError]，上层只看 [ApiResult]。
 *
 * 重试不在这里做——方言候选链不重试（换候选不是重试），网络重试次数
 * 由 [maxRetries] 独立配置，默认 1（`design-detail.md` §E.1）。
 */
class HttpEngine(
    connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    callTimeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
    /** 网络层重试次数（0 = 不重试）。与方言候选链无关。 */
    val maxRetries: Int = DEFAULT_MAX_RETRIES
) {
    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 8_000L
        const val DEFAULT_READ_TIMEOUT_MS = 12_000L
        const val DEFAULT_CALL_TIMEOUT_MS = 18_000L
        const val DEFAULT_MAX_RETRIES = 1

        /** 统一的 UA：与桌面 LibreTV 一致，避免被源站按 UA 识别。 */
        const val USER_AGENT = com.libretv.tv.core.USER_AGENT

        private const val TAG = "HttpEngine"

        /** 日志里不打印完整地址（可能带签名参数）。 */
        fun redact(url: String): String =
            url.substringBefore('?') + if (url.contains('?')) "?…" else ""
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .addInterceptor(browserHeaders())
        .build()

    /** 按域名补齐浏览器头：豆瓣要 Referer，采集源只要 UA。 */
    private fun browserHeaders() = Interceptor { chain ->
        val request = chain.request()
        val host = request.url.host
        val builder = request.newBuilder()
        if (request.header("User-Agent") == null) {
            builder.header("User-Agent", USER_AGENT)
        }
        if (host.endsWith("douban.com") && request.header("Referer") == null) {
            builder.header("Referer", "https://movie.douban.com/")
        }
        chain.proceed(builder.build())
    }

    /**
     * GET 一个文本响应。
     *
     * 网络层异常按 [maxRetries] 重试；HTTP 非 2xx 不重试（服务器已经明确拒绝了，
     * 再试一次只是浪费预算）。
     */
    suspend fun getText(url: String): ApiResult<String> = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/plain, */*")
            .get()
            .build()

        var lastError: SourceError? = null
        repeat(maxRetries + 1) { attempt ->
            try {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Logger.w(
                            TAG,
                            "GET ${redact(url)} -> HTTP ${resp.code}" +
                                "（${System.currentTimeMillis() - started}ms）"
                        )
                        lastError = classifyHttpFailure(resp.code)
                        // 4xx/5xx 不重试。
                        return@withContext ApiResult.Err(lastError!!)
                    }
                    val body = resp.body?.string().orEmpty()
                    Logger.d(
                        TAG,
                        "GET ${redact(url)} -> 200（${System.currentTimeMillis() - started}ms，${body.length} 字节）"
                    )
                    return@withContext ApiResult.Ok(body)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = classifyNetworkError(e)
                Logger.w(
                    TAG,
                    "GET ${redact(url)} 第 ${attempt + 1} 次失败" +
                        "（${System.currentTimeMillis() - started}ms）：${e.message}"
                )
                // 重试前稍作退避，避免对同一个死源打桩。
                if (attempt < maxRetries) {
                    try {
                        kotlinx.coroutines.delay(150)
                    } catch (ignored: kotlinx.coroutines.CancellationException) {
                        throw ignored
                    }
                }
            }
        }
        ApiResult.Err(lastError ?: SourceError.Unreachable)
    }

    /**
     * GET 一个响应的前 [limit] 字节。
     *
     * 用于清单与首片探测：完整下载一个几 MB 的 MP4 只为了量首字节没有意义。
     */
    suspend fun getBytes(url: String, limit: Int): ApiResult<ByteArray> = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-${limit - 1}")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext ApiResult.Err(classifyHttpFailure(resp.code))
                }
                val bytes = resp.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(limit)
                    val read = input.read(buffer)
                    if (read <= 0) ByteArray(0) else buffer.copyOf(read)
                } ?: ByteArray(0)
                Logger.d(
                    TAG,
                    "GET ${redact(url)} -> ${bytes.size}B（${System.currentTimeMillis() - started}ms）"
                )
                ApiResult.Ok(bytes)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.d(TAG, "GET ${redact(url)} 失败：${e.message}")
            ApiResult.Err(classifyNetworkError(e), e.message)
        }
    }

    /** 供探活引擎复用同一个连接池与超时配置。 */
    fun newClientBuilder(): OkHttpClient.Builder = client.newBuilder()
}

/**
 * HTTP 状态码归类。
 *
 * 412/403/429 在采集源语境下几乎都是风控（防盗链、限频、验证页），
 * 单独归为 [SourceError.Blocked]，用户文案比「拒绝请求」准确得多。
 */
fun classifyHttpFailure(code: Int): SourceError = when (code) {
    403, 412, 429 -> SourceError.Blocked
    in 400..499 -> SourceError.HttpFailure(code)
    in 500..599 -> SourceError.HttpFailure(code)
    else -> SourceError.HttpFailure(code)
}
