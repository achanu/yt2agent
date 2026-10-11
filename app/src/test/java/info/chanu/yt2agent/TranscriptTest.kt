package info.chanu.yt2agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptTest {

    @Test
    fun extractsFirstUrlFromSharedText() {
        assertEquals(
            "https://youtu.be/dQw4w9WgXcQ?si=abc123",
            findSharedUrl("Check this out https://youtu.be/dQw4w9WgXcQ?si=abc123"),
        )
        assertEquals(
            "https://m.youtube.com/watch?app=desktop&v=dQw4w9WgXcQ",
            findSharedUrl("https://m.youtube.com/watch?app=desktop&v=dQw4w9WgXcQ"),
        )
        assertEquals(
            "https://vimeo.com/22439234",
            findSharedUrl("https://vimeo.com/22439234"),
        )
    }

    @Test
    fun stripsTrailingPunctuationFromUrl() {
        assertEquals(
            "https://youtu.be/dQw4w9WgXcQ",
            findSharedUrl("Regarde https://youtu.be/dQw4w9WgXcQ."),
        )
    }

    @Test
    fun returnsNullWhenNoUrl() {
        assertNull(findSharedUrl("hello world"))
    }

    @Test
    fun insertsChapterMarkersAtTheirTimestamps() {
        val transcript = "[00:00] intro\n[01:00] milieu\n[02:00] fin"
        val info = JSONObject(
            """{"chapters": [
                {"start_time": 0, "title": "Début"},
                {"start_time": 90, "title": "Après"}
            ]}""",
        )

        assertEquals(
            "[00:00] [Chapitre] Début\n[00:00] intro\n[01:00] milieu\n" +
                "[01:30] [Chapitre] Après\n[02:00] fin",
            insertChapters(transcript, info),
        )
    }

    @Test
    fun insertChaptersLeavesTranscriptAloneWhenNone() {
        assertEquals(
            "[00:00] seul",
            insertChapters("[00:00] seul", JSONObject("""{"chapters": []}""")),
        )
        assertEquals(
            "[00:00] seul",
            insertChapters("[00:00] seul", JSONObject("{}")),
        )
    }

    @Test
    fun parsesJson3WithAppendedAutoCaptionEvents() {
        val json = """
        {"events": [
            {"tStartMs": 0, "segs": [{"utf8": "hello"}]},
            {"tStartMs": 500, "aAppend": 1, "segs": [{"utf8": " world"}]},
            {"tStartMs": 1000, "segs": [{"utf8": "\n"}]},
            {"tStartMs": 1500, "segs": [{"utf8": "second line"}]}
        ]}
        """.trimIndent()

        assertEquals("[00:00] hello world\n[00:01] second line", parseJson3(json))
    }

    @Test
    fun parsesManualCaptionEventsAsSeparateLines() {
        val json = """
        {"events": [
            {"tStartMs": 0, "segs": [{"utf8": "first"}]},
            {"tStartMs": 2000, "segs": [{"utf8": "second"}]}
        ]}
        """.trimIndent()

        assertEquals("[00:00] first\n[00:02] second", parseJson3(json))
    }

    @Test
    fun formatsTimestampsBeyondOneHour() {
        val json = """
        {"events": [
            {"tStartMs": 3723000, "segs": [{"utf8": "long video"}]}
        ]}
        """.trimIndent()

        assertEquals("[01:02:03] long video", parseJson3(json))
    }

    @Test
    fun buildsShareTextWithTitleDescriptionAndSource() {
        val transcript = Transcript(
            title = "Le fantôme MK",
            description = "Une vidéo sur les robots humanoïdes.",
            url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            text = "[00:00] bonjour",
        )

        assertEquals(
            "Le fantôme MK\n\nUne vidéo sur les robots humanoïdes.\n\n" +
                "Source: youtu.be/dQw4w9WgXcQ\n\n[00:00] bonjour",
            buildShareText(transcript),
        )
    }

    @Test
    fun buildShareTextSkipsEmptyTitleAndDescription() {
        val transcript = Transcript(
            title = "",
            description = "",
            url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            text = "[00:00] bonjour",
        )

        assertEquals(
            "Source: youtu.be/dQw4w9WgXcQ\n\n[00:00] bonjour",
            buildShareText(transcript),
        )
    }

    @Test
    fun shortUrlKeepsNonWatchUrlsAsIs() {
        assertEquals(
            "youtu.be/dQw4w9WgXcQ",
            shortUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
        )
        assertEquals(
            "https://youtu.be/dQw4w9WgXcQ",
            shortUrl("https://youtu.be/dQw4w9WgXcQ"),
        )
    }

    @Test
    fun stripsSchemesFromUrlsInTitleAndDescription() {
        val transcript = Transcript(
            title = "Regarde twitter.com/x",
            description = "Plus d'infos sur https://exemple.fr/page et www.site.com/a",
            url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            text = "[00:00] bonjour",
        )

        assertEquals(
            "Regarde twitter.com/x\n\nPlus d'infos sur exemple.fr/page et site.com/a\n\n" +
                "Source: youtu.be/dQw4w9WgXcQ\n\n[00:00] bonjour",
            buildShareText(transcript),
        )
    }

    @Test
    fun picksOriginalTrackOverLocaleAndEnglish() {
        assertEquals(
            "sub.es-orig.json3",
            pickSubtitle(listOf("sub.en.json3", "sub.es-orig.json3", "sub.fr.json3"), "fr"),
        )
    }

    @Test
    fun picksDeviceLocaleWhenNoOriginalTrack() {
        assertEquals(
            "sub.fr.json3",
            pickSubtitle(listOf("sub.en.json3", "sub.fr.json3"), "fr"),
        )
    }

    @Test
    fun fallsBackToEnglishThenFirstFile() {
        assertEquals("sub.en.json3", pickSubtitle(listOf("sub.de.json3", "sub.en.json3"), "fr"))
        assertEquals("sub.de.json3", pickSubtitle(listOf("sub.de.json3", "sub.ja.json3"), "fr"))
        assertNull(pickSubtitle(emptyList(), "fr"))
    }
}
