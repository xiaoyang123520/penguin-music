package com.jussicodes.music.desktop

import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.JLabel
import javax.swing.JWindow
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder

/** Tiny AWT toast: a borderless, always-on-top label that fades after ~2.2s. */
object DesktopToast {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "desktop-toast").apply { isDaemon = true }
    }

    @Volatile
    private var current: JWindow? = null

    fun show(message: String) {
        if (GraphicsEnvironment.isHeadless()) return
        SwingUtilities.invokeLater {
            runCatching {
                current?.dispose()
                val window = JWindow(null as Window?)
                val label = JLabel(message, SwingConstants.CENTER).apply {
                    font = Font("SansSerif", Font.PLAIN, 13)
                    foreground = Color.WHITE
                    border = EmptyBorder(8, 16, 8, 16)
                    isOpaque = true
                    background = Color(40, 40, 40, 235)
                }
                window.add(label)
                window.isAlwaysOnTop = true
                window.pack()
                val screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .defaultScreenDevice.defaultConfiguration.bounds
                window.setLocation(
                    screen.x + (screen.width - window.width) / 2,
                    screen.y + screen.height - window.height - 64
                )
                window.isVisible = true
                current = window
                scheduler.schedule({
                    SwingUtilities.invokeLater {
                        window.dispose()
                        if (current === window) current = null
                    }
                }, 2200, TimeUnit.MILLISECONDS)
            }
        }
    }
}
