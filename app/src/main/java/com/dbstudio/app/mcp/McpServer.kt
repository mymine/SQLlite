package com.dbstudio.app.mcp

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 轻量 HTTP 服务器，实现 MCP Streamable HTTP 传输（JSON-RPC 2.0 over POST）。
 *
 * 不依赖任何第三方网络库，直接使用 ServerSocket 实现：
 *  - POST /mcp    ：JSON-RPC 请求入口
 *  - GET  /mcp    ：不支持 SSE 推送，返回 405
 *  - DELETE /mcp  ：关闭会话（无状态实现，直接 200）
 *  - GET  /health ：健康检查
 *  - GET  /       ：浏览器可查看的说明页
 */
class McpServer {

    private companion object {
        const val MAX_BODY = 16 * 1024 * 1024
        const val MAX_LINE = 16 * 1024
    }

    private val executor = Executors.newCachedThreadPool()
    private val counter = AtomicInteger(0)
    private val sessionId: String = UUID.randomUUID().toString()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    @Volatile
    var boundPort: Int = 0
        private set

    @Volatile
    var boundAddress: String = ""
        private set

    val isRunning: Boolean get() = running

    val requestCount: Int get() = counter.get()

    fun start(bindAddress: String, port: Int): Result<Unit> {
        stop()
        return runCatching {
            val address = InetAddress.getByName(bindAddress)
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(address, port), 64)
            serverSocket = socket
            boundPort = socket.localPort
            this.boundAddress = bindAddress
            running = true
            counter.set(0)
            McpPrefs.requestCount = 0
            Thread({ acceptLoop(socket) }, "mcp-accept").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        boundPort = 0
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (t: Throwable) {
                break
            }
            runCatching { executor.execute { handle(client) } }
        }
    }

    // ------------------------------------------------------------ HTTP 处理

    private class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 30_000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val req = readRequest(input) ?: return

            if (req.method == "OPTIONS") {
                send(output, 204, "text/plain; charset=utf-8", ByteArray(0))
                return
            }

            when {
                req.path == "/health" || req.path == "/healthz" -> {
                    val obj = JSONObject()
                        .put("status", "ok")
                        .put("server", McpProtocol.SERVER_NAME)
                        .put("version", McpProtocol.SERVER_VERSION)
                        .put("transport", "streamable-http")
                        .put("port", boundPort)
                        .put("tools", McpTools.count())
                    sendJson(output, 200, obj.toString())
                }

                req.path == "/" && req.method == "GET" -> {
                    send(output, 200, "text/html; charset=utf-8", indexHtml().toByteArray(Charsets.UTF_8))
                }

                req.path == "/mcp" || req.path == "/" -> handleMcp(req, output)

                else -> sendJson(output, 404, JSONObject().put("error", "not found").toString())
            }
        } catch (t: Throwable) {
            runCatching { McpLog.add("http", "连接异常：${t.message ?: t.javaClass.simpleName}", false) }
        } finally {
            runCatching { client.close() }
        }
    }

    private fun handleMcp(req: HttpRequest, output: OutputStream) {
        when (req.method) {
            "DELETE" -> {
                sendJson(output, 200, "{\"ok\":true}")
                return
            }

            "GET" -> {
                send(output, 405, "application/json; charset=utf-8",
                    "{\"error\":\"SSE 未启用，请使用 POST /mcp 发送 JSON-RPC 请求\"}".toByteArray(Charsets.UTF_8),
                    mapOf("Allow" to "POST, DELETE, OPTIONS"))
                return
            }

            "POST" -> Unit

            else -> {
                send(output, 405, "application/json; charset=utf-8",
                    "{\"error\":\"method not allowed\"}".toByteArray(Charsets.UTF_8),
                    mapOf("Allow" to "POST, DELETE, OPTIONS"))
                return
            }
        }

        if (!authorized(req)) {
            McpLog.add("auth", "拒绝未认证请求", false)
            sendJson(
                output, 401,
                JSONObject().put("error", "unauthorized")
                    .put("hint", "请在 Authorization 头中提供 Bearer 令牌，或使用 ?token= 参数")
                    .toString(),
                mapOf("WWW-Authenticate" to "Bearer"),
            )
            return
        }

        val body = String(req.body, Charsets.UTF_8)
        if (body.isBlank()) {
            sendJson(output, 400, JSONObject().put("error", "empty body").toString())
            return
        }

        val count = counter.incrementAndGet()
        McpPrefs.requestCount = count

        val response = try {
            McpProtocol.handle(body)
        } catch (t: Throwable) {
            McpLog.add("rpc", "协议处理异常：${t.message ?: t.javaClass.simpleName}", false)
            McpProtocol.internalErrorJson(t.message ?: t.javaClass.simpleName)
        }

        if (response == null) {
            // JSON-RPC 通知：按 MCP 规范返回 202 且无正文
            send(output, 202, "application/json; charset=utf-8", ByteArray(0))
            return
        }

        sendJson(output, 200, response, mapOf("Mcp-Session-Id" to sessionId))
    }

    private fun authorized(req: HttpRequest): Boolean {
        if (!McpPrefs.authRequired) return true
        val expected = McpPrefs.token
        if (expected.isBlank()) return true

        val authorization = req.headers["authorization"]
        val bearer = authorization
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)
            ?.trim()
        val header = req.headers["x-mcp-token"]
        val query = req.query["token"]
        return listOfNotNull(bearer, header, query).any { safeEquals(it, expected) }
    }

    private fun safeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    // ------------------------------------------------------------ 解析 / 响应

    private fun readRequest(input: InputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val target = parts[1]

        val rawPath = target.substringBefore('?')
        val rawQuery = target.substringAfter('?', "")
        val query = LinkedHashMap<String, String>()
        if (rawQuery.isNotEmpty()) {
            rawQuery.split('&').forEach { pair ->
                if (pair.isEmpty()) return@forEach
                val key = pair.substringBefore('=')
                val value = if (pair.contains('=')) pair.substringAfter('=') else ""
                query[decode(key)] = decode(value)
            }
        }

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }

        val body = readBody(input, headers)
        return HttpRequest(method, decode(rawPath), query, headers, body)
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var b = input.read()
        if (b == -1) return null
        while (b != -1) {
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            if (sb.length > MAX_LINE) throw IOException("请求头过长")
            b = input.read()
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
        val encoding = headers["transfer-encoding"]
        if (encoding != null && encoding.contains("chunked", ignoreCase = true)) {
            return readChunked(input)
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0) return ByteArray(0)
        if (length > MAX_BODY) throw IOException("请求体过大（上限 16MB）")
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(buffer, offset, length - offset)
            if (n == -1) break
            offset += n
        }
        return if (offset == length) buffer else buffer.copyOf(offset)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val line = readLine(input) ?: break
            val size = line.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                readLine(input)
                break
            }
            val chunk = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val n = input.read(chunk, offset, size - offset)
                if (n == -1) break
                offset += n
            }
            out.write(chunk, 0, offset)
            if (out.size() > MAX_BODY) throw IOException("请求体过大（上限 16MB）")
            readLine(input)
        }
        return out.toByteArray()
    }

    private fun sendJson(
        out: OutputStream,
        status: Int,
        json: String,
        extra: Map<String, String> = emptyMap(),
    ) = send(out, status, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8), extra)

    private fun send(
        out: OutputStream,
        status: Int,
        contentType: String,
        body: ByteArray,
        extra: Map<String, String> = emptyMap(),
    ) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n")
        head.append("Content-Type: ").append(contentType).append("\r\n")
        head.append("Content-Length: ").append(body.size).append("\r\n")
        head.append("Connection: close\r\n")
        head.append("Access-Control-Allow-Origin: *\r\n")
        head.append("Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n")
        head.append("Access-Control-Allow-Headers: Content-Type, Authorization, X-MCP-Token, Mcp-Session-Id, MCP-Protocol-Version, Accept\r\n")
        head.append("Access-Control-Expose-Headers: Mcp-Session-Id\r\n")
        extra.forEach { (key, value) -> head.append(key).append(": ").append(value).append("\r\n") }
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun indexHtml(): String = """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>SQLlite MCP Server</title>
          <style>
            body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;background:#0f1115;color:#e6e8ee;margin:0;padding:32px}
            .card{max-width:640px;margin:0 auto;background:#181b22;border:1px solid #262a33;border-radius:16px;padding:24px}
            h1{font-size:22px;margin:0 0 4px}
            .sub{color:#8b93a7;font-size:13px;margin-bottom:20px}
            code{background:#0b0d11;padding:2px 6px;border-radius:6px;font-size:13px}
            .row{display:flex;justify-content:space-between;padding:10px 0;border-top:1px solid #262a33;font-size:14px}
            .k{color:#8b93a7}
            .dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:#42d392;margin-right:6px}
          </style>
        </head>
        <body>
          <div class="card">
            <h1><span class="dot"></span>SQLlite MCP Server</h1>
            <div class="sub">Model Context Protocol · Streamable HTTP</div>
            <div class="row"><span class="k">版本</span><span>${McpProtocol.SERVER_VERSION}</span></div>
            <div class="row"><span class="k">端点</span><span><code>POST /mcp</code></span></div>
            <div class="row"><span class="k">工具数量</span><span>${McpTools.count()}</span></div>
            <div class="row"><span class="k">端口</span><span>$boundPort</span></div>
            <div class="row"><span class="k">认证</span><span>${if (McpPrefs.authRequired) "Bearer 令牌" else "已关闭"}</span></div>
            <p style="color:#8b93a7;font-size:13px;margin-top:20px">
              在支持 MCP 的客户端中，把服务器地址填写为 <code>http://本机IP:$boundPort/mcp</code>，
              并在请求头中加入 <code>Authorization: Bearer 令牌</code>。
            </p>
          </div>
        </body>
        </html>
    """.trimIndent()
}
