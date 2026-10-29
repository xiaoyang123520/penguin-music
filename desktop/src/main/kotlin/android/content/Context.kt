package android.content

import android.content.res.Resources
import java.io.File

/** 桌面垫片：最小 Context。真实目录/字符串由 DesktopContext 覆写。 */
open class Context {

    open val applicationContext: Context
        get() = this

    open val cacheDir: File
        get() = File(appDataRoot(), "cache").apply { mkdirs() }

    open val filesDir: File
        get() = File(appDataRoot(), "files").apply { mkdirs() }

    open val externalCacheDir: File?
        get() = cacheDir

    open fun getExternalFilesDir(type: String?): File? =
        File(filesDir, type ?: "").apply { mkdirs() }

    open val packageName: String
        get() = "com.jussicodes.music"

    open val resources: Resources
        get() = Resources

    open val assets: android.content.res.AssetManager
        get() = android.content.res.AssetManager.instance

    open val contentResolver: ContentResolver
        get() = ContentResolver

    open fun getString(resId: Int): String = StringResources.lookup(resId)

    open fun getString(resId: Int, vararg formatArgs: Any?): String =
        runCatching { String.format(getString(resId), *formatArgs) }.getOrDefault(getString(resId))

    open fun getText(resId: Int): CharSequence = getString(resId)

    open fun getSystemService(name: String): Any? = null

    open fun <T> getSystemService(serviceClass: Class<T>): T? = null

    open fun startActivity(intent: Intent) {
        // 桌面实现：ACTION_VIEW 打开系统浏览器
        val uri = intent.data?.toString()
        if (!uri.isNullOrBlank() && (uri.startsWith("http://") || uri.startsWith("https://"))) {
            runCatching {
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().browse(java.net.URI(uri))
                }
            }
        }
    }

    open fun startService(intent: Intent): ComponentName? = null

    open fun stopService(intent: Intent): Boolean = true

    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        FileSharedPreferences(File(filesDir, "shared_prefs/$name.properties"))

    companion object {
        const val MODE_PRIVATE: Int = 0
        const val BIND_AUTO_CREATE: Int = 1
        const val NOTIFICATION_SERVICE: String = "notification"
        const val AUDIO_SERVICE: String = "audio"
        const val WINDOW_SERVICE: String = "window"
        const val CLIPBOARD_SERVICE: String = "clipboard"
        const val CONNECTIVITY_SERVICE: String = "connectivity"
        const val DOWNLOAD_SERVICE: String = "download"
        const val LAYOUT_INFLATER_SERVICE: String = "layout_inflater"
        const val INPUT_METHOD_SERVICE: String = "input_method"

        /** 应用数据根目录：~/.jussichords（可用 -Djussichords.dataDir 覆盖） */
        fun appDataRoot(): File =
            File(System.getProperty("jussichords.dataDir") ?: File(System.getProperty("user.home"), ".jussichords").path)
    }
}

/** 字符串 id → 文案的提供点，由桌面 Strings 生成代码在启动时注册。 */
object StringResources {
    @Volatile
    var provider: (Int) -> String = { "" }

    fun lookup(resId: Int): String = provider(resId)
}
