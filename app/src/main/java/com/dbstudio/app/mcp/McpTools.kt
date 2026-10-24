package com.dbstudio.app.mcp

import android.content.Context
import com.dbstudio.app.data.AppPrefs
import com.dbstudio.app.data.DatabaseRef
import com.dbstudio.app.data.DbManager
import com.dbstudio.app.data.DbStore
import com.dbstudio.app.data.db.ColumnSpec
import com.dbstudio.app.data.db.QueryResult
import com.dbstudio.app.data.db.SqliteEngine
import com.dbstudio.app.data.diff.DataDiffer
import com.dbstudio.app.data.diff.SchemaDiffer
import com.dbstudio.app.data.io.CsvCodec
import com.dbstudio.app.data.io.JsonCodec
import com.dbstudio.app.data.io.SqlExport
import com.dbstudio.app.data.io.SqlScript
import com.dbstudio.app.util.DbFileHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MCP 工具集：把 SQLlite 的全部能力暴露为可被大模型调用的工具。
 *
 * 所有工具都直接操作应用的全局数据库会话（DbManager.engine），
 * 因此通过 MCP 修改的数据与手机界面完全一致。
 */
object McpTools {

    private const val MAX_ROWS = 5000

    data class Outcome(
        val text: String,
        val structured: JSONObject? = null,
        val isError: Boolean = false,
    )

    private class Tool(
        val name: String,
        val description: String,
        val schema: JSONObject,
        val handler: (JSONObject) -> Outcome,
    )

    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun context(): Context =
        appContext ?: throw IllegalStateException("MCP 工具尚未初始化")

    private val tools: List<Tool> by lazy { buildTools() }
    private val indexes: Map<String, Tool> by lazy { tools.associateBy { it.name } }

    fun count(): Int = tools.size

    fun listJson(): JSONArray {
        val array = JSONArray()
        tools.forEach { tool ->
            array.put(
                JSONObject()
                    .put("name", tool.name)
                    .put("description", tool.description)
                    .put("inputSchema", tool.schema),
            )
        }
        return array
    }

    fun call(name: String, args: JSONObject): Outcome {
        val tool = indexes[name]
            ?: return Outcome("未知工具：$name。可调用 tools/list 查看全部工具。", isError = true)
        return try {
            tool.handler(args)
        } catch (t: Throwable) {
            Outcome("执行失败：${t.message ?: t.javaClass.simpleName}", isError = true)
        }
    }

    // ------------------------------------------------------------ Schema 辅助

