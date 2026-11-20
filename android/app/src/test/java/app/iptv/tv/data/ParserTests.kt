package app.iptv.tv.data

import app.iptv.tv.model.Channel
import app.iptv.tv.model.ContentKind
import app.iptv.tv.model.SeriesShow
import app.iptv.tv.model.XtreamCredentials
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class M3UParserTest {
    private val sample = """
        ﻿#EXTM3U url-tvg="http://epg.example.com/guide.xml.gz,http://other"
        #EXTINF:-1 tvg-id="ard.de" tvg-logo="http://logo/ard.png" group-title="Nachrichten, DE",Das Erste HD
        http://example.com/live/user/pass/1.ts
        #EXTINF:-1 group-title="Filme",Matrix (1999)
        http://example.com/movie/user/pass/55.mkv
        #EXTINF:-1 group-title="Serien",Dark S01 E03
        http://example.com/series/user/pass/77.mp4
        #EXTINF:-1,
        #EXTGRP:Sport
        http://example.com/stream/sport1.m3u8
        file:///etc/passwd
    """.trimIndent()

    @Test
    fun parsesChannelsAttributesAndEpg() {
        val result = M3UParser.parse(sample)
        assertEquals("http://epg.example.com/guide.xml.gz", result.epgUrl)
        assertEquals(4, result.channels.size)
        val ard = result.channels[0]
        assertEquals("Das Erste HD", ard.name)
        assertEquals("Nachrichten, DE", ard.group)
        assertEquals("ard.de", ard.tvgId)
        assertEquals("http://logo/ard.png", ard.logo)
        assertEquals(ContentKind.LIVE, ard.kind)
        assertEquals(ContentKind.MOVIE, result.channels[1].kind)
        assertEquals(ContentKind.SERIES, result.channels[2].kind)
        val sport = result.channels[3]
        assertEquals("sport1", sport.name)
        assertEquals("Sport", sport.group)
    }

    @Test
    fun reportsWebPage() {
        try {
            M3UParser.parse("<html><body>Login</body></html>")
            fail()
        } catch (e: UserFacingException) {
            assertTrue(e.message!!.contains("Webseite"))
        }
    }

    @Test
    fun reportsServerMessage() {
        try {
            M3UParser.parse("Account expired")
            fail()
        } catch (e: UserFacingException) {
            assertTrue(e.message!!.contains("Account expired"))
        }
    }
}

class ClassifierTest {
    @Test
    fun episodeParser() {
        val info = EpisodeParser.parse("Dark - s1e3")!!
        assertEquals("Dark", info.showTitle)
        assertEquals(1, info.season)
        assertEquals(3, info.episode)
        assertEquals(12, EpisodeParser.parse("The Show S02 E12")!!.episode)
        assertNull(EpisodeParser.parse("Sky Sport 1 HD"))
        assertNull(EpisodeParser.parse("Movies 2020"))
    }

    @Test
    fun classifies() {
        assertEquals(ContentKind.LIVE, ContentClassifier.kind("http://x/live/a/b/1.ts", "Serien HD", "RTL"))
        assertEquals(ContentKind.MOVIE, ContentClassifier.kind("http://x/a.mp4", "Kino", "Film"))
        assertEquals(ContentKind.SERIES, ContentClassifier.kind("http://x/a.mkv", "Serien", "Lost"))
        assertEquals(ContentKind.MOVIE, ContentClassifier.kind("http://x/stream", "VOD Action", "Film"))
        assertEquals(ContentKind.SERIES, ContentClassifier.kind("http://x/movie/1.mp4", null, "Lost S01E01"))
    }

    @Test
    fun seriesFromEpisodes() {
        val items = listOf(
            Channel("Lost S01 E02", "http://x/2.mp4", "Serien", kind = ContentKind.SERIES),
            Channel("Lost S01 E01", "http://x/1.mp4", "Serien", kind = ContentKind.SERIES, logo = "p"),
            Channel("Dark S01 E01", "http://x/3.mp4", "Serien", kind = ContentKind.SERIES),
        )
        val shows = SeriesShow.fromEpisodes(items)
        assertEquals(listOf("Lost", "Dark"), shows.map { it.title })
        assertEquals(listOf(1, 2), shows[0].episodes.map { it.number })
        assertEquals("p", shows[0].poster)
    }
}

class UrlToolsTest {
    @Test
    fun userInput() {
        assertEquals("http://server.de:8080/get.php?a=1&b=2", UrlTools.userInput("  server.de:8080/get.php?a=1&amp;b=2 \n"))
        assertNull(UrlTools.userInput("ftp://x"))
        assertNull(UrlTools.userInput(""))
    }

