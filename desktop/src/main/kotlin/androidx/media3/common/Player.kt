package androidx.media3.common

import androidx.media3.common.text.CueGroup

/**
 * 桌面垫片：media3 Player 接口的裁剪版，成员面按 app 实际调用统计（PlayerState /
 * PlayerExt / UI 组件）确定。桌面实现见 DesktopPlayer。
 */
interface Player {

    @Target(
        AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY,
        AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER, AnnotationTarget.LOCAL_VARIABLE, AnnotationTarget.FUNCTION,
    )
    @Retention(AnnotationRetention.BINARY)
    annotation class State

    @Target(
        AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY,
        AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER, AnnotationTarget.LOCAL_VARIABLE, AnnotationTarget.FUNCTION,
    )
    @Retention(AnnotationRetention.BINARY)
    annotation class RepeatMode

    @Target(
        AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY,
        AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER, AnnotationTarget.LOCAL_VARIABLE, AnnotationTarget.FUNCTION,
    )
    @Retention(AnnotationRetention.BINARY)
    annotation class PlaybackSuppressionReason

    @Target(
        AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY,
        AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER, AnnotationTarget.LOCAL_VARIABLE, AnnotationTarget.FUNCTION,
    )
    @Retention(AnnotationRetention.BINARY)
    annotation class PlayWhenReadyChangeReason

    @Target(
        AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY,
        AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER, AnnotationTarget.LOCAL_VARIABLE, AnnotationTarget.FUNCTION,
    )
    @Retention(AnnotationRetention.BINARY)
    annotation class DiscontinuityReason

    @Target(
        AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY,
        AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER, AnnotationTarget.LOCAL_VARIABLE, AnnotationTarget.FUNCTION,
    )
    @Retention(AnnotationRetention.BINARY)
    annotation class MediaItemTransitionReason

    class Commands(private val commands: Set<Int> = emptySet()) {
        fun contains(command: Int): Boolean = command in commands
        fun containsAny(vararg cmds: Int): Boolean = cmds.any { it in commands }
        val size: Int get() = commands.size

        class Builder {
            private val set = mutableSetOf<Int>()
            fun add(command: Int): Builder = apply { set.add(command) }
            fun addAll(vararg cmds: Int): Builder = apply { set.addAll(cmds.toList()) }
            fun build(): Commands = Commands(set.toSet())
        }

        companion object {
            val EMPTY: Commands = Commands()
        }
    }

    class PositionInfo(
        val mediaItemIndex: Int = 0,
        val mediaItem: MediaItem? = null,
        val periodIndex: Int = 0,
        val positionMs: Long = 0L,
        val contentPositionMs: Long = 0L,
        val adGroupIndex: Int = C.INDEX_UNSET,
        val adIndexInAdGroup: Int = C.INDEX_UNSET,
        val manifestWindowIndex: Int = 0,
    )

    class Events

