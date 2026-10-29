package com.jussicodes.music.desktop

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import kotlin.concurrent.thread

/**
 * 单实例保护（2.0.5，A2）：此前完全没有——双开会同写
 * ~/.jussichords 的 preferences_pb、song_list.json、device-identity，
 * 可能损坏设置且两路同时出声。
 *
 * 机制：数据目录下 instance.lock 文件锁（进程退出/被杀后由 OS 释放，
 * 不残留假锁）+ 本地回环套接字（第二实例连上说一声「SHOW」就退出，
 * 第一实例据此把主窗口拉回前台）。两者都不依赖网络权限。
 */
object SingleInstance {

    private const val PORT = 47653

    // 必须持有引用：channel/lock 被 GC 回收会提前释放文件锁。
    private var lockChannel: java.nio.channels.FileChannel? = null
    private var fileLock: FileLock? = null
    private var serverSocket: ServerSocket? = null

    /** 已有实例收到「SHOW」时的回调（壳层注册：显示并前置主窗口）。 */
    @Volatile
    var onShowRequest: () -> Unit = {}

    /** 尝试成为唯一实例；已有实例在跑时通知它前置窗口并返回 false。 */
    fun tryAcquire(dataDir: File): Boolean {
        runCatching {
            dataDir.mkdirs()
            val channel = RandomAccessFile(File(dataDir, "instance.lock"), "rw").channel
            val lock = try {
                channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                channel.close()
                notifyExisting()
                return false
            }
            lockChannel = channel
            fileLock = lock
            startShowServer()
            return true
        }.onFailure {
            // 锁机制本身异常（罕见文件系统问题）不拦启动，宁可双开也不打不开。
            println("[single-instance] lock failed, continuing without: $it")
            return true
        }
        return true
    }

    private fun notifyExisting() {
        runCatching {
            Socket(InetAddress.getLoopbackAddress(), PORT).use { socket ->
                socket.getOutputStream().apply {
                    write("SHOW\n".toByteArray())
                    flush()
                }
            }
            println("[single-instance] another instance is running; asked it to show, exiting")
        }.onFailure {
            println("[single-instance] another instance is running (notify failed: $it), exiting")
        }
    }

    private fun startShowServer() {
        runCatching {
            val server = ServerSocket(PORT, 50, InetAddress.getLoopbackAddress())
            serverSocket = server
            thread(isDaemon = true, name = "single-instance-show") {
                while (!server.isClosed) {
                    runCatching {
                        server.accept().use { client ->
                            val line = client.getInputStream().bufferedReader().readLine()
                            if (line?.trim() == "SHOW") {
                                runCatching { onShowRequest() }
                            }
                        }
                    }
                }
            }
        }.onFailure {
            println("[single-instance] show server failed: $it")
        }
    }
}

/** 主窗口 AWT 引用持有者（迷你模式/单实例前置等窗口外组件用）。 */
object MainWindowHolder {
    @Volatile
    var window: java.awt.Window? = null
}
