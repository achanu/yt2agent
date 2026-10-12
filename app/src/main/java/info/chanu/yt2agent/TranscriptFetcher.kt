package info.chanu.yt2agent

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** A video chapter marker. */
data class Chapter(val timeMs: Long, val title: String)

/** A SponsorBlock promotional segment inside the video. */
data class Segment(val startMs: Long, val endMs: Long, val category: String)

/** Video metadata plus the flattened transcript text. */
data class Transcript(
    val title: String,
    val description: String,
    val url: String,
    val text: String,
    val chapters: List<Chapter> = emptyList(),
    val channel: String = "",
    val durationSeconds: Long = 0,
    val uploadDate: String = "",
    val sponsorSegments: List<Segment> = emptyList(),
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
            // Transient errors (DNS, one-off 429) resolve alone; only a second
            // failure points at the binary and pays the forced update download.
            try {
                fetchTranscript(videoUrl, onTitle, onStatus)
            } catch (e2: Exception) {
                updateBinary()
                fetchTranscript(videoUrl, onTitle, onStatus)
            }
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
            // Only the tracks the selector can pick are downloaded (a multi-ASR
            // video exposes 16 "-orig" tracks — 15 MB of waste): English and locale
            // originals, then locale and English. ponytail: a video spoken in a
            // third language loses its original track and falls back to the locale.
            addOption("--sub-langs", "en.*-orig,${Locale.getDefault().language}.*-orig,${Locale.getDefault().language},en")
            // Format preference: json3 (YouTube's native), then vtt/srt for sites
            // whose extractors do not expose json3.
            addOption("--sub-format", "json3/vtt/srt")
            addOption("-o", File(outDir, "%(title)s.%(ext)s").absolutePath)
            // One video per share; a URL carrying a list= parameter fetches only
            // the video, and playlist URLs fail with an explicit error.
            addOption("--no-playlist")
            // YouTube 429s timedtext requests from impersonation-less clients;
            // few retries with a long sleep keeps the request count low.
            addOption("--ignore-errors")
            addOption("--retries", "3")
            addOption("--retry-sleep", "10")
            // The recurring ffmpeg-location and po_token warnings only add pipe noise.
            addOption("--no-warnings")
            // SponsorBlock promotional segments; only the requested categories
            // land in sponsorblock_chapters (verified: the mark list filters).
            addOption("--sponsorblock-mark", "sponsor,selfpromo,interaction")
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
        val sponsorSegments = parseSponsorSegments(info)

        // yt-dlp writes every requested language that exists; pick deterministically.
        val subFiles = outDir.listFiles()?.filter { it.extension in SUBTITLE_EXTENSIONS }.orEmpty()
        val subName = pickSubtitle(subFiles.map { it.name }, Locale.getDefault().language)
            ?: error(context.getString(R.string.no_transcript))
        Log.i(
            "yt2agent",
            "subs available: ${subFiles.map { it.name }}, picked: $subName, sponsor segments: ${sponsorSegments.size}",
        )

        val subFile = File(outDir, subName)
        val captionText = when (subFile.extension) {
            "vtt", "srt" -> parseVttOrSrt(subFile.readText())
            else -> parseJson3(subFile.readText())
        }

        return Transcript(
            title = info.optString("title"),
            description = info.optString("description"),
            url = info.optString("webpage_url").ifEmpty { videoUrl },
            channel = info.optString("channel"),
            durationSeconds = info.optLong("duration"),
            uploadDate = info.optString("upload_date"),
            text = insertChapters(captionText, chapters),
            chapters = chapters,
            sponsorSegments = sponsorSegments,
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
                title = json.optString("title"),
                description = json.optString("description"),
                url = json.optString("url"),
                text = json.optString("text"),
                chapters = readChapters(json.optJSONArray("chapters")),
                channel = json.optString("channel"),
                durationSeconds = json.optLong("duration"),
                uploadDate = json.optString("uploadDate"),
                sponsorSegments = readSegments(json.optJSONArray("sponsorSegments")),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun readChapters(array: JSONArray?): List<Chapter> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { Chapter(it.optLong("ms"), it.optString("t")) }
        }
    }

    private fun readSegments(array: JSONArray?): List<Segment> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let {
                Segment(it.optLong("s"), it.optLong("e"), it.optString("c"))
            }
        }
    }

    private fun writeCache(videoUrl: String, transcript: Transcript) {
        try {
            val dir = File(context.cacheDir, "transcripts").apply { mkdirs() }
            val chapters = JSONArray().apply {
                transcript.chapters.forEach {
                    put(JSONObject().put("ms", it.timeMs).put("t", it.title))
                }
            }
            val segments = JSONArray().apply {
                transcript.sponsorSegments.forEach {
                    put(JSONObject().put("s", it.startMs).put("e", it.endMs).put("c", it.category))
                }
            }
            JSONObject()
                .put("title", transcript.title)
                .put("description", transcript.description)
                .put("url", transcript.url)
                .put("text", transcript.text)
                .put("chapters", chapters)
                .put("channel", transcript.channel)
                .put("duration", transcript.durationSeconds)
                .put("uploadDate", transcript.uploadDate)
                .put("sponsorSegments", segments)
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
        // The v6- prefix invalidates entries written before sponsor segments
        // were cached.
        "v6-" + MessageDigest.getInstance("MD5").digest(videoUrl.toByteArray())
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
        val SUBTITLE_EXTENSIONS = setOf("json3", "vtt", "srt")
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
 * Parses the SponsorBlock promotional segments from the yt-dlp metadata.
 */
internal fun parseSponsorSegments(info: JSONObject): List<Segment> {
    val segments = info.optJSONArray("sponsorblock_chapters") ?: return emptyList()
    return (0 until segments.length()).mapNotNull { i ->
        segments.optJSONObject(i)?.let {
            Segment(
                (it.optDouble("start_time", 0.0) * 1000).toLong(),
                (it.optDouble("end_time", 0.0) * 1000).toLong(),
                it.optString("category"),
            )
        }
    }
}

/**
 * Drops the transcript lines whose timestamp falls inside a SponsorBlock segment.
 * Lines without a timestamp are kept.
 */
internal fun removeSponsorSegments(text: String, segments: List<Segment>): String =
    if (segments.isEmpty()) {
        text
    } else {
        text.lineSequence()
            .filter { line ->
                val ms = lineTimeMs(line)
                segments.none { ms >= it.startMs && ms < it.endMs }
            }
            .joinToString("\n")
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

/**
 * Extracts the timestamp in milliseconds from a `[MM:SS] ` or `[H:MM:SS] ` line
 * prefix, or [Long.MAX_VALUE] when the line has none.
 */
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
 * Picks the subtitle file by preference. `-orig` tracks are the genuine ASR
 * (translations are not): a single `-orig` track is taken as is; among several —
 * YouTube's multi-language ASR emits many, all marked "Original" — English wins
 * (the spoken language is undetectable: machine translations read like real text),
 * then the device locale. Without `-orig` tracks, prefer the locale then English.
 * ponytail: a French video whose multi-ASR set has no dominant English audio
 * still yields English; a language picker would lift this.
 * The name list is never empty when called.
 */
internal fun pickSubtitle(names: List<String>, locale: String): String? {
    if (names.isEmpty()) return null
    val origs = names.filter { it.contains("-orig", ignoreCase = true) }
    if (origs.size == 1) return origs.first()
    if (origs.isNotEmpty()) {
        return origs.firstOrNull { it.contains("en", ignoreCase = true) }
            ?: origs.firstOrNull { it.contains(locale, ignoreCase = true) }
            ?: origs.first()
    }
    for (pref in listOf(locale, "en")) {
        names.firstOrNull { it.contains(pref, ignoreCase = true) }?.let { return it }
    }
    return names.first()
}

private val cueTimeRegex = Regex("""(?:(\d{1,2}):)?(\d{2}):(\d{2})[.,]\d{1,3}""")

/**
 * Parses WebVTT or SRT captions — both share the cue-block shape: a timing line
 * followed by the caption text — into timestamped plain text. Cue tags are
 * stripped.
 * ponytail: no de-duplication of rolling auto-caption lines; YouTube always
 * serves json3 (handled by [parseJson3]) and other sites rarely roll VTT.
 */
internal fun parseVttOrSrt(text: String): String =
    text.split(Regex("""\n\s*\n"""))
        .mapNotNull { block ->
            val lines = block.lines()
            val timingIndex = lines.indexOfFirst { cueTimeRegex.containsMatchIn(it) }
            if (timingIndex < 0) return@mapNotNull null
            val match = cueTimeRegex.find(lines[timingIndex])!!
            val startMs = match.groupValues[1].ifEmpty { "0" }.toLong() * 3_600_000 +
                match.groupValues[2].toLong() * 60_000 +
                match.groupValues[3].toLong() * 1_000
            val caption = lines.drop(timingIndex + 1)
                .joinToString(" ") { it.trim() }
                .replace(Regex("""<[^>]*>"""), "")
                .trim()
            if (caption.isEmpty()) null else "[${formatTime(startMs)}] $caption"
        }
        .joinToString("\n")

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

/**
 * Formats milliseconds as `[MM:SS]`, or `[H:MM:SS]` past one hour.
 */
internal fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
