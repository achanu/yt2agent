package info.chanu.yt2agent

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Receives a link shared from another app, fetches the video transcript, and
 * re-shares it as plain text. Fetch failures show an error screen with a retry.
 */
class ShareActivity : Activity() {

    private var videoUrl: String = ""

    /** Bumped on every new fetch; stale fetches must not touch the UI. */
    private var fetchGeneration = 0

    private val prefs by lazy { getSharedPreferences("yt2agent", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleSharedText(intent?.getStringExtra(Intent.EXTRA_TEXT))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A share arriving while this instance is already showing (e.g. on the
        // error screen) must be handled, not ignored.
        handleSharedText(intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    private fun handleSharedText(sharedText: String?) {
        videoUrl = sharedText?.let(::findSharedUrl) ?: ""
        if (videoUrl.isEmpty()) {
            Toast.makeText(this, R.string.no_link, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        showFetchScreen()
        startFetch()
    }

    /** Inflates the fetch screen and wires its checkboxes to their preferences. */
    private fun showFetchScreen() {
        setContentView(R.layout.activity_share)
        wireCheckbox(R.id.include_description, KEY_INCLUDE_DESCRIPTION, default = true)
        wireCheckbox(R.id.remove_sponsors, KEY_REMOVE_SPONSORS, default = false)
        wireCheckbox(R.id.remove_selfpromo, KEY_REMOVE_SELF_PROMO, default = false)
        if (isPlaylistUrl(videoUrl)) {
            // The playlist document is a wall of text: it always goes out as a
            // file. Checked and greyed, without touching the stored preference.
            findViewById<CheckBox>(R.id.share_as_file).apply {
                isChecked = true
                isEnabled = false
            }
        } else {
            wireCheckbox(R.id.share_as_file, KEY_SHARE_AS_FILE, default = false)
        }
    }

    /** Binds a checkbox to its persistent preference. */
    private fun wireCheckbox(id: Int, key: String, default: Boolean) {
        val checkbox = findViewById<CheckBox>(id)
        checkbox.isChecked = prefs.getBoolean(key, default)
        checkbox.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
        }
    }

    private fun startFetch() {
        val generation = ++fetchGeneration
        val fetchStart = SystemClock.elapsedRealtime()
        val timer = Thread {
            val start = SystemClock.elapsedRealtime()
            try {
                while (generation == fetchGeneration) {
                    val seconds = (SystemClock.elapsedRealtime() - start) / 1000
                    runOnUiThread {
                        findViewById<TextView>(R.id.elapsed).text =
                            getString(R.string.elapsed, seconds)
                    }
                    Thread.sleep(1000)
                }
            } catch (e: InterruptedException) {
                // The fetch ended; stop ticking.
            }
        }
        timer.start()
        val onTitle = { title: String ->
            if (generation == fetchGeneration) {
                runOnUiThread {
                    findViewById<TextView>(R.id.status).text =
                        getString(R.string.fetching_named, title)
                }
            }
        }
        val onStatus = { line: String ->
            if (generation == fetchGeneration) {
                runOnUiThread {
                    findViewById<TextView>(R.id.operation).text =
                        operationStringRes(line)?.let { getString(it) } ?: line
                }
            }
        }
        Thread {
            try {
                val fetcher = TranscriptFetcher(this)
                if (isPlaylistUrl(videoUrl)) {
                    val playlist = fetcher.fetchPlaylist(videoUrl, onTitle, onStatus)
                    holdFetchScreen(fetchStart)
                    if (generation == fetchGeneration) {
                        runOnUiThread { sharePlaylist(playlist) }
                    }
                } else {
                    val transcript = fetcher.fetch(videoUrl, onTitle, onStatus)
                    holdFetchScreen(fetchStart)
                    if (generation == fetchGeneration) {
                        runOnUiThread { shareTranscript(transcript) }
                    }
                }
            } catch (e: Exception) {
                Log.e("yt2agent", "transcript fetch failed", e)
                if (generation == fetchGeneration) {
                    runOnUiThread { showError(e) }
                }
            } finally {
                timer.interrupt()
            }
        }.start()
    }

    /** Cache hits resolve instantly; keep the screen up long enough to reach the checkboxes. */
    private fun holdFetchScreen(fetchStart: Long) {
        val elapsed = SystemClock.elapsedRealtime() - fetchStart
        if (elapsed < MIN_FETCH_DISPLAY_MILLIS) {
            Thread.sleep(MIN_FETCH_DISPLAY_MILLIS - elapsed)
        }
    }

    /** Writes the share text to a cache file and exposes it via the FileProvider. */
    private fun fileUri(title: String, text: String): Uri {
        val dir = File(cacheDir, "shared").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val file = File(dir, sanitizeFilename(title) + ".txt")
        file.writeText(text)
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }

    private fun showError(e: Exception) {
        setContentView(R.layout.activity_error)
        findViewById<TextView>(R.id.error_message).text = firstErrorLine(e)
        findViewById<Button>(R.id.copy_details).setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(
                ClipData.newPlainText("yt2agent", Log.getStackTraceString(e)),
            )
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        // "No transcript" is permanent for this video; retrying would be pointless.
        findViewById<Button>(R.id.retry).visibility =
            if (e is IllegalStateException) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.retry).setOnClickListener {
            showFetchScreen()
            startFetch()
        }
        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }
    }

    private fun shareTranscript(transcript: Transcript) {
        val text = buildShareText(
            transcript,
            getString(R.string.chapters_label),
            Locale.getDefault(),
            prefs.getBoolean(KEY_INCLUDE_DESCRIPTION, true),
            prefs.getBoolean(KEY_REMOVE_SPONSORS, false),
            prefs.getBoolean(KEY_REMOVE_SELF_PROMO, false),
        )
        shareOut(
            transcript.title.ifEmpty { transcript.url },
            text,
            prefs.getBoolean(KEY_SHARE_AS_FILE, false),
        )
    }

    /** The playlist document always goes out as a file attachment. */
    private fun sharePlaylist(playlist: PlaylistTranscript) {
        val text = buildPlaylistDocument(
            playlist,
            Locale.getDefault(),
            getString(R.string.playlist_videos),
            prefs.getBoolean(KEY_REMOVE_SPONSORS, false),
            prefs.getBoolean(KEY_REMOVE_SELF_PROMO, false),
        )
        shareOut(playlist.title.ifEmpty { videoUrl }, text, asFile = true)
    }

    private fun shareOut(title: String, text: String, asFile: Boolean) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            if (asFile) {
                // Long texts choke some destination apps; the file carries the
                // same content as an attachment.
                putExtra(Intent.EXTRA_STREAM, fileUri(title, text))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                putExtra(Intent.EXTRA_TEXT, text)
            }
        }
        val chooser = Intent.createChooser(send, getString(R.string.share_via)).apply {
            // yt2agent itself shares text/plain; do not offer it as a destination.
            putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                arrayOf(ComponentName(this@ShareActivity, ShareActivity::class.java)),
            )
        }
        startActivity(chooser)
        finish()
    }
}

