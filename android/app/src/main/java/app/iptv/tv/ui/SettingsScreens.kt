package app.iptv.tv.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.iptv.tv.data.CategoryPrefs
import app.iptv.tv.data.Downloads
import app.iptv.tv.data.GoogleSync
import app.iptv.tv.data.PhoneInputServer
import app.iptv.tv.data.PlayerChoice
import app.iptv.tv.data.Prefs
import app.iptv.tv.data.Repository
import app.iptv.tv.data.Subtitles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Eine Zeile in den Einstellungen: Titel links, Wert rechts, OK = Aktion. */
@Composable
fun SettingRow(
    title: String,
    value: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    FocusSurface(onClick = onClick, modifier = modifier.fillMaxWidth(), scale = 1.02f, shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (danger) Color(0xFFFF6B5A) else AppColors.TextPrimary)
                subtitle?.let { Text(it, fontSize = 13.sp, color = AppColors.TextSecondary) }
            }
            value?.let { Text(it, fontSize = 15.sp, color = AppColors.OrangeLight, textAlign = TextAlign.End) }
        }
    }
}

@Composable
fun SettingsHeader(text: String) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppColors.Orange, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp, start = 4.dp))
}

@Composable
fun ScreenTitle(text: String, subtitle: String? = null) {
    Text(text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    subtitle?.let { Text(it, fontSize = 15.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp)) }
    Spacer(Modifier.height(12.dp))
}

@Composable
fun SettingsScreen(nav: Navigator, memory: ScreenMemory) {
    val context = LocalContext.current
    Prefs.version.collectAsState().value // neu zeichnen bei Änderungen
    val playlists by Repository.playlists.collectAsState()
    val downloads by Downloads.items.collectAsState()
    val signedIn by GoogleSync.signedIn.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDownloads by remember { mutableStateOf(false) }
    remember { memory.restored = false; 0 }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 64.dp, vertical = 32.dp).widthIn(max = 900.dp),
    ) {
        ScreenTitle("Einstellungen")

        SettingsHeader("Inhalte")
        SettingRow("Playlists verwalten", "${playlists.size}", "Hinzufügen, bearbeiten, wechseln, löschen",
            modifier = Modifier.rememberFocus(memory, "playlists", isDefault = true)) { nav.push(Screen.Playlists) }
        SettingRow("Kategorien bearbeiten", subtitle = "Ausblenden, sortieren und mit PIN sperren",
            modifier = Modifier.rememberFocus(memory, "cats")) { nav.push(Screen.CategoryEdit()) }
        SettingRow("Kindersicherung", if (Prefs.hasPin) "PIN aktiv" else "Aus",
            modifier = Modifier.rememberFocus(memory, "parental")) { nav.push(Screen.Parental) }

        SettingsHeader("Wiedergabe")
        SettingRow("Player für Live TV", Prefs.playerLive.title, "OK = wechseln",
            modifier = Modifier.rememberFocus(memory, "pLive")) {
            Prefs.playerLive = PlayerChoice.entries[(Prefs.playerLive.ordinal + 1) % PlayerChoice.entries.size]
            GoogleSync.scheduleSync()
        }
        SettingRow("Player für Filme & Serien", Prefs.playerVod.title, "Automatisch: VLC bei Formaten wie AVI/WMV oder wenn ExoPlayer scheitert",
            modifier = Modifier.rememberFocus(memory, "pVod")) {
            Prefs.playerVod = PlayerChoice.entries[(Prefs.playerVod.ordinal + 1) % PlayerChoice.entries.size]
            GoogleSync.scheduleSync()
        }
        SettingRow("Untertitel (OpenSubtitles)", if (Subtitles.isConfigured) "eingerichtet" else "nicht eingerichtet",
            "API-Key, Konto und bevorzugte Sprachen", modifier = Modifier.rememberFocus(memory, "subs")) { nav.push(Screen.SubtitleSettings) }

        SettingsHeader("Downloads")
        val used = downloads.filter { it.state == app.iptv.tv.data.DownloadState.COMPLETED }.sumOf { it.totalBytes }
        SettingRow("Downloads anzeigen", "${downloads.size} · ${formatBytes(used)}", modifier = Modifier.rememberFocus(memory, "dl")) { nav.push(Screen.Downloads) }
        SettingRow("Speicherlimit", if (Prefs.downloadLimitGb == 0) "Kein Limit" else "${Prefs.downloadLimitGb} GB", "OK = wechseln",
            modifier = Modifier.rememberFocus(memory, "dlLimit")) {
            val options = listOf(0, 5, 10, 20, 50, 100)
            Prefs.downloadLimitGb = options[(options.indexOf(Prefs.downloadLimitGb) + 1) % options.size]
        }
        SettingRow("Während der Wiedergabe pausieren", if (Prefs.downloadPauseDuringPlayback) "An" else "Aus",
            "Empfohlen bei Konten mit nur einer Verbindung", modifier = Modifier.rememberFocus(memory, "dlPause")) {
            Prefs.downloadPauseDuringPlayback = !Prefs.downloadPauseDuringPlayback
        }
        SettingRow("Gesehene automatisch löschen", if (Prefs.downloadAutoDelete) "An" else "Aus", modifier = Modifier.rememberFocus(memory, "dlAuto")) {
            Prefs.downloadAutoDelete = !Prefs.downloadAutoDelete
        }
        SettingRow(if (confirmDownloads) "Wirklich alle Downloads löschen?" else "Alle Downloads löschen", danger = true,
            modifier = Modifier.rememberFocus(memory, "dlClear")) {
            if (confirmDownloads) { Downloads.removeAll(); confirmDownloads = false } else confirmDownloads = true
        }

        SettingsHeader("Synchronisierung")
        SettingRow("Google Drive", if (signedIn) Prefs.googleEmail.ifEmpty { "angemeldet" } else "Nicht angemeldet",
            "Favoriten, Weiterschauen und Verlauf mit der iPhone-App abgleichen", modifier = Modifier.rememberFocus(memory, "gd")) {
            nav.push(Screen.GoogleDrive)
        }

        SettingsHeader("Verlauf")
        SettingRow(if (confirmClear) "Wirklich Verlauf und Fortschritt löschen?" else "Verlauf und Fortschritt löschen", danger = true,
            modifier = Modifier.rememberFocus(memory, "clear")) {
            if (confirmClear) {
                Repository.clearHistory()
                confirmClear = false
                Toast.makeText(context, "Verlauf gelöscht", Toast.LENGTH_SHORT).show()
            } else confirmClear = true
        }

        SettingsHeader("Info")
        val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }
        Text("IPTV für Google TV · Version $version", fontSize = 14.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 32.dp))
    }
}

