package com.jussicodes.music.desktop.local

import java.io.File
import java.io.RandomAccessFile

/** 本地音频标签（读不到的字段为 null / 时长 0，调用方用文件名兜底）。 */
data class LocalTag(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0L
)

/**
 * 本地音乐标签解析（2.1.0，手写轻量实现，不引第三方库）：
 * - MP3：ID3v2.3/2.4 文本帧（TIT2/TPE1/TALB）+ ID3v1 兜底；
 *   时长优先 Xing/Info 头帧数，否则首帧码率估算；
 * - FLAC：STREAMINFO（采样率/总采样数）+ Vorbis Comment；
 * - WAV：fmt/data 块算时长 + LIST INFO（INAM/IART/IPRD）；
 * - M4A：moov/udta/meta/ilst 的 ©nam/©ART/©alb + mvhd 时长。
 * 全部只读文件头/尾的少量字节（M4A 原子遍历除外），解析失败不抛异常。
 */
object LocalTags {

    fun read(file: File): LocalTag = runCatching {
        when (file.extension.lowercase()) {
            "mp3" -> readMp3(file)
            "flac" -> readFlac(file)
            "wav" -> readWav(file)
            "m4a", "mp4", "aac" -> readM4a(file)
            else -> LocalTag()
        }
    }.getOrDefault(LocalTag())

    // ------------------------------------------------------------------
    // MP3
    // ------------------------------------------------------------------

