package com.libretv.tv.domain.model

/**
 * 观看记录的权威键规则（local-first-storage 4.1）。
 *
 * 权威键 = 来源 + 影片 ID + 线路 + 剧集；**播放地址只是快照**——同一集从
 * 同一来源的不同地址播放，仍只产生一条记录。只有拿不到影片 ID 时才退化
 * 用「片名 + 剧集名」（此时不含易变的签名/令牌地址）。
 */
object WatchKeys {

    fun episodeKey(
        sourceName: String,
        vodId: String,
        lineIndex: Int,
        episodeIndex: Int,
        title: String,
        episodeName: String,
        rawUrl: String = ""
    ): String = if (vodId.isNotBlank()) {
        // 有影片 ID：权威四元组，播放地址只是快照不参与身份。
        listOf(sourceName, vodId, lineIndex.toString(), episodeIndex.toString()).joinToString("|")
    } else {
        // 无影片 ID：按 watch-history 规格退化为「标题 + 剧集名 + 播放地址」。
        listOf(title, episodeName, rawUrl).joinToString("|")
    }
}
