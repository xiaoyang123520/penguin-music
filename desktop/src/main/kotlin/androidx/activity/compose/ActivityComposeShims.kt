@file:JvmName("ActivityComposeDesktopShimKt")

package androidx.activity.compose

import android.net.Uri
import androidx.activity.result.ManagedActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Desktop rememberLauncherForActivityResult:
 * - GetContent / OpenDocument / OpenMultipleDocuments -> AWT file dialog,
 *   result delivered as a file:// Uri (the app's ContentResolver shim can
 *   read file:// streams).
 * - RequestPermission(s) -> granted immediately (no runtime permissions on desktop).
 * - Anything else -> null/false result delivered, mirroring a cancelled pick.
 */
@Composable
fun <I, O> rememberLauncherForActivityResult(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit
): ManagedActivityResultLauncher<I, O> {
    val latestOnResult = rememberUpdatedState(onResult)
    return remember(contract) {
        ManagedActivityResultLauncher<I, O> { input ->
            @Suppress("UNCHECKED_CAST")
            when (contract) {
                is ActivityResultContracts.RequestPermission ->
                    latestOnResult.value(true as O)

                is ActivityResultContracts.RequestMultiplePermissions -> {
                    val inputs = input as? Array<*> ?: emptyArray<Any>()
                    val map = inputs.filterIsInstance<String>().associateWith { true }
                    latestOnResult.value(map as O)
                }

                is ActivityResultContracts.GetContent,
                is ActivityResultContracts.OpenDocument -> {
                    val file = pickFile()
                    @Suppress("UNCHECKED_CAST")
                    latestOnResult.value(
                        file?.let { Uri.parse(it.toURI().toString()) } as O
                    )
                }

                is ActivityResultContracts.OpenMultipleDocuments -> {
                    val file = pickFile()
                    val list = listOfNotNull(file?.let { Uri.parse(it.toURI().toString()) })
                    latestOnResult.value(list as O)
                }

                else -> {
                    @Suppress("UNCHECKED_CAST")
                    latestOnResult.value(null as O)
                }
            }
        }
    }
}

private fun pickFile(): File? {
    var chosen: File? = null
    val dialog = FileDialog(null as Frame?, "选择文件", FileDialog.LOAD)
    dialog.isVisible = true // blocks until dismissed
    val dir = dialog.directory
    val name = dialog.file
    if (dir != null && name != null) chosen = File(dir, name)
    dialog.dispose()
    return chosen
}

/** Desktop has no system back gesture; BackHandler is a no-op registration. */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    DisposableEffect(enabled) { onDispose { } }
}
