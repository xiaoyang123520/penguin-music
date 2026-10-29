package androidx.media3.common.text

class Cue(
    val text: CharSequence? = null,
)

class CueGroup(
    val cues: List<Cue> = emptyList(),
    val presentationTimeUs: Long = 0L,
) {
    companion object {
        val EMPTY: CueGroup = CueGroup()
        val EMPTY_TIME_ZERO: CueGroup = CueGroup()
    }
}
