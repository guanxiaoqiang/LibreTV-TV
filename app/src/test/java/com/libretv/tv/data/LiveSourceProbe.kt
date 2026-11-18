package com.libretv.tv.data

import com.libretv.tv.data.remote.CmsApiImpl
import com.libretv.tv.data.remote.HttpEngine
import com.libretv.tv.data.remote.parsePlayLines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Live probe against the bundled source list.
 *
 * This is a diagnostic rather than a pass/fail assertion: the point is to see how
 * many of the 30 bundled collection APIs still answer, and whether a real
 * vod_play_url parses into playable lines. Run it manually with:
 *   ./gradlew :app:testDebugUnitTest --tests "*LiveSourceProbe*"
 */
class LiveSourceProbe {

    @Test
    fun probeBundledSources() = runBlocking {
        val file = File("src/main/assets/sources.json")
        if (!file.exists()) {
            println("SKIP: assets/sources.json not found at ${file.absolutePath}")
            return@runBlocking
        }
        val text = file.readText()
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }
        val sources = json.decodeFromString<BundledSources>(text).sources
            .map { it.copy(url = unwrapSourceUrl(it.url)) }
        println("PROBE: ${sources.size} bundled sources")

        val api = CmsApiImpl(HttpEngine())
        val results = coroutineScope {
            sources.map { src ->
                async(Dispatchers.IO) {
                    val items = api.search(src, "流浪地球").getOrNull()
                    Triple(src.name, src.url, items)
                }
            }.awaitAll()
        }

        var ok = 0
        var empty = 0
        var fail = 0
        var playable = 0

        results.forEach { (name, url, items) ->
            when {
                items == null -> {
                    fail++
                    println("FAIL  $name  $url")
                }
                items.isEmpty() -> {
                    empty++
                    println("EMPTY $name  $url")
                }
                else -> {
                    ok++
                    println("OK    $name  hits=${items.size}  first=${items.first().vodName}")
                    // Try to pull a real play url for the first hit.
                    val detail = api.detail(SourceConfig(name = name, url = url), items.first().vodId).getOrNull()
                    if (detail != null) {
                        val lines = parsePlayLines(detail.playUrl)
                        if (lines.isNotEmpty()) {
                            playable++
                            println("      play OK lines=${lines.size} first=${lines.first().episodes.firstOrNull()?.url?.take(60)}")
                        } else {
                            println("      play EMPTY (raw length=${detail.playUrl.length})")
                        }
                    } else {
                        println("      detail NULL")
                    }
                }
            }
        }

        println("PROBE_SUMMARY reachable=$ok empty=$empty failed=$fail playable=$playable total=${sources.size}")
    }
}
