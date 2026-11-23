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
