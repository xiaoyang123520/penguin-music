@file:JvmName("LocalsDesktopShimKt")

package androidx.compose.ui.platform

import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import com.jussicodes.music.desktop.DesktopContextHolder

/** Desktop stand-in: always yields the process-wide desktop Context. */
val LocalContext: ProvidableCompositionLocal<Context> = staticCompositionLocalOf {
    DesktopContextHolder.context
}

/** Desktop stand-in: reports a large landscape "display" (window is resizable;
 * values approximate a typical desktop window in dp). */
val LocalConfiguration: ProvidableCompositionLocal<Configuration> = staticCompositionLocalOf {
    Configuration().apply {
        screenWidthDp = 1280
        screenHeightDp = 800
        orientation = Configuration.ORIENTATION_LANDSCAPE
    }
}

/** Desktop stand-in: a single shared dummy View (keepScreenOn/haptics are no-ops). */
val LocalView: ProvidableCompositionLocal<View> = staticCompositionLocalOf { View() }
