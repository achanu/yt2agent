package info.chanu.yt2agent

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** A video chapter marker. */
data class Chapter(val timeMs: Long, val title: String)

/** Video metadata plus the flattened transcript text. */
data class Transcript(
    val title: String,
    val description: String,
    val url: String,
    val text: String,
    val chapters: List<Chapter> = emptyList(),
)

/**
 * Downloads a video transcript with yt-dlp and flattens it to plain text.
 */
class TranscriptFetcher(private val context: Context) {

    private val prefs = context.getSharedPreferences("yt2agent", Context.MODE_PRIVATE)

    /**
     * Returns the cached transcript for [videoUrl] when fresh, or fetches it. Blocks;
     * call from a background thread. [onTitle] is invoked on the calling thread as
     * soon as the title is known; [onStatus] receives the current yt-dlp output
     * line (no percentage exists: the slow part, extraction, has none).
     *
     * The yt-dlp binary is checked for updates every 72 hours; a failed fetch forces a
     * redownload and one retry (a stale binary is the usual cause of YouTube 429s).
     *
     * @throws IllegalStateException when the video has no transcript
     * @throws YoutubeDLException when yt-dlp fails even after the retry
     */
    fun fetch(
        videoUrl: String,
        onTitle: (String) -> Unit = {},
        onStatus: (String) -> Unit = {},
    ): Transcript {
        readCache(videoUrl)?.let { cached ->
            onTitle(cached.title)
            return cached
        }
        // Recently failed because the video has no transcript; do not refetch for a day.
        if (readMissing(videoUrl)) error(context.getString(R.string.no_transcript))

        // Blocking on first call only: unpacks the bundled yt-dlp + python.
        YoutubeDL.getInstance().init(context)

        // The periodic check never blocks the fetch; a concurrent fetch may still run
        // on the old binary, and the error path (forced update + retry) recovers that.
        if (lastUpdateAgoMillis() > UPDATE_TTL_MILLIS) Thread { updateBinary() }.start()

        val transcript = try {
            fetchTranscript(videoUrl, onTitle, onStatus)
        } catch (e: IllegalStateException) {
            writeMissing(videoUrl)
            throw e
        } catch (e: Exception) {
            updateBinary()
            fetchTranscript(videoUrl, onTitle, onStatus)
        }
        writeCache(videoUrl, transcript)
        return transcript
    }

    /**
     * One yt-dlp pass writes both the metadata (`--write-info-json`) and the json3
     * captions. The output template embeds the title, so the first
     * `[download] Destination:` line surfaces the title before the subtitles are
     * downloaded; yt-dlp only writes the info file at the very end of the run.
     */
    private fun fetchTranscript(
        videoUrl: String,
        onTitle: (String) -> Unit,
        onStatus: (String) -> Unit,
    ): Transcript {
        val outDir = File(context.cacheDir, "subs").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }

        val request = YoutubeDLRequest(videoUrl).apply {
            addOption("--skip-download")
            addOption("--write-subs")
            addOption("--write-auto-subs")
            addOption("--write-info-json")
            // Original language first: YouTube's auto-translations degrade the text
            // and agents read any language; device locale and English follow.
            addOption("--sub-langs", ".*-orig,${Locale.getDefault().language},en")
            // ponytail: json3 only; sites without json3 subtitles report "no transcript"
            addOption("--sub-format", "json3")
            addOption("-o", File(outDir, "%(title)s.%(ext)s").absolutePath)
            // YouTube 429s timedtext requests from impersonation-less clients.
            addOption("--ignore-errors")
            addOption("--retries", "5")
            addOption("--retry-sleep", "5")
            addOption("--extractor-args", "youtube:player_client=android_vr")
        }

        var titleSurfaced = false
        YoutubeDL.getInstance().execute(request, null) { _, _, line ->
            val text = line?.trim().orEmpty()
            if (text.isNotEmpty() && !text.startsWith("WARNING")) {
                if (!titleSurfaced && text.startsWith(DESTINATION_PREFIX)) {
                    titleSurfaced = true
                    onTitle(destinationTitle(text))
                }
                onStatus(text)
            }
        }

