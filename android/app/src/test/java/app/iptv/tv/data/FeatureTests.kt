package app.iptv.tv.data

import app.iptv.tv.model.Channel
import app.iptv.tv.model.ContentKind
import app.iptv.tv.model.MediaInfo
import app.iptv.tv.model.Playlist
import app.iptv.tv.model.PlaylistSource
import app.iptv.tv.model.XtreamCredentials
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncFormatTest {
    /** So schreibt die iOS-App (Swift Codable, .iso8601) die Datei iptv-sync.json. */
    private val iosJson = """
    {"version":1,"updatedAt":"2026-11-20T10:00:00Z",
     "favorites":{"live|News|ARD":{"isFavorite":true,"changedAt":"2026-11-19T08:00:00Z"},
                  "movie|Action|Matrix":{"isFavorite":false,"changedAt":"2026-11-19T09:00:00Z"}},
     "history":[{"key":"movie|Action|Matrix","channel":{"id":"7E1B6F2A-1111-2222-3333-444455556666","name":"Matrix",
        "url":"http://srv.tv:8080/movie/__IPTV_USER__/__IPTV_PASS__/55.mkv","group":"Action","kind":"movie"},"date":"2026-11-19T20:00:00Z"}],
     "progress":{"movie|Action|Matrix":{"channel":{"name":"Matrix","url":"http://srv.tv:8080/movie/__IPTV_USER__/__IPTV_PASS__/55.mkv",
        "group":"Action","kind":"movie","logoURL":"http://img/m.jpg"},"position":1234.5,"duration":7200,"updated":"2026-11-19T20:30:00Z"}},
     "settings":{"playerLive":"apple","playerVOD":"vlc","changedAt":"2026-11-18T10:00:00Z"},
     "playlists":[{"id":"A1B2C3D4-0000-1111-2222-333344445555","name":"Mein IPTV",
        "source":{"xtream":{"_0":{"server":"http://srv.tv:8080","username":"max","password":"geheim"}}},
        "detectedEPGURL":"http://srv.tv:8080/xmltv.php","lastUpdated":"2026-11-19T07:00:00Z"},
        {"id":"B1B2C3D4-0000-1111-2222-333344445555","name":"Link","source":{"m3u":{"url":"http://x/list.m3u"}}}],
     "deletedPlaylists":["C1B2C3D4-0000-1111-2222-333344445555","2026-11-01T00:00:00Z"]}
    """.trimIndent()

    @Test
    fun decodesIosSnapshot() {
        val s = SyncSnapshot.fromJson(iosJson)
        assertTrue(s.favorites.getValue("live|News|ARD").isFavorite)
        assertFalse(s.favorites.getValue("movie|Action|Matrix").isFavorite)
        val h = s.history.single()
        assertEquals("Matrix", h.channel.name)
        assertEquals(ContentKind.MOVIE, h.channel.kind)
        val p = s.progress.getValue("movie|Action|Matrix")
        assertEquals(1234.5, p.position, 0.01)
        assertEquals("http://img/m.jpg", p.channel.logo)
        assertEquals("apple", s.settings!!.playerLive)
        val x = s.playlists!![0].source as PlaylistSource.Xtream
        assertEquals("geheim", x.credentials.password)
        assertEquals("http://srv.tv:8080/xmltv.php", s.playlists!![0].detectedEpgUrl)
        assertEquals(setOf("C1B2C3D4-0000-1111-2222-333344445555"), s.deletedPlaylists!!.keys)
    }

    @Test
    fun encodesLikeSwift() {
        val s = SyncSnapshot.fromJson(iosJson)
        val root = Json.parseToJsonElement(s.toJson()) as JsonObject
        // Datumsangaben ohne Sekundenbruchteile, gelöschte Playlists als flache Liste
        assertEquals("2026-11-19T20:00:00Z", ((root["history"] as JsonArray)[0] as JsonObject).str("date"))
        assertEquals(2, (root["deletedPlaylists"] as JsonArray).size)
        val pl = (root["playlists"] as JsonArray)[0] as JsonObject
        assertEquals("geheim", (((pl["source"] as JsonObject)["xtream"] as JsonObject)["_0"] as JsonObject).str("password"))
        assertEquals("vlc", (root["settings"] as JsonObject).str("playerVOD"))
        // Rundreise
        assertEquals(s, SyncSnapshot.fromJson(s.toJson()))
    }

    @Test
    fun mergePrefersNewer() {
        val remote = SyncSnapshot.fromJson(iosJson)
        val local = SyncSnapshot(
            updatedAt = 0,
            favorites = mapOf("live|News|ARD" to FavoriteState(false, SnapshotCodec.parseDate("2026-11-20T08:00:00Z")!!)),
            history = emptyList(),
            progress = emptyMap(),
            settings = SyncSettings("automatic", "automatic", 0),
            playlists = listOf(Playlist(id = "a1b2c3d4-0000-1111-2222-333344445555", name = "Lokal", source = PlaylistSource.M3u("http://y"))),
            deletedPlaylists = mapOf("B1B2C3D4-0000-1111-2222-333344445555" to 1L),
        )
        val m = SyncSnapshot.merge(local, remote)
        assertFalse(m.favorites.getValue("live|News|ARD").isFavorite) // lokal neuer
        assertEquals(1, m.history.size)
        assertEquals("vlc", m.settings!!.playerVod) // entfernt neuer
        // gleiche ID (Groß-/Kleinschreibung egal) → lokale bleibt, gelöschte fehlt
        assertEquals(listOf("Lokal"), m.playlists!!.map { it.name })
    }

    @Test
    fun redactsAndRestoresCredentials() {
        val playlists = listOf(Playlist(name = "X", source = PlaylistSource.Xtream(XtreamCredentials("http://srv.tv:8080", "max", "geh eim"))))
        val r = CredentialRedactor(playlists)
        val ch = Channel("Matrix", "http://srv.tv:8080/movie/max/geh%20eim/55.mkv", kind = ContentKind.MOVIE)
        val red = r.redact(ch)
        assertEquals("http://srv.tv:8080/movie/__IPTV_USER__/__IPTV_PASS__/55.mkv", red.url)
        assertEquals(ch.url, r.restore(red).url)
        val other = Channel("Y", "http://anderer.tv/movie/max/x/1.mp4")
        assertEquals(other.url, r.redact(other).url)
        val q = CredentialRedactor(listOf(Playlist(name = "L", source = PlaylistSource.M3u("http://h/list.php?username=u1&password=p1"))))
        assertEquals("http://h/live.php?username=__IPTV_USER__&password=__IPTV_PASS__&id=3",
            q.redact(Channel("Z", "http://h/live.php?username=u1&password=p1&id=3")).url)
    }

    @Test
    fun emailFromIdToken() {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"email":"a@b.de"}""".toByteArray())
        assertEquals("a@b.de", GoogleSync.emailFromIdToken("x.$payload.y"))
    }
}

class MediaInfoTest {
    @Test
    fun versionsAndTitles() {
        assertEquals("The Matrix", MediaInfo.cleanTitle("DE - The Matrix"))
        assertEquals("matrix" to "1999", MediaInfo.versionKey("DE - Matrix (1999) 4K"))
        val movies = listOf(
            Channel("Matrix (1999) HD", "http://x/1.mkv", kind = ContentKind.MOVIE),
            Channel("DE - Matrix 4K", "http://x/2.mkv", kind = ContentKind.MOVIE),
            Channel("Matrix Reloaded", "http://x/3.mkv", kind = ContentKind.MOVIE),
            Channel("Matrix (2021)", "http://x/4.mkv", kind = ContentKind.MOVIE),
        )
        assertEquals(listOf("DE - Matrix 4K"), MediaInfo.versions(movies[0], movies).map { it.name })
    }

    @Test
    fun trailers() {
        assertEquals("https://www.youtube.com/watch?v=abc_DEF-1", MediaInfo.trailerUrl("abc_DEF-1"))
        assertEquals("https://youtu.be/xyz", MediaInfo.trailerUrl("https://youtu.be/xyz"))
        assertNull(MediaInfo.trailerUrl("http://evil.example/x"))
        assertNull(MediaInfo.trailerUrl(""))
    }
}

class SubtitlesTest {
    @Test
    fun queryForMovieAndEpisode() {
        assertEquals(
            "languages=de,en&order_by=download_count&query=the%20matrix&year=1999",
            Subtitles.searchQuery(Channel("DE - The Matrix (1999)", "http://x/1.mkv", kind = ContentKind.MOVIE), listOf("en", "de")),
        )
        assertEquals(
            "episode_number=3&order_by=download_count&query=dark&season_number=1",
            Subtitles.searchQuery(Channel("Dark S01 E03", "http://x/2.mkv", kind = ContentKind.SERIES), emptyList()),
        )
    }

    @Test
    fun parsesAndSortsResults() {
        val body = """{"data":[
          {"attributes":{"language":"en","release":"Matrix.EN","download_count":900,"files":[{"file_id":1}]}},
          {"attributes":{"language":"de","release":"Matrix.DE","download_count":50,"hearing_impaired":true,"files":[{"file_id":2}]}},
          {"attributes":{"language":"de","release":"dup","download_count":10,"files":[{"file_id":2}]}},
          {"attributes":{"language":"de","release":"Matrix.DE.2","download_count":70,"files":[{"file_id":3}]}}]}"""
        val r = Subtitles.parseResults(body, listOf("de", "en"))
        assertEquals(listOf(3L, 2L, 1L), r.map { it.fileId })
        assertTrue(r[1].hearingImpaired)
    }
}

class CategoryPrefsTest {
    @Test
    fun hideAndOrder() {
        val cats = listOf(Category("A", 1), Category("B", 2), Category("C", 3))
        CategoryPrefs.setHidden(ContentKind.LIVE, "B", true)
        assertEquals(listOf("A", "C"), CategoryPrefs.arranged(cats, ContentKind.LIVE).map { it.name })
        CategoryPrefs.move(ContentKind.LIVE, cats, 2, -2)
        assertEquals(listOf("C", "A", "B"), CategoryPrefs.arranged(cats, ContentKind.LIVE, includeHidden = true).map { it.name })
        assertFalse(CategoryPrefs.isVisible(Channel("x", "http://x/1.ts", "B")))
        assertTrue(CategoryPrefs.isVisible(Channel("x", "http://x/1.ts", "B", kind = ContentKind.MOVIE)))
        CategoryPrefs.setHidden(ContentKind.LIVE, "B", false)
        CategoryPrefs.resetOrder(ContentKind.LIVE)
        assertEquals(listOf("A", "B", "C"), CategoryPrefs.arranged(cats, ContentKind.LIVE).map { it.name })
    }
}

class PhoneFormTest {
    @Test
    fun genericFormAndUpload() {
        val dir = java.nio.file.Files.createTempDirectory("up").toFile()
        val server = PhoneInputServer(
            PhoneInputServer.Form("Test", "Intro", listOf(PhoneInputServer.Field("key", "API-Key", "alt"))),
            uploadDir = dir,
        )
        val t = server.token
        assertTrue(server.respond("GET /$t HTTP/1.1", "").third.contains("value=\"alt\""))
        assertEquals("200 OK", server.respond("POST /$t/form HTTP/1.1", "key=neu&fremd=x").first)
        assertEquals(mapOf("key" to "neu"), server.receivedForm.value)

        val port = server.start(0)
        try {
            val m3u = "#EXTM3U\n#EXTINF:-1,Test\nhttp://x/1.ts\n"
            val conn = java.net.URL("http://127.0.0.1:$port/$t/upload?file=liste.m3u&name=Meine&epg=").openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.outputStream.use { it.write(m3u.toByteArray()) }
            assertEquals(200, conn.responseCode)
            val input = server.received.value!!
            assertTrue(input.isFile)
            assertEquals("liste.m3u", input.fileName)
            assertEquals("Meine", input.name)
            assertEquals(m3u, input.file!!.readText())
            assertNotNull(M3UParser.parse(input.file!!.readText()).channels.single())
        } finally {
            server.stop()
            dir.deleteRecursively()
        }
    }
}

class EpgRobustnessTest {
    @Test
    fun unknownEntitiesDoNotStopParsing() {
        val now = EpgParser.parseDate("20240101120000 +0000")!!
        val xml = """<?xml version="1.0"?>
            <!DOCTYPE tv SYSTEM "xmltv.dtd">
            <tv><channel id="a"><display-name>A</display-name></channel>
            <programme start="20240101110000 +0000" stop="20240101123000 +0000" channel="a"><title>Erste&nbsp;Sendung</title></programme>
            <programme start="20240101123000 +0000" stop="20240101130000 +0000" channel="a"><title>Zweite</title><desc>${"x".repeat(1000)}</desc></programme>
            </tv>"""
        val parser = org.kxml2.io.KXmlParser()
        val guide = EpgParser.parse(xml.byteInputStream(), listOf(Channel("A", "http://x/1.ts", tvgId = "a")), parser, now = now)
        val list = guide.programmes.getValue("a")
        assertEquals(2, list.size)
        assertEquals("Zweite", list[1].title)
        assertEquals(400, list[1].description!!.length)
    }
}

class DisplayRateTest {
    private val sony = listOf(50f, 59.94f, 60f, 23.976f, 24f)

    @Test
    fun choosesMatchingRefreshRate() {
        val d = app.iptv.tv.player.DisplayRate
        assertEquals(50f, d.bestRefreshRate(25f, sony))      // deutsches TV (25/50i)
        assertEquals(50f, d.bestRefreshRate(50f, sony))
        assertEquals(23.976f, d.bestRefreshRate(23.976f, sony))
        assertEquals(59.94f, d.bestRefreshRate(29.97f, sony))
        assertEquals(60f, d.bestRefreshRate(30f, sony))
        assertNull(d.bestRefreshRate(25f, listOf(60f)))       // 60-Hz-Bildschirm passt nicht zu 25 fps
        assertEquals(25f, d.snapFps(24.98f))
        assertEquals(50f, d.snapFps(1_000_000f / 20_000))
        assertNull(d.snapFps(0f))
    }
}

class FrameRateMeasureTest {
    @Test
    fun measuresDespiteDroppedFrames() {
        val d = app.iptv.tv.player.DisplayRate
        // 50-fps-Sender, bei dem viele Bilder verworfen werden: Abstände 20, 40, 60 ms gemischt
        val deltas = List(48) { i -> if (i % 3 == 0) 20_000L else if (i % 3 == 1) 40_000L else 60_000L }
        assertEquals(50f, d.snapFps(1_000_000f / d.frameIntervalUs(deltas)))
        // 25 fps ohne Verluste
        assertEquals(25f, d.snapFps(1_000_000f / d.frameIntervalUs(List(48) { 40_000L })))
    }
}

class VideoFitTest {
    private val f = app.iptv.tv.player.VideoFit

    @Test
    fun keepsOriginalAspect() {
        assertEquals(1920 to 1080, f.fit(1920, 1080, 1280, 720))          // 16:9 füllt den Bildschirm
        assertEquals(1440 to 1080, f.fit(1920, 1080, 640, 480))           // 4:3 → Ränder links/rechts
        assertEquals(1920 to 803, f.fit(1920, 1080, 1920, 803))           // Kino 2,39:1 → Ränder oben/unten
        assertEquals(1920 to 822, f.fit(1920, 1080, 3840, 1644))          // 21:9 (4K-Datei) → Ränder oben/unten
        assertEquals(1920 to 810, f.fit(1920, 1080, 2560, 1080))          // 21:9 (2560×1080)
        assertEquals(3840 to 1620, f.fit(3840, 2160, 2560, 1080))         // 21:9 auf 4K-Oberfläche
        assertEquals(1920 to 1080, f.fit(1920, 1080, 720, 576, 64, 45))   // anamorph 16:9 (PAL)
        assertEquals(1440 to 1080, f.fit(1920, 1080, 720, 576, 16, 15))   // anamorph 4:3 (PAL)
    }
}
