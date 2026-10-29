package android.icu.text

/**
 * Desktop stand-in for ICU Transliterator. The app only uses it to derive
 * pinyin sort initials for playlist names; on desktop we return the input
 * unchanged (sorting falls back to raw characters — cosmetic difference).
 */
class Transliterator private constructor() {
    fun transliterate(text: String): String = text

    companion object {
        private val INSTANCE = Transliterator()
        @JvmStatic
        fun getInstance(id: String): Transliterator = INSTANCE
    }
}