    @Test
    fun detectsXtreamGetPhp() {
        val c = UrlTools.xtreamCredentials("http://srv.tv:8080/get.php?username=max&password=p%40ss&type=m3u_plus&output=ts")
        assertEquals(XtreamCredentials("http://srv.tv:8080", "max", "p@ss"), c)
        assertNull(UrlTools.xtreamCredentials("http://srv.tv/list.m3u"))
        assertNull(UrlTools.xtreamCredentials("http://srv.tv/get.php?username=max"))
    }

    @Test
    fun normalizesServer() {
        assertEquals("http://host:8080", UrlTools.normalizedServer("host:8080/player_api.php"))
        assertEquals("https://host", UrlTools.normalizedServer("https://host/"))
    }

    @Test
    fun liveVariants() {
        assertEquals("http://h/live/u/p/1.ts", UrlTools.liveTsVariant("http://h/live/u/p/1.m3u8"))
        assertEquals("http://h/live/u/p/1.m3u8", UrlTools.liveHlsVariant("http://h/live/u/p/1.ts"))
        assertNull(UrlTools.liveHlsVariant("http://h/movie/u/p/1.ts"))
        assertEquals(1234, UrlTools.streamId("http://h/movie/u/p/1234.mkv"))
    }
}

class XtreamClientTest {
    private val client = XtreamClient(XtreamCredentials("http://srv:80", "user", "pa ss"))
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject
    private fun list(s: String) = client.parseList(s)

    @Test
    fun buildsContentPreferringTs() {
        val data = client.buildContent(
            account = obj("""{"user_info":{"auth":1,"status":"Active","allowed_output_formats":["m3u8","ts"]}}"""),
            liveCategories = list("""[{"category_id":"1","category_name":"News"}]"""),
            vodCategories = list("""[{"category_id":2,"category_name":"Action"}]"""),
            seriesCategories = list("null"),
            liveStreams = list("""[{"name":"ARD","stream_id":"11","epg_channel_id":"ard.de","category_id":"1","stream_icon":""}]"""),
            vodStreams = list("""[{"name":"Matrix","stream_id":22,"category_id":"2","container_extension":"mkv","added":"1700000000"}]"""),
            series = list("""[{"name":"Dark","series_id":"33","cover":"http://c","category_id":"9"}]"""),
        )
        val live = data.channels[0]
        assertEquals("http://srv:80/live/user/pa%20ss/11.ts", live.url)
        assertEquals("News", live.group)
        assertEquals("ard.de", live.tvgId)
        assertNull(live.logo)
        val movie = data.channels[1]
        assertEquals("http://srv:80/movie/user/pa%20ss/22.mkv", movie.url)
        assertEquals(ContentKind.MOVIE, movie.kind)
        assertEquals(1700000000L, movie.added)
        assertEquals(33, data.shows[0].xtreamSeriesId)
        assertEquals(Channel.UNGROUPED, data.shows[0].category)
    }

    @Test
    fun usesHlsWhenTsNotAllowed() {
        val data = client.buildContent(
            obj("""{"user_info":{"auth":"1","status":"Active","allowed_output_formats":["m3u8"]}}"""),
            emptyList(), emptyList(), emptyList(),
            list("""[{"name":"ARD","stream_id":"11"}]"""), emptyList(), emptyList(),
        )
        assertTrue(data.channels[0].url.endsWith("/11.m3u8"))
    }

