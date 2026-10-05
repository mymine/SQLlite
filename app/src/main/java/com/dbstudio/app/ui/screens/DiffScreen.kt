package com.dbstudio.app.ui.screens

import android.net.Uri
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dbstudio.app.data.DatabaseRef
import com.dbstudio.app.data.DbManager
import com.dbstudio.app.data.DbStore
import com.dbstudio.app.data.db.SqliteEngine
import com.dbstudio.app.data.diff.ChangeKind
import com.dbstudio.app.data.diff.DataDiffer
import com.dbstudio.app.data.diff.DataDiffResult
import com.dbstudio.app.data.diff.RowDiff
import com.dbstudio.app.data.diff.SchemaDiffer
import com.dbstudio.app.data.diff.SchemaDiffResult
import com.dbstudio.app.ui.AppNav
import com.dbstudio.app.ui.components.EmptyState
import com.dbstudio.app.ui.components.ScreenTitle
import com.dbstudio.app.ui.components.SectionCard
import com.dbstudio.app.ui.components.StorageBrowser
import com.dbstudio.app.util.DbFileHelper
import java.io.File

/** 一个用于对比的数据库（拥有独立引擎，不影响主会话） */
class DiffDb(val ref: DatabaseRef, val engine: SqliteEngine, val tables: List<String>)

private fun openAsDiffDb(ref: DatabaseRef): DiffDb? {
    val engine = SqliteEngine()
    val ok = engine.open(ref.localPath, readOnly = true).isSuccess
    if (!ok) return null
    val tables = runCatching { engine.tableNames(false) }.getOrDefault(emptyList())
    return DiffDb(ref, engine, tables)
}

