package com.jussicodes.music.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import kotlin.concurrent.thread

/**
 * 全局快捷键（2.0.5，C3）+ 全局媒体键（2.3.0，F6）：Windows RegisterHotKey
 * （JNA 直调 user32，不依赖 jna-platform）。此前只有窗口聚焦时才有效的
 * 媒体键/应用内键，切到别的窗口就全灭。
 *
 * 组合（Ctrl+Alt，避免与常用软件冲突；带 MOD_NOREPEAT 防长按连发）：
 * 空格=播放/暂停、→/←=下一首/上一首、↑/↓=音量±、L=桌面歌词开关。
 * 组合键受 DesktopPrefs.globalHotkeys 开关管控（SettingsScreen 切开关
 * 时 start()/stop() 只动组合键一侧）。
 *
 * 媒体键（F6，无修饰键、至多带 MOD_NOREPEAT；只做四个传送键，不含
 * 音量媒体键 0xAD–0xAF）：
 * 0xB0=下一首、0xB1=上一首、0xB2=停止（=暂停，语义与 AWT 段一致）、
 * 0xB3=播放/暂停。随应用启动默认开启（startMediaKeys()，与
 * globalHotkeys 开关互不绑定），与组合键共用同一消息泵线程。
 * 注册失败（如键已被别的程序占用）只打日志不崩，该键的 handler
 * 不会收到；AWT 段此时仍兜底（见 isMediaKeyActive）。
 *
 * 防双触发（F6 硬要求）：全局路径与 AWT 媒体键路径只走其一——AWT 段
 * 在按键时查 isMediaKeyActive(vk)，全局已注册成功的键码 AWT 跳过；
 * 全局未注册的键码 AWT 正常处理。同一键码不会两处各触发一次。
 *
 * 机制选择说明（F6）：首选 RegisterHotKey 路线成立——user32 的
 * RegisterHotKey 接受无修饰键的虚拟键码（含媒体键 VK_MEDIA_*，其值
 * 本就是标准 VK），且与既有组合键共用消息泵、无需另起钩子；
 * WH_KEYBOARD_LL 低级钩子未启用（钩子会全局截获键盘事件、需更重的
 * 注入与清理语义，收益为零）。两条路线不同时启用。
 * 非 Windows 平台直接跳过（开发沙箱是 Linux，真机行为待实测）。
 */
object DesktopHotkeys {

    enum class Action { PLAY_PAUSE, NEXT, PREV, VOLUME_UP, VOLUME_DOWN, TOGGLE_LYRIC, STOP }

    @Volatile
    var handler: (Action) -> Unit = {}

    /**
     * 全局媒体键 handler。未设置时回落到 [handler]。DesktopMain 在
     * playerState 就绪后单独设置它，使媒体键暂停能注记
     * PauseDiagnostics.MEDIA_KEY（与 AWT 媒体键段同语义），而不让
     * Ctrl+Alt+空格的暂停也被误记为媒体键。
     */
    @Volatile
    var mediaHandler: ((Action) -> Unit)? = null

    @Volatile
    var registered: Boolean = false
        private set

    /** 任一全局媒体键注册成功即 true（供 AWT 段粗查；逐键查用 [isMediaKeyActive]）。 */
    @Volatile
    var mediaKeysRegistered: Boolean = false
        private set

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    @Volatile
    private var running = false
    @Volatile
    private var comboWanted = false
    @Volatile
    private var mediaWanted = false
    private var pumpThread: Thread? = null
    private var shutdownHookRegistered = false

    private const val MOD_ALT = 0x0001
    private const val MOD_CONTROL = 0x0002
    private const val MOD_NOREPEAT = 0x4000
    private const val WM_HOTKEY = 0x0312
    private const val PM_REMOVE = 0x0001

    // VK_MEDIA_NEXT_TRACK / PREV_TRACK / STOP / PLAY_PAUSE。
    const val VK_MEDIA_NEXT = 0xB0
    const val VK_MEDIA_PREV = 0xB1
    const val VK_MEDIA_STOP = 0xB2
    const val VK_MEDIA_PLAY_PAUSE = 0xB3

