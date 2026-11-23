package app.iptv.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.iptv.tv.data.Diagnostics
import app.iptv.tv.data.Http
import app.iptv.tv.data.PlayerChoice
import app.iptv.tv.data.Prefs
import app.iptv.tv.data.Repository
import app.iptv.tv.data.Subtitles
import app.iptv.tv.model.Channel
import app.iptv.tv.model.ContentKind
import app.iptv.tv.player.PlaybackEngine
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch

/** Menü im Player (rechts): Tonspur, Untertitel, Untertitel-Suche, Player, Diagnose, Favorit. */
@Composable
fun PlayerOptions(
    page: String,
    focusReady: Boolean,
    channel: Channel,
    engine: PlaybackEngine,
    isFavorite: Boolean,
    onPage: (String) -> Unit,
    onClose: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEngineChange: (PlayerChoice) -> Unit,
    previousChannel: Channel? = null,
    onPreviousChannel: () -> Unit = {},
    onArchive: (app.iptv.tv.model.Programme) -> Unit = {},
) {
    val audio by engine.audioTracks.collectAsState()
    val text by engine.textTracks.collectAsState()
    val isLive = channel.kind == ContentKind.LIVE
    app.iptv.tv.data.SleepTimer.endsAt.collectAsState().value // neu zeichnen
    Prefs.version.collectAsState().value // Bildformat / Ton-Versatz
    val scope = rememberCoroutineScope()
    var busy by remember(page) { mutableStateOf(false) }
    var message by remember(page) { mutableStateOf<String?>(null) }
    var results by remember(page) { mutableStateOf<List<Subtitles.Result>?>(null) }
    var findings by remember(page) { mutableStateOf<List<Diagnostics.Finding>?>(null) }
    val firstFocus = remember(page) { FocusRequester() }

    // Erst fokussieren, wenn OK losgelassen wurde (sonst löst das Loslassen den ersten Eintrag aus).
    LaunchedEffect(page, focusReady) {
        if (focusReady) {
            awaitFrame()
            runCatching { firstFocus.requestFocus() }
        }
    }
    LaunchedEffect(page) {
        when (page) {
            "search" -> {
                busy = true
                try {
                    results = Subtitles.search(channel)
                    if (results!!.isEmpty()) message = "Keine Untertitel gefunden."
                } catch (e: Exception) {
                    message = Http.message(e)
                }
                busy = false
                awaitFrame()
                if (focusReady) runCatching { firstFocus.requestFocus() }
            }
            "diag" -> {
                busy = true
                // Stream anhalten: bei nur einer erlaubten Verbindung würde die Prüfung sonst kollidieren.
                engine.suspendConnection()
                kotlinx.coroutines.delay(1_500)
                findings = Diagnostics.run(channel, Repository.activePlaylist?.source)
                busy = false
            }
        }
    }

    val title = when (page) {
        "audio" -> "Tonspur"
        "text" -> "Untertitel"
        "search" -> "Untertitel suchen"
        "diag" -> "Verbindung prüfen"
        "engine" -> "Player"
        "info" -> "Wiedergabe-Info"
        else -> "Optionen"
    }

    Column(
        Modifier
            .width(460.dp)
            .fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Black.copy(alpha = 0.95f))))
            .padding(top = 28.dp, start = 20.dp, end = 20.dp),
    ) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
        Text(channel.name, fontSize = 13.sp, color = AppColors.TextSecondary, maxLines = 1, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
        if (busy) Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Spinner(size = 26.dp); Spacer(Modifier.width(10.dp)); Text(if (page == "diag") "Prüfe …" else "Suche …", fontSize = 15.sp)
        }
        message?.let { Text(it, fontSize = 15.sp, color = AppColors.OrangeLight, modifier = Modifier.padding(8.dp)) }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
            fun LazyListScope.row(key: String, label: String, value: String? = null, first: Boolean = false, onClick: () -> Unit) {
                item(key) {
                    OptionRow(label, value, if (first) Modifier.focusRequester(firstFocus) else Modifier, onClick)
                }
            }
            when (page) {
                "main" -> {
                    row("audio", "Tonspur", audio.firstOrNull { it.selected }?.label ?: if (audio.isEmpty()) "–" else "Standard", first = true) { onPage("audio") }
                    row("text", "Untertitel", text.firstOrNull { it.selected }?.label ?: "Aus") { onPage("text") }
                    if (!isLive) row("search", "Untertitel suchen (OpenSubtitles)", if (Subtitles.isConfigured) null else "nicht eingerichtet") { onPage("search") }
                    row("engine", "Player", "${engine.backendName} · ${(if (isLive) Prefs.playerLive else Prefs.playerVod).title}") { onPage("engine") }
                    if (isLive && app.iptv.tv.data.Catchup.supported(channel)) {
                        val current = Repository.epg.value?.current(channel)
                        if (current != null && app.iptv.tv.data.Catchup.available(channel, current)) {
                            row("restart", "Sendung von Anfang an", current.title) { onArchive(current) }
                        }
                        row("archive", "Verpasste Sendungen", "Archiv: ${channel.archiveDays} Tage") { onPage("archive") }
                    }
                    if (isLive) Repository.epg.value?.next(channel)?.let { next ->
                        val set = app.iptv.tv.data.Reminders.isSet(channel, next)
                        row("remind", if (set) "Erinnerung entfernen" else "An nächste Sendung erinnern", "${clock(next.start)} ${next.title}") {
                            app.iptv.tv.data.Reminders.toggle(channel, next); onClose()
                        }
                    }
                    if (isLive && previousChannel != null) row("prev", "Zurück zum letzten Sender", previousChannel.name) { onPreviousChannel() }
                    if (isLive) row("fav", if (isFavorite) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen") { onToggleFavorite() }
                    row("format", "Bildformat", app.iptv.tv.player.VideoFit.label(engine.videoMode)) {
                        val modes = app.iptv.tv.player.VideoFit.modes.map { it.first }
                        engine.videoMode = modes[(modes.indexOf(engine.videoMode) + 1) % modes.size]
                        onPage("main")
                    }
                    row("delay", "Ton-Versatz", delayLabel(Prefs.audioDelayMs)) { onPage("delay") }
                    row("sleep", "Sleep-Timer", app.iptv.tv.data.SleepTimer.label()) { app.iptv.tv.data.SleepTimer.cycle() }
                    row("info", "Wiedergabe-Info", "Bildrate, Ruckeln, Puffer") { onPage("info") }
                    row("diag", "Verbindung prüfen", "hält den Stream kurz an") { onPage("diag") }
                    row("close", "Schließen") { onClose() }
                }
                "archive" -> {
                    val past = app.iptv.tv.data.Catchup.pastProgrammes(channel, Repository.epg.value).filter { !it.isLive(System.currentTimeMillis()) }.take(200)
                    if (past.isEmpty()) row("none", "Keine Sendungen gefunden (TV-Guide fehlt?)", first = true) { onPage("main") }
                    past.forEachIndexed { i, p ->
                        row("p$i", "${dayLabel(p.start)}${clock(p.start)}  ${p.title}", first = i == 0) { onArchive(p) }
                    }
                }
                "delay" -> {
                    if (!engine.supportsAudioDelay) {
                        row("novlc", "Nur mit dem VLC-Player möglich", "Player → VLC wählen", first = true) { onPage("engine") }
                    } else {
                        row("later", "Ton später (+50 ms)", delayLabel(Prefs.audioDelayMs), first = true) { engine.setAudioDelay((Prefs.audioDelayMs + 50).coerceAtMost(3000)); onPage("delay") }
                        row("earlier", "Ton früher (−50 ms)", delayLabel(Prefs.audioDelayMs)) { engine.setAudioDelay((Prefs.audioDelayMs - 50).coerceAtLeast(-3000)); onPage("delay") }
                        row("reset", "Zurücksetzen (0 ms)") { engine.setAudioDelay(0); onPage("delay") }
                    }
                    row("back", "Zurück") { onPage("main") }
                }
                "audio" -> {
                    if (audio.isEmpty()) row("none", "Keine Tonspuren erkannt", first = true) { onPage("main") }
                    audio.forEachIndexed { i, t ->
                        row("a$i", (if (t.selected) "✓ " else "") + t.label, first = i == 0) { engine.selectAudio(t.id); onPage("main") }
                    }
                }
                "text" -> {
                    row("off", (if (text.none { it.selected }) "✓ " else "") + "Aus", first = true) { engine.selectText(null); onPage("main") }
                    text.forEachIndexed { i, t ->
                        row("t$i", (if (t.selected) "✓ " else "") + t.label) { engine.selectText(t.id); onPage("main") }
                    }
                    if (!isLive) row("search", "Im Internet suchen (OpenSubtitles) …") { onPage("search") }
                    if (Subtitles.saved(channel) != null) {
                        row("forget", "Heruntergeladenen Untertitel entfernen") {
                            Subtitles.forget(channel); engine.setSubtitleFile(null); onClose()
                        }
                    }
                }
                "search" -> {
                    if (!Subtitles.isConfigured) {
                        row("cfg", "Unter Einstellungen → Untertitel einrichten", first = true) { onPage("main") }
                    }
                    results?.let { list ->
                        list.take(40).forEachIndexed { i, r ->
                            row(
                                "r$i",
                                "${r.language.uppercase()} · ${r.release}",
                                "${r.downloads}×" + if (r.hearingImpaired) " · HI" else "",
                                first = i == 0,
                            ) {
                                if (busy) return@row
                                busy = true
                                scope.launch {
                                    try {
                                        val file = Subtitles.download(r, channel)
                                        engine.setSubtitleFile(file)
                                        onClose()
                                    } catch (e: Exception) {
                                        message = Http.message(e)
                                    }
                                    busy = false
                                }
                            }
                        }
                    }
                }
                "diag" -> {
                    findings?.forEachIndexed { i, f ->
                        item("f$i") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Box(
                                    Modifier.padding(top = 5.dp).size(10.dp).background(
                                        when (f.level) {
                                            Diagnostics.Level.OK -> Color(0xFF4CAF50)
                                            Diagnostics.Level.WARNING -> AppColors.OrangeLight
                                            Diagnostics.Level.PROBLEM -> Color(0xFFFF5A4A)
                                        },
                                        RoundedCornerShape(5.dp),
                                    ),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(f.text, fontSize = 15.sp, lineHeight = 21.sp)
                            }
                        }
                    }
                    if (findings != null) {
                        row("retry", "Stream neu starten", first = true) { engine.retryNow(); onClose() }
                    }
                }
                "info" -> item("info") {
                    val v by engine.videoInfo.collectAsState()
                    var buffer by remember { mutableStateOf(0L) }
                    LaunchedEffect(Unit) {
                        while (true) {
                            buffer = engine.bufferedMs
                            engine.refreshVlcStats()
                            kotlinx.coroutines.delay(1_000)
                        }
                    }
                    val rate = app.iptv.tv.player.DisplayRate.currentRate(app.iptv.tv.player.DisplayRate.findActivity(androidx.compose.ui.platform.LocalContext.current))
                    val lines = listOf(
                        "Player" to engine.backendName + (if (v.decoder.isNotEmpty() && v.decoder != "VLC") " · ${v.decoder}" else ""),
                        "Auflösung" to if (v.width > 0) "${v.width}×${v.height}" else "–",
                        "Bildrate Video" to if (v.fps > 0) "%.3g fps".format(v.fps) else "wird gemessen …",
                        "Bildschirm" to (rate?.let { "%.3g Hz".format(it) } ?: "–") +
                            (if (v.fps > 0 && rate != null && app.iptv.tv.player.DisplayRate.bestRefreshRate(v.fps, listOf(rate)) == null) "  ⚠ passt nicht" else ""),
                        "Deinterlacing" to when (engine.deinterlaceActive) {
                            "yadif2x" -> "an (Yadif 2x)"; "bob" -> "an (Bob)"
                            else -> if (engine.backendName == "ExoPlayer") "aus (ExoPlayer)" else "aus"
                        },
                        "Verlorene Bilder" to "${v.droppedFrames}",
                        "Hänger (Nachladen)" to "${v.rebuffers}",
                        "Puffer" to if (engine.backendName == "VLC") "–" else "%.1f s".format(buffer / 1000f),
                    )
                    Column(Modifier.padding(horizontal = 8.dp).focusRequester(firstFocus).focusable()) {
                        lines.forEach { (k, value) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                Text(k, fontSize = 15.sp, color = AppColors.TextSecondary, modifier = Modifier.weight(1f))
                                Text(value, fontSize = 15.sp)
                            }
                        }
                        Spacer(Modifier.size(10.dp))
                        Text(
                            "Viele verlorene Bilder → Bildrate passt nicht oder das Gerät ist überlastet. " +
                                "Viele Hänger → Verbindung zum Anbieter zu langsam (Einstellungen → großer Puffer).",
                            fontSize = 13.sp, color = AppColors.TextSecondary, lineHeight = 18.sp,
                        )
                    }
                }
                "engine" -> {
                    val current = if (isLive) Prefs.playerLive else Prefs.playerVod
                    PlayerChoice.entries.forEachIndexed { i, c ->
                        row("e$i", (if (c == current) "✓ " else "") + c.title, first = i == 0) { onEngineChange(c) }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, value: String?, modifier: Modifier, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = modifier.fillMaxWidth(), scale = 1.02f, color = Color.White.copy(alpha = 0.06f), shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
            value?.let { Text(it, fontSize = 13.sp, color = AppColors.OrangeLight) }
        }
    }
}

private fun delayLabel(ms: Int) = when {
    ms == 0 -> "aus"
    ms > 0 -> "Ton +$ms ms später"
    else -> "Ton ${-ms} ms früher"
}
