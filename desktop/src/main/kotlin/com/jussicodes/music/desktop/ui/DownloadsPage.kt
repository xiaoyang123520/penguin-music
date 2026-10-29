package com.jussicodes.music.desktop.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import java.awt.Desktop
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 下载管理页（2.0.5，C4）：进行中/失败/已完成的下载任务（来自
 * DownloadHelper 的任务流，带进度、取消、重试）+ 下载目录里已落盘的
 * 文件列表（大小/时间、打开文件夹、删除）。此前下载只有右键菜单一个
 * 入口、过程完全不可见。
 */
@Composable
fun DownloadsPage(navController: NavController) {
    val context = LocalContext.current
    val tasks by DownloadHelper.tasks.collectAsState()
    var files by remember { mutableStateOf(listLocalFiles()) }
    var tick by remember { mutableStateOf(0) }
    // 任务状态变化（尤其新任务完成）时刷新文件列表
    androidx.compose.runtime.LaunchedEffect(tasks, tick) { files = listLocalFiles() }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("下载管理", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Text(
                "目录：${DownloadHelper.downloadDir().absolutePath}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = {
                runCatching {
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().open(DownloadHelper.downloadDir())
                    }
                }
            }) { Text("打开下载文件夹") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { tick++ }) { Text("刷新") }
        }
        Spacer(Modifier.height(14.dp))

        if (tasks.isNotEmpty()) {
            Text("下载任务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            tasks.forEach { task ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            task.fileName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            when (task.status) {
                                DownloadHelper.Status.RUNNING ->
                                    if (task.totalBytes > 0) {
                                        "${formatBytes(task.downloadedBytes)} / ${formatBytes(task.totalBytes)}"
                                    } else {
                                        "已下载 ${formatBytes(task.downloadedBytes)}"
                                    }
                                DownloadHelper.Status.DONE -> "已完成 · ${formatBytes(task.downloadedBytes)}"
                                DownloadHelper.Status.FAILED -> "失败：${task.error ?: "未知错误"}"
                                DownloadHelper.Status.CANCELED -> "已取消"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (task.status == DownloadHelper.Status.RUNNING && task.totalBytes > 0) {
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = {
                                    (task.downloadedBytes.toFloat() / task.totalBytes)
                                        .coerceIn(0f, 1f)
                                },
                                modifier = Modifier.fillMaxWidth(0.6f)
                            )
                        }
                    }
                    when (task.status) {
                        DownloadHelper.Status.RUNNING ->
                            OutlinedButton(onClick = { DownloadHelper.cancel(task.id) }) {
                                Text("取消")
                            }
                        DownloadHelper.Status.FAILED, DownloadHelper.Status.CANCELED -> {
                            OutlinedButton(onClick = { DownloadHelper.retry(context, task) }) {
                                Text("重试")
                            }
                            Spacer(Modifier.width(6.dp))
                            OutlinedButton(onClick = { DownloadHelper.dismiss(task.id) }) {
                                Text("移除")
                            }
                        }
                        DownloadHelper.Status.DONE ->
                            OutlinedButton(onClick = { DownloadHelper.dismiss(task.id) }) {
                                Text("移除记录")
                            }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        Text("已下载文件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (files.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有下载过歌曲（在歌曲上点右键可下载）",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(files, key = { it.absolutePath }) { file ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                file.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${formatBytes(file.length())} · " +
                                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                        .format(Date(file.lastModified())),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(onClick = {
                            runCatching { file.delete() }
                            tick++
                        }) { Text("删除") }
                    }
                }
            }
        }
    }
}

private fun listLocalFiles(): List<File> =
    runCatching {
        DownloadHelper.downloadDir().listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }.getOrDefault(emptyList())

private fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "—"
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
