package com.dbstudio.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dbstudio.app.data.AppPrefs
import com.dbstudio.app.mcp.McpPrefs
import com.dbstudio.app.ui.Screen
import com.dbstudio.app.ui.AppNav
import com.dbstudio.app.ui.components.ScreenTitle
import com.dbstudio.app.ui.components.SectionCard

@Composable
fun SettingsScreen(nav: AppNav) {
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(10.dp))
        ScreenTitle("设置", "外观与偏好")
        Spacer(Modifier.height(12.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionCard(title = "外观") {
                Text("主题模式", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("跟随系统", "浅色", "深色").forEachIndexed { index, label ->
                        ChoiceChip(label, AppPrefs.themeMode == index) {
                            AppPrefs.themeMode = index
                            AppPrefs.persist(context)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SectionCard(title = "数据浏览") {
                Text("每页行数", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(50, 100, 200, 500).forEach { size ->
                        ChoiceChip(size.toString(), AppPrefs.pageSize == size) {
                            AppPrefs.pageSize = size
                            AppPrefs.persist(context)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SectionCard(title = "支持格式") {
                Text(
                    "导入 / 导出支持 SQL、CSV、TXT、JSON 四种格式；\n" +
                        "表结构对比与表数据对比；\n" +
                        "支持打开设备上任意 .db / .sqlite / .sqlite3 文件。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            SectionCard(title = "MCP 服务") {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { nav.push(Screen.Mcp) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("MCP 服务器", fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "通过局域网让大模型操控数据库（网口 / 端口 / 令牌）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (McpPrefs.runningPort > 0) Color(0xFF42D392)
                                else MaterialTheme.colorScheme.outline,
                            ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("进入", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(12.dp))
            SectionCard(title = "关于") {
                Text("SQLlite", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("版本 1.4.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text("基于 Jetpack Compose 构建的 SQLite 数据库管理工具", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(110.dp))
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
