package com.rcmiku.ncmapi.api

/**
 * 登录诊断日志钩子（2.0.3）：风控类响应（-462 /「环境异常」等）需要留痕到
 * ~/.jussichords/logs/login.log 供真机排查，但 ncmapi 模块不依赖桌面壳的
 * DesktopLog，故用可注入的 sink：桌面壳在 initProcess 里把 sink 指向
 * DesktopLog.login；未注入时退化为 stdout 打印（探针环境）。
 * 写入前必须先经 [sanitize] 脱敏——绝不把 Cookie 值写进日志。
 */
object AuthDiagnostics {

    @Volatile
    var sink: ((String) -> Unit)? = null

    fun log(message: String) {
        val line = sanitize(message)
        val s = sink
        if (s != null) {
            runCatching { s(line) }
        } else {
            println("[LOGIN] $line")
        }
    }

    /**
     * 敏感键集合（2.1.2 #16 扩充）：大小写不敏感。长键在前，保证
     * `access_token` / `csrf_token` / `session_id` / `set-cookie` 这类复合键
     * 整体命中、而不是只抹掉后半截。
     */
    private val SENSITIVE_KEYS: List<String> = listOf(
        "MUSIC_U", "MUSIC_A", "__csrf", "NMTID",
        "authorization", "access_token", "refresh_token", "id_token",
        "csrf_token", "client_secret", "session_id", "sessionid",
        "set-cookie", "api_key", "apikey", "passwd", "password",
        "cookie", "secret", "session", "token", "csrf", "pwd",
    )

    private val KEY_ALT: String =
        SENSITIVE_KEYS.joinToString("|") { Regex.escape(it) }

    /**
     * 脱敏：抹掉敏感键值（MUSIC_U / cookie 串 / token / password / authorization
     * 等，见 [SENSITIVE_KEYS]），只留结构信息与业务码。
     *
     * 输出格式约定与旧版一致（既有调用方依赖）：键名原样保留、值替换为
     * `<redacted>`、整体截断 1200 字符。覆盖四种形态：
     * ① JSON 引号键+引号值 `"cookie":"…"`；② JSON 引号键+裸值 `"token":123`；
     * ③ 裸键值 `MUSIC_U=…` / `password: …`（含 URL query 与 Cookie 串里的
     * 逐键形态，值在 `; & , \s " } ]` 处截断）；④ `Bearer/Basic <凭证>`。
     */
    fun sanitize(text: String): String {
        var out = text
        // ④ 授权头凭证（Authorization 的值常是 "Bearer xxx"，裸键值规则只会
        // 抹掉 Bearer 一词、漏掉真凭证，必须先处理）。
        out = out.replace(Regex("(?i)\\b(Bearer|Basic)\\s+[^\\s;,\\\"]+")) {
            "${it.groupValues[1]} <redacted>"
        }
        // ① JSON 引号键 + 引号值。
        out = out.replace(Regex("(?i)\"($KEY_ALT)\"\\s*:\\s*\"[^\"]*\"")) {
            "\"${it.groupValues[1]}\":\"<redacted>\""
        }
        // ② JSON 引号键 + 裸值。
        out = out.replace(Regex("(?i)\"($KEY_ALT)\"\\s*:\\s*[^,\\s}\\]\"]+")) {
            "\"${it.groupValues[1]}\":<redacted>"
        }
        // ③ 裸键值（= 或 : 分隔；含 Cookie 串内逐键、URL query 参数）。
        out = out.replace(Regex("(?i)\\b($KEY_ALT)\\s*[=:]\\s*[^;\\s,\"}\\]&]+")) {
            "${it.groupValues[1]}=<redacted>"
        }
        return out.take(1200)
    }

    /** 是否风控类响应：-462 验证拦截，或文案含环境异常/安全风险/完成验证。 */
    fun isRiskControl(code: Int, message: String): Boolean =
        code == -462 ||
            message.contains("环境异常") ||
            message.contains("安全风险") ||
            message.contains("请完成验证") ||
            message.contains("完成验证后")
}

/**
 * 数据面风控异常（2.2.0，A5-12）：apiGet/apiPost 解码前拦截业务码 -462 时抛出，
 * message 为数据面口径（区别于 AuthApi 内部的登录口径同名嵌套异常，两者是
 * 不同的类、互不影响）。数据面调用方（搜索、歌单、播放解析等）可据此把
 * 「风控」与「网络异常」分开呈现，而不是把风控体误解码成空数据。
 */
class RiskControlException(message: String) : IllegalStateException(message)
