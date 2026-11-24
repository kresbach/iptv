package app.iptv.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.iptv.tv.data.CategoryPrefs
import app.iptv.tv.data.DownloadItem
import app.iptv.tv.data.DownloadState
import app.iptv.tv.data.Downloads
import app.iptv.tv.data.Prefs
import app.iptv.tv.data.Repository
import app.iptv.tv.model.ContentKind

/** Kategorien ausblenden, sperren (mit PIN) und sortieren – je Bereich. */
@Composable
fun CategoryEditScreen(nav: Navigator, screen: Screen.CategoryEdit) {
    val unlocked by CategoryPrefs.unlocked.collectAsState()
    Prefs.version.collectAsState().value
    if (Prefs.hasPin && !unlocked) {
        // Sonst könnten Kinder hier die Sperren einfach aufheben.
        var message by remember { mutableStateOf<String?>(null) }
        PinPad("PIN eingeben", message ?: "Kategorien bearbeiten ist durch die Kindersicherung geschützt", onComplete = { pin ->
            if (!CategoryPrefs.unlock(pin)) message = "Falsche PIN – bitte erneut versuchen."
        })
    } else {
        CategoryEditContent(nav, screen)
    }
}

@Composable
private fun CategoryEditContent(nav: Navigator, screen: Screen.CategoryEdit) {
    val content by Repository.content.collectAsState()
    CategoryPrefs.version.collectAsState().value
    Prefs.version.collectAsState().value
    var kind by remember { mutableStateOf(screen.kind) }
    val memory = screen.memory
    remember { memory.restored = false; 0 }

    val base = when (kind) {
        ContentKind.LIVE -> content.liveCategories
        ContentKind.MOVIE -> content.movieCategories
        ContentKind.SERIES -> content.showCategories
    }
    val categories = CategoryPrefs.arranged(base, kind, includeHidden = true)

    Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 28.dp)) {
        ScreenTitle(
            "Kategorien bearbeiten",
            "Ausblenden = nirgends mehr angezeigt. Sperren = nur nach PIN sichtbar" +
                (if (!Prefs.hasPin) " (erst PIN unter Einstellungen → Kindersicherung festlegen)" else "") +
                ". Kinder ✓ = im Kinderprofil sichtbar (Kinder-Kategorien erkennt die App selbst; alles andere bleibt dort unsichtbar). " +
                "▲▼ verschiebt die Kategorie.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ContentKind.entries.forEach { k ->
                AppButton(k.title, { kind = k }, primary = kind == k, modifier = Modifier.rememberFocus(memory, "k-$k", isDefault = k == kind))
            }
            Spacer(Modifier.width(24.dp))
            AppButton("Reihenfolge zurücksetzen", { CategoryPrefs.resetOrder(kind) }, primary = false)
        }
        Spacer(Modifier.height(12.dp))
        if (categories.isEmpty()) EmptyHint("Keine Kategorien in diesem Bereich.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp, top = 4.dp)) {
            itemsIndexed(categories, key = { _, c -> c.name }) { index, c ->
                val hidden = CategoryPrefs.isHidden(kind, c.name)
                val locked = CategoryPrefs.isLocked(kind, c.name)
                val forKids = CategoryPrefs.isKidsCategory(kind, c.name)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AppColors.Surface).padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        (if (locked) "🔒 " else "") + c.name,
                        fontSize = 16.sp,
                        color = if (hidden) AppColors.TextSecondary.copy(alpha = 0.6f) else AppColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("${c.count}", fontSize = 13.sp, color = AppColors.TextSecondary)
                    SmallButton(if (hidden) "Einblenden" else "Ausblenden", Modifier.rememberFocus(memory, "h-$kind-${c.name}")) {
                        CategoryPrefs.setHidden(kind, c.name, !hidden)
                    }
                    if (Prefs.hasPin) {
                        SmallButton(if (locked) "Entsperren" else "Sperren") { CategoryPrefs.setLocked(kind, c.name, !locked) }
                    }
                    if (!CategoryPrefs.isAdult(c.name)) {
                        SmallButton(if (forKids) "Kinder ✓" else "Kinder –") { CategoryPrefs.setKids(kind, c.name, !forKids) }
                    }
                    SmallButton("▲") { CategoryPrefs.move(kind, categories, index, -1) }
                    SmallButton("▼") { CategoryPrefs.move(kind, categories, index, 1) }
                }
            }
        }
    }
}

