package app.iptv.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import app.iptv.tv.data.PhoneInputServer
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.iptv.tv.data.Repository
import app.iptv.tv.data.UrlTools
import app.iptv.tv.model.PlaylistSource
import app.iptv.tv.model.XtreamCredentials
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun PlaylistsScreen(nav: Navigator, memory: ScreenMemory) {
    val playlists by Repository.playlists.collectAsState()
    val activeId by Repository.activeId.collectAsState()
    val loading by Repository.isLoading.collectAsState()
    val status by Repository.statusMessage.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    remember { memory.restored = false; 0 }

    Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp)) {
        Text("Playlists", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text(
            "OK = verwenden · Aktualisieren lädt Sender, Filme, Serien und TV-Guide neu.",
            fontSize = 16.sp,
            color = AppColors.TextSecondary,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AppButton(
                "Playlist hinzufügen", { nav.push(Screen.AddPlaylist) }, icon = AppIcons.Add,
                modifier = Modifier.rememberFocus(memory, "add", isDefault = true),
            )
            AppButton(
                "Aktive aktualisieren", { scope.launch { Repository.refresh() } }, icon = AppIcons.Refresh, primary = false,
                modifier = Modifier.rememberFocus(memory, "refresh"),
            )
            if (loading) Spinner(size = 30.dp)
        }
        status?.let {
            Text(it, color = AppColors.OrangeLight, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
        }
        Spacer(Modifier.height(20.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(8.dp)) {
            items(playlists, key = { it.id }) { p ->
                val isActive = p.id == activeId
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FocusSurface(
                        onClick = { scope.launch { Repository.activate(p.id); nav.home() } },
                        modifier = Modifier.weight(1f).rememberFocus(memory, "p-${p.id}"),
                        scale = 1.02f,
                        color = if (isActive) AppColors.Orange.copy(alpha = 0.2f) else AppColors.Surface,
                    ) {
                        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                                if (isActive) Icon(AppIcons.Check, null, tint = AppColors.Orange)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${p.source.typeTitle} · ${p.source.displayDetail}" +
                                        (p.lastUpdated?.let { " · Stand " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: ""),
                                    fontSize = 15.sp,
                                    color = AppColors.TextSecondary,
                                )
                            }
                        }
                    }
                    val confirming = confirmDelete == p.id
                    AppButton(
                        if (confirming) "Wirklich löschen?" else "Löschen",
                        onClick = {
                            if (confirming) {
                                confirmDelete = null
                                scope.launch { Repository.delete(p.id) }
                            } else confirmDelete = p.id
                        },
                        icon = AppIcons.Delete,
                        primary = confirming,
                        modifier = Modifier.rememberFocus(memory, "d-${p.id}"),
                    )
                }
            }
        }
    }
}

