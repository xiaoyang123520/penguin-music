package com.jussicodes.music.utils

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import java.io.File
import kotlin.properties.ReadOnlyProperty

/**
 * Desktop version: identical API to the Android DataStoreUtils, but the
 * preferences DataStore is the real multiplatform DataStore (JVM) persisted
 * under ~/.jussichords/datastore/settings.preferences_pb.
 */
private val settingsDataStoreInstance: DataStore<Preferences> by lazy {
    PreferenceDataStoreFactory.createWithPath(
        // 2.2.0（A5-09）：损坏自愈——settings.preferences_pb 一旦损坏
        // （断电写一半/磁盘错误），无 handler 时首次读取即抛
        // CorruptionException，dataStore.data.first() 的全部调用方
        // （启动读 EQ/音质/登录态……）连锁失败、应用基本不可用。
        // 改为损坏即以空 Preferences 重建（设置回默认，登录态 cookie
        // 在同文件里也会丢——但那是文件已物理损坏、不可恢复时的
        // 唯一活路，强于整应用起不来）。
        corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
            println("[DataStore] settings.preferences_pb 损坏，已重置为空设置自愈")
            emptyPreferences()
        },
        produceFile = {
            File(Context.appDataRoot(), "datastore/settings.preferences_pb")
                .apply { parentFile?.mkdirs() }
                .absolutePath.toPath()
        }
    )
}

val Context.dataStore: DataStore<Preferences>
    get() = settingsDataStoreInstance

operator fun <T> DataStore<Preferences>.get(key: Preferences.Key<T>): T? =
    runBlocking(Dispatchers.IO) {
        data.first()[key]
    }

fun <T> DataStore<Preferences>.get(key: Preferences.Key<T>, defaultValue: T): T =
    runBlocking(Dispatchers.IO) {
        data.first()[key] ?: defaultValue
    }

fun <T> preference(
    context: Context,
    key: Preferences.Key<T>,
    defaultValue: T,
) = ReadOnlyProperty<Any?, T> { _, _ -> context.dataStore[key] ?: defaultValue }

inline fun <reified T : Enum<T>> enumPreference(
    context: Context,
    key: Preferences.Key<String>,
    defaultValue: T,
) = ReadOnlyProperty<Any?, T> { _, _ -> context.dataStore[key].toEnum(defaultValue) }

@Composable
fun <T> rememberPreference(
    key: Preferences.Key<T>,
    defaultValue: T,
): MutableState<T> {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val state = remember {
        context.dataStore.data
            .map { it[key] ?: defaultValue }
            .distinctUntilChanged()
    }.collectAsState(defaultValue)

    return remember {
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(value) {
                    coroutineScope.launch {
                        context.dataStore.edit {
                            it[key] = value
                        }
                    }
                }

            override fun component1() = value
            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}

@Composable
fun <T> rememberNullablePreference(
    key: Preferences.Key<T>,
): State<T?> {
    val context = LocalContext.current

    val flow = remember {
        context.dataStore.data
            .map { it[key] }
            .distinctUntilChanged()
    }
    return flow.collectAsState(initial = null)
}

@Composable
inline fun <reified T : Enum<T>> rememberEnumPreference(
    key: Preferences.Key<String>,
    defaultValue: T,
): MutableState<T> {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val state = remember {
        context.dataStore.data
            .map { it[key].toEnum(defaultValue = defaultValue) }
            .distinctUntilChanged()
    }.collectAsState(defaultValue)

    return remember {
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(value) {
                    coroutineScope.launch {
                        context.dataStore.edit {
                            it[key] = value.name
                        }
                    }
                }

            override fun component1() = value
            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}


inline fun <reified T : Enum<T>> String?.toEnum(defaultValue: T): T =
    if (this == null) defaultValue
    else try {
        enumValueOf(this)
    } catch (e: IllegalArgumentException) {
        // 2.0.4 桌面修正：桌面设置页 2.0–2.0.3 存的是 SongLevel.value
        // （小写，如 "exhigh"/"lossless"），安卓与本函数按枚举名解析，
        // 存量值永远落回默认（播放器音质实际恒为 STANDARD，闸门/无损
        // 播放全错位）。加大小写回退，名/值两种存量都能读。
        try {
            enumValueOf(this.uppercase())
        } catch (e2: IllegalArgumentException) {
            defaultValue
        }
    }
