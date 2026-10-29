package android.content

import java.io.File

/** Minimal ContextWrapper: forwards the members the app relies on to [baseContext]. */
open class ContextWrapper(val baseContext: Context) : Context() {
    override val applicationContext: Context get() = baseContext.applicationContext
    override val cacheDir: File get() = baseContext.cacheDir
    override val filesDir: File get() = baseContext.filesDir
    override val contentResolver: ContentResolver get() = baseContext.contentResolver
    override fun getSystemService(name: String): Any? = baseContext.getSystemService(name)
    override fun getString(resId: Int): String = baseContext.getString(resId)
    override fun startActivity(intent: Intent) = baseContext.startActivity(intent)
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        baseContext.getSharedPreferences(name, mode)
}
