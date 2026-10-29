package android.view

/** Minimal desktop View: only the members the app touches. */
open class View {
    var keepScreenOn: Boolean = false
    var isHapticFeedbackEnabled: Boolean = true

    val context: android.content.Context
        get() = com.jussicodes.music.desktop.DesktopContextHolder.context

    val isInEditMode: Boolean
        get() = false

    fun performHapticFeedback(feedbackConstant: Int): Boolean = true
    fun performHapticFeedback(feedbackConstant: Int, flags: Int): Boolean = true
}

/** Minimal desktop Window (only carried through WindowCompat shims). */
class Window {
    var decorView: View = View()
}

object HapticFeedbackConstants {
    const val KEYBOARD_TAP = 3
    const val VIRTUAL_KEY = 1
    const val LONG_PRESS = 0
    const val CLOCK_TICK = 4
    const val CONTEXT_CLICK = 6
    const val KEYBOARD_PRESS = 27
    const val KEYBOARD_RELEASE = 28
    const val VIRTUAL_KEY_RELEASE = 29
    const val CONFIRM = 16
    const val REJECT = 17
}
