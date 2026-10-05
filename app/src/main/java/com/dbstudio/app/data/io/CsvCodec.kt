package com.dbstudio.app.data.io

/** CSV / 分隔符文本的编码与解析（RFC4180 兼容） */
object CsvCodec {

    fun encode(
        columns: List<String>,
        rows: List<List<String?>>,
        delimiter: Char = ',',
    ): String {
        val sb = StringBuilder()
        sb.append(columns.joinToString(delimiter.toString()) { escape(it, delimiter) }).append('\n')
        for (row in rows) {
            sb.append(row.joinToString(delimiter.toString()) { escape(it ?: "", delimiter) }).append('\n')
        }
        return sb.toString()
    }

    private fun escape(s: String, d: Char): String {
        val needQuote = s.contains(d) || s.contains('"') || s.contains('\n') || s.contains('\r')
        val v = s.replace("\"", "\"\"")
        return if (needQuote) "\"$v\"" else v
    }

    /** 解析为矩阵；空白行被忽略 */
    fun parse(text: String, delimiter: Char = ','): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var rowStarted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                inQuotes -> {
                    if (ch == '"') {
                        if (i + 1 < text.length && text[i + 1] == '"') {
                            cell.append('"'); i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        cell.append(ch)
                    }
                }
                ch == '"' -> {
                    inQuotes = true; rowStarted = true
                }
                ch == delimiter -> {
                    row.add(cell.toString()); cell.setLength(0); rowStarted = true
                }
                ch == '\r' -> { /* 忽略 */ }
                ch == '\n' -> {
                    row.add(cell.toString()); cell.setLength(0)
                    if (rowStarted || row.size > 1) rows.add(row)
                    row = ArrayList(); rowStarted = false
                }
                else -> {
                    cell.append(ch); rowStarted = true
                }
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            rows.add(row)
        }
        return rows.filter { r -> r.any { it.isNotEmpty() } }
    }
}
