package com.jussicodes.music.utils

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Desktop version of ImageSaveUtils: the Android build saves into the
 * system gallery via MediaStore; here the image is downloaded into
 * ~/Pictures/jussichords/ instead. Same signature and Result contract.
 */
suspend fun saveRemoteImage(context: Context, imageUrl: String): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            val conn = NetProxy.openHttp(imageUrl)
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
            val bytes = conn.inputStream.use { it.readBytes() }
            val dir = File(System.getProperty("user.home"), "Pictures/jussichords")
                .apply { mkdirs() }
            val rawName = imageUrl.substringBefore('?').substringAfterLast('/')
                .ifBlank { "image" }
            val name = if (rawName.contains('.')) rawName else "$rawName.jpg"
            File(dir, name).writeBytes(bytes)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context, "已保存到 Pictures/jussichords/$name", Toast.LENGTH_SHORT
                ).show()
            }
            Unit
        }
    }
