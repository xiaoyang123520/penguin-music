package com.jussicodes.music.desktop

import android.content.ClipboardManager
import android.content.Context

/** Process-wide desktop Context singleton (the app's "application context"). */
object DesktopContextHolder {
    val context: Context by lazy { DesktopContext() }

    private class DesktopContext : Context() {
        private val clipboard by lazy { ClipboardManager() }

        override val applicationContext: Context
            get() = this

        override fun getSystemService(name: String): Any? = when (name) {
            CLIPBOARD_SERVICE -> clipboard
            else -> null
        }
    }
}
