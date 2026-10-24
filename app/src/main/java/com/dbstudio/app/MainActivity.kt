package com.dbstudio.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import com.dbstudio.app.data.AppPrefs
import com.dbstudio.app.mcp.McpPrefs
import com.dbstudio.app.mcp.McpService
import com.dbstudio.app.mcp.McpTools
import com.dbstudio.app.mcp.McpUiBridge
import com.dbstudio.app.ui.AppNav
import com.dbstudio.app.ui.AppRoot
import com.dbstudio.app.ui.Screen
import com.dbstudio.app.ui.theme.DBStudioTheme

class MainActivity : ComponentActivity() {

    private val appNav = AppNav()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPrefs.init(applicationContext)
        McpPrefs.init(applicationContext)
        McpTools.init(applicationContext)

        // MCP 工具 → 手机界面导航
        McpUiBridge.navigateHandler = { tab ->
            val target = when (tab.lowercase()) {
                "databases", "db", "database" -> Screen.Databases
                "data", "table" -> Screen.Data
                "diff", "compare" -> Screen.Diff
                "sql", "console" -> Screen.Sql
                "settings", "mcp" -> Screen.Settings
                else -> null
            }
            if (target == null) {
                false
            } else {
                runOnUiThread { appNav.switchTab(target) }
                true
            }
        }

        if (McpPrefs.enabled) {
            McpService.start(this)
        }

        enableEdgeToEdge()
        setContent {
            val dark = when (AppPrefs.themeMode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            DBStudioTheme(darkTheme = dark) {
                AppRoot(nav = appNav)
            }
        }
    }

    override fun onDestroy() {
        McpUiBridge.navigateHandler = null
        super.onDestroy()
    }
}
