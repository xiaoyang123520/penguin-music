package com.jussicodes.music.desktop.ui

import android.content.Context
import com.jussicodes.music.utils.dataStore
import com.rcmiku.ncmapi.api.account.AccountApi
import com.rcmiku.ncmapi.model.UserProfile
import com.rcmiku.ncmapi.utils.CookieProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * 桌面壳的全局 UI 状态：全屏播放页/队列面板开关、搜索焦点协作、
 * 顶栏搜索 → 搜索页的查询交接。都用 StateFlow，方便托盘/快捷键等
 * 窗口外组件一起读写。
 */
/** MV 播放请求（2.1.0）：mvId>0 时壳层弹出 MV 播放窗口。 */
data class MvPlayRequest(val mvId: Long, val title: String)

object DesktopUiState {
    val fullPlayerOpen = MutableStateFlow(false)
    val queueOpen = MutableStateFlow(false)

    /** MV 播放窗口请求（2.1.0）：非空即弹窗，关闭置空。 */
    val mvRequest = MutableStateFlow<MvPlayRequest?>(null)

    fun openMv(mvId: Long, title: String) {
        if (mvId <= 0) return
        // A3-02：打开 MV 时暂停主播放，避免音乐与 MV 双声叠播（对标
        // 网易云/QQ）。关 MV 后不自动恢复，保持暂停，由用户手动续播。
        // 2.1.3（A4-03）：声明暂停原因，引擎落盘可查。
        com.jussicodes.music.playback.PauseDiagnostics.note(
            com.jussicodes.music.playback.PauseDiagnostics.OPEN_MV
        )
        runCatching { pauseMainPlayerAction() }
        mvRequest.value = MvPlayRequest(mvId, title)
    }

    fun closeMv() {
        mvRequest.value = null
    }

    /** 一起听面板开关（2.1.0）。 */
    val listenTogetherOpen = MutableStateFlow(false)

    /** 迷你模式（2.0.5）：主窗口缩成置顶小窗播放条（壳层负责窗口尺寸/置顶）。 */
    val miniMode = MutableStateFlow(false)

    /** 任一文本输入框聚焦中：此时空格等快捷键让行给输入（打字不误触播放暂停）。 */
    val inputFocused = MutableStateFlow(false)

    /** 顶栏搜索提交后交给搜索页执行的查询（搜索页消费后清空）。 */
    val pendingSearch = MutableStateFlow<String?>(null)

    /** 自增即请求顶栏搜索框获取焦点（Ctrl+L）。 */
    val focusSearchSignal = MutableStateFlow(0)

    /** 自增即请求当前列表页定位到正在播放的歌曲行。 */
    val locateCurrentSignal = MutableStateFlow(0)

    /** 壳层注册的后退/前进动作（供鼠标侧键等窗口外事件调用）。 */
    @Volatile var backAction: () -> Unit = {}
    @Volatile var forwardAction: () -> Unit = {}

    /**
     * 壳层注册的「暂停主播放」动作（A3-02）：由 MainScreen 在组合时
     * 注册（与 backAction 同模式，MainScreen 手握 LocalPlayerState），
     * openMv 调用。未注册时为空动作，openMv 行为退化为只弹窗。
     */
    @Volatile var pauseMainPlayerAction: () -> Unit = {}
}

/**
 * 登录会话状态：顶栏头像、侧栏、设置页账号区共用。
 * profile 非空 = 已登录；expired = 本地有 cookie 但服务端不认（登录过期）。
 */
object SessionState {
    val profile = MutableStateFlow<UserProfile?>(null)
    val expired = MutableStateFlow(false)
    val checked = MutableStateFlow(false)

    suspend fun refresh(context: Context) {
        val hasCookie = CookieProvider.isLoggedIn()
        if (!hasCookie) {
            profile.value = null
            expired.value = false
            checked.value = true
            return
        }
        val accountResult = AccountApi.account()
        val p = accountResult.getOrNull()?.profile
        if (p != null && p.userId > 0) {
            profile.value = p
            expired.value = false
        } else if (accountResult.isSuccess) {
            // 请求成功但服务端查无此人：cookie 已过期/被踢，明确标出来，
            // 让 UI 给「重新登录」入口，而不是装作还登录着。
            profile.value = null
            expired.value = true
        } else {
            // #4：请求失败（断网/超时/接口异常）不等于登录过期——保持
            // 原 profile/expired 不动，等下一次 refresh 成功再判定；
            // 此前这里把失败与过期混为一谈，断网一瞬就误报「已过期」。
            System.err.println(
                "[Session] refresh failed, keep state: ${accountResult.exceptionOrNull()}"
            )
        }
        checked.value = true
    }

    fun onLoggedOut() {
        profile.value = null
        expired.value = false
    }
}

/** 读当前 DataStore 里的登录用户 id（老通道 userIdKye），无则 0。 */
suspend fun currentUserId(context: Context): Long {
    val prefs = context.dataStore.data.first()
    return prefs[com.jussicodes.music.constants.userIdKye] ?: 0L
}
