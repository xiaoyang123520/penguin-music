package android.os

/** 桌面垫片：Map 实现的 Bundle，覆盖 MediaItem extras / Intent extras 的读写面。 */
open class Bundle {
    companion object {
        val EMPTY: Bundle = Bundle()
    }

    private val map = LinkedHashMap<String, Any?>()

    fun putString(key: String, value: String?) { map[key] = value }
    fun getString(key: String): String? = map[key] as? String
    fun getString(key: String, defaultValue: String?): String? = getString(key) ?: defaultValue

    fun putLong(key: String, value: Long) { map[key] = value }
    fun getLong(key: String): Long = (map[key] as? Number)?.toLong() ?: 0L
    fun getLong(key: String, defaultValue: Long): Long =
        if (map.containsKey(key)) getLong(key) else defaultValue

    fun putInt(key: String, value: Int) { map[key] = value }
    fun getInt(key: String): Int = (map[key] as? Number)?.toInt() ?: 0
    fun getInt(key: String, defaultValue: Int): Int =
        if (map.containsKey(key)) getInt(key) else defaultValue

    fun putBoolean(key: String, value: Boolean) { map[key] = value }
    fun getBoolean(key: String): Boolean = map[key] as? Boolean ?: false
    fun getBoolean(key: String, defaultValue: Boolean): Boolean = map[key] as? Boolean ?: defaultValue

    fun putFloat(key: String, value: Float) { map[key] = value }
    fun getFloat(key: String): Float = (map[key] as? Number)?.toFloat() ?: 0f

    fun putDouble(key: String, value: Double) { map[key] = value }
    fun getDouble(key: String): Double = (map[key] as? Number)?.toDouble() ?: 0.0

    fun putStringArrayList(key: String, value: ArrayList<String>?) { map[key] = value }
    @Suppress("UNCHECKED_CAST")
    fun getStringArrayList(key: String): ArrayList<String>? = map[key] as? ArrayList<String>

    fun putBundle(key: String, value: Bundle?) { map[key] = value }
    fun getBundle(key: String): Bundle? = map[key] as? Bundle

    fun putParcelable(key: String, value: Any?) { map[key] = value }
    fun putSerializable(key: String, value: java.io.Serializable?) { map[key] = value }

    fun containsKey(key: String): Boolean = map.containsKey(key)
    fun remove(key: String) { map.remove(key) }
    fun clear() { map.clear() }
    fun isEmpty(): Boolean = map.isEmpty()
    fun size(): Int = map.size
    fun keySet(): MutableSet<String> = map.keys
    fun putAll(bundle: Bundle) { map.putAll(bundle.map) }
}
