package com.dbstudio.app.data.db

/** 一个表的列定义 */
data class ColumnInfo(
    val cid: Int,
    val name: String,
    val type: String,
    val notNull: Boolean,
    val defaultValue: String?,
    val primaryKey: Boolean,
)

/** 通用查询结果（所有值统一字符串化，便于展示与导出） */
data class QueryResult(
    val columns: List<String>,
    val rows: List<List<String?>>,
    val elapsedMs: Long = 0L,
)

/** sqlite_master 中的一个对象 */
data class DbObject(
    val name: String,
    val type: String,       // table / view / index / trigger
    val tblName: String = "",
    val sql: String? = null,
)

/** 表/视图概览 */
data class TableOverview(
    val name: String,
    val isView: Boolean,
    val rowCount: Long,
    val columnCount: Int,
)

data class ColumnSpec(
    val name: String,
    val type: String = "TEXT",
    val primaryKey: Boolean = false,
    val notNull: Boolean = false,
    val defaultValue: String? = null,
)

class SqlError(message: String, cause: Throwable? = null) : Exception(message, cause)
