package com.libretv.tv.data

/**
 * 实测画质。
 *
 * 采集站写在 `vod_remarks` / `vod_play_from` 里的清晰度只是**声明**，同一个站
 * 上不同片目的声明经常是错的。要拿到真实画质只能去读流本身：HLS 主清单里的
 * `RESOLUTION` 与 `BANDWIDTH` 是采集站自己给的，可信度远高于备注文本。
 */
data class StreamQuality(
    val width: Int = 0,
    val height: Int = 0,
    val bandwidthBps: Int = 0
) {
    /** 由实测分辨率换算出的清晰度等级。 */
    val tier: QualityTier get() = tierOf(this)
}

/** 由实测分辨率换算清晰度等级；分辨率未知时按码率兜底，都没有返回 [QualityTier.UNKNOWN]。 */
fun tierOf(quality: StreamQuality): QualityTier =
    QualityTier.fromMeasured(quality.width, quality.height, quality.bandwidthBps)

/**
 * HLS 主清单里的一条变体：清晰度 + 它的媒体清单地址。
 *
 * 地址可能是相对路径（`hd/index.m3u8`），调用方需要按主清单的地址解析。
 */
data class HlsVariant(
    val quality: StreamQuality,
    val uri: String
)

/**
 * 解析 HLS 主清单里的全部变体。
 *
 * 只认主清单：媒体清单（`#EXTINF` + 分片）里没有 `STREAM-INF`，返回空列表。
 */
fun parseHlsVariants(text: String): List<HlsVariant> {
    val variants = mutableListOf<HlsVariant>()
    var pending: StreamQuality? = null
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        if (line.startsWith("#EXT-X-STREAM-INF")) {
            val attrs = parseHlsAttributes(line.substringAfter(':', ""))
            val resolution = attrs["RESOLUTION"]
                ?.split('x', 'X')
                ?.mapNotNull { it.trim().toIntOrNull() }
            val width = resolution?.getOrNull(0) ?: 0
            val height = resolution?.getOrNull(1) ?: 0
            val bandwidth = attrs["BANDWIDTH"]?.trim()?.toIntOrNull() ?: 0
            pending = StreamQuality(width, height, bandwidth)
            continue
        }
        if (line.startsWith("#")) continue
        // 变体地址是紧跟在 `#EXT-X-STREAM-INF` 之后的第一行非注释。
        val quality = pending ?: continue
        variants += HlsVariant(quality, line)
        pending = null
    }
    return variants
}

/**
 * 解析 HLS 主清单（`#EXT-X-STREAM-INF`），取最高的一条码率。
 *
 * 只认主清单：媒体清单（`#EXTINF` + 分片）里没有 `STREAM-INF`，返回 `null`。
 */
fun parseHlsMaster(text: String): StreamQuality? =
    parseHlsVariants(text).maxWithOrNull(
        compareBy<HlsVariant> { it.quality.height }
            .thenBy { it.quality.bandwidthBps }
    )?.quality

/**
 * 媒体清单里的第一个分片地址。
 *
 * 分片行是所有非注释、非空的行；`#EXT-X-KEY` 之类的标签行不算。
 */
fun firstSegmentUri(text: String): String? = text.lineSequence()
    .map { it.trim() }
    .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }

/** 解析 `KEY=VALUE,KEY="VALUE WITH COMMA"` 形式的属性串。 */
private fun parseHlsAttributes(raw: String): Map<String, String> {
    val parts = mutableListOf<String>()
    val builder = StringBuilder()
    var inQuote = false
    for (ch in raw) {
        when {
            ch == '"' -> {
                inQuote = !inQuote
                builder.append(ch)
            }
            ch == ',' && !inQuote -> {
                parts += builder.toString()
                builder.setLength(0)
            }
            else -> builder.append(ch)
        }
    }
    parts += builder.toString()

    val out = mutableMapOf<String, String>()
    for (part in parts) {
        val idx = part.indexOf('=')
        if (idx <= 0) continue
        val key = part.substring(0, idx).trim().uppercase()
        val value = part.substring(idx + 1).trim().trim('"')
        out[key] = value
    }
    return out
}
