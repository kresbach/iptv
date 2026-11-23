package app.iptv.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.iptv.tv.data.Repository
import app.iptv.tv.data.Search
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Suche über Live TV, Filme und Serien. */
@Composable
fun SearchScreen(nav: Navigator, screen: Screen.Search) {
    val memory = screen.memory
    val content by Repository.visible.collectAsState()
    val epg by Repository.epg.collectAsState()
    val favorites by Repository.favorites.collectAsState()
    val progress by Repository.progress.collectAsState()
    val toggleFavorite = rememberFavoriteToggle()
    val now = rememberNow()
    remember { memory.restored = false; 0 }

    var query by remember { mutableStateOf(memory.selection ?: "") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val speech = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { query = it }
    }
    var results by remember { mutableStateOf(Search.Results()) }
    var searching by remember { mutableStateOf(false) }

    // Kurz nach dem letzten Tastendruck suchen (im Hintergrund, auch bei sehr großen Listen flüssig).
    LaunchedEffect(query, content) {
        memory.selection = query
        if (query.isBlank()) {
            results = Search.Results()
            return@LaunchedEffect
        }
        delay(300)
        searching = true
        results = withContext(Dispatchers.Default) { Search.search(content, query) }
        searching = false
    }

    val saved = memory.scroll["search"]
    val listState = rememberLazyListState(saved?.first ?: 0, saved?.second ?: 0)
    DisposableEffect(Unit) {
        onDispose { memory.scroll["search"] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
    }

    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 48.dp), modifier = Modifier.fillMaxSize()) {
        item("field") {
            Column(Modifier.padding(start = 48.dp, end = 48.dp, top = 32.dp)) {
                Text("Suche", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    TvTextField(
                        query, { query = it },
                        label = "Sender, Film oder Serie (OK drücken zum Tippen – auch per Mikrofon der Fernbedienung)",
                        placeholder = "z. B. Tagesschau, Matrix, Dark …",
                        keyboardType = KeyboardType.Text,
                        modifier = Modifier.width(760.dp).rememberFocus(memory, "field", isDefault = true),
                    )
                    Spacer(Modifier.width(16.dp))
                    AppButton("🎤 Sprechen", {
                        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
                            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Was möchtest du sehen?")
                        try { speech.launch(intent) } catch (_: Exception) {
                            android.widget.Toast.makeText(context, "Spracheingabe ist auf diesem Gerät nicht verfügbar – Mikrofon-Taste der Fernbedienung im Textfeld nutzen.", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }, primary = false, modifier = Modifier.rememberFocus(memory, "mic"))
                    Spacer(Modifier.width(20.dp))
                    if (searching) Spinner(size = 30.dp)
                }
                val hint = when {
                    query.isBlank() -> "Durchsucht Live TV, Filme und Serien der aktiven Playlist."
                    !searching && results.isEmpty -> "Keine Treffer für „$query“."
                    results.isEmpty -> ""
                    else -> "${results.live.size} Sender · ${results.movies.size} Filme · ${results.shows.size} Serien" +
                        "  –  Pfeil nach unten zu den Treffern"
                }
                Text(hint, fontSize = 15.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 10.dp))
            }
        }

        if (results.live.isNotEmpty()) {
            resultRow("live", "Live TV", memory) {
                itemsIndexed(results.live, key = { i, _ -> i }) { i, ch ->
                    ChannelCard(
                        ch, epg?.current(ch, now), now,
                        onClick = { nav.push(Screen.Player(results.live, i)) },
                        onLongClick = { toggleFavorite(ch.stableKey, ch.name) },
                        isFavorite = ch.stableKey in favorites,
                        modifier = Modifier.rememberFocus(memory, "live-$i-${ch.stableKey}"),
                    )
                }
            }
        }
        if (results.movies.isNotEmpty()) {
            resultRow("movies", "Filme", memory) {
                itemsIndexed(results.movies, key = { i, _ -> i }) { i, m ->
                    PosterCard(
                        m.name, m.logo, initials = m.initials,
                        onClick = { nav.push(Screen.MovieDetail(m)) },
                        onLongClick = { toggleFavorite(m.stableKey, m.name) },
                        isFavorite = m.stableKey in favorites,
                        progress = progress[m.stableKey]?.let { it.position.toFloat() / it.duration },
                        modifier = Modifier.rememberFocus(memory, "movie-$i-${m.stableKey}"),
                    )
                }
            }
        }
        if (results.shows.isNotEmpty()) {
            resultRow("shows", "Serien", memory) {
                itemsIndexed(results.shows, key = { i, _ -> i }) { i, s ->
                    PosterCard(
                        s.title, s.poster, initials = s.title.take(2).uppercase(),
                        onClick = { nav.push(Screen.SeriesDetail(s)) },
                        onLongClick = { toggleFavorite(s.favoriteKey, s.title) },
                        isFavorite = s.favoriteKey in favorites,
                        modifier = Modifier.rememberFocus(memory, "show-$i-${s.favoriteKey}"),
                    )
                }
            }
        }
    }
}

private fun LazyListScope.resultRow(id: String, title: String, memory: ScreenMemory, content: LazyListScope.() -> Unit) {
    item(id) {
        Column(Modifier.fillMaxWidth()) {
            SectionTitle(title)
            val saved = memory.scroll[id]
            val state = rememberLazyListState(saved?.first ?: 0, saved?.second ?: 0)
            DisposableEffect(Unit) { onDispose { memory.scroll[id] = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset } }
            LazyRow(
                state = state,
                contentPadding = PaddingValues(horizontal = 40.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }
}
