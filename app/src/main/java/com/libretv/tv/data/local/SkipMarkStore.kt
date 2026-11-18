package com.libretv.tv.data.local

import android.content.Context
import com.libretv.tv.domain.model.SkipMark
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 跳过标记的本地存储。
 *
 * 键是归一化片名 → 标记区间列表（design D6）。用 SP + JSON：条目少、结构
 * 简单；`local-first-storage` 变更落地后迁进 Room 的 `skip_mark` 表。
 */
class SkipMarkStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(StorageKeys.SKIP_MARKS, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 全部标记。 */
    fun marks(): List<SkipMark> {
        val raw = prefs.getString(StorageKeys.SkipMarks.RECORDS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<SkipMark>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 取某个归一化片名的标记；没有就返回 null。 */
    fun mark(titleKey: String): SkipMark? = marks().firstOrNull { it.titleKey == titleKey }

    /** 保存（按 titleKey 覆盖）。 */
    fun save(mark: SkipMark) {
        val current = marks().filterNot { it.titleKey == mark.titleKey }
        replaceAll(current + mark)
    }

    /** 删除某个片子的全部标记。 */
    fun remove(titleKey: String) {
        replaceAll(marks().filterNot { it.titleKey == titleKey })
    }

    /** 整体替换。 */
    fun replaceAll(marks: List<SkipMark>) {
        val deduped = marks.distinctBy { it.titleKey }
        prefs.edit()
            .putString(StorageKeys.SkipMarks.RECORDS, json.encodeToString(deduped))
            .apply()
    }
}