    @Test
    fun rejectsInvalidLogin() {
        try {
            client.buildContent(obj("""{"user_info":{"auth":0}}"""), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
            fail()
        } catch (e: UserFacingException) {
            assertTrue(e.message!!.contains("Anmeldung"))
        }
    }

    @Test
    fun parsesSeriesInfo() {
        val show = SeriesShow("Dark", "Drama", poster = "p", xtreamSeriesId = 1)
        val (info, episodes) = client.parseSeriesInfo(
            """{"info":{"plot":"Zeitreise","rating":"8,5","releaseDate":"2017-12-01","backdrop_path":["http://b"],"episode_run_time":"55"},
               "episodes":{"2":[{"id":"902","episode_num":1,"title":"Anfang","container_extension":"mkv","info":{"plot":"x"}}],
                           "1":[{"id":"901","episode_num":"2","title":"Dark S01 E02","season":1},{"id":"900","episode_num":"1","season":"1"}]}}""",
            show,
        )
        assertEquals("Zeitreise", info!!.plot)
        assertEquals(8.5, info.rating!!, 0.001)
        assertEquals("2017", info.year)
        assertEquals("http://b", info.backdrop)
        assertEquals(55, info.durationMinutes)
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), episodes.map { it.season to it.number })
        assertEquals("http://srv:80/series/user/pa%20ss/902.mkv", episodes[2].channel.url)
        assertEquals("Episode 2", episodes[1].displayTitle)
        assertEquals("1. Anfang", episodes[2].displayTitle)
    }

    @Test
    fun emptyEpisodesAsArray() {
        val (_, episodes) = client.parseSeriesInfo("""{"info":[],"episodes":[]}""", SeriesShow("X", "Y", xtreamSeriesId = 1))
        assertTrue(episodes.isEmpty())
    }

    @Test
    fun minutes() {
        assertEquals(112, XtreamClient.minutes("01:52:03"))
        assertEquals(98, XtreamClient.minutes("98 min"))
        assertNull(XtreamClient.minutes(""))
    }
}

class EpgParserTest {
    private val now = EpgParser.parseDate("20240101120000 +0000")!!
    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="ARD.de"><display-name>Das Erste</display-name></channel>
          <channel id="zdf.de"><display-name>ZDF HD</display-name></channel>
          <programme start="20240101110000 +0000" stop="20240101123000 +0000" channel="ard.de">
            <title lang="de">Tagesschau &amp; Wetter</title><desc>Nachrichten</desc>
          </programme>
          <programme start="20240101123000 +0000" stop="20240101130000 +0000" channel="ard.de"><title>Sport</title></programme>
          <programme start="20240101123000 +0000" stop="20240101130000 +0000" channel="ard.de"><title>Doppelt</title></programme>
          <programme start="20240105120000 +0000" stop="20240105130000 +0000" channel="ard.de"><title>Zu spät</title></programme>
          <programme start="20240101110000 +0000" stop="20240101130000 +0000" channel="zdf.de"><title>Heute</title></programme>
          <programme start="20240101110000 +0000" stop="20240101130000 +0000" channel="rtl.de"><title>Fremd</title></programme>
        </tv>"""

    private val channels = listOf(
        Channel("Das Erste HD", "http://x/1.ts", tvgId = "ard.de"),
        Channel("ZDF HD", "http://x/2.ts"),
    )

    @Test
    fun parsesPlainXml() = check(xml.byteInputStream())

    @Test
    fun parsesGzip() {
        val bytes = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(xml.toByteArray()) } }.toByteArray()
        check(bytes.inputStream())
    }

    private fun check(input: java.io.InputStream) {
        val guide = EpgParser.parse(input, channels, KXmlParser(), now = now)
        val ard = guide.schedule(channels[0])
        assertEquals(listOf("Tagesschau & Wetter", "Sport"), ard.map { it.title })
        assertEquals("Nachrichten", ard[0].description)
        assertEquals("Tagesschau & Wetter", guide.current(channels[0], now)?.title)
        assertEquals("Sport", guide.next(channels[0], now)?.title)
        // ZDF ohne tvg-id über den Anzeigenamen gefunden
        assertEquals("Heute", guide.current(channels[1], now)?.title)
        assertNull(guide.programmes["rtl.de"])
    }

    @Test
    fun dates() {
        assertEquals(EpgParser.parseDate("20240101130000 +0100"), EpgParser.parseDate("20240101120000"))
        assertNotNull(EpgParser.parseDate("20240101120000 +0000"))
        assertNull(EpgParser.parseDate("2024"))
    }
}

class LibraryContentTest {
    @Test
    fun groupsAndOrders() {
        val data = app.iptv.tv.model.PlaylistData(
            listOf(
                Channel("A", "http://x/1.ts"),
                Channel("B", "http://x/2.ts", "News"),
                Channel("M", "http://x/3.mp4", "Kino", kind = ContentKind.MOVIE),
                Channel("Lost S01 E01", "http://x/4.mp4", "Serien", kind = ContentKind.SERIES),
            ),
        )
        val c = LibraryContent(data)
        assertEquals(listOf("News", Channel.UNGROUPED), c.liveCategories.map { it.name })
        assertEquals(1, c.movies.size)
        assertEquals("Lost", c.shows.single().title)
        assertEquals("B", c.channel("live|News|B")?.name)
        assertNotNull(c.show("series|Serien|Lost"))
    }
}
