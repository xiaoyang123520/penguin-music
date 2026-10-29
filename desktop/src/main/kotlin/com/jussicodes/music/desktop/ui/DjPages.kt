package com.jussicodes.music.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.jussicodes.music.ui.navigation.RadioNav
import com.rcmiku.ncmapi.api.apiGet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** 桌面端自有路由（NavGraph 桌面替换版注册）。 */
object DesktopRoutes {
    const val RADIOS = "desktop/radios"
    const val CLOUD = "desktop/cloud"
    const val TOPLIST = "desktop/toplist"
    const val SQUARE = "desktop/square"
    const val DOWNLOADS = "desktop/downloads"
    const val LOCAL = "desktop/local"
}

@Serializable
private data class DjHotResponse(val djRadios: List<DjRadioItem> = emptyList())

@Serializable
private data class DjRadioItem(
    val id: Long = 0,
    val name: String = "",
    val picUrl: String = "",
    val programCount: Int = 0,
    val subCount: Long = 0
)

/**
 * 电台列表页：数据来自 /dj/hot。
 * 实测（2026-10-22 探针）：该接口匿名状态返回 200 但列表为空——
 * 页面如实展示空状态（多为未登录限制），登录后若接口给数据会自动渲染成网格。
 */
@Composable
fun DjRadioPage(navController: NavController) {
    var radios by remember { mutableStateOf<List<DjRadioItem>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableStateOf(0) }

    LaunchedEffect(reloadToken) {
        radios = null
        failed = false
        val result = withContext(Dispatchers.IO) {
            runCatching {
                apiGet<DjHotResponse>("/dj/hot", mapOf("limit" to 60, "offset" to 0)).getOrThrow()
            }
        }
        result.onSuccess { radios = it.djRadios }
            .onFailure { failed = true; radios = emptyList() }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp)) {
        Text("电台", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "热门电台（网易云节目电台）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        when {
            radios == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("电台加载失败，请检查网络", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { reloadToken++ }) { Text("重试") }
                }
            }
            radios!!.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂时没有拿到电台数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "热门电台接口在未登录状态下不返回列表，登录后可再来看看；私人FM 在左侧「电台」下方的漫游入口里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { reloadToken++ }) { Text("重新加载") }
                }
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(radios!!, key = { it.id }) { radio ->
                    Column(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable { if (radio.id > 0) navController.navigate(RadioNav(radio.id)) }
                            .padding(8.dp)
                    ) {
                        ArtworkImage(url = radio.picUrl, modifier = Modifier.size(140.dp), corner = 8.dp, thumbSize = 300)
                        Spacer(Modifier.height(7.dp))
                        Text(radio.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${radio.programCount} 期节目",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
