package info.chanu.yt2agent

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.yausername.youtubedl_android.YoutubeDL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The launcher screen: the cached transcripts (date + id subtitle, title opens
 * the video, delete/copy/share icon buttons), and a collapsed config panel —
 * the share options shared with the fetch screen, the pre-prompt field, the
 * transcript-cache TTL dropdown, and the app/yt-dlp versions.
 */
class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        wireCheckbox(R.id.include_description, KEY_INCLUDE_DESCRIPTION, default = true)
        wireCheckbox(R.id.remove_sponsors, KEY_REMOVE_SPONSORS, default = false)
        wireCheckbox(R.id.remove_selfpromo, KEY_REMOVE_SELF_PROMO, default = false)
        wireCheckbox(R.id.share_as_file, KEY_SHARE_AS_FILE, default = false)
        wirePreprompt()

        // The config panel lives collapsed at the bottom; the header expands it.
        findViewById<View>(R.id.config_header).setOnClickListener {
            val panel = findViewById<View>(R.id.config_panel)
            val expand = panel.visibility != View.VISIBLE
            panel.visibility = if (expand) View.VISIBLE else View.GONE
            findViewById<View>(R.id.config_chevron).rotation = if (expand) 180f else 0f
        }

        // The transcript cache TTL dropdown; the value is read by TranscriptFetcher.
        val hours = resources.getIntArray(R.array.ttl_hours)
        val spinner = findViewById<Spinner>(R.id.ttl_spinner)
        val saved = prefs.getInt(KEY_TTL_HOURS, DEFAULT_TTL_HOURS)
        spinner.setSelection(
            hours.indexOfFirst { it == saved }.takeIf { it >= 0 }
                ?: hours.indexOf(DEFAULT_TTL_HOURS),
        )
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                prefs.edit().putInt(KEY_TTL_HOURS, hours[pos]).apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<TextView>(R.id.app_version).text = getString(
            R.string.app_version,
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?",
        )
        Thread { showYtDlpVersion() }.start()
        Thread { showCachedTranscripts() }.start()
    }

    /**
     * The pre-prompt checkbox shows its text field; unchecking hides the field
     * without clearing the stored text.
     */
    private fun wirePreprompt() {
        val checkbox = findViewById<CheckBox>(R.id.preprompt_enable)
        val edit = findViewById<EditText>(R.id.preprompt_text)
        checkbox.isChecked = prefs.getBoolean(KEY_INCLUDE_PREPROMPT, false)
        edit.setText(prefs.getString(KEY_PREPROMPT, "") ?: "")
        edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString(KEY_PREPROMPT, s?.toString().orEmpty()).apply()
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        fun syncField() {
            edit.visibility = if (checkbox.isChecked) View.VISIBLE else View.GONE
        }
        checkbox.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_INCLUDE_PREPROMPT, checked).apply()
            syncField()
        }
        syncField()
    }

    /** init() unpacks the yt-dlp binary (seconds on first launch); keep it off the UI thread. */
    private fun showYtDlpVersion() {
        val version = try {
            YoutubeDL.getInstance().init(this)
            YoutubeDL.getInstance().version(this)
        } catch (e: Exception) {
            getString(R.string.unknown)
        }
        runOnUiThread {
            findViewById<TextView>(R.id.yt_dlp_version).text =
                getString(R.string.yt_dlp_version, version)
        }
    }

    private fun showCachedTranscripts() {
        // cachedTranscripts() already swallows its own failures (empty dir,
        // unreadable or malformed entries); nothing here can throw.
        val entries = TranscriptFetcher(this).cachedTranscripts()
        runOnUiThread {
            findViewById<TextView>(R.id.no_cached).visibility =
                if (entries.isEmpty()) View.VISIBLE else View.GONE
            val list = findViewById<LinearLayout>(R.id.cached_list)
            entries.forEach { list.addView(row(it)) }
        }
    }

    private fun row(entry: CachedEntry): View {
        val (title, text) = shareContent(entry.content)
        val view = layoutInflater.inflate(R.layout.item_transcript, null)
        view.findViewById<TextView>(R.id.title).text = title
        view.findViewById<TextView>(R.id.subtitle).text = subtitle(entry)
        url(entry.content)?.let { url ->
            view.findViewById<TextView>(R.id.title).setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
        view.findViewById<View>(R.id.copy).setOnClickListener {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("yt2agent", prepromptPrefix() + text))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.share).setOnClickListener {
            // The playlist document is a wall of text: it always goes out as a file.
            val asFile = entry.content is CachedTranscript.Playlist ||
                prefs.getBoolean(KEY_SHARE_AS_FILE, false)
            shareOut(title, text, asFile)
        }
        view.findViewById<View>(R.id.delete).setOnClickListener { confirmDelete(entry, view) }
        return view
    }

    /** Deletion asks for confirmation: the entry would otherwise cost a full refetch. */
    private fun confirmDelete(entry: CachedEntry, row: View) {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                entry.file.delete()
                (row.parent as? ViewGroup)?.removeView(row)
                val empty = findViewById<LinearLayout>(R.id.cached_list).childCount == 0
                findViewById<TextView>(R.id.no_cached).visibility =
                    if (empty) View.VISIBLE else View.GONE
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** The entry's source URL, or null for a legacy cache entry without one. */
    private fun url(content: CachedTranscript): String? = when (content) {
        is CachedTranscript.Video -> content.transcript.url
        is CachedTranscript.Playlist -> content.playlist.url
    }.takeIf { it.isNotEmpty() }

    /** The subtitle under the title: when the entry was cached, and its id. */
    private fun subtitle(entry: CachedEntry): String = buildString {
        append(
            SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
                .format(Date(entry.file.lastModified())),
        )
        url(entry.content)?.let { append("  ·  ").append(stripLinkSchemes(shortUrl(it))) }
    }

    /** The entry's display title and its share text, built with the current options. */
    private fun shareContent(entry: CachedTranscript): Pair<String, String> = when (entry) {
        is CachedTranscript.Video -> {
            val t = entry.transcript
            t.title.ifEmpty { t.url } to buildShareText(
                t,
                getString(R.string.chapters_label),
                Locale.getDefault(),
                prefs.getBoolean(KEY_INCLUDE_DESCRIPTION, true),
                prefs.getBoolean(KEY_REMOVE_SPONSORS, false),
                prefs.getBoolean(KEY_REMOVE_SELF_PROMO, false),
            )
        }
        is CachedTranscript.Playlist -> {
            val p = entry.playlist
            p.title.ifEmpty { getString(R.string.app_name) } to buildPlaylistDocument(
                p,
                Locale.getDefault(),
                getString(R.string.playlist_videos),
                getString(R.string.no_transcript),
                prefs.getBoolean(KEY_REMOVE_SPONSORS, false),
                prefs.getBoolean(KEY_REMOVE_SELF_PROMO, false),
                prefs.getBoolean(KEY_INCLUDE_DESCRIPTION, true),
            )
        }
    }
}
