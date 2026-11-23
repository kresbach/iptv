package app.iptv.tv.data

import app.iptv.tv.model.Channel
import app.iptv.tv.model.ContentKind
import app.iptv.tv.model.EpgGuide
import app.iptv.tv.model.PlaylistData
import app.iptv.tv.model.Programme
import app.iptv.tv.model.XtreamCredentials
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class CatchupTest {
    private val now = 1_763_900_000_000L // 23.11.2025 ~ 12:13 UTC
    private val ard = Channel("ARD", "http://srv:8080/live/user/pass/11.ts", "News", archiveDays = 3)

    @Test
    fun buildsTimeshiftLinkInServerTimeZone() {
        // 20:15–21:45 Berliner Zeit (UTC+1)
        val start = java.time.ZonedDateTime.of(2025, 11, 22, 20, 15, 0, 0, java.time.ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()
        val p = Programme("Tatort", null, start, start + 90 * 60_000L)
        assertEquals("http://srv:8080/timeshift/user/pass/90/2025-11-22:20-15/11.ts", Catchup.url(ard, p, "Europe/Berlin"))
        assertEquals("http://srv:8080/timeshift/user/pass/90/2025-11-22:19-15/11.ts", Catchup.url(ard, p, "UTC"))
        // Links ohne /live/ (M3U von Xtream) funktionieren auch
        assertEquals("http://srv:8080/timeshift/u/p/90/2025-11-22:19-15/5.ts", Catchup.url(Channel("X", "http://srv:8080/u/p/5", archiveDays = 1), p, "UTC"))
    }

    @Test
    fun availabilityRespectsArchiveWindow() {
        val p = { hoursAgo: Int -> Programme("S", null, now - hoursAgo * 3600_000L, now - hoursAgo * 3600_000L + 3600_000L) }
        assertTrue(Catchup.available(ard, p(5), now))
        assertTrue(Catchup.available(ard, p(70), now))
        assertFalse(Catchup.available(ard, p(80), now)) // älter als 3 Tage
        assertFalse(Catchup.available(ard, Programme("Zukunft", null, now + 60_000, now + 3600_000), now))
        assertFalse(Catchup.available(ard.copy(archiveDays = null), p(5), now))
        assertFalse(Catchup.available(Channel("HLS", "http://cdn/stream.m3u8", archiveDays = 3), p(5), now))
        val guide = EpgGuide(mapOf("ard" to listOf(p(2), p(1), p(80))))
        assertEquals(listOf(p(1), p(2)), Catchup.pastProgrammes(ard, guide, now))
    }

    @Test
    fun readsArchiveFromXtreamAndM3u() {
        val client = XtreamClient(XtreamCredentials("http://srv:80", "user", "pass"))
        val data = client.buildContent(
            account = Json.parseToJsonElement("""{"user_info":{"auth":1,"status":"Active"},"server_info":{"timezone":"Europe/Berlin"}}""") as JsonObject,
            liveCategories = emptyList(), vodCategories = emptyList(), seriesCategories = emptyList(),
            liveStreams = client.parseList("""[{"name":"ARD","stream_id":1,"tv_archive":1,"tv_archive_duration":"5"},{"name":"ZDF","stream_id":2,"tv_archive":0}]"""),
            vodStreams = emptyList(), series = emptyList(),
        )
        assertEquals("Europe/Berlin", data.serverTimeZone)
        assertEquals(5, data.channels[0].archiveDays)
        assertNull(data.channels[1].archiveDays)
        val m3u = M3UParser.parse("#EXTM3U\n#EXTINF:-1 catchup=\"xc\" catchup-days=\"7\",ARD\nhttp://s/live/u/p/1.ts\n#EXTINF:-1,ZDF\nhttp://s/live/u/p/2.ts")
        assertEquals(7, m3u.channels[0].archiveDays)
        assertNull(m3u.channels[1].archiveDays)
    }

    @Test
    fun binaryStoreKeepsArchiveAndTimeZone() {
        val data = PlaylistData(listOf(ard, ard.copy(name = "ZDF", archiveDays = null)), emptyList(), "Europe/Berlin")
        val out = ByteArrayOutputStream().also { BinaryStore.writeData(data, it) }
        assertEquals(data, BinaryStore.readData(ByteArrayInputStream(out.toByteArray())))
    }
}

class SleepTimerTest {
    @Test
    fun cyclesAndExtends() {
        val now = 1_000_000L
        SleepTimer.endsAt.value = null
        assertEquals("Aus", SleepTimer.label(now))
        SleepTimer.cycle(now); assertEquals(15, SleepTimer.minutesLeft(now))
        SleepTimer.cycle(now); assertEquals(30, SleepTimer.minutesLeft(now))
        repeat(3) { SleepTimer.cycle(now) }; assertEquals(120, SleepTimer.minutesLeft(now))
        SleepTimer.cycle(now); assertNull(SleepTimer.endsAt.value)
        SleepTimer.cycle(now); SleepTimer.extend(30, now); assertEquals(45, SleepTimer.minutesLeft(now))
        SleepTimer.endsAt.value = null
    }
}

class LanguagesTest {
    @Test
    fun vlcCodes() {
        assertEquals("de,deu,ger", app.iptv.tv.player.Languages.vlcCodes("de"))
        assertEquals("en,eng", app.iptv.tv.player.Languages.vlcCodes("en"))
        assertNull(app.iptv.tv.player.Languages.vlcCodes(""))
        assertEquals("Deutsch", app.iptv.tv.player.Languages.name("de"))
    }
}

class UpdateInfoTest {
    @Test
    fun parsesVersionFile() {
        val text = java.io.File("../release/version.json").readText()
        val info = Json { ignoreUnknownKeys = true }.decodeFromString(AppUpdate.Info.serializer(), text)
        // version.json muss zur gebauten App passen, sonst bietet sie sich selbst als Update an
        assertEquals(app.iptv.tv.BuildConfig.VERSION_CODE, info.versionCode)
        assertEquals(app.iptv.tv.BuildConfig.VERSION_NAME, info.versionName)
        assertTrue(info.versionName.isNotEmpty())
    }
}
