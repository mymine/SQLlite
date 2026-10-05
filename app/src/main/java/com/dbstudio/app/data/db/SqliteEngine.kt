package com.dbstudio.app.data.db

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import java.io.File

/**
 * 对 android.database.sqlite.SQLiteDatabase 的薄封装，
 * 提供管理工具所需的元数据、查询与结构变更能力。
 */
class SqliteEngine {

    private var db: SQLiteDatabase? = null

    var path: String? = null
        private set

    var readOnly: Boolean = false
        private set

    val isOpen: Boolean get() = db?.isOpen == true

    fun open(filePath: String, readOnly: Boolean): Result<Unit> = runCatching {
        close()
        val flags = if (readOnly) {
            SQLiteDatabase.OPEN_READONLY
        } else {
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY
        }
        val database = SQLiteDatabase.openDatabase(filePath, null, flags)
        database.enableWriteAheadLogging() // 只读库会被忽略
        db = database
        path = filePath
        this.readOnly = readOnly
    }

    fun close() {
        runCatching { db?.close() }
        db = null
        path = null
    }

    private fun requireDb(): SQLiteDatabase =
        db?.takeIf { it.isOpen } ?: throw SqlError("数据库尚未打开")

    // ---------------------------------------------------------------- 查询

    fun query(sql: String, args: List<Any?> = emptyList()): QueryResult {
        val database = requireDb()
        val start = System.currentTimeMillis()
        val cursor = database.rawQuery(sql, args.map { it?.toString() }.toTypedArray())
        cursor.use { c ->
            val columns = c.columnNames.toList()
            val rows = ArrayList<List<String?>>()
            while (c.moveToNext()) {
                val row = ArrayList<String?>(columns.size)
                for (i in columns.indices) row.add(cellToString(c, i))
                rows.add(row)
            }
            return QueryResult(columns, rows, System.currentTimeMillis() - start)
        }
    }

    /** 执行非查询语句，返回受影响行数（尽力而为） */
    fun exec(sql: String): Long {
        val database = requireDb()
        database.execSQL(sql)
        return runCatching {
            database.rawQuery("SELECT changes()", null).use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
        }.getOrDefault(0L)
    }

