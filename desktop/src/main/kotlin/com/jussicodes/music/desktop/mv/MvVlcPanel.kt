package com.jussicodes.music.desktop.mv

import com.sun.jna.NativeLibrary
import java.awt.BorderLayout
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent

/**
 * MV 播放面板（2.1.1 起）：VLCJ（libVLC 3）嵌入式播放器，替代 2.1.0 的
 * JCEF HTML5 video 面板。
 *
 * 换引擎原因：2.1.0 发货包捆绑的标准版 CEF 132 构建不含 H.264 解码
 * （专有编解码器未编入，沙箱实测真 MV 与本地完好 MP4 全部 error:4），
 * 而网易云 MV 全是 H.264。libVLC 自带 FFmpeg 解码链（avcodec 插件），
 * H.264/AAC 为其原生支持格式，不依赖宿主系统编解码器。
 *
 * 原生库定位（Windows 发货包）：exe 同级 `vlc\` 目录（libvlc.dll +
 * libvlccore.dll + plugins\ 子集，随包分发，见 PORTING §7.16）。
 * 运行时先在候选目录里找 libvlc，找到就把目录注册进 JNA 搜索路径并
 * 以 `--plugin-path` 告知 libVLC 插件位置；找不到则回落 VLCJ 自带
 * NativeDiscovery（系统已装 VLC 的情形 / Linux 沙箱系统包）。
 *
 * 事件协议与旧面板一致（宿主 MvPlayerWindow 无需区分引擎）：
 * `pos:<currentTime 秒>:<duration 秒>:<paused 0/1>` 每 500ms 回报，
 * 另有 `ended` / `error:<说明>`。位置由宿主侧轮询 status 产生
 * （libVLC 的 timeChanged 事件在本管线里足够，但轮询与旧口径一致、
 * 且暂停态也能持续回报 UI 状态）。
 */
object MvVlcPanel {

    /**
     * MV 音量记忆（A3-09，2.1.2）：vlcj 音量量程 0..200、100 为标称，
     * UI 只暴露 0..100 标称段（不做超标称增益，避免失真/爆音）。
     * 存进程级单例而非某次窗口组合内：MvPlayerWindow 按 mvId key
     * 整窗重建（A3-01）时 remember 会全部重置，音量若只记在窗口内
     * 就会每次换片弹回满格；记在这里，换片/重开窗口沿用用户上次
     * 调好的音量，新进程才回默认标称 100。
     */
    @Volatile
    var rememberedVolume: Int = 100

    /**
     * MV 倍速记忆（2.2.0，L5）：与音量同理的进程级记忆——新 Session
     * 起播时回填。值的权威来源是落盘键 desktop.playbackSpeed（与音乐
     * 共用），MvPlayerWindow 负责把落盘值同步到这里并在改档时调用
     * Handle.setRate 即时生效。
     */
    @Volatile
    var rememberedRate: Float = 1f

    class Handle {
        @Volatile
        var panel: Session? = null

        /**
         * 最近一次初始化失败的可诊断原因（A3-05，2.1.2）：具体到缺哪个
         * 文件/查过哪些路径/异常类名，供宿主拼进用户文案。事件协议不变，
         * 仍只发 `error:vlc-init` 裸码，细节走这个旁路字段 + 日志。
         */
        @Volatile
        var lastFailureDetail: String? = null

        fun play() = runCatching { panel?.play() }
        fun pause() = runCatching { panel?.pause() }
        fun seekTo(seconds: Double) = runCatching { panel?.seekTo(seconds) }

        /** 设 MV 音量（入参为 vlcj 量程，内部钳到 UI 暴露的 0..100 标称段）。 */
        fun setVolume(vlcVolume: Int) {
            val v = vlcVolume.coerceIn(0, 100)
            rememberedVolume = v
            runCatching { panel?.setVolume(v) }
        }

        /** 播完重播（2.2.0，A5-13）：对当前 URL 重发一次 media play。 */
        fun replay() = runCatching { panel?.replay() }

        /** 设 MV 倍速（2.2.0，L5）：VLC 原生 rate，钳 0.5–2.0 与音乐侧一致。 */
        fun setRate(rate: Float) {
            val r = rate.coerceIn(0.5f, 2f)
            rememberedRate = r
            runCatching { panel?.setRate(r) }
        }

        fun dispose() {
            runCatching { panel?.dispose() }
            panel = null
        }
    }

