package com.jussicodes.music.desktop.lyrics

import com.jussicodes.music.desktop.DesktopLog
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JWindow
import javax.swing.SwingUtilities

/**
 * 桌面歌词窗 · 纯 AWT 实现（2.0.3 替换 Compose Window 版）。
 *
 * 为什么换：Compose 透明窗在用户 Windows 真机上连续两版出问题（不出窗、
 * 锁定样式失效），且沙箱 Xvfb 照不出。本版用最原始的 JWindow + Graphics2D
 * 自绘：出窗路径只有 setVisible 一步，位置/尺寸全是物理像素直算（AWT 坐标
 * 即用户空间坐标，DPI 缩放由 JVM 统一处理，保存值与校验同口径）；锁定
 * 穿透在窗口 showing 后用 JNA 应用（runCatching 兜底，只降级不崩窗）；
 * 拖动用屏幕绝对坐标（与主窗标题栏同款，修相对坐标反馈振荡）。
 *
 * 可见反馈铁律：setVisible(true) 无论有没有队列/歌词都出窗——没歌词时
 * 显示歌名，还没有就显示占位「企鹅音乐 · 未播放」。全程日志走
 * DesktopLog.lyric（~/.jussichords/logs/lyric-window.log）。
 */
object DesktopLyricAwtWindow {

    private var window: JWindow? = null
    private var panel: LyricPanel? = null
    private var timer: javax.swing.Timer? = null
    private var appliedLock: Boolean? = null
    private var lastFontSize = -1f
    private var lastDoubleLine: Boolean? = null
    private var tickCount = 0

    /** 主题跟随色在 AWT 侧的近似（Compose 版用封面动态取色，AWT 无主题通道）。 */
    private val ACCENT = Color(0xFF35C48D.toInt())

    fun setVisible(visible: Boolean) {
        SwingUtilities.invokeLater {
            runCatching { if (visible) show() else hide() }
                .onFailure {
                    DesktopLog.lyric("awt setVisible($visible) FAILED: ${it.javaClass.simpleName}: ${it.message}")
                }
        }
    }

    private fun show() {
        val ctl = DesktopLyricController
        if (window == null) createWindow()
        val w = window ?: return
        val width = ctl.windowWidth.value.coerceIn(300, 2560)
        val height = computeHeight()
        w.setSize(width, height)
        val pos = visibleLyricPosition(ctl.windowX.value, ctl.windowY.value, width, height)
        if (pos != null) {
            w.setLocation(pos.first, pos.second)
        } else {
            val bounds = defaultScreenBounds()
            w.setLocation(
                bounds.x + (bounds.width - width) / 2,
                bounds.y + bounds.height - height - 48
            )
        }
        val scale = runCatching {
            w.graphicsConfiguration.defaultTransform.scaleX
        }.getOrNull()
        DesktopLog.lyric(
            "awt show saved=(${ctl.windowX.value},${ctl.windowY.value}) w=${ctl.windowWidth.value} " +
                "-> loc=${w.location} size=${w.size} dpiScale=$scale screens=${screenBounds().size}"
        )
        w.isVisible = true
        w.toFront()
        appliedLock = null
        applyLockIfNeeded()
        startTimer()
        panel?.repaint()
    }

    private fun hide() {
        window?.isVisible = false
        timer?.stop()
        DesktopLog.lyric("awt hide")
    }

    private fun createWindow() {
        val w = JWindow()
        w.background = Color(0, 0, 0, 0)
        w.setFocusableWindowState(false)
        runCatching { w.isAlwaysOnTop = true }
        val p = LyricPanel()
        w.contentPane.add(p)
        window = w
        panel = p
        DesktopLog.lyric("awt window created")
    }

    private fun computeHeight(): Int {
        val ctl = DesktopLyricController
        val fs = ctl.fontSize.value
        val lines = if (ctl.doubleLine.value) 2 else 1
        return (fs * 1.45f * lines + 44).toInt().coerceIn(64, 400)
    }

    private fun startTimer() {
        if (timer == null) {
            timer = javax.swing.Timer(120) {
                runCatching { onTick() }
            }
        }
        timer?.start()
    }

