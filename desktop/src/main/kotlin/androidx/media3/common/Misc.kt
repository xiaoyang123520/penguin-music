package androidx.media3.common

/** 桌面垫片集合：PlayerState 只存不用细的 media3 类型，给最小可用实现。 */

class Timeline {
    val periodCount: Int = 0
    val windowCount: Int = 0
    val isEmpty: Boolean = true

    class Window {
        val isSeekable: Boolean = false
        val isDynamic: Boolean = false
        val durationMs: Long = C.TIME_UNSET
    }

    class Period {
        val durationMs: Long = C.TIME_UNSET
        val positionInWindowMs: Long = 0
    }

    fun getWindow(windowIndex: Int, window: Window): Window = window
    fun getPeriod(periodIndex: Int, period: Period): Period = period
    fun getIndexOfPeriod(uid: Any): Int = C.INDEX_UNSET

    companion object {
        val EMPTY: Timeline = Timeline()
    }
}

class Tracks {
    val groups: List<Group> = emptyList()
    val isEmpty: Boolean = true

    class Group
}

class TrackSelectionParameters {
    class Builder {
        fun build(): TrackSelectionParameters = TrackSelectionParameters()
    }

    companion object {
        val DEFAULT: TrackSelectionParameters = TrackSelectionParameters()
    }
}

class PlaybackException(
    message: String? = null,
    cause: Throwable? = null,
    val errorCode: Int = ERROR_CODE_UNSPECIFIED,
) : Exception(message, cause) {
    val errorCodeName: String
        get() = "error_code_$errorCode"

    companion object {
        const val ERROR_CODE_UNSPECIFIED: Int = 1000
        const val ERROR_CODE_REMOTE_ERROR: Int = 1001
        const val ERROR_CODE_BEHIND_LIVE_WINDOW: Int = 1002
        const val ERROR_CODE_TIMEOUT: Int = 1003
        const val ERROR_CODE_IO_UNSPECIFIED: Int = 2000
        const val ERROR_CODE_IO_NETWORK_CONNECTION_FAILED: Int = 2001
        const val ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT: Int = 2002
        const val ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE: Int = 2003
        const val ERROR_CODE_IO_BAD_HTTP_STATUS: Int = 2004
        const val ERROR_CODE_IO_FILE_NOT_FOUND: Int = 2005
        const val ERROR_CODE_IO_NO_PERMISSION: Int = 2006
        const val ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED: Int = 2007
        const val ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE: Int = 2008
        const val ERROR_CODE_PARSER_CONTAINER_UNSUPPORTED: Int = 3001
        const val ERROR_CODE_DECODER_INIT_FAILED: Int = 4001
        const val ERROR_CODE_AUDIO_TRACK_INIT_FAILED: Int = 6001
    }
}

data class PlaybackParameters(
    val speed: Float = 1f,
    val pitch: Float = 1f,
) {
    companion object {
        val DEFAULT: PlaybackParameters = PlaybackParameters()
    }
}

class AudioAttributes(
    val contentType: Int = C.AUDIO_CONTENT_TYPE_MUSIC,
    val usage: Int = C.USAGE_MEDIA,
) {
    class Builder {
        private var contentType: Int = C.AUDIO_CONTENT_TYPE_MUSIC
        private var usage: Int = C.USAGE_MEDIA
        fun setContentType(contentType: Int): Builder = apply { this.contentType = contentType }
        fun setUsage(usage: Int): Builder = apply { this.usage = usage }
        fun build(): AudioAttributes = AudioAttributes(contentType, usage)
    }

    companion object {
        val DEFAULT: AudioAttributes = AudioAttributes()
    }
}

class DeviceInfo(val playbackType: Int = PLAYBACK_TYPE_LOCAL) {
    companion object {
        const val PLAYBACK_TYPE_LOCAL: Int = 0
        const val PLAYBACK_TYPE_REMOTE: Int = 1
        val UNKNOWN: DeviceInfo = DeviceInfo()
        val DEFAULT: DeviceInfo = DeviceInfo()
    }
}

data class VideoSize(
    val width: Int = 0,
    val height: Int = 0,
    val unappliedRotationDegrees: Int = 0,
    val pixelWidthHeightRatio: Float = 1f,
) {
    companion object {
        val UNKNOWN: VideoSize = VideoSize()
    }
}

class Format {
    val bitrate: Int = Format.NO_VALUE
    val sampleRate: Int = Format.NO_VALUE

    companion object {
        const val NO_VALUE: Int = -1
    }
}
