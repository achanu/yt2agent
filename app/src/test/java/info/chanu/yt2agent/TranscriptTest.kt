package info.chanu.yt2agent

import org.json.JSONObject
import java.util.Locale
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
    fun parsesChaptersAndInsertsMarkersAtTheirTimestamps() {
        val transcript = "[00:00] intro\n[01:00] milieu\n[02:00] fin"
        val info = JSONObject(
            """{"chapters": [
                {"start_time": 0, "title": "Début"},
                {"start_time": 90, "title": "Après"}
            ]}""",
        )
        val chapters = parseChapters(info)

        assertEquals(listOf(Chapter(0, "Début"), Chapter(90_000, "Après")), chapters)
        assertEquals(
            "[00:00] [Chapitre] Début\n[00:00] intro\n[01:00] milieu\n" +
                "[01:30] [Chapitre] Après\n[02:00] fin",
            insertChapters(transcript, chapters),
        )
    }

    @Test
    fun insertChaptersLeavesTranscriptAloneWhenNone() {
        assertEquals(emptyList<Chapter>(), parseChapters(JSONObject("""{"chapters": []}""")))
        assertEquals(emptyList<Chapter>(), parseChapters(JSONObject("{}")))
        assertEquals("[00:00] seul", insertChapters("[00:00] seul", emptyList()))
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
            buildShareText(transcript, "Chapitres :", Locale.FRANCE, false),
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
            buildShareText(transcript, "Chapitres :", Locale.FRANCE, false),
        )
    }

    @Test
    fun buildsShareTextWithChapterSummary() {
        val transcript = Transcript(
            title = "Titre",
            description = "Desc",
            url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            text = "[00:00] texte",
            chapters = listOf(Chapter(0, "Intro"), Chapter(83_000, "Suite")),
        )

        assertEquals(
            "Titre\n\nDesc\n\nSource: youtu.be/dQw4w9WgXcQ\n\n" +
                "Chapitres :\n[00:00] Intro\n[01:23] Suite\n\n[00:00] texte",
            buildShareText(transcript, "Chapitres :", Locale.FRANCE, false),
        )
    }

    @Test
    fun buildsMetadataLineWithAllParts() {
        val transcript = Transcript(
            title = "T",
            description = "",
            url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            text = "[00:00] x",
            channel = "DEF CON",
            durationSeconds = 2715,
            uploadDate = "20240812",
        )

        assertEquals(
            "DEF CON — 45:15 — 12 août 2024",
            buildInfoLine(transcript, Locale.FRANCE),
        )
    }

    @Test
    fun metadataLineOmitsMissingParts() {
        val transcript = Transcript(
            title = "T",
            description = "",
            url = "https://youtu.be/dQw4w9WgXcQ",
            text = "[00:00] x",
            channel = "",
            durationSeconds = 0,
            uploadDate = "",
        )

        assertEquals("", buildInfoLine(transcript, Locale.FRANCE))
    }

    @Test
    fun parsesSponsorSegmentsFromMetadata() {
        val info = JSONObject(
            """{"sponsorblock_chapters": [
                {"category": "sponsor", "start_time": 0, "end_time": 89.5},
                {"category": "interaction", "start_time": 90, "end_time": 95}
            ]}""",
        )

        assertEquals(
            listOf(Segment(0, 89_500, "sponsor"), Segment(90_000, 95_000, "interaction")),
            parseSponsorSegments(info),
        )
        assertEquals(emptyList<Segment>(), parseSponsorSegments(JSONObject("{}")))
    }

    @Test
    fun removesLinesInsideSponsorSegments() {
        val text = "[00:00] sponsor line\n[01:30] kept line\n[01:29] still sponsor\nno timestamp"
        val segments = listOf(Segment(0, 89_500, "sponsor"))

        assertEquals(
            "[01:30] kept line\nno timestamp",
            removeSponsorSegments(text, segments),
        )
    }

    @Test
    fun buildShareTextDropsSponsorsWhenRequested() {
        val transcript = Transcript(
            title = "T",
            description = "",
            url = "https://www.youtube.com/watch?v=7q8Gu4amvl4",
            text = "[00:00] sponsor line\n[01:30] kept line",
            sponsorSegments = listOf(Segment(0, 89_500, "sponsor")),
        )

        assertEquals(
            "T\n\nSource: youtu.be/7q8Gu4amvl4\n\n[00:00] sponsor line\n[01:30] kept line",
            buildShareText(transcript, "Chapitres :", Locale.FRANCE, false),
        )
        assertEquals(
            "T\n\nSource: youtu.be/7q8Gu4amvl4\n\n[01:30] kept line",
            buildShareText(transcript, "Chapitres :", Locale.FRANCE, true),
        )
    }

    @Test
    fun capsLongDescriptions() {
        val short = "a".repeat(1000)
        assertEquals(short, capDescription(short))
        val long = "b".repeat(2500)
        assertEquals(1000, capDescription(long).length)
        assertEquals("…", capDescription(long).takeLast(1))
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
            buildShareText(transcript, "Chapitres :", Locale.FRANCE, false),
        )
    }

    @Test
    fun singleOrigTrackIsTakenAsIs() {
        // A French video mislabeled as English by its creator: fr-orig is the real ASR.
        assertEquals(
            "sub.fr-orig.json3",
            pickSubtitle(listOf("sub.fr-orig.json3", "sub.fr.json3", "sub.en.json3"), "fr"),
        )
        // An English video on a French phone: en-orig is the real ASR,
        // sub.fr.json3 is a machine translation of it.
        assertEquals(
            "sub.en-orig.json3",
            pickSubtitle(listOf("sub.en-orig.json3", "sub.fr.json3", "sub.en.json3"), "fr"),
        )
        assertEquals(
            "sub.es-orig.json3",
            pickSubtitle(listOf("sub.en.json3", "sub.es-orig.json3", "sub.fr.json3"), "fr"),
        )
    }

    @Test
    fun multipleOrigTracksPreferEnglish() {
        // YouTube's multi-language ASR: the same audio transcribed in many
        // languages, all marked "Original" — the spoken language is undetectable.
        assertEquals(
            "sub.en-orig.json3",
            pickSubtitle(
                listOf("sub.ar-orig.json3", "sub.en-orig.json3", "sub.fr-FR-orig.json3", "sub.fr.json3"),
                "fr",
            ),
        )
    }

    @Test
    fun multipleOrigTracksWithoutEnglishFallBackToLocale() {
        assertEquals(
            "sub.fr-FR-orig.json3",
            pickSubtitle(listOf("sub.ar-orig.json3", "sub.fr-FR-orig.json3"), "fr"),
        )
    }

    @Test
    fun fallsBackToLocaleWhenNoOrigTrack() {
        assertEquals(
            "sub.fr.json3",
            pickSubtitle(listOf("sub.en.json3", "sub.fr.json3"), "fr"),
        )
    }

    @Test
    fun extractsDisplayTitleFromDestinationLine() {
        assertEquals(
            "Big Buck Bunny 60fps 4K - Official Blender Foundation Short Film",
            destinationTitle(
                "[download] Destination: /data/cache/subs/" +
                    "Big Buck Bunny 60fps 4K - Official Blender Foundation Short Film.fr.json3",
            ),
        )
    }

    @Test
    fun parsesWebVttCues() {
        val vtt = """
            WEBVTT

            NOTE cue settings follow the timing line

            00:00:01.000 --> 00:00:04.000 align:start
            Première ligne
            deuxième partie

            01:02:03.500 --> 01:02:05.000
            <c>Tagué</c> texte
        """.trimIndent()

        assertEquals(
            "[00:01] Première ligne deuxième partie\n[01:02:03] Tagué texte",
            parseVttOrSrt(vtt),
        )
    }

    @Test
    fun parsesSrtCues() {
        val srt = """
            1
            00:00:01,000 --> 00:00:04,000
            Sous-titre un

            2
            00:01:02,500 --> 00:01:05,000
            Sous-titre deux
        """.trimIndent()

        assertEquals(
            "[00:01] Sous-titre un\n[01:02] Sous-titre deux",
            parseVttOrSrt(srt),
        )
    }

    @Test
    fun fallsBackToEnglishThenFirstFile() {
        assertEquals("sub.en.json3", pickSubtitle(listOf("sub.de.json3", "sub.en.json3"), "fr"))
        assertEquals("sub.de.json3", pickSubtitle(listOf("sub.de.json3", "sub.ja.json3"), "fr"))
        assertNull(pickSubtitle(emptyList(), "fr"))
    }
}
