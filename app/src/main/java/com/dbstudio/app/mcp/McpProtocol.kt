package com.dbstudio.app.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP JSON-RPC 2.0 协议处理：initialize / tools/list / tools/call / ping 等。
 */
object McpProtocol {

    const val SERVER_NAME = "sqllite-mcp"
    const val SERVER_VERSION = "1.4.0"
    const val SERVER_PROTOCOL = "2025-06-18"

    private val SUPPORTED_PROTOCOLS = listOf("2025-06-18", "2025-03-26", "2024-11-05")

    private val INSTRUCTIONS = """
SQLlite 是运行在 Android 上的 SQLite 数据库管理工具，通过 MCP 暴露了完整的数据库操作能力。

推荐使用顺序：
1. db_list / db_open / db_create 选择或新建数据库
2. table_list / table_schema 查看结构
3. data_query / data_rows / data_insert / data_update / data_delete 读写数据
4. sql_execute 执行任意 SQL
5. export_data / import_data 导入导出（csv、txt、json、sql）
6. diff_schema / diff_data 对比两个数据库的结构与数据
7. app_navigate / app_set_pref 控制手机端界面

所有写操作都会立即生效，请谨慎使用 table_drop、data_delete 等破坏性工具。
""".trimIndent()

    fun handle(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return error(null, -32700, "Parse error: empty body").toString()
        return try {
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                if (array.length() == 0) return error(null, -32600, "Invalid Request").toString()
                val out = JSONArray()
                for (i in 0 until array.length()) {
                    val message = array.optJSONObject(i) ?: continue
                    handleOne(message)?.let { out.put(it) }
                }
                if (out.length() == 0) null else out.toString()
            } else {
                handleOne(JSONObject(trimmed))?.toString()
            }
        } catch (t: Throwable) {
            error(null, -32700, "Parse error: ${t.message ?: t.javaClass.simpleName}").toString()
        }
    }

    fun internalErrorJson(message: String): String =
        error(null, -32603, "Internal error: $message").toString()

    private fun handleOne(message: JSONObject): JSONObject? {
        val hasId = message.has("id") && !message.isNull("id")
        val id = if (hasId) message.opt("id") else null
        val method = message.optString("method", "")

        if (method.isBlank()) {
            return if (hasId) error(id, -32600, "Invalid Request: missing method") else null
        }

        // JSON-RPC 通知：无 id，不返回响应
        if (!hasId) return null

        return when (method) {
            "initialize" -> result(id, initialize(message.optJSONObject("params") ?: JSONObject()))
            "ping" -> result(id, JSONObject())
            "tools/list" -> result(id, JSONObject().put("tools", McpTools.listJson()))
            "tools/call" -> callTool(id, message.optJSONObject("params") ?: JSONObject())
            "resources/list" -> result(id, JSONObject().put("resources", JSONArray()))
            "resources/templates/list" -> result(id, JSONObject().put("resourceTemplates", JSONArray()))
            "prompts/list" -> result(id, JSONObject().put("prompts", JSONArray()))
            "logging/setLevel" -> result(id, JSONObject())
            else -> error(id, -32601, "Method not found: $method")
        }
    }

    private fun initialize(params: JSONObject): JSONObject {
        val requested = params.optString("protocolVersion")
        val version = if (requested in SUPPORTED_PROTOCOLS) requested else SERVER_PROTOCOL
        return JSONObject()
            .put("protocolVersion", version)
            .put(
                "capabilities",
                JSONObject().put("tools", JSONObject().put("listChanged", false)),
            )
            .put(
                "serverInfo",
                JSONObject()
                    .put("name", SERVER_NAME)
                    .put("title", "SQLlite")
                    .put("version", SERVER_VERSION),
            )
            .put("instructions", INSTRUCTIONS)
    }

    private fun callTool(id: Any?, params: JSONObject): JSONObject {
        val name = params.optString("name", "")
        if (name.isBlank()) return error(id, -32602, "Missing tool name")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()

        val started = System.currentTimeMillis()
        val outcome = McpTools.call(name, arguments)
        val elapsed = System.currentTimeMillis() - started

        McpLog.add(
            "tools/call",
            buildString {
                append(name)
                append(" · ")
                append(elapsed)
                append(" ms")
                if (outcome.isError) {
                    append(" · ")
                    append(outcome.text.replace('\n', ' ').take(120))
                }
            },
            !outcome.isError,
        )

        val result = JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", outcome.text)))
            .put("isError", outcome.isError)
        outcome.structured?.let { result.put("structuredContent", it) }
        return result(id, result)
    }

    private fun result(id: Any?, value: JSONObject): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("result", value)

    private fun error(id: Any?, code: Int, message: String): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))
}
