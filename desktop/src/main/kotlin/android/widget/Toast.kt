package android.widget

import android.content.Context

/** 桌面 Toast 汇聚点：main() 把它接到 Compose Snackbar；默认打印到 stdout。 */
object ToastSink {
    @Volatile
    var handler: (String) -> Unit = { println("[Toast] $it") }
}

class Toast private constructor(private val text: String) {

    fun show() {
        ToastSink.handler(text)
    }

    fun cancel() {}

    companion object {
        const val LENGTH_SHORT: Int = 0
        const val LENGTH_LONG: Int = 1

        @JvmStatic
        fun makeText(context: Context, text: CharSequence?, duration: Int): Toast =
            Toast(text?.toString() ?: "")

        @JvmStatic
        fun makeText(context: Context, resId: Int, duration: Int): Toast =
            Toast(context.getString(resId))
    }
}