    private fun onTick() {
        val w = window ?: return
        if (!w.isShowing) return
        val ctl = DesktopLyricController
        // 字号/双行变化 → 跟着改尺寸（设置页滑杆实时生效）
        if (ctl.fontSize.value != lastFontSize || ctl.doubleLine.value != lastDoubleLine) {
            lastFontSize = ctl.fontSize.value
            lastDoubleLine = ctl.doubleLine.value
            val width = ctl.windowWidth.value.coerceIn(300, 2560)
            w.setSize(width, computeHeight())
        }
        applyLockIfNeeded()
        panel?.repaint()
        // 位置落盘轮询（与旧版同口径：约每 1.2s 对齐一次，拖动中不写）
        tickCount++
        if (tickCount % 10 == 0 && panel?.dragging != true) {
            runCatching {
                val loc = w.locationOnScreen
                if (loc.x != ctl.windowX.value || loc.y != ctl.windowY.value) {
                    ctl.saveWindowBounds(loc.x, loc.y, w.width)
                }
            }
        }
    }

    private fun applyLockIfNeeded() {
        val w = window ?: return
        val locked = DesktopLyricController.locked.value
        if (appliedLock == locked) return
        if (!w.isShowing) return
        applyLyricWindowLock(w, locked)
        appliedLock = locked
    }

    internal fun defaultScreenBounds(): Rectangle =
        runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice.defaultConfiguration.bounds
        }.getOrElse { Rectangle(0, 0, 1920, 1080) }

    internal fun screenBounds(): List<Rectangle> =
        runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment()
                .screenDevices.map { it.defaultConfiguration.bounds }
        }.getOrDefault(emptyList())

    /**
     * 保存位置的可见性校验（沿 2.0.2 口径）：窗口矩形与任一显示器交集
     * ≥120×60 才采用；哨兵 (-1,-1)/坏值/拔掉的外接屏坐标 → null（回落
     * 主屏底部居中）。AWT 坐标与 GraphicsConfiguration.bounds 同为用户
     * 空间坐标，DPI 缩放下不会把窗口算到屏幕外。
     */
    internal fun visibleLyricPosition(x: Int, y: Int, width: Int, height: Int): Pair<Int, Int>? {
        if (x == -1 && y == -1) return null
        val screens = screenBounds()
        if (screens.isEmpty()) return null
        val win = Rectangle(x, y, width, height)
        val visible = screens.any { s ->
            val inter = win.intersection(s)
            !inter.isEmpty && inter.width >= 120 && inter.height >= 60
        }
        return if (visible) x to y else null
    }

    internal fun playedAwtColor(): Color {
        val pref = DesktopLyricController.playedColor.value
        return if (pref == DesktopLyricController.COLOR_FOLLOW_ARTWORK) ACCENT
        else Color(pref.toInt(), true)
    }

    internal fun unplayedAwtColor(): Color =
        Color(DesktopLyricController.unplayedColor.value.toInt(), true)

    internal fun lyricFont(sizePx: Float): Font {
        val families = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        }.getOrDefault(emptySet())
        val name = when {
            "Microsoft YaHei" in families -> "Microsoft YaHei"
            "Noto Sans CJK SC" in families -> "Noto Sans CJK SC"
            else -> Font.SANS_SERIF
        }
        return Font(name, Font.BOLD, sizePx.toInt().coerceAtLeast(10))
    }
}

// ---------------------------------------------------------------------------
// 绘制与交互
// ---------------------------------------------------------------------------

private class LyricPanel : JComponent() {

    @Volatile var dragging = false
    private var hovered = false
    private var dragStartWin: Point? = null
    private var dragStartPtr: Point? = null
    private var buttonRects: List<Pair<Rectangle, String>> = emptyList()

    init {
        isOpaque = false
        val mouse = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovered = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = false
                repaint()
            }

            override fun mousePressed(e: MouseEvent) {
                if (DesktopLyricController.locked.value) return
                val hit = buttonRects.firstOrNull { it.first.contains(e.point) }?.second
                if (hit != null) {
                    onToolbarAction(hit)
                    repaint()
                    return
                }
                // 拖动：屏幕绝对坐标（与主窗同款修法，杜绝相对坐标振荡）
                dragging = true
                dragStartWin = windowAncestor()?.location
                dragStartPtr = MouseInfo.getPointerInfo()?.location
            }

