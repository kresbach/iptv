package app.iptv.tv.data

import app.iptv.tv.model.Channel
import app.iptv.tv.model.ContentKind
import app.iptv.tv.model.EpgGuide
import app.iptv.tv.model.Programme
import app.iptv.tv.player.DisplayRate
import app.iptv.tv.player.FrameMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothnessTest {
    @Test
    fun fernsehenNieAuf25Hz() {
        // Fernseher mit 25-Hz-Modus: Fußball (1080i, 25 fps) muss trotzdem auf 50 Hz
        val rates = listOf(24f, 25f, 50f, 60f)
        assertEquals(50f, DisplayRate.bestRefreshRate(25f, rates))
        assertEquals(50f, DisplayRate.bestRefreshRate(50f, rates))
        // Kinofilm bleibt bei 24 Hz
        assertEquals(24f, DisplayRate.bestRefreshRate(24f, rates))
        // Nur 60 Hz vorhanden → keine passende Frequenz
        assertNull(DisplayRate.bestRefreshRate(25f, listOf(60f)))
        // Nur 25 Hz vorhanden (kein 50) → 25 Hz ist besser als nichts
        assertEquals(25f, DisplayRate.bestRefreshRate(25f, listOf(25f)))
    }

    @Test
    fun bildverlusteErkennen() {
        val m = FrameMonitor()
        var t = 0L
        var shown = 0
        var lost = 0
        fun tick(dShown: Int, dLost: Int, errors: Int = 0): Boolean {
            t += 2_000; shown += dShown; lost += dLost
            return m.update(t, shown, lost, errors, playingMs = t)
        }
        tick(100, 0)                       // erste Messung
        repeat(10) { assertFalse(tick(100, 0)) } // flüssig: 50 Bilder/s
        assertEquals(50f, m.shownFps, 0.1f)
        assertEquals(0f, m.lostPercent, 0.01f)
        // überlastet: 15 % verloren, nach 4 Messungen in Folge wird gemeldet
        assertFalse(tick(85, 15)); assertFalse(tick(85, 15)); assertFalse(tick(85, 15))
        assertTrue(tick(85, 15))
        assertEquals(15f, m.lostPercent, 0.1f)
    }

    @Test
    fun streamFehlerZaehlenNichtGegenDenFernseher() {
        val m = FrameMonitor()
        var t = 0L; var shown = 0; var lost = 0; var errors = 0
        m.update(t, 0, 0, 0, 0)
        repeat(10) {
            t += 2_000; shown += 80; lost += 20; errors += 3
            assertFalse(m.update(t, shown, lost, errors, t))
        }
    }

    @Test
    fun anlaufphaseWirdIgnoriert() {
        val m = FrameMonitor()
        var t = 0L; var shown = 0; var lost = 0
        m.update(t, 0, 0, 0, 0)
        repeat(3) { t += 2_000; shown += 50; lost += 50; assertFalse(m.update(t, shown, lost, 0, t)) }
    }

    @Test
    fun epgBinaereSucheWieVorher() {
        val ch = Channel(name = "Sport", url = "http://x/1.ts", kind = ContentKind.LIVE)
        val list = (0 until 500).map { Programme("S$it", start = it * 1000L, end = it * 1000L + 900) }
        val guide = EpgGuide(mapOf("sport" to list))
        assertEquals("S0", guide.current(ch, 0)?.title)
        assertEquals("S10", guide.current(ch, 10_500)?.title)
        assertNull(guide.current(ch, 10_950))          // Lücke zwischen zwei Sendungen
        assertEquals("S11", guide.next(ch, 10_950)?.title)
        assertEquals("S11", guide.next(ch, 10_500)?.title)
        assertNull(guide.current(ch, -5))
        assertEquals("S0", guide.next(ch, -5)?.title)
        assertNull(guide.next(ch, 499_100))
        assertNull(guide.current(ch, 600_000))
    }
}

class DetailExtrasTest {
    @Test
    fun altersfreigabe() {
        assertEquals(12, app.iptv.tv.model.MediaInfo.parseAge("12+"))
        assertEquals(16, app.iptv.tv.model.MediaInfo.parseAge("FSK 16"))
        assertEquals(12, app.iptv.tv.model.MediaInfo.parseAge("PG-13"))
        assertEquals(16, app.iptv.tv.model.MediaInfo.parseAge("TV-MA"))
        assertNull(app.iptv.tv.model.MediaInfo.parseAge(""))
        assertNull(app.iptv.tv.model.MediaInfo.parseAge("99"))
    }

