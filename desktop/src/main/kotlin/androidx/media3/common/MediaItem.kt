package androidx.media3.common

import android.net.Uri

/** 桌面垫片：MediaItem。uri 是应用的伪地址（songId?fee=&pl=），由桌面引擎解析成直链。 */
class MediaItem(
    val mediaId: String = DEFAULT_MEDIA_ID,
    val mediaMetadata: MediaMetadata = MediaMetadata.EMPTY,
    val localConfiguration: LocalConfiguration? = null,
    val requestMetadata: RequestMetadata = RequestMetadata.EMPTY,
    val mimeType: String? = null,
) {
    val uri: Uri?
        get() = localConfiguration?.uri

    class LocalConfiguration(
        val uri: Uri?,
        val mimeType: String? = null,
    )

    class RequestMetadata(
        val mediaUri: Uri? = null,
        val searchQuery: String? = null,
        val extras: android.os.Bundle? = null,
    ) {
        companion object {
            val EMPTY: RequestMetadata = RequestMetadata()
        }
    }

    class Builder {
        private var mediaId: String = DEFAULT_MEDIA_ID
        private var uri: Uri? = null
        private var mimeType: String? = null
        private var mediaMetadata: MediaMetadata = MediaMetadata.EMPTY
        private var requestMetadata: RequestMetadata = RequestMetadata.EMPTY

        fun setMediaId(mediaId: String): Builder = apply { this.mediaId = mediaId }
        fun setUri(uri: String?): Builder = apply { this.uri = uri?.let { Uri.parse(it) } }
        fun setUri(uri: Uri?): Builder = apply { this.uri = uri }
        fun setMimeType(mimeType: String?): Builder = apply { this.mimeType = mimeType }
        fun setMediaMetadata(mediaMetadata: MediaMetadata): Builder =
            apply { this.mediaMetadata = mediaMetadata }
        fun setRequestMetadata(requestMetadata: RequestMetadata): Builder =
            apply { this.requestMetadata = requestMetadata }
        fun setTag(tag: Any?): Builder = this
        fun setDrmConfiguration(config: Any?): Builder = this
        fun setStreamKeys(streamKeys: List<*>): Builder = this
        fun setCustomCacheKey(customCacheKey: String?): Builder = this
        fun setSubtitleConfigurations(configs: List<*>): Builder = this
        fun setClippingConfiguration(config: Any?): Builder = this
        fun setAdsConfiguration(config: Any?): Builder = this
        fun setLiveConfiguration(config: Any?): Builder = this
        fun setImageDurationMs(durationMs: Long): Builder = this

        fun build(): MediaItem = MediaItem(
            mediaId = mediaId,
            mediaMetadata = mediaMetadata,
            localConfiguration = if (uri != null || mimeType != null) {
                LocalConfiguration(uri, mimeType)
            } else {
                null
            },
            requestMetadata = requestMetadata,
            mimeType = mimeType,
        )
    }

    fun buildUpon(): Builder = Builder().also { b ->
        b.setMediaId(mediaId)
        b.setMediaMetadata(mediaMetadata)
        b.setRequestMetadata(requestMetadata)
        localConfiguration?.let { b.setUri(it.uri); b.setMimeType(it.mimeType) }
    }

    override fun equals(other: Any?): Boolean =
        other is MediaItem && other.mediaId == mediaId &&
            other.localConfiguration?.uri == localConfiguration?.uri

    override fun hashCode(): Int = mediaId.hashCode()

    companion object {
        const val DEFAULT_MEDIA_ID: String = ""
    }
}
