@file:JvmName("DynamicColorDesktopShimKt")

package androidx.compose.material3

import android.content.Context
import androidx.compose.runtime.Composable

/**
 * Desktop stand-ins for Android's wallpaper-based dynamic color schemes:
 * there is no wallpaper palette on desktop, so the stock dark/light schemes
 * are returned (the app falls back to these whenever dynamic color is off,
 * too).
 */
fun dynamicDarkColorScheme(context: Context): ColorScheme = darkColorScheme()

fun dynamicLightColorScheme(context: Context): ColorScheme = lightColorScheme()