fun formatBytes(b: Long): String = when {
    b >= 1_000_000_000 -> "%.1f GB".format(b / 1e9)
    b >= 1_000_000 -> "%.0f MB".format(b / 1e6)
    b > 0 -> "%.0f KB".format(b / 1e3)
    else -> "0 MB"
}

// region Handy-Formular

/** QR-Code-Panel mit kleinem Web-Server für ein Formular (z. B. API-Key) – nichts abtippen. */
@Composable
fun PhoneFormPanel(form: PhoneInputServer.Form, onReceived: (Map<String, String>) -> Unit) {
    val server = remember(form) { PhoneInputServer(form) }
    val url by produceState<String?>(null, server) {
        value = withContext(Dispatchers.IO) {
            runCatching { PhoneInputServer.localIpAddress()?.let { ip -> server.start(); server.url(ip) } }.getOrNull()
        }
    }
    DisposableEffect(server) { onDispose { server.stop() } }
    LaunchedEffect(server) {
        server.receivedForm.collect { v -> if (v != null) { server.receivedForm.value = null; onReceived(v) } }
    }
    PhonePanelContent(url, "Mit dem Handy eintragen")
}

// endregion

// region Untertitel

@Composable
fun SubtitleSettingsScreen(nav: Navigator) {
    val context = LocalContext.current
    Prefs.version.collectAsState().value
    var key by remember { mutableStateOf(Prefs.subtitleApiKey) }
    var user by remember { mutableStateOf(Prefs.subtitleUser) }
    var pass by remember { mutableStateOf(Prefs.subtitlePassword) }
    var langs by remember { mutableStateOf(Prefs.subtitleLanguages) }
    val memory = remember { ScreenMemory() }

    fun save() {
        Prefs.subtitleApiKey = key; Prefs.subtitleUser = user; Prefs.subtitlePassword = pass; Prefs.subtitleLanguages = langs
        Toast.makeText(context, "Gespeichert", Toast.LENGTH_SHORT).show()
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 64.dp, end = 32.dp, top = 32.dp, bottom = 32.dp)) {
            ScreenTitle(
                "Untertitel (OpenSubtitles)",
                "Den kostenlosen API-Key gibt es auf opensubtitles.com unter „API Consumers“. Ohne Anmeldung sind etwa 5 Downloads " +
                    "pro Tag möglich, mit Benutzername und Passwort etwa 20. Untertitel suchst du im Player: OK lange drücken → „Untertitel suchen“.",
            )
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.widthIn(max = 720.dp)) {
                TvTextField(key, { key = it }, "API-Key", keyboardType = KeyboardType.Text, modifier = Modifier.rememberFocus(memory, "key", isDefault = true))
                TvTextField(user, { user = it }, "Benutzername (optional)", keyboardType = KeyboardType.Text)
                TvTextField(pass, { pass = it }, "Passwort (optional)", password = true)
                TvTextField(langs, { langs = it }, "Sprachen (Kürzel, Reihenfolge = Vorrang)", placeholder = "de,en", keyboardType = KeyboardType.Text)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AppButton("Speichern", { save(); nav.back() }, icon = AppIcons.Check)
                    AppButton("Abbrechen", { nav.back() }, primary = false)
                }
            }
        }
        PhoneFormPanel(
            PhoneInputServer.Form(
                "Untertitel einrichten", "OpenSubtitles-Zugang für den Fernseher.",
                listOf(
                    PhoneInputServer.Field("key", "API-Key", key),
                    PhoneInputServer.Field("user", "Benutzername (optional)", user),
                    PhoneInputServer.Field("pass", "Passwort (optional)", "", password = true),
                    PhoneInputServer.Field("langs", "Sprachen", langs, hint = "z. B. de,en"),
                ),
            ),
        ) { v ->
            v["key"]?.let { key = it.trim() }
            v["user"]?.let { user = it.trim() }
            v["pass"]?.takeIf { it.isNotEmpty() }?.let { pass = it }
            v["langs"]?.takeIf { it.isNotBlank() }?.let { langs = it }
            save()
        }
    }
}

