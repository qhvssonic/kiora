package dev.kiora.speech

/** A revision replaces the entire text of one segment; partial text is never an append-only delta.
 * Segment IDs start at zero for each session. A final revision seals that segment, including silence.
 * These IDs are not timestamps: not every recognition model supplies word timing.
 */
data class SpeechRecognitionUpdate(
    val segmentId: Long,
    val revision: Long,
    val text: String,
    val isFinal: Boolean,
)

/** Host supplies microphone audio and owns permissions, buffering, UI and the session lifetime. */
fun interface StreamingSpeechRecognitionPort {
    suspend fun openSession(): StreamingSpeechSession
}

/** Feed mono PCM in order. Do not call accept/finish concurrently. Always close, even on cancellation.
 * finish drains the last audio and seals the last segment; close cancels without producing text.
 * Implementations may perform blocking native cleanup in close; call it off the UI thread.
 */
interface StreamingSpeechSession : AutoCloseable {
    suspend fun accept(audio: Pcm16Audio): List<SpeechRecognitionUpdate>
    suspend fun finish(): List<SpeechRecognitionUpdate>
    override fun close()
}

/** Reduces ordered session updates for a text composer; duplicates and stale revisions are ignored. */
class StreamingTranscript {
    private val committed = StringBuilder()
    private var segment = 0L
    private var revision = -1L
    private var partial = ""
    val text: String get() = join(committed.toString(), partial)

    fun apply(update: SpeechRecognitionUpdate): String {
        require(update.segmentId <= segment) { "Missing final update for segment $segment" }
        if (update.segmentId < segment || update.revision <= revision) return text
        revision = update.revision
        partial = update.text.trim()
        if (update.isFinal) {
            val full = text
            committed.clear().append(full)
            partial = ""
            segment++
            revision = -1
        }
        return text
    }

    private fun join(left: String, right: String): String = when {
        left.isEmpty() -> right
        right.isEmpty() -> left
        else -> "$left $right"
    }
}
