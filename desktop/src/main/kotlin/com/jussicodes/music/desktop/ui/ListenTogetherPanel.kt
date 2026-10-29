package com.jussicodes.music.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jussicodes.music.playback.ListenTogetherSession
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * 一起听面板（2.1.0）：桌面端接安卓同一套 [ListenTogetherSession]
 * 状态机与协议（建房/邀请链接加入/状态同步全在会话层）。未进房：
 * 创建房间 + 粘贴邀请链接加入；房内：房号/成员数/邀请链接复制/
 * 离开。错误与提示直接展示会话层的 error/notice，不吞不假成功。
 */
@Composable
fun ListenTogetherPanel(onDismiss: () -> Unit) {
    val state by ListenTogetherSession.state.collectAsState()
    var inviteInput by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("一起听") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                val room = state.room
                if (room == null) {
                    Text(
                        "和朋友一起同步听歌：创建房间把邀请链接发给对方，" +
                            "或粘贴对方的邀请链接加入。播放/暂停/切歌/进度会自动同步。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { ListenTogetherSession.create() },
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (state.isLoading) "处理中…" else "创建房间") }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = inviteInput,
                        onValueChange = { inviteInput = it },
                        label = { Text("邀请链接或房间号") },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { ListenTogetherSession.join(inviteInput.trim()) },
                        enabled = inviteInput.isNotBlank() && !state.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("加入房间") }
                } else {
                    Text(
                        if (state.isHost) "你是房主" else "已加入房间",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("房间号：${room.id}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "成员：${room.members.size} 人",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (state.reconnecting) {
                        Text(
                            "连接中断，正在重连…",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    val invite = state.inviteUrl
                    if (!invite.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            invite,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = {
                            runCatching {
                                Toolkit.getDefaultToolkit().systemClipboard
                                    .setContents(StringSelection(invite), null)
                                copied = true
                            }
                        }) { Text(if (copied) "已复制" else "复制邀请链接") }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row {
                        OutlinedButton(onClick = { ListenTogetherSession.refresh() }) {
                            Text("刷新状态")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { ListenTogetherSession.end() }) {
                            Text(if (state.isHost) "结束房间" else "离开房间")
                        }
                    }
                }
                state.error?.takeIf { it.isNotBlank() }?.let { err ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                state.notice?.takeIf { it.isNotBlank() }?.let { notice ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        notice,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