/** Eigene Reihenfolge der Favoriten (Sender, Filme, Serien). */
@Composable
fun FavoriteOrderScreen(memory: ScreenMemory) {
    val content by Repository.visible.collectAsState()
    val favorites by Repository.favorites.collectAsState()
    remember { memory.restored = false; 0 }
    val entries = remember(content, favorites) {
        favorites.mapNotNull { key ->
            content.channel(key)?.let { Triple(key, it.name, if (it.kind == ContentKind.LIVE) "Sender" else "Film") }
                ?: content.show(key)?.let { Triple(key, it.title, "Serie") }
        }
    }
    Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 28.dp)) {
        ScreenTitle("Favoriten sortieren", "▲▼ verschiebt, „Nach oben“ setzt an die erste Stelle. Gilt für Live TV und die Startseite.")
        if (entries.isEmpty()) EmptyHint("Noch keine Favoriten. OK lange drücken fügt einen Sender hinzu.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp, top = 4.dp)) {
            itemsIndexed(entries, key = { _, e -> e.first }) { index, (key, title, type) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AppColors.Surface).padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("${index + 1}", fontSize = 14.sp, color = AppColors.TextSecondary, modifier = Modifier.width(32.dp))
                    Text(title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(type, fontSize = 13.sp, color = AppColors.TextSecondary)
                    SmallButton("Nach oben", Modifier.rememberFocus(memory, "top-$key", isDefault = index == 0)) { Repository.moveFavoriteToTop(key) }
                    SmallButton("▲", Modifier.rememberFocus(memory, "up-$key")) { Repository.moveFavorite(key, -1) }
                    SmallButton("▼", Modifier.rememberFocus(memory, "down-$key")) { Repository.moveFavorite(key, 1) }
                }
            }
        }
    }
}

@Composable
fun SmallButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = modifier, scale = 1.08f, color = AppColors.SurfaceHigh, shape = RoundedCornerShape(16.dp)) {
        Text(text, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
    }
}

@Composable
fun IconSmallButton(icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = modifier, scale = 1.1f, color = AppColors.SurfaceHigh, shape = RoundedCornerShape(20.dp)) {
        androidx.tv.material3.Icon(icon, null, tint = Color.White, modifier = Modifier.padding(9.dp).size(20.dp))
    }
}

/** Übersicht der Downloads: OK = abspielen, lange OK = löschen. */
@Composable
fun DownloadsScreen(nav: Navigator, memory: ScreenMemory) {
    val items by Downloads.items.collectAsState()
    val progress by Repository.progress.collectAsState()
    var confirm by remember { mutableStateOf<String?>(null) }
    remember { memory.restored = false; 0 }

    Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 28.dp)) {
        ScreenTitle(
            "Downloads",
            "OK = abspielen · OK lange drücken = löschen. Downloads laufen, solange die App geöffnet ist " +
                "(während der Wiedergabe pausiert, damit die Verbindung frei bleibt).",
        )
        if (items.isEmpty()) {
            EmptyHint("Noch keine Downloads. Auf der Seite eines Films oder einer Serie „Herunterladen“ wählen.")
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
            itemsIndexed(items.sortedByDescending { it.addedAt }, key = { _, it -> it.id }) { i, item ->
                val p = progress[item.key]
                FocusSurface(
                    onClick = {
                        when (item.state) {
                            DownloadState.COMPLETED -> nav.push(Screen.Player(listOf(item.channel), 0, p?.position ?: 0, item.showKey))
                            DownloadState.FAILED -> Downloads.retry(item)
                            else -> Unit
                        }
                    },
                    onLongClick = { if (confirm == item.id) { Downloads.remove(item); confirm = null } else confirm = item.id },
                    modifier = Modifier.fillMaxWidth().rememberFocus(memory, item.id, isDefault = i == 0),
                    scale = 1.02f,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp, 66.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF14141A))) {
                            RemoteImage(item.poster, item.title.take(2).uppercase(), Modifier.fillMaxSize(), ContentScale.Crop)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            item.subtitle?.let { Text(it, fontSize = 13.sp, color = AppColors.TextSecondary) }
                            Spacer(Modifier.height(4.dp))
                            Text(downloadStatus(item, confirm == item.id), fontSize = 13.sp, color = AppColors.OrangeLight)
                            if (item.isActive && item.totalBytes > 0) ProgressLine(item.fraction, Modifier.width(320.dp).padding(top = 4.dp), 3.dp)
                            if (item.state == DownloadState.COMPLETED && p != null) {
                                ProgressLine(p.position.toFloat() / p.duration, Modifier.width(320.dp).padding(top = 4.dp), 3.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

fun downloadStatus(item: DownloadItem, confirming: Boolean = false): String = when {
    confirming -> "Nochmal lange drücken zum Löschen"
    else -> when (item.state) {
        DownloadState.QUEUED -> "Wartet …"
        DownloadState.DOWNLOADING -> "Lädt ${(item.fraction * 100).toInt()} % · ${formatBytes(item.bytesWritten)}" +
            (if (item.totalBytes > 0) " von ${formatBytes(item.totalBytes)}" else "")
        DownloadState.PAUSED -> "Pausiert ${(item.fraction * 100).toInt()} %" + (item.error?.let { " – $it" } ?: " (während der Wiedergabe)")
        DownloadState.COMPLETED -> "Heruntergeladen · ${formatBytes(item.totalBytes)}"
        DownloadState.FAILED -> "Fehlgeschlagen: ${item.error ?: "?"} – OK = erneut versuchen"
    }
}
