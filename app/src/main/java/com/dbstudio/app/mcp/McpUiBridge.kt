package com.dbstudio.app.mcp

/**
 * MCP → 手机界面的桥接：让 MCP 工具可以切换应用页面并读取当前页面。
 * 由 MainActivity 注册处理器，界面销毁时注销。
 */
object McpUiBridge {

    @Volatile
    var navigateHandler: ((String) -> Boolean)? = null

    @Volatile
    var currentScreen: String = "databases"

    fun navigate(tab: String): Boolean {
        val handler = navigateHandler ?: return false
        return runCatching { handler(tab) }.getOrDefault(false)
    }
}