        val infoFile = outDir.listFiles()?.firstOrNull { it.name.endsWith(".info.json") }
        val info = if (infoFile != null) JSONObject(infoFile.readText()) else JSONObject()
        val chapters = parseChapters(info)

        // yt-dlp writes every requested language that exists; pick deterministically.
        val subFiles = outDir.listFiles()?.filter { it.extension == "json3" }.orEmpty()
        val subName = pickSubtitle(subFiles.map { it.name }, Locale.getDefault().language)
            ?: error(context.getString(R.string.no_transcript))
        Log.i("yt2agent", "subs available: ${subFiles.map { it.name }}, picked: $subName")

        return Transcript(
            title = info.optString("title"),
            description = info.optString("description"),
            url = info.optString("webpage_url").ifEmpty { videoUrl },
            text = insertChapters(parseJson3(File(outDir, subName).readText()), chapters),
            chapters = chapters,
        )
    }

    // ponytail: 7-day TTL; captions never change, this only bounds cache growth.
    private fun readCache(videoUrl: String): Transcript? {
        val file = File(File(context.cacheDir, "transcripts"), "${cacheKey(videoUrl)}.json")
        if (!file.exists() ||
            System.currentTimeMillis() - file.lastModified() > TRANSCRIPT_TTL_MILLIS
        ) {
            return null
        }
        return try {
            val json = JSONObject(file.readText())
            Transcript(
                json.optString("title"),
                json.optString("description"),
                json.optString("url"),
                json.optString("text"),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun writeCache(videoUrl: String, transcript: Transcript) {
        try {
            val dir = File(context.cacheDir, "transcripts").apply { mkdirs() }
            JSONObject()
                .put("title", transcript.title)
                .put("description", transcript.description)
                .put("url", transcript.url)
                .put("text", transcript.text)
                .toString()
                .let { File(dir, "${cacheKey(videoUrl)}.json").writeText(it) }
        } catch (e: Exception) {
            // A failed cache write is not fatal.
        }
    }

    // ponytail: 24 h TTL; the video will not grow captions by retrying sooner.
    private fun readMissing(videoUrl: String): Boolean {
        val file = missingFile(videoUrl)
        return file.exists() &&
            System.currentTimeMillis() - file.lastModified() < MISSING_TTL_MILLIS
    }

    private fun writeMissing(videoUrl: String) {
        try {
            missingFile(videoUrl).apply { parentFile?.mkdirs() }.writeText("no transcript")
        } catch (e: Exception) {
            // A failed negative-cache write is not fatal.
        }
    }

    private fun missingFile(videoUrl: String): File =
        File(File(context.cacheDir, "transcripts"), "missing-${cacheKey(videoUrl)}.json")

    private fun cacheKey(videoUrl: String): String =
        // The v3- prefix invalidates entries written with a different subtitle
        // preference order (they hold the wrong language).
        "v3-" + MessageDigest.getInstance("MD5").digest(videoUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun lastUpdateAgoMillis(): Long =
        System.currentTimeMillis() - prefs.getLong(KEY_LAST_UPDATE, 0L)

    // Serialized: the periodic background check and the error path can overlap.
    private fun updateBinary() = synchronized(this) {
        try {
            YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
            prefs.edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
        } catch (e: Exception) {
            // Best effort: fetch with the binary we have when the update server is unreachable.
        }
    }

    private companion object {
        const val UPDATE_TTL_MILLIS = 72L * 60 * 60 * 1000
        const val TRANSCRIPT_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val MISSING_TTL_MILLIS = 24L * 60 * 60 * 1000
        const val KEY_LAST_UPDATE = "yt_dlp_last_update"
    }
}

/**
 * Parses the chapter markers from the yt-dlp metadata.
 */
internal fun parseChapters(info: JSONObject): List<Chapter> {
    val chapters = info.optJSONArray("chapters") ?: return emptyList()
    return (0 until chapters.length()).mapNotNull { i ->
        chapters.optJSONObject(i)?.let {
            Chapter((it.optDouble("start_time", 0.0) * 1000).toLong(), it.optString("title"))
        }
    }
}

/**
 * Inserts chapter markers as `[MM:SS] [Chapitre] <title>` lines at their position in
 * the timestamped transcript.
 */
internal fun insertChapters(transcript: String, chapters: List<Chapter>): String {
    if (chapters.isEmpty()) return transcript

    val lines = transcript.split('\n').toMutableList()
    for (chapter in chapters) {
        val line = "[${formatTime(chapter.timeMs)}] [Chapitre] ${chapter.title}"
        val index = lines.indexOfFirst { lineTimeMs(it) >= chapter.timeMs }
        if (index < 0) lines.add(line) else lines.add(index, line)
    }
    return lines.joinToString("\n")
}

private val lineTimeRegex = Regex("""^\[(\d+):(\d{2})(?::(\d{2}))?]""")

internal fun lineTimeMs(line: String): Long {
    val match = lineTimeRegex.find(line) ?: return Long.MAX_VALUE
    return if (match.groupValues[3].isEmpty()) {
        (match.groupValues[1].toLong() * 60 + match.groupValues[2].toLong()) * 1000
    } else {
        (match.groupValues[1].toLong() * 3600 +
            match.groupValues[2].toLong() * 60 +
            match.groupValues[3].toLong()) * 1000
    }
}

private const val DESTINATION_PREFIX = "[download] Destination: "

/**
 * Extracts a display title from a `[download] Destination: <file>` yt-dlp line.
 * ponytail: a title containing dots is cut at the second-to-last dot; the exact
 * title still reaches the shared text via the info file.
 */
internal fun destinationTitle(line: String): String =
    line.removePrefix(DESTINATION_PREFIX)
        .substringAfterLast('/')
        .substringBeforeLast(".")
        .substringBeforeLast(".")

/**
 * Picks the subtitle file by preference: the `-orig` track of the device locale, any
 * other `-orig` track (the real ASR, whatever the language — YouTube's translations
 * degrade it), then the locale, then English. `-orig` tracks are genuine ASR even
 * when creators mislabel the video language; translated tracks are not.
 * The name list is never empty when called.
 */
internal fun pickSubtitle(names: List<String>, locale: String): String? {
    if (names.isEmpty()) return null
    for (pref in listOf("$locale-orig", "-orig", locale, "en")) {
        names.firstOrNull { it.contains(pref, ignoreCase = true) }?.let { return it }
    }
    return names.first()
}

/**
 * Flattens yt-dlp json3 captions to timestamped plain text, one `[MM:SS]` line per
 * caption, joining `aAppend` continuation events (auto-captions) into their line.
 */
internal fun parseJson3(json: String): String {
    val events = JSONObject(json).getJSONArray("events")
    val lines = StringBuilder()
    var current = StringBuilder()
    var startMs = 0L

    for (i in 0 until events.length()) {
        val event = events.optJSONObject(i) ?: continue
        val segs = event.optJSONArray("segs") ?: continue

        val text = buildString {
            for (j in 0 until segs.length()) {
                append(segs.optJSONObject(j)?.optString("utf8") ?: "")
            }
        }.replace('\n', ' ')

        if (event.optInt("aAppend", 0) == 1) {
            current.append(text)
        } else {
            if (current.isNotBlank()) lines.appendLine("[${formatTime(startMs)}] ${current.trim()}")
            current = StringBuilder(text)
            startMs = event.optLong("tStartMs", 0L)
        }
    }
    if (current.isNotBlank()) lines.appendLine("[${formatTime(startMs)}] ${current.trim()}")

    return lines.toString().trimEnd('\n')
}

internal fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
