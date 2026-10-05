package com.dbstudio.app.data.io

/** SQL 脚本切分：正确处理字符串、标识符引号、行注释与块注释内的分号 */
object SqlScript {

    fun split(script: String): List<String> {
        val statements = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        val n = script.length
        while (i < n) {
            val ch = script[i]
            when {
                // 行注释
                ch == '-' && i + 1 < n && script[i + 1] == '-' -> {
                    while (i < n && script[i] != '\n') i++
                    current.append('\n')
                }
                // 块注释
                ch == '/' && i + 1 < n && script[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < n && !(script[i] == '*' && script[i + 1] == '/')) i++
                    i += 2
                }
                // 字符串或标识符
                ch == '\'' || ch == '"' || ch == '`' -> {
                    val quote = ch
                    current.append(ch); i++
                    while (i < n) {
                        val c = script[i]
                        current.append(c)
                        if (c == quote) {
                            // 处理转义（'' 或 \"）
                            if (i + 1 < n && script[i + 1] == quote) {
                                current.append(script[i + 1]); i += 2; continue
                            }
                            i++; break
                        }
                        i++
                    }
                }
                ch == ';' -> {
                    val stmt = current.toString().trim()
                    if (stmt.isNotEmpty()) statements.add(stmt)
                    current.setLength(0); i++
                }
                else -> {
                    current.append(ch); i++
                }
            }
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) statements.add(tail)
        return statements
    }
}
