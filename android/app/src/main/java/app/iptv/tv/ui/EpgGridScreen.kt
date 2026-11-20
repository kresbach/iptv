package app.iptv.tv.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.iptv.tv.data.CategoryPrefs
import app.iptv.tv.data.Repository
import app.iptv.tv.model.Channel
import app.iptv.tv.model.ContentKind
import app.iptv.tv.model.Programme
import kotlinx.coroutines.android.awaitFrame

private const val WINDOW_MS = 3 * 3600_000L
private const val STEP_MS = 3600_000L
private const val HALF_HOUR = 1800_000L

/** TV-Programm als Zeitraster: Sender untereinander, Sendungen entlang der Zeitachse. */
@Composable
fun EpgGridScreen(nav: Navigator, memory: ScreenMemory) {
    val content by Repository.visible.collectAsState()
    val epg by Repository.epg.collectAsState()
    val favorites by Repository.favorites.collectAsState()
    CategoryPrefs.version.collectAsState().value
    val now = rememberNow()
    remember { memory.restored = false; 0 }

    val guide = epg
    if (guide == null || guide.programmes.isEmpty()) {
        EmptyHint("Kein TV-Guide vorhanden.\nDer Anbieter liefert keinen mit – unter Playlists → Bearbeiten lässt sich eine eigene Adresse eintragen.")
        return
    }

    val categories = remember(content, favorites, guide) {
        buildList {
            if (content.live.any { it.stableKey in favorites && guide.schedule(it).isNotEmpty() }) add(FAVORITES to "★ Favoriten")
            add(ALL to "Alle Sender")
            CategoryPrefs.arranged(content.liveCategories, ContentKind.LIVE)
                .filter { c -> content.live(c.name).any { guide.schedule(it).isNotEmpty() } }
                .forEach { add(it.name to it.name) }
        }
    }
    var category by remember { mutableStateOf(memory.selection ?: categories.first().first) }
    val channels = remember(content, category, favorites, guide) {
        liveChannels(content, category, favorites).filter { guide.schedule(it).isNotEmpty() }.take(300)
    }
    var windowStart by remember { mutableLongStateOf(System.currentTimeMillis() / HALF_HOUR * HALF_HOUR) }
    var focused by remember { mutableStateOf<Pair<Channel, Programme?>?>(null) }
    /** Nach dem Blättern: Fokus in dieser Zeile auf die Sendung zu diesem Zeitpunkt setzen. */
    var focusTarget by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    val minStart = (System.currentTimeMillis() - 2 * 3600_000L) / HALF_HOUR * HALF_HOUR

    Column(Modifier.fillMaxSize().padding(top = 20.dp)) {
        // Kopf: Details zur fokussierten Sendung
        Column(Modifier.padding(horizontal = 40.dp).height(96.dp)) {
            val f = focused
            if (f?.second != null) {
                val p = f.second!!
                Text("${f.first.name}  ·  ${clock(p.start)} – ${clock(p.end)}" + if (p.isLive(now)) "  ·  läuft – OK = einschalten" else "",
                    fontSize = 14.sp, color = AppColors.OrangeLight)
                Text(p.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                p.description?.let { Text(it, fontSize = 14.sp, color = AppColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            } else {
                Text("TV-Programm", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("◀ ▶ Zeit · ▲ ▼ Sender · OK auf laufender Sendung = einschalten", fontSize = 14.sp, color = AppColors.TextSecondary)
            }
        }
        // Kategorien
        LazyRow(contentPadding = PaddingValues(horizontal = 40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(categories, key = { it.first }) { (id, title) ->
                SmallButton(
                    if (id == category) "● $title" else title,
                    Modifier.rememberFocus(memory, "cat-$id", isDefault = false),
                ) { category = id; memory.selection = id; focusTarget = null }
            }
        }
        Spacer(Modifier.height(8.dp))

        BoxWithConstraints(Modifier.fillMaxSize().padding(start = 40.dp, end = 24.dp)) {
            val channelColumn = 190.dp
            val timeline = maxWidth - channelColumn
            val dpPerMs = timeline.value / WINDOW_MS
            Column {
                // Zeitleiste
                Row(Modifier.fillMaxWidth().height(24.dp)) {
                    Spacer(Modifier.width(channelColumn))
                    Box(Modifier.width(timeline).fillMaxHeight()) {
                        var t = windowStart
                        while (t < windowStart + WINDOW_MS) {
                            Text(clock(t), fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.offset(x = ((t - windowStart) * dpPerMs).dp))
                            t += HALF_HOUR
                        }
                        if (now in windowStart until windowStart + WINDOW_MS) {
                            Box(Modifier.offset(x = ((now - windowStart) * dpPerMs).dp).width(2.dp).fillMaxHeight().background(AppColors.Orange))
                        }
                    }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                    itemsIndexed(channels, key = { i, c -> "$i|${c.url}" }) { row, ch ->
                        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.width(channelColumn).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(46.dp, 30.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF14141A))) {
                                    RemoteImage(ch.logo, ch.initials, Modifier.fillMaxSize().padding(2.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(ch.name, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            val list = guide.schedule(ch).filter { it.end > windowStart && it.start < windowStart + WINDOW_MS }
                            Box(Modifier.width(timeline).fillMaxHeight()) {
                                if (list.isEmpty()) {
                                    GridCell(
                                        title = "Keine Programmdaten",
                                        x = 0f, width = timeline.value, live = false,
                                        wantFocus = focusTarget?.first == row || (memory.focusedKey == null && row == 0 && focusTarget == null),
                                        onFocus = { focused = ch to null; focusTarget = null },
                                        onClick = { nav.push(Screen.Player(channels, row)) },
                                        onEdge = { dir -> shift(dir, windowStart, minStart)?.let { windowStart = it; focusTarget = row to it } },
                                        isFirst = true, isLast = true,
                                    )
                                }
                                list.forEachIndexed { i, p ->
                                    val s = maxOf(p.start, windowStart)
                                    val e = minOf(p.end, windowStart + WINDOW_MS)
                                    val target = focusTarget
                                    GridCell(
                                        title = p.title,
                                        x = (s - windowStart) * dpPerMs,
                                        width = ((e - s) * dpPerMs - 3f).coerceAtLeast(6f),
                                        live = p.isLive(now),
                                        wantFocus = if (target != null) target.first == row && target.second in p.start until p.end.coerceAtLeast(p.start + 1)
                                            || (target != null && target.first == row && i == 0 && target.second < p.start)
                                        else memory.focusedKey == null && row == 0 && p.isLive(now),
                                        onFocus = { focused = ch to p; memory.focusedKey = "grid"; focusTarget = null },
                                        onClick = {
                                            if (p.isLive(now)) nav.push(Screen.Player(channels, row))
                                            else focused = ch to p
                                        },
                                        onEdge = { dir ->
                                            shift(dir, windowStart, minStart)?.let {
                                                windowStart = it
                                                focusTarget = row to if (dir > 0) it + WINDOW_MS - STEP_MS else it + STEP_MS - 1
                                            }
                                        },
                                        isFirst = i == 0,
                                        isLast = i == list.lastIndex,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun shift(dir: Int, start: Long, min: Long): Long? {
    val next = start + dir * STEP_MS
    if (next < min) return null
    if (next > System.currentTimeMillis() + 36 * 3600_000L) return null
    return next
}

@Composable
private fun GridCell(
    title: String,
    x: Float,
    width: Float,
    live: Boolean,
    wantFocus: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onEdge: (Int) -> Unit,
    isFirst: Boolean,
    isLast: Boolean,
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(wantFocus) {
        if (wantFocus) {
            awaitFrame()
            runCatching { requester.requestFocus() }
        }
    }
    FocusSurface(
        onClick = onClick,
        modifier = Modifier
            .offset(x = x.dp)
            .width(width.dp)
            .fillMaxHeight()
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    e.key.nativeKeyCode == KeyEvent.KEYCODE_DPAD_RIGHT && isLast -> { onEdge(1); true }
                    e.key.nativeKeyCode == KeyEvent.KEYCODE_DPAD_LEFT && isFirst -> { onEdge(-1); true }
                    else -> false
                }
            },
        scale = 1f,
        color = if (live) AppColors.Orange.copy(alpha = 0.22f) else AppColors.Surface,
        shape = RoundedCornerShape(6.dp),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
            Text(title, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
