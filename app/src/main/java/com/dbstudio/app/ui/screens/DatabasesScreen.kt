@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.dbstudio.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Visibility
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dbstudio.app.data.DatabaseRef
import com.dbstudio.app.data.DbManager
import com.dbstudio.app.data.DbStore
import com.dbstudio.app.data.db.ColumnSpec
import com.dbstudio.app.data.db.TableOverview
import com.dbstudio.app.data.io.SqlExport
import com.dbstudio.app.data.io.SqlScript
import com.dbstudio.app.ui.AppNav
import com.dbstudio.app.ui.Screen
import com.dbstudio.app.ui.components.ConfirmDialog
import com.dbstudio.app.ui.components.EmptyState
import com.dbstudio.app.ui.components.SearchField
import com.dbstudio.app.ui.components.ScreenTitle
import com.dbstudio.app.ui.components.SelectionAction
import com.dbstudio.app.ui.components.SelectionBar
import com.dbstudio.app.ui.components.SectionCard
import com.dbstudio.app.ui.components.StorageBrowser
import com.dbstudio.app.ui.components.humanSize
import com.dbstudio.app.util.DbFileHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DatabasesScreen(nav: AppNav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var recents by remember { mutableStateOf(DbStore.loadRecent(context)) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showNewDialog by remember { mutableStateOf(false) }
    var showBrowser by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    var showCreateTable by remember { mutableStateOf(false) }
    var menuTable by remember { mutableStateOf<TableOverview?>(null) }
    var renameTarget by remember { mutableStateOf<TableOverview?>(null) }
    var deleteTarget by remember { mutableStateOf<TableOverview?>(null) }

    // 多选
    var selectionMode by remember { mutableStateOf(false) }
    val selectedTables = remember { mutableStateListOf<String>() }
    var showExportOptions by remember { mutableStateOf(false) }
    var exportIncludeData by remember { mutableStateOf(true) }
    var showBatchDelete by remember { mutableStateOf(false) }

    fun openLocalFile(file: File) {
        runCatching {
            val ref = DatabaseRef("file:" + file.absolutePath, file.name, null, file.absolutePath, false, file.length())
            DbManager.openDb(ref).getOrThrow()
            DbStore.rememberRecent(context, ref)
            recents = DbStore.loadRecent(context)
        }.onFailure { errorMessage = it.message ?: "打开失败" }
    }

    fun requestStorageThen(block: () -> Unit) {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true
        if (granted) {
            block()
        } else {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
            }.onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
            errorMessage = "请在弹出的页面中授予「所有文件访问」权限，然后返回重试"
        }
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            DbFileHelper.takePermission(context, uri, write = true)
            runCatching {
                val file = DbFileHelper.importUri(context, uri)
                val ref = DatabaseRef(uri.toString(), file.name, uri.toString(), file.absolutePath, false, file.length())
                DbManager.openDb(ref).getOrThrow()
                DbStore.rememberRecent(context, ref)
                recents = DbStore.loadRecent(context)
            }.onFailure { errorMessage = it.message ?: "打开失败" }
        }
    }

    // 导入 SQL 脚本
    val importSqlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                        val n = DbManager.engine.execScript(SqlScript.split(text))
                        n
                    }
                }
                r.onSuccess { DbManager.refreshTables(); errorMessage = "已成功执行 $it 条语句（含建表与数据）" }
                    .onFailure { errorMessage = "导入失败：${it.message}" }
            }
        }
    }

    // 导出 SQL
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/sql")) { uri ->
        if (uri != null) {
            val tables = selectedTables.toList()
            val includeData = exportIncludeData
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val content = SqlExport.dump(DbManager.engine, tables, includeData)
                        context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                    }
                }.onSuccess { errorMessage = "已导出 ${tables.size} 张表" }
                    .onFailure { errorMessage = "导出失败：${it.message}" }
                selectionMode = false
                selectedTables.clear()
            }
        }
    }

    if (showBrowser) {
        StorageBrowser(startPath = null, onPick = { file -> showBrowser = false; openLocalFile(file) }, onCancel = { showBrowser = false })
        return
    }

    val current = DbManager.current
    val allObjects = DbManager.tables
    val objects = remember(allObjects, query) {
        if (query.isBlank()) allObjects else allObjects.filter { it.name.contains(query, ignoreCase = true) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenTitle("SQLlite", "打开、新建并管理 SQLite 数据库") }
        item {
            OpenActionCard(
                onOpenSystem = { openLauncher.launch(arrayOf("*/*")) },
                onBrowse = { requestStorageThen { showBrowser = true } },
                onNew = { showNewDialog = true },
                onImportSql = { importSqlLauncher.launch(arrayOf("*/*")) },
            )
        }

        if (current != null) {
            item {
                CurrentDbCard(
                    current,
                    onRefresh = { DbManager.refreshTables() },
                    onClose = { selectionMode = false; selectedTables.clear(); DbManager.closeCurrent() },
                    onWriteBack = {
                        current.sourceUri?.let { uri ->
                            runCatching { DbFileHelper.writeBack(context, Uri.parse(uri), File(current.localPath)) }
                                .onSuccess { errorMessage = "已写回原文件" }
                                .onFailure { errorMessage = it.message }
                        }
                    },
                )
            }
            item { SearchField(query, { query = it }, "搜索表 / 视图") }

            if (selectionMode) {
                item {
                    SelectionBar(
                        selectedCount = selectedTables.size,
                        actions = listOf(
                            SelectionAction("导出", Icons.Rounded.FileDownload) { showExportOptions = true },
                            SelectionAction("删除", Icons.Rounded.DeleteOutline, danger = true) { showBatchDelete = true },
                        ),
                        onSelectAll = {
                            selectedTables.clear()
                            selectedTables.addAll(objects.filter { !it.isView }.map { it.name })
                        },
                        onInvert = {
                            val all = objects.filter { !it.isView }.map { it.name }
                            val now = selectedTables.toList()
                            selectedTables.clear()
                            selectedTables.addAll(all.filter { it !in now })
                        },
                        onCancel = { selectionMode = false; selectedTables.clear() },
                    )
                }
            } else {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (query.isBlank()) "表与视图" else "搜索结果",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable { showCreateTable = true }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("新建表", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            if (objects.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Rounded.GridOn,
                        if (query.isBlank()) "没有数据表" else "未找到匹配项",
                        if (query.isBlank()) "点击上方「新建表」创建一张表，或从「导入 SQL」导入结构+数据" else "试试其它关键字",
                    )
                }
            } else {
                items(objects, key = { it.name + it.isView }) { t ->
                    val selected = selectedTables.contains(t.name)
                    ObjectRow(
                        name = t.name,
                        subtitle = if (t.isView) "视图 · ${t.columnCount} 列" else "${t.rowCount} 行 · ${t.columnCount} 列",
                        icon = if (t.isView) Icons.Rounded.Visibility else Icons.Rounded.TableChart,
                        selected = selectionMode && selected,
                        selectable = selectionMode && !t.isView,
                        onClick = {
                            when {
                                selectionMode && !t.isView -> {
                                    if (selected) selectedTables.remove(t.name) else selectedTables.add(t.name)
                                }
                                selectionMode && t.isView -> nav.push(Screen.TableDetail(t.name))
                                else -> nav.push(Screen.TableDetail(t.name))
                            }
                        },
                        onLongClick = {
                            if (!t.isView) {
                                if (!selectionMode) selectionMode = true
                                if (!selectedTables.contains(t.name)) selectedTables.add(t.name) else selectedTables.remove(t.name)
                            }
                        },
                        onMore = { menuTable = t },
                    )
                }
            }
        } else {
            if (recents.isEmpty()) {
                item { EmptyState(Icons.Rounded.Storage, "尚未打开数据库", "点击「打开文件」选择 .db / .sqlite，或用「浏览存储」直接进入内部存储目录") }
            } else {
                item { Text("最近打开", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) }
                items(recents, key = { it.id }) { ref ->
                    RecentRow(
                        ref = ref,
                        onOpen = { runCatching { DbManager.openDb(ref).getOrThrow() }.onFailure { errorMessage = it.message } },
                        onRemove = { DbStore.forget(context, ref.id); recents = DbStore.loadRecent(context) },
                    )
                }
            }
        }
    }

    if (showNewDialog) {
        NewDbDialog(
            onDismiss = { showNewDialog = false },
            onCreate = { name ->
                showNewDialog = false
                runCatching {
                    val dir = DbFileHelper.dbsDir(context)
                    val file = File(dir, DbFileHelper.sanitize(if (name.endsWith(".db") || name.endsWith(".sqlite")) name else "$name.db"))
                    if (file.exists()) file.delete()
                    val ref = DatabaseRef("local:" + file.absolutePath, file.name, null, file.absolutePath, false)
                    DbManager.openDb(ref).getOrThrow()
                    DbStore.rememberRecent(context, ref)
                    recents = DbStore.loadRecent(context)
                }.onFailure { errorMessage = it.message ?: "创建失败" }
            },
        )
    }

    if (showCreateTable) {
        CreateTableDialog(
            onDismiss = { showCreateTable = false },
            onCreate = { name, cols ->
                runCatching { DbManager.engine.createTable(name, cols) }
                    .onSuccess { showCreateTable = false; DbManager.refreshTables() }
                    .onFailure { errorMessage = it.message }
            },
        )
    }

    if (showExportOptions) {
        AlertDialog(
            onDismissRequest = { showExportOptions = false },
            title = { Text("导出 SQL（${selectedTables.size} 张表）") },
            text = {
                Column {
                    MenuRow("仅表结构", Icons.Rounded.TableChart) {
                        showExportOptions = false
                        exportIncludeData = false
                        exportLauncher.launch("schema.sql")
                    }
                    MenuRow("表结构 + 数据", Icons.Rounded.DataObject) {
                        showExportOptions = false
                        exportIncludeData = true
                        exportLauncher.launch("dump.sql")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showExportOptions = false }) { Text("取消") } },
        )
    }

    if (showBatchDelete) {
        ConfirmDialog(
            title = "删除表",
            message = "确定要删除选中的 ${selectedTables.size} 张表吗？此操作不可撤销。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                runCatching {
                    selectedTables.toList().forEach { DbManager.engine.dropTable(it) }
                }.onSuccess { DbManager.refreshTables() }.onFailure { errorMessage = it.message }
                showBatchDelete = false
                selectionMode = false
                selectedTables.clear()
            },
            onDismiss = { showBatchDelete = false },
        )
    }

    menuTable?.let { t ->
        AlertDialog(
            onDismissRequest = { menuTable = null },
            title = { Text(t.name) },
            text = {
                Column {
                    MenuRow("查看结构", Icons.Rounded.TableChart) { menuTable = null; nav.push(Screen.TableDetail(t.name)) }
                    if (!t.isView) {
                        MenuRow("重命名", Icons.Rounded.DriveFileRenameOutline) { menuTable = null; renameTarget = t }
                        MenuRow("删除表", Icons.Rounded.DeleteOutline, danger = true) { menuTable = null; deleteTarget = t }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { menuTable = null }) { Text("取消") } },
        )
    }

    renameTarget?.let { t ->
        var newName by remember(t.name) { mutableStateOf(t.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名表") },
            text = { OutlinedTextField(newName, { newName = it }, label = { Text("新表名") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { DbManager.engine.renameTable(t.name, newName.trim()) }
                        .onSuccess { renameTarget = null; DbManager.refreshTables() }
                        .onFailure { errorMessage = it.message }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }

    deleteTarget?.let { t ->
        ConfirmDialog(
            title = "删除表",
            message = "确定要删除表「${t.name}」吗？此操作不可撤销。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                runCatching { DbManager.engine.dropTable(t.name) }
                    .onSuccess { DbManager.refreshTables() }
                    .onFailure { errorMessage = it.message }
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }

    errorMessage?.let {
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text(if (it.length < 40) "提示" else "出错了") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("知道了") } },
        )
    }
}

@Composable
private fun MenuRow(text: String, icon: ImageVector, danger: Boolean = false, onClick: () -> Unit) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, color = tint, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun OpenActionCard(
    onOpenSystem: () -> Unit,
    onBrowse: () -> Unit,
    onNew: () -> Unit,
    onImportSql: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)))
            .padding(20.dp),
    ) {
        Text("开始使用", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Text("打开一个 SQLite 文件，或新建空的数据库", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionPill("打开文件", Icons.Rounded.FolderOpen, Modifier.weight(1f), onOpenSystem)
            ActionPill("新建数据库", Icons.Rounded.Add, Modifier.weight(1f), onNew)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SurfacePill("浏览存储", Icons.Rounded.SdStorage, Modifier.weight(1f), onBrowse)
            SurfacePill("导入 SQL", Icons.Rounded.FileUpload, Modifier.weight(1f), onImportSql)
        }
    }
}