// endregion

// region Google Drive

@Composable
fun GoogleDriveScreen(nav: Navigator) {
    val context = LocalContext.current
    Prefs.version.collectAsState().value
    val signedIn by GoogleSync.signedIn.collectAsState()
    val syncing by GoogleSync.isSyncing.collectAsState()
    val error by GoogleSync.lastError.collectAsState()
    val login by GoogleSync.login.collectAsState()
    val memory = remember { ScreenMemory() }
    var showSetup by remember { mutableStateOf(!GoogleSync.isConfigured) }
    DisposableEffect(Unit) { onDispose { GoogleSync.cancelSignIn() } }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 64.dp, end = 32.dp, top = 32.dp, bottom = 32.dp)) {
            ScreenTitle(
                "Google Drive",
                "Gleicht Favoriten, Weiterschauen, Verlauf, Player-Einstellungen und optional Playlists mit der iPhone-App ab. " +
                    "Gespeichert wird in einem versteckten App-Ordner – auf andere Dateien hat die App keinen Zugriff.",
            )
            Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    login != null -> {
                        val l = login!!
                        Text("Am Handy oder Computer anmelden:", fontSize = 18.sp)
                        Text("1. ${l.url} öffnen (oder QR-Code rechts scannen)", fontSize = 17.sp, color = AppColors.TextSecondary)
                        Text("2. Diesen Code eingeben:", fontSize = 17.sp, color = AppColors.TextSecondary)
                        Text(l.userCode, fontSize = 44.sp, fontWeight = FontWeight.Black, color = AppColors.OrangeLight)
                        Text("3. Mit dem Google-Konto anmelden, das auch das iPhone nutzt, und Zugriff erlauben.", fontSize = 17.sp, color = AppColors.TextSecondary)
                        Row(verticalAlignment = Alignment.CenterVertically) { Spinner(size = 24.dp); Spacer(Modifier.width(10.dp)); Text("Warte auf Bestätigung …", fontSize = 15.sp) }
                        AppButton("Abbrechen", { GoogleSync.cancelSignIn() }, primary = false, modifier = Modifier.rememberFocus(memory, "cancel", isDefault = true))
                    }
                    signedIn -> {
                        SettingRow("Angemeldet", Prefs.googleEmail.ifEmpty { "Google-Konto" }) { }
                        SettingRow(
                            if (syncing) "Synchronisiere …" else "Jetzt synchronisieren",
                            Prefs.lastSync.takeIf { it > 0 }?.let { "zuletzt " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) },
                            modifier = Modifier.rememberFocus(memory, "sync", isDefault = true),
                        ) { GoogleSync.syncNow() }
                        SettingRow(
                            "Playlists synchronisieren", if (Prefs.syncPlaylists) "An" else "Aus",
                            "Überträgt Playlists samt Zugangsdaten zwischen den Geräten",
                            modifier = Modifier.rememberFocus(memory, "pl"),
                        ) { Prefs.syncPlaylists = !Prefs.syncPlaylists; GoogleSync.syncNow() }
                        SettingRow("Abmelden", danger = true, modifier = Modifier.rememberFocus(memory, "out")) { GoogleSync.signOut() }
                    }
                    else -> {
                        if (GoogleSync.isConfigured) {
                            AppButton("Mit Google anmelden", { GoogleSync.startSignIn() }, icon = AppIcons.Check,
                                modifier = Modifier.rememberFocus(memory, "in", isDefault = true))
                        }
                        SettingRow(
                            "Google-Zugang einrichten", if (GoogleSync.isConfigured) "eingerichtet" else "nötig",
                            "Einmalig: Client-ID für Fernseher anlegen (Anleitung rechts bzw. im README)",
                            modifier = Modifier.rememberFocus(memory, "setup", isDefault = !GoogleSync.isConfigured),
                        ) { showSetup = !showSetup }
                    }
                }
                error?.let { Text(it, color = Color(0xFFFF6B5A), fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp)) }
                if (showSetup && !signedIn && login == null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Einmalige Einrichtung (am Computer, ca. 5 Minuten):\n" +
                            "1. console.cloud.google.com → dasselbe Projekt wie für die iPhone-App öffnen.\n" +
                            "2. APIs & Dienste → Anmeldedaten → Anmeldedaten erstellen → OAuth-Client-ID.\n" +
                            "3. Anwendungstyp „Fernseher und Geräte mit begrenzter Eingabe“ wählen, Name „IPTV TV“.\n" +
                            "4. Client-ID und Clientschlüssel (Secret) per QR-Code rechts vom Handy aus senden.\n" +
                            "Wichtig: Gleiches Projekt wie die iPhone-App, sonst sehen die Geräte verschiedene Daten.",
                        fontSize = 15.sp, lineHeight = 22.sp, color = AppColors.TextSecondary,
                    )
                }
            }
        }
        val l = login
        if (l != null) {
            Column(
                Modifier.width(400.dp).fillMaxHeight().background(AppColors.Surface.copy(alpha = 0.6f)).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Anmeldeseite öffnen", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                QrCode(l.url, Modifier.size(240.dp))
                Spacer(Modifier.height(12.dp))
                Text(l.url, fontSize = 16.sp, color = AppColors.OrangeLight)
            }
        } else if (showSetup && !signedIn) {
            PhoneFormPanel(
                PhoneInputServer.Form(
                    "Google-Zugang", "Client-ID und Clientschlüssel des OAuth-Clients vom Typ „Fernseher und Geräte mit begrenzter Eingabe“.",
                    listOf(
                        PhoneInputServer.Field("id", "Client-ID", Prefs.googleClientId, hint = "endet auf .apps.googleusercontent.com"),
                        PhoneInputServer.Field("secret", "Clientschlüssel (Client Secret)", "", password = true),
                    ),
                ),
            ) { v ->
                v["id"]?.takeIf { it.isNotBlank() }?.let { Prefs.googleClientId = it }
                v["secret"]?.takeIf { it.isNotBlank() }?.let { Prefs.googleClientSecret = it }
                Toast.makeText(context, "Google-Zugang gespeichert", Toast.LENGTH_SHORT).show()
                if (GoogleSync.isConfigured) { showSetup = false; GoogleSync.startSignIn() }
            }
        }
    }
}

