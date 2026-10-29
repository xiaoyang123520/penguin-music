package android.app

import android.content.ContextWrapper
import com.jussicodes.music.desktop.DesktopContextHolder

/** Minimal desktop Activity stub (only for type checks / casts in app code). */
open class Activity : ContextWrapper(DesktopContextHolder.context) {
    val window: android.view.Window = android.view.Window()

    var isFinishing: Boolean = false
        protected set
    var isDestroyed: Boolean = false
        protected set

    fun finish() {
        isFinishing = true
        isDestroyed = true
    }

    fun runOnUiThread(action: Runnable) {
        if (java.awt.EventQueue.isDispatchThread()) action.run()
        else java.awt.EventQueue.invokeLater(action)
    }
}
