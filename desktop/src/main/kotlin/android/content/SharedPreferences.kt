package android.content

import java.io.File
import java.util.Properties

/** 桌面垫片：接口形状对齐 Android，底层用 properties 文件落盘。 */
interface SharedPreferences {
    val all: Map<String, *>
    fun getString(key: String, defValue: String?): String?
    fun getStringSet(key: String, defValues: Set<String>?): Set<String>?
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun getFloat(key: String, defValue: Float): Float
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun contains(key: String): Boolean
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putStringSet(key: String, values: Set<String>?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun commit(): Boolean
        fun apply()
    }
}

class FileSharedPreferences(private val file: File) : SharedPreferences {
    private val props = Properties()
    private var loaded = false

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        if (file.exists()) {
            runCatching { file.inputStream().use { props.load(it) } }
        }
    }

    @Synchronized
    private fun persist() {
        file.parentFile?.mkdirs()
        runCatching { file.outputStream().use { props.store(it, "jussichords-desktop") } }
    }

    override val all: Map<String, *>
        get() {
            load()
            @Suppress("UNCHECKED_CAST")
            return props.toMap() as Map<String, *>
        }

    override fun getString(key: String, defValue: String?): String? {
        load(); return props.getProperty(key) ?: defValue
    }

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
        val raw = getString(key, null) ?: return defValues
        return if (raw.isEmpty()) emptySet() else raw.split("").toSet()
    }

    override fun getInt(key: String, defValue: Int): Int =
        getString(key, null)?.toIntOrNull() ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        getString(key, null)?.toLongOrNull() ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        getString(key, null)?.toFloatOrNull() ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        getString(key, null)?.toBooleanStrictOrNull() ?: defValue

    override fun contains(key: String): Boolean {
        load(); return props.containsKey(key)
    }

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    private inner class EditorImpl : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, String?>()
        private var clearAll = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) =
            apply { pending[key] = values?.joinToString("") }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value.toString() }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value.toString() }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value.toString() }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value.toString() }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { clearAll = true }

        override fun commit(): Boolean {
            synchronized(this@FileSharedPreferences) {
                load()
                if (clearAll) props.clear()
                for ((k, v) in pending) {
                    if (v == null) props.remove(k) else props.setProperty(k, v)
                }
                persist()
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
