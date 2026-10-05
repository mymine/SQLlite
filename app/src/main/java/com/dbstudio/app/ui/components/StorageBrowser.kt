package com.dbstudio.app.ui.components

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/** 内置文件浏览器：浏览设备存储（需 MANAGE_EXTERNAL_STORAGE 才能访问 /storage/emulated/0） */
@Composable
fun StorageBrowser(
    startPath: String?,
    onPick: (File) -> Unit,
    onCancel: () -> Unit,
) {
    val initial = remember {
        val candidates = listOfNotNull(
            startPath?.let { File(it) },
            Environment.getExternalStorageDirectory(),
            File("/storage/emulated/0"),
        )
        candidates.firstOrNull { it.exists() && it.canRead() } ?: File("/")
    }
    var cwd by remember { mutableStateOf(initial) }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(cwd, refresh) {
        val list = cwd.listFiles()
        if (list == null) {
            error = "无法读取该目录（可能缺少「所有文件访问」权限）"
            entries = emptyList()
        } else {
            error = null
            entries = list
                .filter { !it.name.startsWith(".") }
                .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }

    fun enter(dir: File) {
        cwd = dir
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                val parent = cwd.parentFile
                if (parent != null && cwd.absolutePath != "/") enter(parent) else onCancel()
            }) { Icon(Icons.Rounded.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onBackground) }
            Column(Modifier.weight(1f)) {
                Text(
                    text = cwd.name.ifEmpty { "/" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = cwd.absolutePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onCancel) { Text("取消") }
        }
        HorizontalDivider()

        if (cwd.absolutePath == "/") {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { enter(File("/storage/emulated/0")) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.SdCard, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("内部存储 (/storage/emulated/0)", fontWeight = FontWeight.Medium)
            }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(entries, key = { it.absolutePath }) { f ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { if (f.isDirectory) enter(f) else onPick(f) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (f.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Description,
                        contentDescription = null,
                        tint = if (f.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!f.isDirectory) {
                            Text(
                                humanSize(f.length()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

fun humanSize(bytes: Long): String {
    if (bytes <= 0) return "—"
    val kb = bytes / 1024.0
    return when {
        kb < 1024 -> String.format("%.1f KB", kb)
        kb < 1024 * 1024 -> String.format("%.1f MB", kb / 1024.0)
        else -> String.format("%.2f GB", kb / 1024.0 / 1024.0)
    }
}
