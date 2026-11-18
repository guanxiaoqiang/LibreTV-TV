package com.libretv.tv.domain.model

/**
 * TVBOX 配置导入的领域结果。
 *
 * 解析器住在 `data` 层，但它产出的结果要被 `ui` 层直接拿去渲染预览，
 * 所以类型下沉到 `domain`：ui 只认 domain 里的模型（规则 G1）。
 */
sealed class TvboxParseResult {

    /** 一条没能导入的条目，以及原因。 */
    data class Skipped(val name: String, val reason: String)

    /** 解析成功：可用源 + 被跳过的条目。 */
    data class Ok(
        /** 可导入的源。 */
        val sources: List<SourceConfig>,
        /** 被跳过的条目及原因。 */
        val skipped: List<Skipped>
    ) : TvboxParseResult()

    /** 解析失败。 */
    data class Err(val message: String) : TvboxParseResult()
}
