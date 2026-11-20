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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
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

fun liveChannels(content: LibraryContent, category: String, favorites: Set<String>): List<Channel> = when (category) {
    ALL -> content.live
    FAVORITES -> content.live.filter { it.stableKey in favorites }
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
            if (content.live.any { it.stableKey in favorites }) add(Triple(FAVORITES, "★ Favoriten", null as Int?))
            add(Triple(ALL, "Alle Sender", content.live.size))
            app.iptv.tv.data.CategoryPrefs.arranged(content.liveCategories, app.iptv.tv.model.ContentKind.LIVE)
                .forEach { add(Triple(it.name, it.name, it.count)) }
            if (app.iptv.tv.data.CategoryPrefs.hasLocked && !app.iptv.tv.data.CategoryPrefs.unlocked.value) add(Triple(UNLOCK, "🔒 Gesperrte freischalten", null))
        }
    }
    var selected by remember { mutableStateOf(memory.selection ?: screen.startCategory ?: categories.firstOrNull { it.first != FAVORITES }?.first ?: ALL) }
    val channels = remember(content, selected, favorites) { liveChannels(content, selected, favorites) }

    Row(Modifier.fillMaxSize()) {
        // Kategorien
        Column(
            Modifier
                .width(260.dp)
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
                            if (selected != id && id != UNLOCK) {
                                selected = id
                                memory.selection = id
                                memory.scroll.remove("channels")
                            }
                        },
                        onClick = { if (id == UNLOCK) nav.push(Screen.Unlock) else { selected = id; memory.selection = id } },
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
                    itemsIndexed(channels, key = { i, _ -> i }) { index, ch ->
                        ChannelRow(
                            number = index + 1,
                            channel = ch,
                            programme = epg?.current(ch, now),
                            now = now,
                            onClick = {
                                memory.selection = selected
                                nav.push(Screen.Player(channels, index))
                            },
                            onLongClick = { toggleFavorite(ch.stableKey, ch.name) },
                            isFavorite = ch.stableKey in favorites,
                            modifier = Modifier.rememberFocus(memory, "ch-${ch.stableKey}"),
                        )
                    }
                }
            }
        }
    }
}
