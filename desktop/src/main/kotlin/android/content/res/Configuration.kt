package android.content.res

/** Minimal desktop Configuration (only fields the app reads). */
class Configuration {
    var screenWidthDp: Int = 1280
    var screenHeightDp: Int = 800
    var orientation: Int = ORIENTATION_LANDSCAPE
    var uiMode: Int = 0
    var densityDpi: Int = 160
    var fontScale: Float = 1f
    var smallestScreenWidthDp: Int = 800

    companion object {
        const val ORIENTATION_UNDEFINED = 0
        const val ORIENTATION_PORTRAIT = 1
        const val ORIENTATION_LANDSCAPE = 2
        const val UI_MODE_NIGHT_MASK = 0x30
        const val UI_MODE_NIGHT_NO = 0x10
        const val UI_MODE_NIGHT_YES = 0x20
        const val UI_MODE_TYPE_NORMAL = 0x01
    }
}
