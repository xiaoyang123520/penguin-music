package androidx.media3.common

import android.net.Uri
import android.os.Bundle

/** 桌面垫片：MediaMetadata 只保留应用实际读写的字段。 */
class MediaMetadata(
    val title: CharSequence? = null,
    val artist: CharSequence? = null,
    val albumTitle: CharSequence? = null,
    val albumArtist: CharSequence? = null,
    val description: CharSequence? = null,
    val subtitle: CharSequence? = null,
    val artworkUri: Uri? = null,
    val mediaType: Int = MEDIA_TYPE_MUSIC,
    val isPlayable: Boolean? = null,
    val extras: Bundle? = null,
) {
    class Builder {
        private var title: CharSequence? = null
        private var artist: CharSequence? = null
        private var albumTitle: CharSequence? = null
        private var albumArtist: CharSequence? = null
        private var description: CharSequence? = null
        private var subtitle: CharSequence? = null
        private var artworkUri: Uri? = null
        private var mediaType: Int = MEDIA_TYPE_MUSIC
        private var isPlayable: Boolean? = null
        private var extras: Bundle? = null

        fun setTitle(title: CharSequence?): Builder = apply { this.title = title }
        fun setArtist(artist: CharSequence?): Builder = apply { this.artist = artist }
        fun setAlbumTitle(albumTitle: CharSequence?): Builder = apply { this.albumTitle = albumTitle }
        fun setAlbumArtist(albumArtist: CharSequence?): Builder = apply { this.albumArtist = albumArtist }
        fun setDescription(description: CharSequence?): Builder = apply { this.description = description }
        fun setSubtitle(subtitle: CharSequence?): Builder = apply { this.subtitle = subtitle }
        fun setArtworkUri(artworkUri: Uri?): Builder = apply { this.artworkUri = artworkUri }
        fun setMediaType(mediaType: Int): Builder = apply { this.mediaType = mediaType }
        fun setIsPlayable(isPlayable: Boolean?): Builder = apply { this.isPlayable = isPlayable }
        fun setExtras(extras: Bundle?): Builder = apply { this.extras = extras }
        fun setReleaseYear(year: Int): Builder = this
        fun setRecordingYear(year: Int): Builder = this
        fun setTrackNumber(trackNumber: Int): Builder = this
        fun setTotalTrackCount(totalTrackCount: Int): Builder = this
        fun setWriter(writer: CharSequence?): Builder = this
        fun setComposer(composer: CharSequence?): Builder = this
        fun setGenre(genre: CharSequence?): Builder = this
        fun setArtworkData(data: ByteArray?, pictureType: Int): Builder = this
        fun setStation(station: CharSequence?): Builder = this
        fun setIsBrowsable(isBrowsable: Boolean?): Builder = this
        fun setFolderType(folderType: Int): Builder = this
        fun setUserRating(rating: Any?): Builder = this
        fun setOverallRating(rating: Any?): Builder = this
        fun setDisplayTitle(displayTitle: CharSequence?): Builder = this
        fun setDiscNumber(discNumber: Int): Builder = this
        fun setTotalDiscCount(totalDiscCount: Int): Builder = this
        fun setRecordingMonth(month: Int): Builder = this
        fun setRecordingDay(day: Int): Builder = this
        fun setReleaseMonth(month: Int): Builder = this
        fun setReleaseDay(day: Int): Builder = this
        fun setCompilation(compilation: CharSequence?): Builder = this
        fun setConductor(conductor: CharSequence?): Builder = this
        fun setDurationMs(durationMs: Long): Builder = this

        fun build(): MediaMetadata = MediaMetadata(
            title = title,
            artist = artist,
            albumTitle = albumTitle,
            albumArtist = albumArtist,
            description = description,
            subtitle = subtitle,
            artworkUri = artworkUri,
            mediaType = mediaType,
            isPlayable = isPlayable,
            extras = extras,
        )
    }

    fun buildUpon(): Builder = Builder().also { b ->
        b.setTitle(title)
        b.setArtist(artist)
        b.setAlbumTitle(albumTitle)
        b.setAlbumArtist(albumArtist)
        b.setDescription(description)
        b.setSubtitle(subtitle)
        b.setArtworkUri(artworkUri)
        b.setMediaType(mediaType)
        b.setIsPlayable(isPlayable)
        b.setExtras(extras)
    }

    companion object {
        const val MEDIA_TYPE_MIXED: Int = 0
        const val MEDIA_TYPE_MUSIC: Int = 1
        const val MEDIA_TYPE_AUDIO_BOOK_CHAPTER: Int = 2
        const val MEDIA_TYPE_PLAYLIST: Int = 4
        val EMPTY: MediaMetadata = MediaMetadata()
    }
}
