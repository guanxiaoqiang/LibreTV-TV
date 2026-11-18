package com.libretv.tv.core

import com.libretv.tv.domain.model.CatalogTitle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地优先搜索单测（local-first-storage 6.1–6.4）。
 */
class LocalFirstSearchTest {

    private fun title(key: String, name: String) = CatalogTitle(titleKey = key, title = name)

    // ---------- 6.1 本地索引匹配（别名 + 标题） ----------
    @Test
    fun `local index matches title and alias`() {
        // 模拟 catalog_alias 表：标题与别名都作为可匹配项。
        val index = mapOf(
            "流浪地球" to listOf("片A"),
            "liu lang di qiu" to listOf("片A"), // 拼音别名
            "星际穿越" to listOf("片B")
        )
        fun lookup(keyword: String) = index.filterKeys { it.contains(keyword) }.values.flatten().distinct()

        assertEquals(listOf("片A"), lookup("流浪地球"))
        assertEquals(listOf("片A"), lookup("liu lang"))   // 别名命中
        assertTrue(lookup("不存在").isEmpty())
    }

    // ---------- 6.2 命中不发起网络，无命中扇出 ----------
    @Test
    fun `local hit returns without fanout`() = runBlocking {
        val search = LocalFirstSearch()
        var fanoutCalls = 0
        val outcome = search.search(
            keyword = "流浪",
            localLookup = { listOf(title("a", "流浪地球")) },
            fanout = { fanoutCalls++; listOf(title("x", "远端")) }
        )
        assertTrue(outcome.fromLocal)
        assertFalse(outcome.fannedOut)
        assertEquals(0, fanoutCalls)
        assertEquals("流浪地球", outcome.results.first().title)
    }

    @Test
    fun `no local hit falls back to fanout`() = runBlocking {
        val search = LocalFirstSearch()
        var fanoutCalls = 0
        val outcome = search.search(
            keyword = "冷门",
            localLookup = { emptyList() },
            fanout = { fanoutCalls++; listOf(title("x", "远端结果")) }
        )
        assertFalse(outcome.fromLocal)
        assertTrue(outcome.fannedOut)
        assertEquals(1, fanoutCalls)
    }

    // ---------- 6.3 追加后顺序不变 ----------
    @Test
    fun `backfill is appended without disturbing existing order`() {
        val search = LocalFirstSearch()
        val existing = listOf(title("a", "A"), title("b", "B"))
        val backfilled = listOf(title("b", "B-新来源"), title("c", "C"))

        val merged = search.appendBackfill(existing, backfilled)

        assertEquals(listOf("a", "b", "c"), merged.map { it.titleKey })
        // 已存在的 b 不被重复追加，且位置仍是第 2。
        assertEquals("B", merged[1].title)
    }

    // ---------- 6.4 待补采队列 ----------
    @Test
    fun `miss keyword is enqueued for backfill`() = runBlocking {
        val search = LocalFirstSearch()
        search.search("无命中词", { emptyList() }, { emptyList() })
        assertEquals(1, search.backfillSize())
        assertEquals("无命中词", search.pollBackfill())
        assertEquals(0, search.backfillSize())
    }
}
