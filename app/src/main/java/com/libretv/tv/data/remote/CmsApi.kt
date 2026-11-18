package com.libretv.tv.data.remote

import com.libretv.tv.core.ApiResult
import com.libretv.tv.core.Logger
import com.libretv.tv.core.SourceError
import com.libretv.tv.data.SourceConfig
import com.libretv.tv.data.VodClass
import com.libretv.tv.data.VodItem
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Apple CMS v10 采集源的接口契约。
 *
 * 实现方（[CmsApiImpl]）负责三件事：按方言构造候选链、沿链发起请求、
 * 把解析结果包成 [ApiResult]。调用方不再关心「这个源要怎么问」，
 * 也第一次能区分「没有结果」和「源死了」。
 */
interface CmsApi {
    /** 关键词搜索。 */
    suspend fun search(source: SourceConfig, keyword: String, page: Int = 1): ApiResult<List<VodItem>>

    /** 读分类树。 */
    suspend fun categories(source: SourceConfig): ApiResult<List<VodClass>>

    /** 读一页分类片单，[typeId] 是源自己的 type_id。 */
    suspend fun categoryItems(source: SourceConfig, typeId: Int, page: Int): ApiResult<List<VodItem>>

    /** 读详情（含 `vod_play_url`）。 */
    suspend fun detail(source: SourceConfig, vodId: String): ApiResult<CmsDetail>

    /**
     * 探活：顺带把分类树取回来。
     *
     * @return 树（可能为空）表示源活着；[SourceError] 表示没应答——
     *   空树与无应答是两回事，空树同样证明源活着。
     */
    suspend fun probe(source: SourceConfig): ApiResult<List<VodClass>>
}

/** 详情响应：远端条目 + 原始播放地址串（由 [parsePlayLines] 解析）。 */
data class CmsDetail(
    val item: VodItem,
    val playUrl: String
)

/**
 * [CmsApi] 的默认实现。
 *
 * 候选链共享一个截止时间（[chainTimeoutMs]）：链上的第 2、第 3 个候选
 * 用的是**剩余**预算，不是又一个全额预算。
 */
