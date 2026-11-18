package com.libretv.tv.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 领域层纯净性断言（local-first-storage 1.5 / 2.3）。
 *
 * 规则 D2：domain 目录不依赖 Android 框架与 Room 持久化技术；这样领域层
 * 才能继续用纯 JVM 单测验证，也不因存储方案变化而重写。另加迁移规则：
 * 禁止 `fallbackToDestructiveMigration` 与裸 `DROP TABLE`。
 */
class DomainPurityTest {

    @Test
    fun `domain must not import android or room`() {
        val offenders = domainSources { _, line ->
            val t = line.trimStart()
            t.startsWith("import android.") || t.startsWith("import androidx.room.")
        }
        assertTrue(
            "domain 层不得依赖 Android / Room：\n${offenders.joinToString("\n")}",
            offenders.isEmpty()
        )
    }

    @Test
    fun `no destructive migration allowed`() {
        val offenders = allSources { _, line ->
            val t = line.trimStart()
            val isComment = t.startsWith("*") || t.startsWith("//")
            !isComment && (t.contains("fallbackToDestructiveMigration")) &&
                !t.startsWith("import ")
        }
        assertTrue(
            "禁止破坏性迁移：\n${offenders.joinToString("\n")}",
            offenders.isEmpty()
        )
    }

    @Test
    fun `no raw DROP TABLE in migration code`() {
        // Room 生成的 schema JSON 里会出现 DROP TABLE（downgrade 脚本），那不是
        // 手写迁移代码；这里只扫 Kotlin 源码。
        val offenders = allSources { file, line ->
            file.extension == "kt" &&
                line.contains("DROP TABLE", ignoreCase = true) &&
                !line.trimStart().startsWith("*") &&
                !line.trimStart().startsWith("//")
        }
        assertTrue(
            "手写迁移不得使用 DROP TABLE：\n${offenders.joinToString("\n")}",
            offenders.isEmpty()
        )
    }

    private fun domainSources(matches: (File, String) -> Boolean): List<String> =
        scanUnder(File("src/main/java/com/libretv/tv/domain"), matches)

    private fun allSources(matches: (File, String) -> Boolean): List<String> =
        scanUnder(File("src/main/java/com/libretv/tv"), matches)

    private fun scanUnder(dir: File, matches: (File, String) -> Boolean): List<String> {
        assertTrue("找不到源码目录：${dir.absolutePath}", dir.isDirectory)
        val offenders = mutableListOf<String>()
        dir.walkTopDown()
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
