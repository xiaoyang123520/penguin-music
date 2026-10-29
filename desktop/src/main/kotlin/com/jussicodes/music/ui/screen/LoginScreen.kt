package com.jussicodes.music.ui.screen

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.navigation.NavController
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.ui.awt.SwingPanel
import com.jussicodes.music.constants.ncmCookieKey
import com.jussicodes.music.desktop.ui.ArtworkImage
import com.jussicodes.music.desktop.weblogin.WebLoginAutoRetry
import com.jussicodes.music.desktop.weblogin.WebLoginBrowser
import com.jussicodes.music.desktop.ui.DesktopUiState
import com.jussicodes.music.desktop.ui.SessionState
import com.jussicodes.music.ui.navigation.Screen
import com.jussicodes.music.utils.NcmCookieNormalizer
import com.jussicodes.music.utils.dataStore
import com.rcmiku.ncmapi.api.account.AccountApi
import com.rcmiku.ncmapi.api.account.AuthApi
import com.rcmiku.ncmapi.utils.CookieProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 桌面登录页（2.0 起，对标网易云 Windows 版登录窗）：
 * 扫码登录（真二维码 + 状态轮询：等待/已扫待确认/过期刷新/成功）、
 * 手机号+验证码（60 秒重发倒计时）、手机号/邮箱+密码、Cookie 粘贴兜底。
 * 登录成功统一落盘 ncmCookie 并初始化 CookieProvider（与 1.x 格式一致）。
 *
 * 2.0.4：新增「网页登录」为默认方式——JCEF 内嵌官方 m/login 页、登录后
 * 抓两域 Cookie，与手机端（WebView 同页抓 Cookie）完全同路线；此前三版
 * 用 API 模拟登录在真机一直被网易云风控拦（「环境异常有安全风险」）。
 * API 三种登录（扫码/验证码/密码）降为「其他方式」次级入口，Cookie 页保留。
 */
