@file:JvmName("HiltViewModelDesktopShimKt")

package androidx.hilt.navigation.compose

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry

/**
 * Desktop replacement for Hilt's hiltViewModel(): manual assembly.
 * The app's ViewModels only use constructors of shape (Context),
 * (SavedStateHandle), (Context, SavedStateHandle) or (), which are matched
 * reflectively, longest signature first. When the composition sits inside a
 * NavHost entry, the entry's own SavedStateHandle (carrying the navigation
 * arguments: playlistId / songId / ...) is supplied, exactly like the
 * Android Hilt + Navigation integration does.
 */
@Composable
inline fun <reified VM : ViewModel> hiltViewModel(): VM {
    val context = LocalContext.current
    val owner = LocalViewModelStoreOwner.current
    val savedStateHandle = (owner as? NavBackStackEntry)?.savedStateHandle
    return remember(owner) {
        DesktopViewModelFactory.create(VM::class.java, context, savedStateHandle)
    }
}

object DesktopViewModelFactory {
    fun <VM : ViewModel> create(
        cls: Class<VM>,
        context: Context,
        savedStateHandle: SavedStateHandle? = null
    ): VM {
        val ctors = cls.declaredConstructors.sortedByDescending { it.parameterCount }
        var lastError: Throwable? = null
        for (ctor in ctors) {
            val params = ctor.parameterTypes
            val args = arrayOfNulls<Any>(params.size)
            var satisfiable = true
            for (i in params.indices) {
                args[i] = when (params[i]) {
                    Context::class.java -> context
                    SavedStateHandle::class.java ->
                        savedStateHandle ?: SavedStateHandle(emptyMap<String, Any?>())
                    else -> {
                        satisfiable = false
                        break
                    }
                }
            }
            if (!satisfiable) continue
            try {
                ctor.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                return ctor.newInstance(*args) as VM
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw IllegalStateException(
            "DesktopViewModelFactory: no satisfiable constructor for ${cls.name}", lastError
        )
    }
}
