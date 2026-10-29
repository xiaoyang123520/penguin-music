package android.os

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** 桌面垫片：用调度线程池实现 CountDownTimer（睡眠定时用）。 */
abstract class CountDownTimer(
    private val millisInFuture: Long,
    private val countDownInterval: Long,
) {
    private var future: ScheduledFuture<*>? = null
    private var startAtMs: Long = 0L

    abstract fun onTick(millisUntilFinished: Long)
    abstract fun onFinish()

    @Synchronized
    fun start(): CountDownTimer {
        cancelInternal()
        if (millisInFuture <= 0) {
            onFinish()
            return this
        }
        startAtMs = System.currentTimeMillis()
        future = EXECUTOR.scheduleAtFixedRate({
            val remaining = millisInFuture - (System.currentTimeMillis() - startAtMs)
            if (remaining <= 0) {
                cancelInternal()
                onFinish()
            } else if (remaining <= countDownInterval) {
                // 最后一跳直接等结束，避免多打一次 tick
            } else {
                onTick(remaining)
            }
        }, countDownInterval, countDownInterval, TimeUnit.MILLISECONDS)
        return this
    }

    @Synchronized
    fun cancel() {
        cancelInternal()
    }

    private fun cancelInternal() {
        future?.cancel(false)
        future = null
    }

    companion object {
        private val EXECUTOR = Executors.newScheduledThreadPool(1) { r ->
            Thread(r, "CountDownTimer-shim").apply { isDaemon = true }
        }
    }
}
