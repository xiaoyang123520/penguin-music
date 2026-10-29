package android.util

/**
 * 桌面垫片：android.util.Log → stderr 输出。
 * 原型/桌面版统一走 [logSink]（默认 System.err），保持调用方源码不变。
 */
object Log {
    @Volatile
    var logSink: (String) -> Unit = { System.err.println(it) }

    private fun emit(level: String, tag: String?, msg: String?, tr: Throwable?): Int {
        logSink("[$level] ${tag ?: ""}: ${msg ?: ""}${tr?.let { " (${it})" } ?: ""}")
        return 0
    }

    @JvmStatic fun v(tag: String?, msg: String?): Int = emit("V", tag, msg, null)
    @JvmStatic fun v(tag: String?, msg: String?, tr: Throwable?): Int = emit("V", tag, msg, tr)
    @JvmStatic fun d(tag: String?, msg: String?): Int = emit("D", tag, msg, null)
    @JvmStatic fun d(tag: String?, msg: String?, tr: Throwable?): Int = emit("D", tag, msg, tr)
    @JvmStatic fun i(tag: String?, msg: String?): Int = emit("I", tag, msg, null)
    @JvmStatic fun i(tag: String?, msg: String?, tr: Throwable?): Int = emit("I", tag, msg, tr)
    @JvmStatic fun w(tag: String?, msg: String?): Int = emit("W", tag, msg, null)
    @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?): Int = emit("W", tag, msg, tr)
    @JvmStatic fun e(tag: String?, msg: String?): Int = emit("E", tag, msg, null)
    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int = emit("E", tag, msg, tr)

    @JvmStatic
    fun getStackTraceString(tr: Throwable?): String =
        tr?.stackTraceToString() ?: ""
}