@Composable
fun LoginScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    val expired by SessionState.expired.collectAsState()

    // 2.0.1：打开登录页先预热种 Cookie（桌面 UA GET 官网，与登录请求同指纹；
    // 每进程实际只跑一次，登录接口内部也会兜底触发）
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { com.rcmiku.ncmapi.api.DeviceIdentity.warmUpBlocking() }
        }
    }

    fun onLoginSuccess(cookieMap: Map<String, String>) {
        scope.launch {
            val json = Json.encodeToString(cookieMap)
            context.dataStore.edit { it[ncmCookieKey] = json }
            withContext(Dispatchers.IO) { CookieProvider.init(cookieMap) }
            AccountApi.invalidateUserPlaylistCache()
            SessionState.refresh(context)
            Toast.makeText(context, "登录成功", Toast.LENGTH_SHORT).show()
            navController.navigate(Screen.Library.route) {
                popUpTo(Screen.Library.route) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("登录网易云账号", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (expired) "登录已过期，请重新登录" else "登录后可同步歌单、喜欢与播放记录",
            style = MaterialTheme.typography.bodyMedium,
            color = if (expired) Color(0xFFE83E52) else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(18.dp))
        Box(Modifier.width(460.dp)) {
            TabRow(selectedTabIndex = tab) {
                listOf("网页登录", "其他方式", "Cookie").forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        Box(Modifier.width(460.dp)) {
            when (tab) {
                0 -> WebLoginTab(onSuccess = ::onLoginSuccess)
                1 -> OtherLoginTabs(onSuccess = ::onLoginSuccess)
                else -> CookieLoginTab(onSuccess = ::onLoginSuccess)
            }
        }
    }
}

/** API 登录三件（扫码/验证码/密码）的次级页签组（2.0.4 起降为「其他方式」）。 */
@Composable
private fun OtherLoginTabs(onSuccess: (Map<String, String>) -> Unit) {
    var sub by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth()) {
        TabRow(selectedTabIndex = sub) {
            listOf("扫码登录", "验证码", "密码").forEachIndexed { i, label ->
                Tab(selected = sub == i, onClick = { sub = i }, text = { Text(label) })
            }
        }
        Spacer(Modifier.height(18.dp))
        when (sub) {
            0 -> QrLoginTab(active = true, onSuccess = onSuccess)
            1 -> CaptchaLoginTab(onSuccess = onSuccess)
            else -> PasswordLoginTab(onSuccess = onSuccess)
        }
    }
}

/**
 * 网页登录（2.0.4 默认方式，手机同款）：JCEF 内嵌官方 m/login 页，
 * WebLoginBrowser 轮询两域 Cookie，MUSIC_U 出现后走归一化（须含
 * MUSIC_U）+ AuthApi.loginByCookie 的 /user/account 真校验（uid>0），
 * 通过才算登录成功——与 Cookie 页同一把尺子，不再「有字段就算登录」。
 */
@Composable
private fun WebLoginTab(onSuccess: (Map<String, String>) -> Unit) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("在下方官方页面里完成登录，成功后会自动回到曲库") }
    // 2.2.0（A5-11）：留住最近一次归一化 cookie——校验失败（断网等
    // 瞬时原因）时可直接重试，不逼用户在页面里重新登录一遍。
    var lastNormalized by remember { mutableStateOf<Map<String, String>?>(null) }
    // 2.3.0（A6-01）：自动重报上限门控——自动失败累计 3 次后停，
    // 只剩手动「重新校验」；成功/换 cookie 时清零。
    val autoRetry = remember { WebLoginAutoRetry() }

    fun validate(normalized: Map<String, String>, manual: Boolean = false) {
        if (checking) return
        // 自动重报已达上限：不再发校验请求，也不再复位捕获去重——
        // 轮询不会重报同一 cookie，自动重试即停（手动按钮仍可走）。
        if (!manual && autoRetry.exhausted) return
        if (manual) autoRetry.onManualRetry()
        checking = true
        error = null
        status = "已捕获登录态，正在校验…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { AuthApi.loginByCookie(normalized) }
            checking = false
            r.onSuccess {
                autoRetry.onSuccess()
                onSuccess(normalized)
            }.onFailure {
                status = "在下方官方页面里完成登录，成功后会自动回到曲库"
                error = it.message ?: "登录校验失败，请重试或改用 Cookie 登录"
                // 自动重报：未达上限才复位浏览器侧捕获去重，让下一轮
                // 2.5s 轮询把页面里的同一 cookie 再报一轮（A5-11 的
                // 瞬时失败自愈）；达上限则不再复位，自动重试到此为止，
                // 用户仍可用下方「重新校验」手动重跑（A6-01）。
                // 手动失败也计一次：手动点击已清零计数，复位后轮询
                // 最多再给 3 次自动机会，之后仍停（避免手动后又无限）。
                val stopped = autoRetry.onAutoFailure()
                if (stopped) {
                    error = "登录校验失败，自动重试已达上限（3 次），" +
                        "请点「重新校验」手动重试或改用 Cookie 登录"
                } else {
                    WebLoginBrowser.resetCaptureDedup()
                }
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        SwingPanel(
            factory = {
                WebLoginBrowser.createComponent { cookieMap ->
                    val normalized = NcmCookieNormalizer.normalizeForStorage(cookieMap)
                    if (normalized == null) {
                        status = "登录态还没有生效，请完成登录后稍等"
                        return@createComponent
                    }
                    // 2.3.0（A6-01）：换 cookie（新登录态）时自动计数清零。
                    if (normalized != lastNormalized) autoRetry.onNewCookie()
                    lastNormalized = normalized
                    validate(normalized)
                }
            },
            modifier = Modifier.fillMaxWidth().height(540.dp)
                .clip(RoundedCornerShape(12.dp))
        )
        Spacer(Modifier.height(10.dp))
        if (checking) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(status, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        error?.let {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    it,
                    color = Color(0xFFE83E52),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                // 2.2.0（A5-11）：显式重试入口——用留存的 cookie 立即
                // 重跑校验（自动轮询重报之外多一条手动路径）。
                OutlinedButton(
                    onClick = { lastNormalized?.let { m -> validate(m, manual = true) } },
                    enabled = !checking && lastNormalized != null
                ) { Text("重新校验") }
            }
        }
    }
}

/** 扫码状态轮询连续失败上限（#8）：超过即停轮询报错误态，不再无限空转。 */
private const val QR_CHECK_MAX_CONSECUTIVE_FAILURES = 5

private sealed interface QrUi {
    data object Loading : QrUi
    data class Waiting(val matrix: BitMatrix) : QrUi
    data class Scanned(val matrix: BitMatrix, val nickname: String, val avatarUrl: String) : QrUi
    data class Expired(val matrix: BitMatrix) : QrUi
    data class Failed(val message: String) : QrUi
}

@Composable
private fun QrLoginTab(active: Boolean, onSuccess: (Map<String, String>) -> Unit) {
    var ui by remember { mutableStateOf<QrUi>(QrUi.Loading) }
    var generation by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    DisposableEffect(active, generation) {
        if (!active) return@DisposableEffect onDispose { }
        var job: Job? = null
        job = scope.launch {
            ui = QrUi.Loading
            val keyResult = withContext(Dispatchers.IO) { AuthApi.qrKey() }
            val session = keyResult.getOrNull()
            if (session == null) {
                ui = QrUi.Failed(keyResult.exceptionOrNull()?.message ?: "二维码获取失败")
                return@launch
            }
            val matrix = runCatching {
                QRCodeWriter().encode(
                    session.content, BarcodeFormat.QR_CODE, 480, 480,
                    mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8")
                )
            }.getOrNull()
            if (matrix == null) {
                ui = QrUi.Failed("二维码生成失败")
                return@launch
            }
            ui = QrUi.Waiting(matrix)
            // 轮询登录状态（800 过期 / 801 等待 / 802 已扫 / 803 成功）
            // #8：连续失败（断网等）达到阈值后停轮询并落到 Failed 错误态
            // （带既有的「重试」入口，generation++ 重新拉码）；此前失败
            // 直接 continue 无限空转，用户看不到任何提示。
            var consecutiveFailures = 0
            while (isActive) {
                delay(1500)
                val check = withContext(Dispatchers.IO) { AuthApi.qrCheck(session.key) }.getOrNull()
                if (check == null) {
                    consecutiveFailures++
                    if (consecutiveFailures >= QR_CHECK_MAX_CONSECUTIVE_FAILURES) {
                        ui = QrUi.Failed("网络异常，扫码状态查询连续失败，请检查网络后重试")
                        break
                    }
                    continue
                }
                consecutiveFailures = 0
                when (check) {
                    is AuthApi.QrCheck.Waiting -> Unit
                    is AuthApi.QrCheck.Scanned -> ui = QrUi.Scanned(
                        matrix, check.nickname.orEmpty(), check.avatarUrl.orEmpty()
                    )
                    is AuthApi.QrCheck.Expired -> {
                        ui = QrUi.Expired(matrix)
                        break
                    }
                    is AuthApi.QrCheck.Success -> {
                        onSuccess(check.cookie)
                        break
                    }
                    is AuthApi.QrCheck.Failed -> {
                        ui = QrUi.Failed(check.message)
                        break
                    }
                }
            }
        }
        onDispose { job?.cancel() }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier.size(216.dp).clip(RoundedCornerShape(12.dp)).background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            when (val state = ui) {
                is QrUi.Loading -> CircularProgressIndicator(color = Color.DarkGray)
                is QrUi.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.message, color = Color.DarkGray, textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
                    OutlinedButton(onClick = { generation++ }) {
                        Icon(Icons.Filled.Refresh, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("重试")
                    }
                }
                is QrUi.Waiting -> QrCanvas(state.matrix)
                is QrUi.Scanned -> {
                    QrCanvas(state.matrix)
                    Box(Modifier.matchParentSize().background(Color.White.copy(alpha = 0.88f)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (state.avatarUrl.isNotBlank()) {
                                ArtworkImage(url = state.avatarUrl, modifier = Modifier.size(56.dp), corner = 28.dp, thumbSize = 120)
                                Spacer(Modifier.height(8.dp))
                            }
                            Text(state.nickname.ifBlank { "已扫码" }, color = Color.Black, fontWeight = FontWeight.Bold)
                            Text("已扫码，请在手机上确认登录", color = Color.DarkGray)
                        }
                    }
                }
                is QrUi.Expired -> {
                    QrCanvas(state.matrix)
                    Box(
                        Modifier.matchParentSize().background(Color.White.copy(alpha = 0.88f))
                            .clickable { generation++ },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.Refresh, null, tint = Color.DarkGray, modifier = Modifier.size(26.dp))
                            Spacer(Modifier.height(6.dp))
                            Text("二维码已过期", color = Color.Black, fontWeight = FontWeight.Bold)
                            Text("点击刷新", color = Color.DarkGray)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "打开网易云音乐 App，扫一扫登录",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** zxing BitMatrix → Compose Canvas 绘制（避开 AWT 图像转换）。 */
@Composable
private fun QrCanvas(matrix: BitMatrix) {
    Canvas(Modifier.size(196.dp)) {
        val modules = matrix.width
        val cell = size.width / modules
        for (x in 0 until modules) {
            for (y in 0 until matrix.height) {
                if (matrix[x, y]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(x * cell, y * cell),
                        size = Size(cell + 0.5f, cell + 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptchaLoginTab(onSuccess: (Map<String, String>) -> Unit) {
    var phone by remember { mutableStateOf("") }
    var captcha by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var logging by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown -= 1
        }
    }

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it.filter { c -> c.isDigit() }.take(11) },
            label = { Text("手机号") },
            placeholder = { Text("输入 11 位手机号") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().focusWatch()
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = captcha,
                onValueChange = { captcha = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("短信验证码") },
                singleLine = true,
                modifier = Modifier.weight(1f).focusWatch()
            )
            Spacer(Modifier.width(10.dp))
            Button(
                enabled = phone.length == 11 && countdown == 0 && !sending,
                onClick = {
                    sending = true
                    error = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { AuthApi.captchaSent(phone) }
                        sending = false
                        r.onSuccess {
                            countdown = 60
                            Toast.makeText(context, "验证码已发送", Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            error = it.message ?: "验证码发送失败"
                        }
                    }
                }
            ) {
                Text(
                    when {
                        sending -> "发送中…"
                        countdown > 0 -> "${countdown}s 后重发"
                        else -> "获取验证码"
                    }
                )
            }
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Color(0xFFE83E52), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(18.dp))
        Button(
            enabled = phone.length == 11 && captcha.length in 4..6 && !logging,
            onClick = {
                logging = true
                error = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { AuthApi.loginCellphoneCaptcha(phone, captcha) }
                    logging = false
                    r.onSuccess { onSuccess(it) }
                        .onFailure { error = it.message ?: "登录失败" }
                }
            },
            modifier = Modifier.fillMaxWidth().height(46.dp)
        ) {
            Text(if (logging) "登录中…" else "登录")
        }
    }
}

@Composable
private fun PasswordLoginTab(onSuccess: (Map<String, String>) -> Unit) {
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var logging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = account,
            onValueChange = { account = it },
            label = { Text("手机号或邮箱") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().focusWatch()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().focusWatch()
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Color(0xFFE83E52), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(18.dp))
        Button(
            enabled = account.isNotBlank() && password.isNotEmpty() && !logging,
            onClick = {
                logging = true
                error = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        if (account.contains("@")) AuthApi.loginEmail(account.trim(), password)
                        else AuthApi.loginCellphonePassword(account.trim(), password)
                    }
                    logging = false
                    r.onSuccess { onSuccess(it) }
                        .onFailure { error = it.message ?: "登录失败，请检查账号密码" }
                }
            },
            modifier = Modifier.fillMaxWidth().height(46.dp)
        ) {
            Text(if (logging) "登录中…" else "登录")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "部分账号开启二次验证时密码登录可能失败，可改用扫码或验证码登录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CookieLoginTab(onSuccess: (Map<String, String>) -> Unit) {
    var raw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(Modifier.fillMaxWidth()) {
        Text(
            "备用方式：扫码/验证码被风控拦截时用这个。从 music.163.com 网页版" +
                "复制 Cookie（需包含 MUSIC_U）粘贴到这里，会先向网易云校验有效才登录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = raw,
            onValueChange = { raw = it },
            label = { Text("Cookie") },
            modifier = Modifier.fillMaxWidth().height(120.dp).focusWatch()
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Color(0xFFE83E52), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(14.dp))
        Button(
            enabled = raw.isNotBlank() && !checking,
            onClick = {
                val map = NcmCookieNormalizer.normalizeForStorage(raw)
                if (map == null || !map.containsKey("MUSIC_U")) {
                    error = "Cookie 中未找到 MUSIC_U，请确认复制完整"
                    return@Button
                }
                // 2.0.3：真校验（/user/account 拿到账号才算数）——2.0.2 时只看
                // 有没有 MUSIC_U 字段就直接进主界面，过期 Cookie 也能「登录」，
                // 进去后所有接口无账号、表现为各种没反应。
                checking = true
                error = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { AuthApi.loginByCookie(map) }
                    checking = false
                    r.onSuccess { onSuccess(map) }
                        .onFailure { error = it.message ?: "Cookie 校验失败" }
                }
            },
            modifier = Modifier.fillMaxWidth().height(46.dp)
        ) { Text(if (checking) "校验中…" else "用 Cookie 登录") }
    }
}

/** 输入框聚焦时通知壳层：全局空格键不要误触播放/暂停。 */
private fun Modifier.focusWatch(): Modifier = this.onFocusChanged {
    DesktopUiState.inputFocused.value = it.isFocused
}
