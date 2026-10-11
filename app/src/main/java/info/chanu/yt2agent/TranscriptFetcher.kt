package info.chanu.yt2agent

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Video metadata plus the flattened transcript text. */
data class Transcript(val title: String, val description: String, val url: String, val text: String)

/**
 * Downloads a video transcript with yt-dlp and flattens it to plain text.
 */
class TranscriptFetcher(private val context: Context) {

    private val prefs = context.getSharedPreferences("yt2agent", Context.MODE_PRIVATE)

    /**
     * Returns the cached transcript for [videoUrl] when fresh, or fetches it. Blocks;
     * call from a background thread. [onTitle] is invoked on the calling thread as
     * soon as the title is known.
     *
     * The yt-dlp binary is checked for updates every 72 hours; a failed fetch forces a
     * redownload and one retry (a stale binary is the usual cause of YouTube 429s).
     *
     * @throws IllegalStateException when the video has no transcript
     * @throws YoutubeDLException when yt-dlp fails even after the retry
     */
    fun fetch(videoUrl: String, onTitle: (String) -> Unit = {}): Transcript {
        readCache(videoUrl)?.let { cached ->
            onTitle(cached.title)
            return cached
        }

        // Blocking on first call only: unpacks the bundled yt-dlp + python.
        YoutubeDL.getInstance().init(context)

        if (lastUpdateAgoMillis() > UPDATE_TTL_MILLIS) updateBinary()

        val transcript = try {
            fetchTranscript(videoUrl, onTitle)
        } catch (e: IllegalStateException) {
            // No transcript on this video: a fresh binary would not change that.
            throw e
        } catch (e: Exception) {
            updateBinary()
            fetchTranscript(videoUrl, onTitle)
        }
        writeCache(videoUrl, transcript)
        return transcript
    }

    /**
     * One yt-dlp pass writes both the metadata (`--write-info-json`) and the json3
     * captions. yt-dlp writes the info file right after extraction, before the
     * subtitles, so a poller surfaces the title while the download continues.
     */
    private fun fetchTranscript(videoUrl: String, onTitle: (String) -> Unit): Transcript {
        val outDir = File(context.cacheDir, "subs").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }

        val request = YoutubeDLRequest(videoUrl).apply {
            addOption("--skip-download")
            addOption("--write-subs")
            addOption("--write-auto-subs")
            addOption("--write-info-json")
            // ponytail: device locale + English only; add a language picker if other languages matter
            addOption("--sub-langs", "${Locale.getDefault().language},en")
            // ponytail: json3 only; sites without json3 subtitles report "no transcript"
            addOption("--sub-format", "json3")
            addOption("-o", File(outDir, "sub").absolutePath)
            // YouTube 429s timedtext requests from impersonation-less clients.
            addOption("--ignore-errors")
            addOption("--retries", "5")
            addOption("--retry-sleep", "5")
            addOption("--extractor-args", "youtube:player_client=android_vr")
        }

        val infoFile = File(outDir, "sub.info.json")
        val titleThread = Thread {
            for (i in 0 until 300) {
                if (infoFile.exists()) {
                    try {
                        onTitle(JSONObject(infoFile.readText()).optString("title"))
                        return@Thread
                    } catch (e: Exception) {
                        // File still being written; retry on the next tick.
                    }
                }
                Thread.sleep(200)
            }
        }
        titleThread.start()

        YoutubeDL.getInstance().execute(request)
        titleThread.join()

        val info = if (infoFile.exists()) JSONObject(infoFile.readText()) else JSONObject()
        val subFile = outDir.listFiles()?.firstOrNull { it.extension == "json3" }
            ?: error(context.getString(R.string.no_transcript))

        return Transcript(
            title = info.optString("title"),
            description = info.optString("description"),
            url = info.optString("webpage_url").ifEmpty { videoUrl },
            text = insertChapters(parseJson3(subFile.readText()), info),
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

    private fun cacheKey(videoUrl: String): String =
        MessageDigest.getInstance("MD5").digest(videoUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun lastUpdateAgoMillis(): Long =
        System.currentTimeMillis() - prefs.getLong(KEY_LAST_UPDATE, 0L)

    private fun updateBinary() {
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
        const val KEY_LAST_UPDATE = "yt_dlp_last_update"
    }
}

/**
 * Inserts chapter markers from the yt-dlp metadata as `[MM:SS] [Chapitre] <title>`
 * lines at their position in the timestamped transcript.
 */
internal fun insertChapters(transcript: String, info: JSONObject): String {
    val chapters = info.optJSONArray("chapters") ?: return transcript
    if (chapters.length() == 0) return transcript

    val lines = transcript.split('\n').toMutableList()
    for (i in 0 until chapters.length()) {
        val chapter = chapters.optJSONObject(i) ?: continue
        val ms = (chapter.optDouble("start_time", 0.0) * 1000).toLong()
        val line = "[${formatTime(ms)}] [Chapitre] ${chapter.optString("title")}"
        val index = lines.indexOfFirst { lineTimeMs(it) >= ms }
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
