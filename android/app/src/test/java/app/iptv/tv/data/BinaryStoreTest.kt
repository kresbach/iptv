package app.iptv.tv.data

import app.iptv.tv.model.Channel
import app.iptv.tv.model.ContentKind
import app.iptv.tv.model.EpgGuide
import app.iptv.tv.model.Episode
import app.iptv.tv.model.PlaylistData
import app.iptv.tv.model.Programme
import app.iptv.tv.model.SeriesShow
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BinaryStoreTest {
    private fun sample(): PlaylistData {
        val channels = listOf(
            Channel("Das Erste HD", "http://s.example:8080/live/u/p/1.ts", "DE | Vollprogramm", "daserste.de", "http://logo.example/a.png", ContentKind.LIVE, null),
            Channel("Ümlaut 🎬 Film", "http://s.example:8080/movie/u/p/2.mkv", "Filme", null, null, ContentKind.MOVIE, 1_700_000_000),
            Channel("Ohne Gruppe", "rtmp://x", null, null, null, ContentKind.LIVE, null),
        )
        val ep = Episode(Channel("S01E01", "http://s.example:8080/series/u/p/9.mp4", "Serien", kind = ContentKind.SERIES), 1, 1, "Pilot", "Inhalt")
        val ep2 = Episode(Channel("Extra", "http://s.example:8080/series/u/p/10.mp4", null, kind = ContentKind.SERIES), null, null, null, null)
        val shows = listOf(
            SeriesShow("Serie A", "Serien", "http://img/x.jpg", "Beschreibung", listOf(ep, ep2), 42, 123L),
            SeriesShow("Serie B", "Serien"),
        )
        return PlaylistData(channels, shows)
    }

    @Test
    fun playlistRoundtrip() {
        val data = sample()
        val out = ByteArrayOutputStream()
        BinaryStore.writeData(data, out)
        assertEquals(data, BinaryStore.readData(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun epgRoundtripKeepsSharedLists() {
        val list = listOf(Programme("Tagesschau", "Nachrichten", 1000, 2000), Programme("Wetter", null, 2000, 2100))
        val guide = EpgGuide(mapOf("daserste.de" to list, "das erste" to list, "zdf" to emptyList()), 99)
        val out = ByteArrayOutputStream()
        BinaryStore.writeEpg(guide, out)
        val back = BinaryStore.readEpg(ByteArrayInputStream(out.toByteArray()))
        assertEquals(guide, back)
        assertSame(back.programmes["daserste.de"], back.programmes["das erste"])
    }

    @Test(expected = java.io.IOException::class)
    fun rejectsForeignFile() {
        BinaryStore.readData(ByteArrayInputStream("{\"channels\":[]}".toByteArray()))
    }

    /** Große Playlist (wie bei Xtream-Anbietern): Binär muss deutlich schneller laden als JSON. */
    @Test
    fun binaryIsFasterThanJson() {
        val channels = (0 until 120_000).map { i ->
            val kind = if (i % 3 == 0) ContentKind.LIVE else ContentKind.MOVIE
            Channel("Sender Nummer $i HD", "http://server.example:8080/${kind.name.lowercase()}/user/pass/$i.ts", "Kategorie ${i % 200}", "id$i", "http://logos.example/img/$i.png", kind, 1_700_000_000L + i)
        }
        val data = PlaylistData(channels)
        val json = Json { ignoreUnknownKeys = true }
        val jsonText = json.encodeToString(PlaylistData.serializer(), data)
        val bin = ByteArrayOutputStream().also { BinaryStore.writeData(data, it) }.toByteArray()

        fun time(block: () -> Unit): Long { val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1_000_000 }
        // Erster Durchlauf = wie beim App-Start (kalt), danach bester von 3.
        val binCold = time { BinaryStore.readData(ByteArrayInputStream(bin)) }
        val jsonCold = time { json.decodeFromString(PlaylistData.serializer(), jsonText) }
        val jsonMs = (0 until 3).minOf { time { json.decodeFromString(PlaylistData.serializer(), jsonText) } }
        val binMs = (0 until 3).minOf { time { BinaryStore.readData(ByteArrayInputStream(bin)) } }
        println("Laden 120.000 Einträge: JSON kalt $jsonCold ms / warm $jsonMs ms (${jsonText.length / 1024} KB), Binär kalt $binCold ms / warm $binMs ms (${bin.size / 1024} KB)")
        assertTrue("Binär ($binMs ms) nicht schneller als JSON ($jsonMs ms)", binMs < jsonMs)
        assertTrue(bin.size < jsonText.length)
    }
}
