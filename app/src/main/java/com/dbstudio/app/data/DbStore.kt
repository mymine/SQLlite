package com.dbstudio.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 轻量持久化：最近打开的数据库 + 偏好设置 */
object DbStore {

    private const val PREF = "dbstudio_prefs"
    private const val KEY_RECENT = "recent_dbs"

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun loadRecent(context: Context): List<DatabaseRef> {
        val raw = prefs(context).getString(KEY_RECENT, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        DatabaseRef(
                            id = o.optString("id"),
                            name = o.optString("name"),
                            sourceUri = o.optString("uri").ifBlank { null },
                            localPath = o.optString("path"),
                            readOnly = o.optBoolean("readOnly", false),
                            sizeBytes = o.optLong("size", 0L),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun rememberRecent(context: Context, ref: DatabaseRef) {
        val list = loadRecent(context).toMutableList()
        list.removeAll { it.id == ref.id || it.localPath == ref.localPath }
        list.add(0, ref)
        val trimmed = list.take(30)
        val arr = JSONArray()
        trimmed.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("name", r.name)
                    put("uri", r.sourceUri ?: "")
                    put("path", r.localPath)
                    put("readOnly", r.readOnly)
                    put("size", r.sizeBytes)
                }
            )
        }
        prefs(context).edit().putString(KEY_RECENT, arr.toString()).apply()
    }

    fun forget(context: Context, id: String) {
        val list = loadRecent(context).filterNot { it.id == id }
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("name", r.name)
                    put("uri", r.sourceUri ?: "")
                    put("path", r.localPath)
                    put("readOnly", r.readOnly)
                    put("size", r.sizeBytes)
                }
            )
        }
        prefs(context).edit().putString(KEY_RECENT, arr.toString()).apply()
    }

    // ------------------------------------------------------------ 偏好

    var pageSize: Int
        get() = prefsHolder!!.getInt("page_size", 200)
        set(v) = prefsHolder!!.edit().putInt("page_size", v).apply()

    var themeMode: Int // 0 system, 1 light, 2 dark
        get() = prefsHolder!!.getInt("theme_mode", 0)
        set(v) = prefsHolder!!.edit().putInt("theme_mode", v).apply()

    private var prefsHolder: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefsHolder = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }
}