            override fun mouseDragged(e: MouseEvent) {
                if (!dragging) return
                val sw = dragStartWin ?: return
                val sp = dragStartPtr ?: return
                val now = MouseInfo.getPointerInfo()?.location ?: return
                windowAncestor()?.setLocation(sw.x + (now.x - sp.x), sw.y + (now.y - sp.y))
            }

            override fun mouseReleased(e: MouseEvent) {
                if (dragging) {
                    dragging = false
                    runCatching {
                        val w = windowAncestor() ?: return@runCatching
                        if (w.isShowing) {
                            val loc = w.locationOnScreen
                            DesktopLyricController.saveWindowBounds(loc.x, loc.y, w.width)
                            DesktopLog.lyric("awt drag end loc=$loc size=${w.size}")
                        }
                    }
                }
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    private fun windowAncestor(): JWindow? =
        SwingUtilities.getWindowAncestor(this) as? JWindow

    private fun onToolbarAction(action: String) {
        val ctl = DesktopLyricController
        val ps = ctl.playerProvider()
        when (action) {
            "prev" -> ps?.player?.seekToPrevious()
            "playpause" -> {
                val p = ps ?: return
                if (p.isPlaying) p.player.pause() else p.player.play()
            }
            "next" -> ps?.player?.seekToNext()
            "font-" -> ctl.setFontSize((ctl.fontSize.value - 2f).coerceAtLeast(16f))
            "font+" -> ctl.setFontSize((ctl.fontSize.value + 2f).coerceAtMost(56f))
            "lock" -> ctl.setLocked(true)
            "close" -> ctl.setEnabled(false)
        }
    }

    override fun paintComponent(g0: Graphics) {
        super.paintComponent(g0)
        val g = g0.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            )
            val ctl = DesktopLyricController
            val w = width
            // 半透明圆角底（透明度跟设置）
            val alpha = (0x7A * ctl.opacity.value).toInt().coerceIn(10, 200)
            g.color = Color(0, 0, 0, alpha)
            g.fillRoundRect(6, 14, w - 12, height - 20, 22, 22)
            g.color = Color(255, 255, 255, 26)
            g.drawRoundRect(6, 14, w - 12, height - 20, 22, 22)

            val entries = ctl.entries.value
            val idx = ctl.currentIndex.value
            val current = entries.getOrNull(idx)
            val next = entries.getOrNull(idx + 1)
            val played = DesktopLyricAwtWindow.playedAwtColor()
            val unplayed = DesktopLyricAwtWindow.unplayedAwtColor()
            val fs = ctl.fontSize.value
            val baseY = 14 + fs * 1.10f

            if (current == null) {
                // 占位：有歌显示歌名（或「暂无歌词」），没歌显示未播放文案
                val ps = ctl.playerProvider()
                val title = ps?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() }
                val text = when {
                    title != null && ps?.currentMediaItem != null -> title
                    title != null -> title
                    else -> "企鹅音乐 · 未播放"
                }
                g.font = DesktopLyricAwtWindow.lyricFont(fs * 0.8f)
                drawCentered(g, text, w, baseY, unplayed)
            } else {
                val lineEnd = next?.time
                    ?: (ctl.playerProvider()?.player?.duration?.takeIf { it > 0 }
                        ?: (current.time + 4000))
                val segments = sweepSegments(
                    current, ctl.positionMs.value, lineEnd, ctl.wordByWord.value, played, unplayed
                )
                g.font = DesktopLyricAwtWindow.lyricFont(fs)
                drawSegmentsCentered(g, segments, w, baseY)
                if (ctl.doubleLine.value) {
                    // 2.2.0（A5-14）：双行第二行优先画当前行译文——
                    // 此前桌面歌词完全不显示译文（LyricEntry.translation
                    // 已有数据、绘制端从不读）。无译文时回落下一行
                    // 预告（旧行为不变）。
                    val translation = current.translation?.takeIf { it.isNotBlank() }
                    val secondLine = translation ?: next?.text
                    if (secondLine != null) {
                        g.font = DesktopLyricAwtWindow.lyricFont(fs * 0.62f)
                        drawCentered(
                            g, secondLine, w, baseY + fs * 1.45f,
                            Color(unplayed.red, unplayed.green, unplayed.blue, 190)
                        )
                    }
                }
            }

