package androidx.core.view

import android.view.View
import android.view.Window

object HapticFeedbackConstantsCompat {
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
    const val GESTURE_START = 38
    const val GESTURE_END = 39
    const val TOGGLE_ON = 41
    const val TOGGLE_OFF = 42
    const val SEGMENT_TICK = 42
    const val SEGMENT_FREQUENT_TICK = 43
    const val NO_HAPTICS = -1
}

object ViewCompat {
    @JvmStatic
    fun performHapticFeedback(view: View, type: Int): Boolean =
        view.performHapticFeedback(type)

    @JvmStatic
    fun getWindowInsetsController(view: View): WindowInsetsControllerCompat =
        WindowInsetsControllerCompat()
}

class WindowInsetsControllerCompat {
    var systemBarsBehavior: Int = BEHAVIOR_DEFAULT

    fun hide(types: Int) {}
    fun show(types: Int) {}
    fun isAppearanceLightStatusBars(): Boolean = false
    fun setAppearanceLightStatusBars(light: Boolean) {}
    fun isAppearanceLightNavigationBars(): Boolean = false
    fun setAppearanceLightNavigationBars(light: Boolean) {}

    companion object {
        const val BEHAVIOR_DEFAULT = 0
        const val BEHAVIOR_SHOW_BARS_BY_TOUCH = 0
        const val BEHAVIOR_SHOW_BARS_BY_SWIPE = 1
        const val BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE = 2
    }
}

object WindowCompat {
    @JvmStatic
    fun getInsetsController(window: Window, view: View): WindowInsetsControllerCompat =
        WindowInsetsControllerCompat()

    @JvmStatic
    fun setDecorFitsSystemWindows(window: Window, decorFitsSystemWindows: Boolean) {}
}

class WindowInsetsCompat {
    companion object {
        fun CONSUMED(): WindowInsetsCompat = WindowInsetsCompat()
    }

    object Type {
        @JvmStatic fun systemBars(): Int = 7
        @JvmStatic fun statusBars(): Int = 1
        @JvmStatic fun navigationBars(): Int = 2
        @JvmStatic fun captionBar(): Int = 4
        @JvmStatic fun ime(): Int = 8
        @JvmStatic fun systemGestures(): Int = 32
        @JvmStatic fun displayCutout(): Int = 128
        @JvmStatic fun all(): Int = 511
    }
}
