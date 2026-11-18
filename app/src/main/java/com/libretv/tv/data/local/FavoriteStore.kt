package com.libretv.tv.data.local

import android.content.Context
import com.libretv.tv.domain.gateway.FavoriteGateway
import com.libretv.tv.domain.model.FavoriteEntry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 收藏的本地存储（SharedPreferences，JSON 数组）。
 *
 * 收藏条数不多（几十条），SP 足够；`local-first-storage` 变更落地后
 * 迁进 Room 的 `favorite` 表，键名集中在 [StorageKeys]。
 */
class FavoriteStore(context: Context) : FavoriteGateway {

    private val prefs = context.applicationContext
        .getSharedPreferences(StorageKeys.FAVORITES, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun favorites(): List<FavoriteEntry> {
        val raw = prefs.getString(StorageKeys.Favorites.RECORDS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<FavoriteEntry>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun save(entry: FavoriteEntry) {
        val current = favorites().filterNot { it.titleKey == entry.titleKey }
        replaceAll(current + entry)
    }

    override fun remove(titleKey: String) {
        replaceAll(favorites().filterNot { it.titleKey == titleKey })
    }

    override fun replaceAll(entries: List<FavoriteEntry>) {
        // 按归一化片名去重，保留先出现的那条。
        val deduped = entries.distinctBy { it.titleKey }
        prefs.edit()
            .putString(StorageKeys.Favorites.RECORDS, json.encodeToString(deduped))
            .apply()
    }
}
