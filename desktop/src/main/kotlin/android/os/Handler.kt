package android.os

import java.util.concurrent.Executors

/** Desktop Looper: a single "main" executor backed by a daemon thread. */
class Looper private constructor() {
    companion object {
        private val MAIN = Looper()
        fun getMainLooper(): Looper = MAIN
        fun myLooper(): Looper? = MAIN
    }

    internal val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "desktop-main-looper").apply { isDaemon = true }
    }
}

/** Desktop Handler: posts onto the shared main-looper executor. */
open class Handler(private val looper: Looper = Looper.getMainLooper()) {
    constructor() : this(Looper.getMainLooper())

    fun post(r: Runnable): Boolean {
        looper.executor.execute(r)
        return true
    }

    fun postDelayed(r: Runnable, delayMillis: Long): Boolean {
        looper.executor.execute {
            try { Thread.sleep(delayMillis) } catch (_: InterruptedException) { }
            r.run()
        }
        return true
    }

    fun removeCallbacks(r: Runnable) {}
    fun removeCallbacksAndMessages(token: Any?) {}
}
