package com.libretv.tv.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「键名无散落字面量」的自动化断言（tasks.md 4.4）。
 *
 * SP 文件名与内部键必须只出现在 `data/local/StorageKeys.kt`；
 * 其他任何主源码文件里出现 `libretv_` 前缀的字面量都算违规。
 * 这条规则 review 抓不住（新增一个 store 就可能漏），所以交给测试。
 */
class StorageKeysTest {

    @Test
    fun `sp key literals live only in StorageKeys`() {
        val mainDir = File("src/main/java/com/libretv/tv")
        assertTrue("找不到主源码目录：${mainDir.absolutePath}", mainDir.isDirectory)

        val offenders = mutableListOf<String>()
        mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val isStorageKeys = file.canonicalFile.endsWith(
                    File("com/libretv/tv/data/local/StorageKeys.kt")
                )
                if (isStorageKeys) return@forEach
                file.readLines().forEachIndexed { index, line ->
                    if ("libretv_" in line) {
                        offenders += "${file.path}:${index + 1} ${line.trim()}"
                    }
                }
            }
        assertTrue(
            "SP 键名字面量只能出现在 StorageKeys.kt，违规：\n${offenders.joinToString("\n")}",
            offenders.isEmpty()
        )
    }

    @Test
    fun `every store opens its sp through StorageKeys`() {
        // getSharedPreferences 的第一个参数必须是 StorageKeys 常量，不允许内联字符串。
        val offenders = mutableListOf<String>()
        File("src/main/java/com/libretv/tv").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (line.contains("getSharedPreferences(\"")) {
                        offenders += "${file.path}:${index + 1} ${line.trim()}"
                    }
                }
            }
        assertTrue(
            "getSharedPreferences 不得使用内联文件名，违规：\n${offenders.joinToString("\n")}",
            offenders.isEmpty()
        )
    }
}
