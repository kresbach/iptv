package app.iptv.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.iptv.tv.data.LibraryContent
import app.iptv.tv.data.Repository
import app.iptv.tv.model.Channel

const val ALL = "\u0000alle"
const val FAVORITES = "\u0000favoriten"
const val UNLOCK = "\u0000entsperren"
const val SORT_FAVORITES = "\u0000sortieren"

fun liveChannels(content: LibraryContent, category: String, favorites: Set<String>): List<Channel> = when (category) {
    ALL -> content.live
    // In der eigenen Reihenfolge (Einstellungen → Favoriten sortieren)
    FAVORITES -> favorites.mapNotNull { content.channel(it) }.filter { it.kind == app.iptv.tv.model.ContentKind.LIVE }
    else -> content.live(category)
}

/** Live TV: Kategorien links, Sender mit aktueller Sendung rechts. */
@Composable
fun LiveScreen(nav: Navigator, screen: Screen.Live) {
    val memory = screen.memory
    val content by Repository.visible.collectAsState()
    val catVersion = app.iptv.tv.data.CategoryPrefs.version.collectAsState().value
    val epg by Repository.epg.collectAsState()
    val favorites by Repository.favorites.collectAsState()
    val toggleFavorite = rememberFavoriteToggle()
    val now = rememberNow()
    remember { memory.restored = false; 0 }

    val categories = remember(content, favorites, catVersion) {
        buildList {
            val favCount = content.live.count { it.stableKey in favorites }
            if (favCount > 0) add(Triple(FAVORITES, "★ Favoriten", favCount as Int?))
            if (favCount > 1) add(Triple(SORT_FAVORITES, "⇅ Favoriten sortieren", null))
            add(Triple(ALL, "Alle Sender", content.live.size))
            app.iptv.tv.data.CategoryPrefs.arranged(content.liveCategories, app.iptv.tv.model.ContentKind.LIVE)
                .forEach { add(Triple(it.name, it.name, it.count)) }
            if (app.iptv.tv.data.CategoryPrefs.hasLocked(app.iptv.tv.model.ContentKind.LIVE) && !app.iptv.tv.data.CategoryPrefs.unlocked.value) add(Triple(UNLOCK, "🔒 Gesperrte freischalten", null))
        }
    }
    var selected by remember {
        mutableStateOf(
            memory.selection ?: screen.startCategory
                ?: categories.firstOrNull { it.first != FAVORITES || app.iptv.tv.data.Prefs.liveStartFavorites }?.first ?: ALL,
        )
    }

    // Gemerkte Kategorie gibt es nicht mehr (ausgeblendet, gesperrt, letzte Favorit entfernt) → „Alle“.
    LaunchedEffect(categories) {
        if (categories.none { it.first == selected }) { selected = ALL; memory.selection = null }
    }
    val channels = remember(content, selected, favorites) { liveChannels(content, selected, favorites) }

    // Vorschau: markierter Sender läuft nach 1 s im kleinen Fenster (immer nur eine Verbindung).
    val context = androidx.compose.ui.platform.LocalContext.current
    val previewOn = remember { app.iptv.tv.data.Prefs.livePreview }
    val preview = remember { if (previewOn) app.iptv.tv.player.PlaybackEngine(context.applicationContext) else null }
    var focusedChannel by remember { mutableStateOf<Channel?>(null) }
    var previewKey by remember { mutableStateOf<String?>(null) }
    val handedOver = remember { booleanArrayOf(false) }
    if (preview != null) {
        LaunchedEffect(focusedChannel) {
            val ch = focusedChannel ?: return@LaunchedEffect
            if (ch.stableKey == previewKey) return@LaunchedEffect
            kotlinx.coroutines.delay(1_000)
            previewKey = ch.stableKey
            preview.preferredBackend = app.iptv.tv.data.Prefs.playerLive
            Repository.playbackActive = true
            preview.play(ch)
            val alt = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                app.iptv.tv.data.Alternatives.find(ch, Repository.visible.value.live)
            }
            if (preview.currentKey == ch.stableKey) preview.alternatives = alt
        }
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            // Fernseher geht in Standby/Home: Verbindung sofort freigeben.
            val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
                if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                    preview.stop(); previewKey = null; Repository.playbackActive = false
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                if (!handedOver[0]) {
                    preview.release()
                    Repository.playbackActive = false
                }
            }
        }
    }
    fun open(index: Int) {
        memory.selection = selected
        val ch = channels[index]
        val p = preview
        if (p != null && previewKey == ch.stableKey && !p.status.value.failed) {
            handedOver[0] = true
            LivePreview.handOver(p, ch.stableKey)
        }
        nav.push(Screen.Player(channels, index))
    }

    Row(Modifier.fillMaxSize()) {
        // Kategorien
        Column(
            Modifier
                .width(if (preview != null) 200.dp else 260.dp)
                .fillMaxHeight()
                .background(AppColors.Surface.copy(alpha = 0.5f))
                .padding(top = 32.dp),
        ) {
            Text("Live TV", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 28.dp, bottom = 12.dp))
            val catState = rememberLazyListState(memory.scroll["cats"]?.first ?: 0)
            DisposableEffect(Unit) { onDispose { memory.scroll["cats"] = catState.firstVisibleItemIndex to 0 } }
            LazyColumn(
                state = catState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.focusRestorer(),
            ) {
                itemsIndexed(categories, key = { _, c -> c.first }) { _, (id, title, count) ->
                    CategoryItem(
                        name = title,
                        count = count,
                        selected = id == selected,
                        onFocus = {
                            if (selected != id && id != UNLOCK && id != SORT_FAVORITES) {
                                selected = id
                                memory.selection = id
                                memory.scroll.remove("channels")
                            }
                        },
                        onClick = {
                            when (id) {
                                UNLOCK -> nav.push(Screen.Unlock)
                                SORT_FAVORITES -> nav.push(Screen.FavoriteOrder)
                                else -> { selected = id; memory.selection = id }
                            }
                        },
                        modifier = Modifier.rememberFocus(memory, "cat-$id", isDefault = id == selected && memory.focusedKey == null),
                    )
                }
            }
        }

        // Sender
        Column(Modifier.weight(1f).fillMaxHeight().padding(top = 32.dp)) {
            val title = categories.firstOrNull { it.first == selected }?.second ?: ""
            Text(
                "$title · ${channels.size} Sender",
                fontSize = 20.sp,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(start = 32.dp, bottom = 12.dp),
            )
            if (channels.isEmpty()) {
                EmptyHint("Keine Sender in dieser Kategorie.")
            } else {
                // Senderlogos der Kategorie vorab laden, damit beim ersten Durchscrollen nichts nachlädt.
                val context = androidx.compose.ui.platform.LocalContext.current
                LaunchedEffect(channels) { LogoPrefetch.run(context, channels) }
                val saved = memory.scroll["channels"]
                val listState = rememberLazyListState(saved?.first ?: 0, saved?.second ?: 0)
                DisposableEffect(selected) {
                    onDispose { memory.scroll["channels"] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 24.dp, end = 40.dp, top = 4.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.focusRestorer(),
                ) {
                    itemsIndexed(channels, key = { i, _ -> i }, contentType = { _, _ -> "channel" }) { index, ch ->
                        ChannelRow(
                            number = index + 1,
                            channel = ch,
                            programme = epg?.current(ch, now),
                            next = epg?.next(ch, now),
                            now = now,
                            onClick = { open(index) },
                            onLongClick = { toggleFavorite(ch.stableKey, ch.name) },
                            isFavorite = ch.stableKey in favorites,
                            isPlaying = preview != null && ch.stableKey == previewKey,
                            compact = preview != null,
                            modifier = Modifier.rememberFocus(memory, "ch-${ch.stableKey}")
                                .onFocusChanged { if (it.hasFocus) focusedChannel = ch },
                        )
                    }
                }
            }
        }
        if (preview != null) {
            // Angezeigt wird der markierte Sender; das Video gehört zu ihm, sobald die Vorschau umgeschaltet hat.
            PreviewPanel(
                preview, focusedChannel, epg, now,
                active = previewKey != null && previewKey == focusedChannel?.stableKey,
                modifier = Modifier.width(340.dp),
            )
        }
    }
}