/**
 * Maps a yt-dlp output line to a localized short operation label, or null to show
 * the raw line.
 */
internal fun operationStringRes(line: String): Int? = when {
    line.contains("Extracting URL") -> R.string.op_extracting
    line.contains("Downloading webpage") -> R.string.op_webpage
    line.contains("player API JSON") -> R.string.op_player
    line.startsWith("[download] Destination:") -> R.string.op_downloading
    line.contains("Writing video subtitles") || line.contains("Writing video metadata") ->
        R.string.op_writing
    else -> null
}

private const val DESCRIPTION_MAX_CHARS = 1000
private const val KEY_INCLUDE_DESCRIPTION = "include_description"
private const val KEY_SHARE_AS_FILE = "share_as_file"
private const val MIN_FETCH_DISPLAY_MILLIS = 1000L
private const val KEY_REMOVE_SPONSORS = "remove_sponsors"
private const val KEY_REMOVE_SELF_PROMO = "remove_selfpromo"

/**
 * Caps the description: some are huge walls of links and hashtags and would bloat
 * the shared text.
 */
internal fun capDescription(description: String): String =
    if (description.length <= DESCRIPTION_MAX_CHARS) {
        description
    } else {
        description.take(DESCRIPTION_MAX_CHARS - 1).trimEnd() + "…"
    }

/**
 * Builds the metadata line under the title — "channel — duration — upload date" —
 * with missing parts omitted.
 */
internal fun buildInfoLine(transcript: Transcript, locale: Locale): String = buildList {
    if (transcript.channel.isNotEmpty()) add(transcript.channel)
    if (transcript.durationSeconds > 0) add(formatTime(transcript.durationSeconds * 1000))
    if (transcript.uploadDate.length == 8) {
        val parsed = SimpleDateFormat("yyyyMMdd", Locale.US).parse(transcript.uploadDate)
        add(SimpleDateFormat("d MMMM yyyy", locale).format(parsed))
    }
}.joinToString(" — ")

/**
 * Builds the shared text: title, metadata line, capped description, video source,
 * chapter summary, then the timestamped transcript. All URLs are scheme-stripped
 * (`stripLinkSchemes`): destination apps that sniff links discard everything else
 * from a text containing a real URL.
 */
