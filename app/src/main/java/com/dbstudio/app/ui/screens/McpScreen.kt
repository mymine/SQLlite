package com.dbstudio.app.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.dbstudio.app.ui.AppNav
import com.dbstudio.app.mcp.McpLog
import com.dbstudio.app.mcp.McpPrefs
import com.dbstudio.app.mcp.McpService
import com.dbstudio.app.mcp.McpTools
import com.dbstudio.app.ui.components.PrimaryButton
import com.dbstudio.app.ui.components.SectionCard
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * MCP 服务设置页：启停服务、配置网口/端口、令牌认证、查看日志与客户端配置。
 */
@Composable
fun McpScreen(nav: AppNav) {
    val context = LocalContext.current

    var enabled by remember { mutableStateOf(McpPrefs.enabled) }
    var bind by remember { mutableStateOf(McpPrefs.bindAddress) }
    var portText by remember { mutableStateOf(McpPrefs.port.toString()) }
    var auth by remember { mutableStateOf(McpPrefs.authRequired) }
    var token by remember { mutableStateOf(McpPrefs.token) }

    var error by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }

    var notificationGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationGranted = granted }

    val addresses = remember { localAddresses() }
    val runningPort = McpPrefs.runningPort
    val requestCount = McpPrefs.requestCount
    val lastError = McpPrefs.lastError
    val revision = McpLog.revision

    LaunchedEffect(hint) {
        if (hint != null) {
            delay(2500)
            hint = null
        }
    }

    // 从通知栏「停止服务」或其它入口关闭后，同步开关状态
    LaunchedEffect(McpPrefs.enabled) { enabled = McpPrefs.enabled }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.back() }) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
            }
            Spacer(Modifier.width(2.dp))
            Column {
                Text(
                    "MCP 服务",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "让大模型通过局域网操控 SQLlite 的全部功能",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        Column(Modifier.verticalScroll(rememberScrollState())) {
            StatusCard(
                runningPort = runningPort,
                bind = McpPrefs.bindAddress,
                enabled = enabled,
                error = lastError,
                requestCount = requestCount,
                toolCount = McpTools.count(),
            )

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "服务") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("启用 MCP 服务", fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "开启后会以前台服务常驻运行，通知栏可见运行状态。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { value ->
                            enabled = value
                            val validPort = portText.toIntOrNull()?.takeIf { it in 1024..65535 } ?: McpPrefs.port
                            McpPrefs.update(context, value, bind, validPort, auth, token)
                            error = null
                            if (value) {
                                McpService.restart(context)
                                hint = "正在启动服务…"
                            } else {
                                McpService.stop(context)
                                hint = "服务已停止"
                            }
                        },
                    )
                }
                if (!notificationGranted) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "未授予通知权限，通知栏不会显示运行状态",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "授予",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    if (Build.VERSION.SDK_INT >= 33) {
                                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "网络") {
                Text("监听网口", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("全部网口", bind == McpPrefs.BIND_ALL) { bind = McpPrefs.BIND_ALL }
                    ChoiceChip("仅本机", bind == McpPrefs.BIND_LOCAL) { bind = McpPrefs.BIND_LOCAL }
                }
                if (addresses.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "本机地址：" + addresses.joinToString("  /  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = portText,
                    onValueChange = { value ->
                        if (value.length <= 5 && value.all { it.isDigit() }) portText = value
                    },
                    label = { Text("端口（1024 - 65535）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "认证") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("令牌认证", fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "开启后，客户端必须在请求头中携带 Authorization: Bearer <令牌>。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = auth, onCheckedChange = { auth = it })
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            "当前令牌",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            token,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = {
                        copyToClipboard(context, token)
                        hint = "令牌已复制"
                    }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "复制令牌", modifier = Modifier.size(19.dp))
                    }
                    IconButton(onClick = {
                        token = McpPrefs.regenerateToken()
                        hint = "已生成新令牌，需重启服务生效"
                    }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "重新生成", modifier = Modifier.size(19.dp))
                    }
                }
                if (!auth) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "⚠ 关闭认证后，同一网络内的任何设备都可以访问数据库，请仅在可信网络中使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            if (error != null) {
                Text(
                    error ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
            }
            PrimaryButton("保存并应用", modifier = Modifier.fillMaxWidth()) {
                val port = portText.toIntOrNull()
                if (port == null || port !in 1024..65535) {
                    error = "端口需为 1024 - 65535 之间的数字"
                    return@PrimaryButton
                }
                if (!auth && bind == McpPrefs.BIND_ALL) {
                    error = "已关闭认证并监听全部网口，请确认当前网络可信"
                } else {
                    error = null
                }
                McpPrefs.update(context, enabled, bind, port, auth, token)
                if (enabled) {
                    McpService.restart(context)
                    hint = "正在启动服务…"
                } else {
                    McpService.stop(context)
                    hint = "服务已停止"
                }
            }
            if (hint != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    hint ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "客户端配置") {
                Text(
                    "在 Claude Desktop、Cherry Studio、Cursor 等支持 MCP 的客户端中添加：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                val host = if (bind == McpPrefs.BIND_LOCAL) "127.0.0.1" else addresses.firstOrNull() ?: "手机IP"
                val port = portText.toIntOrNull() ?: McpPrefs.port
                val config = buildString {
                    appendLine("{")
                    appendLine("  \"mcpServers\": {")
                    appendLine("    \"sqllite\": {")
                    appendLine("      \"type\": \"http\",")
                    appendLine("      \"url\": \"http://$host:$port/mcp\",")
                    if (auth) {
                        appendLine("      \"headers\": {")
                        appendLine("        \"Authorization\": \"Bearer $token\"")
                        appendLine("      }")
                    } else {
                        appendLine("      \"headers\": {}")
                    }
                    appendLine("    }")
                    appendLine("  }")
                    append("}")
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(12.dp),
                ) {
                    Text(
                        config,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable {
                                copyToClipboard(context, config)
                                hint = "配置已复制"
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text("复制配置", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp)
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable {
                                copyToClipboard(context, "http://$host:$port/mcp")
                                hint = "地址已复制"
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text("复制地址", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "最近请求") {
                Text(
                    "共 ${requestCount} 次请求 · 显示最近 60 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                val entries = remember(revision) { McpLog.snapshot() }
                if (entries.isEmpty()) {
                    Text(
                        "暂无请求",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val format = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                    for (entry in entries.takeLast(12).reversed()) {
                        val time = format.format(Date(entry.time))
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                time,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${entry.method} · ${entry.detail}",
                                fontSize = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = if (entry.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionCard(title = "可用工具") {
                Text(
                    "共 ${McpTools.count()} 个工具，覆盖数据库、表、数据、SQL、导入导出、对比、文件与应用控制。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "db_list · db_open · db_create · db_info · table_list · table_schema · table_create · " +
                        "data_query · data_insert · data_update · data_delete · sql_execute · export_data · " +
                        "import_data · diff_schema · diff_data · app_navigate …",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(110.dp))
        }
    }
}

@Composable
private fun StatusCard(
    runningPort: Int,
    bind: String,
    enabled: Boolean,
    error: String?,
    requestCount: Int,
    toolCount: Int,
) {
    val running = runningPort > 0
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            running -> Color(0xFF42D392)
                            enabled -> Color(0xFFF0A93B)
                            else -> MaterialTheme.colorScheme.outline
                        },
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    running -> "服务运行中"
                    enabled -> "服务未启动"
                    else -> "服务已关闭"
                },
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (running) "http://${if (bind == McpPrefs.BIND_ALL) "0.0.0.0" else bind}:$runningPort/mcp"
            else "开启后即可通过 MCP 客户端连接",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "已处理 $requestCount 次请求 · 提供 $toolCount 个工具",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "上次错误：$error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
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
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("SQLlite MCP", text))
    }
}

private fun localAddresses(): List<String> = runCatching {
    Collections.list(NetworkInterface.getNetworkInterfaces())
        .filter { it.isUp && !it.isLoopback }
        .flatMap { Collections.list(it.inetAddresses) }
        .filterIsInstance<Inet4Address>()
        .mapNotNull { it.hostAddress }
        .filter { it.isNotBlank() }
        .distinct()
}.getOrDefault(emptyList())