    private fun readMp3(file: File): LocalTag {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var durationMs = 0L
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            var audioStart = 0L
            // ID3v2
            if (len >= 10) {
                val head = ByteArray(10)
                raf.readFully(head)
                if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() &&
                    head[2] == '3'.code.toByte()
                ) {
                    val major = head[3].toInt()
                    val tagSize = synchsafe(head, 6)
                    audioStart = 10L + tagSize
                    if (major == 3 || major == 4) {
                        val body = ByteArray(tagSize.coerceAtMost(2 * 1024 * 1024).toInt())
                        raf.seek(10)
                        raf.readFully(body)
                        var off = 0
                        while (off + 10 <= body.size) {
                            val id = String(body, off, 4, Charsets.ISO_8859_1)
                            if (id.all { it == '\u0000' } || !id.all { it.isLetterOrDigit() }) break
                            val sizeRaw = if (major == 4) {
                                synchsafe(body, off + 4)
                            } else {
                                u32be(body, off + 4)
                            }
                            val size = sizeRaw.toInt()
                            if (size <= 0 || off + 10 + size > body.size) break
                            if (id == "TIT2" || id == "TPE1" || id == "TALB") {
                                val text = decodeId3Text(body, off + 10, size)
                                when (id) {
                                    "TIT2" -> title = text
                                    "TPE1" -> artist = text
                                    "TALB" -> album = text
                                }
                            }
                            off += 10 + size
                        }
                    }
                }
            }
            // ID3v1 兜底
            if ((title == null || artist == null || album == null) && len >= 128) {
                raf.seek(len - 128)
                val tail = ByteArray(128)
                raf.readFully(tail)
                if (tail[0] == 'T'.code.toByte() && tail[1] == 'A'.code.toByte() &&
                    tail[2] == 'G'.code.toByte()
                ) {
                    fun field(from: Int, size: Int): String? =
                        String(tail, from, size, Charsets.ISO_8859_1)
                            .trim('\u0000', ' ').ifBlank { null }
                    if (title == null) title = field(3, 30)
                    if (artist == null) artist = field(33, 30)
                    if (album == null) album = field(63, 30)
                }
            }
            durationMs = mp3Duration(raf, audioStart, len)
        }
        return LocalTag(title?.clean(), artist?.clean(), album?.clean(), durationMs)
    }

    /** MPEG 帧头表（码率 kbps，Layer III 列）。 */
    private val mp3Bitrates = intArrayOf(
        0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0
    )
    private val mp3SampleRates = intArrayOf(44100, 48000, 32000, 0)

    private fun mp3Duration(raf: RandomAccessFile, audioStart: Long, fileLen: Long): Long {
        // 找第一个 MPEG 同步字（限找 64KB，避免在大封面图里误同步）
        val scan = ByteArray(64 * 1024)
        raf.seek(audioStart)
        val read = raf.read(scan)
        if (read < 4) return 0L
        var frameOff = -1
        var header = 0
        for (i in 0 until read - 3) {
            if (scan[i] == 0xFF.toByte() && (scan[i + 1].toInt() and 0xE0) == 0xE0) {
                val h = u32be(scan, i).toInt()
                val version = (h shr 19) and 3
                val layer = (h shr 17) and 3
                val brIdx = (h shr 12) and 0xF
                val srIdx = (h shr 10) and 3
                if (version != 1 && layer == 1 && brIdx in 1..14 && srIdx in 0..2) {
                    frameOff = i
                    header = h
                    break
                }
            }
        }
        if (frameOff < 0) return 0L
        val version = (header shr 19) and 3
        val brIdx = (header shr 12) and 0xF
        val srIdx = (header shr 10) and 3
        val channels = (header shr 6) and 3
        val sampleRate = when (version) {
            3 -> mp3SampleRates[srIdx] // MPEG1
            2 -> mp3SampleRates[srIdx] / 2 // MPEG2
            else -> mp3SampleRates[srIdx] / 4 // MPEG2.5
        }
        if (sampleRate <= 0) return 0L
        // Xing/Info：MPEG1 双声道偏移 36、单声道 21；MPEG2 双 21、单 13（相对帧头）
        val xingOff = frameOff + when {
            version == 3 -> if (channels == 3) 21 else 36
            else -> if (channels == 3) 13 else 21
        }
        if (xingOff + 16 <= read) {
            val magic = String(scan, xingOff, 4, Charsets.ISO_8859_1)
            if (magic == "Xing" || magic == "Info") {
                val flags = u32be(scan, xingOff + 4)
                if ((flags and 1) != 0L) {
                    val frames = u32be(scan, xingOff + 8)
                    val samplesPerFrame = if (version == 3) 1152 else 576
                    if (frames > 0) return frames * samplesPerFrame * 1000L / sampleRate
                }
            }
        }
        val bitrateKbps = mp3Bitrates[brIdx]
        if (bitrateKbps <= 0) return 0L
        val audioBytes = fileLen - audioStart - frameOff
        return if (audioBytes > 0) audioBytes * 8L / bitrateKbps else 0L
    }

    /** Xing/Info 头解析结果（2.2.0，A5-07）：供 VBR seek 落点查表。 */
    private data class XingInfo(
        /** 音频数据绝对起始偏移（ID3 + 首帧帧头之前）。 */
        val audioStartAbs: Long,
        val totalBytes: Long,
        val toc: ByteArray?,
        val durationMs: Long
    )

    /**
     * VBR seek 落点（2.2.0，A5-07）：按 Xing TOC 把目标时间映射为文件
     * 字节偏移（TOC 100 项线性插值，口径与 LAME/Xing 规范一致）。
     * 无 Xing 头 / 无 TOC / 时长未知时返回 null，调用方回落平均码率
     * 估算（CBR 文件两者等价，VBR 旧估算能差数十秒）。
     * 返回的是「应跳过的字节数」——调用方从文件头 seek，与
     * decodeMp3Local 的 skipFully 口径一致（audioStartAbs 已包含
     * ID3 与首帧头之前的全部字节）。
     */
    fun xingByteOffset(file: File, targetMs: Long): Long? {
        if (targetMs <= 0) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                var audioStart = 0L
                if (len >= 10) {
                    val head = ByteArray(10)
                    raf.readFully(head)
                    if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() &&
                        head[2] == '3'.code.toByte()
                    ) {
                        audioStart = 10L + synchsafe(head, 6)
                    }
                }
                val info = parseXing(raf, audioStart, len) ?: return@use null
                val toc = info.toc ?: return@use null
                if (info.durationMs <= 0L || info.totalBytes <= 0L) return@use null
                val fraction = (targetMs.toDouble() / info.durationMs)
                    .coerceIn(0.0, 1.0)
                val fa = fraction * 100.0
                val i = fa.toInt().coerceIn(0, 99)
                val a = toc[i].toInt() and 0xFF
                val b = if (i < 99) toc[i + 1].toInt() and 0xFF else 256
                val interp = a + (b - a) * (fa - i)
                val rel = (interp / 256.0 * info.totalBytes).toLong()
                (info.audioStartAbs + rel).coerceIn(0L, maxOf(0L, len - 1))
            }
        }.getOrNull()
    }

    /** 解析 Xing/Info 头（帧数/字节数/TOC），与 mp3Duration 同一扫描口径。 */
    private fun parseXing(
        raf: RandomAccessFile,
        audioStart: Long,
        fileLen: Long
    ): XingInfo? {
        val scan = ByteArray(64 * 1024)
        raf.seek(audioStart)
        val read = raf.read(scan)
        if (read < 4) return null
        var frameOff = -1
        var header = 0
        for (i in 0 until read - 3) {
            if (scan[i] == 0xFF.toByte() && (scan[i + 1].toInt() and 0xE0) == 0xE0) {
                val h = u32be(scan, i).toInt()
                val version = (h shr 19) and 3
                val layer = (h shr 17) and 3
                val brIdx = (h shr 12) and 0xF
                val srIdx = (h shr 10) and 3
                if (version != 1 && layer == 1 && brIdx in 1..14 && srIdx in 0..2) {
                    frameOff = i
                    header = h
                    break
                }
            }
        }
        if (frameOff < 0) return null
        val version = (header shr 19) and 3
        val srIdx = (header shr 10) and 3
        val channels = (header shr 6) and 3
        val sampleRate = when (version) {
            3 -> mp3SampleRates[srIdx]
            2 -> mp3SampleRates[srIdx] / 2
            else -> mp3SampleRates[srIdx] / 4
        }
        if (sampleRate <= 0) return null
        val xingOff = frameOff + when {
            version == 3 -> if (channels == 3) 21 else 36
            else -> if (channels == 3) 13 else 21
        }
        if (xingOff + 8 > read) return null
        val magic = String(scan, xingOff, 4, Charsets.ISO_8859_1)
        if (magic != "Xing" && magic != "Info") return null
        val flags = u32be(scan, xingOff + 4)
        var p = xingOff + 8
        var frames = 0L
        var bytes = 0L
        var toc: ByteArray? = null
        if ((flags and 1L) != 0L) {
            if (p + 4 > read) return null
            frames = u32be(scan, p)
            p += 4
        }
        if ((flags and 2L) != 0L) {
            if (p + 4 > read) return null
            bytes = u32be(scan, p)
            p += 4
        }
        if ((flags and 4L) != 0L) {
            if (p + 100 > read) return null
            toc = scan.copyOfRange(p, p + 100)
            p += 100
        }
        if (frames <= 0) return null
        val samplesPerFrame = if (version == 3) 1152 else 576
        val durationMs = frames * samplesPerFrame * 1000L / sampleRate
        val totalBytes = if (bytes > 0) bytes else fileLen - audioStart - frameOff
        return XingInfo(audioStart + frameOff, totalBytes, toc, durationMs)
    }

    private fun decodeId3Text(body: ByteArray, off: Int, size: Int): String? {
        if (size <= 1) return null
        val enc = body[off].toInt()
        val raw = body.copyOfRange(off + 1, off + size)
        val text = when (enc) {
            1 -> {
                // UTF-16 带 BOM
                if (raw.size >= 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte()) {
                    String(raw, 2, raw.size - 2, Charsets.UTF_16LE)
                } else if (raw.size >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte()) {
                    String(raw, 2, raw.size - 2, Charsets.UTF_16BE)
                } else {
                    String(raw, Charsets.UTF_16BE)
                }
            }
            2 -> String(raw, Charsets.UTF_16BE)
            3 -> String(raw, Charsets.UTF_8)
            else -> String(raw, Charsets.ISO_8859_1)
        }
        // 文本帧可能以 \0 分隔多值，取第一段即可。
        return text.substringBefore('\u0000').trim().ifBlank { null }
    }

    // ------------------------------------------------------------------
    // FLAC
    // ------------------------------------------------------------------

    private fun readFlac(file: File): LocalTag {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var durationMs = 0L
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 8) return LocalTag()
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (String(magic, Charsets.ISO_8859_1) != "fLaC") return LocalTag()
            var last = false
            var guard = 0
            while (!last && guard++ < 32) {
                val h = raf.read()
                if (h < 0) break
                last = (h and 0x80) != 0
                val type = h and 0x7F
                val lenBytes = ByteArray(3)
                raf.readFully(lenBytes)
                val blockLen = ((lenBytes[0].toInt() and 0xFF) shl 16) or
                    ((lenBytes[1].toInt() and 0xFF) shl 8) or (lenBytes[2].toInt() and 0xFF)
                when (type) {
                    0 -> { // STREAMINFO（34 字节）
                        val si = ByteArray(34)
                        raf.readFully(si)
                        val sampleRate = ((si[10].toInt() and 0xFF) shl 12) or
                            ((si[11].toInt() and 0xFF) shl 4) or
                            ((si[12].toInt() and 0xFF) shr 4)
                        val totalSamples = ((si[13].toLong() and 0x0F) shl 32) or
                            ((si[14].toLong() and 0xFF) shl 24) or
                            ((si[15].toLong() and 0xFF) shl 16) or
                            ((si[16].toLong() and 0xFF) shl 8) or
                            (si[17].toLong() and 0xFF)
                        if (sampleRate > 0 && totalSamples > 0) {
                            durationMs = totalSamples * 1000L / sampleRate
                        }
                    }
                    4 -> { // VORBIS_COMMENT
                        val data = ByteArray(blockLen.coerceAtMost(4 * 1024 * 1024))
                        raf.readFully(data)
                        var p = 0
                        fun le32(): Int {
                            val v = (data[p].toInt() and 0xFF) or
                                ((data[p + 1].toInt() and 0xFF) shl 8) or
                                ((data[p + 2].toInt() and 0xFF) shl 16) or
                                ((data[p + 3].toInt() and 0xFF) shl 24)
                            p += 4
                            return v
                        }
                        if (data.size >= 8) {
                            val vendorLen = le32()
                            p += vendorLen
                            if (p + 4 <= data.size) {
                                val count = le32()
                                for (i in 0 until count) {
                                    if (p + 4 > data.size) break
                                    val entryLen = le32()
                                    if (entryLen < 0 || p + entryLen > data.size) break
                                    val entry = String(data, p, entryLen, Charsets.UTF_8)
                                    p += entryLen
                                    val eq = entry.indexOf('=')
                                    if (eq > 0) {
                                        val key = entry.substring(0, eq).uppercase()
                                        val value = entry.substring(eq + 1).trim()
                                        when (key) {
                                            "TITLE" -> if (title == null) title = value
                                            "ARTIST" -> if (artist == null) artist = value
                                            "ALBUM" -> if (album == null) album = value
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> raf.seek(raf.filePointer + blockLen)
                }
            }
        }
        return LocalTag(title?.clean(), artist?.clean(), album?.clean(), durationMs)
    }

    // ------------------------------------------------------------------
    // WAV
    // ------------------------------------------------------------------

    private fun readWav(file: File): LocalTag {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var durationMs = 0L
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 44) return LocalTag()
            val head = ByteArray(12)
            raf.readFully(head)
            if (String(head, 0, 4, Charsets.ISO_8859_1) != "RIFF" ||
                String(head, 8, 4, Charsets.ISO_8859_1) != "WAVE"
            ) return LocalTag()
            var byteRate = 0L
            var dataSize = 0L
            while (raf.filePointer + 8 <= raf.length()) {
                val chunkHead = ByteArray(8)
                raf.readFully(chunkHead)
                val id = String(chunkHead, 0, 4, Charsets.ISO_8859_1)
                val size = u32le(chunkHead, 4)
                val start = raf.filePointer
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(minOf(size, 40L).toInt())
                        raf.readFully(fmt)
                        if (fmt.size >= 12) byteRate = u32le(fmt, 8)
                    }
                    "data" -> dataSize = size
                    "LIST" -> {
                        val list = ByteArray(minOf(size, 64 * 1024L).toInt())
                        raf.readFully(list)
                        if (list.size >= 4 &&
                            String(list, 0, 4, Charsets.ISO_8859_1) == "INFO"
                        ) {
                            var p = 4
                            while (p + 8 <= list.size) {
                                val subId = String(list, p, 4, Charsets.ISO_8859_1)
                                val subSize = u32le(list, p + 4).toInt()
                                if (subSize < 0 || p + 8 + subSize > list.size) break
                                val value = String(list, p + 8, subSize, Charsets.UTF_8)
                                    .trim('\u0000', ' ')
                                when (subId) {
                                    "INAM" -> title = value
                                    "IART" -> artist = value
                                    "IPRD" -> album = value
                                }
                                p += 8 + subSize + (subSize and 1)
                            }
                        }
                    }
                }
                raf.seek(start + size + (size and 1))
            }
            if (byteRate > 0 && dataSize > 0) durationMs = dataSize * 1000L / byteRate
        }
        return LocalTag(title?.clean(), artist?.clean(), album?.clean(), durationMs)
    }

    // ------------------------------------------------------------------
    // M4A（ISO-BMFF 原子）
    // ------------------------------------------------------------------

    private fun readM4a(file: File): LocalTag {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var durationMs = 0L
        RandomAccessFile(file, "r").use { raf ->
            val fileLen = raf.length()
            // 顶层找 moov
            var pos = 0L
            while (pos + 8 <= fileLen) {
                val (size, type, headerSize) = readAtomHeader(raf, pos, fileLen) ?: break
                if (type == "moov") {
                    val moovEnd = pos + size
                    var p = pos + headerSize
                    while (p + 8 <= moovEnd) {
                        val (s2, t2, h2) = readAtomHeader(raf, p, moovEnd) ?: break
                        when (t2) {
                            "mvhd" -> {
                                raf.seek(p + h2)
                                val ver = raf.read()
                                if (ver == 0) {
                                    raf.seek(p + h2 + 4 + 8)
                                    val timescale = raf.readInt().toLong() and 0xFFFFFFFFL
                                    val duration = raf.readInt().toLong() and 0xFFFFFFFFL
                                    if (timescale > 0) {
                                        durationMs = duration * 1000L / timescale
                                    }
                                } else if (ver == 1) {
                                    raf.seek(p + h2 + 4 + 16)
                                    val timescale = raf.readInt().toLong() and 0xFFFFFFFFL
                                    val duration = raf.readLong()
                                    if (timescale > 0) {
                                        durationMs = duration * 1000L / timescale
                                    }
                                }
                            }
                            "udta" -> {
                                // udta → meta（meta 有 4 字节 version/flags）→ ilst
                                var q = p + h2
                                val udtaEnd = p + s2
                                while (q + 8 <= udtaEnd) {
                                    val (s3, t3, h3) = readAtomHeader(raf, q, udtaEnd) ?: break
                                    if (t3 == "meta") {
                                        var r = q + h3 + 4
                                        val metaEnd = q + s3
                                        while (r + 8 <= metaEnd) {
                                            val (s4, t4, h4) =
                                                readAtomHeader(raf, r, metaEnd) ?: break
                                            if (t4 == "ilst") {
                                                var w = r + h4
                                                val ilstEnd = r + s4
                                                while (w + 8 <= ilstEnd) {
                                                    val (s5, t5, h5) = readAtomHeader(
                                                        raf, w, ilstEnd
                                                    ) ?: break
                                                    if (s5 > h5) {
                                                        // 子原子 data：+4 type flags +4 locale
                                                        raf.seek(w + h5)
                                                        val (ds, dt, dh) = readAtomHeader(
                                                            raf, w + h5, w + s5
                                                        ) ?: Triple(0L, "", 0L)
                                                        if (dt == "data" && ds > dh + 8) {
                                                            val textLen =
                                                                (ds - dh - 8).toInt()
                                                                    .coerceAtMost(4096)
                                                            val buf = ByteArray(textLen)
                                                            raf.seek(w + h5 + dh + 8)
                                                            raf.readFully(buf)
                                                            val value = String(buf, Charsets.UTF_8)
                                                                .trim('\u0000', ' ')
                                                            when (t5) {
                                                                "©nam" -> title = value
                                                                "©ART", "aART" -> artist = value
                                                                "©alb" -> album = value
                                                            }
                                                        }
                                                    }
                                                    if (s5 <= 0) break
                                                    w += s5
                                                }
                                            }
                                            if (s4 <= 0) break
                                            r += s4
                                        }
                                    }
                                    if (s3 <= 0) break
                                    q += s3
                                }
                            }
                        }
                        if (s2 <= 0) break
                        p += s2
                    }
                    break
                }
                if (size <= 0) break
                pos += size
            }
        }
        return LocalTag(title?.clean(), artist?.clean(), album?.clean(), durationMs)
    }

    /** 读原子头：返回 (总大小, 类型, 头字节数)；size==1 时 64 位大小。 */
    private fun readAtomHeader(
        raf: RandomAccessFile,
        pos: Long,
        limit: Long
    ): Triple<Long, String, Long>? {
        if (pos + 8 > limit) return null
        raf.seek(pos)
        val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
        val typeBytes = ByteArray(4)
        raf.readFully(typeBytes)
        val type = String(typeBytes, Charsets.ISO_8859_1)
        return when (size32) {
            1L -> {
                val size64 = raf.readLong()
                Triple(size64, type, 16L)
            }
            0L -> Triple(limit - pos, type, 8L)
            else -> Triple(size32, type, 8L)
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private fun synchsafe(bytes: ByteArray, off: Int): Long =
        ((bytes[off].toLong() and 0x7F) shl 21) or
            ((bytes[off + 1].toLong() and 0x7F) shl 14) or
            ((bytes[off + 2].toLong() and 0x7F) shl 7) or
            (bytes[off + 3].toLong() and 0x7F)

    private fun u32be(bytes: ByteArray, off: Int): Long =
        ((bytes[off].toLong() and 0xFF) shl 24) or
            ((bytes[off + 1].toLong() and 0xFF) shl 16) or
            ((bytes[off + 2].toLong() and 0xFF) shl 8) or
            (bytes[off + 3].toLong() and 0xFF)

    private fun u32le(bytes: ByteArray, off: Int): Long =
        (bytes[off].toLong() and 0xFF) or
            ((bytes[off + 1].toLong() and 0xFF) shl 8) or
            ((bytes[off + 2].toLong() and 0xFF) shl 16) or
            ((bytes[off + 3].toLong() and 0xFF) shl 24)

    private fun String.clean(): String? = trim().ifBlank { null }
}