            // hover 工具条（锁定后不画、也不响应）
            buttonRects = if (hovered && !ctl.locked.value) {
                drawToolbar(g, w)
            } else {
                emptyList()
            }
        } finally {
            g.dispose()
        }
    }

    /** 文本宽于可用宽度时按比例缩字号（长歌词行不许溢出窗口两侧）。 */
    private fun fitFont(g: Graphics2D, textWidth: Int, avail: Int) {
        if (textWidth <= avail || textWidth <= 0) return
        val scale = avail.toFloat() / textWidth.toFloat()
        val cur = g.font
        g.font = cur.deriveFont((cur.size2D * scale).coerceAtLeast(10f))
    }

    private fun drawCentered(g: Graphics2D, text: String, width: Int, baselineY: Float, color: Color) {
        fitFont(g, g.fontMetrics.stringWidth(text), width - 36)
        val fm = g.fontMetrics
        val x = (width - fm.stringWidth(text)) / 2f
        g.color = Color(0, 0, 0, 140)
        g.drawString(text, x + 1f, baselineY + 2f)
        g.color = color
        g.drawString(text, x, baselineY)
    }

    private fun drawSegmentsCentered(
        g: Graphics2D, segments: List<Pair<String, Color>>, width: Int, baselineY: Float
    ) {
        fitFont(g, segments.sumOf { g.fontMetrics.stringWidth(it.first) }, width - 36)
        val fm = g.fontMetrics
        val total = segments.sumOf { fm.stringWidth(it.first) }
        var x = (width - total) / 2f
        // 先整行打一遍阴影，再按段上色
        var sx = x
        g.color = Color(0, 0, 0, 140)
        for ((text, _) in segments) {
            g.drawString(text, sx + 1f, baselineY + 2f)
            sx += fm.stringWidth(text)
        }
        for ((text, color) in segments) {
            g.color = color
            g.drawString(text, x, baselineY)
            x += fm.stringWidth(text)
        }
    }

    /** 工具条：胶囊底 + 7 个按钮（形状绘制，不依赖 emoji 字体）。返回命中表。 */
    private fun drawToolbar(g: Graphics2D, width: Int): List<Pair<Rectangle, String>> {
        val actions = listOf("prev", "playpause", "next", "font-", "font+", "lock", "close")
        val btn = 28
        val gap = 2
        val pillW = actions.size * btn + (actions.size - 1) * gap + 12
        val pillX = (width - pillW) / 2
        val pillY = 0
        val pillH = 26
        g.color = Color(0x1A, 0x1A, 0x22, 0xE6)
        g.fillRoundRect(pillX, pillY, pillW, pillH, 16, 16)
        g.color = Color(255, 255, 255, 36)
        g.drawRoundRect(pillX, pillY, pillW, pillH, 16, 16)
        val isPlaying = DesktopLyricController.playerProvider()?.isPlaying == true
        val fg = Color(0xFFE8E8F2.toInt())
        val rects = mutableListOf<Pair<Rectangle, String>>()
        actions.forEachIndexed { i, action ->
            val bx = pillX + 6 + i * (btn + gap)
            val rect = Rectangle(bx, pillY, btn, pillH)
            rects += rect to action
            val cx = bx + btn / 2
            val cy = pillY + pillH / 2
            g.color = fg
            when (action) {
                "prev" -> {
                    g.fillRect(cx - 8, cy - 6, 2, 12)
                    g.fillPolygon(
                        intArrayOf(cx + 7, cx - 4, cx + 7),
                        intArrayOf(cy - 6, cy, cy + 6), 3
                    )
                }
                "next" -> {
                    g.fillRect(cx + 6, cy - 6, 2, 12)
                    g.fillPolygon(
                        intArrayOf(cx - 7, cx + 4, cx - 7),
                        intArrayOf(cy - 6, cy, cy + 6), 3
                    )
                }
                "playpause" -> {
                    if (isPlaying) {
                        g.fillRect(cx - 6, cy - 6, 4, 12)
                        g.fillRect(cx + 2, cy - 6, 4, 12)
                    } else {
                        g.fillPolygon(
                            intArrayOf(cx - 5, cx + 7, cx - 5),
                            intArrayOf(cy - 7, cy, cy + 7), 3
                        )
                    }
                }
                "font-" -> {
                    g.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
                    g.drawString("A-", cx - 8, cy + 4)
                }
                "font+" -> {
                    g.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
                    g.drawString("A+", cx - 8, cy + 4)
                }
                "lock" -> {
                    g.stroke = BasicStroke(1.6f)
                    g.drawRoundRect(cx - 5, cy - 1, 10, 8, 2, 2)
                    g.drawArc(cx - 4, cy - 8, 8, 8, 0, 180)
                }
                "close" -> {
                    g.stroke = BasicStroke(1.8f)
                    g.drawLine(cx - 5, cy - 5, cx + 5, cy + 5)
                    g.drawLine(cx + 5, cy - 5, cx - 5, cy + 5)
                }
            }
        }
        return rects
    }

    /** 当前行扫光分段（与 Compose 版 buildSweptText 同逻辑，AWT 颜色版）。 */
    private fun sweepSegments(
        entry: LyricEntry,
        position: Long,
        lineEnd: Long,
        wordByWord: Boolean,
        played: Color,
        unplayed: Color
    ): List<Pair<String, Color>> {
        val out = mutableListOf<Pair<String, Color>>()
        val words = entry.words
        if (wordByWord && !words.isNullOrEmpty()) {
            for (word in words) {
                val wordEnd = word.start + word.dur
                when {
                    position >= wordEnd -> out += word.text to played
                    position <= word.start -> out += word.text to unplayed
                    else -> {
                        val frac = if (word.dur > 0) {
                            ((position - word.start).toFloat() / word.dur).coerceIn(0f, 1f)
                        } else 0f
                        val cut = (word.text.length * frac).toInt().coerceIn(0, word.text.length)
                        if (cut > 0) out += word.text.substring(0, cut) to played
                        if (cut < word.text.length) out += word.text.substring(cut) to unplayed
                    }
                }
            }
        } else {
            val total = (lineEnd - entry.time).coerceAtLeast(1)
            val frac = ((position - entry.time).toFloat() / total).coerceIn(0f, 1f)
            val cut = (entry.text.length * frac).toInt().coerceIn(0, entry.text.length)
            if (cut > 0) out += entry.text.substring(0, cut) to played
            if (cut < entry.text.length) out += entry.text.substring(cut) to unplayed
        }
        return out
    }
}

