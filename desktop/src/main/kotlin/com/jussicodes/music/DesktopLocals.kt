package com.jussicodes.music

import androidx.compose.runtime.staticCompositionLocalOf
import com.jussicodes.music.playback.PlayerController
import com.jussicodes.music.playback.PlayerState

/**
 * These CompositionLocals live at the bottom of MainActivity.kt in the
 * Android build (excluded on desktop); they are defined identically here so
 * every consumer compiles unchanged.
 */
val LocalPlayerController = staticCompositionLocalOf<PlayerController> {
    error("No PlayerController provided")
}

val LocalPlayerState = staticCompositionLocalOf<PlayerState?> {
    error("No PlayerState provided")
}

data class UiScaleController(
    val scale: Float,
    val setScale: (Float) -> Unit,
)

val LocalUiScaleController = staticCompositionLocalOf<UiScaleController> {
    UiScaleController(
        scale = 1f,
        setScale = {},
    )
}
