package com.dbstudio.app.mcp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** MCP 请求日志（内存环形队列，供设置页展示） */
object McpLog {

    data class Entry(
        val time: Long,
        val method: String,
        val detail: String,
        val ok: Boolean,
    )

    private const val MAX = 60
    private val items = ArrayDeque<Entry>()

    /** 版本号，变化时触发 Compose 重组 */
    var revision by mutableStateOf(0)
        private set

    fun add(method: String, detail: String, ok: Boolean) {
        synchronized(items) {
            items.addLast(Entry(System.currentTimeMillis(), method, detail, ok))
            while (items.size > MAX) items.removeFirst()
        }
        revision++
    }

    fun snapshot(): List<Entry> = synchronized(items) { items.toList() }

    fun clear() {
        synchronized(items) { items.clear() }
        revision++
    }
}
