package com.jussicodes.music.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import com.jussicodes.music.SearchHistory
import java.io.File
import java.io.InputStream
import java.io.OutputStream

object SearchHistorySerializer : Serializer<SearchHistory> {

    override val defaultValue: SearchHistory = SearchHistory.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): SearchHistory {
        return try {
            SearchHistory.parseFrom(input)
        } catch (e: InvalidProtocolBufferException) {
            defaultValue
        }
    }

    override suspend fun writeTo(t: SearchHistory, output: OutputStream) = t.writeTo(output)
}

/** Desktop: real DataStore (JVM) persisted under ~/.jussichords/datastore/. */
private val searchHistoryStore: DataStore<SearchHistory> by lazy {
    DataStoreFactory.create(
        serializer = SearchHistorySerializer,
        produceFile = {
            File(Context.appDataRoot(), "datastore/search_history.pb")
                .apply { parentFile?.mkdirs() }
        }
    )
}

val Context.searchHistoryDataStore: DataStore<SearchHistory>
    get() = searchHistoryStore
