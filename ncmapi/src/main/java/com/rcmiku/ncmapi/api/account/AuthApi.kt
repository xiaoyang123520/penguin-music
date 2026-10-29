package com.rcmiku.ncmapi.api.account

import com.rcmiku.ncmapi.api.AuthDiagnostics
import com.rcmiku.ncmapi.api.DeviceIdentity
import com.rcmiku.ncmapi.api.ensureAnonymousToken
import com.rcmiku.ncmapi.api.requestNeteaseWithCookies
import com.rcmiku.ncmapi.utils.CookieProvider
import com.rcmiku.ncmapi.utils.json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * 桌面登录接口（2.0 新增，对齐 NeteaseCloudMusicApi 的登录族）。
 *
 * 网易云的登录会话 cookie 走响应 Set-Cookie 下发，JSON 体本身至多附带一个
 * 拼接好的 cookie 字符串（803 时），所以这里统一走 [requestNeteaseWithCookies]，
 * 两边都收、合并去重。登录成功返回的 cookie map 交给调用方走既有
 * NcmCookie → DataStore → CookieProvider 的持久化通道，本类不落盘。
 */
object AuthApi {

    /** 扫码会话：key 用于轮询，content 是二维码要编码的完整登录 URL。 */
    data class QrSession(val key: String, val content: String)

    sealed interface QrCheck {
        /** 801：等待扫码。 */
        data object Waiting : QrCheck

        /** 802：已扫码，等待在手机上确认。 */
        data class Scanned(val nickname: String?, val avatarUrl: String?) : QrCheck

        /** 800：二维码已过期，需要刷新。 */
        data object Expired : QrCheck

        /** 803：登录成功，cookie 已就绪。 */
        data class Success(val cookie: Map<String, String>) : QrCheck

        /** 其他业务码（如风控 8821 等），把服务端 message 透出给 UI。 */
        data class Failed(val code: Int, val message: String) : QrCheck
    }

    /** 风控拦截异常：UI 直接展示 message（已含处理指引），不再透原始报错。 */
    class RiskControlException(message: String) : IllegalStateException(message)

    /**
     * 匿名注册预热（2.0.3）：用持久 deviceId 向 /api/register/anonimous 换
     * MUSIC_A 游客令牌存入 DeviceIdentity jar。登录请求发送前 sendWithCookies
     * 也会自动兜底调用；这里公开给启动流程与探针显式触发。
     * 返回 jar 里是否已有 MUSIC_A。
     */
    suspend fun ensureAnonymous(): Boolean {
        ensureAnonymousToken()
        return DeviceIdentity.hasMusicA()
    }

    /**
     * 风控识别：命中 -462/「环境异常」类响应时，先把脱敏后的完整响应写
     * login.log，再抛 [RiskControlException] 给 UI 明确提示。
     */
    private fun throwIfRisk(where: String, body: String, root: JsonObject) {
        val code = root["code"]?.jsonPrimitive?.intOrNull ?: 0
        val msg = root["message"]?.jsonPrimitive?.contentOrNull()
            ?: root["msg"]?.jsonPrimitive?.contentOrNull()
            ?: ""
        if (AuthDiagnostics.isRiskControl(code, msg)) {
            AuthDiagnostics.log("risk-control at $where code=$code msg=$msg body=$body")
            throw RiskControlException(
                "网易云风控拦截（环境异常）：服务器认为当前登录环境有安全风险。" +
                    "可稍后重试，或改用「Cookie」页粘贴 music.163.com 网页版 Cookie 登录。"
            )
        }
    }

    suspend fun qrKey(): Result<QrSession> = runCatching {
        val (body, _) = requestNeteaseWithCookies("/login/qr/key")
        val root = json.parseToJsonElement(body).jsonObject
        throwIfRisk("qr/key", body, root)
        // 实测（2026-10-22 探针）：unikey 端点是扁平返回 {"code":200,"unikey":"..."}，
        // 没有 data 包裹；两种形状都兼容。
        val data = root["data"]?.jsonObject
        val key = (root["unikey"] ?: data?.get("unikey"))?.jsonPrimitive?.contentOrNull()
            ?: throw IllegalStateException("二维码 key 获取失败：${root["message"]?.jsonPrimitive?.contentOrNull() ?: body.take(200)}")
        QrSession(key = key, content = "https://music.163.com/login?codekey=$key")
    }

    suspend fun qrCheck(key: String): Result<QrCheck> = runCatching {
        val (body, setCookies) = requestNeteaseWithCookies("/login/qr/check", mapOf("key" to key))
        val root = json.parseToJsonElement(body).jsonObject
        val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
        val message = root["message"]?.jsonPrimitive?.contentOrNull().orEmpty()
        if (AuthDiagnostics.isRiskControl(code, message)) {
            AuthDiagnostics.log("risk-control at qr/check code=$code msg=$message body=$body")
            return@runCatching QrCheck.Failed(
                code,
                "网易云风控拦截（环境异常）：可稍后重试，或改用「Cookie」页粘贴网页版 Cookie 登录。"
            )
        }
        when (code) {
            801 -> QrCheck.Waiting
            802 -> QrCheck.Scanned(
                nickname = root["nickname"]?.jsonPrimitive?.contentOrNull(),
                avatarUrl = root["avatarUrl"]?.jsonPrimitive?.contentOrNull()
            )
            800 -> QrCheck.Expired
            803 -> {
                val cookie = mergeCookies(root, setCookies)
                if (cookie.containsKey("MUSIC_U")) {
                    QrCheck.Success(cookie)
                } else {
                    QrCheck.Failed(code, "登录成功但未拿到会话 cookie")
                }
            }
            else -> QrCheck.Failed(code, message.ifBlank { "扫码登录失败（code=$code）" })
        }
    }