// ---------------------------------------------------------------------------
// Windows 点击穿透（JNA/Win32）——从旧 Compose 版原样迁入：必须在窗口
// showing 后应用；失败只降级（歌词照常显示、不穿透）并写日志。
// ---------------------------------------------------------------------------

private interface User32Lock : Library {
    fun GetWindowLongA(hWnd: Pointer, nIndex: Int): Int
    fun SetWindowLongA(hWnd: Pointer, nIndex: Int, dwNewLong: Int): Int

    companion object {
        val INSTANCE: User32Lock? by lazy {
            runCatching { Native.load("user32", User32Lock::class.java) }.getOrNull()
        }
    }
}

private const val GWL_EXSTYLE = -20
private const val WS_EX_TRANSPARENT = 0x00000020
private const val WS_EX_LAYERED = 0x00080000
private const val WS_EX_NOACTIVATE = 0x08000000
private const val WS_EX_TOOLWINDOW = 0x00000080

internal fun applyLyricWindowLock(window: java.awt.Window, locked: Boolean) {
    runCatching {
        window.focusableWindowState = false
        val user32 = User32Lock.INSTANCE
        if (user32 != null && System.getProperty("os.name").startsWith("Windows")) {
            val hwnd = Native.getWindowPointer(window)
            val current = user32.GetWindowLongA(hwnd, GWL_EXSTYLE)
            val target = if (locked) {
                current or WS_EX_TRANSPARENT or WS_EX_LAYERED or WS_EX_NOACTIVATE or WS_EX_TOOLWINDOW
            } else {
                (current and WS_EX_TRANSPARENT.inv()) or WS_EX_NOACTIVATE or WS_EX_TOOLWINDOW
            }
            user32.SetWindowLongA(hwnd, GWL_EXSTYLE, target)
        }
        DesktopLog.lyric(
            "awt lock style applied locked=$locked os=${System.getProperty("os.name")}"
        )
    }.onFailure { e ->
        DesktopLog.lyric(
            "awt lock style FAILED locked=$locked: ${e.javaClass.simpleName}: ${e.message}"
        )
    }
}
