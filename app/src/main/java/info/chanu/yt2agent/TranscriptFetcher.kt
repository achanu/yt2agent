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
    val playlistIndex: Int = 0,
)

/** A multi-video fetch: the playlist title, its video count, and the videos that had captions. */
data class PlaylistTranscript(
    val title: String,
    val total: Int,
    val entries: List<Transcript>,
    /** False when some entries failed to fetch (network), so the result is not cached. */
    val complete: Boolean = true,
)

/**
 * Downloads a video transcript with yt-dlp and flattens it to plain text.
 */
class TranscriptFetcher(private val context: Context) {

    private val prefs = context.getSharedPreferences("yt2agent", Context.MODE_PRIVATE)

    /** Serializes yt-dlp executions and binary updates: they share one work dir. */
    private val ytLock = Any()

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

        val transcript = try {
            fetchWithRetries { fetchTranscript(videoUrl, onTitle, onStatus) }
        } catch (e: IllegalStateException) {
            writeMissing(videoUrl)
            throw e
        }
        writeCache(videoUrl, transcript)

        // The periodic check runs AFTER the fetch chain, in this same thread: it
        // never races an execution, the chooser is already up, and the next share
        // has a head start before the (rare) update download.
        if (lastUpdateAgoMillis() > UPDATE_TTL_MILLIS) updateBinary()
        return transcript
    }

    /**
     * Fetches up to [PLAYLIST_MAX] videos of a playlist or channel URL as one
     * document source: every video with captions becomes an entry, videos without
     * captions are skipped. Same caching and binary-update policy as [fetch].
     *
     * @throws IllegalStateException when no entry has captions
     * @throws YoutubeDLException when yt-dlp fails even after the retry
     */
    fun fetchPlaylist(
        playlistUrl: String,
        onTitle: (String) -> Unit = {},
        onStatus: (String) -> Unit = {},
    ): PlaylistTranscript {
        readPlaylistCache(playlistUrl)?.let { cached ->
            onTitle(cached.title)
            return cached
        }
        if (readMissing(playlistUrl)) error(context.getString(R.string.no_transcript))

        YoutubeDL.getInstance().init(context)

        val playlist = try {
            fetchWithRetries { fetchPlaylistTranscript(playlistUrl, onTitle, onStatus) }
        } catch (e: IllegalStateException) {
            writeMissing(playlistUrl)
            throw e
        }
        // A partial playlist is not cached: a re-share resumes from the archive
        // and refetches the entries the network dropped.
        if (playlist.complete) writePlaylistCache(playlistUrl, playlist)

        if (lastUpdateAgoMillis() > UPDATE_TTL_MILLIS) updateBinary()
        return playlist
    }

    /**
     * The shared error policy: "no transcript" is permanent and propagates
     * untouched; other failures get a plain retry (transient DNS/429 errors resolve
     * alone), then a forced binary update and one last retry — the update download
     * is paid only when the binary is the plausible suspect.
     */
    private fun <T> fetchWithRetries(block: () -> T): T = try {
        block()
    } catch (e: IllegalStateException) {
        throw e
    } catch (e: Exception) {
        try {
            block()
        } catch (e2: Exception) {
            // A recently updated binary is not the suspect; the failure is
            // network-ish and re-downloading would only add delay.
            if (lastUpdateAgoMillis() > RECENT_UPDATE_MILLIS) updateBinary()
            block()
        }
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

        val request = baseRequest(videoUrl).apply {
            addOption("-o", File(outDir, "%(title)s.%(ext)s").absolutePath)
            // One video per share; a URL carrying a list= parameter fetches only
            // the video, and playlist URLs fail with an explicit error.
            addOption("--no-playlist")
            // Channel and playlist URLs enumerate every video otherwise; cap any
            // multi-video extraction to its first entry so the fetch stays bounded.
            addOption("--playlist-items", "1")
        }

        var titleSurfaced = false
        synchronized(ytLock) {
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
        }

        val infoFile = outDir.listFiles()?.firstOrNull { it.name.endsWith(".info.json") }
        val info = if (infoFile != null && infoFile.length() <= MAX_PARSE_BYTES) {
            JSONObject(infoFile.readText())
        } else {
            JSONObject()
        }
        val chapters = parseChapters(info)
        val sponsorSegments = parseSponsorSegments(info)

        // yt-dlp writes every requested language that exists; pick deterministically.
        val subFiles = outDir.listFiles()
            ?.filter { it.extension in SUBTITLE_EXTENSIONS && it.length() <= MAX_PARSE_BYTES }
            .orEmpty()
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

    /**
     * One yt-dlp pass over up to [PLAYLIST_MAX] entries. The output template prefixes
     * every file with its zero-padded playlist index, so a video's info json and caption
     * files share that prefix; entries without captions are skipped, not fatal.
     */
    private fun fetchPlaylistTranscript(
        playlistUrl: String,
        onTitle: (String) -> Unit,
        onStatus: (String) -> Unit,
    ): PlaylistTranscript {
        val outDir = File(context.cacheDir, "subs").apply { mkdirs() }
        // A retry (from the fetch chain or the Retry button) resumes after the
        // failed entry instead of restarting: the download archive lets yt-dlp
        // skip the entries it already wrote, so the dir is kept while it holds
        // a recent attempt on the same playlist.
        val session = File(outDir, SESSION_FILE)
        val resumable = session.exists() &&
            runCatching { session.readText() }.getOrNull() == playlistUrl &&
            System.currentTimeMillis() - session.lastModified() < TRANSCRIPT_TTL_MILLIS
        if (!resumable) {
            outDir.listFiles()?.forEach { it.delete() }
            session.writeText(playlistUrl)
        }

        val request = baseRequest(playlistUrl).apply {
            addOption("-o", File(outDir, "%(playlist_index)03d - %(title)s.%(ext)s").absolutePath)
            addOption("--playlist-items", "1-$PLAYLIST_MAX")
            addOption("--download-archive", File(outDir, "archive.txt").absolutePath)
            // --skip-download is a simulation option: without this the archive
            // would never be written.
            addOption("--force-download-archive")
        }

        runPlaylistPass(request, onTitle, onStatus)
        var playlist = parsePlaylistFiles(outDir, playlistUrl)
        // --ignore-errors swallows entry failures: a congested network returns
        // the playlist with holes. One more pass — the download archive makes it
        // cheap — refetches only the dropped entries.
        if (!playlist.complete) {
            runPlaylistPass(request, onTitle, onStatus)
            playlist = parsePlaylistFiles(outDir, playlistUrl)
        }
        if (playlist.entries.isEmpty()) error(context.getString(R.string.no_transcript))
        return playlist
    }

    private fun runPlaylistPass(
        request: YoutubeDLRequest,
        onTitle: (String) -> Unit,
        onStatus: (String) -> Unit,
    ) = synchronized(ytLock) {
        YoutubeDL.getInstance().execute(request, null) { _, _, line ->
            val text = line?.trim().orEmpty()
            if (text.isNotEmpty() && !text.startsWith("WARNING")) {
                // The displayed title follows the run: the last entry written wins.
                if (text.startsWith(DESTINATION_PREFIX)) onTitle(destinationTitle(text))
                onStatus(text)
            }
        }
    }

    /**
     * Parses the files yt-dlp left in [outDir] into a playlist, one info json at a
     * time. [PlaylistTranscript.complete] compares the info json count with the
     * entry count yt-dlp reported: entries missing files are fetch failures.
     */
    private fun parsePlaylistFiles(outDir: File, playlistUrl: String): PlaylistTranscript {
        val files = outDir.listFiles().orEmpty()
        val groups = files.map { it.name }.groupBy { it.substringBefore(" - ") }
        val infoCount = files.count { it.name.endsWith(".info.json") }
        val firstInfoFile = files.firstOrNull {
            it.name.endsWith(".info.json") && it.length() <= MAX_PARSE_BYTES
        }
        val firstInfo = if (firstInfoFile != null) JSONObject(firstInfoFile.readText()) else JSONObject()
        val infoTotal = firstInfo.optInt("playlist_count", firstInfo.optInt("n_entries", 0))
        val playlistTitle = firstInfo.optString("playlist_title")
            .ifEmpty { firstInfo.optString("playlist") }
            .ifEmpty { firstInfo.optString("channel") }

        val entries = groups.mapNotNull { (prefix, names) ->
            // Oversized files (a multi-hour entry's word-level auto-captions reach
            // tens of MB) would OOM the heap: skip the entry, like a caption-less one.
            val infoName = names.firstOrNull { it.endsWith(".info.json") } ?: return@mapNotNull null
            if (File(outDir, infoName).length() > MAX_PARSE_BYTES) return@mapNotNull null
            val info = JSONObject(File(outDir, infoName).readText())
            val subNames = names
                .filter { SUBTITLE_EXTENSIONS.contains(it.substringAfterLast('.')) }
                .filter { File(outDir, it).length() <= MAX_PARSE_BYTES }
            val subName = pickSubtitle(subNames, Locale.getDefault().language)
                ?: return@mapNotNull null
            Log.i("yt2agent", "playlist entry $prefix subs: $subNames, picked: $subName")
            val subFile = File(outDir, subName)
            val captionText = when (subFile.extension) {
                "vtt", "srt" -> parseVttOrSrt(subFile.readText())
                else -> parseJson3(subFile.readText())
            }
            val chapters = parseChapters(info)
            Transcript(
                title = info.optString("title"),
                description = info.optString("description"),
                url = info.optString("webpage_url").ifEmpty { playlistUrl },
                text = insertChapters(captionText, chapters),
                chapters = chapters,
                channel = info.optString("channel"),
                durationSeconds = info.optLong("duration"),
                uploadDate = info.optString("upload_date"),
                sponsorSegments = parseSponsorSegments(info),
                playlistIndex = info.optInt("playlist_index", prefix.toIntOrNull() ?: 0),
            )
        }.sortedBy { it.playlistIndex }
        return PlaylistTranscript(
            title = playlistTitle,
            total = if (infoTotal > 0) infoTotal else entries.size,
            entries = entries,
            complete = infoTotal <= 0 || infoCount >= minOf(infoTotal, PLAYLIST_MAX),
        )
    }

    /** The options shared by the single-video and playlist fetch paths. */
    private fun baseRequest(url: String): YoutubeDLRequest = YoutubeDLRequest(url).apply {
        addOption("--skip-download")
        addOption("--write-subs")
        addOption("--write-auto-subs")
        addOption("--write-info-json")
        // Only the tracks the selector can pick are downloaded (a multi-ASR
        // video exposes 16 "-orig" tracks — 15 MB of waste): English and locale
        // originals, then locale and English. ponytail: a video spoken in a
        // third language loses its original track and falls back to the locale.
        addOption(
            "--sub-langs",
            "en.*-orig,${Locale.getDefault().language}.*-orig,${Locale.getDefault().language},en",
        )
        // Format preference: json3 (YouTube's native), then vtt/srt for sites
        // whose extractors do not expose json3.
        addOption("--sub-format", "json3/vtt/srt")
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
        // No player_client override: android_vr was killed by YouTube on 2026-08-17
        // (yt-dlp dropped it from defaults in 2026.08.19). Without a JS runtime the
        // current defaults extract through visionos; a fresh install on a stale
        // bundled binary self-heals via the forced update in the retry chain.
    }

    // ponytail: 1-hour TTL; the cache is a short-term de-dup, not a store.
    private fun readCache(videoUrl: String): Transcript? =
        readTranscriptFile("${cacheKey(videoUrl)}.json")?.let { jsonToTranscript(it) }

    private fun readTranscriptFile(name: String): JSONObject? {
        val file = File(File(context.cacheDir, "transcripts"), name)
        if (!file.exists() ||
            System.currentTimeMillis() - file.lastModified() > TRANSCRIPT_TTL_MILLIS
        ) {
            return null
        }
        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    // Same TTL and shape as the single-video cache, with the playlist wrapper.
    private fun readPlaylistCache(playlistUrl: String): PlaylistTranscript? {
        val json = readTranscriptFile("${cacheKey(playlistUrl)}.json") ?: return null
        return try {
            val entries = json.optJSONArray("entries")
            PlaylistTranscript(
                title = json.optString("title"),
                total = json.optInt("total"),
                entries = (0 until (entries?.length() ?: 0)).mapNotNull {
                    entries?.optJSONObject(it)?.let(::jsonToTranscript)
                },
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun jsonToTranscript(json: JSONObject): Transcript = Transcript(
        title = json.optString("title"),
        description = json.optString("description"),
        url = json.optString("url"),
        text = json.optString("text"),
        chapters = readChapters(json.optJSONArray("chapters")),
        channel = json.optString("channel"),
        durationSeconds = json.optLong("duration"),
        uploadDate = json.optString("uploadDate"),
        sponsorSegments = readSegments(json.optJSONArray("sponsorSegments")),
        playlistIndex = json.optInt("playlistIndex", 0),
    )

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
        writeTranscriptFile("${cacheKey(videoUrl)}.json", transcriptToJson(transcript))
    }

    private fun writePlaylistCache(playlistUrl: String, playlist: PlaylistTranscript) {
        val entries = JSONArray().apply { playlist.entries.forEach { put(transcriptToJson(it)) } }
        writeTranscriptFile(
            "${cacheKey(playlistUrl)}.json",
            JSONObject()
                .put("title", playlist.title)
                .put("total", playlist.total)
                .put("entries", entries),
        )
    }

    private fun writeTranscriptFile(name: String, json: JSONObject) {
        try {
            val dir = File(context.cacheDir, "transcripts").apply { mkdirs() }
            File(dir, name).writeText(json.toString())
            pruneCache()
        } catch (e: Exception) {
            // A failed cache write is not fatal.
        }
    }

    private fun transcriptToJson(transcript: Transcript): JSONObject = JSONObject()
        .put("title", transcript.title)
        .put("description", transcript.description)
        .put("url", transcript.url)
        .put("text", transcript.text)
        .put("chapters", JSONArray().apply {
            transcript.chapters.forEach {
                put(JSONObject().put("ms", it.timeMs).put("t", it.title))
            }
        })
        .put("channel", transcript.channel)
        .put("duration", transcript.durationSeconds)
        .put("uploadDate", transcript.uploadDate)
        .put("sponsorSegments", JSONArray().apply {
            transcript.sponsorSegments.forEach {
                put(JSONObject().put("s", it.startMs).put("e", it.endMs).put("c", it.category))
            }
        })
        .put("playlistIndex", transcript.playlistIndex)

    /** Drops expired entries, then keeps the cache dir under a size cap. */
    private fun pruneCache() {
        try {
            val files = File(context.cacheDir, "transcripts")
                .listFiles()?.filter { it.isFile } ?: return
            val now = System.currentTimeMillis()
            files.filter { file ->
                val ttl = if (file.name.startsWith("missing")) {
                    MISSING_TTL_MILLIS
                } else {
                    TRANSCRIPT_TTL_MILLIS
                }
                now - file.lastModified() > ttl
            }.forEach { it.delete() }

            var total = files.sumOf { it.length() }
            for (file in files.sortedBy { it.lastModified() }) {
                if (total <= CACHE_MAX_BYTES) break
                total -= file.length()
                file.delete()
            }
        } catch (e: Exception) {
            // A failed prune is not fatal.
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
        // The v7- prefix invalidates entries written before the playlist path
        // (and its playlistIndex field) existed.
        "v7-" + MessageDigest.getInstance("MD5").digest(videoUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun lastUpdateAgoMillis(): Long =
        System.currentTimeMillis() - prefs.getLong(KEY_LAST_UPDATE, 0L)

    // Serialized with execute via ytLock: updates and fetches share one work dir.
    private fun updateBinary() = synchronized(ytLock) {
        try {
            YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
            prefs.edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
        } catch (e: Exception) {
            // Best effort: fetch with the binary we have when the update server is unreachable.
        }
    }

    private companion object {
        val SUBTITLE_EXTENSIONS = setOf("json3", "vtt", "srt")
        const val SESSION_FILE = "session.txt"
        const val PLAYLIST_MAX = 30
        // ponytail: 10 MB ≈ many hours of word-level captions; bigger files are
        // skipped — reading one into the heap OOMs (org.json needs the whole string).
        const val MAX_PARSE_BYTES = 10L * 1024 * 1024
        const val UPDATE_TTL_MILLIS = 72L * 60 * 60 * 1000
        const val RECENT_UPDATE_MILLIS = 12L * 60 * 60 * 1000
        const val TRANSCRIPT_TTL_MILLIS = 60L * 60 * 1000
        const val MISSING_TTL_MILLIS = 24L * 60 * 60 * 1000
        const val CACHE_MAX_BYTES = 30L * 1024 * 1024
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