// endregion

// region Kindersicherung

/** Zahlenfeld für die PIN – mit Steuerkreuz oder den Zifferntasten der Fernbedienung bedienbar. */
@Composable
fun PinPad(title: String, message: String?, onComplete: (String) -> Unit, length: Int = 4) {
    var pin by remember { mutableStateOf("") }
    val memory = remember { ScreenMemory() }
    fun add(d: String) {
        if (pin.length >= length) return
        pin += d
        if (pin.length == length) {
            val done = pin
            pin = ""
            onComplete(done)
        }
    }
    Column(
        Modifier.fillMaxSize().onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            val code = e.key.nativeKeyCode
            when (code) {
                in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> { add((code - android.view.KeyEvent.KEYCODE_0).toString()); true }
                android.view.KeyEvent.KEYCODE_DEL -> { pin = pin.dropLast(1); true }
                else -> false
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(message ?: " ", fontSize = 16.sp, color = if (message != null) AppColors.OrangeLight else AppColors.TextSecondary)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(length) { i ->
                Box(
                    Modifier.size(22.dp).background(
                        if (i < pin.length) AppColors.Orange else AppColors.SurfaceHigh,
                        RoundedCornerShape(11.dp),
                    ),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", ""))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { label ->
                        if (label.isEmpty()) Spacer(Modifier.size(76.dp, 60.dp)) else {
                            FocusSurface(
                                onClick = { if (label == "⌫") pin = pin.dropLast(1) else add(label) },
                                modifier = Modifier.size(76.dp, 60.dp).rememberFocus(memory, label, isDefault = label == "1"),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(label, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("Tipp: Die Zifferntasten der Fernbedienung funktionieren auch.", fontSize = 13.sp, color = AppColors.TextSecondary)
    }
}

@Composable
fun UnlockScreen(nav: Navigator) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    PinPad("PIN eingeben", message ?: "Gesperrte Kategorien freischalten", onComplete = { pin ->
        if (CategoryPrefs.unlock(pin)) {
            Toast.makeText(context, "Freigeschaltet bis zum Verlassen der App", Toast.LENGTH_SHORT).show()
            nav.back()
        } else message = "Falsche PIN – bitte erneut versuchen."
    })
}

@Composable
fun ParentalScreen(nav: Navigator) {
    val context = LocalContext.current
    Prefs.version.collectAsState().value
    val unlocked by CategoryPrefs.unlocked.collectAsState()
    // Schritt: null = Übersicht, "verify" = alte PIN prüfen, "new" = neue PIN, "confirm" = wiederholen
    var step by remember { mutableStateOf<String?>(if (Prefs.hasPin && !unlocked) "verify" else null) }
    var first by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var afterVerify by remember { mutableStateOf<String?>(null) }
    val memory = remember { ScreenMemory() }

    when (step) {
        "verify" -> PinPad("Aktuelle PIN", message, onComplete = { pin ->
            if (CategoryPrefs.unlock(pin)) { message = null; step = afterVerify; afterVerify = null } else message = "Falsche PIN."
        })
        "new" -> PinPad("Neue PIN (4 Ziffern)", message, onComplete = { pin -> first = pin; message = null; step = "confirm" })
        "confirm" -> PinPad("PIN wiederholen", message, onComplete = { pin ->
            if (pin == first) {
                CategoryPrefs.setPin(pin)
                Toast.makeText(context, "PIN gespeichert", Toast.LENGTH_SHORT).show()
                step = null; message = null
            } else { message = "Die PINs stimmen nicht überein. Bitte neu festlegen."; step = "new" }
        })
        else -> Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 32.dp).widthIn(max = 900.dp)) {
            remember { memory.restored = false; 0 }
            ScreenTitle(
                "Kindersicherung",
                "Mit einer PIN lassen sich Kategorien sperren (Einstellungen → Kategorien bearbeiten → „Sperren“). " +
                    "Gesperrte Kategorien sind erst nach Eingabe der PIN sichtbar – bis die App verlassen wird.",
            )
            if (!Prefs.hasPin) {
                SettingRow("PIN festlegen", modifier = Modifier.rememberFocus(memory, "set", isDefault = true)) { step = "new" }
            } else {
                SettingRow("Kategorien sperren …", subtitle = "Öffnet „Kategorien bearbeiten“",
                    modifier = Modifier.rememberFocus(memory, "cats", isDefault = true)) { nav.push(Screen.CategoryEdit()) }
                SettingRow("PIN ändern", modifier = Modifier.rememberFocus(memory, "change")) { step = "new" }
                if (unlocked) SettingRow("Jetzt wieder sperren", modifier = Modifier.rememberFocus(memory, "lock")) { CategoryPrefs.lock() }
                SettingRow("Kindersicherung ausschalten", "hebt alle Sperren auf", danger = true, modifier = Modifier.rememberFocus(memory, "off")) {
                    CategoryPrefs.setPin(null)
                    Toast.makeText(context, "Kindersicherung ausgeschaltet", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

// endregion