    class Session(
        private val component: EmbeddedMediaPlayerComponent,
        private val onEvent: (String) -> Unit
    ) {
        private val player get() = component.mediaPlayer()
        private val disposed = AtomicBoolean(false)
        @Volatile private var lastLengthMs = -1L

        init {
            player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun finished(mp: MediaPlayer) = onEvent("ended")
                override fun error(mp: MediaPlayer) = onEvent("error:vlc")
                override fun lengthChanged(mp: MediaPlayer, newLength: Long) {
                    lastLengthMs = newLength
                }
            })
            Thread({
                while (!disposed.get()) {
                    try {
                        Thread.sleep(500)
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (disposed.get()) break
                    runCatching {
                        val timeMs = player.status().time()
                        val lenMs = player.status().length().takeIf { it > 0 } ?: lastLengthMs
                        val paused = !player.status().isPlaying()
                        onEvent(
                            "pos:${timeMs.coerceAtLeast(0) / 1000.0}:" +
                                "${lenMs.coerceAtLeast(0) / 1000.0}:${if (paused) 1 else 0}"
                        )
                    }
                }
            }, "mv-vlc-poll").apply { isDaemon = true }.start()
        }

        private val startOnce = AtomicBoolean(false)

        /** 当前 URL（replay 用）与起播恢复位置（清晰度切换保进度，L6）。 */
        @Volatile
        private var currentUrl: String? = null

        @Volatile
        private var pendingStartSec: Double = 0.0