class CmsApiImpl(
    private val engine: HttpEngine,
    private val json: kotlinx.serialization.json.Json = CmsDialects.defaultJson,
    /** 整条候选链的总预算。 */
    private val chainTimeoutMs: Long = DEFAULT_CHAIN_TIMEOUT_MS
) : CmsApi {
    companion object {
        /** 与扇出预算对齐：单源 10s，这里留一点余量给候选链。 */
        const val DEFAULT_CHAIN_TIMEOUT_MS = 10_000L
    }

    private fun dialectOf(source: SourceConfig) = CmsDialects.byId(source.dialect)

    private inline fun <reified T> decode(text: String): T? = try {
        json.decodeFromString<T>(text)
    } catch (e: Exception) {
        null
    }

    /**
     * 沿候选链依次请求，命中即止。
     *
     * 候选之间不重试（换候选不是重试），网络层的重试由 [HttpEngine] 负责。
     * 整条链共用 [chainTimeoutMs]；超时归类为 [SourceError.Timeout]。
     *
     * @param emptyIsResult 所有候选都答了但内容为空时，`null` 表示「合法的空结果」
     *   （返回 [ApiResult.Ok]），非 `null` 表示「确实没拿到东西」（[SourceError.EmptyResult]）。
     */
    private suspend fun <T> walk(
        source: SourceConfig,
        candidates: List<Map<String, String>>,
        parse: (String) -> T?,
        accept: (T) -> Boolean,
        emptyIsResult: (() -> T)? = null
    ): ApiResult<T> {
        if (candidates.isEmpty()) {
            return ApiResult.Err(SourceError.UnsupportedDialect, "方言未提供候选链")
        }
        val dialect = dialectOf(source)
        val started = System.currentTimeMillis()

        var firstError: ApiResult.Err? = null

        val result = try {
            withTimeoutOrNull(chainTimeoutMs) {
                for ((index, params) in candidates.withIndex()) {
                    val url = dialect.url(source.url, params)
                    val body = when (val resp = engine.getText(url)) {
                        is com.libretv.tv.core.ApiResult.Ok -> resp.value
                        is com.libretv.tv.core.ApiResult.Err -> {
                            // 记下第一个错误继续往后问：换个候选可能就通了。
                            if (firstError == null) firstError = resp
                            continue
                        }
                    }
                    val parsed = parse(body) ?: continue
                    if (!accept(parsed)) continue
                    Logger.d(
                        "CmsApi",
                        "${source.name} 命中候选 #${index + 1}/${candidates.size}"
                    )
                    return@withTimeoutOrNull ApiResult.Ok(parsed)
                }
                // 链上每个候选都试过了。
                null
            }
        } catch (e: TimeoutCancellationException) {
            Logger.w("CmsApi", "${source.name} 候选链超时（${chainTimeoutMs}ms）")
            null
        } ?: run {
            val elapsed = System.currentTimeMillis() - started
            if (elapsed >= chainTimeoutMs) {
                ApiResult.Err(SourceError.Timeout, "候选链超时")
            } else {
                null
            }
        }

        if (result != null) return result
        firstError?.let { return it }
        return if (emptyIsResult != null) {
            ApiResult.Ok(emptyIsResult())
        } else {
            ApiResult.Err(SourceError.EmptyResult)
        }
    }

    override suspend fun search(
        source: SourceConfig,
        keyword: String,
        page: Int
    ): ApiResult<List<VodItem>> {
        val dialect = dialectOf(source)
        return walk(
            source,
            dialect.searchCandidates(source.url, keyword, page),
            { dialect.parseList(it) },
            { it.isNotEmpty() },
            emptyIsResult = { emptyList() }
        )
    }

    override suspend fun categories(source: SourceConfig): ApiResult<List<VodClass>> {
        val dialect = dialectOf(source)
        return walk(
            source,
            dialect.categoryCandidates(source.url),
            { dialect.parseCategories(it) },
            { it.isNotEmpty() },
            emptyIsResult = { emptyList() }
        )
    }

    override suspend fun categoryItems(
        source: SourceConfig,
        typeId: Int,
        page: Int
    ): ApiResult<List<VodItem>> {
        val dialect = dialectOf(source)
        return walk(
            source,
            dialect.categoryItemCandidates(source.url, typeId, page),
            { dialect.parseList(it) },
            { it.isNotEmpty() },
            emptyIsResult = { emptyList() }
        )
    }

    override suspend fun detail(source: SourceConfig, vodId: String): ApiResult<CmsDetail> {
        val dialect = dialectOf(source)
        val raw = walk(
            source,
            dialect.detailCandidates(source.url, vodId),
            { dialect.parseDetail(it) },
            // 命中 = 条目里带着可播地址。条目在但没有地址不算数：继续往
            // 候选链下一个走（ac=detail → ac=videolist），而不是把一个
            // 空壳详情当成成功返回。
            { it.vodPlayUrl.isNotBlank() }
        )
        return when (raw) {
            is com.libretv.tv.core.ApiResult.Ok ->
                com.libretv.tv.core.ApiResult.Ok(CmsDetail(raw.value, raw.value.vodPlayUrl))
            is com.libretv.tv.core.ApiResult.Err -> raw
        }
    }

    override suspend fun probe(source: SourceConfig): ApiResult<List<VodClass>> {
        val dialect = dialectOf(source)
        val started = System.currentTimeMillis()
        // 探活用候选链的第一项（最便宜的那个）；探测的目的在于「有没有应答」，
        // 沿链多问没有意义。
        val url = dialect.url(source.url, dialect.probeCandidates(source.url).first())
        val text = engine.getText(url)
        return when (text) {
            is com.libretv.tv.core.ApiResult.Ok -> {
                val tree = dialect.parseCategories(text.value).orEmpty()
                Logger.d(
                    "CmsApi",
                    "探活 ${source.name} -> ${tree.size} 类（${System.currentTimeMillis() - started}ms）"
                )
                ApiResult.Ok(tree)
            }
            is com.libretv.tv.core.ApiResult.Err -> text
        }
    }
}
