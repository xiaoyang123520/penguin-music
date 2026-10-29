package com.jussicodes.music.desktop.ui

import com.jussicodes.music.utils.NetProxy
import com.rcmiku.ncmapi.api.apiGet
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 云盘上传（2.0.5，C8）：NOS 四步链（对齐 api-enhanced module/cloud.js）——
 * 1. check：POST /api/cloud/upload/check（md5+文件大小），服务端查重：
 *    已有同 md5 的云盘曲目时走 import 秒传，不必真传字节；
 * 2. token：/api/nos/token/alloc 申请音频桶预签名 token + objectKey；
 * 3. upload：文件字节裸 POST 到 NOS 预签名 URL（x-nos-token 头），带进度；
 * 4. info + pub：/api/cloud/upload/info/v2 落元数据、/api/cloud/pub/v2 发布。
 *
 * 每一步的失败都带步骤名抛出，页面可见；进度经 [onProgress] 回调
 * （step 文案 + 0..1 进度，字节步进只在 upload 步有真实百分比）。
 */
object CloudUploadHelper {

    private const val CLOUD_BUCKET = "jd-musicrep-privatecloud-audio-public"

    data class Progress(val step: String, val fraction: Float?)

    suspend fun upload(
        file: File,
        uid: Long,
        onProgress: (Progress) -> Unit
    ): String {
        require(file.exists() && file.length() > 0) { "文件不存在或为空" }
        val ext = file.extension.lowercase().ifBlank { "mp3" }
        onProgress(Progress("计算文件指纹…", null))
        val md5 = md5Hex(file)

        // ---- 1. check ----
        onProgress(Progress("校验云端是否已有（check）…", null))
        val check = apiGet<JsonObject>(
            "/cloud/upload/check",
            mapOf(
                "bitrate" to 999000,
                "ext" to ext,
                "length" to file.length(),
                "md5" to md5,
                "songId" to 0,
            )
        ).getOrElse { throw IllegalStateException("check 失败：${it.message}") }
        val checkData = check["data"]?.jsonObject ?: check
        val checkedSongId = checkData["songId"]?.jsonPrimitive?.longOrNull ?: 0L
        val needUpload = checkData["needUpload"]?.jsonPrimitive?.booleanOrNull ?: true
        if (checkedSongId > 0 && !needUpload) {
            // 秒传：云端已有同 md5，直接导入到我的云盘
            onProgress(Progress("云端已有此曲，秒传导入…", null))
            val imported = apiGet<JsonObject>(
                "/cloud/import", mapOf("songId" to checkedSongId, "uid" to uid)
            ).getOrElse { throw IllegalStateException("秒传导入失败：${it.message}") }
            val code = imported["code"]?.jsonPrimitive?.longOrNull
            if (code != null && code != 200L) {
                throw IllegalStateException("秒传导入被拒（code=$code）")
            }
            return "秒传成功（云端已有同文件）"
        }

        // ---- 2. token ----
        onProgress(Progress("申请上传凭证（token）…", null))
        val alloc = apiGet<JsonObject>(
            "/nos/token/alloc",
            mapOf(
                "bucket" to CLOUD_BUCKET,
                "ext" to ext,
                "filename" to file.name,
                "local" to false,
                "nos_product" to 3,
                "return_body" to "{\"code\":200,\"size\":\"\$(ObjectSize)\"}",
                "type" to "audio",
            )
        ).getOrElse { throw IllegalStateException("token 申请失败：${it.message}") }
        val result = alloc["result"]?.jsonObject
            ?: throw IllegalStateException("token 申请未返回 result")
        val token = result["token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val objectKey = result["objectKey"]?.jsonPrimitive?.contentOrNull
            ?: result["resourceId"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("token 申请未返回 objectKey")
        val resourceId = result["resourceId"]?.jsonPrimitive?.contentOrNull ?: objectKey
        val songId = result["songId"]?.jsonPrimitive?.longOrNull
            ?: checkedSongId
        if (token.isBlank()) throw IllegalStateException("token 申请返回空 token")

        // ---- 3. upload bytes ----
        onProgress(Progress("上传文件到云存储…", 0f))
        uploadToNos(file, objectKey, token, ext) { fraction ->
            onProgress(Progress("上传文件到云存储…", fraction))
        }

        // ---- 4. info + pub ----
        onProgress(Progress("写入歌曲信息（info）…", null))
        val infoResp = apiGet<JsonObject>(
            "/cloud/upload/info",
            mapOf(
                "md5" to md5,
                "songid" to songId,
                "filename" to file.nameWithoutExtension,
                "song" to file.nameWithoutExtension,
                "album" to "",
                "artist" to "",
                "bitrate" to 999000,
                "resourceId" to resourceId,
            )
        ).getOrElse { throw IllegalStateException("info 失败：${it.message}") }
        val infoCode = (infoResp["data"]?.jsonObject ?: infoResp)["code"]
            ?.jsonPrimitive?.longOrNull ?: infoResp["code"]?.jsonPrimitive?.longOrNull
        if (infoCode != null && infoCode != 200L) {
            throw IllegalStateException("info 被拒（code=$infoCode）")
        }
        onProgress(Progress("发布到云盘（pub）…", null))
        apiGet<JsonObject>("/cloud/pub", mapOf("songid" to songId))
            .getOrElse { throw IllegalStateException("pub 失败：${it.message}") }
        onProgress(Progress("上传完成", 1f))
        return "上传成功"
    }

    private fun uploadToNos(
        file: File,
        objectKey: String,
        token: String,
        ext: String,
        onProgress: (Float) -> Unit
    ) {
        val encodedKey = java.net.URLEncoder.encode(objectKey, "UTF-8")
            .replace("+", "%20")
        val url = "https://nosup-hz1.127.net/$CLOUD_BUCKET/$encodedKey" +
            "?offset=0&complete=true&version=1.0"
        val conn = NetProxy.openHttp(url)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("x-nos-token", token)
        conn.setRequestProperty(
            "Content-Type",
            when (ext) {
                "flac" -> "audio/flac"
                "m4a" -> "audio/mp4"
                "wav" -> "audio/wav"
                else -> "audio/mpeg"
            }
        )
        conn.setFixedLengthStreamingMode(file.length())
        file.inputStream().use { input ->
            conn.outputStream.use { output ->
                val buf = ByteArray(64 * 1024)
                var sent = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    sent += n
                    onProgress((sent.toFloat() / file.length()).coerceIn(0f, 1f))
                }
            }
        }
        val code = conn.responseCode
        val body = runCatching {
            (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText()?.take(300)
        }.getOrNull().orEmpty()
        if (code !in 200..299) {
            throw IllegalStateException("NOS 上传失败 HTTP $code：${body.take(120)}")
        }
    }

    private fun md5Hex(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
