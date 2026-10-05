package com.dbstudio.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dbstudio.app.data.DbManager
import com.dbstudio.app.data.db.QueryResult
import com.dbstudio.app.data.io.SqlScript
import com.dbstudio.app.ui.AppNav
import com.dbstudio.app.ui.components.EmptyState
import com.dbstudio.app.ui.components.ScreenTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SQL_COL_WIDTH = 150.dp

@Composable
fun SqlScreen(nav: AppNav) {
    val engine = DbManager.engine
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sql by remember { mutableStateOf("SELECT name FROM sqlite_master WHERE type='table';") }
    var result by remember { mutableStateOf<QueryResult?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }

    val scriptLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    }
                }.onSuccess { sql = it.orEmpty(); message = "已载入脚本" }
                    .onFailure { error = it.message }
            }
        }
    }

    fun run() {
        if (!engine.isOpen) {
            error = "请先在「数据库」页打开一个数据库"
            return
        }
        running = true; error = null; message = null
        scope.launch {
            val stmts = SqlScript.split(sql)
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    if (stmts.size == 1 && isQuery(stmts[0])) {
                        Triple("query", engine.query(stmts[0]), 1)
                    } else {
                        Triple("exec", null, engine.execScript(stmts))
                    }
                }
            }
            outcome.onSuccess { (kind, qr, count) ->
                if (kind == "query") {
                    result = qr
                    message = "查询返回 ${qr?.rows?.size ?: 0} 行 · ${qr?.elapsedMs ?: 0} ms"
                } else {
                    result = null
                    message = "已执行 $count 条语句"
                }
            }.onFailure { error = it.message }
            running = false
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(10.dp))
        ScreenTitle("SQL 控制台", DbManager.current?.name ?: "未打开数据库")
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = sql,
            onValueChange = { sql = it },
            modifier = Modifier.fillMaxWidth().height(170.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            placeholder = { Text("输入 SQL 语句…") },
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SqlButton("执行", Icons.Rounded.PlayArrow, primary = true) { run() }
            SqlButton("清空", Icons.Rounded.DeleteSweep) { sql = "" }
            SqlButton("载入", Icons.Rounded.FileOpen) { scriptLauncher.launch(arrayOf("*/*")) }
        }
        Spacer(Modifier.height(12.dp))
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(6.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                running -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                result != null -> SqlResultTable(result!!)
                else -> EmptyState(Icons.Rounded.Terminal, "结果区", "执行查询后在此显示结果")
            }
        }
        Spacer(Modifier.height(90.dp))
    }
}

@Composable
private fun SqlButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SqlResultTable(result: QueryResult) {
    Column(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(vertical = 8.dp),
        ) {
            result.columns.forEach { col ->
                Text(
                    col,
                    modifier = Modifier.width(SQL_COL_WIDTH).padding(horizontal = 10.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(result.rows) { _, row ->
                Row(Modifier.padding(vertical = 8.dp)) {
                    row.forEach { cell ->
                        Text(
                            cell ?: "NULL",
                            modifier = Modifier.width(SQL_COL_WIDTH).padding(horizontal = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 12.5.sp,
                            color = if (cell == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

private fun isQuery(statement: String): Boolean {
    val head = statement.trim().substringBefore(' ').substringBefore('(').lowercase()
    return head in setOf("select", "pragma", "explain", "with", "values")
}