internal fun buildShareText(
    transcript: Transcript,
    chaptersLabel: String,
    locale: Locale,
    includeDescription: Boolean,
    removeSponsors: Boolean,
    removeSelfPromo: Boolean,
): String = buildString {
    if (transcript.title.isNotEmpty()) appendLine(stripLinkSchemes(transcript.title)).appendLine()
    val infoLine = buildInfoLine(transcript, locale)
    if (infoLine.isNotEmpty()) appendLine(infoLine).appendLine()
    if (includeDescription && transcript.description.isNotEmpty()) {
        appendLine(capDescription(stripLinkSchemes(transcript.description))).appendLine()
    }
    appendLine("Source: ${shortUrl(transcript.url)}").appendLine()
    if (transcript.chapters.isNotEmpty()) {
        appendLine(chaptersLabel)
        transcript.chapters.forEach { appendLine("[${formatTime(it.timeMs)}] ${it.title}") }
        appendLine()
    }
    append(transcriptBody(transcript, removeSponsors, removeSelfPromo))
}

/**
 * Builds the shared playlist document: a header (title, video count), then one block
 * per video — "[i/N] title", metadata line, timestamped transcript. Per-video
 * descriptions and sources stay out: at 30 videos they would drown the transcripts.
 */
internal fun buildPlaylistDocument(
    playlist: PlaylistTranscript,
    locale: Locale,
    videosLabel: String,
    removeSponsors: Boolean,
    removeSelfPromo: Boolean,
): String = buildString {
    if (playlist.title.isNotEmpty()) appendLine(stripLinkSchemes(playlist.title))
    appendLine(videosLabel.format(playlist.total)).appendLine()
    playlist.entries.forEach { entry ->
        appendLine("[${entry.playlistIndex}/${playlist.total}] ${stripLinkSchemes(entry.title)}")
        buildInfoLine(entry, locale).takeIf { it.isNotEmpty() }?.let { appendLine(it) }
        appendLine()
        append(transcriptBody(entry, removeSponsors, removeSelfPromo))
        appendLine().appendLine()
    }
}.trimEnd('\n')

/** The transcript text with the requested sponsor segments filtered out. */
private fun transcriptBody(
    transcript: Transcript,
    removeSponsors: Boolean,
    removeSelfPromo: Boolean,
): String {
    val toRemove = transcript.sponsorSegments.filter {
        when (it.category) {
            "sponsor" -> removeSponsors
            "selfpromo", "interaction" -> removeSelfPromo
            else -> false
        }
    }
    return if (toRemove.isEmpty()) {
        transcript.text
    } else {
        removeSponsorSegments(transcript.text, toRemove)
    }
}

internal fun shortUrl(url: String): String =
    url.removePrefix("https://www.youtube.com/watch?v=").let {
        if (it == url) url else "youtu.be/$it"
    }

internal fun stripLinkSchemes(text: String): String =
    text.replace(Regex("""\bhttps?://""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\bwww\.""", RegexOption.IGNORE_CASE), "")

/**
 * YoutubeDLException messages embed the whole yt-dlp stderr including warnings;
 * surface only the first actual error line.
 */
private fun firstErrorLine(e: Exception): String =
    e.message?.lineSequence()?.firstOrNull { "ERROR" in it } ?: e.message ?: e.toString()

/** Builds a safe file name from a video title. */
internal fun sanitizeFilename(title: String): String =
    title.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(100).ifEmpty { "transcript" }

/**
 * Extracts the first URL from shared text, or null if it contains none. Any URL
 * accepted by yt-dlp works (YouTube, Vimeo, PeerTube, ...).
 */
internal fun findSharedUrl(sharedText: String): String? =
    urlRegex.find(sharedText)?.value?.trimEnd('.', ',', ')')

/**
 * Whether [url] points at a YouTube playlist or channel — the multi-transcript
 * path. A watch URL counts only when it carries both `list=` and `index=` (a share
 * from inside a playlist); `watch?v=…&list=…` without an index stays a single
 * video, and so does every other site.
 */
internal fun isPlaylistUrl(url: String): Boolean {
    val noScheme = url.substringAfter("://")
    val host = noScheme.substringBefore('/')
    if (host != "youtube.com" && !host.endsWith(".youtube.com")) return false
    val path = noScheme.substringAfter('/', "")
    if (path.startsWith("watch")) return "list=" in path && "index=" in path
    return path.startsWith("playlist") ||
        path.startsWith("@") ||
        path.startsWith("channel/") ||
        path.startsWith("c/") ||
        path.startsWith("user/")
}

private val urlRegex = Regex("""https?://\S+""")
