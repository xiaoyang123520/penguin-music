@file:JvmName("SessionDesktopShimKt")

package androidx.media3.session

import android.os.Bundle
import androidx.media3.common.Player

/**
 * Desktop stand-ins for the two media3-session types referenced outside the
 * (excluded) PlaybackService: MediaController is used purely as a Player
 * handle, SessionCommand only as a custom-command descriptor.
 */
typealias MediaController = Player

class SessionCommand {
    val commandCode: Int
    val customAction: String
    val extras: Bundle

    constructor(customAction: String, extras: Bundle) {
        this.commandCode = COMMAND_CODE_CUSTOM
        this.customAction = customAction
        this.extras = extras
    }

    constructor(commandCode: Int) {
        this.commandCode = commandCode
        this.customAction = ""
        this.extras = Bundle()
    }

    companion object {
        const val COMMAND_CODE_CUSTOM = 0
        const val COMMAND_CODE_SESSION_SET_RATING = 40010
    }
}

class SessionResult(val resultCode: Int) {
    companion object {
        const val RESULT_SUCCESS = 0
        const val RESULT_ERROR_UNKNOWN = -1
        const val RESULT_ERROR_INVALID_STATE = -2
        const val RESULT_ERROR_BAD_VALUE = -3
        const val RESULT_ERROR_PERMISSION_DENIED = -4
        const val RESULT_ERROR_IO = -5
        const val RESULT_ERROR_NOT_SUPPORTED = -6
        const val RESULT_SESSION_DISCONNECTED = -100
        const val RESULT_SESSION_CLOSED = -101
    }
}
