@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.dbstudio.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dbstudio.app.data.DbManager
import com.dbstudio.app.data.db.QueryResult
import com.dbstudio.app.data.db.SqliteEngine
import com.dbstudio.app.data.io.CsvCodec
import com.dbstudio.app.data.io.JsonCodec
import com.dbstudio.app.ui.AppNav
import com.dbstudio.app.ui.components.ConfirmDialog
import com.dbstudio.app.ui.components.EmptyState
import com.dbstudio.app.ui.components.ScreenTitle
import com.dbstudio.app.ui.components.SearchField
import com.dbstudio.app.ui.components.SelectionAction
import com.dbstudio.app.ui.components.SelectionBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val COL_WIDTH = 150.dp
private const val PAGE_SIZE = 200

@Composable
fun DataScreen(nav: AppNav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = DbManager.engine
    val current = DbManager.current

    if (current == null || !engine.isOpen) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Spacer(Modifier.height(10.dp))
            ScreenTitle("数据")
            EmptyState(Icons.Rounded.TableChart, "未打开数据库", "请先在「数据库」页打开一个 SQLite 文件")
        }
        return
    }

    val tables = remember(DbManager.tables) { DbManager.tables.filter { !it.isView } }
    var table by remember(current.localPath) { mutableStateOf(tables.firstOrNull()?.name) }
    var page by remember { mutableIntStateOf(0) }
    var orderBy by remember { mutableStateOf<String?>(null) }
    var desc by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<QueryResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editingRow by remember { mutableStateOf<List<String?>?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf<String?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var pendingFormat by remember { mutableStateOf("csv") }

    // 多选
    var selectionMode by remember { mutableStateOf(false) }
    val selectedRowIds = remember { mutableStateListOf<String>() }
    var showBatchDelete by remember { mutableStateOf(false) }

    fun reload() {
        val t = table ?: return
        loading = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val order = orderBy?.let {
                        " ORDER BY ${SqliteEngine.quoteIdent(it)} ${if (desc) "DESC" else "ASC"}"
                    } ?: ""
                    val kw = searchQuery.trim()
                    var where = ""
                    val args = ArrayList<Any?>()
                    if (kw.isNotEmpty()) {
                        val names = engine.columns(t).map { it.name }
                        where = " WHERE " + names.joinToString(" OR ") { "CAST(${SqliteEngine.quoteIdent(it)} AS TEXT) LIKE ?" }
                        names.forEach { args.add("%" + kw + "%") }
                    }
                    args.add(PAGE_SIZE)
                    args.add(page * PAGE_SIZE)
                    engine.query("SELECT rowid AS _rowid_, * FROM ${SqliteEngine.quoteIdent(t)}$where$order LIMIT ? OFFSET ?", args)
                }
            }
            r.onSuccess { result = it; error = null }.onFailure { error = it.message }
            loading = false
        }
    }

    LaunchedEffect(table, page, orderBy, desc, searchQuery) {
        selectionMode = false
        selectedRowIds.clear()
        reload()
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val t = table
        if (uri != null && t != null) {
            val fmt = pendingFormat
            val onlySelected = selectionMode
            val ids = selectedRowIds.toList()
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val all = if (onlySelected) {
                            val src = result
                            QueryResult(src?.columns ?: emptyList(), (src?.rows ?: emptyList()).filter { it.firstOrNull() in ids })
                        } else {
                            engine.query("SELECT * FROM ${SqliteEngine.quoteIdent(t)}")
                        }
                        val content = buildExport(fmt, t, all)
                        context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                    }
                }.onFailure { error = it.message }
                    .onSuccess { statusText = "已导出 ${fmt.uppercase()} 文件" }
                selectionMode = false
                selectedRowIds.clear()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val t = table
        if (uri != null && t != null) {
            scope.launch {
                loading = true
                runCatching {
                    withContext(Dispatchers.IO) {
                        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                        val (cols, rows) = when {
                            text.trim().startsWith("[") || text.trim().startsWith("{") -> JsonCodec.decode(text)
                            else -> {
                                val matrix = CsvCodec.parse(text)
                                if (matrix.isEmpty()) emptyList<String>() to emptyList<List<String?>>()
                                else matrix[0] to matrix.drop(1).map { it.map { c -> c as String? } }
                            }
                        }
                        engine.transaction {
                            var imported = 0
                            rows.forEach { row ->
                                val values = LinkedHashMap<String, Any?>()
                                cols.forEachIndexed { i, c -> values[c] = row.getOrNull(i) }
                                engine.insert(t, values)
                                imported++
                            }
                            imported
                        }
                    }
                }.onSuccess { statusText = "已导入 $it 行" }.onFailure { error = it.message }
                loading = false
                reload()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(10.dp))
            ScreenTitle("数据", current.name)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tables.forEach { t ->
                    val selected = t.name == table
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { table = t.name; page = 0; orderBy = null }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            t.name,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (selectionMode) {
                SelectionBar(
                    selectedCount = selectedRowIds.size,
                    actions = listOf(
                        SelectionAction("删除", Icons.Rounded.DeleteOutline, danger = true) { showBatchDelete = true },
                        SelectionAction("导出", Icons.Rounded.FileDownload) { showExportDialog = true },
                    ),
                    onSelectAll = {
                        selectedRowIds.clear()
                        result?.rows?.forEach { it.firstOrNull()?.let { id -> selectedRowIds.add(id) } }
                    },
                    onInvert = {
                        val all = result?.rows?.mapNotNull { it.firstOrNull() } ?: emptyList()
                        val now = selectedRowIds.toList()
                        selectedRowIds.clear()
                        selectedRowIds.addAll(all.filter { it !in now })
                    },
                    onCancel = { selectionMode = false; selectedRowIds.clear() },
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolChip("刷新", Icons.Rounded.Refresh) { reload() }
                    ToolChip("新增", Icons.Rounded.Add) { editingRow = emptyList() }
                    ToolChip("导出", Icons.Rounded.FileDownload) { showExportDialog = true }
                    ToolChip("导入", Icons.Rounded.FileUpload) { importLauncher.launch(arrayOf("*/*")) }
                }
            }
            Spacer(Modifier.height(8.dp))
            SearchField(searchQuery, { searchQuery = it; page = 0 }, "搜索当前表数据")
            Spacer(Modifier.height(4.dp))
        }

        val res = result
        if (loading && res == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (res != null) {
            DataGrid(
                result = res,
                modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
                selectionMode = selectionMode,
                selected = selectedRowIds,
                onHeaderClick = { col -> if (col != "_rowid_") { if (orderBy == col) desc = !desc else { orderBy = col; desc = false }; page = 0 } },
                onRowClick = { row ->
                    val id = row.firstOrNull() ?: return@DataGrid
                    if (selectionMode) {
                        if (selectedRowIds.contains(id)) selectedRowIds.remove(id) else selectedRowIds.add(id)
                    } else {
                        editingRow = row
                    }
                },
                onRowLongClick = { row ->
                    val id = row.firstOrNull() ?: return@DataGrid
                    if (!selectionMode) selectionMode = true
                    if (selectedRowIds.contains(id)) selectedRowIds.remove(id) else selectedRowIds.add(id)
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "第 ${page + 1} 页 · ${res.rows.size} 行",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButtonBox(Icons.Rounded.ChevronLeft, page > 0) { if (page > 0) page-- }
                IconButtonBox(Icons.Rounded.ChevronRight, res.rows.size >= PAGE_SIZE) { page++ }
            }
        } else {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(error ?: "无数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    editingRow?.let { row ->
        RowEditDialog(
            engine = engine,
            table = table ?: "",
            columns = result?.columns ?: emptyList(),
            row = row,
            onDismiss = { editingRow = null },
            onSaved = { editingRow = null; reload(); statusText = "已保存" },
            onDeleted = { editingRow = null; reload(); statusText = "已删除" },
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(if (selectionMode) "导出选中行（${selectedRowIds.size}）" else "导出为") },
            text = {
                Column {
                    listOf("CSV" to "csv", "TXT" to "txt", "JSON" to "json", "SQL" to "sql").forEach { (label, fmt) ->
                        Text(
                            label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    showExportDialog = false
                                    pendingFormat = fmt
                                    exportLauncher.launch((table ?: "table") + "." + fmt)
                                }
                                .padding(vertical = 12.dp, horizontal = 6.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showExportDialog = false }) { Text("取消") } },
        )
    }

    if (showBatchDelete) {
        ConfirmDialog(
            title = "删除行",
            message = "确定要删除选中的 ${selectedRowIds.size} 行吗？此操作不可撤销。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                val ids = selectedRowIds.toList()
                val t = table
                if (t != null && ids.isNotEmpty()) {
                    runCatching {
                        val placeholders = ids.joinToString(",") { "?" }
                        engine.delete(t, "rowid IN ($placeholders)", ids)
                    }.onSuccess { statusText = "已删除 ${ids.size} 行" }.onFailure { error = it.message }
                }
                showBatchDelete = false
                selectionMode = false
                selectedRowIds.clear()
                reload()
            },
            onDismiss = { showBatchDelete = false },
        )
    }

    statusText?.let {
        AlertDialog(
            onDismissRequest = { statusText = null },
            title = { Text("完成") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { statusText = null }) { Text("好的") } },
        )
    }
}

@Composable
private fun ToolChip(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
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
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun IconButtonBox(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, null,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun DataGrid(
    result: QueryResult,
    modifier: Modifier = Modifier,
    selectionMode: Boolean,
    selected: List<String>,
    onHeaderClick: (String) -> Unit,
    onRowClick: (List<String?>) -> Unit,
    onRowLongClick: (List<String?>) -> Unit,
) {
    val hScroll = rememberScrollState()
    val totalWidth: Dp = COL_WIDTH * result.columns.size + (if (selectionMode) 34.dp else 0.dp)
    Column(modifier = modifier.fillMaxSize().horizontalScroll(hScroll)) {
        Row(
            modifier = Modifier
                .width(totalWidth)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(vertical = 10.dp),
        ) {
            if (selectionMode) Spacer(Modifier.width(34.dp))
            result.columns.forEach { col ->
                Text(
                    col,
                    modifier = Modifier.width(COL_WIDTH).padding(horizontal = 10.dp).clickable { onHeaderClick(col) },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.width(totalWidth).weight(1f)) {
            itemsIndexed(result.rows) { _, row ->
                val id = row.firstOrNull()
                val isSelected = selectionMode && id != null && selected.contains(id)
                Row(
                    modifier = Modifier
                        .width(totalWidth)
                        .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else androidx.compose.ui.graphics.Color.Transparent)
                        .combinedClickable(onClick = { onRowClick(row) }, onLongClick = { onRowLongClick(row) })
                        .padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selectionMode) {
                        Box(Modifier.width(34.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    row.forEach { cell ->
                        val isNull = cell == null
                        Text(
                            text = if (isNull) "NULL" else cell,
                            modifier = Modifier.width(COL_WIDTH).padding(horizontal = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 12.5.sp,
                            color = if (isNull) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
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

@Composable
private fun RowEditDialog(
    engine: SqliteEngine,
    table: String,
    columns: List<String>,
    row: List<String?>,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
) {
    val isNew = row.isEmpty()
    val editable = columns.filter { it != "_rowid_" }
    var values by remember { mutableStateOf(editable.mapIndexed { i, _ -> row.getOrNull(i) ?: "" }) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新增行 · $table" else "编辑行 · $table") },
        text = {
            Column(Modifier.height(380.dp).verticalScroll(rememberScrollState())) {
                editable.forEachIndexed { i, col ->
                    OutlinedTextField(
                        value = values.getOrNull(i) ?: "",
                        onValueChange = { v -> values = values.toMutableList().also { it[i] = v } },
                        label = { Text(col) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching {
                    val map = LinkedHashMap<String, Any?>()
                    editable.forEachIndexed { i, col -> map[col] = values.getOrNull(i)?.ifEmpty { null } }
                    if (isNew) engine.insert(table, map)
                    else engine.update(table, map, "rowid=?", listOf(row.getOrNull(0)))
                }.onSuccess { onSaved() }.onFailure { error = it.message }
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = {
                        runCatching { engine.delete(table, "rowid=?", listOf(row.getOrNull(0))) }
                            .onSuccess { onDeleted() }.onFailure { error = it.message }
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

private fun buildExport(format: String, table: String, result: QueryResult): String = when (format.lowercase()) {
    "json" -> JsonCodec.encodeRows(result.columns, result.rows)
    "sql" -> buildString {
        append("-- Export of $table\n")
        result.rows.forEach { row ->
            append("INSERT INTO ${SqliteEngine.quoteIdent(table)} (")
            append(result.columns.joinToString(", ") { SqliteEngine.quoteIdent(it) })
            append(") VALUES (")
            append(row.joinToString(", ") { SqliteEngine.literal(it) })
            append(");\n")
        }
    }
    "txt" -> CsvCodec.encode(result.columns, result.rows, '\t')
    else -> CsvCodec.encode(result.columns, result.rows)
}
