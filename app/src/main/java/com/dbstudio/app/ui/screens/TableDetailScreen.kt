package com.dbstudio.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dbstudio.app.data.DbManager
import com.dbstudio.app.data.db.ColumnInfo
import com.dbstudio.app.data.db.ColumnSpec
import com.dbstudio.app.data.db.DbObject
import com.dbstudio.app.data.db.QueryResult
import com.dbstudio.app.data.db.SqliteEngine
import com.dbstudio.app.ui.AppNav
import com.dbstudio.app.ui.components.ConfirmDialog
import com.dbstudio.app.ui.components.SectionCard

@Composable
fun TableDetailScreen(nav: AppNav, table: String) {
    val engine = DbManager.engine
    var columns by remember { mutableStateOf<List<ColumnInfo>>(emptyList()) }
    var createSql by remember { mutableStateOf<String?>(null) }
    var indexes by remember { mutableStateOf<List<DbObject>>(emptyList()) }
    var preview by remember { mutableStateOf<QueryResult?>(null) }
    var rowCount by remember { mutableStateOf(0L) }
    var refresh by remember { mutableStateOf(0) }

    var menuOpen by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDrop by remember { mutableStateOf(false) }
    var showAddColumn by remember { mutableStateOf(false) }
    var dropColumnTarget by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(table, refresh) {
        runCatching {
            columns = engine.columns(table)
            createSql = engine.createSql(table)
            indexes = engine.indexes().filter { it.tblName == table }
            rowCount = runCatching { engine.rowCount(table) }.getOrDefault(0L)
            preview = engine.query("SELECT * FROM ${SqliteEngine.quoteIdent(table)} LIMIT 50")
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { nav.back() }) {
                Icon(Icons.Rounded.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onBackground)
            }
            Column(Modifier.weight(1f)) {
                Text(table, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("$rowCount 行 · ${columns.size} 列", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, "更多", tint = MaterialTheme.colorScheme.onBackground)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名表") },
                        leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, null) },
                        onClick = { menuOpen = false; showRename = true },
                    )
                    DropdownMenuItem(
                        text = { Text("添加列") },
                        leadingIcon = { Icon(Icons.Rounded.Add, null) },
                        onClick = { menuOpen = false; showAddColumn = true },
                    )
                    DropdownMenuItem(
                        text = { Text("删除表", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; showDrop = true },
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard(title = "列结构") {
                    columns.forEach { c ->
                        ColumnRow(c) { dropColumnTarget = c.name }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { showAddColumn = true }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("添加列", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            if (indexes.isNotEmpty()) {
                item {
                    SectionCard(title = "索引") {
                        indexes.forEach { idx ->
                            Text(
                                idx.sql ?: idx.name,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(vertical = 3.dp),
                            )
                        }
                    }
                }
            }
            createSql?.let { sql ->
                item {
                    SectionCard(title = "建表语句") {
                        Text(sql, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            preview?.let { p ->
                item {
                    SectionCard(title = "数据预览（前 50 行）") {
                        if (p.rows.isEmpty()) {
                            Text("暂无数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Column(Modifier.horizontalScroll(rememberScrollState())) {
                                Row(Modifier.padding(bottom = 6.dp)) {
                                    p.columns.forEach { col ->
                                        Text(col, modifier = Modifier.width(130.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                p.rows.forEach { row ->
                                    Row {
                                        row.forEach { cell ->
                                            Text(
                                                cell ?: "NULL",
                                                modifier = Modifier.width(130.dp),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontSize = 12.5.sp,
                                                color = if (cell == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRename) {
        var newName by remember { mutableStateOf(table) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("重命名表") },
            text = { OutlinedTextField(newName, { newName = it }, label = { Text("新表名") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { engine.renameTable(table, newName.trim()) }
                        .onSuccess { showRename = false; DbManager.refreshTables(); nav.back() }
                        .onFailure { message = it.message }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("取消") } },
        )
    }

    if (showDrop) {
        ConfirmDialog(
            title = "删除表",
            message = "确定要删除表「$table」吗？此操作不可撤销。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                runCatching { engine.dropTable(table) }
                    .onSuccess { DbManager.refreshTables(); nav.back() }
                    .onFailure { message = it.message }
            },
            onDismiss = { showDrop = false },
        )
    }

    if (showAddColumn) {
        AddColumnDialog(
            onDismiss = { showAddColumn = false },
            onAdd = { spec ->
                runCatching { engine.addColumn(table, spec) }
                    .onSuccess { showAddColumn = false; refresh++ }
                    .onFailure { message = it.message }
            },
        )
    }

    dropColumnTarget?.let { col ->
        ConfirmDialog(
            title = "删除列",
            message = "确定要删除列「$col」吗？",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                runCatching { engine.dropColumn(table, col) }
                    .onSuccess { refresh++ }
                    .onFailure { message = it.message }
                dropColumnTarget = null
            },
            onDismiss = { dropColumnTarget = null },
        )
    }

    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("提示") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } },
        )
    }
}

@Composable
private fun ColumnRow(c: ColumnInfo, onDelete: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (c.primaryKey) {
            Box(
                modifier = Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Key, null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(13.dp))
            }
        } else {
            Spacer(Modifier.width(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            val extras = buildList {
                add(c.type)
                if (c.notNull) add("NOT NULL")
                if (c.defaultValue != null) add("默认 ${c.defaultValue}")
                if (c.primaryKey) add("主键")
            }
            Text(extras.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Rounded.Close, "删除列", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun AddColumnDialog(onDismiss: () -> Unit, onAdd: (ColumnSpec) -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("TEXT") }
    var notNull by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加列") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("列名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Box {
                    OutlinedTextField(type, {}, label = { Text("类型") }, readOnly = true, modifier = Modifier.fillMaxWidth().clickable { typeMenu = true })
                    Box(Modifier.fillMaxWidth().height(56.dp).clickable { typeMenu = true })
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        listOf("INTEGER", "TEXT", "REAL", "BLOB", "NUMERIC").forEach { t ->
                            DropdownMenuItem(text = { Text(t) }, onClick = { type = t; typeMenu = false })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = notNull, onCheckedChange = { notNull = it })
                    Text("NOT NULL")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) error = "请输入列名" else onAdd(ColumnSpec(name.trim(), type, notNull = notNull))
            }) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
