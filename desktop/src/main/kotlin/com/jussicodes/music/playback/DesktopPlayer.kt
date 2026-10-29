package com.jussicodes.music.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import com.jussicodes.music.constants.audioQualityKey
import com.jussicodes.music.constants.autoSkipNextOnErrorKey
import com.jussicodes.music.desktop.local.LocalLibrary
import com.jussicodes.music.desktop.local.LocalTags
import com.jussicodes.music.extensions.updateMediaItemUri
import com.jussicodes.music.extensions.updateMediaItemUriOfficial
import com.jussicodes.music.utils.NetProxy
import com.jussicodes.music.utils.dataStore
import com.jussicodes.music.utils.enumPreference
import com.jussicodes.music.utils.get
import com.rcmiku.ncmapi.api.player.SongLevel
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.BitstreamErrors
import javazoom.jl.decoder.BitstreamException
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.Header
import javazoom.jl.decoder.SampleBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jflac.FLACDecoder
import org.jflac.PCMProcessor
import org.jflac.metadata.StreamInfo
import org.jflac.util.ByteData
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Desktop playback engine implementing the media3 Player surface the app uses.
 *
 * Pipeline: MediaItem pseudo-URI -> updateMediaItemUri() (identical source
 * selection as the Android PlaybackService: LX bridge / direct sources /
 * unblock / official) -> HTTP stream -> decode (JLayer for MP3, jFLAC for
 * FLAC since 2.0.4) -> PCM -> EQ -> javax.sound.
 *
 * FLAC path (2.0.4): the whole track is decoded into 16-bit PCM (jFLAC is
 * stream-sequential; a lossless track is tens of MB of PCM), then played
 * from a frame offset — seek is sample-accurate, unlike the MP3 path whose
 * seek is byte-estimate based (~1s accuracy). 2.1.2 (#1/#6): decoding is
 * memory-bounded — PCM past 32MB spills to a temp file and playback
 * streams from disk, so a long track cannot OOM the 1GB heap; the decoded
 * PCM is cached (LRU, ≤2 tracks) so seek / repeat-one replay it instead of
 * re-decoding (and, online, re-downloading) the whole track.
 *
 * Known limits (see PORTING.md): formats other than MP3/FLAC report a
 * decoding error (with an official-EXHIGH downgrade retry), no
 * audio-focus / media-session integration. 2.2.0: playback speed is now
 * applied on the PCM path via simple resampling (pitch shifts with
 * speed — see SpeedResampler), and the next track's direct URL is
 * pre-resolved at ~80% progress (L8, resolve only, no audio download).
 */
class DesktopPlayer(private val appContext: Context) : Player {

    private val audioQuality: SongLevel by enumPreference(
        appContext, audioQualityKey, SongLevel.STANDARD
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = CopyOnWriteArrayList<Player.Listener>()
    private val lock = Any()

    private val items = mutableListOf<MediaItem>()
    private var index: Int = -1
    private var shuffleOrder: List<Int> = emptyList()

    @Volatile private var state: Int = Player.STATE_IDLE
    @Volatile private var error: PlaybackException? = null
    @Volatile override var playWhenReady: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            fire { it.onPlayWhenReadyChanged(value, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
            fireEvents()
            if (value) {
                startCurrentLocked()
            } else {
                // 2.1.3（A4-03）：暂停原因落盘。错误停留路径先置 error 再翻
                // 本开关（见 onSessionError），那种情况原因由错误路径自己
                // 记录，这里不抢记成 USER。
                if (error == null) {
                    val reason = PauseDiagnostics.consumePending() ?: PauseDiagnostics.USER
                    PauseDiagnostics.record(
                        reason,
                        "mediaId=${currentMediaItem?.mediaId} pos=${currentPosition}"
                    )
                }
                pauseDecode()
            }
        }

    override var repeatMode: Int = Player.REPEAT_MODE_OFF
        set(value) {
            if (field == value) return
            field = value
            fire { it.onRepeatModeChanged(value) }
            fireEvents()
        }

    override var shuffleModeEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            rebuildShuffleOrder()
            fire { it.onShuffleModeEnabledChanged(value) }
            fireEvents()
        }

    override var volume: Float = 1f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (field == v) return
            field = v
            applyVolume()
            fire { it.onVolumeChanged(v) }
            fireEvents()
        }

    private var playbackParams: PlaybackParameters = PlaybackParameters.DEFAULT

    // --- decode session ---
    @Volatile private var generation: Int = 0
    @Volatile private var sessionRunning: Boolean = false
    @Volatile private var activeItemId: String? = null
    @Volatile private var line: SourceDataLine? = null
    @Volatile private var stream: InputStream? = null
    @Volatile private var pausedFlag: Boolean = false
    @Volatile private var framesPlayed: Long = 0
    @Volatile private var seekBaseMs: Long = 0
    @Volatile private var msPerFrame: Float = 26.12f
    @Volatile private var durationMs: Long = C.TIME_UNSET
    @Volatile private var contentBytes: Long = -1
    @Volatile private var playingFlag: Boolean = false
    // 2.0.5（A5）：直链缓存加 TTL。此前只在切音源时清空，长会话里重播
    // 旧歌会命中已过期的签名直链直接报错；现每条缓存 20 分钟过期失效。
    private val resolvedUrls = mutableMapOf<String, Pair<String, Long>>()

    // 2.1.2（#6）：FLAC 已解码 PCM 缓存。seek 与单曲循环重走整条会话时
    // 命中即免重解码（在线源连重下载一并省掉）。载体就是 #1 的有界解码
    // 产物：小曲在内存（≤阈值、无二次拷贝）、长曲是临时文件流式读，
    // 不再引入新的无上限内存驻留。键与 resolvedUrls 同口径（见
    // flacPcmCacheKey / localFlacPcmCacheKey），切音源/切音质天然不命中
    // 旧条目；容量与清理时机见 putPcmCache / clearPcmCache。
    private val pcmCacheLock = Any()
    private val pcmCache =
        LinkedHashMap<String, CachedFlacPcm>(4, 0.75f, true)

    private val eqProcessor = com.jussicodes.music.playback.audio.EqProcessor()
    private val reverbProcessor = com.jussicodes.music.playback.audio.ReverbProcessor()
    private val pcmEffects = com.jussicodes.music.playback.audio.PcmEffects()
    // 2.2.0（L5）：变速重采样器（speed==1 直通）。只在解码线程内用。
    private val speedResampler = com.jussicodes.music.playback.audio.SpeedResampler()

    // 2.0.5（A1）：「播放失败自动跳下一首」开关的运行时镜像（DataStore
    // 收集器维护）。此前设置页只写键、桌面引擎从不读，失败无条件跳歌。
    @Volatile private var autoSkipOnError: Boolean = false

    // 2.0.5（B1）：连续失败熔断计数。自动跳歌连败达上限后停播报错，
    // 防「全队列不可播 + 循环全部」时无限轮转白烧流量。
    @Volatile private var consecutiveFailures: Int = 0

    // 2.1.4：MP3 中途 BitstreamException 自动续播已用标记——记已为哪个
    // 条目用过自动续播。同一条目同一轮播放只许一次；切歌（seekInternal
    // 跨条目）或该条目成功解出首帧（decodeLoop 建线缆处，与
    // consecutiveFailures 同落点）时清零，防坏文件反复重连死循环。
    @Volatile private var bitstreamRetryItemId: String? = null

    // 2.2.0（A5-17）：尾部断流续播已用标记——与 bitstreamRetryItemId
    // 独立的另一份额度：尾部（剩余 ≤10s）断流先重取新链从断点把尾巴
    // 补齐播完，失败才回落 2.1.4 的「按播完切歌」。清零落点与中途
    // 续播标记一致（切歌 / 成功解出首帧）。
    @Volatile private var tailRetryItemId: String? = null

    // 2.2.0（L8）：已为哪个条目触发过下一首直链预解析（每曲一次，
    // 切歌清零）。只预解析进 resolvedUrls、不下载音频本体。
    @Volatile private var prefetchItemId: String? = null

    /** 当前解码线程（淡出等待要避开它，见 stopDecodeLocked）。 */
    @Volatile private var decodeThread: Thread? = null

    init {
        // 2.1.3（A4-07）：清扫上一进程残留的 FLAC 溢写临时文件（崩溃/强杀
        // 后 deleteOnExit 来不及执行）。只删修改时间早于本进程启动时刻
        // 的文件——本进程正在用的溢写文件 mtime 必然不早于进程启动，
        // 同进程多实例/探针也不会误删。
        runCatching {
            val tmpDir = File(System.getProperty("java.io.tmpdir"))
            tmpDir.listFiles { f ->
                f.name.startsWith(FLAC_SPILL_PREFIX) && f.name.endsWith(".pcm") &&
                    f.lastModified() < PROCESS_START_MS
            }?.forEach { f -> runCatching { f.delete() } }
        }
        scope.launch {
            runCatching {
                appContext.dataStore.data.collect { prefs ->
                    autoSkipOnError = prefs[autoSkipNextOnErrorKey] ?: false
                }
            }
        }
        // 2.0.5（C2）：播放历史云端上报（与安卓 PlaybackService 同一套
        // reporter/session，只在登录态真正发报，未登录静默）。
        runCatching { DesktopPlaybackHistory.attach(this, appContext) }
        // 2.0.2：换源重载总线（安卓版由 PlaybackService 消费，桌面版此前无人消费，
        // 切音源后 resolvedUrls 一直命中旧直链——P0 修复）。收到信号即清缓存并
        // 以当前进度重解析当前曲，与 AudioSourceSwitcher.apply 的语义对齐。
        scope.launch {
            PlaybackReloadBus.requests.collect {
                synchronized(lock) { resolvedUrls.clear() }
                // 音源已切换：旧音源的 PCM 虽因键不匹配不会被命中，也一并
                // 清掉释放内存/临时文件，不留陈账。
                clearPcmCache()
                val idx = index
                val count = synchronized(lock) { items.size }
                if (idx >= 0 && idx < count) {
                    println("[DesktopPlayer] 音源切换重载：清空直链缓存并重解析 index=$idx")
                    seekInternal(idx, currentPosition, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                }
            }
        }
    }

    /** 直链缓存键：与安卓 PlaybackService 的 cacheKey 同口径（曲目+音质+音源代号）。 */
    private fun resolveCacheKey(item: MediaItem): String =
        "${item.mediaId}|${audioQuality.value}|${PlaybackReloadBus.sourceTag}"

    /**
     * FLAC PCM 缓存键（在线）：resolveCacheKey（曲目+音质+音源）+ URL
     * 去签名指纹。2.1.3（A4-11）：旧实现拼完整 resolvedUrl——签名直链
     * 20 分钟 TTL 过期换签后键必失配，长曲 seek/单曲循环整曲重下载+
     * 重解码（数十秒无声）。指纹只取 host + 文件名：NCM CDN 的签名在
     * 查询串与路径中的时间戳段里，文件名本身是内容寻址、换签不变；
     * 跨曲碰撞由键里的 mediaId 兜底，不会串曲。本地键见
     * [localFlacPcmCacheKey]。
     */
    private fun flacPcmCacheKey(item: MediaItem, resolvedUrl: String): String =
        "${resolveCacheKey(item)}|${urlFingerprint(resolvedUrl)}"

    /** URL 去签名指纹：host + 末段文件名（去掉查询串/锚点/时间戳路径段）。 */
    private fun urlFingerprint(url: String): String {
        val noQuery = url.substringBefore('?').substringBefore('#')
        val host = runCatching { java.net.URL(noQuery).host }.getOrNull().orEmpty()
        val fileName = noQuery.substringAfterLast('/')
        return "$host/$fileName"
    }

    /** FLAC PCM 缓存键（本地）：mediaId + 路径 + 长度 + 修改时间，文件被替换即失效。 */
    private fun localFlacPcmCacheKey(mediaId: String, file: File): String =
        "$mediaId|local|${file.absolutePath}|${file.length()}|${file.lastModified()}"

    // ---------- Player read surface ----------
    override val currentTimeline: Timeline get() = Timeline.EMPTY
    override val currentTracks: Tracks get() = Tracks()
    override val currentMediaItemIndex: Int get() = index
    override val currentPeriodIndex: Int get() = if (index < 0) 0 else index
    override val currentMediaItem: MediaItem?
        get() = synchronized(lock) { items.getOrNull(index) }
    override val mediaMetadata: MediaMetadata
        get() = currentMediaItem?.mediaMetadata ?: MediaMetadata.EMPTY
    override val playlistMetadata: MediaMetadata get() = MediaMetadata.EMPTY
    override val isLoading: Boolean get() = state == Player.STATE_BUFFERING
    override val availableCommands: Player.Commands
        get() = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA, Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_GET_VOLUME, Player.COMMAND_SET_VOLUME,
                Player.COMMAND_RELEASE
            ).build()
    override val trackSelectionParameters: TrackSelectionParameters get() = TrackSelectionParameters()
    override val playbackState: Int get() = state
    override val playbackSuppressionReason: Int get() = 0
    override val isPlaying: Boolean get() = playingFlag
    override val isDeviceMuted: Boolean get() = false
    override val playerError: PlaybackException? get() = error
    override val playbackParameters: PlaybackParameters get() = playbackParams
    override val seekBackIncrement: Long get() = 10_000
    override val seekForwardIncrement: Long get() = 10_000
    override val maxSeekToPreviousPosition: Long get() = 3_000
    override val audioAttributes: AudioAttributes get() = AudioAttributes.DEFAULT
    override val deviceInfo: DeviceInfo get() = DeviceInfo.DEFAULT
    override val deviceVolume: Int get() = (volume * 100).toInt()
    override val videoSize: VideoSize get() = VideoSize.UNKNOWN
    override val currentCues: CueGroup get() = CueGroup.EMPTY
    override val mediaItemCount: Int get() = synchronized(lock) { items.size }
    override val currentPosition: Long
        get() = seekBaseMs + (framesPlayed * msPerFrame).toLong()
    override val contentPosition: Long get() = currentPosition
    override val duration: Long get() = durationMs
    override val bufferedPosition: Long
        get() = if (durationMs == C.TIME_UNSET) currentPosition else durationMs
    override val bufferedPercentage: Int
        get() = if (durationMs > 0) min(100, (bufferedPosition * 100 / durationMs).toInt()) else 0
    override val totalBufferedDuration: Long get() = 0
    override val nextMediaItemIndex: Int
        get() = computeNextIndex(index, forAuto = false) ?: C.INDEX_UNSET
    override val previousMediaItemIndex: Int
        get() = computePreviousIndex(index) ?: C.INDEX_UNSET
    override val currentAdGroupIndex: Int get() = C.INDEX_UNSET
    override val currentAdIndexInAdGroup: Int get() = C.INDEX_UNSET
    override val isPlayingAd: Boolean get() = false
    override val isCurrentMediaItemSeekable: Boolean get() = durationMs != C.TIME_UNSET
    override val isCurrentMediaItemDynamic: Boolean get() = false
    override val isCurrentMediaItemLive: Boolean get() = false
    override val currentLiveOffset: Long get() = C.TIME_UNSET
    override val contentDuration: Long get() = durationMs
    override val contentBufferedPosition: Long get() = bufferedPosition

    // ---------- queue mutation ----------
    override fun setMediaItems(mediaItems: List<MediaItem>) =
        setMediaItems(mediaItems, C.INDEX_UNSET, C.TIME_UNSET)

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        synchronized(lock) {
            stopDecodeLocked()
            // 切队列即弃旧 PCM 缓存（#6 清理时机）：键虽含音源身份不会误
            // 命中，但旧队列的临时文件/内存不应跨队列滞留。
            clearPcmCache()
            items.clear()
            items.addAll(mediaItems)
            index = when {
                items.isEmpty() -> -1
                startIndex == C.INDEX_UNSET -> 0
                else -> startIndex.coerceIn(0, items.size - 1)
            }
            rebuildShuffleOrder()
            seekBaseMs = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs
            framesPlayed = 0
            durationMs = C.TIME_UNSET
            error = null
            setStateLocked(Player.STATE_IDLE)
        }
        fire { it.onTimelineChanged(currentTimeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) }
        fire { it.onMediaItemTransition(currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) }
        fire { it.onMediaMetadataChanged(mediaMetadata) }
        fireEvents()
        if (playWhenReady) startCurrentLocked()
    }

    override fun setMediaItem(mediaItem: MediaItem) = setMediaItems(listOf(mediaItem))

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) =
        setMediaItems(listOf(mediaItem), 0, startPositionMs)

    override fun addMediaItem(mediaItem: MediaItem) = addMediaItems(listOf(mediaItem))

    override fun addMediaItem(index: Int, mediaItem: MediaItem) =
        addMediaItems(index, listOf(mediaItem))

    override fun addMediaItems(mediaItems: List<MediaItem>) {
        synchronized(lock) {
            val wasEmpty = items.isEmpty()
            items.addAll(mediaItems)
            if (wasEmpty && items.isNotEmpty()) index = 0
            rebuildShuffleOrder()
        }
        fireTimeline()
    }

    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) {
        synchronized(lock) {
            val at = index.coerceIn(0, items.size)
            items.addAll(at, mediaItems)
            if (this.index >= at) this.index += mediaItems.size
            rebuildShuffleOrder()
        }
        fireTimeline()
    }

    override fun removeMediaItem(index: Int) = removeMediaItems(index, index + 1)

    override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
        var removedCurrent = false
        synchronized(lock) {
            val from = fromIndex.coerceIn(0, items.size)
            val to = toIndex.coerceIn(from, items.size)
            if (from >= to) return
            val removed = to - from
            removedCurrent = this.index in from until to
            repeat(removed) { items.removeAt(from) }
            when {
                items.isEmpty() -> {
                    this.index = -1
                    stopDecodeLocked()
                    setStateLocked(Player.STATE_IDLE)
                }
                this.index >= to -> this.index -= removed
                removedCurrent -> this.index = min(from, items.size - 1)
            }
            rebuildShuffleOrder()
        }
        fireTimeline()
        if (removedCurrent) {
            fire { it.onMediaItemTransition(currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) }
            fireEvents()
            if (playWhenReady) startCurrentLocked()
        }
    }

    override fun moveMediaItem(currentIndex: Int, newIndex: Int) =
        moveMediaItems(currentIndex, currentIndex + 1, newIndex)

    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) {
        synchronized(lock) {
            val from = fromIndex.coerceIn(0, items.size)
            val to = toIndex.coerceIn(from, items.size)
            if (from >= to) return
            val moved = items.subList(from, to).toList()
            repeat(to - from) { items.removeAt(from) }
            val dest = newIndex.coerceIn(0, items.size)
            items.addAll(dest, moved)
            index = when (index) {
                in from until to -> dest + (index - from)
                in to..(dest + moved.size) -> index - moved.size
                in dest until from -> index + moved.size
                else -> index
            }
            rebuildShuffleOrder()
        }
        fireTimeline()
    }

    override fun clearMediaItems() {
        synchronized(lock) {
            stopDecodeLocked()
            clearPcmCache()
            items.clear()
            index = -1
            setStateLocked(Player.STATE_IDLE)
        }
        fireTimeline()
        fire { it.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) }
        fireEvents()
    }

    override fun getMediaItemAt(index: Int): MediaItem = synchronized(lock) { items[index] }

    // ---------- seeking ----------
    override fun seekToDefaultPosition() {
        if (index >= 0) seekInternal(index, 0, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) =
        seekInternal(mediaItemIndex, 0, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) =
        seekInternal(mediaItemIndex, positionMs, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)

    override fun seekTo(positionMs: Long) {
        if (index >= 0) seekInternal(index, positionMs, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
    }

    override fun seekBack() = seekTo(max(0, currentPosition - seekBackIncrement))

    override fun seekForward() {
        val target = currentPosition + seekForwardIncrement
        seekTo(if (durationMs == C.TIME_UNSET) target else min(target, durationMs))
    }

    override fun seekToNext() = seekToNextMediaItem()

    override fun seekToPrevious() {
        if (currentPosition > maxSeekToPreviousPosition) {
            seekTo(0)
            return
        }
        seekToPreviousMediaItem()
    }

    override fun seekToNextMediaItem() {
        val next = computeNextIndex(index, forAuto = false) ?: return
        seekInternal(next, 0, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
    }

    override fun seekToPreviousMediaItem() {
        val prev = computePreviousIndex(index) ?: return
        seekInternal(prev, 0, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
    }

    override fun seekToNextWindow() = seekToNextMediaItem()
    override fun seekToPreviousWindow() = seekToPreviousMediaItem()

    override fun hasNextMediaItem(): Boolean = computeNextIndex(index, forAuto = false) != null
    override fun hasPreviousMediaItem(): Boolean = computePreviousIndex(index) != null
    override fun hasNextWindow(): Boolean = hasNextMediaItem()
    override fun hasPreviousWindow(): Boolean = hasPreviousMediaItem()

    // ---------- transport ----------
    override fun prepare() {
        if (items.isEmpty() || index < 0) return
        if (state == Player.STATE_IDLE) startCurrentLocked()
    }

    override fun play() {
        // 2.1.3（A4-01/A4-02）：playWhenReady 残留 true 时（错误停留、播完
        // 落 ENDED）旧实现只赋值 true，setter「值未变早退」，会话已死却
        // 无人重启——按播放字面无反应（audit4 双案实证）。现：ENDED 先
        // 回到曲首，错误态/ENDED 一律强制停旧会话并重启当前曲；位置在
        // onSessionError 停留时已归一到出错点，重试即从出错点续播。
        val restartNeeded = state == Player.STATE_ENDED ||
            (error != null && !sessionRunning)
        if (state == Player.STATE_ENDED) {
            synchronized(lock) {
                seekBaseMs = 0
                framesPlayed = 0
            }
        }
        playWhenReady = true
        if (restartNeeded && currentMediaItem != null) {
            synchronized(lock) {
                error = null
                stopDecodeLocked()
            }
            startCurrentLocked()
        }
    }

    override fun pause() {
        playWhenReady = false
    }

    override fun stop() {
        synchronized(lock) {
            stopDecodeLocked()
            seekBaseMs = 0
            framesPlayed = 0
            setStateLocked(Player.STATE_IDLE)
        }
        fireEvents()
    }

    override fun release() {
        synchronized(lock) {
            stopDecodeLocked()
            listeners.clear()
        }
        // 退出清理 PCM 缓存与临时文件（#6），不给磁盘留残档。
        clearPcmCache()
        scope.cancel()
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        playbackParams = playbackParameters
        fire { it.onPlaybackParametersChanged(playbackParameters) }
        fireEvents()
    }

    override fun setPlaybackSpeed(speed: Float) {
        // 2.2.0（L5）：倍速档位钳制 0.5×–2.0×（UI 档位与引擎上限一致）。
        setPlaybackParameters(
            PlaybackParameters(speed.coerceIn(0.5f, 2f), playbackParams.pitch)
        )
    }

    override fun setTrackSelectionParameters(parameters: TrackSelectionParameters) {
        fire { it.onTrackSelectionParametersChanged(parameters) }
        fireEvents()
    }

    /**
     * Mirrors PlaybackService.onCustomCommand: shuffle toggles the player
     * directly; desktop-lyric toggles through DesktopLyricManager (a no-op
     * end state on desktop); like is a notification-only action on Android
     * whose UI equivalent lives in the player UI itself, so it succeeds
     * without side effects here.
     */
    override fun sendCustomCommand(
        command: androidx.media3.session.SessionCommand,
        args: android.os.Bundle
    ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> {
        when (command.customAction) {
            com.jussicodes.music.constants.MediaSessionConstants.ACTION_TOGGLE_SHUFFLE -> {
                shuffleModeEnabled = !shuffleModeEnabled
            }
            com.jussicodes.music.constants.MediaSessionConstants.ACTION_TOGGLE_DESKTOP_LYRIC -> {
                scope.launch {
                    val enabled = appContext.dataStore.get(
                        com.jussicodes.music.constants.desktopLyricEnabledKey, false
                    )
                    com.jussicodes.music.lyric.DesktopLyricManager.setEnabled(
                        context = appContext,
                        enabled = !enabled,
                        requestPermissionIfNeeded = !enabled
                    )
                }
            }
        }
        return com.google.common.util.concurrent.Futures.immediateFuture(
            androidx.media3.session.SessionResult(
                androidx.media3.session.SessionResult.RESULT_SUCCESS
            )
        )
    }

    override fun addListener(listener: Player.Listener) {
        listeners.addIfAbsent(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
    }

    // ---------- internals ----------

    private fun fire(block: (Player.Listener) -> Unit) {
        for (l in listeners) runCatching { block(l) }
    }

    private fun fireEvents() = fire { it.onEvents(this, Player.Events()) }

    private fun fireTimeline() {
        fire { it.onTimelineChanged(currentTimeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) }
        fireEvents()
    }

    private fun setStateLocked(newState: Int) {
        if (state == newState) return
        state = newState
        fire { it.onPlaybackStateChanged(newState) }
        fire { it.onIsLoadingChanged(isLoading) }
    }

    private fun setPlayingLocked(playing: Boolean) {
        if (playingFlag == playing) return
        playingFlag = playing
        fire { it.onIsPlayingChanged(playing) }
    }

    private fun rebuildShuffleOrder() {
        val n = items.size
        shuffleOrder = if (!shuffleModeEnabled || n == 0) {
            (0 until n).toList()
        } else {
            val rest = (0 until n).filter { it != index }.shuffled(Random(System.nanoTime()))
            listOf(index.coerceAtLeast(0)) + rest
        }
    }

    private fun orderPosition(ofIndex: Int): Int =
        if (shuffleModeEnabled) shuffleOrder.indexOf(ofIndex) else ofIndex

    private fun computeNextIndex(from: Int, forAuto: Boolean): Int? {
        if (items.isEmpty() || from < 0) return null
        if (forAuto && repeatMode == Player.REPEAT_MODE_ONE) return from
        val pos = orderPosition(from)
        val order = if (shuffleModeEnabled) shuffleOrder else items.indices.toList()
        if (pos in 0 until order.size - 1) return order[pos + 1]
        if (repeatMode == Player.REPEAT_MODE_ALL) return order.firstOrNull()
        return null
    }

    private fun computePreviousIndex(from: Int): Int? {
        if (items.isEmpty() || from < 0) return null
        val pos = orderPosition(from)
        val order = if (shuffleModeEnabled) shuffleOrder else items.indices.toList()
        if (pos > 0) return order[pos - 1]
        if (repeatMode == Player.REPEAT_MODE_ALL) return order.lastOrNull()
        return null
    }

    private fun seekInternal(targetIndex: Int, positionMs: Long, reason: Int) {
        val oldPos = Player.PositionInfo(index, currentMediaItem, positionMs = currentPosition)
        var transitioned = false
        synchronized(lock) {
            if (targetIndex !in items.indices) return
            transitioned = targetIndex != index
            if (transitioned) {
                stopDecodeLocked()
                index = targetIndex
                error = null
                durationMs = C.TIME_UNSET
                // 2.1.4：切歌清零自动续播标记（新条目可再用一次）。
                bitstreamRetryItemId = null
                // 2.2.0（A5-17/L8）：尾部续播标记与预读标记同清零。
                tailRetryItemId = null
                prefetchItemId = null
            }
            seekBaseMs = max(0, positionMs)
            framesPlayed = 0
        }
        if (transitioned) {
            // 2.2.0（A5-05）：跨条目切换回收超大 PCM 条目（单条即超盘限
            // 的只陪一首，见 evictSessionOnlyPcmCache）。
            evictSessionOnlyPcmCache()
            fire { it.onMediaItemTransition(currentMediaItem, reason) }
            fire { it.onMediaMetadataChanged(mediaMetadata) }
        }
        fire {
            it.onPositionDiscontinuity(
                oldPos,
                Player.PositionInfo(targetIndex, currentMediaItem, positionMs = positionMs),
                Player.DISCONTINUITY_REASON_SEEK
            )
        }
        fire { it.onSeekProcessed() }
        fireEvents()
        // A seek always restarts the stream at the target position (the new
        // session parks immediately when playWhenReady is false).
        synchronized(lock) { stopDecodeLocked() }
        startCurrentLocked()
    }

    private fun pauseDecode() {
        pausedFlag = true
        synchronized(lock) { setPlayingLocked(false) }
        fireEvents()
    }

    /**
     * Starts decoding of the current item, or resumes the parked session if
     * one is already running for it. A fresh session starts at the current
     * position counters (seekBaseMs + framesPlayed), which callers set up
     * before invoking this.
     */
    private fun startCurrentLocked() {
        // 新会话启动即清常驻错误条（A4-03）：重试/切歌/seek/重播都经此。
        PlaybackStatus.clear()
        val item = currentMediaItem ?: return
        if (sessionRunning && activeItemId == item.mediaId) {
            pausedFlag = !playWhenReady
            if (playWhenReady) {
                synchronized(lock) { setPlayingLocked(true) }
                fireEvents()
            }
            return
        }
        val gen = ++generation
        activeItemId = item.mediaId
        pausedFlag = !playWhenReady
        setStateLocked(Player.STATE_BUFFERING)
        scope.launch { runDecodeSession(gen, item) }
    }

    private fun stopDecodeLocked() {
        // 2.0.5（C10）：切歌/停止淡出——有声会话先等 PCM 增益落下再关线缆，
        // 避免硬切爆音。解码线程自己触发的收尾（自然播完后）不等待：旧流
        // 已 drain 完毕，没有可淡的尾音，还白等一拍。
        // 2.2.0（A5-16）：等待上限 280ms → 120ms。本函数常由 UI 线程的
        // 切歌/seek 调用，同步等待即 UI 阻塞；淡出斜坡本身约 90ms
        // （PcmEffects FADE_STEP/步进节拍），120ms 仍能等增益落完，
        // 最坏阻塞减半以上。真机若切歌爆音再议（见 PORTING §7.20）。
        if (playingFlag && sessionRunning && line != null &&
            Thread.currentThread() !== decodeThread
        ) {
            pcmEffects.fadeOut()
            val deadline = System.currentTimeMillis() + 120
            while (pcmEffects.gain > 0.02f && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(10) } catch (_: InterruptedException) { break }
            }
        }
        generation++
        sessionRunning = false
        activeItemId = null
        try { stream?.close() } catch (_: Exception) { }
        stream = null
        try { line?.stop() } catch (_: Exception) { }
        try { line?.close() } catch (_: Exception) { }
        line = null
        setPlayingLocked(false)
    }

    private suspend fun runDecodeSession(gen: Int, item: MediaItem) {
        // 2.1.0：本地文件分支。mediaId 带 local: 前缀（或 file:// uri）的
        // 条目直接解码本地文件，不走在线取链级联、不读直链缓存、格式
        // 不支持也不降级到官方直达（本地歌没有官方链可降）。
        val localFile = localFileOf(item)
        if (localFile != null) {
            try {
                decodeLocalFile(gen, item.mediaId, localFile, seekBaseMs)
            } catch (e: Throwable) {
                // 2.1.2（#1）：捕获面从 Exception 扩到 Throwable——解码路径
                // 的 OutOfMemoryError 等 Error 必须在本层转成播放错误走
                // onSessionError，绝不许逃逸到协程/UI 线程崩进程。playFlacPcm
                // 已把 FLAC 路径的 Error 包成 DecodeFailedException，这里
                // 再兜一层裸 Error（如 MP3/WAV 路径分配失败）。
                if (gen != generation) return
                // 2.2.0（A5-17）：本地尾部断流直接回落 2.1.4 行为——本地
                // 源是确定性的，没有「重取新链」可言（同文件同偏移重读
                // 结果相同），时长吸附到实际位置后走自然结束，不报错。
                if (e is Mp3TailStreamException) {
                    val pos = currentPosition
                    if (durationMs != C.TIME_UNSET && durationMs > pos) {
                        durationMs = pos
                    }
                    onTrackEnded(gen)
                    return
                }
                val failure: Throwable = if (e is Error && e !is DecodeFailedException) {
                    DecodeFailedException(e.message ?: "解码内存不足", e)
                } else {
                    e
                }
                val code = when (failure) {
                    is UnsupportedFormatException ->
                        PlaybackException.ERROR_CODE_PARSER_CONTAINER_UNSUPPORTED
                    is AudioDeviceException ->
                        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
                    is DecodeFailedException ->
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                    // 2.1.4：解码流异常单列解码类（与在线分支同口径），
                    // 不再误报网络类。本地文件损坏是确定性的，不走自动
                    // 续播（续播只在在线分支），停留分支按解码失败报错。
                    is BitstreamException ->
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                    else ->
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                }
                if (failure is AudioDeviceException) {
                    PlaybackToast.fail("音频输出设备打开失败：${failure.message}（请检查系统默认输出设备）")
                } else {
                    PlaybackToast.fail("本地文件播放失败：${failure.message?.take(80) ?: "未知错误"}")
                }
                onSessionError(
                    gen,
                    PlaybackException(failure.message ?: "本地文件播放失败", failure, code),
                    retryableAdvance =
                        code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                            failure is DecodeFailedException
                )
            }
            return
        }
        var lastError: PlaybackException? = null
        // URL resolution with one retry (mirrors the Android service's
        // network-error recovery at engine level; PlayerStateImpl deliberately
        // ignores retryable errors, so recovery must happen here).
        val cacheKey = resolveCacheKey(item)
        var url: String? = synchronized(lock) {
            val cached = resolvedUrls[cacheKey]
            if (cached != null &&
                System.currentTimeMillis() - cached.second < RESOLVED_URL_TTL_MS
            ) {
                cached.first
            } else {
                if (cached != null) resolvedUrls.remove(cacheKey)
                null
            }
        }
        if (url == null) {
            var attempt = 0
            while (attempt < 2 && url == null) {
                attempt++
                url = runCatching {
                    val uri = item.uri ?: return@runCatching null
                    updateMediaItemUri(uri, audioQuality)?.toString()
                }.getOrNull()
                if (url == null && attempt < 2) {
                    try { Thread.sleep(1200) } catch (_: InterruptedException) { }
                }
            }
            if (url == null) {
                lastError = PlaybackException(
                    "无法解析播放地址", null, PlaybackException.ERROR_CODE_REMOTE_ERROR
                )
            } else {
                synchronized(lock) {
                    resolvedUrls[cacheKey] = url to System.currentTimeMillis()
                }
            }
        }
        if (gen != generation) return
        if (url == null) {
            // 2.0.3：取链终局失败必须可见——把级联步骤链拼进 Toast，不许静默跳歌。
            // 2.0.5（A1）：文案不再预告「已跳下一首」——是否跳歌由
            // onSessionError 按 autoSkipNextOnError 开关决定，跳了它会另行提示。
            val trace = com.rcmiku.ncmapi.api.player.ResolveTrace.summary()
            PlaybackToast.fail(
                "播放失败：取链未成功${if (trace.isNotBlank()) "（$trace）" else ""}"
            )
            onSessionError(gen, lastError!!, retryableAdvance = true)
            return
        }
        var streamError: Throwable? = null
        try {
            decodeStream(gen, url, seekBaseMs, flacPcmCacheKey(item, url))
        } catch (t: Throwable) {
            // 2.1.2（#1）：同本地分支——捕获面扩到 Throwable，Error（至少
            // OutOfMemoryError）在本层落成播放错误，不许逃逸崩进程。
            if (gen != generation) return
            streamError = t
        }
        // 2.1.4：MP3 中途解码流异常先自动续播一次。尾部异常已在
        // decodeLoop 内吞掉走自然结束，逃逸到这里的 BitstreamException
        // 都是离结尾 >10s 的中途断流（STREAM_ERROR 本质是底层流
        // IOException，常见于旧签名链失效/CDN 中途复位）。做法：位置
        // 归一到出错点（与 2.1.3 停留分支同语义）、绕直链缓存重取一次
        // 新链、同 gen 重启 decodeStream。同一条目只许一次（标记在切歌
        // 或成功解出首帧时清零），续播再失败才落下方既有错误处理。
        if (streamError is BitstreamException &&
            bitstreamRetryItemId != item.mediaId
        ) {
            bitstreamRetryItemId = item.mediaId
            synchronized(lock) {
                seekBaseMs = currentPosition
                framesPlayed = 0
            }
            val freshUrl = runCatching {
                val uri = item.uri ?: return@runCatching null
                updateMediaItemUri(uri, audioQuality)?.toString()
            }.getOrNull()
            val retryUrl = freshUrl?.takeIf { it.isNotBlank() } ?: url
            if (retryUrl != url) {
                synchronized(lock) {
                    resolvedUrls[cacheKey] = retryUrl to System.currentTimeMillis()
                }
            }
            PlaybackToast.show("播放中断，正在从出错点自动续播")
            streamError = try {
                decodeStream(gen, retryUrl, seekBaseMs, flacPcmCacheKey(item, retryUrl))
                null
            } catch (t2: Throwable) {
                if (gen != generation) return
                t2
            }
        }
        // 2.2.0（A5-17，老板拍板「完整播放完成才切」）：尾部断流先续播
        // 一次、失败才回落。2.1.4 的 decodeLoop 就地吞尾部异常（最后
        // ≤10s 直接没声切歌）改为抛 Mp3TailStreamException 到这里：用
        // 独立额度（tailRetryItemId，不占中途续播计数）绕直链缓存重取
        // 新链、从断点续播把尾巴补齐，播到真正 EOF 才在 decodeLoop 内
        // 走自然结束。只有尾部续播也失败（取链失败/续流立刻再断/
        // 服务端无更多数据到立刻 EOF 后又报错）才回落 2.1.4 行为：
        // 时长吸附到实际位置 + onTrackEnded 按播完切歌，不落错误。
        val tailError = streamError
        if (tailError is Mp3TailStreamException) {
            var endedNaturally = false
            if (tailRetryItemId != item.mediaId) {
                tailRetryItemId = item.mediaId
                val resumePos: Long
                synchronized(lock) {
                    seekBaseMs = currentPosition
                    framesPlayed = 0
                    resumePos = seekBaseMs
                }
                val freshUrl = runCatching {
                    val uri = item.uri ?: return@runCatching null
                    updateMediaItemUri(uri, audioQuality)?.toString()
                }.getOrNull()
                val retryUrl = freshUrl?.takeIf { it.isNotBlank() }
                if (retryUrl != null) {
                    if (retryUrl != url) {
                        synchronized(lock) {
                            resolvedUrls[cacheKey] = retryUrl to System.currentTimeMillis()
                        }
                    }
                    val resumed = try {
                        decodeStream(gen, retryUrl, resumePos, flacPcmCacheKey(item, retryUrl))
                        true
                    } catch (t2: Throwable) {
                        if (gen != generation) return
                        false
                    }
                    if (gen != generation) return
                    if (resumed) {
                        // 续播流已在 decodeLoop 内走完自然结束
                        // （onTrackEnded 已处理切歌/结束态）。
                        PauseDiagnostics.record(
                            PauseDiagnostics.TAIL_RESUME_OK,
                            "mediaId=${item.mediaId} resumePos=$resumePos"
                        )
                        endedNaturally = true
                    }
                }
            }
            if (!endedNaturally && gen == generation) {
                val pos = currentPosition
                if (durationMs != C.TIME_UNSET && durationMs > pos) {
                    durationMs = pos
                }
                onTrackEnded(gen)
                // 记在 onTrackEnded 之后：与成功路径（TRACK_END 在
                // decodeLoop 内先记、TAIL_RESUME_OK 后记）口径一致，
                // lastReason 最终指向本次尾部处置结论。
                PauseDiagnostics.record(
                    PauseDiagnostics.TAIL_RESUME_FALLBACK,
                    "mediaId=${item.mediaId} pos=$pos dur=$durationMs"
                )
            }
            return
        }
        val firstError = streamError
        if (firstError != null) {
            val e: Throwable = firstError
            var failure: Throwable = if (e is Error && e !is DecodeFailedException) {
                DecodeFailedException(e.message ?: "解码内存不足", e)
            } else {
                e
            }
            var handled = false
            // 2.0.4：格式不支持/解码失败 → 真降级。2.0.3 的降级用 EXHIGH
            // 重走整条取链级联，又命中同源同一条 FLAC，是假降级（真机实测
            // 「降音质取链也失败」）。本版改走 PlayerApi.songPlayUrlOfficial
            // 官方直达（绕级联、不读直链缓存）指定 EXHIGH 取链，并校验返回
            // 非 FLAC 才采用。触发面也不再限于 FLAC 直链：FLAC 解码坏文件、
            // 直连源回的 M4A 等其它解不了的格式同样走这条兜底。
            if (e is UnsupportedFormatException) {
                val downgraded = runCatching {
                    val uri = item.uri ?: return@runCatching null
                    updateMediaItemUriOfficial(uri, SongLevel.EXHIGH)?.toString()
                }.getOrNull()
                if (!downgraded.isNullOrBlank() && downgraded != url && !isFlacUrl(downgraded)) {
                    PlaybackToast.show("当前格式播放失败，已自动降为高音质播放")
                    try {
                        decodeStream(gen, downgraded, seekBaseMs)
                        handled = true
                    } catch (e2: Throwable) {
                        if (gen != generation) return
                        failure = if (e2 is Error && e2 !is DecodeFailedException) {
                            DecodeFailedException(e2.message ?: "解码内存不足", e2)
                        } else {
                            e2
                        }
                    }
                } else {
                    PlaybackToast.fail("当前格式暂不支持，官方高音质直达取链也失败")
                }
            }
            if (!handled) {
                val code = when (failure) {
                    is UnsupportedFormatException ->
                        PlaybackException.ERROR_CODE_PARSER_CONTAINER_UNSUPPORTED
                    // 音频设备打不开不是网络问题：不自动跳歌（跳了也一样响不了），
                    // 明确 Toast 让用户查默认输出设备。
                    is AudioDeviceException ->
                        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
                    // 解码期 Error（含 OOM）：错误码单列，但按可重试处理——
                    // 走既有自动跳歌开关+熔断语义（见下方 retryableAdvance）。
                    is DecodeFailedException ->
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                    // 2.1.4：解码流异常单列解码类——此前落 else 被误报
                    // 「播放出错，网络或音源异常」。归类后标题走「本曲
                    // 解码失败，播放已停止」、PauseDiagnostics 归
                    // DECODE_ERROR；detail 的 msg 保留原始文案（含
                    // errorcode）。retryableAdvance 不含它：自动续播已在
                    // 上方试过一次，再失败直接走停留分支。
                    is BitstreamException ->
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                    else ->
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                }
                if (failure is AudioDeviceException) {
                    PlaybackToast.fail("音频输出设备打开失败：${failure.message}（请检查系统默认输出设备）")
                }
                onSessionError(
                    gen,
                    PlaybackException(failure.message ?: "播放失败", failure, code),
                    retryableAdvance =
                        code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                            failure is DecodeFailedException
                )
            }
        }
    }

    private fun isFlacUrl(u: String): Boolean =
        u.substringBefore('?').endsWith(".flac", ignoreCase = true)

    companion object {
        /** 本进程启动时刻（类加载即记）：A4-07 启动清扫溢写残留的界。 */
        private val PROCESS_START_MS = System.currentTimeMillis()
        /** FLAC 溢写临时文件名前缀（A4-07 清扫与待删登记共用）。 */
        private const val FLAC_SPILL_PREFIX = "penguin-flac-pcm-"

        /**
         * 删除失败待重试的文件（2.1.3，A4-07）：Windows 下正在播放读的
         * 落盘 PCM 删不掉，先登记 + deleteOnExit 兜底，后续
         * clearPcmCache/putPcmCache 时重试，避免孤儿文件泄漏。
         */
        private val pendingFileDeletes =
            java.util.Collections.synchronizedSet(mutableSetOf<File>())

        private fun retryPendingFileDeletes() {
            if (pendingFileDeletes.isEmpty()) return
            val snapshot = synchronized(pendingFileDeletes) { pendingFileDeletes.toList() }
            for (f in snapshot) {
                val gone = try { !f.exists() || f.delete() } catch (_: Exception) { false }
                if (gone) pendingFileDeletes.remove(f)
            }
        }

        /** 直链缓存 TTL（A5）：NCM 签名直链本身有有效期，20 分钟足够复用又不至于踩过期。 */
        private const val RESOLVED_URL_TTL_MS = 20 * 60_000L
        /** 连续失败熔断阈值（B1）。 */
        private const val MAX_CONSECUTIVE_FAILURES = 5

        /** 2.1.4：MP3 尾部容错窗口——剩余时长 ≤10s 时的解码流异常按自然播完处理（见 isMp3TailError）。 */
        private const val MP3_TAIL_TOLERANCE_MS = 10_000L
        /** 2.1.4：时长未知时尾部判据的最低已解码帧数（100 帧 ≈ 2.6s @26.12ms/帧，证明在播真实音频）。 */
        private const val MP3_TAIL_MIN_FRAMES = 100L
        /** 2.2.0（A5-03）：MP3 帧体损坏连续跳帧上限——连坏达此数判本曲解码失败，不再无限跳。 */
        private const val MP3_BAD_FRAME_MAX_STREAK = 5

        /**
         * FLAC 解码内存阈值（2.1.2 #1）：单曲 PCM 在内存中最多累积 32MB
         * （44.1kHz 立体声约 3 分钟），超出即溢写临时文件、播放流式读盘——
         * 长曲 PCM 不再整曲驻留 1GB 堆。小曲走内存是为保留 seek/循环的
         * 零拷贝直读（见 FlacPcmSink，不再有 toByteArray 的整曲复制）。
         */
        private const val FLAC_PCM_MEMORY_THRESHOLD_BYTES = 32 * 1024 * 1024
        /** PCM 缓存最多保留最近 2 首（#6）：当前曲 + 上一首，够 seek/回切/单曲循环复用。 */
        private const val FLAC_PCM_MAX_CACHED_ENTRIES = 2
        /** PCM 缓存内存总量上限（#6）：两首满阈值内存曲正好 64MB，与单曲阈值配套。 */
        private const val FLAC_PCM_MAX_CACHED_MEMORY_BYTES = 64L * 1024 * 1024
        /** PCM 缓存落盘总量上限（#6）：溢写长曲按 ~176KB/s(44.1k 立体声) 估，512MB 约容 2 首长曲。 */
        private const val FLAC_PCM_MAX_CACHED_DISK_BYTES = 512L * 1024 * 1024
    }

    private class UnsupportedFormatException(message: String) : Exception(message)

    /**
     * 解码期 Error 的 Exception 化包装（2.1.2 #1）：OutOfMemoryError 等
     * Error 在解码入口被包成它，沿既有 Exception 错误通道上报/跳歌，
     * 绝不让 Error 本体逃逸。仅作标记与承载，原 Error 在 cause 里。
     */
    private class DecodeFailedException(message: String, cause: Throwable?) :
        Exception(message, cause)

    /** 音频输出设备异常（与网络/解码错误分开上报，见 decodeLoop）。 */
    private class AudioDeviceException(message: String, cause: Throwable?) :
        Exception(message, cause)

    /**
     * MP3 尾部断流信号（2.2.0，A5-17）：decodeLoop 命中 isMp3TailError
     * 时抛出，不再就地吞掉（那是 2.1.4 行为）；runDecodeSession 接住
     * 后先做一次尾部续播（在线）或直接回落（本地），续播失败才按
     * 「播完」收尾。cause 保留原始 BitstreamException 备查。
     */
    private class Mp3TailStreamException(cause: BitstreamException) :
        Exception(cause.message, cause)

    /**
     * FLAC 解码中止信号（2.2.0，A5-04）：PCM 回调发现会话代次已变
     * （切歌/seek）时抛出，穿透 jFLAC 的 decode()（它只内吞
     * FrameDecodeException/EOFException），让整曲顺序解码立即停下，
     * 不再把已作废的曲子满载解完。
     */
    private class FlacDecodeAbortedException :
        RuntimeException("FLAC decode aborted (session superseded)")

    private fun onSessionError(gen: Int, e: PlaybackException, retryableAdvance: Boolean) {
        if (gen != generation) return
        synchronized(lock) {
            error = e
            setPlayingLocked(false)
            setStateLocked(Player.STATE_READY)
        }
        fire { it.onPlayerErrorChanged(e) }
        fire { it.onPlayerError(e) }
        fireEvents()
        // Retryable (network/resolve) errors: the UI layer stays silent by
        // design (the Android service recovered internally), so the engine
        // advances to the next item to avoid a dead stop.
        // 2.0.3：自动跳歌也要有痕——Toast 说明原因（同文案 4 秒去重防刷屏）。
        // 2.0.5（A1+B1）：跳歌受「播放失败自动跳下一首」开关管辖（关则停播
        // 报错、留在当前曲）；开着也加连续失败熔断——连败达
        // MAX_CONSECUTIVE_FAILURES 次停播报错，不再无限轮转。
        var advanced = false
        var breakerHit = false
        if (retryableAdvance && playWhenReady && autoSkipOnError) {
            consecutiveFailures++
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                PlaybackToast.fail(
                    "连续 $MAX_CONSECUTIVE_FAILURES 首播放失败，已停止自动跳歌" +
                        "（请检查网络或音源设置）"
                )
                breakerHit = true
            } else {
                val next = computeNextIndex(index, forAuto = false)
                if (next != null) {
                    PlaybackToast.show("播放出错已自动跳下一首：${e.message?.take(60) ?: "未知错误"}")
                    seekBaseMs = 0
                    seekInternal(next, 0, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                    advanced = true
                }
            }
        }
        if (advanced) return
        // ---- 停留分支（2.1.3，A4-01②+A4-03）----
        // 旧行为：playWhenReady 残留 true，会话已死，按播放被 setter 早退
        // 吞掉（audit4 头号根因）。现四件事一起做：
        //  1) 位置归一到出错点（seekBaseMs=currentPosition、framesPlayed
        //     清零），重试/按播放即从出错点续播，不回曲首；
        //  2) playWhenReady 置 false 使状态自洽（UI 呈暂停态，按播放走
        //     正常 startCurrentLocked 重启会话；play() 另有强制重启兜底）；
        //  3) 常驻错误条（原因 + 重试/跳下一首），不再只有 4 秒 Toast；
        //  4) 暂停原因落盘（PauseDiagnostics），下次出问题可直接查因。
        synchronized(lock) {
            val pos = currentPosition
            seekBaseMs = pos
            framesPlayed = 0
        }
        val detail = "mediaId=${currentMediaItem?.mediaId} " +
            "code=${e.errorCode} msg=${e.message?.take(100)}"
        if (breakerHit) {
            PauseDiagnostics.record(PauseDiagnostics.BREAKER, detail)
            PlaybackStatus.show(
                "连续 $MAX_CONSECUTIVE_FAILURES 首播放失败，已停止自动跳歌",
                "请检查网络或音源设置后点重试",
                PauseDiagnostics.BREAKER
            )
        } else {
            val reason = when (e.errorCode) {
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ->
                    PauseDiagnostics.DEVICE_ERROR
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                    PauseDiagnostics.DECODE_ERROR
                PlaybackException.ERROR_CODE_REMOTE_ERROR ->
                    PauseDiagnostics.RESOLVE_ERROR
                else -> PauseDiagnostics.NETWORK_ERROR
            }
            PauseDiagnostics.record(reason, detail)
            val title = when (reason) {
                PauseDiagnostics.DEVICE_ERROR -> "音频输出设备异常，播放已停止"
                PauseDiagnostics.DECODE_ERROR -> "本曲解码失败，播放已停止"
                PauseDiagnostics.RESOLVE_ERROR -> "取链失败，播放已停止"
                else -> "播放出错，网络或音源异常"
            }
            PlaybackStatus.show(title, e.message, reason)
        }
        if (playWhenReady) playWhenReady = false
    }

    private fun decodeStream(
        gen: Int,
        url: String,
        startMs: Long,
        pcmCacheKey: String? = null
    ) {
        decodeThread = Thread.currentThread()
        if (isFlacUrl(url)) {
            decodeFlacStream(gen, url, startMs, pcmCacheKey)
            return
        }
        decodeMp3Stream(gen, url, startMs)
    }

    // ------------------------------------------------------------------
    // 本地文件播放（2.1.0）：MP3/FLAC 复用在线解码核心，WAV 直喂 PCM。
    // 进度/seek/暂停语义与在线歌一致：位置 = seekBaseMs + framesPlayed *
    // msPerFrame；MP3 seek 按码率估算字节偏移（与在线「服务器忽略
    // Range」同策略，约 1s 精度），FLAC/WAV 帧级精确。
    // ------------------------------------------------------------------

    /** 条目是否为本地文件：优先 mediaId 的 local: 前缀，其次 file:// uri。 */
    private fun localFileOf(item: MediaItem): File? {
        LocalLibrary.fileOf(item.mediaId)?.let { return it }
        val uri = item.uri ?: return null
        if (uri.scheme == "file") {
            val raw = uri.path
            val path = raw?.let {
                runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
            }
            if (!path.isNullOrBlank()) return File(path)
        }
        return null
    }

    private fun decodeLocalFile(gen: Int, mediaId: String, file: File, startMs: Long) {
        decodeThread = Thread.currentThread()
        if (!file.exists()) {
            throw java.io.FileNotFoundException("文件不存在：${file.name}")
        }
        when (file.extension.lowercase()) {
            "flac" -> {
                // 本地 FLAC 与在线共用 playFlacPcm（内存守卫 + PCM 缓存
                // 一并受益）；缓存命中时连文件都不必再开。
                val pcmKey = localFlacPcmCacheKey(mediaId, file)
                if (tryPlayCachedFlacPcm(gen, pcmKey, startMs)) return
                val input: InputStream =
                    BufferedInputStream(FileInputStream(file), 64 * 1024)
                stream = input
                try {
                    playFlacPcm(gen, input, startMs, pcmKey)
                } finally {
                    try { input.close() } catch (_: Exception) { }
                }
            }
            "mp3" -> decodeMp3Local(gen, file, startMs)
            "wav" -> decodeWavFile(gen, file, startMs)
            else -> throw UnsupportedFormatException(
                "本地 .${file.extension.lowercase()} 格式暂不支持播放"
            )
        }
    }

    private fun decodeMp3Local(gen: Int, file: File, startMs: Long) {
        contentBytes = file.length()
        var input: InputStream = BufferedInputStream(FileInputStream(file), 64 * 1024)
        stream = input
        val bitstream = Bitstream(input)
        val decoder = Decoder()
        var header: Header? = bitstream.readFrame()
        if (header == null) {
            bitstream.close()
            throw UnsupportedFormatException("无法解码的音频文件")
        }
        if (startMs > 0) {
            val bytesPerMs = if (contentBytes > 0 && durationMs > 0) {
                contentBytes.toDouble() / durationMs
            } else {
                (header.bitrate() / 8.0) / 1000.0
            }
            // 2.2.0（A5-07）：VBR 文件优先用 Xing TOC 落点（与
            // LocalTags.mp3Duration 同一份 Xing 解析，含 100 字节
            // TOC 查表）——旧实现拿全曲平均码率估偏移，VBR 落点能
            // 差数十秒。无 Xing/TOC 时回落原估算。
            val toSkip = LocalTags.xingByteOffset(file, startMs)
                ?: (bytesPerMs * startMs).toLong()
            try { input.close() } catch (_: Exception) { }
            input = BufferedInputStream(FileInputStream(file), 64 * 1024)
            stream = input
            skipFully(input, toSkip)
            val bs2 = Bitstream(input)
            header = bs2.readFrame() ?: throw UnsupportedFormatException("定位后无音频帧")
            decodeLoop(gen, bs2, decoder, header)
            return
        }
        decodeLoop(gen, bitstream, decoder, header)
    }

    /**
     * WAV 直喂 PCM：解析 RIFF（fmt/data），支持 PCM 整数 8/16/24/32 位与
     * IEEE float 32/64 位，统一转 16-bit 小端后按帧块流式过
     * EQ/淡入淡出/8D 写线缆（不整曲载入内存，长 WAV 也安全）。
     * 暂停泊车语义与 [playPcmLoop] 一致。
     */
    private fun decodeWavFile(gen: Int, file: File, startMs: Long) {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 44) throw UnsupportedFormatException("WAV 文件过小")
            val head = ByteArray(12)
            raf.readFully(head)
            if (String(head, 0, 4, Charsets.ISO_8859_1) != "RIFF" ||
                String(head, 8, 4, Charsets.ISO_8859_1) != "WAVE"
            ) {
                throw UnsupportedFormatException("不是有效的 WAV 文件")
            }
            var audioFormat = 0
            var channels = 0
            var sampleRate = 0
            var blockAlign = 0
            var bitsPerSample = 0
            var dataStart = -1L
            var dataSize = 0L
            while (raf.filePointer + 8 <= raf.length()) {
                val chunkHead = ByteArray(8)
                raf.readFully(chunkHead)
                val id = String(chunkHead, 0, 4, Charsets.ISO_8859_1)
                val size = (chunkHead[4].toLong() and 0xFF) or
                    ((chunkHead[5].toLong() and 0xFF) shl 8) or
                    ((chunkHead[6].toLong() and 0xFF) shl 16) or
                    ((chunkHead[7].toLong() and 0xFF) shl 24)
                val start = raf.filePointer
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(minOf(size, 40L).toInt())
                        raf.readFully(fmt)
                        if (fmt.size >= 16) {
                            fun u16(o: Int) = (fmt[o].toInt() and 0xFF) or
                                ((fmt[o + 1].toInt() and 0xFF) shl 8)
                            fun u32(o: Int) = (fmt[o].toLong() and 0xFF) or
                                ((fmt[o + 1].toLong() and 0xFF) shl 8) or
                                ((fmt[o + 2].toLong() and 0xFF) shl 16) or
                                ((fmt[o + 3].toLong() and 0xFF) shl 24)
                            audioFormat = u16(0)
                            channels = u16(2)
                            sampleRate = u32(4).toInt()
                            blockAlign = u16(12)
                            bitsPerSample = u16(14)
                        }
                    }
                    "data" -> {
                        dataStart = start
                        dataSize = size
                        // 2.2.0（A5-06）：流式/在线生成的 WAV 常把
                        // data size 写 0 或 0xFFFFFFFF（长度未知占位）
                        // ——声明不可信，数据实际延伸到文件尾。fmt
                        // 必在 data 之前，扫到此即可停；帧数下方按
                        // 文件剩余字节兜底（dataSize=-1 为标记）。
                        if (size <= 0L || size > raf.length() - start) {
                            dataSize = -1L
                            break
                        }
                    }
                }
                if (dataStart >= 0 && audioFormat != 0) break
                raf.seek(start + size + (size and 1))
            }
            val supported = (audioFormat == 1 && bitsPerSample in setOf(8, 16, 24, 32)) ||
                (audioFormat == 3 && bitsPerSample in setOf(32, 64))
            if (dataStart < 0 || channels <= 0 || sampleRate <= 0 || blockAlign <= 0 ||
                !supported
            ) {
                throw UnsupportedFormatException(
                    "WAV 变体暂不支持（format=$audioFormat bits=$bitsPerSample）"
                )
            }
            val bytesPerSample = bitsPerSample / 8
            // 2.2.0（A5-06）：data 声明不可信（-1 标记或越出文件）时按
            // 「数据到文件尾」兜底算帧数，流式 WAV 不再被拒播。
            val effectiveDataSize = if (dataSize <= 0L ||
                dataStart + dataSize > raf.length()
            ) {
                raf.length() - dataStart
            } else {
                dataSize
            }
            val totalFrames = effectiveDataSize / blockAlign
            if (totalFrames <= 0L) throw UnsupportedFormatException("WAV 无音频数据")
            if (durationMs == C.TIME_UNSET) {
                durationMs = totalFrames * 1000L / sampleRate
            }
            val startFrame = (startMs.coerceAtLeast(0) * sampleRate / 1000L)
                .coerceIn(0L, totalFrames)
            msPerFrame = 1000f / sampleRate
            val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
            val dataLine = openOutputLine(format)
            line = dataLine
            applyVolume()
            pcmEffects.beginTrack()
            speedResampler.reset()
            sessionRunning = true
            synchronized(lock) {
                error = null
                setStateLocked(Player.STATE_READY)
                if (playWhenReady) setPlayingLocked(true)
            }
            consecutiveFailures = 0
            fireEvents()
            val chunkFrames = 4096
            val raw = ByteArray(chunkFrames * blockAlign)
            val shorts = ShortArray(chunkFrames * channels)
            var framePos = startFrame
            var parked = false
            try {
                raf.seek(dataStart + startFrame * blockAlign)
                while (framePos < totalFrames) {
                    if (gen != generation) break
                    if (parked) {
                        while (pausedFlag && gen == generation) {
                            try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                        }
                        if (gen != generation) break
                        runCatching { dataLine.start() }
                        pcmEffects.fadeIn()
                        parked = false
                    }
                    val n = min(chunkFrames.toLong(), totalFrames - framePos).toInt()
                    val want = n * blockAlign
                    val got = raf.read(raw, 0, want)
                    if (got <= 0) break
                    val frames = got / blockAlign
                    if (frames <= 0) break
                    for (f in 0 until frames) {
                        for (ch in 0 until channels) {
                            val o = (f * blockAlign) + ch * bytesPerSample
                            shorts[f * channels + ch] = when {
                                audioFormat == 3 && bitsPerSample == 32 -> {
                                    val bits = (raw[o].toInt() and 0xFF) or
                                        ((raw[o + 1].toInt() and 0xFF) shl 8) or
                                        ((raw[o + 2].toInt() and 0xFF) shl 16) or
                                        ((raw[o + 3].toInt() and 0xFF) shl 24)
                                    (Float.fromBits(bits).coerceIn(-1f, 1f) *
                                        32767f).toInt().toShort()
                                }
                                audioFormat == 3 && bitsPerSample == 64 -> {
                                    var bits = 0L
                                    for (b in 0 until 8) {
                                        bits = bits or
                                            ((raw[o + b].toLong() and 0xFF) shl (8 * b))
                                    }
                                    (Double.fromBits(bits).coerceIn(-1.0, 1.0) *
                                        32767.0).toInt().toShort()
                                }
                                bitsPerSample == 8 ->
                                    (((raw[o].toInt() and 0xFF) - 128) * 256).toShort()
                                bitsPerSample == 16 ->
                                    ((raw[o + 1].toInt() shl 8) or
                                        (raw[o].toInt() and 0xFF)).toShort()
                                bitsPerSample == 24 ->
                                    ((raw[o + 2].toInt() shl 8) or
                                        (raw[o + 1].toInt() and 0xFF)).toShort()
                                else -> // 32-bit int：取高 16 位
                                    ((raw[o + 3].toInt() shl 8) or
                                        (raw[o + 2].toInt() and 0xFF)).toShort()
                            }
                        }
                    }
                    val count = frames * channels
                    eqProcessor.process(shorts, count, channels, sampleRate)
                    reverbProcessor.process(shorts, count, channels, sampleRate)
                    // 2.2.0（L5）：变速（顺序同其它路径）；帧计数仍按输入帧。
                    val (spShorts, spLen) = speedResampler.process(
                        shorts, count, channels, playbackParams.speed
                    )
                    pcmEffects.process(spShorts, spLen, channels, sampleRate)
                    pcmEffects.processSurround(spShorts, spLen, channels, sampleRate)
                    val bytes = ByteArray(spLen * 2)
                    var bi = 0
                    for (i in 0 until spLen) {
                        val s = spShorts[i].toInt()
                        bytes[bi++] = (s and 0xFF).toByte()
                        bytes[bi++] = (s shr 8 and 0xFF).toByte()
                    }
                    dataLine.write(bytes, 0, bytes.size)
                    framesPlayed += frames
                    framePos += frames
                    maybePrefetchNext(gen)
                    if (pausedFlag && !parked) {
                        pcmEffects.fadeOut()
                        if (pcmEffects.gain <= 0.002f) {
                            runCatching { dataLine.stop() }
                            parked = true
                        }
                    }
                }
            } finally {
                if (gen == generation) {
                    sessionRunning = false
                    try { dataLine.drain() } catch (_: Exception) { }
                    try { dataLine.stop() } catch (_: Exception) { }
                    try { dataLine.close() } catch (_: Exception) { }
                    if (line === dataLine) line = null
                }
            }
            if (gen != generation) return
            val playedMs = seekBaseMs + (framesPlayed * msPerFrame).toLong()
            if (durationMs == C.TIME_UNSET) durationMs = playedMs
            onTrackEnded(gen)
        }
    }

    /**
     * FLAC 在线播放（2.0.4 新增，治本替代 2.0.3 的「FLAC 死停 + 假降级」）：
     * jFLAC 解码出 16-bit PCM（ByteData 是小端序；24-bit 取高 16 位，与
     * run6 JFlacProbe 的实测解码口径一致，badFrames=0、采样数与
     * STREAMINFO 全对），再从目标帧偏移喂 SourceDataLine——seek 是帧级
     * 精确的，不走 MP3 的字节估算。解码/下载失败统一包成
     * UnsupportedFormatException，交给 runDecodeSession 的官方直达降级。
     * 2.1.2（#1/#6）：解码有界化（超 32MB 溢写临时文件）且产物入 PCM
     * 缓存；进门先查缓存，命中时连接都不必再开。
     */
    private fun decodeFlacStream(
        gen: Int,
        url: String,
        startMs: Long,
        pcmCacheKey: String?
    ) {
        if (pcmCacheKey != null && tryPlayCachedFlacPcm(gen, pcmCacheKey, startMs)) return
        val conn = openConnection(url, 0)
        val input: InputStream = BufferedInputStream(conn.inputStream, 64 * 1024)
        stream = input
        try {
            playFlacPcm(gen, input, startMs, pcmCacheKey)
        } finally {
            try { input.close() } catch (_: Exception) { }
        }
    }

    /** FLAC 解码+播放核心（2.1.0 抽出，在线 URL 流与本地文件共用）。 */
    private fun playFlacPcm(
        gen: Int,
        input: InputStream,
        startMs: Long,
        pcmCacheKey: String?
    ) {
        // 2.1.2（#1）：PCM 不再无上限累积——FlacPcmSink 内存封顶 32MB，
        // 超出溢写临时文件；内存路径直接交出内部缓冲（无 toByteArray 的
        // 整曲二次复制）。解码产物即 #6 的缓存条目，播完不弃。
        val sink = FlacPcmSink()
        var streamInfo: StreamInfo? = null
        try {
            val decoder = FLACDecoder(input)
            decoder.addPCMProcessor(object : PCMProcessor {
                override fun processStreamInfo(info: StreamInfo) {
                    streamInfo = info
                }

                override fun processPCM(data: ByteData) {
                    // 2.2.0（A5-04）：FLAC 解码可中断——jFLAC 的
                    // decode() 对整曲顺序解码且无中断接口，旧实现切歌
                    // 后旧曲仍被满载解完（CPU + 若在线还有流量）。每
                    // 帧 PCM 回调查一次代次，变了即抛穿透型
                    // RuntimeException 让 decode() 立刻栈展开（已核
                    // jFLAC 源码：它只内吞 FrameDecodeException 与
                    // EOFException，其它异常原样穿透）。
                    if (gen != generation) throw FlacDecodeAbortedException()
                    val bytes = data.data
                    val len = data.len
                    val info = streamInfo
                    if (info == null || info.bitsPerSample == 16) {
                        sink.write(bytes, 0, len)
                        return
                    }
                    // 非 16-bit：逐采样取高 16 位（小端），声道交错顺序不变。
                    val bps = info.bitsPerSample / 8
                    if (bps < 2) {
                        sink.write(bytes, 0, len)
                        return
                    }
                    var i = 0
                    while (i + bps <= len) {
                        sink.writeByte(bytes[i + bps - 2].toInt())
                        sink.writeByte(bytes[i + bps - 1].toInt())
                        i += bps
                    }
                }
            })
            decoder.decode()
            if (decoder.badFrames > 0 && sink.totalBytes == 0L) {
                throw UnsupportedFormatException("FLAC 解码失败（坏帧 ${decoder.badFrames}）")
            }
        } catch (e: UnsupportedFormatException) {
            sink.discard()
            throw e
        } catch (e: FlacDecodeAbortedException) {
            // 2.2.0（A5-04）：会话已被新曲接管——丢弃产物静默退出
            // （即使不抛，下方 post-decode 的代次检查也会丢弃，
            // 区别只是不必把废曲解完）。
            sink.discard()
            return
        } catch (e: IOException) {
            // 2.2.0（A5-01）：解码期底层流 IOException（断网、CDN
            // 中途复位、本地文件读错误）原样上抛，runDecodeSession
            // 归网络类（IO_NETWORK）。旧实现被下方通用 Exception 兜底
            // 一刀切包成 UnsupportedFormatException：断网误报「格式
            // 不支持」，还会触发官方直达假降级并误落「降为高音质」
            // 提示。只有解码器自身/结构性异常才走格式错。
            sink.discard()
            throw e
        } catch (e: OutOfMemoryError) {
            // 2.1.2（#1）：Error 捕获点之一。内存已吃紧：先丢本次产物并清
            // PCM 缓存腾堆，再把 Error 包成 Exception 沿既有错误通道上报，
            // 绝不让 OutOfMemoryError 逃逸到协程/UI 线程崩进程。
            sink.discard()
            clearPcmCache()
            throw DecodeFailedException("FLAC 解码内存不足（有界解码下仍溢出）", e)
        } catch (e: Error) {
            sink.discard()
            throw DecodeFailedException("FLAC 解码错误：${e.message}", e)
        } catch (e: Exception) {
            sink.discard()
            if (gen != generation) throw e
            throw UnsupportedFormatException("FLAC 解码失败：${e.message}")
        } finally {
            try { input.close() } catch (_: Exception) { }
        }
        if (gen != generation) {
            sink.discard()
            return
        }
        val info = streamInfo
        if (info == null) {
            sink.discard()
            throw UnsupportedFormatException("FLAC 缺少 STREAMINFO")
        }
        val sampleRate = info.sampleRate
        val channels = info.channels.coerceAtLeast(1)
        val frameSize = 2 * channels
        val totalFrames = sink.totalBytes / frameSize
        if (totalFrames <= 0L) {
            sink.discard()
            throw UnsupportedFormatException("FLAC 解码出空音频")
        }
        val totalSamples = info.totalSamples
        val trackDurationMs = if (totalSamples > 0) {
            totalSamples * 1000L / sampleRate
        } else {
            totalFrames * 1000L / sampleRate
        }
        if (durationMs == C.TIME_UNSET) {
            durationMs = trackDurationMs
        }
        val startFrame = (startMs.coerceAtLeast(0) * sampleRate / 1000L)
            .coerceIn(0L, totalFrames)
        val entry = sink.finish(sampleRate, channels, totalFrames, trackDurationMs)
        if (pcmCacheKey != null) {
            // 入缓存后再播：播放读的就是缓存载体本身（内存缓冲/临时文件），
            // seek 与单曲循环重入时 tryPlayCachedFlacPcm 直接命中它。
            putPcmCache(pcmCacheKey, entry)
            playPcmLoop(gen, entry, startFrame)
        } else {
            // 无缓存键的兜底（降级路径已校验非 FLAC，理论不可达）：播完即
            // 弃，溢写文件随之删除，不留残档。
            try {
                playPcmLoop(gen, entry, startFrame)
            } finally {
                entry.deleteFile()
            }
        }
    }

    // ------------------------------------------------------------------
    // FLAC PCM 缓存与有界解码（2.1.2 #1/#6）
    // ------------------------------------------------------------------

    /**
     * 已解码 PCM 条目：内存与落盘二选一（memory/file 恰一非空）。
     * memory 持有 FlacPcmSink 的内部缓冲本体（不复制），有效长度以
     * memoryLength 为准（缓冲容量可大于有效长度）；file 为溢写临时
     * 文件，条目被淘汰/清理时必须 deleteFile 防泄漏。
     */
    private class CachedFlacPcm(
        val sampleRate: Int,
        val channels: Int,
        val totalFrames: Long,
        val durationMs: Long,
        val memory: ByteArray?,
        val memoryLength: Int,
        val file: File?,
        val byteLength: Long
    ) {
        val memoryBytes: Long get() = if (memory != null) memoryLength.toLong() else 0L
        val diskBytes: Long get() = if (file != null) byteLength else 0L

        /**
         * 2.2.0（A5-05）：单条落盘即超过缓存盘上限——旧淘汰三限都
         * 以「size > 1 才动手」为前提，单条 600MB 的超长曲永远留驻
         * （磁盘被一首歌长期占满）。这类条目标记为「只陪当前会话」：
         * 新条目入缓存或跨条目切歌时优先回收（见 putPcmCache /
         * evictSessionOnlyPcmCache）。
         */
        val sessionOnly: Boolean
            get() = diskBytes > DesktopPlayer.FLAC_PCM_MAX_CACHED_DISK_BYTES

        fun deleteFile() {
            val f = file ?: return
            val deleted = try { f.delete() } catch (_: Exception) { false }
            if (!deleted && f.exists()) {
                // 2.1.3（A4-07）：删除失败（Windows 下文件正被打开读是
                // 常态）——登记待删 + deleteOnExit 兜底，后续缓存操作时
                // 重试，不让条目摘除后文件成孤儿留在磁盘。
                runCatching { f.deleteOnExit() }
                DesktopPlayer.pendingFileDeletes.add(f)
                println("[DesktopPlayer] PCM 缓存文件暂删不掉，已登记待删：${f.name}")
            }
        }
    }

    /**
     * FLAC 解码 PCM 汇（#1 内存守卫）：先在内存累积（自增长缓冲，上限
     * FLAC_PCM_MEMORY_THRESHOLD_BYTES=32MB），越限即把已有数据溢写临时
     * 文件、后续全走文件，内存缓冲随即释放。内存路径 finish 时直接交出
     * 内部缓冲——旧实现 ByteArrayOutputStream.toByteArray() 的整曲二次
     * 复制就此消除（缓冲容量≥有效长度，播放侧按 memoryLength 界读）。
     * writeByte 走 8KB 暂存批写，避免 24-bit 逐字节路径频繁扩容/触盘。
     */
    private class FlacPcmSink {
        private var memory = ByteArray(1024 * 1024)
        private var memorySize = 0
        private var spillFile: File? = null
        private var spillOut: BufferedOutputStream? = null
        private val staging = ByteArray(8192)
        private var stagingSize = 0

        /** 已接收的 PCM 总字节数（含暂存未刷部分）。 */
        var totalBytes: Long = 0
            private set

        fun write(data: ByteArray, offset: Int, length: Int) {
            if (length <= 0) return
            flushStaging()
            writeRaw(data, offset, length)
            totalBytes += length
        }

        fun writeByte(value: Int) {
            staging[stagingSize++] = value.toByte()
            totalBytes++
            if (stagingSize == staging.size) flushStaging()
        }

        private fun flushStaging() {
            if (stagingSize == 0) return
            writeRaw(staging, 0, stagingSize)
            stagingSize = 0
        }

        private fun writeRaw(data: ByteArray, offset: Int, length: Int) {
            val out = spillOut
            if (out != null) {
                out.write(data, offset, length)
                return
            }
            if (memorySize + length <=
                DesktopPlayer.FLAC_PCM_MEMORY_THRESHOLD_BYTES
            ) {
                ensureCapacity(memorySize + length)
                System.arraycopy(data, offset, memory, memorySize, length)
                memorySize += length
                return
            }
            // 越限溢写：已有内存数据先落盘，随后本次数据续写文件。
            val f = File.createTempFile(DesktopPlayer.FLAC_SPILL_PREFIX, ".pcm")
            // 2.1.3（A4-07）：deleteOnExit 兜底（条目删除走缓存清理；
            // 进程异常退出时至少让 JVM 收尾，残留再由下次启动清扫）。
            runCatching { f.deleteOnExit() }
            val newOut = BufferedOutputStream(FileOutputStream(f), 64 * 1024)
            newOut.write(memory, 0, memorySize)
            spillFile = f
            spillOut = newOut
            memory = ByteArray(0)
            memorySize = 0
            newOut.write(data, offset, length)
        }

        private fun ensureCapacity(needed: Int) {
            if (memory.size >= needed) return
            var cap = memory.size.coerceAtLeast(1)
            while (cap < needed) {
                cap = minOf(cap * 2, DesktopPlayer.FLAC_PCM_MEMORY_THRESHOLD_BYTES)
            }
            val grown = ByteArray(cap)
            System.arraycopy(memory, 0, grown, 0, memorySize)
            memory = grown
        }

        /** 收尾成缓存条目：落盘则关流交文件，内存则交内部缓冲本体（零拷贝）。 */
        fun finish(
            sampleRate: Int,
            channels: Int,
            totalFrames: Long,
            durationMs: Long
        ): CachedFlacPcm {
            flushStaging()
            val out = spillOut
            if (out != null) {
                try { out.flush() } catch (_: Exception) { }
                try { out.close() } catch (_: Exception) { }
                spillOut = null
                return CachedFlacPcm(
                    sampleRate, channels, totalFrames, durationMs,
                    null, 0, spillFile, totalBytes
                )
            }
            return CachedFlacPcm(
                sampleRate, channels, totalFrames, durationMs,
                memory, memorySize, null, totalBytes
            )
        }

        /** 解码失败/会话作废时调用：关流、删溢写文件、释放内存缓冲。 */
        fun discard() {
            try { spillOut?.close() } catch (_: Exception) { }
            spillOut = null
            val f = spillFile
            if (f != null) {
                val deleted = try { f.delete() } catch (_: Exception) { false }
                if (!deleted && f.exists()) {
                    // 同 A4-07：删不掉登记待删，别留孤儿。
                    DesktopPlayer.pendingFileDeletes.add(f)
                }
            }
            spillFile = null
            memory = ByteArray(0)
            memorySize = 0
            stagingSize = 0
        }
    }

    /** 命中返回缓存条目并刷新 LRU 顺序；落盘文件已被外部清掉则视为未命中并摘除。 */
    private fun getPcmCache(key: String): CachedFlacPcm? =
        synchronized(pcmCacheLock) {
            val entry = pcmCache[key] ?: return@synchronized null
            if (entry.file != null && !entry.file.exists()) {
                pcmCache.remove(key)
                return@synchronized null
            }
            entry
        }

    /**
     * 入缓存并按 LRU 淘汰至上限内（条数/内存/落盘三限，见 companion 常量）。
     * 淘汰即删其临时文件。刚入的条目永不被本轮淘汰（它正是要播的曲目；
     * 单曲落盘超盘限时先保它，靠条数限在下一曲入缓存时回收）。
     */
    private fun putPcmCache(key: String, entry: CachedFlacPcm) {
        retryPendingFileDeletes()
        synchronized(pcmCacheLock) {
            val replaced = pcmCache.remove(key)
            if (replaced != null && replaced !== entry) replaced.deleteFile()
            pcmCache[key] = entry
            while (pcmCache.size > 1 && (
                    pcmCache.size > FLAC_PCM_MAX_CACHED_ENTRIES ||
                        pcmCache.values.sumOf { it.memoryBytes } >
                        FLAC_PCM_MAX_CACHED_MEMORY_BYTES ||
                        pcmCache.values.sumOf { it.diskBytes } >
                        FLAC_PCM_MAX_CACHED_DISK_BYTES
                    )
            ) {
                val eldestKey = pcmCache.keys.first()
                if (eldestKey == key) break
                val evicted = pcmCache.remove(eldestKey) ?: break
                evicted.deleteFile()
            }
            // 2.2.0（A5-05）：其它 sessionOnly 超大条目在新条目入缓存
            // 时即回收（刚入的条目本身不回收——它正是要播的曲）。
            val iter = pcmCache.entries.iterator()
            while (iter.hasNext()) {
                val (k, v) = iter.next()
                if (k != key && v.sessionOnly) {
                    iter.remove()
                    v.deleteFile()
                }
            }
        }
    }

    /** 2.2.0（A5-05）：跨条目切换时回收全部 sessionOnly 超大条目。 */
    private fun evictSessionOnlyPcmCache() {
        synchronized(pcmCacheLock) {
            val iter = pcmCache.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (entry.value.sessionOnly) {
                    iter.remove()
                    entry.value.deleteFile()
                }
            }
        }
    }

    /** 清空 PCM 缓存（切队列/切音源/退出/OOM 自救）：条目的落盘文件一并删除。 */
    private fun clearPcmCache() {
        retryPendingFileDeletes()
        synchronized(pcmCacheLock) {
            for (entry in pcmCache.values) entry.deleteFile()
            pcmCache.clear()
        }
    }

    /**
     * #6 命中短路：seek/单曲循环重入会话时先查 PCM 缓存，命中即从缓存
     * 载体直接播放（免整曲重解码；在线源在 decodeFlacStream 开连接之前
     * 调用，故连重下载也一并省掉）。未命中返回 false 走正常解码路径。
     */
    private fun tryPlayCachedFlacPcm(gen: Int, key: String, startMs: Long): Boolean {
        val entry = getPcmCache(key) ?: return false
        if (durationMs == C.TIME_UNSET) durationMs = entry.durationMs
        val startFrame = (startMs.coerceAtLeast(0) * entry.sampleRate / 1000L)
            .coerceIn(0L, entry.totalFrames)
        println(
            "[DesktopPlayer] FLAC PCM 缓存命中，免重解码（frames=${entry.totalFrames}，" +
                "载体=${if (entry.file != null) "file" else "memory"}）"
        )
        playPcmLoop(gen, entry, startFrame)
        return true
    }

    /**
     * PCM 播放循环（FLAC 路径）：按帧块过 EQ 后写线缆，位置计数与 MP3
     * 路径同口径（seekBaseMs + framesPlayed * msPerFrame），暂停靠
     * pausedFlag 泊车。起始帧由 seek 决定，故落点帧级精确。数据载体二
     * 选一（2.1.2 #1/#6）：内存条目直读缓冲、落盘条目 RandomAccessFile
     * 顺序流读——两者帧序/计数/泊车逻辑完全共用，探针语义不分叉。
     */
    private fun playPcmLoop(
        gen: Int,
        entry: CachedFlacPcm,
        startFrame: Long
    ) {
        val sampleRate = entry.sampleRate
        val channels = entry.channels
        val totalFrames = entry.totalFrames
        val frameSize = 2 * channels
        msPerFrame = 1000f / sampleRate
        val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
        val dataLine = openOutputLine(format)
        line = dataLine
        applyVolume()
        pcmEffects.beginTrack()
        speedResampler.reset()
        sessionRunning = true
        synchronized(lock) {
            error = null
            setStateLocked(Player.STATE_READY)
            if (playWhenReady) setPlayingLocked(true)
        }
        consecutiveFailures = 0
        fireEvents()
        val chunkFrames = 4096
        val shorts = ShortArray(chunkFrames * channels)
        var framePos = startFrame
        // 2.1.2（#1/#6）：落盘条目从临时文件流式读（起始 seek 一次、其后
        // 顺序读，暂停泊车不挪文件位，drift 语义与内存直读一致）；内存
        // 条目按帧偏移直读缓冲。raf 在循环 finally 中关闭（只关读句柄，
        // 缓存文件本体由缓存淘汰/清理负责删除）。
        val raf: RandomAccessFile? = entry.file?.let { RandomAccessFile(it, "r") }
        val fileChunk = raf?.let { ByteArray(chunkFrames * frameSize) }
        if (raf != null) raf.seek(startFrame * frameSize.toLong())
        // 2.0.5（B2+C10）：暂停先在 PCM 层淡出（增益落到 ~0）再 stop 线缆
        // 泊车——旧实现只置标志，线缆里已缓冲的尾音会播完才停；现在淡出
        // 完成即 stop（缓冲数据保留），恢复时 start+淡入，无丢帧无爆音。
        var parked = false
        try {
            while (framePos < totalFrames) {
                if (gen != generation) break
                if (parked) {
                    while (pausedFlag && gen == generation) {
                        try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                    }
                    if (gen != generation) break
                    runCatching { dataLine.start() }
                    pcmEffects.fadeIn()
                    parked = false
                }
                val wantFrames = min(chunkFrames.toLong(), totalFrames - framePos).toInt()
                val effFrames: Int
                if (raf != null && fileChunk != null) {
                    // 落盘载体：顺序读满一块（文件由本类写全后才入缓存，
                    // 正常不会短读；真短读按实际帧数推进，不伪造数据）。
                    val wantBytes = wantFrames * frameSize
                    var got = 0
                    while (got < wantBytes) {
                        val r = raf.read(fileChunk, got, wantBytes - got)
                        if (r < 0) break
                        got += r
                    }
                    effFrames = got / frameSize
                    if (effFrames <= 0) break
                    for (i in 0 until effFrames * channels) {
                        shorts[i] = ((fileChunk[i * 2 + 1].toInt() shl 8) or
                            (fileChunk[i * 2].toInt() and 0xFF)).toShort()
                    }
                } else {
                    val mem = entry.memory
                        ?: throw UnsupportedFormatException("FLAC PCM 缓存条目无数据载体")
                    effFrames = wantFrames
                    val byteOff = (framePos * frameSize).toInt()
                    for (i in 0 until effFrames * channels) {
                        val bi = byteOff + i * 2
                        shorts[i] = ((mem[bi + 1].toInt() shl 8) or
                            (mem[bi].toInt() and 0xFF)).toShort()
                    }
                }
                eqProcessor.process(shorts, effFrames * channels, channels, sampleRate)
                reverbProcessor.process(shorts, effFrames * channels, channels, sampleRate)
                // 2.2.0（L5）：变速（顺序同 decodeLoop：EQ → 混响 →
                // 变速 → PcmEffects）；帧计数仍按输入帧，位置语义不变。
                val (spShorts, spLen) = speedResampler.process(
                    shorts, effFrames * channels, channels, playbackParams.speed
                )
                pcmEffects.process(spShorts, spLen, channels, sampleRate)
                pcmEffects.processSurround(spShorts, spLen, channels, sampleRate)
                val bytes = ByteArray(spLen * 2)
                var bi = 0
                for (i in 0 until spLen) {
                    val s = spShorts[i].toInt()
                    bytes[bi++] = (s and 0xFF).toByte()
                    bytes[bi++] = (s shr 8 and 0xFF).toByte()
                }
                dataLine.write(bytes, 0, bytes.size)
                framesPlayed += effFrames
                framePos += effFrames
                maybePrefetchNext(gen)
                if (pausedFlag && !parked) {
                    pcmEffects.fadeOut()
                    if (pcmEffects.gain <= 0.002f) {
                        runCatching { dataLine.stop() }
                        parked = true
                    }
                }
            }
        } finally {
            try { raf?.close() } catch (_: Exception) { }
            if (gen == generation) {
                sessionRunning = false
                try { dataLine.drain() } catch (_: Exception) { }
                try { dataLine.stop() } catch (_: Exception) { }
                try { dataLine.close() } catch (_: Exception) { }
                if (line === dataLine) line = null
            }
        }
        if (gen != generation) return
        val playedMs = seekBaseMs + (framesPlayed * msPerFrame).toLong()
        if (durationMs == C.TIME_UNSET) durationMs = playedMs
        onTrackEnded(gen)
    }

    /** 打开音频输出线缆；设备异常单独成 AudioDeviceException（不跳歌、要提示）。 */
    private fun openOutputLine(format: AudioFormat): SourceDataLine = try {
        AudioSystem.getSourceDataLine(format).apply {
            // 2.0.5：显式给约 200ms 缓冲（默认缓冲各平台差异大且偏大，
            // 写超前太多会让暂停淡出/位置计数都拖泥带水）；200ms 对本机
            // 解码吞吐留足余量，不易欠载爆音。
            val frameSize = maxOf(1, format.frameSize)
            val bufferBytes = (format.sampleRate * 0.2f).toInt() * frameSize
            open(format, bufferBytes)
            start()
        }
    } catch (dev: Exception) {
        throw AudioDeviceException(dev.message ?: dev.javaClass.simpleName, dev)
    }

    private fun decodeMp3Stream(gen: Int, url: String, startMs: Long) {
        var conn = openConnection(url, startMs)
        var input: InputStream = BufferedInputStream(conn.inputStream, 64 * 1024)
        stream = input
        val bitstream = Bitstream(input)
        val decoder = Decoder()
        var header: Header? = bitstream.readFrame()
        if (header == null) {
            bitstream.close()
            throw UnsupportedFormatException("无法解码的音频流")
        }
        // If a seek was requested but the server ignored Range, discard-skip.
        if (startMs > 0 && conn.responseCode != HttpURLConnection.HTTP_PARTIAL) {
            val bytesPerMs = if (contentBytes > 0 && durationMs > 0) {
                contentBytes.toDouble() / durationMs
            } else {
                (header.bitrate() / 8.0) / 1000.0
            }
            var toSkip = (bytesPerMs * startMs).toLong()
            // Bitstream already buffered frame 1; approximate by skipping bytes
            // on the raw stream then re-syncing with a fresh Bitstream.
            try { input.close() } catch (_: Exception) { }
            conn = openConnection(url, 0)
            input = BufferedInputStream(conn.inputStream, 64 * 1024)
            stream = input
            skipFully(input, toSkip)
            val bs2 = Bitstream(input)
            header = bs2.readFrame() ?: throw UnsupportedFormatException("定位后无音频帧")
            decodeLoop(gen, bs2, decoder, header)
            return
        }
        decodeLoop(gen, bitstream, decoder, header)
    }

    private fun openConnection(url: String, startMs: Long): HttpURLConnection {
        // NetEase's CDN serves the same objects over HTTPS; prefer it (plain
        // HTTP audio links are prone to ISP tampering and some networks
        // filter port 80). Local/LAN sources keep their original scheme.
        val effectiveUrl = if (
            url.startsWith("http://") &&
            !url.contains("://127.0.0.1") && !url.contains("://localhost")
        ) {
            "https://" + url.removePrefix("http://")
        } else url
        // 2.2.0（L1）：经 NetProxy 打开（直连=强制 NO_PROXY；跟随
        // 系统=WinINet/环境解析出的代理），播放直链与各下载路径同口径。
        val conn = NetProxy.openHttp(effectiveUrl)
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
        if (startMs > 0 && durationMs > 0 && contentBytes > 0) {
            val offset = (contentBytes.toDouble() * startMs / durationMs).toLong()
            if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
        }
        conn.connect()
        val len = conn.getHeaderFieldLong("Content-Length", -1)
        if (conn.responseCode == HttpURLConnection.HTTP_OK && len > 0) {
            contentBytes = len
        } else if (conn.responseCode == HttpURLConnection.HTTP_PARTIAL) {
            val range = conn.getHeaderField("Content-Range")
            val total = range?.substringAfter('/')?.toLongOrNull()
            if (total != null && total > 0) contentBytes = total
        }
        return conn
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val read = input.read(buf, 0, min(buf.size.toLong(), remaining).toInt())
            if (read < 0) break
            remaining -= read
        }
    }

    private fun decodeLoop(gen: Int, bitstream: Bitstream, decoder: Decoder, firstHeader: Header) {
        var header: Header? = firstHeader
        var dataLine: SourceDataLine? = null
        sessionRunning = true
        // 2.0.5（B2+C10）：同 FLAC 路径的暂停语义——淡出落完才 stop 线缆
        // 泊车，恢复 start+淡入。parked 仅在 dataLine 已建后置真。
        var parked = false
        // 2.2.0（A5-03）：连续帧体损坏计数（DecoderException 跳帧用，
        // 成功解出一帧即清零；达上限判本曲解码失败）。
        var badFrameStreak = 0
        // 2.2.0（A5-17）：尾部 BitstreamException 不再就地吞掉走自然
        // 结束（2.1.4 的 tailTolerated 机制退役）——两个捕获点命中
        // isMp3TailError 即抛 Mp3TailStreamException，runDecodeSession
        // 先做一次尾部续播、失败才回落按播完处理。
        try {
            while (header != null) {
                if (gen != generation) break
                if (parked) {
                    // Pause: park the decode thread, keep stream/frame state.
                    while (pausedFlag && gen == generation) {
                        try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                    }
                    if (gen != generation) break
                    runCatching { dataLine?.start() }
                    pcmEffects.fadeIn()
                    parked = false
                }
                // 2.2.0（A5-03）：帧体损坏的容错解码。坏帧在 JLayer 里
                // 有两种抛法：DecoderException（层/参数非法等受检异常）
                // 与解码器内部的 RuntimeException（帧体异或损坏实测抛
                // ArrayIndexOutOfBounds，run13 JlCorrupt 实验）——旧代码
                // 两者都不接：一帧坏体整曲停住，前者落 else 误归
                // IO_NETWORK、后者直接逃逸。现统一跳帧：关掉当前帧、
                // 读下一帧头续解（主流播放器做法）；连续坏帧达上限才
                // 抛 DecodeFailedException 判本曲解码失败（归
                // DECODER_INIT_FAILED，与 2.1.4 口径一致）。Error 不捕。
                var badFrame: Exception? = null
                val decodedBuffer: SampleBuffer? = try {
                    decoder.decodeFrame(header, bitstream) as SampleBuffer
                } catch (e: BitstreamException) {
                    if (isMp3TailError(e, readPhase = false)) {
                        throw Mp3TailStreamException(e)
                    }
                    throw e
                } catch (e: Exception) {
                    badFrame = e
                    null
                }
                if (badFrame != null) {
                    badFrameStreak++
                    if (badFrameStreak >= MP3_BAD_FRAME_MAX_STREAK) {
                        throw DecodeFailedException(
                            "MP3 连续 $badFrameStreak 帧解码失败，本曲文件已损坏",
                            badFrame
                        )
                    }
                    bitstream.closeFrame()
                    header = try {
                        bitstream.readFrame()
                    } catch (be: BitstreamException) {
                        if (isMp3TailError(be, readPhase = true)) {
                            throw Mp3TailStreamException(be)
                        }
                        throw be
                    }
                    continue
                }
                badFrameStreak = 0
                val sampleBuffer = decodedBuffer!!
                if (dataLine == null) {
                    msPerFrame = runCatching { header.ms_per_frame() }.getOrElse {
                        1152f / sampleBuffer.sampleFrequency * 1000f
                    }
                    if (durationMs == C.TIME_UNSET && contentBytes > 0 && header.bitrate() > 0) {
                        durationMs = contentBytes * 8L * 1000L / header.bitrate()
                    }
                    val format = AudioFormat(
                        sampleBuffer.sampleFrequency.toFloat(),
                        16, sampleBuffer.channelCount, true, false
                    )
                    // 2.0.3：设备打开失败（无输出设备/被占用/驱动异常）单独成
                    // AudioDeviceException——2.0.2 时它被统一映射成网络错误自动
                    // 跳歌，用户只看到歌一首首跳过去、完全无声无提示。
                    dataLine = openOutputLine(format)
                    line = dataLine
                    applyVolume()
                    pcmEffects.beginTrack()
                    speedResampler.reset()
                    synchronized(lock) {
                        error = null
                        setStateLocked(Player.STATE_READY)
                        if (playWhenReady) setPlayingLocked(true)
                    }
                    consecutiveFailures = 0
                    // 2.1.4：成功解出首帧——中途断流自动续播标记清零
                    // （与 consecutiveFailures 同落点同风格）。
                    if (bitstreamRetryItemId == activeItemId) bitstreamRetryItemId = null
                    // 2.2.0（A5-17）：尾部续播标记同落点清零。
                    if (tailRetryItemId == activeItemId) tailRetryItemId = null
                    fireEvents()
                }
                val len = sampleBuffer.bufferLength
                val shorts = sampleBuffer.buffer
                // 2.0.2 EQ：PCM 进线缆前过 10 段 biquad（未启用时零开销直通）。
                eqProcessor.process(shorts, len, sampleBuffer.channelCount, sampleBuffer.sampleFrequency)
                // 2.1.3（A4-05）：混响（未启用零开销），插入点与 EQ 一致。
                reverbProcessor.process(shorts, len, sampleBuffer.channelCount, sampleBuffer.sampleFrequency)
                // 2.2.0（L5）：变速重采样。链路顺序定死：EQ → 混响 →
                // 变速 → 淡入淡出/8D（PcmEffects）→ 写线缆。位置计数
                // 仍按输入帧（framesPlayed++ 在下方原样），位置语义
                // 与 1× 完全一致；speed==1 直通零拷贝。
                val (spShorts, spLen) = speedResampler.process(
                    shorts, len, sampleBuffer.channelCount, playbackParams.speed
                )
                // 2.0.5：淡入淡出增益斜坡 + 8D 环绕（PcmEffects，未启用零开销）。
                pcmEffects.process(spShorts, spLen, sampleBuffer.channelCount, sampleBuffer.sampleFrequency)
                pcmEffects.processSurround(spShorts, spLen, sampleBuffer.channelCount, sampleBuffer.sampleFrequency)
                val bytes = ByteArray(spLen * 2)
                var bi = 0
                for (i in 0 until spLen) {
                    val s = spShorts[i].toInt()
                    bytes[bi++] = (s and 0xFF).toByte()
                    bytes[bi++] = (s shr 8 and 0xFF).toByte()
                }
                dataLine.write(bytes, 0, bytes.size)
                framesPlayed++
                maybePrefetchNext(gen)
                bitstream.closeFrame()
                // 暂停淡出：增益落到 ~0 即 stop 线缆泊车（见循环头 parked 分支）。
                if (pausedFlag && !parked) {
                    pcmEffects.fadeOut()
                    if (pcmEffects.gain <= 0.002f) {
                        runCatching { dataLine.stop() }
                        parked = true
                    }
                }
                header = try {
                    bitstream.readFrame()
                } catch (e: BitstreamException) {
                    if (isMp3TailError(e, readPhase = true)) {
                        throw Mp3TailStreamException(e)
                    }
                    throw e
                }
            }
        } finally {
            try { bitstream.close() } catch (_: Exception) { }
            if (gen == generation) {
                sessionRunning = false
                try { dataLine?.drain() } catch (_: Exception) { }
                try { dataLine?.stop() } catch (_: Exception) { }
                try { dataLine?.close() } catch (_: Exception) { }
                if (line === dataLine) line = null
            }
        }
        if (gen != generation) return
        // Natural end of stream.（尾部断流的时长吸附改由 runDecodeSession
        // 的 A5-17 回落分支在续播失败后执行，口径与 2.1.4 一致。）
        val playedMs = seekBaseMs + (framesPlayed * msPerFrame).toLong()
        if (durationMs == C.TIME_UNSET) durationMs = playedMs
        onTrackEnded(gen)
    }

    /**
     * 2.2.0（L8）：预读下一首直链——当前曲播过约 80% 时，把下一首
     * （按 computeNextIndex 的自动切歌语义，含循环全部/随机）解析
     * 进 resolvedUrls（与主路径同 TTL 口径），切歌时省掉整段取链
     * 级联。只预解析、不下载音频本体；单曲循环不预读；解析失败
     * 静默（主路径切歌时会照常重试）。
     */
    private fun maybePrefetchNext(gen: Int) {
        if (gen != generation) return
        val dur = durationMs
        if (dur <= 0) return
        if (currentPosition * 5 < dur * 4) return
        if (repeatMode == Player.REPEAT_MODE_ONE) return
        val cur = currentMediaItem ?: return
        if (prefetchItemId == cur.mediaId) return
        val curIndex = index
        val nextIdx = computeNextIndex(curIndex, forAuto = true) ?: return
        if (nextIdx == curIndex) return
        val nextItem = synchronized(lock) { items.getOrNull(nextIdx) } ?: return
        prefetchItemId = cur.mediaId
        // 2.3.0（A6-08）：下一首是本地曲目时跳过在线取链级联——
        // 本地歌播时不走在线音源，预解析它的直链纯属白跑（还可能
        // 对 local: mediaId 调在线接口）。本地路径在切歌时直接由
        // 本地解码分支使用，无需预读。
        if (localFileOf(nextItem) != null) return
        val key = resolveCacheKey(nextItem)
        synchronized(lock) {
            val cached = resolvedUrls[key]
            if (cached != null &&
                System.currentTimeMillis() - cached.second < RESOLVED_URL_TTL_MS
            ) {
                return
            }
        }
        val quality = audioQuality
        scope.launch {
            val resolved = runCatching {
                nextItem.uri?.let { updateMediaItemUri(it, quality)?.toString() }
            }.getOrNull()
            if (!resolved.isNullOrBlank()) {
                synchronized(lock) {
                    resolvedUrls[key] = resolved to System.currentTimeMillis()
                }
                println("[DesktopPlayer] 预读：已预解析下一首直链（index=$nextIdx）")
            }
        }
    }

    /**
     * 2.1.4：BitstreamException 是否属于「MP3 尾部伪错误」。
     *
     * 背景：JLayer 的 STREAM_ERROR(0x102) 本质是底层流 IOException 的
     * 包装（CDN 尾部断流/连接复位时 readBytes/readFully 抛出），
     * UNEXPECTED_EOF(0x103)、STREAM_EOF(0x104) 同为流终结类；网易云
     * 不少 MP3 尾部又带 ID3v1/APE 标签或坏尾帧，播到尾声底层流一断
     * 就抛这类异常（真机实证：《阴天》3:56/4:02 处 errorcode 102 被
     * 误报成「网络或音源异常」）。主流播放器对尾部一律按播完处理，
     * 这里对齐。判据：
     *  - 时长已知：剩余（durationMs - 当前位置）≤ 10s 即尾部
     *    （见 [MP3_TAIL_TOLERANCE_MS]）；
     *  - 时长未知：异常须发生在 readFrame 找帧阶段、且已成功解码
     *    ≥ [MP3_TAIL_MIN_FRAMES] 帧（≈2.6s，证明在播真实音频而非
     *    整文件垃圾），才判流尾。
     * 其余（中途断流）返回 false，交回 runDecodeSession 自动续播/报错。
     * 注意位置按已解码帧数计：底层流有预读缓冲，断流被感知时解码位置
     * 只会更靠前，判据偏保守（宁可走续播，不误吞中途错误）。
     */
    private fun isMp3TailError(e: BitstreamException, readPhase: Boolean): Boolean {
        val code = e.errorCode
        if (code != BitstreamErrors.STREAM_ERROR &&
            code != BitstreamErrors.UNEXPECTED_EOF &&
            code != BitstreamErrors.STREAM_EOF
        ) {
            return false
        }
        val dur = durationMs
        if (dur != C.TIME_UNSET) {
            return dur - currentPosition <= MP3_TAIL_TOLERANCE_MS
        }
        return readPhase && framesPlayed >= MP3_TAIL_MIN_FRAMES
    }

    private fun onTrackEnded(gen: Int) {
        synchronized(lock) { setPlayingLocked(false) }
        val next = computeNextIndex(index, forAuto = true)
        when {
            repeatMode == Player.REPEAT_MODE_ONE -> {
                seekBaseMs = 0
                framesPlayed = 0
                fire {
                    it.onMediaItemTransition(
                        currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                    )
                }
                fireEvents()
                if (playWhenReady) startCurrentLocked()
            }
            next != null -> {
                seekInternal(next, 0, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            }
            else -> {
                synchronized(lock) { setStateLocked(Player.STATE_ENDED) }
                // 2.1.3（A4-03）：区分自然播完与流提前 EOF 假结束——时长
                // 已知却停在半路（差 >3s）即 C 类嫌疑，落盘供事后查因。
                val pos = currentPosition
                val dur = durationMs
                if (dur != C.TIME_UNSET && dur - pos > 3_000) {
                    PauseDiagnostics.record(
                        PauseDiagnostics.EOF_PREMATURE,
                        "mediaId=${currentMediaItem?.mediaId} pos=$pos dur=$dur"
                    )
                } else {
                    PauseDiagnostics.record(
                        PauseDiagnostics.TRACK_END,
                        "mediaId=${currentMediaItem?.mediaId} pos=$pos dur=$dur"
                    )
                }
                fire {
                    it.onPositionDiscontinuity(
                        Player.PositionInfo(index, currentMediaItem, positionMs = currentPosition),
                        Player.PositionInfo(index, currentMediaItem, positionMs = currentPosition),
                        Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                    )
                }
                fireEvents()
            }
        }
    }

    private fun applyVolume() {
        val l = line ?: return
        runCatching {
            if (l.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                val control = l.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
                val db = if (volume <= 0.001f) control.minimum
                else max(control.minimum, min(control.maximum, (20 * log10(volume.toDouble())).toFloat()))
                control.value = db
            }
        }
    }
}
