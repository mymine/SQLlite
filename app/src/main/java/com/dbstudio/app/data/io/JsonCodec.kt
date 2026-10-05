package com.dbstudio.app.data.io

import org.json.JSONArray
import org.json.JSONObject

/** JSON 编解码：以对象数组 [{col:val}, ...] 表示表数据 */
object JsonCodec {

    fun encodeRows(columns: List<String>, rows: List<List<String?>>): String {
        val arr = JSONArray()
        for (row in rows) {
            val obj = JSONObject()
            columns.forEachIndexed { i, c ->
                val raw = row.getOrNull(i)
                obj.put(c, smartValue(raw))
            }
            arr.put(obj)
        }
        return arr.toString(2)
    }

    /** 从 JSON 文本推断列并解析出数据矩阵 */
    fun decode(text: String): Pair<List<String>, List<List<String?>>> {
        val trimmed = text.trim()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> {
                val obj = JSONObject(trimmed)
                JSONArray().put(obj)
            }
        }
        val columns = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            o.keys().forEach { columns.add(it) }
        }
        val cols = columns.toList()
        val rows = ArrayList<List<String?>>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val row = ArrayList<String?>(cols.size)
            cols.forEach { c ->
                row.add(
                    when {
                        !o.has(c) || o.isNull(c) -> null
                        else -> o.get(c).toString()
                    }
                )
            }
            rows.add(row)
        }
        return cols to rows
    }

    private fun smartValue(raw: String?): Any {
        if (raw == null) return JSONObject.NULL
        raw.toLongOrNull()?.let { return it }
        raw.toDoubleOrNull()?.let { return it }
        return when (raw.lowercase()) {
            "true" -> true
            "false" -> false
            else -> raw
        }
    }
}
