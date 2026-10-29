package com.jussicodes.music.playback

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Desktop replacement for the MediaController-based PlayerController:
 * same object/API, but the controller is a [DesktopPlayer] created eagerly
 * in init() instead of a MediaController bound to PlaybackService.
 */
@UnstableApi
object PlayerController {
    private var player: DesktopPlayer? = null

    var controller by mutableStateOf<Player?>(null)
        private set

    fun init(context: Context) {
        if (controller != null) return
        val created = DesktopPlayer(context.applicationContext)
        player = created
        controller = created
    }
}
