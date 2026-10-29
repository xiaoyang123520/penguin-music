package androidx.core.graphics

import kotlin.math.roundToInt

/** Minimal androidx.core.graphics.ColorUtils (only what the app calls). */
object ColorUtils {
    @JvmStatic
    fun blendARGB(color1: Int, color2: Int, ratio: Float): Int {
        val inverse = 1 - ratio
        val a = (android.graphics.Color.alpha(color1) * inverse +
            android.graphics.Color.alpha(color2) * ratio).roundToInt()
        val r = (android.graphics.Color.red(color1) * inverse +
            android.graphics.Color.red(color2) * ratio).roundToInt()
        val g = (android.graphics.Color.green(color1) * inverse +
            android.graphics.Color.green(color2) * ratio).roundToInt()
        val b = (android.graphics.Color.blue(color1) * inverse +
            android.graphics.Color.blue(color2) * ratio).roundToInt()
        return android.graphics.Color.argb(a, r, g, b)
    }

    @JvmStatic
    fun setAlphaComponent(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    @JvmStatic
    fun compositeColors(foreground: Int, background: Int): Int {
        val fa = android.graphics.Color.alpha(foreground) / 255f
        val ba = android.graphics.Color.alpha(background) / 255f
        val a = fa + ba * (1 - fa)
        if (a <= 0f) return 0
        fun channel(f: Int, b: Int): Int =
            ((f * fa + b * ba * (1 - fa)) / a).roundToInt()
        return android.graphics.Color.argb(
            (a * 255).roundToInt(),
            channel(android.graphics.Color.red(foreground), android.graphics.Color.red(background)),
            channel(android.graphics.Color.green(foreground), android.graphics.Color.green(background)),
            channel(android.graphics.Color.blue(foreground), android.graphics.Color.blue(background)),
        )
    }
}