    /** 纯函数：媒体键码 → 动作；非媒体键码返回 null。探针可直接单测。 */
    fun mediaActionForVk(vk: Int): Action? = when (vk) {
        VK_MEDIA_NEXT -> Action.NEXT
        VK_MEDIA_PREV -> Action.PREV
        VK_MEDIA_STOP -> Action.STOP
        VK_MEDIA_PLAY_PAUSE -> Action.PLAY_PAUSE
        else -> null
    }

    fun isMediaKey(vk: Int): Boolean = mediaActionForVk(vk) != null

    // 当前全局已注册成功的媒体键码集合（仅泵线程写，AWT 线程读）。
    private val activeMediaVks = mutableSetOf<Int>()

    /** 逐键防双触发查询：该媒体键码当前是否已由全局路径注册成功。 */
    fun isMediaKeyActive(vk: Int): Boolean =
        synchronized(activeMediaVks) { vk in activeMediaVks }

    private data class Binding(val id: Int, val modifiers: Int, val vk: Int, val action: Action, val isMedia: Boolean)

    private val comboBindings: List<Binding> = listOf(
        Binding(1, MOD_CONTROL or MOD_ALT or MOD_NOREPEAT, 0x20, Action.PLAY_PAUSE, false),
        Binding(2, MOD_CONTROL or MOD_ALT or MOD_NOREPEAT, 0x27, Action.NEXT, false),
        Binding(3, MOD_CONTROL or MOD_ALT or MOD_NOREPEAT, 0x25, Action.PREV, false),
        Binding(4, MOD_CONTROL or MOD_ALT or MOD_NOREPEAT, 0x26, Action.VOLUME_UP, false),
        Binding(5, MOD_CONTROL or MOD_ALT or MOD_NOREPEAT, 0x28, Action.VOLUME_DOWN, false),
        Binding(6, MOD_CONTROL or MOD_ALT or MOD_NOREPEAT, 0x4C, Action.TOGGLE_LYRIC, false),
    )

    private val mediaBindings: List<Binding> = listOf(
        Binding(11, MOD_NOREPEAT, VK_MEDIA_NEXT, Action.NEXT, true),
        Binding(12, MOD_NOREPEAT, VK_MEDIA_PREV, Action.PREV, true),
        Binding(13, MOD_NOREPEAT, VK_MEDIA_STOP, Action.STOP, true),
        Binding(14, MOD_NOREPEAT, VK_MEDIA_PLAY_PAUSE, Action.PLAY_PAUSE, true),
    )

    private val allBindings: List<Binding> = comboBindings + mediaBindings

    /** 组合键侧：受 globalHotkeys 开关管控。 */
    fun start() {
        if (!isWindows) {
            println("[hotkeys] not Windows, global hotkeys skipped")
            return
        }
        comboWanted = true
        ensurePump()
    }

    /** 组合键侧：只撤组合键；媒体键仍在要时泵线程继续跑。 */
    fun stop() {
        comboWanted = false
        if (!mediaWanted) shutdownPump()
    }

    /** 媒体键侧（F6）：随启动默认开启，与 globalHotkeys 开关互不绑定。 */
    fun startMediaKeys() {
        if (!isWindows) {
            println("[hotkeys] not Windows, global media keys skipped")
            return
        }
        mediaWanted = true
        ensurePump()
    }

    /** 媒体键侧：只撤媒体键；组合键仍在要时泵线程继续跑。 */
    fun stopMediaKeys() {
        mediaWanted = false
        if (!comboWanted) shutdownPump()
    }

    private fun shutdownPump() {
        running = false
        pumpThread = null
    }