    /** 执行多条语句（按分号切分后逐条执行），返回执行条数 */
    fun execScript(statements: List<String>): Int {
        val database = requireDb()
        var count = 0
        database.beginTransaction()
        try {
            for (stmt in statements) {
                val s = stmt.trim()
                if (s.isEmpty()) continue
                database.execSQL(s)
                count++
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return count
    }

    private fun cellToString(c: Cursor, i: Int): String? = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i).toString()
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i).toString()
        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)?.let { "«BLOB ${it.size} B»" }
        else -> c.getString(i)
    }

    // ------------------------------------------------------------ 元数据

    fun objects(type: String): List<DbObject> =
        query(
            "SELECT name, type, tbl_name, sql FROM sqlite_master " +
                "WHERE type=? AND name NOT LIKE 'sqlite_%' ORDER BY name COLLATE NOCASE",
            listOf(type),
        ).rows.map {
            DbObject(it[0] ?: "", it[1] ?: type, it[2] ?: "", it[3])
        }

    fun tables(): List<DbObject> = objects("table")
    fun views(): List<DbObject> = objects("view")
    fun indexes(): List<DbObject> = objects("index")
    fun triggers(): List<DbObject> = objects("trigger")

    fun tableNames(includeViews: Boolean = false): List<String> {
        val list = ArrayList<String>()
        tables().forEach { list.add(it.name) }
        if (includeViews) views().forEach { list.add(it.name) }
        return list
    }

    fun columns(table: String): List<ColumnInfo> =
        query("PRAGMA table_info(${quoteIdent(table)})").rows.map { row ->
            ColumnInfo(
                cid = row.getOrNull(0)?.toIntOrNull() ?: 0,
                name = row.getOrNull(1) ?: "",
                type = row.getOrNull(2).orEmpty().ifEmpty { "BLOB" },
                notNull = (row.getOrNull(3)?.toIntOrNull() ?: 0) != 0,
                defaultValue = row.getOrNull(4),
                primaryKey = (row.getOrNull(5)?.toIntOrNull() ?: 0) != 0,
            )
        }

    fun createSql(table: String): String? =
        query(
            "SELECT sql FROM sqlite_master WHERE name=? AND type IN ('table','view')",
            listOf(table),
        ).rows.firstOrNull()?.firstOrNull()

    fun foreignKeys(table: String): QueryResult =
        query("PRAGMA foreign_key_list(${quoteIdent(table)})")

    fun rowCount(table: String): Long =
        query("SELECT COUNT(*) FROM ${quoteIdent(table)}").rows.firstOrNull()?.firstOrNull()
            ?.toLongOrNull() ?: 0L

    fun tableOverviews(): List<TableOverview> {
        val result = ArrayList<TableOverview>()
        tables().forEach { t ->
            val count = runCatching { rowCount(t.name) }.getOrDefault(-1L)
            val colCount = runCatching { columns(t.name).size }.getOrDefault(0)
            result.add(TableOverview(t.name, false, count, colCount))
        }
        views().forEach { v ->
            result.add(TableOverview(v.name, true, -1L, runCatching { columns(v.name).size }.getOrDefault(0)))
        }
        return result
    }

    // ------------------------------------------------------------ 数据页

    fun pageRows(
        table: String,
        limit: Int,
        offset: Int,
        orderBy: String? = null,
        descending: Boolean = false,
    ): QueryResult {
        val order = if (orderBy.isNullOrBlank()) {
            ""
        } else {
            " ORDER BY ${quoteIdent(orderBy)} ${if (descending) "DESC" else "ASC"}"
        }
        return query("SELECT * FROM ${quoteIdent(table)}$order LIMIT ? OFFSET ?", listOf(limit, offset))
    }

    fun insert(table: String, values: Map<String, Any?>): Long {
        val database = requireDb()
        val cols = values.keys.toList()
        val placeholders = cols.joinToString(", ") { "?" }
        val sql = "INSERT INTO ${quoteIdent(table)} (${cols.joinToString(", ") { quoteIdent(it) }}) VALUES ($placeholders)"
        val stmt: SQLiteStatement = database.compileStatement(sql)
        try {
            cols.forEachIndexed { index, col -> bindValue(stmt, index + 1, values[col]) }
            return stmt.executeInsert()
        } finally {
            runCatching { stmt.close() }
        }
    }

    fun update(table: String, values: Map<String, Any?>, where: String, whereArgs: List<Any?>): Int {
        val database = requireDb()
        val setClause = values.keys.joinToString(", ") { "${quoteIdent(it)}=?" }
        val sql = "UPDATE ${quoteIdent(table)} SET $setClause" + if (where.isBlank()) "" else " WHERE $where"
        val stmt = database.compileStatement(sql)
        try {
            var idx = 1
            values.values.forEach { bindValue(stmt, idx++, it) }
            whereArgs.forEach { bindValue(stmt, idx++, it) }
            return stmt.executeUpdateDelete()
        } finally {
            runCatching { stmt.close() }
        }
    }

    fun <T> transaction(block: () -> T): T {
        val database = requireDb()
        database.beginTransaction()
        try {
            val result = block()
            database.setTransactionSuccessful()
            return result
        } finally {
            database.endTransaction()
        }
    }

    fun delete(table: String, where: String, whereArgs: List<Any?>): Int {
        val database = requireDb()
        val sql = "DELETE FROM ${quoteIdent(table)}" + if (where.isBlank()) "" else " WHERE $where"
        val stmt = database.compileStatement(sql)
        try {
            whereArgs.forEachIndexed { i, v -> bindValue(stmt, i + 1, v) }
            return stmt.executeUpdateDelete()
        } finally {
            runCatching { stmt.close() }
        }
    }

    private fun bindValue(stmt: SQLiteStatement, index: Int, value: Any?) {
        when (value) {
            null -> stmt.bindNull(index)
            is Long -> stmt.bindLong(index, value)
            is Int -> stmt.bindLong(index, value.toLong())
            is Double -> stmt.bindDouble(index, value)
            is Float -> stmt.bindDouble(index, value.toDouble())
            is Boolean -> stmt.bindLong(index, if (value) 1 else 0)
            is ByteArray -> stmt.bindBlob(index, value)
            else -> stmt.bindString(index, value.toString())
        }
    }

    // ------------------------------------------------------------ 结构变更

    fun createTable(table: String, columns: List<ColumnSpec>) {
        require(columns.isNotEmpty()) { "至少需要一列" }
        val defs = columns.joinToString(", ") { c ->
            buildString {
                append(quoteIdent(c.name))
                if (c.type.isNotBlank()) append(" ").append(c.type)
                if (c.primaryKey) append(" PRIMARY KEY")
                if (c.notNull && !c.primaryKey) append(" NOT NULL")
                if (!c.defaultValue.isNullOrBlank()) append(" DEFAULT ").append(c.defaultValue)
            }
        }
        requireDb().execSQL("CREATE TABLE ${quoteIdent(table)} ($defs)")
    }

    fun dropTable(table: String) {
        requireDb().execSQL("DROP TABLE IF EXISTS ${quoteIdent(table)}")
    }

    fun renameTable(oldName: String, newName: String) {
        requireDb().execSQL("ALTER TABLE ${quoteIdent(oldName)} RENAME TO ${quoteIdent(newName)}")
    }

    fun addColumn(table: String, column: ColumnSpec) {
        val sb = StringBuilder("ALTER TABLE ${quoteIdent(table)} ADD COLUMN ${quoteIdent(column.name)}")
        if (column.type.isNotBlank()) sb.append(" ").append(column.type)
        if (column.notNull) sb.append(" NOT NULL")
        if (!column.defaultValue.isNullOrBlank()) sb.append(" DEFAULT ").append(column.defaultValue)
        else if (column.notNull) sb.append(" DEFAULT ''")
        requireDb().execSQL(sb.toString())
    }

    fun dropColumn(table: String, column: String) {
        requireDb().execSQL("ALTER TABLE ${quoteIdent(table)} DROP COLUMN ${quoteIdent(column)}")
    }

    fun vacuum() {
        requireDb().execSQL("VACUUM")
    }

    fun compactFile(target: File) {
        val database = requireDb()
        database.execSQL("VACUUM INTO ?", arrayOf(target.absolutePath))
    }

    companion object {
        fun quoteIdent(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

        /** 将任意值转换为可写入 SQL 的字面量 */
        fun literal(value: String?): String = when {
            value == null -> "NULL"
            else -> "'" + value.replace("'", "''") + "'"
        }
    }
}
