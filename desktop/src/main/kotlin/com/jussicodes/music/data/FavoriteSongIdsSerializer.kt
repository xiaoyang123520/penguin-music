package com.jussicodes.music.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import com.jussicodes.music.FavoriteSongIds
import java.io.File
import java.io.InputStream
import java.io.OutputStream

object FavoriteSongIdsSerializer : Serializer<FavoriteSongIds> {
    override val defaultValue: FavoriteSongIds
        get() = FavoriteSongIds.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): FavoriteSongIds {
        return try {
            FavoriteSongIds.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            defaultValue
        }
    }

    override suspend fun writeTo(t: FavoriteSongIds, output: OutputStream) = t.writeTo(output)

}

/** Desktop: real DataStore (JVM) persisted under ~/.jussichords/datastore/. */
private val favoriteSongIdsStore: DataStore<FavoriteSongIds> by lazy {
    DataStoreFactory.create(
        serializer = FavoriteSongIdsSerializer,
        produceFile = {
            File(Context.appDataRoot(), "datastore/favorite_song_ids.pb")
                .apply { parentFile?.mkdirs() }
        }
    )
}

val Context.favoriteSongIdsDatastore: DataStore<FavoriteSongIds>
    get() = favoriteSongIdsStore
