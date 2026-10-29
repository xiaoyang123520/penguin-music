package com.jussicodes.music.utils

import android.content.Context
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Desktop version of CacheManager: same Snapshot/measure/clear API, but sizes
 * come straight from the cache directories (the Android build additionally
 * consulted media3's SimpleCache index and Coil's runtime caches; the desktop
 * image cache lives in the same `coil` directory, so numbers stay accurate).
 */
object CacheManager {

    data class Bucket(
        val name: String,
        val bytes: Long,
    )

    data class Snapshot(
        val audio: Bucket,
        val image: Bucket,
        val other: Bucket,
    ) {
        val totalBytes: Long get() = audio.bytes + image.bytes + other.bytes
    }

    private const val AUDIO_DIR = "audio"
    private const val IMAGE_DIR = "coil"

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { f -> if (f.isFile) total += f.length() }
        return total
    }

    suspend fun measure(context: Context): Snapshot = withContext(Dispatchers.IO) {
        val cacheDir = context.cacheDir
        val audioBytes = dirSize(File(cacheDir, AUDIO_DIR))
        val imageBytes = dirSize(File(cacheDir, IMAGE_DIR))
        var otherBytes = 0L
        runCatching { cacheDir.listFiles() }.getOrNull()?.forEach { f ->
            when {
                f.isFile -> otherBytes += f.length()
                f.isDirectory && f.name != AUDIO_DIR && f.name != IMAGE_DIR ->
                    otherBytes += dirSize(f)
            }
        }
        Snapshot(
            audio = Bucket("音频缓存", audioBytes),
            image = Bucket("图片缓存", imageBytes),
            other = Bucket("其它缓存", otherBytes),
        )
    }

    suspend fun clear(context: Context): Boolean = withContext(Dispatchers.IO) {
        val cacheDir = context.cacheDir
        var ok = true
        ok = runCatching { File(cacheDir, AUDIO_DIR).deleteRecursively() }.getOrElse { false } && ok
        ok = runCatching { File(cacheDir, IMAGE_DIR).deleteRecursively() }.getOrElse { false } && ok
        ok = runCatching {
            cacheDir.listFiles()?.forEach { f ->
                when {
                    f.isFile -> f.delete()
                    f.isDirectory && f.name != AUDIO_DIR && f.name != IMAGE_DIR ->
                        f.deleteRecursively()
                }
            }
            true
        }.getOrElse { false } && ok
        ok
    }
}

/**
 * Desktop stand-in for the media3 SimpleCache-backed AudioCache statistics
 * object that lives in the Android CacheManager.kt: the desktop engine does
 * not keep an on-disk audio cache, so this simply reports/clears the audio
 * cache directory.
 */
@UnstableApi
object AudioCache {
    /** The desktop engine streams without an on-disk audio cache: always 0. */
    fun measureBytes(): Long = 0L

    fun clearCached(): Boolean = true
}