    private fun ensurePump() {
        if (running) return
        running = true
        if (!shutdownHookRegistered) {
            shutdownHookRegistered = true
            runCatching {
                Runtime.getRuntime().addShutdownHook(thread(start = false, name = "global-hotkeys-shutdown") {
                    // 退出时尽力注销：守护线程在 JVM 退出时可能来不及跑
                    // finally，钩子是第二道清理（钩子路线未启用故无 Unhook）。
                    runCatching {
                        val user32 = User32.INSTANCE
                        for (b in allBindings) user32.UnregisterHotKey(null, b.id)
                    }
                })
            }
        }
        // RegisterHotKey 与消息队列都归属调用线程：在专用线程注册并泵消息。
        pumpThread = thread(isDaemon = true, name = "global-hotkeys") {
            val user32 = User32.INSTANCE
            // 仅泵线程维护的已注册 id 集合；开关切换时 sync 一步增量调整。
            val registeredIds = mutableSetOf<Int>()
            fun syncRegistrations() {
                for (b in allBindings) {
                    val wanted = if (b.isMedia) mediaWanted else comboWanted
                    if (wanted && b.id !in registeredIds) {
                        val ok = runCatching {
                            user32.RegisterHotKey(null, b.id, b.modifiers, b.vk)
                        }.getOrDefault(false)
                        if (ok) {
                            registeredIds += b.id
                            if (b.isMedia) {
                                synchronized(activeMediaVks) { activeMediaVks += b.vk }
                            }
                            println("[hotkeys] RegisterHotKey id=${b.id} vk=${b.vk} ok")
                        } else {
                            println("[hotkeys] RegisterHotKey id=${b.id} vk=${b.vk} failed (occupied?)")
                        }
                    } else if (!wanted && b.id in registeredIds) {
                        runCatching { user32.UnregisterHotKey(null, b.id) }
                        registeredIds -= b.id
                        if (b.isMedia) {
                            synchronized(activeMediaVks) { activeMediaVks -= b.vk }
                        }
                    }
                }
                registered = registeredIds.any { id -> comboBindings.any { it.id == id } }
                mediaKeysRegistered = registeredIds.any { id -> mediaBindings.any { it.id == id } }
            }
            val msg = WinMsg()
            try {
                while (running) {
                    syncRegistrations()
                    var got = false
                    runCatching {
                        if (user32.PeekMessage(msg, null, 0, 0, PM_REMOVE)) {
                            got = true
                        }
                    }
                    if (got && msg.message == WM_HOTKEY) {
                        val binding = allBindings.firstOrNull { it.id == msg.wParam.toInt() }
                        if (binding != null) {
                            val dispatch = if (binding.isMedia) (mediaHandler ?: handler) else handler
                            runCatching { dispatch(binding.action) }
                        }
                    }
                    if (!got) Thread.sleep(25)
                }
            } finally {
                for (id in registeredIds.toList()) {
                    runCatching { user32.UnregisterHotKey(null, id) }
                }
                registeredIds.clear()
                synchronized(activeMediaVks) { activeMediaVks.clear() }
                registered = false
                mediaKeysRegistered = false
            }
        }
    }

    internal class WinMsg : Structure() {
        @JvmField var hwnd: Pointer? = null
        @JvmField var message: Int = 0
        @JvmField var wParam: Long = 0
        @JvmField var lParam: Long = 0
        @JvmField var time: Int = 0
        @JvmField var ptX: Int = 0
        @JvmField var ptY: Int = 0

        override fun getFieldOrder(): List<String> =
            listOf("hwnd", "message", "wParam", "lParam", "time", "ptX", "ptY")
    }

    internal interface User32 : Library {
        fun RegisterHotKey(hWnd: Pointer?, id: Int, fsModifiers: Int, vk: Int): Boolean
        fun UnregisterHotKey(hWnd: Pointer?, id: Int): Boolean
        fun PeekMessage(lpMsg: WinMsg, hWnd: Pointer?, min: Int, max: Int, remove: Int): Boolean

        companion object {
            val INSTANCE: User32 = Native.load("user32", User32::class.java)
        }
    }
}
