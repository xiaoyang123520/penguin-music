package androidx.core.net

import android.net.Uri
import java.io.File

/** 桌面垫片：androidx.core.net 的 toUri 扩展。 */
fun String.toUri(): Uri = Uri.parse(this)

fun File.toUri(): Uri = Uri.parse(toURI().toString())
