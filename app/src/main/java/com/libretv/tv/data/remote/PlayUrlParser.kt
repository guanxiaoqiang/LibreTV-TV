package com.libretv.tv.data.remote

import com.libretv.tv.data.Episode
import com.libretv.tv.data.PlayLine

/**
 * 解析 Apple CMS 的 `vod_play_url` 字段。
 *
 * 真实采集站对这个字段的写法有好几种，这里全部接受：
 *
 *   A) `线路名$$$第1集$url#第2集$url$$$线路名2$$$...`   (classic maccms)
 *   B) `线路名$$第1集$url#第2集$url$$$线路名2$$$...`   (name glued to its block)
 *   C) `第1集$url#第2集$url`                            (single unnamed line)
 *
 * 集数用 `#` 分隔，集名与地址用单个 `$` 分隔。没有 `$` 的段落视为裸地址，
 * 两者都不像的段落视为下一线路的名称。
 */
fun parsePlayLines(raw: String?): List<PlayLine> {
    if (raw.isNullOrBlank()) return emptyList()

    val lines = mutableListOf<PlayLine>()
    var pendingName: String? = null

    for (segment in raw.split("$$$")) {
        val seg = segment.trim()
        if (seg.isEmpty()) continue

        var explicitName: String? = null
        var content = seg
        val glueIdx = seg.indexOf("$$")
        if (glueIdx >= 0) {
            explicitName = seg.substring(0, glueIdx).trim().takeIf { it.isNotEmpty() }
            content = seg.substring(glueIdx + 2).trim()
        }

        val episodes = parseEpisodes(content)
        if (episodes.isNotEmpty()) {
            val name = explicitName
                ?: pendingName
                ?: "线路${lines.size + 1}"
            lines += PlayLine(lines.size, name, episodes)
            pendingName = null
        } else {
            // No episodes in this segment -> it carries the name of the next line.
            val carried = explicitName ?: content.takeIf { it.isNotBlank() }
            if (!carried.isNullOrBlank()) pendingName = carried
        }
    }
    return lines
}

/** Split one episode block into episodes. */
private fun parseEpisodes(content: String): List<Episode> {
    if (content.isBlank()) return emptyList()
    val episodes = mutableListOf<Episode>()
    for (segment in content.split("#")) {
        val seg = segment.trim()
        if (seg.isEmpty()) continue
        val parts = seg.split("$", limit = 2)
        val name: String
        val url: String
        if (parts.size >= 2) {
            name = parts[0].trim()
            url = parts[1].trim()
        } else if (looksLikeUrl(seg)) {
            name = ""
            url = seg
        } else {
            continue
        }
        if (url.isEmpty()) continue
        val position = episodes.size
        episodes += Episode(position, name.ifBlank { "第${position + 1}集" }, url)
    }
    return episodes
}

private fun looksLikeUrl(value: String): Boolean {
    val lower = value.lowercase()
    return lower.startsWith("http://") ||
        lower.startsWith("https://") ||
        lower.startsWith("rtsp://") ||
        lower.startsWith("rtmp://") ||
        lower.contains(".m3u8") ||
        lower.contains(".mp4")
}
