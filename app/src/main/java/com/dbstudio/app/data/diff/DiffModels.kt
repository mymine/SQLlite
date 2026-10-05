package com.dbstudio.app.data.diff

enum class ChangeKind { ADDED, REMOVED, CHANGED, SAME }

data class ColumnChange(
    val name: String,
    val kind: ChangeKind,
    val leftType: String? = null,
    val rightType: String? = null,
    val detail: String = "",
)

data class SchemaDiffResult(
    val leftName: String,
    val rightName: String,
    val columns: List<ColumnChange>,
) {
    val hasDiff: Boolean get() = columns.any { it.kind != ChangeKind.SAME }
    val addedCount: Int get() = columns.count { it.kind == ChangeKind.ADDED }
    val removedCount: Int get() = columns.count { it.kind == ChangeKind.REMOVED }
    val changedCount: Int get() = columns.count { it.kind == ChangeKind.CHANGED }
}

data class RowDiff(
    val key: String,
    val kind: ChangeKind,
    val left: List<String?>?,
    val right: List<String?>?,
    val changedColumns: List<String> = emptyList(),
)

data class DataDiffResult(
    val columns: List<String>,
    val keyLabel: String,
    val rows: List<RowDiff>,
    val addedCount: Int,
    val removedCount: Int,
    val changedCount: Int,
    val sameCount: Int,
) {
    val hasDiff: Boolean get() = addedCount + removedCount + changedCount > 0
}

/** 单元格级差异（用于逐单元格高亮） */
data class CellDiff(
    val table: String,
    val key: String,
    val column: String,
    val kind: ChangeKind,
)