    private fun objectSchema(
        vararg props: Pair<String, JSONObject>,
        required: List<String> = emptyList(),
    ): JSONObject {
        val properties = JSONObject()
        props.forEach { properties.put(it.first, it.second) }
        return JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray(required))
    }

    private fun str(desc: String, enum: List<String>? = null): JSONObject {
        val obj = JSONObject().put("type", "string").put("description", desc)
        if (enum != null) obj.put("enum", JSONArray(enum))
        return obj
    }

    private fun num(desc: String): JSONObject =
        JSONObject().put("type", "integer").put("description", desc)

    private fun bool(desc: String, default: Boolean? = null): JSONObject {
        val obj = JSONObject().put("type", "boolean").put("description", desc)
        if (default != null) obj.put("default", default)
        return obj
    }

    private fun obj(desc: String): JSONObject =
        JSONObject().put("type", "object").put("description", desc)

    private fun arr(desc: String, itemType: String = "string"): JSONObject =
        JSONObject()
            .put("type", "array")
            .put("description", desc)
            .put("items", JSONObject().put("type", itemType))

    // ------------------------------------------------------------ 通用辅助

    private fun JSONObject.req(name: String): String {
        val value = optString(name, "")
        if (value.isBlank()) throw IllegalArgumentException("缺少参数 $name")
        return value
    }

    private fun JSONArray?.toAnyList(): List<Any?> {
        if (this == null) return emptyList()
        val list = ArrayList<Any?>(length())
        for (i in 0 until length()) list.add(if (isNull(i)) null else opt(i))
        return list
    }

    private fun JSONArray?.toStringList(): List<String?> {
        if (this == null) return emptyList()
        val list = ArrayList<String?>(length())
        for (i in 0 until length()) list.add(if (isNull(i)) null else optString(i))
        return list
    }

    private fun toMap(json: JSONObject): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        json.keys().forEach { key -> map[key] = if (json.isNull(key)) null else json.get(key) }
        return map
    }

    private fun engine(): SqliteEngine {
        val e = DbManager.engine
        if (!e.isOpen) throw IllegalStateException("当前没有打开的数据库，请先调用 db_open 或 db_create")
        return e
    }

    private fun requireTable(name: String?): String {
        val table = name?.trim().orEmpty()
        if (table.isEmpty()) throw IllegalArgumentException("缺少参数 table")
        return table
    }

    private fun quote(name: String): String = SqliteEngine.quoteIdent(name)

    private fun queryToJson(result: QueryResult): JSONObject {
        val columns = JSONArray()
        result.columns.forEach { columns.put(it) }
        val rows = JSONArray()
        result.rows.forEach { row ->
            val item = JSONArray()
            row.forEach { value -> if (value == null) item.put(JSONObject.NULL) else item.put(value) }
            rows.put(item)
        }
        return JSONObject()
            .put("columns", columns)
            .put("rows", rows)
            .put("row_count", result.rows.size)
            .put("elapsed_ms", result.elapsedMs)
    }

    private fun queryOutcome(title: String, result: QueryResult): Outcome {
        val structured = queryToJson(result)
        val preview = structured.toString(2)
        val text = if (preview.length > 120_000) {
            preview.take(120_000) + "\n…（结果过长已截断，可减少 limit 或改用导出工具）"
        } else {
            preview
        }
        return Outcome("$title：${result.rows.size} 行\n$text", structured)
    }

    private fun dbInfoJson(): JSONObject {
        val ref = DbManager.current
        val e = DbManager.engine
        val info = JSONObject().put("open", e.isOpen)
        if (ref != null) {
            val file = File(ref.localPath)
            info.put("name", ref.name)
            info.put("path", ref.localPath)
            info.put("read_only", ref.readOnly)
            info.put("size_bytes", if (file.exists()) file.length() else 0L)
            info.put("source_uri", ref.sourceUri ?: JSONObject.NULL)
        }
        if (e.isOpen) {
            info.put("tables", e.tables().size)
            info.put("views", e.views().size)
            info.put("indexes", e.indexes().size)
            info.put("triggers", e.triggers().size)
            runCatching { info.put("journal_mode", e.query("PRAGMA journal_mode").rows.firstOrNull()?.firstOrNull()) }
            runCatching { info.put("page_size", e.query("PRAGMA page_size").rows.firstOrNull()?.firstOrNull()) }
            runCatching { info.put("encoding", e.query("PRAGMA encoding").rows.firstOrNull()?.firstOrNull()) }
        }
        return info
    }

    private fun findDbFile(nameOrPath: String): File {
        val direct = File(nameOrPath)
        if (direct.isAbsolute) return direct

        val ctx = context()
        DbStore.loadRecent(ctx).firstOrNull {
            it.name == nameOrPath || File(it.localPath).name == nameOrPath
        }?.let { return File(it.localPath) }

        val dir = DbFileHelper.dbsDir(ctx)
        val candidate = File(dir, nameOrPath)
        if (candidate.exists()) return candidate
        val withExt = File(dir, if (nameOrPath.contains('.')) nameOrPath else "$nameOrPath.db")
        if (withExt.exists()) return withExt
        return direct
    }

    private fun openDatabase(file: File, readOnly: Boolean): DatabaseRef {
        if (!file.exists()) throw IllegalArgumentException("文件不存在：${file.absolutePath}")
        val ref = DatabaseRef(
            id = "mcp:${file.absolutePath}",
            name = file.name,
            localPath = file.absolutePath,
            readOnly = readOnly,
            sizeBytes = file.length(),
        )
        DbManager.openDb(ref).getOrThrow()
        runCatching { DbStore.rememberRecent(context(), ref) }
        return ref
    }

    private fun runScript(statements: List<String>): Int {
        val control = setOf("BEGIN", "BEGIN TRANSACTION", "BEGIN DEFERRED", "BEGIN IMMEDIATE", "BEGIN EXCLUSIVE", "COMMIT", "END", "END TRANSACTION", "ROLLBACK")
        val filtered = statements.filter { it.trim().uppercase() !in control }
        if (filtered.isEmpty()) return 0
        return engine().execScript(filtered)
    }

    private fun writeTextFile(path: String, content: String): File {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    // ------------------------------------------------------------ 工具定义

    private fun buildTools(): List<Tool> {
        val list = ArrayList<Tool>()

        // ------------------------------ 数据库 ------------------------------

        list += Tool(
            name = "db_list",
            description = "列出数据库：当前打开的数据库、最近使用记录，以及应用私有目录 dbs/ 下的所有 .db 文件。",
            schema = objectSchema(),
            handler = {
                val ctx = context()
                val result = JSONObject()

                val current = DbManager.current
                result.put(
                    "current",
                    if (current == null) JSONObject.NULL else JSONObject()
                        .put("name", current.name)
                        .put("path", current.localPath)
                        .put("read_only", current.readOnly),
                )

                val recent = JSONArray()
                DbStore.loadRecent(ctx).forEach { ref ->
                    recent.put(
                        JSONObject()
                            .put("name", ref.name)
                            .put("path", ref.localPath)
                            .put("read_only", ref.readOnly)
                            .put("exists", File(ref.localPath).exists()),
                    )
                }
                result.put("recent", recent)

                val local = JSONArray()
                DbFileHelper.dbsDir(ctx).listFiles()
                    ?.filter { it.isFile }
                    ?.sortedBy { it.name.lowercase() }
                    ?.forEach { file ->
                        local.put(
                            JSONObject()
                                .put("name", file.name)
                                .put("path", file.absolutePath)
                                .put("size_bytes", file.length())
                                .put("modified", file.lastModified()),
                        )
                    }
                result.put("local_files", local)

                Outcome(result.toString(2), result)
            },
        )

        list += Tool(
            name = "db_open",
            description = "打开一个 SQLite 数据库文件。path 为绝对路径；name 可以是最近记录中的文件名或 dbs/ 目录下的文件名。",
            schema = objectSchema(
                "path" to str("数据库文件的绝对路径，例如 /storage/emulated/0/test.db"),
                "name" to str("数据库文件名（当没有提供 path 时使用）"),
                "read_only" to bool("是否以只读模式打开", false),
                required = emptyList(),
            ),
            handler = { args ->
                val path = args.optString("path", "")
                val name = args.optString("name", "")
                if (path.isBlank() && name.isBlank()) {
                    throw IllegalArgumentException("需要提供 path 或 name")
                }
                val file = if (path.isNotBlank()) File(path) else findDbFile(name)
                openDatabase(file, args.optBoolean("read_only", false))
                Outcome("已打开数据库 ${file.name}\n${dbInfoJson().toString(2)}", dbInfoJson())
            },
        )

        list += Tool(
            name = "db_create",
            description = "新建一个空的 SQLite 数据库并立即打开。name 为文件名（自动补 .db 后缀），默认保存到应用私有 dbs/ 目录；也可用 path 指定绝对路径。",
            schema = objectSchema(
                "name" to str("数据库文件名，例如 demo.db"),
                "path" to str("可选的绝对路径；提供后忽略 name 的目录部分"),
                "overwrite" to bool("文件已存在时是否覆盖", false),
                required = listOf("name"),
            ),
            handler = { args ->
                val name = args.req("name")
                val rawPath = args.optString("path", "")
                val file = if (rawPath.isNotBlank()) {
                    File(rawPath)
                } else {
                    val fileName = if (name.contains('.')) name else "$name.db"
                    File(DbFileHelper.dbsDir(context()), DbFileHelper.sanitize(fileName))
                }
                file.parentFile?.mkdirs()
                if (file.exists()) {
                    if (!args.optBoolean("overwrite", false)) {
                        throw IllegalArgumentException("文件已存在：${file.absolutePath}，如需覆盖请传 overwrite=true")
                    }
                    file.delete()
                }
                android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).close()
                openDatabase(file, false)
                Outcome("已创建并打开数据库 ${file.absolutePath}", dbInfoJson())
            },
        )

        list += Tool(
            name = "db_close",
            description = "关闭当前数据库。",
            schema = objectSchema(),
            handler = {
                if (!DbManager.engine.isOpen) {
                    Outcome("当前没有打开的数据库", isError = false)
                } else {
                    DbManager.closeCurrent()
                    Outcome("数据库已关闭")
                }
            },
        )

        list += Tool(
            name = "db_info",
            description = "查看当前数据库信息：文件路径、大小、表/视图/索引/触发器数量、日志模式、编码。",
            schema = objectSchema(),
            handler = {
                val info = dbInfoJson()
                Outcome(info.toString(2), info)
            },
        )

        list += Tool(
            name = "db_write_back",
            description = "把当前数据库的改动写回原始文件（仅适用于通过系统文件选择器打开、带有 sourceUri 的数据库）。",
            schema = objectSchema(),
            handler = {
                val ref = DbManager.current ?: throw IllegalStateException("当前没有打开的数据库")
                val uri = ref.sourceUri
                    ?: throw IllegalStateException("当前数据库不是通过系统文件选择器打开的，没有可写回的原件")
                runCatching { DbManager.engine.query("PRAGMA wal_checkpoint(TRUNCATE)") }
                DbFileHelper.writeBack(context(), android.net.Uri.parse(uri), File(ref.localPath))
                Outcome("已写回原件：$uri")
            },
        )

        list += Tool(
            name = "db_vacuum",
            description = "对当前数据库执行 VACUUM，回收空间并整理文件。",
            schema = objectSchema(),
            handler = {
                engine().vacuum()
                Outcome("VACUUM 完成")
            },
        )

        list += Tool(
            name = "db_compact",
            description = "使用 VACUUM INTO 把当前数据库压缩导出为另一个文件。",
            schema = objectSchema(
                "path" to str("输出文件的绝对路径"),
                required = listOf("path"),
            ),
            handler = { args ->
                val target = File(args.req("path"))
                target.parentFile?.mkdirs()
                if (target.exists()) target.delete()
                engine().compactFile(target)
                Outcome("已压缩导出到 ${target.absolutePath}（${target.length()} 字节）")
            },
        )

        // ------------------------------ 表 ------------------------------

        list += Tool(
            name = "table_list",
            description = "列出当前数据库中的所有表和视图，包含行数与列数。",
            schema = objectSchema(
                "include_views" to bool("是否包含视图", true),
            ),
            handler = { args ->
                val includeViews = args.optBoolean("include_views", true)
                val e = engine()
                val items = JSONArray()
                e.tableOverviews()
                    .filter { includeViews || !it.isView }
                    .forEach { overview ->
                        items.put(
                            JSONObject()
                                .put("name", overview.name)
                                .put("type", if (overview.isView) "view" else "table")
                                .put("rows", if (overview.rowCount < 0) JSONObject.NULL else overview.rowCount)
                                .put("columns", overview.columnCount),
                        )
                    }
                val result = JSONObject().put("items", items).put("count", items.length())
                Outcome("共 ${items.length()} 个对象\n${result.toString(2)}", result)
            },
        )

        list += Tool(
            name = "table_schema",
            description = "查看指定表的完整结构：列定义、建表 SQL、索引、外键。",
            schema = objectSchema(
                "table" to str("表名"),
                required = listOf("table"),
            ),
            handler = { args ->
                val table = requireTable(args.optString("table", ""))
                val e = engine()

                val columns = JSONArray()
                e.columns(table).forEach { column ->
                    columns.put(
                        JSONObject()
                            .put("cid", column.cid)
                            .put("name", column.name)
                            .put("type", column.type)
                            .put("not_null", column.notNull)
                            .put("default", column.defaultValue ?: JSONObject.NULL)
                            .put("primary_key", column.primaryKey),
                    )
                }

                val indexes = JSONArray()
                e.objects("index")
                    .filter { it.tblName.equals(table, ignoreCase = true) }
                    .forEach { indexes.put(JSONObject().put("name", it.name).put("sql", it.sql ?: "")) }

                val result = JSONObject()
                    .put("table", table)
                    .put("columns", columns)
                    .put("create_sql", e.createSql(table) ?: "")
                    .put("indexes", indexes)
                    .put("foreign_keys", queryToJson(e.foreignKeys(table)))
                    .put("row_count", runCatching { e.rowCount(table) }.getOrDefault(-1L))

                Outcome(result.toString(2), result)
            },
        )

        list += Tool(
            name = "table_create",
            description = "创建新表。columns 为列定义数组，每项包含 name、type、primary_key、not_null、default。",
            schema = objectSchema(
                "table" to str("新表名"),
                "columns" to JSONObject()
                    .put("type", "array")
                    .put("description", "列定义数组")
                    .put(
                        "items",
                        JSONObject().put("type", "object").put(
                            "properties",
                            JSONObject()
                                .put("name", JSONObject().put("type", "string"))
                                .put("type", JSONObject().put("type", "string").put("description", "如 TEXT / INTEGER / REAL / BLOB"))
                                .put("primary_key", JSONObject().put("type", "boolean"))
                                .put("not_null", JSONObject().put("type", "boolean"))
                                .put("default", JSONObject().put("type", "string")),
                        ).put("required", JSONArray().put("name")),
                    ),
                required = listOf("table", "columns"),
            ),
            handler = { args ->
                val table = requireTable(args.optString("table", ""))
                val columnsJson = args.optJSONArray("columns")
                    ?: throw IllegalArgumentException("缺少参数 columns")
                val specs = ArrayList<ColumnSpec>()
                for (i in 0 until columnsJson.length()) {
                    val column = columnsJson.optJSONObject(i) ?: continue
                    val name = column.optString("name", "")
                    if (name.isBlank()) throw IllegalArgumentException("columns[$i].name 不能为空")
                    specs.add(
                        ColumnSpec(
                            name = name,
                            type = column.optString("type", "TEXT").ifBlank { "TEXT" },
                            primaryKey = column.optBoolean("primary_key", false),
                            notNull = column.optBoolean("not_null", false),
                            defaultValue = if (column.has("default") && !column.isNull("default")) {
                                column.optString("default")
                            } else {
                                null
                            },
                        ),
                    )
                }
                if (specs.isEmpty()) throw IllegalArgumentException("至少需要一列")
                engine().createTable(table, specs)
                DbManager.refreshTables()
                Outcome("已创建表 $table（${specs.size} 列）")
            },
        )

        list += Tool(
            name = "table_drop",
            description = "删除指定表（不可恢复）。",
            schema = objectSchema(
                "table" to str("要删除的表名"),
                required = listOf("table"),
            ),
            handler = { args ->
                val table = requireTable(args.optString("table", ""))
                engine().dropTable(table)
                DbManager.refreshTables()
                Outcome("已删除表 $table")
            },
        )

        list += Tool(
            name = "table_rename",
            description = "重命名表。",
            schema = objectSchema(
                "table" to str("原表名"),
                "new_name" to str("新表名"),
                required = listOf("table", "new_name"),
            ),
            handler = { args ->
                val table = requireTable(args.optString("table", ""))
                val newName = args.req("new_name")
                engine().renameTable(table, newName)
                DbManager.refreshTables()
                Outcome("已将表 $table 重命名为 $newName")
            },
        )

        list += Tool(
            name = "table_add_column",
            description = "为表添加一列。",
            schema = objectSchema(
                "table" to str("表名"),
                "name" to str("新列名"),
                "type" to str("列类型，默认 TEXT"),
                "not_null" to bool("是否 NOT NULL", false),
                "default" to str("默认值表达式，如 '' 或 0"),
                required = listOf("table", "name"),
            ),
            handler = { args ->
                val table = requireTable(args.optString("table", ""))
                val spec = ColumnSpec(
                    name = args.req("name"),
                    type = args.optString("type", "TEXT").ifBlank { "TEXT" },
                    notNull = args.optBoolean("not_null", false),
                    defaultValue = if (args.has("default") && !args.isNull("default")) args.optString("default") else null,
                )
                engine().addColumn(table, spec)
                DbManager.refreshTables()
                Outcome("已为表 $table 添加列 ${spec.name}")
            },
        )

        list += Tool(
            name = "table_drop_column",
            description = "删除表中的一列（不可恢复）。",
            schema = objectSchema(
                "table" to str("表名"),
                "column" to str("要删除的列名"),
                required = listOf("table", "column"),
            ),
            handler = { args ->
                val table = requireTable(args.optString("table", ""))
                val column = args.req("column")
                engine().dropColumn(table, column)
                DbManager.refreshTables()
                Outcome("已删除表 $table 的列 $column")
            },
        )

        // ------------------------------ 数据 ------------------------------

        list += Tool(
            name = "data_query",
            description = "执行任意 SQL。SELECT / PRAGMA / WITH / EXPLAIN 返回结果集（自动追加 LIMIT），其它语句返回受影响行数。",
            schema = objectSchema(
                "sql" to str("要执行的 SQL 语句"),
                "params" to arr("绑定参数数组，用于 ? 占位符"),
                "limit" to num("查询结果最大行数，默认 500，上限 $MAX_ROWS"),
                required = listOf("sql"),
            ),
            handler = { args ->
                val sql = args.req("sql").trim().trimEnd(';')
                val limit = args.optInt("limit", 500).coerceIn(1, MAX_ROWS)
                val params = args.optJSONArray("params").toAnyList()
                val isQuery = Regex("^(select|pragma|with|explain)\\b", RegexOption.IGNORE_CASE).containsMatchIn(sql)
                if (isQuery) {
                    val hasLimit = Regex("\\blimit\\b", RegexOption.IGNORE_CASE).containsMatchIn(sql)
                    val finalSql = if (hasLimit) sql else "$sql LIMIT $limit"
                    queryOutcome("查询结果", engine().query(finalSql, params))
                } else {
                    val affected = engine().exec(sql)
                    DbManager.refreshTables()
                    Outcome("执行成功，受影响行数：$affected")
                }
            },
        )

        list += Tool(
            name = "data_rows",
            description = "分页读取表数据，可选排序列与降序。",
            schema = objectSchema(
                "table" to str("表名"),
                "limit" to num("返回行数，默认 100，上限 $MAX_ROWS"),
                "offset" to num("偏移量，默认 0"),
                "order_by" to str("排序列名"),
                "descending" to bool("是否降序", false),
                required = listOf("table"),
            ),
            handler = { args ->
                val table = requireTable(args.optString("table", ""))
                val limit = args.optInt("limit", 100).coerceIn(1, MAX_ROWS)
                val offset = args.optInt("offset", 0).coerceAtLeast(0)
                val orderBy = args.optString("order_by", "").ifBlank { null }
                val result = engine().pageRows(table, limit, offset, orderBy, args.optBoolean("descending", false))
                queryOutcome("表 $table", result)
            },
        )

        list += Tool(
            name = "data_count",
            description = "统计表行数，可带 where 条件。",
            schema = objectSchema(
                "table" to str("表名"),
                "where" to str("WHERE 条件（不含 WHERE 关键字），如 age > ?"),
                "where_args" to arr("where 中的 ? 参数"),
                required = listOf("table"),
            ),
            handler = { args ->
                val table = requireTable(args.optString("table", ""))
                val where = args.optString("where", "")
                val whereArgs = args.optJSONArray("where_args").toAnyList()
                val sql = "SELECT COUNT(*) FROM ${quote(table)}" +
                    if (where.isBlank()) "" else " WHERE $where"
                val count = engine().query(sql, whereArgs).rows.firstOrNull()?.firstOrNull()?.toLongOrNull() ?: 0L
                Outcome("表 $table 共 $count 行", JSONObject().put("table", table).put("count", count))
            },
        )

        list += Tool(
            name = "data_insert",
            description = "向表插入数据。values 为单行对象；rows 为多行对象数组（批量在事务中执行）。",
            schema = objectSchema(
                "table" to str("表名"),
                "values" to obj("单行数据，如 {\"name\":\"Alice\",\"age\":20}"),
                "rows" to JSONObject()
                    .put("type", "array")
                    .put("description", "多行数据数组")
                    .put("items", JSONObject().put("type", "object")),
                required = listOf("table"),
            ),
            handler = { args ->
                val table = requireTable(args.optString("table", ""))
                val rows = ArrayList<Map<String, Any?>>()
                args.optJSONObject("values")?.let { rows.add(toMap(it)) }
                args.optJSONArray("rows")?.let { array ->
                    for (i in 0 until array.length()) {
                        array.optJSONObject(i)?.let { rows.add(toMap(it)) }
                    }
                }
                if (rows.isEmpty()) throw IllegalArgumentException("需要提供 values 或 rows")
                val e = engine()
                val ids = e.transaction { rows.map { e.insert(table, it) } }
                DbManager.refreshTables()
                val result = JSONObject().put("inserted", ids.size).put("row_ids", JSONArray(ids))
                Outcome("已插入 ${ids.size} 行，rowid：${ids.joinToString(", ")}", result)
            },
        )

        list += Tool(
            name = "data_update",
            description = "更新表数据。必须提供 where 条件；更新全部行请显式传 where=\"1=1\"。",
            schema = objectSchema(
                "table" to str("表名"),
                "values" to obj("要更新的列与新值"),
                "where" to str("WHERE 条件（不含 WHERE 关键字），如 id = ?"),
                "where_args" to arr("where 中的 ? 参数"),
                required = listOf("table", "values", "where"),
            ),
            handler = { args ->
                val table = requireTable(args.optString("table", ""))
                val values = args.optJSONObject("values") ?: throw IllegalArgumentException("缺少参数 values")
                val where = args.optString("where", "")
                if (where.isBlank()) throw IllegalArgumentException("必须提供 where 条件；如需更新全部行请传 where=\"1=1\"")
                val count = engine().update(table, toMap(values), where, args.optJSONArray("where_args").toAnyList())
                DbManager.refreshTables()
                Outcome("已更新 $count 行", JSONObject().put("updated", count))
            },
        )

        list += Tool(
            name = "data_delete",
            description = "删除表数据。必须提供 where 条件；删除全部行请显式传 where=\"1=1\"。",
            schema = objectSchema(
                "table" to str("表名"),
                "where" to str("WHERE 条件（不含 WHERE 关键字），如 id = ?"),
                "where_args" to arr("where 中的 ? 参数"),
                required = listOf("table", "where"),
            ),
            handler = { args ->
                val table = requireTable(args.optString("table", ""))
                val where = args.optString("where", "")
                if (where.isBlank()) throw IllegalArgumentException("必须提供 where 条件；如需删除全部行请传 where=\"1=1\"")
                val count = engine().delete(table, where, args.optJSONArray("where_args").toAnyList())
                DbManager.refreshTables()
                Outcome("已删除 $count 行", JSONObject().put("deleted", count))
            },
        )

        list += Tool(
            name = "data_search",
            description = "在表的所有列（或指定列）中对关键字做模糊匹配。",
            schema = objectSchema(
                "table" to str("表名"),
                "query" to str("搜索关键字"),
                "columns" to arr("限定搜索的列名，省略则搜索全部列"),
                "limit" to num("返回行数，默认 100"),
                required = listOf("table", "query"),
            ),
            handler = { args ->
                val table = requireTable(args.optString("table", ""))
                val keyword = args.req("query")
                val limit = args.optInt("limit", 100).coerceIn(1, MAX_ROWS)
                val e = engine()
                val columns = args.optJSONArray("columns")?.toStringList()
                    ?.filterNotNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?: e.columns(table).map { it.name }
                val where = columns.joinToString(" OR ") { "CAST(${quote(it)} AS TEXT) LIKE ?" }
                val sql = "SELECT * FROM ${quote(table)} WHERE $where LIMIT $limit"
                queryOutcome("搜索 \"$keyword\"", e.query(sql, columns.map { "%$keyword%" }))
            },
        )

        list += Tool(
            name = "sql_execute",
            description = "执行单条 SQL 或整段脚本（按分号切分、在事务中执行）。脚本中的 BEGIN/COMMIT 会被自动忽略以避免嵌套事务。",
            schema = objectSchema(
                "sql" to str("SQL 语句或脚本"),
                "script" to bool("是否按脚本模式执行（默认自动判断）", null),
                required = listOf("sql"),
            ),
            handler = { args ->
                val sql = args.req("sql")
                val statements = SqlScript.split(sql)
                val isScript = args.optBoolean("script", statements.size > 1)
                if (isScript) {
                    val count = runScript(statements)
                    DbManager.refreshTables()
                    McpLog.add("sql", "执行脚本 $count 条语句", true)
                    Outcome("脚本执行完成，共 $count 条语句")
                } else {
                    val single = statements.firstOrNull() ?: throw IllegalArgumentException("SQL 为空")
                    val isQuery = Regex("^(select|pragma|with|explain)\\b", RegexOption.IGNORE_CASE).containsMatchIn(single)
                    if (isQuery) {
                        queryOutcome("查询结果", engine().query(single))
                    } else {
                        val affected = engine().exec(single)
                        DbManager.refreshTables()
                        Outcome("执行成功，受影响行数：$affected")
                    }
                }
            },
        )

        // ------------------------------ 导入导出 ------------------------------

        list += Tool(
            name = "export_data",
            description = "导出数据。format=csv/txt/json 时导出单表数据；format=sql 时可导出指定表或整个数据库的结构（可选含数据）。传 path 写入文件，否则返回文本内容。",
            schema = objectSchema(
                "format" to str("导出格式", listOf("csv", "txt", "json", "sql")),
                "table" to str("要导出的表（csv/txt/json 必填）"),
                "tables" to arr("format=sql 时要导出的表名数组，省略则导出全部表"),
                "include_data" to bool("format=sql 时是否包含数据", true),
                "limit" to num("最多导出的行数，默认 100000"),
                "delimiter" to str("csv 分隔符，默认逗号；txt 默认制表符"),
                "path" to str("输出文件绝对路径；省略则直接返回文本"),
                required = listOf("format"),
            ),
            handler = { args ->
                val format = args.req("format").lowercase()
                val table = args.optString("table", "")
                val limit = args.optInt("limit", 100_000).coerceIn(1, 1_000_000)
                val e = engine()

                val content: String
                val extension: String
                when (format) {
                    "sql" -> {
                        val tablesArg = args.optJSONArray("tables")?.toStringList()?.filterNotNull()
                        val targets = when {
                            !tablesArg.isNullOrEmpty() -> tablesArg
                            table.isNotBlank() -> listOf(table)
                            else -> e.tableNames()
                        }
                        if (targets.isEmpty()) throw IllegalStateException("当前数据库没有可导出的表")
                        content = SqlExport.dump(e, targets, args.optBoolean("include_data", true))
                        extension = "sql"
                    }

                    "csv", "txt" -> {
                        val target = requireTable(table)
                        val rows = e.query("SELECT * FROM ${quote(target)} LIMIT $limit")
                        val delimiter = if (format == "txt") {
                            args.optString("delimiter", "\t").firstOrNull() ?: '\t'
                        } else {
                            args.optString("delimiter", ",").firstOrNull() ?: ','
                        }
                        content = CsvCodec.encode(rows.columns, rows.rows, delimiter)
                        extension = format
                    }

                    "json" -> {
                        val target = requireTable(table)
                        val rows = e.query("SELECT * FROM ${quote(target)} LIMIT $limit")
                        content = JsonCodec.encodeRows(rows.columns, rows.rows)
                        extension = "json"
                    }

                    else -> throw IllegalArgumentException("不支持的格式：$format（可选 csv / txt / json / sql）")
                }

                val path = args.optString("path", "")
                if (path.isNotBlank()) {
                    val target = if (File(path).isDirectory) {
                        val base = if (table.isNotBlank()) table else "database"
                        File(path, "${base}_${stamp()}.$extension")
                    } else {
                        File(path)
                    }
                    writeTextFile(target.absolutePath, content)
                    val result = JSONObject()
                        .put("path", target.absolutePath)
                        .put("bytes", target.length())
                        .put("format", format)
                    Outcome("已导出到 ${target.absolutePath}（${target.length()} 字节）", result)
                } else {
                    val capped = if (content.length > 200_000) {
                        content.take(200_000) + "\n…（内容过长已截断，可传 path 写入文件）"
                    } else {
                        content
                    }
                    Outcome(capped, JSONObject().put("format", format).put("chars", content.length).put("truncated", content.length > 200_000))
                }
            },
        )

        list += Tool(
            name = "import_data",
            description = "导入数据。format=sql 执行整段脚本；format=csv/txt 导入到指定表；format=json 从对象数组导入。mode=replace 会先删表重建（列类型统一 TEXT）。数据来源可传 path 或 content。",
            schema = objectSchema(
                "format" to str("导入格式", listOf("csv", "txt", "json", "sql")),
                "table" to str("目标表（csv/txt/json 必填）"),
                "path" to str("源文件绝对路径"),
                "content" to str("直接提供的文本内容（与 path 二选一）"),
                "mode" to str("append 追加 / replace 覆盖建表", listOf("append", "replace")),
                "has_header" to bool("csv/txt 首行是否为表头", true),
                "delimiter" to str("csv 分隔符，默认逗号；txt 默认制表符"),
                required = listOf("format"),
            ),
            handler = { args ->
                val format = args.req("format").lowercase()
                val inline = args.optString("content", "")
                val path = args.optString("path", "")
                val text = when {
                    inline.isNotBlank() -> inline
                    path.isNotBlank() -> File(path).let { file ->
                        if (!file.exists()) throw IllegalArgumentException("文件不存在：$path")
                        file.readText()
                    }

                    else -> throw IllegalArgumentException("需要提供 path 或 content")
                }

                when (format) {
                    "sql" -> {
                        val statements = SqlScript.split(text)
                        val count = runScript(statements)
                        DbManager.refreshTables()
                        Outcome("导入完成，执行 $count 条语句")
                    }

                    "csv", "txt" -> {
                        val table = requireTable(args.optString("table", ""))
                        val delimiter = if (format == "txt") {
                            args.optString("delimiter", "\t").firstOrNull() ?: '\t'
                        } else {
                            args.optString("delimiter", ",").firstOrNull() ?: ','
                        }
                        val matrix = CsvCodec.parse(text, delimiter)
                        if (matrix.isEmpty()) throw IllegalArgumentException("文件内容为空")
                        val hasHeader = args.optBoolean("has_header", true)
                        val header = if (hasHeader) matrix.first() else emptyList()
                        val dataRows = if (hasHeader) matrix.drop(1) else matrix

                        val e = engine()
                        val replace = args.optString("mode", "append").equals("replace", true)
                        val existing = e.tables().any { it.name.equals(table, true) }
                        val columns = when {
                            existing && !replace -> e.columns(table).map { it.name }
                            header.isNotEmpty() -> header.mapIndexed { i, name -> name.ifBlank { "col_$i" } }
                            else -> (0 until (dataRows.maxOfOrNull { it.size } ?: 0)).map { "col_$it" }
                        }
                        if (columns.isEmpty()) throw IllegalArgumentException("无法确定列名，请开启 has_header 或在 CSV 中提供表头")

                        val inserted = insertRows(table, columns, dataRows, replace)
                        DbManager.refreshTables()
                        Outcome("已导入 $inserted 行到表 $table（${columns.size} 列）")
                    }

                    "json" -> {
                        val table = requireTable(args.optString("table", ""))
                        val (columns, rows) = JsonCodec.decode(text)
                        if (columns.isEmpty()) throw IllegalArgumentException("JSON 中没有可用字段")
                        val replace = args.optString("mode", "append").equals("replace", true)
                        val inserted = insertRows(table, columns, rows, replace)
                        DbManager.refreshTables()
                        Outcome("已导入 $inserted 行到表 $table（${columns.size} 列）")
                    }

                    else -> throw IllegalArgumentException("不支持的格式：$format（可选 csv / txt / json / sql）")
                }
            },
        )

        // ------------------------------ 对比 ------------------------------

        list += Tool(
            name = "diff_schema",
            description = "对比两张表的结构。left_path / right_path 省略时使用当前打开的数据库；可配合 left_table / right_table 对比不同表。",
            schema = objectSchema(
                "table" to str("两侧使用的同名表名"),
                "left_table" to str("左侧表名（默认同 table）"),
                "right_table" to str("右侧表名（默认同 table）"),
                "left_path" to str("左侧数据库文件路径（默认当前数据库）"),
                "right_path" to str("右侧数据库文件路径（默认当前数据库）"),
                required = emptyList(),
            ),
            handler = { args ->
                val table = args.optString("table", "")
                val leftTable = args.optString("left_table", table).ifBlank { throw IllegalArgumentException("需要 table 或 left_table") }
                val rightTable = args.optString("right_table", table).ifBlank { leftTable }
                val leftPath = args.optString("left_path", "")
                val rightPath = args.optString("right_path", "")

                withEngines(leftPath, rightPath) { left, right ->
                    val result = SchemaDiffer.diff(
                        leftTable,
                        left.columns(leftTable),
                        rightTable,
                        right.columns(rightTable),
                    )
                    val changes = JSONArray()
                    result.columns.forEach { change ->
                        changes.put(
                            JSONObject()
                                .put("column", change.name)
                                .put("kind", change.kind.name)
                                .put("left_type", change.leftType ?: JSONObject.NULL)
                                .put("right_type", change.rightType ?: JSONObject.NULL)
                                .put("detail", change.detail),
                        )
                    }
                    val payload = JSONObject()
                        .put("left", result.leftName)
                        .put("right", result.rightName)
                        .put("changes", changes)
                    Outcome("结构对比完成，共 ${changes.length()} 处条目\n${payload.toString(2)}", payload)
                }
            },
        )

        list += Tool(
            name = "diff_data",
            description = "对比两张表的数据。key_column 用于按主键匹配，省略则按整行匹配。",
            schema = objectSchema(
                "table" to str("两侧使用的同名表名"),
                "left_table" to str("左侧表名（默认同 table）"),
                "right_table" to str("右侧表名（默认同 table）"),
                "left_path" to str("左侧数据库文件路径（默认当前数据库）"),
                "right_path" to str("右侧数据库文件路径（默认当前数据库）"),
                "key_column" to str("用于匹配行的主键列名"),
                "limit" to num("每侧最多读取的行数，默认 2000"),
                required = emptyList(),
            ),
            handler = { args ->
                val table = args.optString("table", "")
                val leftTable = args.optString("left_table", table).ifBlank { throw IllegalArgumentException("需要 table 或 left_table") }
                val rightTable = args.optString("right_table", table).ifBlank { leftTable }
                val leftPath = args.optString("left_path", "")
                val rightPath = args.optString("right_path", "")
                val limit = args.optInt("limit", 2000).coerceIn(1, 20_000)

                withEngines(leftPath, rightPath) { left, right ->
                    val leftRows = left.query("SELECT * FROM ${quote(leftTable)} LIMIT $limit")
                    val rightRows = right.query("SELECT * FROM ${quote(rightTable)} LIMIT $limit")
                    val keyColumn = args.optString("key_column", "")
                    val keyIndex = if (keyColumn.isNotBlank()) {
                        leftRows.columns.indexOfFirst { it.equals(keyColumn, ignoreCase = true) }
                            .takeIf { it >= 0 }
                    } else {
                        null
                    }
                    val diff = DataDiffer.diff(leftRows.columns, keyIndex, leftRows.rows, rightRows.rows)
                    val rows = JSONArray()
                    diff.rows.take(200).forEach { row ->
                        rows.put(
                            JSONObject()
                                .put("key", row.key)
                                .put("kind", row.kind.name)
                                .put("changed_columns", JSONArray(row.changedColumns))
                                .put("left", row.left?.let { JSONArray(it) } ?: JSONObject.NULL)
                                .put("right", row.right?.let { JSONArray(it) } ?: JSONObject.NULL),
                        )
                    }
                    val payload = JSONObject()
                        .put("columns", JSONArray(diff.columns))
                        .put("key", diff.keyLabel)
                        .put("added", diff.addedCount)
                        .put("removed", diff.removedCount)
                        .put("changed", diff.changedCount)
                        .put("same", diff.sameCount)
                        .put("rows", rows)
                    Outcome(
                        "数据对比：新增 ${diff.addedCount}，删除 ${diff.removedCount}，变更 ${diff.changedCount}，相同 ${diff.sameCount}\n${payload.toString(2)}",
                        payload,
                    )
                }
            },
        )

        // ------------------------------ 文件 ------------------------------

        list += Tool(
            name = "file_list",
            description = "列出设备目录内容（用于查找可导入的文件或数据库）。可访问 /storage/emulated/0 需要「所有文件访问」权限。",
            schema = objectSchema(
                "path" to str("目录绝对路径，默认 /storage/emulated/0"),
            ),
            handler = { args ->
                val path = args.optString("path", "/storage/emulated/0").ifBlank { "/storage/emulated/0" }
                val dir = File(path)
                if (!dir.exists()) throw IllegalArgumentException("目录不存在：$path")
                if (!dir.isDirectory) throw IllegalArgumentException("不是目录：$path")

                val items = JSONArray()
                dir.listFiles()
                    ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    ?.forEach { file ->
                        items.put(
                            JSONObject()
                                .put("name", file.name)
                                .put("path", file.absolutePath)
                                .put("is_dir", file.isDirectory)
                                .put("size_bytes", if (file.isDirectory) 0L else file.length())
                                .put("modified", file.lastModified()),
                        )
                    }
                val payload = JSONObject().put("path", dir.absolutePath).put("items", items)
                Outcome("${dir.absolutePath} 下 ${items.length()} 个条目\n${payload.toString(2)}", payload)
            },
        )

        list += Tool(
            name = "file_read",
            description = "读取文本文件内容（最多读取 2MB 或指定字节数）。",
            schema = objectSchema(
                "path" to str("文件绝对路径"),
                "max_bytes" to num("最多读取的字节数，默认 262144"),
                required = listOf("path"),
            ),
            handler = { args ->
                val path = args.req("path")
                val file = File(path)
                if (!file.exists()) throw IllegalArgumentException("文件不存在：$path")
                if (file.isDirectory) throw IllegalArgumentException("这是一个目录：$path")
                val max = args.optInt("max_bytes", 262_144).coerceIn(1, 2 * 1024 * 1024)
                val text = file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(max)
                    var offset = 0
                    while (offset < max) {
                        val n = input.read(buffer, offset, max - offset)
                        if (n == -1) break
                        offset += n
                    }
                    String(buffer, 0, offset, Charsets.UTF_8)
                }
                Outcome(text, JSONObject().put("path", file.absolutePath).put("size", file.length()).put("truncated", file.length() > max))
            },
        )

        list += Tool(
            name = "file_write",
            description = "写入文本文件（覆盖或追加），自动创建父目录。",
            schema = objectSchema(
                "path" to str("文件绝对路径"),
                "content" to str("要写入的文本"),
                "append" to bool("是否追加而不是覆盖", false),
                required = listOf("path", "content"),
            ),
            handler = { args ->
                val path = args.req("path")
                val content = args.optString("content", "")
                val file = File(path)
                file.parentFile?.mkdirs()
                if (args.optBoolean("append", false)) file.appendText(content) else file.writeText(content)
                Outcome("已写入 ${file.absolutePath}（${file.length()} 字节）", JSONObject().put("path", file.absolutePath).put("bytes", file.length()))
            },
        )

        // ------------------------------ 应用控制 ------------------------------

        list += Tool(
            name = "app_get_state",
            description = "获取应用当前状态：打开的数据库、当前页面、主题模式、每页行数、MCP 服务状态。",
            schema = objectSchema(),
            handler = {
                val ref = DbManager.current
                val payload = JSONObject()
                    .put("screen", McpUiBridge.currentScreen)
                    .put("theme_mode", AppPrefs.themeMode)
                    .put("page_size", AppPrefs.pageSize)
                    .put("database", if (ref == null) JSONObject.NULL else JSONObject().put("name", ref.name).put("path", ref.localPath))
                    .put("mcp_running", McpPrefs.runningPort > 0)
                    .put("mcp_port", McpPrefs.runningPort)
                    .put("mcp_bind", McpPrefs.bindAddress)
                    .put("request_count", McpPrefs.requestCount)
                Outcome(payload.toString(2), payload)
            },
        )

        list += Tool(
            name = "app_navigate",
            description = "让手机界面切换到指定页面：databases（数据库）、data（数据）、diff（对比）、sql（SQL 控制台）、settings（设置）。",
            schema = objectSchema(
                "tab" to str("目标页面", listOf("databases", "data", "diff", "sql", "settings")),
                required = listOf("tab"),
            ),
            handler = { args ->
                val tab = args.req("tab").lowercase()
                val ok = McpUiBridge.navigate(tab)
                if (ok) Outcome("已切换到 $tab 页面") else Outcome("无法切换页面：应用界面未就绪", isError = true)
            },
        )

        list += Tool(
            name = "app_set_pref",
            description = "修改应用偏好：theme_mode（0 跟随系统 / 1 浅色 / 2 深色）、page_size（数据页每页行数）。",
            schema = objectSchema(
                "theme_mode" to num("0 跟随系统 / 1 浅色 / 2 深色"),
                "page_size" to num("数据页每页行数"),
            ),
            handler = { args ->
                val ctx = context()
                if (args.has("theme_mode")) AppPrefs.themeMode = args.optInt("theme_mode", 0).coerceIn(0, 2)
                if (args.has("page_size")) AppPrefs.pageSize = args.optInt("page_size", 200).coerceIn(10, 5000)
                AppPrefs.persist(ctx)
                Outcome(
                    "偏好已更新：主题=${AppPrefs.themeMode}，每页=${AppPrefs.pageSize} 行",
                    JSONObject().put("theme_mode", AppPrefs.themeMode).put("page_size", AppPrefs.pageSize),
                )
            },
        )

        list += Tool(
            name = "server_info",
            description = "查看 MCP 服务信息：版本、工具数量、监听端口、绑定地址、认证开关、累计请求数。",
            schema = objectSchema(),
            handler = {
                val payload = JSONObject()
                    .put("name", McpProtocol.SERVER_NAME)
                    .put("version", McpProtocol.SERVER_VERSION)
                    .put("protocol", McpProtocol.SERVER_PROTOCOL)
                    .put("tools", count())
                    .put("running", McpPrefs.runningPort > 0)
                    .put("port", McpPrefs.runningPort)
                    .put("bind", McpPrefs.bindAddress)
                    .put("auth_required", McpPrefs.authRequired)
                    .put("request_count", McpPrefs.requestCount)
                Outcome(payload.toString(2), payload)
            },
        )

        return list
    }

    // ------------------------------------------------------------ 对比辅助

    private fun <T> withEngines(
        leftPath: String,
        rightPath: String,
        block: (SqliteEngine, SqliteEngine) -> T,
    ): T {
        var leftTemp: SqliteEngine? = null
        var rightTemp: SqliteEngine? = null
        try {
            val left = if (leftPath.isBlank()) {
                engine()
            } else {
                SqliteEngine().also { engine ->
                    engine.open(leftPath, true).getOrThrow()
                    leftTemp = engine
                }
            }
            val right = if (rightPath.isBlank() || rightPath == leftPath) {
                left
            } else {
                SqliteEngine().also { engine ->
                    engine.open(rightPath, true).getOrThrow()
                    rightTemp = engine
                }
            }
            return block(left, right)
        } finally {
            leftTemp?.close()
            rightTemp?.close()
        }
    }

    // ------------------------------------------------------------ 导入辅助

    private fun insertRows(
        table: String,
        columns: List<String>,
        rows: List<List<String?>>,
        replace: Boolean,
    ): Int {
        val e = engine()
        if (replace) {
            e.dropTable(table)
            e.createTable(table, columns.map { ColumnSpec(it, "TEXT") })
        }
        var count = 0
        e.transaction {
            rows.forEach { row ->
                val map = LinkedHashMap<String, Any?>()
                columns.forEachIndexed { index, column ->
                    map[column] = row.getOrNull(index)?.takeIf { it.isNotEmpty() }
                }
                e.insert(table, map)
                count++
            }
        }
        return count
    }
}
