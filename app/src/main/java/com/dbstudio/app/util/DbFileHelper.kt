package com.dbstudio.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** SAF 文件与本地工作副本的转换 */
object DbFileHelper {

    fun dbsDir(context: Context): File = File(context.filesDir, "dbs").apply { mkdirs() }

    fun exportDir(context: Context): File = File(context.filesDir, "export").apply { mkdirs() }

    fun displayName(context: Context, uri: Uri): String {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "database.db"
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
            }
        }
        return name
    }

    fun takePermission(context: Context, uri: Uri, write: Boolean) {
        runCatching {
            val flags = if (write) {
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            } else {
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }
    }

    /** 将外部 URI 复制为本地工作副本，返回文件 */
    fun importUri(context: Context, uri: Uri): File {
        val name = sanitize(displayName(context, uri))
        val target = uniqueFile(dbsDir(context), name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        } ?: throw IllegalStateException("无法读取所选文件")
        return target
    }

    fun writeBack(context: Context, uri: Uri, file: File) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            file.inputStream().use { input -> input.copyTo(out) }
        } ?: throw IllegalStateException("无法写入目标文件")
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) {
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            f = File(dir, "${base}_$i$suffix")
            i++
        }
        return f
    }

    fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]"), "_").ifBlank { "database.db" }
}
