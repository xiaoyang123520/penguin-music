package android.content

import android.net.Uri
import java.io.FileInputStream
import java.io.InputStream

/** 桌面垫片：仅支持 file:// 与本地路径的读取，其余返回 null。 */
object ContentResolver {
    fun openInputStream(uri: Uri): InputStream? {
        val s = uri.toString()
        return runCatching {
            when {
                s.startsWith("file://") -> FileInputStream(java.io.File(java.net.URI(s)))
                s.startsWith("/") -> FileInputStream(s)
                else -> null
            }
        }.getOrNull()
    }
}