        /** 组件可显示后起播（见 createComponent 注释）；只执行一次。 */
        fun startWhenDisplayable(url: String, startAtSec: Double = 0.0) {
            currentUrl = url
            pendingStartSec = startAtSec
            Thread({
                val deadline = System.currentTimeMillis() + 15000
                while (!disposed.get() && System.currentTimeMillis() < deadline) {
                    if (component.isDisplayable) break
                    try {
                        Thread.sleep(100)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
                if (disposed.get() || !startOnce.compareAndSet(false, true)) return@Thread
                if (!component.isDisplayable) {
                    System.err.println("[MV-VLC] component never became displayable")
                    onEvent("error:vlc-init")
                    return@Thread
                }
                val started = runCatching { player.media().play(url) }
                    .getOrElse {
                        System.err.println("[MV-VLC] play() failed: $it")
                        onEvent("error:vlc")
                        false
                    }
                if (!started) {
                    onEvent("error:vlc")
                } else {
                    // 起播后回填记忆音量（A3-09）：libVLC 的音量是播放器级
                    // 状态，新建 Session 默认标称值，用户调过的量在这里找回来。
                    runCatching { player.audio().setVolume(rememberedVolume) }
                    // 2.2.0（L5）：回填记忆倍速。
                    runCatching { player.controls().setRate(rememberedRate) }
                    // 2.2.0（L6）：清晰度切换重建面板时恢复播放位置。
                    if (pendingStartSec > 0.5) {
                        runCatching {
                            player.controls().setTime((pendingStartSec * 1000).toLong())
                        }
                    }
                }
            }, "mv-vlc-start").apply { isDaemon = true }.start()
        }

        fun play() = player.controls().play()
        fun pause() = player.controls().pause()
        fun seekTo(seconds: Double) = player.controls().setTime((seconds * 1000).toLong())

        /**
         * 播完重播（2.2.0，A5-13）：finished 后播放器停在末尾，单靠
         * play()/setTime(0) 在 libVLC 上不可靠，须对原 URL 重发一次
         * media().play(url) 从头播，并回填倍速。
         */
        fun replay() {
            val url = currentUrl ?: return
            runCatching { player.media().play(url) }
            runCatching { player.controls().setRate(rememberedRate) }
        }

        /** MV 倍速（2.2.0，L5）：VLC 原生 setRate（内部时间拉伸，音调处理与音乐侧简单重采样不同）。 */
        fun setRate(rate: Float) = runCatching { player.controls().setRate(rate) }

        /** vlcj 量程 0..200（100 标称）；宿主 Handle 已钳到 0..100 标称段。 */
        fun setVolume(vlcVolume: Int) =
            player.audio().setVolume(vlcVolume.coerceIn(0, 200))

        fun dispose() {
            if (!disposed.compareAndSet(false, true)) return
            runCatching { player.controls().stop() }
            runCatching { component.release() }
        }
    }

    fun createComponent(
        url: String,
        handle: Handle,
        startAtSec: Double = 0.0,
        onEvent: (String) -> Unit
    ): JComponent {
        val panel = JPanel(BorderLayout())
        handle.lastFailureDetail = null
        val locate = locateBundledVlc()
        val bundledDir = locate.dir
        val args = mutableListOf("--no-video-title-show", "--network-caching=1500")
        if (bundledDir != null) {
            // JNA 搜索路径注册 libvlc 本体；插件目录用 libVLC 自带选项告知，
            // 不依赖进程环境变量（Java 进程内改 env 对已启动进程不可靠）。
            runCatching {
                NativeLibrary.addSearchPath(
                    RuntimeUtil.getLibVlcLibraryName(), bundledDir.absolutePath
                )
            }
            val plugins = File(bundledDir, "plugins")
            if (plugins.isDirectory) {
                args += "--plugin-path=${plugins.absolutePath}"
            }
            println("[MV-VLC] bundled libvlc dir=${bundledDir.absolutePath}")
        } else {
            // 回落路径不变（NativeDiscovery 系统发现），但把查过的候选与
            // 缺失情况留进日志（A3-05），初始化真失败时这份摘要会一并
            // 进用户文案，不再只有一句「详见日志」。
            println("[MV-VLC] no bundled libvlc found, falling back to system discovery")
            System.err.println("[MV-VLC] ${locate.summary}")
        }
        val component = try {
            EmbeddedMediaPlayerComponent(*args.toTypedArray())
        } catch (t: Throwable) {
            System.err.println("[MV-VLC] init failed: $t")
            t.printStackTrace()
            // A3-05：失败原因具体化——异常类名+简要信息、随包引擎定位
            // 摘要（缺 libvlc.dll 时列出找过的关键路径），宿主与本面板
            // label 共用这一份，另附杀软隔离/解压完整性自查提示。
            val exceptionInfo = "${t.javaClass.simpleName}：${t.message?.take(160) ?: "无附加信息"}"
            val detail = if (bundledDir != null) {
                "随包引擎目录 ${bundledDir.absolutePath} 已找到，但引擎初始化异常（$exceptionInfo）"
            } else {
                "${locate.summary}；系统也未发现可用的 VLC，引擎初始化异常（$exceptionInfo）"
            }
            handle.lastFailureDetail = detail
            val hint = "请检查杀毒软件隔离区是否有 libvlc 相关文件被隔离，" +
                "并确认程序已完整解压（vlc 文件夹须与主程序在同一目录）后重试"
            val label = JLabel(
                "<html><div style='text-align:center'>VLC 播放引擎初始化失败，无法播放 MV<br>" +
                    escapeHtml(detail) + "<br>" + escapeHtml(hint) + "</div></html>",
                SwingConstants.CENTER
            )
            panel.add(label, BorderLayout.CENTER)
            onEvent("error:vlc-init")
            return panel
        }
        val session = Session(component, onEvent)
        handle.panel = session
        panel.add(component, BorderLayout.CENTER)
        // libVLC 的嵌入式视频面要求组件 displayable 后才能起播（否则
        // IllegalStateException: video surface component must be
        // displayable）。SwingPanel factory 运行时组件尚未挂到可见窗口，
        // 故起播推迟到组件可显示后（轮询等待，上限 15 秒）。
        session.startWhenDisplayable(url, startAtSec)
        return panel
    }

    /** 随包 vlc 定位结果：命中目录 + 逐候选检查摘要（A3-05 诊断用）。 */
    private data class BundledLocateResult(
        val dir: File?,
        val libFileName: String,
        /** 逐候选的人话摘要，如「X（目录不存在）」「Y（缺 libvlc.dll）」。 */
        val checked: List<String>,
        val summary: String
    )

    /**
     * 随包 vlc 目录候选（按优先级）：
     * 1. 系统属性 `penguin.vlc.dir`（探针/调试覆盖）；
     * 2. 工作目录下 `vlc\`（Launch4j chdir 到 exe 目录，发货包主路径）；
     * 3. fat jar / classes 所在目录及其上级下的 `vlc\`（开发期直跑 jar 时）。
     * 目录内须有当前平台的 libvlc 本体文件才算命中。
     */
    private fun locateBundledVlc(): BundledLocateResult {
        val libFileName = when {
            RuntimeUtil.isWindows() -> "libvlc.dll"
            RuntimeUtil.isMac() -> "libvlc.dylib"
            else -> "libvlc.so"
        }
        val candidates = mutableListOf<File>()
        System.getProperty("penguin.vlc.dir")?.takeIf { it.isNotBlank() }
            ?.let { candidates += File(it) }
        candidates += File(System.getProperty("user.dir"), "vlc")
        runCatching {
            val src = MvVlcPanel::class.java.protectionDomain.codeSource.location
            val anchor = File(src.toURI())
            val base = if (anchor.isFile) anchor.parentFile else anchor
            if (base != null) {
                candidates += File(base, "vlc")
                candidates += File(base.parentFile, "vlc")
            }
        }
        val distinct = candidates.distinctBy { it.absolutePath }
        val checked = distinct.map { dir ->
            when {
                !dir.isDirectory -> "${dir.absolutePath}（目录不存在）"
                !File(dir, libFileName).isFile -> "${dir.absolutePath}（缺 $libFileName）"
                else -> "${dir.absolutePath}（含 $libFileName）"
            }
        }
        val hit = distinct.firstOrNull { dir ->
            dir.isDirectory && File(dir, libFileName).isFile
        }
        val summary = if (hit != null) {
            "随包 libvlc 命中 ${hit.absolutePath}"
        } else {
            "未找到随包播放引擎（$libFileName），已检查：${checked.joinToString("；")}"
        }
        return BundledLocateResult(hit, libFileName, checked, summary)
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
