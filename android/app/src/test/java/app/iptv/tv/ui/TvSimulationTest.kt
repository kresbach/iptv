package app.iptv.tv.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import app.iptv.tv.data.LibraryContent
import app.iptv.tv.data.Prefs
import app.iptv.tv.data.Progress
import app.iptv.tv.data.Repository
import app.iptv.tv.model.Channel
import app.iptv.tv.model.ContentKind
import app.iptv.tv.model.EpgGuide
import app.iptv.tv.model.Playlist
import app.iptv.tv.model.PlaylistData
import app.iptv.tv.model.PlaylistSource
import app.iptv.tv.model.Programme
import app.iptv.tv.model.SeriesShow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Simuliert die App auf einem Fernseher (1920×1080, Fernbedienung) mit Robolectric:
 * Bildschirme werden aufgerufen, Tasten gedrückt und Bildschirmfotos gespeichert.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@OptIn(ExperimentalTestApi::class)
class TvSimulationTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val outDir = File("build/tv-screens").apply { mkdirs() }

    private fun shot(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Before
    fun setUp() {
        Repository.attachStorage(ApplicationProvider.getApplicationContext())
        // Zustand aus anderen Tests zurücksetzen (Objekte leben im selben Prozess weiter).
        app.iptv.tv.data.Downloads.items.value = emptyList()
        app.iptv.tv.data.CategoryPrefs.setPin(null)
        val now = System.currentTimeMillis()
        val live = (1..30).map { Channel(if (it % 10 == 0) "Sky Sport 1" else "Sender $it HD", "http://x/live/u/p/$it.ts", if (it <= 15) "Nachrichten" else "Sport", tvgId = "s$it") }
        val movies = (1..40).map { Channel("Film Nummer $it", "http://x/movie/u/p/$it.mkv", if (it % 2 == 0) "Action" else if (it % 5 == 0) "NETFLIX Filme" else "Drama", kind = ContentKind.MOVIE, added = it.toLong()) } +
            Channel("Film Nummer 1", "http://x/movie/u/p/999.mkv", "Drama", kind = ContentKind.MOVIE)
        val episodes = (1..6).map { Channel("Dark S01 E0$it", "http://x/series/u/p/$it.mp4", "Serien", kind = ContentKind.SERIES) }
        val shows = (1..12).map { SeriesShow("Serie $it", "Drama", xtreamSeriesId = it) }
        val content = LibraryContent(PlaylistData(live + movies + episodes, shows))
        Repository.playlists.value = listOf(Playlist(id = "p1", name = "Test-Playlist", source = PlaylistSource.M3u("http://x/list.m3u")))
        Repository.activeId.value = "p1"
        Repository.content.value = content
        Repository.visible.value = content
        Repository.restored.value = true
        app.iptv.tv.data.Trends.rows.value = app.iptv.tv.data.Trends.build(content, app.iptv.tv.data.Trends.Cache())
        Repository.epg.value = EpgGuide(
            live.associate { c ->
                c.tvgId!! to (0..6).map { i ->
                    val start = now - 3600_000L + i * 45 * 60_000L
                    Programme("Sendung $i auf ${c.name}", "Beschreibung", start, start + 45 * 60_000L)
                }
            },
            now,
        )
        Repository.favorites.value = app.iptv.tv.data.OrderedKeys(listOf(live[1].stableKey, movies[2].stableKey))
        app.iptv.tv.data.Prefs.liveStartFavorites = false
        Repository.progress.value = mapOf(movies[4].stableKey to Progress(1_200_000, 5_400_000, now, movies[4]))
    }

    private fun start() {
        listOf(Screen.Home, Screen.Search, Screen.Settings, Screen.EpgGrid, Screen.Playlists, Screen.Downloads).forEach {
            it.memory.focusedKey = null
            it.memory.scroll.clear()
            it.memory.selection = null
        }
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme { AppRoot(onExit = {}) } }
        settle()
    }

    private fun settle(ms: Long = 700) {
        repeat((ms / 100).toInt()) {
            rule.mainClock.advanceTimeBy(100)
            org.robolectric.shadows.ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        rule.waitForIdle()
    }

    private fun key(k: androidx.compose.ui.input.key.Key, times: Int = 1) = repeat(times) {
        rule.onRoot().performKeyInput { pressKey(k) }
        settle(300)
    }

    private fun ok() = key(androidx.compose.ui.input.key.Key.DirectionCenter)
    private fun right(n: Int = 1) = key(androidx.compose.ui.input.key.Key.DirectionRight, n)
    private fun left(n: Int = 1) = key(androidx.compose.ui.input.key.Key.DirectionLeft, n)
    private fun down(n: Int = 1) = key(androidx.compose.ui.input.key.Key.DirectionDown, n)
    private fun up(n: Int = 1) = key(androidx.compose.ui.input.key.Key.DirectionUp, n)
    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
    }
    /** OK lange drücken, wie auf der Fernbedienung (Taste mit automatischer Wiederholung). */
    private fun longOk() {
        val c = android.view.KeyEvent.KEYCODE_DPAD_CENTER
        rule.runOnUiThread {
            val w = rule.activity.window
            w.superDispatchKeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, c, 0))
            // Wie Android bei echtem langem Drücken: Wiederholung mit FLAG_LONG_PRESS
            w.superDispatchKeyEvent(android.view.KeyEvent(0, 500, android.view.KeyEvent.ACTION_DOWN, c, 1, 0,
                android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, android.view.KeyEvent.FLAG_LONG_PRESS))
            w.superDispatchKeyEvent(android.view.KeyEvent(0, 550, android.view.KeyEvent.ACTION_DOWN, c, 2))
        }
        settle(300)
        rule.runOnUiThread { rule.activity.window.superDispatchKeyEvent(android.view.KeyEvent(0, 900, android.view.KeyEvent.ACTION_UP, c, 0)) }
        settle(500)
    }

    private fun see(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun expect(text: String) = check(see(text)) { "Erwartet auf dem Bildschirm: „$text“" }

    @Test
    fun startseite() {
        // „Zuletzt gesehen“ nur mit Live-Sendern, Filme stehen unter „Weiterschauen“
        Repository.recents.value = listOf(
            app.iptv.tv.data.RecentEntry(Repository.content.value.live[2], 2),
            app.iptv.tv.data.RecentEntry(Repository.content.value.movies[4], 1),
        )
        start()
        shot("01-startseite")
        down(4) // Weiterschauen → Zuletzt gesehen → Favoriten → erste Trend-Reihe
        expect("Trends")
        expect("Netflix")
        check(rule.onAllNodesWithText("Filme – neu hinzugefügt").fetchSemanticsNodes().isEmpty())
        shot("02-startseite-trends")
    }

    @Test
    fun liveTvUndPlayer() {
        start()
        ok() // Live TV (fokussiert)
        expect("Alle Sender")
        shot("10-live")
        right()
        down(2)
        shot("11-live-sender-fokus")
        ok() // Sender abspielen
        shot("12-player-live")
        up() // Sender wechseln
        settle(1000)
        shot("13-player-zappen")
        left() // Senderliste
        shot("14-player-senderliste")
        back()
        longOk() // Optionen per langem OK
        expect("Optionen")
        check(!see("Keine Tonspuren")) { "Langes OK hat ungewollt einen Menüpunkt ausgelöst" }
        shot("14b-player-optionen-langes-ok")
        back()
        back() // Player schließen
        expect("Alle Sender")
        shot("15-live-zurueck")
        back()
        expect("Weiterschauen")
    }

    @Test
    fun favoritenReihenfolgeUndSendernummer() {
        val live = Repository.content.value.live
        Repository.favorites.value = app.iptv.tv.data.OrderedKeys(listOf(live[5].stableKey, live[1].stableKey, live[20].stableKey))
        Repository.moveFavoriteToTop(live[20].stableKey)
        check(liveChannels(Repository.content.value, FAVORITES, Repository.favorites.value) == listOf(live[20], live[5], live[1])) {
            "Favoriten nicht in eigener Reihenfolge: ${Repository.favorites.value} → ${liveChannels(Repository.content.value, FAVORITES, Repository.favorites.value).map { it.name }}"
        }
        app.iptv.tv.data.Prefs.liveStartFavorites = true
        start()
        ok() // Live TV startet mit Favoriten
        expect("★ Favoriten")
        expect("noch ")
        expect("Danach ")
        shot("16-live-favoriten")
        down() // → „Favoriten sortieren“ (direkt unter Favoriten)
        ok()
        expect("Favoriten sortieren")
        shot("17-favoriten-sortieren")
        down() // zweiter Eintrag „Nach oben“
        ok()
        check(Repository.favorites.value.first() == live[5].stableKey) { "„Nach oben“ hat nicht gewirkt: ${Repository.favorites.value}" }
        back()
        shot("17b-nach-sortieren")
        right() // in die Senderliste
        ok() // ersten Favoriten abspielen
        shot("17c-player")
        key(androidx.compose.ui.input.key.Key.Three) // Reihenfolge jetzt 6, 21, 2 → Nummer 3 = Sender 2
        settle(1000)
        expect(live[1].name)
        shot("18-player-sendernummer")
    }

    @Test
    fun erwachseneninhalteAutomatischGesperrt() {
        val prefs = app.iptv.tv.data.CategoryPrefs
        check(prefs.isAdult("XXX | Adult")); check(prefs.isAdult("Erotik 18+")); check(prefs.isAdult("FOR ADULTS"))
        check(!prefs.isAdult("Sport")); check(!prefs.isAdult("Sachsen")); check(!prefs.isAdult("Essex TV")); check(!prefs.isAdult("Kinder"))
        val adult = Channel("Heiß", "http://x/live/u/p/x.ts", "XXX | Adult")
        prefs.setPin("1234"); prefs.lock()
        check(prefs.isLocked(ContentKind.LIVE, "XXX | Adult"))
        check(prefs.filterChannels(listOf(adult)).isEmpty()) { "Erwachsenen-Sender trotz PIN sichtbar" }
        check(prefs.unlock("1234"))
        check(prefs.filterChannels(listOf(adult)).size == 1)
        // bewusst entsperrt → bleibt entsperrt
        prefs.setLocked(ContentKind.LIVE, "XXX | Adult", false); prefs.lock()
        check(!prefs.isLocked(ContentKind.LIVE, "XXX | Adult"))
        prefs.setPin(null)
    }

    @Test
    fun filmeUndDetail() {
        start()
        right() // Filme
        ok()
        expect("Alle Filme")
        shot("20-filme")
        right()
        ok() // ersten Film öffnen
        expect("Abspielen")
        shot("21-film-detail")
        ok() // Abspielen
        shot("22-player-film")
        down() // Optionen
        shot("23-player-optionen")
        down()
        ok() // Untertitel-Seite
        shot("24-player-untertitel")
        back()
        down(4)
        ok() // Wiedergabe-Info
        expect("Bildrate Video")
        shot("25-wiedergabe-info")
        back()
        back()
        back()
        expect("Abspielen")
        back()
        back()
        expect("Weiterschauen")
    }

    @Test
    fun serien() {
        start()
        right(2)
        ok()
        expect("Alle Serien")
        shot("30-serien")
        down(3) // zur Kategorie „Serien“ (M3U-Episoden)
        right()
        ok()
        shot("31-serie-detail")
    }

    @Test
    fun tvProgramm() {
        start()
        right(3)
        ok()
        shot("40-tv-programm")
        down()
        right(4)
        shot("41-tv-programm-spaeter")
    }

    @Test
    fun suche() {
        start()
        left()
        ok()
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("film 1")
        settle(1500)
        expect("Filme")
        shot("50-suche")
    }

    @Test
    fun einstellungenUndKindersicherung() {
        start()
        right(4)
        ok()
        expect("Einstellungen")
        shot("60-einstellungen")
        down()
        ok() // Kategorien bearbeiten
        expect("Kategorien bearbeiten")
        shot("61-kategorien")
        back()
        down(3)
        ok() // Kindersicherung
        ok() // PIN festlegen
        shot("62-pin")
        // PIN mit den Zifferntasten der Fernbedienung eingeben, dann wiederholen
        val digits = listOf(androidx.compose.ui.input.key.Key.One, androidx.compose.ui.input.key.Key.Two,
            androidx.compose.ui.input.key.Key.Three, androidx.compose.ui.input.key.Key.Four)
        repeat(2) { digits.forEach { key(it) } }
        shot("62b-nach-pin")
        expect("PIN ändern")
        shot("63-kindersicherung-aktiv")
        // Kategorie „Sport“ sperren und wieder sperren → in Live TV nicht mehr sichtbar
        app.iptv.tv.data.CategoryPrefs.setLocked(app.iptv.tv.model.ContentKind.LIVE, "Sport", true)
        rule.runOnUiThread { Repository.visible.value = LibraryContent(PlaylistData(app.iptv.tv.data.CategoryPrefs.filterChannels(Repository.content.value.data.channels), Repository.content.value.data.shows)) }
        down(2)
        ok() // Jetzt wieder sperren
        settle()
        shot("63b-wieder-gesperrt")
        back()
        back()
        rule.runOnUiThread { Repository.visible.value = LibraryContent(PlaylistData(app.iptv.tv.data.CategoryPrefs.filterChannels(Repository.content.value.data.channels), Repository.content.value.data.shows)) }
        settle()
        left(4)
        ok() // Live TV
        check(rule.onAllNodesWithText("Sport").fetchSemanticsNodes().isEmpty()) { "Gesperrte Kategorie sichtbar" }
        expect("Gesperrte freischalten")
        shot("66-live-gesperrt")
        back()
        right(4)
        ok() // Einstellungen
        ok() // Playlists verwalten
        shot("64-playlists")
        ok() // Playlist hinzufügen
        shot("65-playlist-hinzufuegen")
    }

    @Test
    fun ersterStartOhnePlaylist() {
        Repository.playlists.value = emptyList()
        Repository.activeId.value = null
        Repository.content.value = LibraryContent.EMPTY
        Repository.visible.value = LibraryContent.EMPTY
        start()
        expect("Willkommen")
        shot("70-willkommen")
        ok() // Playlist hinzufügen
        expect("Playlist hinzufügen")
        back()
        expect("Willkommen")
    }

    @Test
    fun favoritPerLangemOkUndWeiterschauen() {
        start()
        ok() // Live TV
        right()
        down(4) // vom Sender auf Höhe der Kategorie (Sender 2) vier weiter → Sender 6
        longOk()
        shot("71-favorit-gesetzt")
        check(Repository.favorites.value.contains(Repository.content.value.live[5].stableKey)) { "Favorit nicht gesetzt: ${Repository.favorites.value}" }
        shot("71-favorit-gesetzt")
        back()
        down() // Reihe „Weiterschauen“
        ok() // Film fortsetzen
        expect("Film Nummer 5")
        shot("72-weiterschauen-player")
        back()
        back()
        expect("Weiterschauen")
    }

    @Test
    fun downloadsUndSync() {
        app.iptv.tv.data.Downloads.items.value = listOf(
            app.iptv.tv.data.DownloadItem(channel = Repository.content.value.movies[0], title = "Film Nummer 1", state = app.iptv.tv.data.DownloadState.DOWNLOADING, bytesWritten = 500_000_000, totalBytes = 1_500_000_000),
            app.iptv.tv.data.DownloadItem(channel = Repository.content.value.movies[1], title = "Film Nummer 2", state = app.iptv.tv.data.DownloadState.FAILED, error = "HTTP 403"),
        )
        start()
        right(4) // Downloads-Kachel
        ok()
        expect("Lädt 33 %")
        expect("Fehlgeschlagen: HTTP 403")
        shot("73-downloads")
        back()
        right()
        ok() // Einstellungen
        down(12)
        ok() // Untertitel
        expect("API-Key")
        shot("76-untertitel")
        back()
        down(6) // Google Drive
        shot("74-einstellungen-unten")
        ok()
        expect("Google-Zugang einrichten")
        shot("75-google-drive")
    }

    @Test
    fun playlistLoeschen() {
        start()
        right(4)
        ok() // Einstellungen
        ok() // Playlists verwalten
        down() // Playlist-Zeile
        right(2) // „Löschen“
        ok()
        expect("Wirklich löschen?")
        ok()
        settle(1500)
        check(Repository.playlists.value.isEmpty())
        back()
        back()
        expect("Willkommen")
        shot("77-nach-loeschen")
    }
}
