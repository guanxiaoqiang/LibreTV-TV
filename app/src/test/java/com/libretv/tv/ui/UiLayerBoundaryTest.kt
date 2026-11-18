package com.libretv.tv.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 分层边界的自动化断言。
 *
 * 规则 G1：`ui` 目录下的代码不得依赖 `data` 包——UI 只认 `domain` 里的模型与用例。
 * 这条规则靠人 review 很容易漏（新增一个 import 就破功），
 * 所以写成测试：跑一遍就知道有没有破。
 */
class UiLayerBoundaryTest {

    @Test
    fun `ui layer must not import data layer`() {
        val offenders = scanUiSources { _, line ->
            line.trimStart().startsWith("import com.libretv.tv.data.")
        }
        assertTrue(
            "ui 层不得依赖 data 层，违规导入：\n${offenders.joinToString("\n")}",
            offenders.isEmpty()
        )
    }

    @Test
    fun `page view models must not import android framework`() {
        // 页面 ViewModel 不碰 Android 框架：它们只管状态与意图。
        val offenders = scanUiSources { file, line ->
            file.path.contains("ui${File.separator}pages") &&
                line.trimStart().startsWith("import android.")
        }
        assertTrue(
            "页面 ViewModel 不得直接依赖 Android 框架：\n${offenders.joinToString("\n")}",
            offenders.isEmpty()
        )
    }

    private fun scanUiSources(matches: (File, String) -> Boolean): List<String> {
        val uiDir = File("src/main/java/com/libretv/tv/ui")
        assertTrue("找不到 ui 源码目录：${uiDir.absolutePath}", uiDir.isDirectory)
        val offenders = mutableListOf<String>()
        uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (matches(file, line)) {
                        offenders += "${file.path}:${index + 1} ${line.trim()}"
                    }
                }
            }
        return offenders
    }
}
