package com.moddingforge.truetube.data

import android.content.Context
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** YouTube search suggestions (same endpoint family the YouTube apps use, JSON variant). */
class SearchSuggestions(private val json: Json) {

    suspend fun fetch(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val lang = Locale.getDefault().language
        val url = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&hl=$lang&q=" + Uri.encode(query)
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
            }
            try {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                // ["query", ["s1", "s2", ...], ...]
                (json.parseToJsonElement(body) as JsonArray)[1].jsonArray.map { it.jsonPrimitive.content }
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(emptyList())
    }
}

/** Local search history (most recent first), like the official app's history entries. */
class SearchHistory(context: Context) {
    private val prefs = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)

    fun all(): List<String> = prefs.getString(KEY, null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    fun add(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val list = (listOf(q) + all().filterNot { it.equals(q, ignoreCase = true) }).take(MAX)
        prefs.edit().putString(KEY, list.joinToString("\n")).apply()
    }

    fun remove(query: String) {
        prefs.edit().putString(KEY, all().filterNot { it == query }.joinToString("\n")).apply()
    }

    private companion object {
        const val KEY = "entries"
        const val MAX = 25
    }
}
