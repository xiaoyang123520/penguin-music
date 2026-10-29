package com.google.common.util.concurrent

import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Minimal Guava ListenableFuture (desktop only needs immediate futures). */
interface ListenableFuture<V> : Future<V> {
    fun addListener(listener: Runnable, executor: Executor)
}

class ImmediateFuture<V>(private val value: V) : ListenableFuture<V> {
    override fun addListener(listener: Runnable, executor: Executor) {
        executor.execute(listener)
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
    override fun isCancelled(): Boolean = false
    override fun isDone(): Boolean = true
    override fun get(): V = value
    override fun get(timeout: Long, unit: TimeUnit): V = value
}

object Futures {
    @JvmStatic
    fun <V> immediateFuture(value: V): ListenableFuture<V> = ImmediateFuture(value)

    @JvmStatic
    fun <V> immediateVoidFuture(): ListenableFuture<V?> = ImmediateFuture(null)
}
