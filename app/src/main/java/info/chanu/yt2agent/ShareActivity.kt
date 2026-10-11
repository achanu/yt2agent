package info.chanu.yt2agent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast

/**
 * Receives a YouTube link shared from another app, fetches the video transcript, and
 * re-shares it as plain text.
 */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val videoUrl = intent?.getStringExtra(Intent.EXTRA_TEXT)?.let(::findSharedUrl)
        if (videoUrl == null) {
            Toast.makeText(this, R.string.no_youtube_link, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContentView(R.layout.activity_share)

        Thread {
            try {
                val transcript = TranscriptFetcher(this).fetch(videoUrl) { title ->
                    runOnUiThread {
                        findViewById<TextView>(R.id.status).text =
                            getString(R.string.fetching_named, title)
                    }
                }
                runOnUiThread { shareTranscript(transcript) }
            } catch (e: Exception) {
                Log.e("yt2agent", "transcript fetch failed", e)
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.fetch_failed, firstErrorLine(e)), Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    private fun shareTranscript(transcript: Transcript) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, transcript.title.ifEmpty { transcript.url })
            putExtra(Intent.EXTRA_TEXT, buildShareText(transcript))
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_via)))
        finish()
    }
}

/**
 * Builds the shared text: title, description, video source, then the timestamped
 * transcript. All URLs are scheme-stripped (`stripLinkSchemes`): destination apps
 * that sniff links discard everything else from a text containing a real URL.
 */
internal fun buildShareText(transcript: Transcript): String = buildString {
    if (transcript.title.isNotEmpty()) appendLine(stripLinkSchemes(transcript.title)).appendLine()
    if (transcript.description.isNotEmpty()) appendLine(stripLinkSchemes(transcript.description)).appendLine()
    appendLine("Source: ${shortUrl(transcript.url)}").appendLine()
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
