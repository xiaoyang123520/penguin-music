package android.content.res

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Desktop AssetManager: resolves asset names against the app resources that
 * ship inside the fat jar (assets/...) and, during development, the source
 * tree's app/src/main/assets directory.
 */
class AssetManager {
    fun open(fileName: String): InputStream {
        val normalized = fileName.trimStart('/')
        // 1) classpath: /assets/<name> inside the fat jar
        AssetManager::class.java.getResourceAsStream("/assets/$normalized")?.let { return it }
        AssetManager::class.java.getResourceAsStream("/$normalized")?.let { return it }
        // 2) dev fallback: working copy assets
        val candidates = listOf(
            File("app/src/main/assets", normalized),
            File(System.getProperty("user.dir"), "app/src/main/assets/$normalized"),
            File(System.getProperty("jussichords.assetsDir", ""), normalized),
        )
        for (candidate in candidates) {
            if (candidate.isFile) return FileInputStream(candidate)
        }
        throw IOException("Asset not found: $fileName")
    }

    fun list(path: String): Array<String> = emptyArray()

    companion object {
        val instance: AssetManager = AssetManager()
    }
}
