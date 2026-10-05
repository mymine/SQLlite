package com.dbstudio.app.data.diff

import com.dbstudio.app.data.db.ColumnInfo

/** 表结构对比 */
object SchemaDiffer {

    fun diff(leftName: String, left: List<ColumnInfo>, rightName: String, right: List<ColumnInfo>): SchemaDiffResult {
        val leftMap = left.associateBy { it.name.lowercase() }
        val rightMap = right.associateBy { it.name.lowercase() }
        val order = LinkedHashSet<String>()
        left.forEach { order.add(it.name.lowercase()) }
        right.forEach { order.add(it.name.lowercase()) }

        val result = ArrayList<ColumnChange>()
        for (key in order) {
            val l = leftMap[key]
            val r = rightMap[key]
            when {
                l != null && r == null -> result.add(
                    ColumnChange(l.name, ChangeKind.REMOVED, l.type, null, "仅存在于左表")
                )
                l == null && r != null -> result.add(
                    ColumnChange(r.name, ChangeKind.ADDED, null, r.type, "仅存在于右表")
                )
                l != null && r != null -> {
                    val details = ArrayList<String>()
                    if (!l.type.equals(r.type, ignoreCase = true)) details.add("类型 ${l.type} → ${r.type}")
                    if (l.notNull != r.notNull) details.add("NOT NULL ${l.notNull} → ${r.notNull}")
                    if ((l.defaultValue ?: "") != (r.defaultValue ?: "")) {
                        details.add("默认值 ${l.defaultValue ?: "-"} → ${r.defaultValue ?: "-"}")
                    }
                    if (l.primaryKey != r.primaryKey) details.add("主键 ${l.primaryKey} → ${r.primaryKey}")
                    result.add(
                        ColumnChange(
                            name = r.name,
                            kind = if (details.isEmpty()) ChangeKind.SAME else ChangeKind.CHANGED,
                            leftType = l.type,
                            rightType = r.type,
                            detail = details.joinToString("，"),
                        )
                    )
                }
            }
        }
        return SchemaDiffResult(leftName, rightName, result)
    }
}

/** 表数据对比 */
object DataDiffer {

    fun diff(
        columns: List<String>,
        keyIndex: Int?,
        left: List<List<String?>>,
        right: List<List<String?>>,
    ): DataDiffResult {
        fun keyOf(row: List<String?>): String =
            if (keyIndex != null) {
                row.getOrNull(keyIndex) ?: "\u0000NULL"
            } else {
                row.joinToString("\u0001") { it ?: "\u0000NULL" }
            }

        val leftMap = LinkedHashMap<String, List<String?>>()
        left.forEach { leftMap[keyOf(it)] = it }
        val rightMap = LinkedHashMap<String, List<String?>>()
        right.forEach { rightMap[keyOf(it)] = it }

        val keys = LinkedHashSet<String>()
        keys.addAll(leftMap.keys)
        keys.addAll(rightMap.keys)

        val rows = ArrayList<RowDiff>()
        var added = 0; var removed = 0; var changed = 0; var same = 0
        for (k in keys) {
            val l = leftMap[k]
            val r = rightMap[k]
            when {
                l != null && r == null -> { rows.add(RowDiff(k, ChangeKind.REMOVED, l, null)); removed++ }
                l == null && r != null -> { rows.add(RowDiff(k, ChangeKind.ADDED, null, r)); added++ }
                l != null && r != null -> {
                    val changedCols = ArrayList<String>()
                    for (i in columns.indices) {
                        val lv = l.getOrNull(i)
                        val rv = r.getOrNull(i)
                        if (lv != rv) changedCols.add(columns[i])
                    }
                    if (changedCols.isEmpty()) { same++ } else {
                        changed++
                        rows.add(RowDiff(k, ChangeKind.CHANGED, l, r, changedCols))
                    }
                }
            }
        }

        val keyLabel = if (keyIndex != null) columns.getOrNull(keyIndex) ?: "整行" else "整行"
        return DataDiffResult(columns, keyLabel, rows, added, removed, changed, same)
    }
}
