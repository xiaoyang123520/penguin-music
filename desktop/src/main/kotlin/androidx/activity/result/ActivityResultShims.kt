package androidx.activity.result

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

/** Minimal launcher: behavior is supplied by the compose shim that created it. */
open class ActivityResultLauncher<I>(
    private val onLaunch: (I) -> Unit = {}
) {
    open fun launch(input: I) = onLaunch(input)
    open fun unregister() {}
}

class ManagedActivityResultLauncher<I, O>(
    onLaunch: (I) -> Unit
) : ActivityResultLauncher<I>(onLaunch)

data class ActivityResult(val resultCode: Int, val data: Intent?)

object ActivityResultCallerMarker

@Suppress("unused")
private fun unused(c: Context, contract: ActivityResultContract<*, *>) = contract.hashCode()