@Composable
fun DiffScreen(nav: AppNav) {
    val context = LocalContext.current
    var mode by remember { mutableIntStateOf(0) } // 0 结构 / 1 数据
    var left by remember { mutableStateOf<DiffDb?>(null) }
    var right by remember { mutableStateOf<DiffDb?>(null) }
    var leftTable by remember { mutableStateOf<String?>(null) }
    var rightTable by remember { mutableStateOf<String?>(null) }
    var schemaResult by remember { mutableStateOf<SchemaDiffResult?>(null) }
    var dataResult by remember { mutableStateOf<DataDiffResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickingDb by remember { mutableIntStateOf(0) } // 0 none / 1 left / 2 right
    var pickingTable by remember { mutableIntStateOf(0) }
    var browsingSide by remember { mutableIntStateOf(0) }

    fun assignSide(side: Int, db: DiffDb?) {
        if (side == 1) {
            left = db
            leftTable = db?.tables?.firstOrNull()
        } else {
            right = db
            rightTable = db?.tables?.firstOrNull()
        }
        schemaResult = null
        dataResult = null
    }

    LaunchedEffect(Unit) {
        DbManager.current?.let { cur ->
            val d = openAsDiffDb(cur)
            if (d != null) {
                left = d
                right = d
                leftTable = d.tables.firstOrNull()
                rightTable = d.tables.getOrNull(1) ?: d.tables.firstOrNull()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val side = browsingSide
        if (uri != null && side != 0) {
            DbFileHelper.takePermission(context, uri, write = false)
            runCatching {
                val file = DbFileHelper.importUri(context, uri)
                val ref = DatabaseRef(uri.toString(), file.name, uri.toString(), file.absolutePath, readOnly = true, sizeBytes = file.length())
                assignSide(side, openAsDiffDb(ref))
            }.onFailure { error = it.message }
        }
    }

    if (browsingSide != 0) {
        val side = browsingSide
        StorageBrowser(
            startPath = null,
            onPick = { file ->
                browsingSide = 0
                runCatching {
                    val ref = DatabaseRef("file:" + file.absolutePath, file.name, null, file.absolutePath, readOnly = true, sizeBytes = file.length())
                    assignSide(side, openAsDiffDb(ref))
                }.onFailure { error = it.message }
            },
            onCancel = { browsingSide = 0 },
        )
        return
    }

    fun runDiff() {
        val ldb = left
        val rdb = right
        val lt = leftTable
        val rt = rightTable
        if (ldb == null || rdb == null) {
            error = "请先选择要对比的两个数据库"
            return
        }
        if (lt == null || rt == null) {
            error = "请先选择要对比的两张表"
            return
        }
        runCatching {
            if (mode == 0) {
                schemaResult = SchemaDiffer.diff(lt, ldb.engine.columns(lt), rt, rdb.engine.columns(rt))
                dataResult = null
            } else {
                val cols = ldb.engine.columns(lt)
                val pk = cols.firstOrNull { it.primaryKey }?.name
                val colNames = cols.map { it.name }
                val pkIndex = pk?.let { colNames.indexOf(it) }?.takeIf { it >= 0 }
                val leftRows = ldb.engine.query("SELECT * FROM ${SqliteEngine.quoteIdent(lt)}").rows
                val rightRows = rdb.engine.query("SELECT * FROM ${SqliteEngine.quoteIdent(rt)}").rows
                dataResult = DataDiffer.diff(colNames, pkIndex, leftRows, rightRows)
                schemaResult = null
            }
        }.onFailure { error = it.message }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(10.dp))
        ScreenTitle("对比", "支持跨数据库文件对比")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SegChip("表结构", mode == 0) { mode = 0; schemaResult = null; dataResult = null }
            SegChip("表数据", mode == 1) { mode = 1; schemaResult = null; dataResult = null }
        }
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SideSelector(
                label = "左侧",
                dbName = left?.ref?.name,
                tableName = leftTable,
                modifier = Modifier.weight(1f),
                onPickDb = { pickingDb = 1 },
                onPickTable = { pickingTable = 1 },
            )
            SideSelector(
                label = "右侧",
                dbName = right?.ref?.name,
                tableName = rightTable,
                modifier = Modifier.weight(1f),
                onPickDb = { pickingDb = 2 },
                onPickTable = { pickingTable = 2 },
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primary)
                .clickable { runDiff() }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("开始对比", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            val sr = schemaResult
            val dr = dataResult
            if (sr != null) {
                item {
                    SectionCard(title = "结构对比：${left?.ref?.name}/$leftTable  ↔  ${right?.ref?.name}/$rightTable") {
                        if (!sr.hasDiff) {
                            Text("两张表结构完全一致 ✓", color = MaterialTheme.colorScheme.secondary)
                        } else {
                            Text(
                                "新增 ${sr.addedCount} · 删除 ${sr.removedCount} · 变更 ${sr.changedCount}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            sr.columns.filter { it.kind != ChangeKind.SAME }.forEach { c ->
                                DiffColumnRow(c.name, c.kind, c.detail)
                            }
                        }
                    }
                }
            }
            if (dr != null) {
                item {
                    SectionCard(title = "数据对比（键：${dr.keyLabel}）") {
                        Text(
                            "新增 ${dr.addedCount} · 删除 ${dr.removedCount} · 变更 ${dr.changedCount} · 相同 ${dr.sameCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (dr.rows.isEmpty()) {
                            Text("数据完全一致 ✓", color = MaterialTheme.colorScheme.secondary)
                        } else {
                            dr.rows.take(300).forEach { row -> DataDiffRowView(row) }
                        }
                    }
                }
            }
            if (sr == null && dr == null) {
                item { EmptyState(Icons.Rounded.CompareArrows, "选择两个数据库与表", "可对比同一库内的两张表，也可对比两个不同的数据库文件") }
            }
            item { Spacer(Modifier.height(110.dp)) }
        }
    }

    if (pickingDb != 0) {
        val side = pickingDb
        val candidates = remember(side, DbManager.openDbs.size) {
            buildList {
                DbManager.current?.let { add(it) }
                addAll(DbManager.openDbs)
                addAll(DbStore.loadRecent(context))
            }.distinctBy { it.localPath }
        }
        AlertDialog(
            onDismissRequest = { pickingDb = 0 },
            title = { Text(if (side == 1) "选择左侧数据库" else "选择右侧数据库") },
            text = {
                Column(Modifier.height(360.dp).verticalScroll(rememberScrollState())) {
                    candidates.forEach { ref ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    pickingDb = 0
                                    runCatching {
                                        val d = openAsDiffDb(ref)
                                        if (d == null) error = "无法打开：${ref.name}" else assignSide(side, d)
                                    }.onFailure { error = it.message }
                                }
                                .padding(vertical = 10.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Storage, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(ref.name, style = MaterialTheme.typography.bodyLarge)
                                Text(ref.localPath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { pickingDb = 0; browsingSide = side }
                            .padding(vertical = 10.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Storage, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(10.dp))
                        Text("浏览存储 / 选择文件…", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Medium)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingDb = 0 }) { Text("取消") } },
        )
    }

    if (pickingTable != 0) {
        val side = pickingTable
        val db = if (side == 1) left else right
        AlertDialog(
            onDismissRequest = { pickingTable = 0 },
            title = { Text(if (side == 1) "选择左侧的表" else "选择右侧的表") },
            text = {
                Column(Modifier.height(360.dp).verticalScroll(rememberScrollState())) {
                    val names = db?.tables ?: emptyList()
                    if (names.isEmpty()) Text("该数据库中没有表", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    names.forEach { name ->
                        Text(
                            name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    if (side == 1) leftTable = name else rightTable = name
                                    pickingTable = 0
                                    schemaResult = null
                                    dataResult = null
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingTable = 0 }) { Text("取消") } },
        )
    }
}

@Composable
private fun SideSelector(
    label: String,
    dbName: String?,
    tableName: String?,
    modifier: Modifier = Modifier,
    onPickDb: () -> Unit,
    onPickTable: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onPickDb).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Storage, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.width(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                dbName ?: "选择数据库",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (dbName == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            tableName ?: "选择表",
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onPickTable)
                .padding(vertical = 8.dp, horizontal = 2.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = if (tableName == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SegChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun DiffColumnRow(name: String, kind: ChangeKind, detail: String) {
    val (color, tag) = when (kind) {
        ChangeKind.ADDED -> MaterialTheme.colorScheme.secondary to "新增"
        ChangeKind.REMOVED -> MaterialTheme.colorScheme.error to "删除"
        ChangeKind.CHANGED -> Color(0xFFE09000) to "变更"
        ChangeKind.SAME -> MaterialTheme.colorScheme.outline to "相同"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(color.copy(alpha = 0.16f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(tag, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DataDiffRowView(row: RowDiff) {
    val (color, tag) = when (row.kind) {
        ChangeKind.ADDED -> MaterialTheme.colorScheme.secondary to "+"
        ChangeKind.REMOVED -> MaterialTheme.colorScheme.error to "−"
        ChangeKind.CHANGED -> Color(0xFFE09000) to "~"
        ChangeKind.SAME -> MaterialTheme.colorScheme.outline to "="
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(tag, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp), fontSize = 15.sp)
        Column(Modifier.weight(1f)) {
            Text(row.key, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (row.kind == ChangeKind.CHANGED) {
                Text(
                    "变更列：" + row.changedColumns.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
