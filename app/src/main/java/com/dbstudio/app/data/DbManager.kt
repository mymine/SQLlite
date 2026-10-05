package com.dbstudio.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dbstudio.app.data.db.SqliteEngine
import com.dbstudio.app.data.db.TableOverview

/** 全局数据库会话状态（单例，供 Compose 观察） */
object DbManager {

    val engine = SqliteEngine()

    var current by mutableStateOf<DatabaseRef?>(null)
        private set

    val openDbs = mutableStateListOf<DatabaseRef>()

    var tables by mutableStateOf<List<TableOverview>>(emptyList())
    var currentTable by mutableStateOf<String?>(null)

    fun openDb(ref: DatabaseRef): Result<Unit> {
        val result = engine.open(ref.localPath, ref.readOnly)
        if (result.isSuccess) {
            current = ref
            if (openDbs.none { it.id == ref.id }) openDbs.add(ref)
            currentTable = null
            refreshTables()
        }
        return result
    }

    fun closeCurrent() {
        engine.close()
        current = null
        currentTable = null
        tables = emptyList()
    }

    fun refreshTables() {
        tables = if (engine.isOpen) {
            runCatching { engine.tableOverviews() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        if (currentTable != null && tables.none { it.name == currentTable && !it.isView }) {
            currentTable = null
        }
    }
}