    @Test
    fun tmdbErgaenzungen() {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"backdrop_path":"/b.jpg","overview":"Text","release_dates":{"results":[{"iso_3166_1":"US","release_dates":[{"certification":"R"}]},
            {"iso_3166_1":"DE","release_dates":[{"certification":""},{"certification":"12"}]}]},
            "credits":{"cast":[{"name":"A"},{"name":"B"}]}}""",
        ) as kotlinx.serialization.json.JsonObject
        val e = Similar.parseExtras(o, tv = false)
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", e.backdrop)
        assertEquals(12, e.age)
        assertEquals("A, B", e.cast)
        val merged = Similar.merge(app.iptv.tv.model.MediaInfo(plot = "Anbieter"), e)!!
        assertEquals("Anbieter", merged.plot) // Anbieter-Angaben haben Vorrang
        assertEquals(12, merged.age)
    }

    @Test
    fun sportErkennung() {
        val sky = Channel(name = "Sky Sport 1 HD", url = "u", group = "Sport", kind = ContentKind.LIVE)
        val ard = Channel(name = "Das Erste", url = "u2", group = "Vollprogramm", kind = ContentKind.LIVE)
        fun p(t: String) = Programme(t, start = 0, end = 1)
        assertTrue(Sport.isSport(ard, p("Fußball: Länderspiel Deutschland - Frankreich")))
        assertTrue(Sport.isSport(sky, p("FC Bayern München - Borussia Dortmund")))
        assertFalse(Sport.isSport(ard, p("Tatort - Der Fall")))     // Bindestrich allein reicht nicht
        assertFalse(Sport.isSport(sky, p("Sky Sport News")))
        assertFalse(Sport.isSport(ard, p("Bundesliga Magazin")))
        // nur Fußball
        assertFalse(Sport.isSport(sky, p("Handball-Bundesliga: THW Kiel - SG Flensburg")))
        assertFalse(Sport.isSport(sky, p("Basketball Champions League: Bonn - Tenerife")))
        assertFalse(Sport.isSport(sky, p("Darts: Premier League")))
        assertFalse(Sport.isSport(sky, p("Eishockey: Adler Mannheim - Eisbären Berlin")))
        assertFalse(Sport.isSport(sky, p("Golf - The Open")))
        assertTrue(Sport.isSport(ard, p("UEFA Champions League: Bayern - Real")))
        assertTrue(Sport.isSport(sky, p("Real Madrid vs. FC Barcelona")))
        assertTrue(Sport.isSport(ard, p("2. Bundesliga: Hamburger SV - Schalke 04")))
    }
}

class SportReihenfolgeTest {
    @Test
    fun bundesligaUndChampionsLeagueZuerst() {
        val now = 1_000_000_000L
        val a = Channel(name = "Sky Sport 1", url = "a", tvgId = "a", kind = ContentKind.LIVE)
        val b = Channel(name = "DAZN 1", url = "b", tvgId = "b", kind = ContentKind.LIVE)
        val c = Channel(name = "Sky Sport 2", url = "c", tvgId = "c", kind = ContentKind.LIVE)
        val guide = EpgGuide(mapOf(
            "a" to listOf(Programme("Premier League: Arsenal - Chelsea", start = now - 60_000, end = now + 5_000_000)),
            "b" to listOf(Programme("Champions League: Inter - PSG", start = now - 30_000, end = now + 5_000_000)),
            "c" to listOf(Programme("Bundesliga: Mainz - Freiburg", start = now + 3_600_000, end = now + 9_000_000)),
        ))
        val ev = Sport.events(guide, listOf(a, b, c), emptySet(), now)
        assertEquals(listOf("Champions League: Inter - PSG", "Premier League: Arsenal - Chelsea", "Bundesliga: Mainz - Freiburg"), ev.map { it.programme.title })
    }
}

class LiveRobustTest {
    @Test
    fun ersatzsenderFinden() {
        fun ch(n: String, cat: String = "DE Sport") = Channel(name = n, url = "u/$n", group = cat, kind = ContentKind.LIVE)
        val orig = ch("Sky Sport 1 HD")
        val all = listOf(
            orig, ch("DE: Sky Sport 1 FHD"), ch("Sky Sport 1 (Backup)"), ch("Sky Sport 1 SD", "DE Sport SD"),
            ch("Sky Sport 10 HD"), ch("Sky Sport Bundesliga 1 HD"), ch("Sky Sport 1 UHD 4K"),
        )
        assertEquals("sky sport 1", Alternatives.baseName("DE: Sky Sport 1 FHD (Backup)"))
        val alt = Alternatives.find(orig, all).map { it.name }
        assertEquals(listOf("DE: Sky Sport 1 FHD", "Sky Sport 1 UHD 4K", "Sky Sport 1 (Backup)", "Sky Sport 1 SD"), alt)
        assertTrue(Alternatives.find(ch("Sky Sport Bundesliga 1 HD"), all).isEmpty())
    }

    @Test
    fun eingefrorenesBildErkennen() {
        val m = FrameMonitor()
        var t = 0L; var shown = 0
        m.update(t, 0, 0, 0, 0)
        repeat(3) { t += 2_000; shown += 100; m.update(t, shown, 0, 0, t) }
        assertTrue(m.counting)
        repeat(3) { t += 2_000; m.update(t, shown, 0, 0, t) } // keine neuen Bilder
        assertEquals(6_000L, m.frozenMs)
        t += 2_000; shown += 100; m.update(t, shown, 0, 0, t)
        assertEquals(0L, m.frozenMs)
    }

    @Test
    fun zaehlerOhneBilderKeinFehlalarm() {
        // Manche Dekoder zählen keine gezeigten Bilder: dann nie „eingefroren“ melden
        val m = FrameMonitor()
        var t = 0L
        m.update(t, 0, 0, 0, 0)
        repeat(10) { t += 2_000; m.update(t, 0, 0, 0, t) }
        assertFalse(m.counting)
        assertEquals(0L, m.frozenMs)
    }

    @Test
    fun gestoerterStream() {
        val m = FrameMonitor()
        var t = 0L; var errors = 0; var shown = 0
        m.update(t, 0, 0, 0, 0)
        repeat(10) { t += 2_000; errors += 5; shown += 90; m.update(t, shown, 0, errors, t) }
        assertEquals(20_000L, m.troubledMs)
        t += 2_000; shown += 90; m.update(t, shown, 0, errors, t)
        assertEquals(0L, m.troubledMs)
    }
}