@Composable
private fun ActionPill(text: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier.clip(RoundedCornerShape(15.dp)).background(Color.White).clickable(onClick = onClick).padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(text, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SurfacePill(text: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun CurrentDbCard(ref: DatabaseRef, onRefresh: () -> Unit, onClose: () -> Unit, onWriteBack: () -> Unit) {
    SectionCard(title = "当前数据库") {
        InfoRow("名称", ref.name)
        InfoRow("路径", ref.localPath)
        InfoRow("大小", humanSize(ref.sizeBytes))
        InfoRow("模式", if (ref.readOnly) "只读" else "读写")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction("刷新", Icons.Rounded.Refresh, onRefresh)
            SmallAction("关闭", Icons.Rounded.Close, onClose)
            if (ref.sourceUri != null) SmallAction("写回原件", Icons.Rounded.Upload, onWriteBack)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SmallAction(text: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ObjectRow(
    name: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    selectable: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 14.dp, end = 2.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectable) {
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!selectable) {
            IconButton(onClick = onMore) {
                Icon(Icons.Rounded.MoreVert, "更多", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RecentRow(ref: DatabaseRef, onOpen: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.DataObject, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(ref.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(ref.localPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Rounded.DeleteOutline, "移除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NewDbDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("my_database") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建数据库") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("数据库文件名") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onCreate(name) }) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun CreateTableDialog(onDismiss: () -> Unit, onCreate: (String, List<ColumnSpec>) -> Unit) {
    var name by remember { mutableStateOf("") }
    var cols by remember {
        mutableStateOf(listOf(ColumnSpec("id", "INTEGER", primaryKey = true), ColumnSpec("name", "TEXT")))
    }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建表") },
        text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("表名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text("列定义", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                cols.forEachIndexed { i, c ->
                    Column(Modifier.padding(bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = c.name,
                                onValueChange = { v -> cols = cols.toMutableList().also { it[i] = c.copy(name = v) } },
                                label = { Text("列名") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(6.dp))
                            TypeDropdown(c.type) { t -> cols = cols.toMutableList().also { it[i] = c.copy(type = t) } }
                            IconButton(onClick = { if (cols.size > 1) cols = cols.filterIndexed { j, _ -> j != i } }) {
                                Icon(Icons.Rounded.Close, "删除列", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = c.primaryKey, onCheckedChange = { b -> cols = cols.toMutableList().also { it[i] = c.copy(primaryKey = b, notNull = if (b) true else c.notNull) } })
                            Text("主键", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(8.dp))
                            Checkbox(checked = c.notNull, onCheckedChange = { b -> cols = cols.toMutableList().also { it[i] = c.copy(notNull = b) } })
                            Text("非空", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                TextButton(onClick = { cols = cols + ColumnSpec("", "TEXT") }) { Text("+ 添加列") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    name.isBlank() -> error = "请输入表名"
                    cols.any { it.name.isBlank() } -> error = "列名不能为空"
                    else -> onCreate(name.trim(), cols.map { it.copy(name = it.name.trim()) })
                }
            }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun TypeDropdown(current: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 14.dp),
        ) {
            Text(current, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf("INTEGER", "TEXT", "REAL", "BLOB", "NUMERIC").forEach { t ->
                DropdownMenuItem(text = { Text(t) }, onClick = { onSelect(t); open = false })
            }
        }
    }
}
