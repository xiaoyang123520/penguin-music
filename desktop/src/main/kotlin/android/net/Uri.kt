package android.net

import java.net.URLDecoder
import java.net.URLEncoder

/** 桌面垫片：字符串包装的 Uri，覆盖 parse/toString/buildUpon/encode 常用面。 */
class Uri private constructor(private val value: String) {

    override fun toString(): String = value

    override fun equals(other: Any?): Boolean = other is Uri && other.value == value
    override fun hashCode(): Int = value.hashCode()

    val scheme: String?
        get() {
            val colon = value.indexOf(':')
            if (colon <= 0) return null
            val candidate = value.substring(0, colon)
            if (!candidate[0].isLetter() ||
                !candidate.all { it.isLetterOrDigit() || it in "+-." }
            ) return null
            val firstDelim = listOf('/', '?', '#')
                .map { value.indexOf(it) }
                .filter { it >= 0 }
                .minOrNull() ?: Int.MAX_VALUE
            return if (colon < firstDelim) candidate.lowercase() else null
        }

    val host: String?
        get() = value.substringAfter("://", "").substringBefore("/").substringBefore("?")
            .ifEmpty { null }

    val path: String?
        get() {
            val schemeSep = value.indexOf("://")
            if (schemeSep >= 0) {
                val afterAuthority = value.substring(schemeSep + 3)
                val idx = afterAuthority.indexOf('/')
                return if (idx >= 0) {
                    afterAuthority.substring(idx).substringBefore("?").substringBefore("#")
                } else ""
            }
            // Relative/opaque URI: the path is everything before '?' or '#'
            // (e.g. the app's song pseudo-URIs "1315196858?fee=0&pl=0").
            return value.substringBefore("?").substringBefore("#")
        }

    val lastPathSegment: String?
        get() = path?.trimEnd('/')?.substringAfterLast('/')

    val query: String?
        get() = value.substringAfter("?", "").ifEmpty { null }

    fun getQueryParameter(name: String): String? {
        val q = query ?: return null
        return q.split("&").firstNotNullOfOrNull { part ->
            val kv = part.split("=", limit = 2)
            if (kv[0] == name) kv.getOrNull(1)?.let { decode(it) } else null
        }
    }

    val queryParameterNames: Set<String>
        get() {
            val q = query ?: return emptySet()
            return q.split("&").mapNotNull { part ->
                part.split("=", limit = 2).firstOrNull()?.let { decode(it) }
            }.toSet()
        }

    fun getQueryParameters(name: String): List<String> {
        val q = query ?: return emptyList()
        return q.split("&").mapNotNull { part ->
            val kv = part.split("=", limit = 2)
            if (kv.size == 2 && decode(kv[0]) == name) decode(kv[1]) else null
        }
    }

    fun buildUpon(): Builder = Builder(value)

    class Builder(private var base: String) {
        fun appendPath(segment: String): Builder {
            base = base.trimEnd('/') + "/" + encode(segment)
            return this
        }

        fun appendQueryParameter(key: String, value: String): Builder {
            base += (if (base.contains("?")) "&" else "?") + encode(key) + "=" + encode(value)
            return this
        }

        fun scheme(scheme: String): Builder {
            base = scheme + "://" + base.substringAfter("://", base)
            return this
        }

        fun clearQuery(): Builder {
            base = base.substringBefore("?")
            return this
        }

        fun fragment(fragment: String?): Builder {
            base = base.substringBefore("#")
            if (!fragment.isNullOrEmpty()) base += "#$fragment"
            return this
        }

        fun build(): Uri = Uri(base)
    }

    companion object {
        @JvmStatic
        fun parse(value: String?): Uri = Uri(value ?: "")

        @JvmStatic
        fun fromParts(scheme: String, ssp: String, fragment: String?): Uri =
            Uri("$scheme:$ssp${fragment?.let { "#$it" } ?: ""}")

        @JvmStatic
        fun encode(s: String?): String =
            URLEncoder.encode(s ?: "", "UTF-8").replace("+", "%20")

        @JvmStatic
        fun decode(s: String?): String =
            runCatching { URLDecoder.decode(s ?: "", "UTF-8") }.getOrDefault(s ?: "")
    }
}
