package info.chanu.yt2agent

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
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
        setContentView(R.layout.activity_share)
        startFetch()
    }

    private fun startFetch() {
        val generation = ++fetchGeneration
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
        Thread {
            try {
                val transcript = TranscriptFetcher(this).fetch(
                    videoUrl,
                    onTitle = { title ->
                        if (generation == fetchGeneration) {
                            runOnUiThread {
                                findViewById<TextView>(R.id.status).text =
                                    getString(R.string.fetching_named, title)
                            }
                        }
                    },
                    onStatus = { line ->
                        if (generation == fetchGeneration) {
                            runOnUiThread {
                                findViewById<TextView>(R.id.operation).text =
                                    operationStringRes(line)?.let { getString(it) } ?: line
                            }
                        }
                    },
                )
                if (generation == fetchGeneration) {
                    runOnUiThread { shareTranscript(transcript) }
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

    private fun showError(e: Exception) {
        setContentView(R.layout.activity_error)
        findViewById<TextView>(R.id.error_message).text = firstErrorLine(e)
        // "No transcript" is permanent for this video; retrying would be pointless.
        findViewById<Button>(R.id.retry).visibility =
            if (e is IllegalStateException) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.retry).setOnClickListener {
            setContentView(R.layout.activity_share)
            startFetch()
        }
        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }
    }

    private fun shareTranscript(transcript: Transcript) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, transcript.title.ifEmpty { transcript.url })
            putExtra(
                Intent.EXTRA_TEXT,
                buildShareText(
                    transcript,
                    getString(R.string.chapters_label),
                    Locale.getDefault(),
                ),
            )
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
): String = buildString {
    if (transcript.title.isNotEmpty()) appendLine(stripLinkSchemes(transcript.title)).appendLine()
    val infoLine = buildInfoLine(transcript, locale)
    if (infoLine.isNotEmpty()) appendLine(infoLine).appendLine()
    if (transcript.description.isNotEmpty()) {
        appendLine(capDescription(stripLinkSchemes(transcript.description))).appendLine()
    }
    appendLine("Source: ${shortUrl(transcript.url)}").appendLine()
    if (transcript.chapters.isNotEmpty()) {
        appendLine(chaptersLabel)
        transcript.chapters.forEach { appendLine("[${formatTime(it.timeMs)}] ${it.title}") }
        appendLine()
    }
    append(transcript.text)
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

/**
 * Extracts the first URL from shared text, or null if it contains none. Any URL
 * accepted by yt-dlp works (YouTube, Vimeo, PeerTube, ...).
 */
internal fun findSharedUrl(sharedText: String): String? =
    urlRegex.find(sharedText)?.value?.trimEnd('.', ',', ')')

private val urlRegex = Regex("""https?://\S+""")