@Composable
fun AddPlaylistScreen(nav: Navigator) {
    var xtreamMode by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var link by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val loading by Repository.isLoading.collectAsState()
    val scope = rememberCoroutineScope()
    val memory = remember { ScreenMemory() }

    // Eingabe per Handy: kleiner Web-Server im Heimnetz, solange dieser Bildschirm offen ist.
    val phone = remember { PhoneInputServer() }
    val phoneUrl by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val ip = PhoneInputServer.localIpAddress() ?: return@runCatching null
                phone.start()
                phone.url(ip)
            }.getOrNull()
        }
    }
    DisposableEffect(Unit) { onDispose { phone.stop() } }

    fun save() {
        error = null
        val source: PlaylistSource = if (xtreamMode) {
            val s = UrlTools.normalizedServer(server)
            if (s == null || user.isBlank() || pass.isBlank()) {
                error = "Bitte Server, Benutzername und Passwort eingeben."
                phone.status = "Fehler: $error"
                return
            }
            PlaylistSource.Xtream(XtreamCredentials(s, user.trim(), pass.trim()))
        } else {
            val url = UrlTools.userInput(link)
            if (url == null) {
                error = "Bitte einen gültigen Link eingeben (beginnt mit http:// oder https://)."
                phone.status = "Fehler: $error"
                return
            }
            // get.php-Links sind Xtream-Zugänge → über die Xtream-Schnittstelle laden.
            UrlTools.xtreamCredentials(url)?.let { PlaylistSource.Xtream(it) } ?: PlaylistSource.M3u(url)
        }
        val title = name.trim().ifEmpty {
            when (source) {
                is PlaylistSource.Xtream -> source.credentials.username + " – Xtream"
                is PlaylistSource.M3u -> source.displayDetail
            }
        }
        phone.status = "Der Fernseher lädt die Playlist – das kann bei großen Listen etwas dauern …"
        scope.launch {
            val message = Repository.addPlaylist(title, source)
            if (message == null) {
                val c = Repository.content.value
                phone.status = "Fertig! ${c.live.size} Sender, ${c.movies.size} Filme und ${c.shows.size} Serien geladen."
                delay(2_500) // Handy kann die Erfolgsmeldung noch abholen.
                nav.home()
            } else {
                error = message
                phone.status = "Fehler: $message"
            }
        }
    }

    // Vom Handy gesendet → Felder ausfüllen und sofort laden.
    LaunchedEffect(phone) {
        phone.received.collect { input ->
            if (input == null || loading) return@collect
            phone.received.value = null
            if (input.name.isNotBlank()) name = input.name
            xtreamMode = input.isXtream
            if (input.isXtream) {
                server = input.server
                user = input.user
                pass = input.pass
            } else {
                link = input.link
            }
            save()
        }
    }

    Row(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(start = 64.dp, end = 32.dp, top = 40.dp, bottom = 40.dp),
    ) {
        Text("Playlist hinzufügen", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AppButton(
                "M3U-Link", { xtreamMode = false }, primary = !xtreamMode,
                modifier = Modifier.rememberFocus(memory, "m3u", isDefault = true),
            )
            AppButton("Xtream-Login", { xtreamMode = true }, primary = xtreamMode)
        }
        Spacer(Modifier.height(24.dp))
        Column(Modifier.widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            TvTextField(name, { name = it }, "Name (optional)", placeholder = "z. B. Mein IPTV", keyboardType = KeyboardType.Text)
            if (xtreamMode) {
                TvTextField(server, { server = it }, "Server", placeholder = "http://server.de:8080")
                TvTextField(user, { user = it }, "Benutzername", keyboardType = KeyboardType.Text)
                TvTextField(pass, { pass = it }, "Passwort", password = true)
            } else {
                TvTextField(link, { link = it }, "M3U-Link", placeholder = "http://server.de/get.php?username=…&password=…")
                Text(
                    "Links mit „get.php?username=…&password=…“ werden automatisch als Xtream-Zugang erkannt.",
                    fontSize = 15.sp,
                    color = AppColors.TextSecondary,
                )
            }
            error?.let {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .then(Modifier),
                ) {
                    Text(it, color = Color(0xFFFF6B5A), fontSize = 17.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AppButton(if (loading) "Lade …" else "Speichern & laden", { if (!loading) save() }, icon = AppIcons.Check)
                AppButton("Abbrechen", { nav.back() }, primary = false)
                if (loading) {
                    Spinner(size = 30.dp)
                    Text("Lade Sender, Filme und Serien – das kann bei großen Listen etwas dauern.", fontSize = 15.sp, color = AppColors.TextSecondary)
                }
            }
        }
    }
        PhonePanel(phoneUrl)
    }
}

/** Rechts: QR-Code + Adresse für die Eingabe per Handy. */
@Composable
private fun PhonePanel(url: String?) {
    Column(
        Modifier
            .width(400.dp)
            .fillMaxHeight()
            .background(AppColors.Surface.copy(alpha = 0.6f))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Bequemer mit dem Handy", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        if (url == null) {
            Text(
                "Keine WLAN-/Netzwerkverbindung gefunden. Ist der Fernseher mit dem Heimnetz verbunden?",
                fontSize = 16.sp, color = AppColors.TextSecondary, textAlign = TextAlign.Center,
            )
            return@Column
        }
        Text(
            "QR-Code mit der Handy-Kamera scannen (Handy im selben WLAN), Link einfügen und senden.",
            fontSize = 16.sp, color = AppColors.TextSecondary, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        QrCode(url, Modifier.size(260.dp))
        Spacer(Modifier.height(18.dp))
        Text("Oder im Handy-Browser öffnen:", fontSize = 14.sp, color = AppColors.TextSecondary)
        Text(url, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = AppColors.OrangeLight, textAlign = TextAlign.Center)
    }
}

@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
    }
    Canvas(modifier.background(Color.White, RoundedCornerShape(12.dp)).padding(10.dp)) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    drawRect(Color.Black, topLeft = Offset(x * cell, y * cell), size = Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}
