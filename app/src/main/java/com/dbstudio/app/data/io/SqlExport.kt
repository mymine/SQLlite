package com.dbstudio.app.data.io

import com.dbstudio.app.data.db.SqliteEngine

/** 生成 SQL 脚本：表结构（可选含数据） */
object SqlExport {

    fun dump(engine: SqliteEngine, tables: List<String>, includeData: Boolean): String {
        val sb = StringBuilder()
        sb.append("-- SQLlite export\n")
        sb.append("-- tables: ").append(tables.joinToString(", ")).append("\n\n")
        sb.append("BEGIN TRANSACTION;\n")
        for (t in tables) {
            val create = engine.createSql(t)
            if (create.isNullOrBlank()) continue
            sb.append("DROP TABLE IF EXISTS ").append(SqliteEngine.quoteIdent(t)).append(";\n")
            sb.append(create.trim().trimEnd(';')).append(";\n")
            if (includeData) {
                val res = engine.query("SELECT * FROM " + SqliteEngine.quoteIdent(t))
                for (row in res.rows) {
                    sb.append("INSERT INTO ").append(SqliteEngine.quoteIdent(t)).append(" (")
                    sb.append(res.columns.joinToString(", ") { SqliteEngine.quoteIdent(it) })
                    sb.append(") VALUES (")
                    sb.append(row.joinToString(", ") { SqliteEngine.literal(it) })
                    sb.append(");\n")
                }
            }
            sb.append("\n")
        }
        sb.append("COMMIT;\n")
        return sb.toString()
    }
}
