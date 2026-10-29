package com.jussicodes.music.desktop

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 桌面端诊断日志（2.0.2 新增）：歌词窗这类「沙箱测不出、真机才现形」的
 * 问题必须留痕。写到 <dataDir>/logs/lyric-window.log（dataDir 由
 * --data-dir 或默认 ~/.jussichords 决定），同时打一份到 stdout 供
 * Xvfb 验收脚本抓取。写日志失败绝不影响主流程。
 *
 * 2.0.5（A9）：全部日志加大小轮转（单文件 1MB 上限，滚动保留一份 .1
 * 备份），此前只 appendText 无上限、长期运行会无限长；新增 crash.log
 * 承接全局未捕获异常（此前只打 stderr，Windows GUI 闪退零记录）。
 */
object DesktopLog {

    private val lock = Any()
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private const val MAX_BYTES = 1024L * 1024L

    private fun logsDir(): File {
        val dataDir = System.getProperty("jussichords.dataDir")
            ?: (System.getProperty("user.home") + File.separator + ".jussichords")
        return File(dataDir, "logs")
    }

    private fun append(fileName: String, line: String) {
        runCatching {
            synchronized(lock) {
                val dir = logsDir().apply { mkdirs() }
                val file = File(dir, fileName)
                if (file.exists() && file.length() > MAX_BYTES) {
                    val backup = File(dir, "$fileName.1")
                    runCatching { backup.delete() }
                    runCatching { file.renameTo(backup) }
                }
                file.appendText(line + "\n")
            }
        }
    }

    fun lyric(message: String) {
        val line = "[${format.format(Date())}] $message"
        println("[LYRIC] $message")
        append("lyric-window.log", line)
    }

    /** 登录诊断日志（2.0.3）：写 logs/login.log，内容已由 AuthDiagnostics 脱敏。 */
    fun login(message: String) {
        val line = "[${format.format(Date())}] $message"
        println("[LOGIN] $message")
        append("login.log", line)
    }

    /**
     * 暂停诊断日志（2.2.0，A5-10）：承接 PauseDiagnostics 的整行记录
     * （时间戳由调用方格式化），走与其它日志相同的 1MB 轮转——旧实现
     * 在 PauseDiagnostics 里直接 File.appendText 无上限。
     */
    fun pauses(line: String) {
        append("pauses.log", line)
    }

    /** LX 引擎日志（2.1.0）：脚本错误/解析轨迹落 logs/lx.log。 */
    fun lx(message: String) {
        val line = "[${format.format(Date())}] $message"
        println("[LX] $message")
        append("lx.log", line)
    }

    /** 崩溃留痕（2.0.5）：未捕获异常落 logs/crash.log（含线程名与堆栈）。 */
    fun crash(threadName: String, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val line = "[${format.format(Date())}] uncaught in thread=$threadName\n$sw"
        println("[CRASH] $threadName: $error")
        append("crash.log", line)
    }
}