    interface Listener {
        fun onEvents(player: Player, events: Events) {}
        fun onTimelineChanged(timeline: Timeline, reason: Int) {}
        fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {}
        fun onTracksChanged(tracks: Tracks) {}
        fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {}
        fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) {}
        fun onIsLoadingChanged(isLoading: Boolean) {}
        fun onLoadingChanged(isLoading: Boolean) {}
        fun onAvailableCommandsChanged(availableCommands: Commands) {}
        fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {}
        fun onPlaybackStateChanged(playbackState: Int) {}
        fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {}
        fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {}
        fun onIsPlayingChanged(isPlaying: Boolean) {}
        fun onRepeatModeChanged(repeatMode: Int) {}
        fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {}
        fun onPlayerErrorChanged(error: PlaybackException?) {}
        fun onPlayerError(error: PlaybackException) {}
        fun onPositionDiscontinuity(oldPosition: PositionInfo, newPosition: PositionInfo, reason: Int) {}
        fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {}
        fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) {}
        fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) {}
        fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) {}
        fun onAudioAttributesChanged(audioAttributes: AudioAttributes) {}
        fun onVolumeChanged(volume: Float) {}
        fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {}
        fun onDeviceVolumeChanged(volume: Int, muted: Boolean) {}
        fun onVideoSizeChanged(videoSize: VideoSize) {}
        fun onCues(cueGroup: CueGroup) {}
        fun onSeekProcessed() {}
        fun onRenderedFirstFrame() {}
        fun onSurfaceSizeChanged(width: Int, height: Int) {}
        fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {}
    }

    // ---- 状态只读面 ----
    val currentTimeline: Timeline
    val currentTracks: Tracks
    val currentMediaItemIndex: Int
    val currentPeriodIndex: Int
    val currentMediaItem: MediaItem?
    val mediaMetadata: MediaMetadata
    val playlistMetadata: MediaMetadata
    val isLoading: Boolean
    val availableCommands: Commands
    val trackSelectionParameters: TrackSelectionParameters

    @get:State
    val playbackState: Int

    @get:PlaybackSuppressionReason
    val playbackSuppressionReason: Int
    val isPlaying: Boolean
    val isDeviceMuted: Boolean
    val playerError: PlaybackException?
    val playbackParameters: PlaybackParameters
    val seekBackIncrement: Long
    val seekForwardIncrement: Long
    val maxSeekToPreviousPosition: Long
    val audioAttributes: AudioAttributes
    val deviceInfo: DeviceInfo
    val deviceVolume: Int
    val videoSize: VideoSize
    val currentCues: CueGroup
    val mediaItemCount: Int
    val currentPosition: Long
    val contentPosition: Long
    val duration: Long
    val bufferedPosition: Long
    val bufferedPercentage: Int
    val totalBufferedDuration: Long
    val nextMediaItemIndex: Int
    val previousMediaItemIndex: Int
    val currentAdGroupIndex: Int
    val currentAdIndexInAdGroup: Int
    val isPlayingAd: Boolean
    val isCurrentMediaItemSeekable: Boolean
    val isCurrentMediaItemDynamic: Boolean
    val isCurrentMediaItemLive: Boolean
    val currentLiveOffset: Long
    val contentDuration: Long
    val contentBufferedPosition: Long

    // ---- 可写状态 ----
    var playWhenReady: Boolean

    @get:RepeatMode
    @set:RepeatMode
    var repeatMode: Int
    var shuffleModeEnabled: Boolean
    var volume: Float
    val playbackSpeed: Float
        get() = playbackParameters.speed

    // ---- 队列/播放控制 ----
    fun setMediaItems(mediaItems: List<MediaItem>)
    fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long)
    fun setMediaItem(mediaItem: MediaItem)
    fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long)
    fun addMediaItem(mediaItem: MediaItem)
    fun addMediaItem(index: Int, mediaItem: MediaItem)
    fun addMediaItems(mediaItems: List<MediaItem>)
    fun addMediaItems(index: Int, mediaItems: List<MediaItem>)
    fun removeMediaItem(index: Int)
    fun removeMediaItems(fromIndex: Int, toIndex: Int)
    fun moveMediaItem(currentIndex: Int, newIndex: Int)
    fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int)
    fun clearMediaItems()
    fun getMediaItemAt(index: Int): MediaItem
    fun seekToDefaultPosition()
    fun seekToDefaultPosition(mediaItemIndex: Int)
    fun seekTo(mediaItemIndex: Int, positionMs: Long)
    fun seekTo(positionMs: Long)
    fun seekBack()
    fun seekForward()
    fun seekToNext()
    fun seekToPrevious()
    fun seekToNextMediaItem()
    fun seekToPreviousMediaItem()
    fun seekToNextWindow()
    fun seekToPreviousWindow()
    fun hasNextMediaItem(): Boolean
    fun hasPreviousMediaItem(): Boolean
    fun hasNextWindow(): Boolean
    fun hasPreviousWindow(): Boolean
    fun prepare()
    fun play()
    fun pause()
    fun stop()
    fun release()
    fun setPlaybackParameters(playbackParameters: PlaybackParameters)
    fun setPlaybackSpeed(speed: Float)
    fun setTrackSelectionParameters(parameters: TrackSelectionParameters)
    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)
    fun canAdvertiseSession(): Boolean = false

    /** MediaController parity: custom session commands (notification buttons
     * on Android; queue/menu buttons send TOGGLE_SHUFFLE through here). */
    fun sendCustomCommand(
        command: androidx.media3.session.SessionCommand,
        args: android.os.Bundle
    ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> =
        com.google.common.util.concurrent.Futures.immediateFuture(
            androidx.media3.session.SessionResult(
                androidx.media3.session.SessionResult.RESULT_SUCCESS
            )
        )

    companion object {
        const val STATE_IDLE: Int = 1
        const val STATE_BUFFERING: Int = 2
        const val STATE_READY: Int = 3
        const val STATE_ENDED: Int = 4

        const val REPEAT_MODE_OFF: Int = 0
        const val REPEAT_MODE_ONE: Int = 1
        const val REPEAT_MODE_ALL: Int = 2

        const val PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST: Int = 1
        const val PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS: Int = 2
        const val PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY: Int = 3
        const val PLAY_WHEN_READY_CHANGE_REASON_REMOTE: Int = 4
        const val PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM: Int = 5

        const val DISCONTINUITY_REASON_AUTO_TRANSITION: Int = 0
        const val DISCONTINUITY_REASON_SEEK: Int = 1
        const val DISCONTINUITY_REASON_SEEK_ADJUSTMENT: Int = 2
        const val DISCONTINUITY_REASON_SKIP_PERIOD: Int = 3
        const val DISCONTINUITY_REASON_REMOVE: Int = 4
        const val DISCONTINUITY_REASON_INTERNAL: Int = 5

        const val MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED: Int = 0
        const val MEDIA_ITEM_TRANSITION_REASON_REPEAT: Int = 1
        const val MEDIA_ITEM_TRANSITION_REASON_AUTO: Int = 2
        const val MEDIA_ITEM_TRANSITION_REASON_SEEK: Int = 3

        const val TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED: Int = 0
        const val TIMELINE_CHANGE_REASON_SOURCE_UPDATE: Int = 1

        const val COMMAND_INVALID: Int = -1
        const val COMMAND_PLAY_PAUSE: Int = 2
        const val COMMAND_PREPARE: Int = 3
        const val COMMAND_STOP: Int = 4
        const val COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM: Int = 5
        const val COMMAND_SEEK_TO_DEFAULT_POSITION: Int = 6
        const val COMMAND_SEEK_IN_CURRENT_WINDOW: Int = 5
        const val COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM: Int = 8
        const val COMMAND_SEEK_TO_PREVIOUS: Int = 8
        const val COMMAND_SEEK_TO_NEXT_MEDIA_ITEM: Int = 9
        const val COMMAND_SEEK_TO_NEXT: Int = 9
        const val COMMAND_SEEK_TO_MEDIA_ITEM: Int = 10
        const val COMMAND_SEEK_FORWARD: Int = 11
        const val COMMAND_SEEK_BACK: Int = 12
        const val COMMAND_SET_SPEED_AND_PITCH: Int = 13
        const val COMMAND_SET_SHUFFLE_MODE: Int = 14
        const val COMMAND_SET_REPEAT_MODE: Int = 15
        const val COMMAND_GET_CURRENT_MEDIA_ITEM: Int = 16
        const val COMMAND_GET_TIMELINE: Int = 17
        const val COMMAND_GET_METADATA: Int = 18
        const val COMMAND_SET_PLAYLIST_METADATA: Int = 19
        const val COMMAND_SET_MEDIA_ITEM: Int = 20
        const val COMMAND_CHANGE_MEDIA_ITEMS: Int = 21
        const val COMMAND_GET_TRACKS: Int = 24
        const val COMMAND_GET_AUDIO_ATTRIBUTES: Int = 25
        const val COMMAND_GET_VOLUME: Int = 26
        const val COMMAND_GET_DEVICE_VOLUME: Int = 27
        const val COMMAND_SET_VOLUME: Int = 28
        const val COMMAND_SET_DEVICE_VOLUME: Int = 29
        const val COMMAND_ADJUST_DEVICE_VOLUME: Int = 30
        const val COMMAND_SET_VIDEO_SURFACE: Int = 31
        const val COMMAND_GET_TEXT: Int = 32
        const val COMMAND_SET_TRACK_SELECTION_PARAMETERS: Int = 33
        const val COMMAND_GET_SEEK_BACK_INCREMENT: Int = 34
        const val COMMAND_GET_SEEK_FORWARD_INCREMENT: Int = 35
        const val COMMAND_RELEASE: Int = 36
    }
}
