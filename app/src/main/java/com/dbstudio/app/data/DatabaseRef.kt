package com.dbstudio.app.data

/** 一个已打开/可打开的数据库引用 */
data class DatabaseRef(
    val id: String,
    val name: String,
    /** 原始 content:// URI（来自 SAF），本地新建时为 null */
    val sourceUri: String? = null,
    /** 真实打开的文件路径 */
    val localPath: String,
    val readOnly: Boolean = false,
    val sizeBytes: Long = 0L,
)
