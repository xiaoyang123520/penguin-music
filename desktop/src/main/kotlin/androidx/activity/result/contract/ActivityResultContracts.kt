package androidx.activity.result.contract

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResult

abstract class ActivityResultContract<I, O> {
    abstract fun createIntent(context: Context, input: I): Intent
    abstract fun parseResult(resultCode: Int, intent: Intent?): O?
}

object ActivityResultContracts {

    class GetContent : ActivityResultContract<String, Uri?>() {
        override fun createIntent(context: Context, input: String): Intent =
            Intent(Intent.ACTION_GET_CONTENT).setType(input)

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
    }

    class OpenDocument : ActivityResultContract<Array<String>, Uri?>() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).setType(input.firstOrNull() ?: "*/*")

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
    }

    class OpenMultipleDocuments : ActivityResultContract<Array<String>, List<Uri>>() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).setType(input.firstOrNull() ?: "*/*")

        override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> =
            listOfNotNull(intent?.data)
    }

    class CreateDocument(private val mimeType: String = "*/*") :
        ActivityResultContract<String, Uri?>() {
        override fun createIntent(context: Context, input: String): Intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mimeType)

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
    }

    class TakePicture : ActivityResultContract<Uri, Boolean>() {
        override fun createIntent(context: Context, input: Uri): Intent = Intent()
        override fun parseResult(resultCode: Int, intent: Intent?): Boolean = false
    }

    class RequestPermission : ActivityResultContract<String, Boolean>() {
        override fun createIntent(context: Context, input: String): Intent = Intent()
        override fun parseResult(resultCode: Int, intent: Intent?): Boolean = true
    }

    class RequestMultiplePermissions :
        ActivityResultContract<Array<String>, Map<String, Boolean>>() {
        override fun createIntent(context: Context, input: Array<String>): Intent = Intent()
        override fun parseResult(resultCode: Int, intent: Intent?): Map<String, Boolean> = emptyMap()
    }

    class StartActivityForResult : ActivityResultContract<Intent, ActivityResult>() {
        override fun createIntent(context: Context, input: Intent): Intent = input
        override fun parseResult(resultCode: Int, intent: Intent?): ActivityResult =
            ActivityResult(resultCode, intent)
    }
}
