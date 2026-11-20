package com.tuyen.tubeviet

import android.content.Context
import org.json.JSONArray

/** Lịch sử xem, lưu ngay trên máy. */
object History {
    private const val PREFS = "history"
    private const val KEY = "items"
    private const val MAX = 300

    private fun prefs() = App.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<Item> {
        val raw = prefs().getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Item.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(item: Item) {
        val list = all().filter { it.url != item.url }.toMutableList()
        list.add(0, item)
        val arr = JSONArray()
        list.take(MAX).forEach { arr.put(it.toJson()) }
        prefs().edit().putString(KEY, arr.toString()).apply()
    }

    fun clear() {
        prefs().edit().remove(KEY).apply()
    }

    // Vị trí đang xem dở
    fun savePosition(url: String, posMs: Long) {
        App.instance.getSharedPreferences("positions", Context.MODE_PRIVATE)
            .edit().putLong(url, posMs).apply()
    }

    fun position(url: String): Long =
        App.instance.getSharedPreferences("positions", Context.MODE_PRIVATE).getLong(url, 0)
}
