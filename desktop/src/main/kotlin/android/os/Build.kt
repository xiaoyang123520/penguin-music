package android.os

/** 桌面垫片：给出无害的固定值，仅用于 UA 拼接等非关键路径。 */
object Build {
    const val MODEL: String = "Desktop"
    const val BRAND: String = "Jussichords"
    const val MANUFACTURER: String = "Jussichords"
    const val DEVICE: String = "desktop"
    const val PRODUCT: String = "desktop"
    const val HARDWARE: String = "desktop"
    const val ID: String = "desktop"
    const val DISPLAY: String = "desktop"
    const val FINGERPRINT: String = "jussichords/desktop"
    const val HOST: String = "localhost"
    const val USER: String = "user"
    const val TYPE: String = "release"
    const val TAGS: String = "release-keys"
    const val TIME: Long = 0L
    val SUPPORTED_ABIS: Array<String> = arrayOf("x86_64")

    object VERSION {
        const val SDK_INT: Int = 34
        const val PREVIEW_SDK_INT: Int = 0
        const val RELEASE: String = "14"
        const val RELEASE_OR_CODENAME: String = "14"
        const val CODENAME: String = "REL"
        const val INCREMENTAL: String = ""
        const val BASE_OS: String = ""
        const val SECURITY_PATCH: String = ""
    }

    object VERSION_CODES {
        const val M: Int = 23
        const val N: Int = 24
        const val O: Int = 26
        const val P: Int = 28
        const val Q: Int = 29
        const val R: Int = 30
        const val S: Int = 31
        const val S_V2: Int = 32
        const val TIRAMISU: Int = 33
        const val UPSIDE_DOWN_CAKE: Int = 34
    }
}