    /** 发送短信验证码。成功返回服务端 code（200）。失败抛异常，message 含服务端说明。 */
    suspend fun captchaSent(phone: String, ctcode: String = "86"): Result<Int> = runCatching {
        val (body, _) = requestNeteaseWithCookies(
            "/captcha/sent",
            mapOf("phone" to phone, "ctcode" to ctcode)
        )
        val root = json.parseToJsonElement(body).jsonObject
        throwIfRisk("captcha/sent", body, root)
        val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
        if (code != 200) {
            throw IllegalStateException(
                root["message"]?.jsonPrimitive?.contentOrNull()
                    ?: root["msg"]?.jsonPrimitive?.contentOrNull()
                    ?: "验证码发送失败（code=$code）"
            )
        }
        code
    }

    /** 手机号 + 验证码登录。成功返回 cookie map。 */
    suspend fun loginCellphoneCaptcha(
        phone: String,
        captcha: String,
        ctcode: String = "86"
    ): Result<Map<String, String>> = loginForCookies(
        "/login/cellphone",
        mapOf("phone" to phone, "captcha" to captcha, "ctcode" to ctcode)
    )

    /** 手机号 + 密码登录（密码在此 md5，与官方客户端一致）。 */
    suspend fun loginCellphonePassword(
        phone: String,
        password: String,
        ctcode: String = "86"
    ): Result<Map<String, String>> = loginForCookies(
        "/login/cellphone",
        mapOf("phone" to phone, "password" to md5(password), "ctcode" to ctcode)
    )

    /** 邮箱 + 密码登录（password 字段同样传 md5）。 */
    suspend fun loginEmail(
        email: String,
        password: String
    ): Result<Map<String, String>> = loginForCookies(
        "/login",
        mapOf("username" to email, "password" to md5(password))
    )

    /**
     * Cookie 登录兜底校验（2.0.3）：候选 Cookie 先装进 CookieProvider，
     * 调 /user/account 验真——拿到 profile 才算登录态成立；验不过恢复
     * 原 Provider 状态并失败。成功后 Provider 保持候选 Cookie（与其它
     * 登录方式的 onLoginSuccess 落盘流程衔接，调用方继续落盘即可）。
     */
    suspend fun loginByCookie(cookieMap: Map<String, String>): Result<Map<String, String>> =
        runCatching {
            require(cookieMap["MUSIC_U"]?.isNotBlank() == true) { "Cookie 中未找到 MUSIC_U" }
            val previous = CookieProvider.getCookieMap()
            CookieProvider.init(cookieMap)
            val account = AccountApi.account()
            // profile 模型有默认实例、永不为 null，未登录/假 Cookie 时 userId=0，
            // 故校验标准是 userId>0（探针实测：伪造 MUSIC_U 能骗过 profile 非空检查）。
            val uid = account.getOrNull()?.profile?.userId ?: 0L
            if (account.isFailure || uid <= 0L) {
                if (previous.isEmpty()) CookieProvider.clear() else CookieProvider.init(previous)
                AuthDiagnostics.log(
                    "cookie-login validation failed: accountOk=${account.isSuccess} uid=$uid"
                )
                throw IllegalStateException(
                    "Cookie 校验未通过：网易云不认这份登录态（多半已过期），请重新从 music.163.com 网页版复制"
                )
            }
            AuthDiagnostics.log("cookie-login validation ok uid=$uid")
            cookieMap
        }

    private suspend fun loginForCookies(
        path: String,
        params: Map<String, Any>
    ): Result<Map<String, String>> = runCatching {
        val (body, setCookies) = requestNeteaseWithCookies(path, params)
        val root = json.parseToJsonElement(body).jsonObject
        throwIfRisk(path, body, root)
        val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
        val cookie = mergeCookies(root, setCookies)
        if (code == 200 && cookie.containsKey("MUSIC_U")) {
            cookie
        } else {
            val msg = root["message"]?.jsonPrimitive?.contentOrNull()
                ?: root["msg"]?.jsonPrimitive?.contentOrNull()
                ?: "登录失败（code=$code）"
            throw IllegalStateException(msg)
        }
    }

    /** 合并 JSON 体里的 cookie 字符串（若有）与 Set-Cookie 头，后到先得按名覆盖。 */
    private fun mergeCookies(root: JsonObject, setCookies: List<String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (header in setCookies) {
            val pair = header.substringBefore(';').trim()
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val name = pair.substring(0, eq).trim()
                val value = pair.substring(eq + 1).trim()
                if (name.isNotEmpty() && value.isNotEmpty()) out[name] = value
            }
        }
        root["cookie"]?.jsonPrimitive?.contentOrNull()?.takeIf { it.isNotBlank() }?.let { str ->
            for (part in str.split(';')) {
                val pair = part.trim()
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val name = pair.substring(0, eq).trim()
                    val value = pair.substring(eq + 1).trim()
                    if (name.isNotEmpty() && value.isNotEmpty()) out[name] = value
                }
            }
        }
        return out
    }

    private fun md5(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content
}
