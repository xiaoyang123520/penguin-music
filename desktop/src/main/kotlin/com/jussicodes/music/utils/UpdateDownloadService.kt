package com.jussicodes.music.utils

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

/**
 * Desktop update download (2.0.5 rewritten for A7).
 *
 * Before: fake "service" that immediately reported failure and sent the user
 * to the upstream GitHub APK page, while DesktopMain simultaneously toasted
 * contradictory guidance. Now: really downloads the zip from the self-hosted
 * version feed (see [AppUpdateManager.checkUpdate]) to ~/Downloads with
 * progress in [UpdateDownloadStateStore], then opens the containing folder
 * so the user can unzip-over-install. Progress/completion also surface as
 * toasts because the desktop UpdateDialog does not observe the store.
 */
object UpdateDownloadService {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(
        context: Context,
        updateInfo: UpdateInfo,
        sourceId: String = downloadSources.first().id,
    ) {
        UpdateDownloadStateStore.reset()
        Toast.makeText(
            context, "开始下载企鹅音乐 ${updateInfo.versionName}…", Toast.LENGTH_SHORT
        ).show()
        scope.launch {
            val result = AppUpdateManager.downloadApk(updateInfo, sourceId) { progress ->
                UpdateDownloadStateStore.downloading(updateInfo, progress)
            }
            result.onSuccess { file ->
                // downloadApk 已做完整性校验（A3-06），走到这里的必然是完整包。
                UpdateDownloadStateStore.completed(updateInfo, file.absolutePath)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "新版已下载并校验完整：请在打开的「下载」文件夹里解压 " +
                            "${file.name}，覆盖旧版文件后重新启动企鹅音乐完成更新",
                        Toast.LENGTH_LONG
                    ).show()
                }
                runCatching {
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().open(file.parentFile)
                    }
                }
            }.onFailure { e ->
                val message = e.message ?: "下载失败"
                UpdateDownloadStateStore.failed(updateInfo, message)
                withContext(Dispatchers.Main) {
                    // 失败（含 A3-06 校验不过）只提示不出重试按钮：Toast 无
                    // 交互能力，重试入口是设置页「检查更新」/ 启动时的更新
                    // 弹窗（半截文件已被 downloadApk 删除，重走即可重下）。
                    Toast.makeText(
                        context,
                        "更新下载失败：$message。可到「设置 → 关于 → 检查更新」重试",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
