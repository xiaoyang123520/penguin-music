package com.jussicodes.music.desktop

import java.io.File

/**
 * 开机自启（2.0.5，C5）：Windows 启动文件夹快捷方式路线（免注册表、
 * 用户在资源管理器里可见可删）。开启时经 PowerShell 的 WScript.Shell
 * 在 %APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup 下生成
 * PenguinMusic.lnk（目标 = 安装目录里的 PenguinMusic.exe，工作目录 =
 * 安装目录）；关闭即删 .lnk。
 *
 * 安装目录按进程 user.dir 推断（launch4j 配了 chdir=.，即 exe 所在目录）；
 * 找不到 exe 时不写坏快捷方式、只打日志。非 Windows 直接跳过。
 */
object AutoStart {

    private const val LINK_NAME = "PenguinMusic.lnk"

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private fun startupDir(): File? {
        val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: return null
        return File(appData, "Microsoft\\Windows\\Start Menu\\Programs\\Startup")
    }

    private fun linkFile(): File? = startupDir()?.let { File(it, LINK_NAME) }

    fun isEnabled(): Boolean = isWindows && (linkFile()?.exists() == true)

    fun setEnabled(on: Boolean): Boolean {
        if (!isWindows) {
            println("[autostart] not Windows, skipped")
            return false
        }
        val link = linkFile() ?: return false
        if (!on) {
            val deleted = !link.exists() || link.delete()
            println("[autostart] disabled, link deleted=$deleted")
            return deleted
        }
        val exe = File(System.getProperty("user.dir"), "PenguinMusic.exe")
        if (!exe.exists()) {
            println("[autostart] PenguinMusic.exe not found under user.dir=${exe.parent}, skipped")
            return false
        }
        link.parentFile?.mkdirs()
        val ps = """
            ${'$'}s = (New-Object -ComObject WScript.Shell).CreateShortcut('${link.absolutePath.replace("'", "''")}')
            ${'$'}s.TargetPath = '${exe.absolutePath.replace("'", "''")}'
            ${'$'}s.WorkingDirectory = '${exe.parent.replace("'", "''")}'
            ${'$'}s.Description = '企鹅音乐'
            ${'$'}s.Save()
        """.trimIndent()
        return runCatching {
            val proc = ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", ps
            ).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            println("[autostart] powershell exit=$code out=${out.take(200)}")
            code == 0 && link.exists()
        }.getOrElse {
            println("[autostart] failed: $it")
            false
        }
    }
}
