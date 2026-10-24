package com.dbstudio.app.mcp

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.SecureRandom

/**
 * MCP 服务配置，持久化到 SharedPreferences("mcp_prefs")。
 *
 * 字段使用 Compose State 承载，设置页可以实时观察服务状态（运行端口、请求数、错误）。
 */
object McpPrefs {

    const val PREF_NAME = "mcp_prefs"
    const val DEFAULT_PORT = 8765

    /** 监听全部网络接口 */
    const val BIND_ALL = "0.0.0.0"

    /** 仅监听本机回环 */
    const val BIND_LOCAL = "127.0.0.1"

    var enabled by mutableStateOf(false)
        private set
    var bindAddress by mutableStateOf(BIND_ALL)
        private set
    var port by mutableStateOf(DEFAULT_PORT)
        private set
    var authRequired by mutableStateOf(true)
        private set
    var token by mutableStateOf("")
        private set

    // ---------------- 运行时状态（不持久化） ----------------
    var runningPort by mutableStateOf(0)
    var lastError by mutableStateOf<String?>(null)
    var requestCount by mutableStateOf(0)

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs = p
        enabled = p.getBoolean("enabled", false)
        bindAddress = p.getString("bind_address", BIND_ALL) ?: BIND_ALL
        port = p.getInt("port", DEFAULT_PORT).coerceIn(1024, 65535)
        authRequired = p.getBoolean("auth_required", true)
        val saved = p.getString("token", null)
        token = if (saved.isNullOrBlank()) generateToken() else saved
        p.edit().putString("token", token).apply()
    }

    fun update(
        context: Context,
        enabled: Boolean,
        bindAddress: String,
        port: Int,
        authRequired: Boolean,
        token: String? = null,
    ) {
        this.enabled = enabled
        this.bindAddress = bindAddress
        this.port = port.coerceIn(1024, 65535)
        this.authRequired = authRequired
        if (!token.isNullOrBlank()) this.token = token
        prefs?.edit()?.apply {
            putBoolean("enabled", this@McpPrefs.enabled)
            putString("bind_address", this@McpPrefs.bindAddress)
            putInt("port", this@McpPrefs.port)
            putBoolean("auth_required", this@McpPrefs.authRequired)
            putString("token", this@McpPrefs.token)
            apply()
        }
    }

    fun regenerateToken(): String {
        val t = generateToken()
        token = t
        prefs?.edit()?.putString("token", t)?.apply()
        return t
    }

    fun resetRuntime() {
        runningPort = 0
        requestCount = 0
    }

    private fun generateToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
