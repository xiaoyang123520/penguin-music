@file:JvmName("StringResourceDesktopShimKt")

package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import com.jussicodes.music.Strings

/**
 * Desktop stand-in for Android's stringResource(@StringRes id, vararg args).
 * Ids come from the generated com.jussicodes.music.R.string constants.
 */
@Composable
fun stringResource(id: Int, vararg formatArgs: Any): String {
    val raw = Strings.byId(id)
    return if (formatArgs.isEmpty()) raw else raw.format(*formatArgs)
}

@Composable
fun stringResource(id: Int): String = Strings.byId(id)
