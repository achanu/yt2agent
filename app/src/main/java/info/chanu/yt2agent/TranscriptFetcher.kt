package info.chanu.yt2agent

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Video metadata plus the flattened transcript text. */
data class Transcript(val title: String, val description: String, val url: String, val text: String)

/**
 * Downloads a video transcript with yt-dlp and flattens it to plain text.
 */
class TranscriptFetcher(private val context: Context) {

    private val prefs = context.getSharedPreferences("yt2agent", Context.MODE_PRIVATE)

    /**
     * Fetches the video metadata and transcript. Blocks; call from a background
     * thread. [onTitle] is invoked on the calling thread as soon as the title is
     * known, before the longer transcript download starts.
     *
     * The yt-dlp binary is checked for updates once a day; a failed fetch forces a
     * redownload and one retry (a stale binary is the usual cause of YouTube 429s).
     *
     * @throws IllegalStateException when the video has no transcript
     * @throws YoutubeDLException when yt-dlp fails even after the retry
     */
    fun fetch(videoUrl: String, onTitle: (String) -> Unit = {}): Transcript {
        // Blocking on first call only: unpacks the bundled yt-dlp + python.
        YoutubeDL.getInstance().init(context)

        if (lastUpdateAgoMillis() > DAY_MILLIS) updateBinary()

        return try {
            fetchTranscript(videoUrl, onTitle)
        } catch (e: IllegalStateException) {
            // No transcript on this video: a fresh binary would not change that.
            throw e
        } catch (e: Exception) {
            updateBinary()
            fetchTranscript(videoUrl, onTitle)
        }
    }

    private fun fetchTranscript(videoUrl: String, onTitle: (String) -> Unit): Transcript {
        // Metadata and subtitles run concurrently so the title can be shown during
        // the fetch without extending total wall time.
        // ponytail: parallel yt-dlp processes; serialize if the library ever chokes on it.
        var info = JSONObject()
        val infoThread = Thread {
            try {
                info = JSONObject(infoJson(videoUrl))
            } catch (e: Exception) {
                // Metadata is optional; the transcript is the payload.
            }
            onTitle(info.optString("title"))
        }
        infoThread.start()

        val text = downloadTranscript(videoUrl)
        infoThread.join()

        return Transcript(
            title = info.optString("title"),
            description = info.optString("description"),
            url = info.optString("webpage_url").ifEmpty { videoUrl },
            text = text,
        )
    }

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
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
        const val KEY_LAST_UPDATE = "yt_dlp_last_update"
    }

    private fun infoJson(videoUrl: String): String {
        val request = YoutubeDLRequest(videoUrl).apply {
            addOption("--skip-download")
            addOption("--dump-single-json")
            addOption("--extractor-args", "youtube:player_client=android_vr")
        }
        return YoutubeDL.getInstance().execute(request).out.trim()
    }

    private fun downloadTranscript(videoUrl: String): String {
        val outDir = File(context.cacheDir, "subs").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }

        val request = YoutubeDLRequest(videoUrl).apply {
            addOption("--skip-download")
            addOption("--write-subs")
            addOption("--write-auto-subs")
            // ponytail: device locale + English only; add a language picker if other languages matter
            addOption("--sub-langs", "${Locale.getDefault().language},en")
            addOption("--sub-format", "json3")
            addOption("-o", File(outDir, "sub").absolutePath)
            // YouTube 429s timedtext requests from impersonation-less clients.
            addOption("--ignore-errors")
            addOption("--retries", "5")
            addOption("--retry-sleep", "5")
            addOption("--extractor-args", "youtube:player_client=android_vr")
        }
        YoutubeDL.getInstance().execute(request)

        val subFile = outDir.listFiles()?.firstOrNull { it.extension == "json3" }
            ?: error(context.getString(R.string.no_transcript))
        return parseJson3(subFile.readText())
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
