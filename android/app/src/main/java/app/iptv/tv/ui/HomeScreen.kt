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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.iptv.tv.data.LibraryContent
import app.iptv.tv.data.Repository
import app.iptv.tv.model.ContentKind
import kotlinx.coroutines.delay

@Composable
fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        // Zur vollen Minute aktualisieren (Anzeigen sind minutengenau) – halb so oft wie vorher
        // und trotzdem pünktlich, wenn die nächste Sendung beginnt.
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
            value = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun HomeScreen(nav: Navigator, memory: ScreenMemory) {
    val restored by Repository.restored.collectAsState()
    val playlists by Repository.playlists.collectAsState()
    val content by Repository.visible.collectAsState()
    val loading by Repository.isLoading.collectAsState()
    val reading by Repository.contentLoading.collectAsState()
    val status by Repository.statusMessage.collectAsState()
    remember { memory.restored = false; 0 }

    when {
        !restored -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
        playlists.isEmpty() -> WelcomeScreen(nav, memory)
        content.isEmpty && loading && !reading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spinner()
                Spacer(Modifier.height(16.dp))
                Text("Playlist wird geladen …", fontSize = 20.sp, color = AppColors.TextPrimary)
            }
        }
        else -> HomeContent(nav, memory, content, loading || reading, status)
    }
}

@Composable
private fun WelcomeScreen(nav: Navigator, memory: ScreenMemory) {
    Column(
        Modifier.fillMaxSize().padding(64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Logo(big = true)
        Spacer(Modifier.height(24.dp))
        Text(
            "Willkommen! Füge deine erste Playlist hinzu –\nper M3U-Link oder mit deinem Xtream-Codes-Zugang.",
            fontSize = 22.sp,
            textAlign = TextAlign.Center,
            color = AppColors.TextSecondary,
        )
        Spacer(Modifier.height(32.dp))
        AppButton(
            "Playlist hinzufügen",
            onClick = { nav.push(Screen.AddPlaylist) },
            icon = AppIcons.Add,
            modifier = Modifier.rememberFocus(memory, "add", isDefault = true),
        )
    }
}

@Composable
fun Logo(big: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(if (big) 64.dp else 40.dp)
                .background(AppColors.Gradient, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.tv.material3.Icon(AppIcons.Play, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(if (big) 40.dp else 26.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text("IPTV", fontSize = if (big) 44.sp else 28.sp, fontWeight = FontWeight.Black, color = androidx.compose.ui.graphics.Color.White)
    }
}

@Composable
private fun HomeContent(
    nav: Navigator,
    memory: ScreenMemory,
    content: LibraryContent,
    loading: Boolean,
    status: String?,
) {
    val epg by Repository.epg.collectAsState()
    val favorites by Repository.favorites.collectAsState()
    val recents by Repository.recents.collectAsState()
    val progress by Repository.progress.collectAsState()
    val toggleFavorite = rememberFavoriteToggle()
    val now = rememberNow()
    val active = Repository.activePlaylist
    val playlistCount = Repository.playlists.collectAsState().value.size
    val downloadCount = app.iptv.tv.data.Downloads.items.collectAsState().value.size
    val continueList = remember(progress) { Repository.continueWatching(progress).take(20) }
    val trendRows by app.iptv.tv.data.Trends.rows.collectAsState()
    val update by app.iptv.tv.data.AppUpdate.state.collectAsState()
    val newEpisodes by Repository.newEpisodes.collectAsState()
    app.iptv.tv.data.Profiles.activeId.collectAsState().value
    val profileCount = app.iptv.tv.data.Profiles.list.collectAsState().value.size
    val context = androidx.compose.ui.platform.LocalContext.current

    val saved = memory.scroll["home"]
    val listState = rememberLazyListState(saved?.first ?: 0, saved?.second ?: 0)
    DisposableEffect(Unit) {
        onDispose { memory.scroll["home"] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
    }

    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 48.dp), modifier = Modifier.fillMaxSize()) {
        item("header") {
            Row(
                Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Logo()
                Spacer(Modifier.width(20.dp))
                Text(active?.name ?: "", fontSize = 18.sp, color = AppColors.TextSecondary, modifier = Modifier.weight(1f))
                if (loading) {
                    Spinner(size = 24.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Aktualisiere …", fontSize = 15.sp, color = AppColors.TextSecondary)
                } else if (status != null) {
                    Text(status, fontSize = 15.sp, color = AppColors.OrangeLight, maxLines = 1)
                }
            }
        }

        homeRow("tiles", null, memory) {
            val u = update
            if (u is app.iptv.tv.data.AppUpdate.State.Available || u is app.iptv.tv.data.AppUpdate.State.Downloading) {
                item("update") {
                    val info = (u as? app.iptv.tv.data.AppUpdate.State.Available)?.info ?: (u as app.iptv.tv.data.AppUpdate.State.Downloading).info
                    TileCard(
                        "Update ${info.versionName}",
                        if (u is app.iptv.tv.data.AppUpdate.State.Downloading) "Lädt ${u.percent} %" else "Jetzt installieren",
                        AppIcons.Download,
                        onClick = { app.iptv.tv.data.AppUpdate.downloadAndInstall(context, info) },
                        modifier = Modifier.rememberFocus(memory, "tile-update"),
                    )
                }
            }
            if (profileCount > 1) item("profile") {
                TileCard(
                    "Profil", app.iptv.tv.data.Profiles.active.name, AppIcons.Star,
                    onClick = { nav.push(Screen.Profiles) },
                    modifier = Modifier.rememberFocus(memory, "tile-profile"),
                    highlighted = false,
                )
            }
            item("search") {
                TileCard(
                    "Suche", "Sender, Filme, Serien", AppIcons.Search,
                    onClick = { nav.push(Screen.Search) },
                    modifier = Modifier.rememberFocus(memory, "tile-search"),
                    highlighted = false,
                )
            }
            item("live") {
                TileCard(
                    "Live TV", if (content.isEmpty && loading) "…" else "${content.live.size} Sender", AppIcons.Tv,
                    onClick = { nav.push(Screen.Live()) },
                    modifier = Modifier.rememberFocus(memory, "tile-live", isDefault = true),
                )
            }
            item("movies") {
                TileCard(
                    "Filme", if (content.isEmpty && loading) "…" else "${content.movies.size} Filme", AppIcons.Movie,
                    onClick = { nav.push(Screen.Grid(ContentKind.MOVIE)) },
                    modifier = Modifier.rememberFocus(memory, "tile-movies"),
                )
            }
            item("series") {
                TileCard(
                    "Serien", if (content.isEmpty && loading) "…" else "${content.shows.size} Serien", AppIcons.Series,
                    onClick = { nav.push(Screen.Grid(ContentKind.SERIES)) },
                    modifier = Modifier.rememberFocus(memory, "tile-series"),
                )
            }
            item("epg") {
                TileCard(
                    "TV-Programm", "Raster", AppIcons.History,
                    onClick = { nav.push(Screen.EpgGrid) },
                    modifier = Modifier.rememberFocus(memory, "tile-epg"),
                    highlighted = false,
                )
            }
            if (downloadCount > 0) {
                item("downloads") {
                    TileCard(
                        "Downloads", "$downloadCount Einträge", AppIcons.Download,
                        onClick = { nav.push(Screen.Downloads) },
                        modifier = Modifier.rememberFocus(memory, "tile-downloads"),
                        highlighted = false,
                    )
                }
            }
            item("settings") {
                TileCard(
                    "Einstellungen", "${playlistCount} Playlist(s)", AppIcons.Settings,
                    onClick = { nav.push(Screen.Settings) },
                    modifier = Modifier.rememberFocus(memory, "tile-settings"),
                    highlighted = false,
                )
            }
        }

        if (continueList.isNotEmpty()) {
            homeRow("continue", "Weiterschauen", memory) {
                itemsIndexed(continueList, key = { i, _ -> "c-$i" }) { _, p ->
                    val ch = p.channel!!
                    PosterCard(
                        title = ch.name,
                        image = ch.logo ?: p.showKey?.let { content.show(it)?.poster },
                        initials = ch.initials,
                        progress = p.position.toFloat() / p.duration,
                        onClick = { nav.push(Screen.Player(listOf(ch), 0, p.position, p.showKey)) },
                        onLongClick = {
                            val show = p.showKey?.let { content.show(it) }
                            if (show != null) nav.push(Screen.SeriesDetail(show))
                            else if (ch.kind == ContentKind.MOVIE) nav.push(Screen.MovieDetail(ch))
                        },
                        modifier = Modifier.rememberFocus(memory, "cont-${ch.stableKey}"),
                    )
                }
            }
        }

        // „Zuletzt gesehen“: nur Live-Sender (Filme und Serien stehen unter „Weiterschauen“).
        val recentLive = recents.filter { it.channel.kind == ContentKind.LIVE }.take(20)
        if (recentLive.isNotEmpty()) {
            homeRow("recents", "Zuletzt gesehen", memory) {
                itemsIndexed(recentLive, key = { i, _ -> "r-$i" }) { _, entry ->
                    val ch = entry.channel
                    ChannelCard(
                        ch, epg?.current(ch, now), now,
                        onClick = {
                            val list = content.live.ifEmpty { listOf(ch) }
                            val idx = list.indexOfFirst { it.stableKey == ch.stableKey }
                            nav.push(if (idx >= 0) Screen.Player(list, idx) else Screen.Player(listOf(ch), 0))
                        },
                        onLongClick = { toggleFavorite(ch.stableKey, ch.name) },
                        isFavorite = ch.stableKey in favorites,
                        modifier = Modifier.rememberFocus(memory, "recent-${ch.stableKey}"),
                    )
                }
            }
        }

        val newShows = newEpisodes.mapNotNull { content.show(it) }
        if (newShows.isNotEmpty()) {
            homeRow("newEpisodes", "Neue Folgen", memory) {
                itemsIndexed(newShows, key = { i, _ -> "n-$i" }) { _, show ->
                    PosterCard(
                        title = "NEU · ${show.title}",
                        image = show.poster,
                        initials = show.title.take(2),
                        onClick = { nav.push(Screen.SeriesDetail(show)) },
                        onLongClick = { toggleFavorite(show.favoriteKey, show.title) },
                        isFavorite = true,
                        modifier = Modifier.rememberFocus(memory, "new-${show.favoriteKey}"),
                    )
                }
            }
        }

        val favChannels = favorites.mapNotNull { content.channel(it) }
        val favShows = favorites.mapNotNull { content.show(it) }
        if (favChannels.isNotEmpty() || favShows.isNotEmpty()) {
            homeRow("favorites", "Favoriten", memory) {
                itemsIndexed(favChannels, key = { i, _ -> "f-$i" }) { _, ch ->
                    val key = "fav-${ch.stableKey}"
                    if (ch.kind == ContentKind.LIVE) {
                        ChannelCard(
                            ch, epg?.current(ch, now), now,
                            onClick = {
                                val list = favChannels.filter { it.kind == ContentKind.LIVE }
                                nav.push(Screen.Player(list, list.indexOf(ch).coerceAtLeast(0)))
                            },
                            onLongClick = { toggleFavorite(ch.stableKey, ch.name) },
                            isFavorite = true,
                            modifier = Modifier.rememberFocus(memory, key),
                        )
                    } else {
                        PosterCard(
                            ch.name, ch.logo, initials = ch.initials,
                            onClick = { nav.push(Screen.MovieDetail(ch)) },
                            onLongClick = { toggleFavorite(ch.stableKey, ch.name) },
                            isFavorite = true,
                            modifier = Modifier.rememberFocus(memory, key),
                        )
                    }
                }
                itemsIndexed(favShows, key = { i, _ -> "fs-$i" }) { _, show ->
                    PosterCard(
                        show.title, show.poster, initials = show.title.take(2).uppercase(),
                        onClick = { nav.push(Screen.SeriesDetail(show)) },
                        onLongClick = { toggleFavorite(show.favoriteKey, show.title) },
                        isFavorite = true,
                        modifier = Modifier.rememberFocus(memory, "fav-${show.favoriteKey}"),
                    )
                }
            }
        }

        // Trends: was bei den Streamingdiensten gerade viel geschaut wird (aus der eigenen Playlist).
        item("trends-title") {
            Row(Modifier.padding(start = 40.dp, top = 22.dp, end = 40.dp), verticalAlignment = Alignment.Bottom) {
                Text("Trends", fontSize = 24.sp, fontWeight = FontWeight.Black, color = AppColors.Orange)
                Spacer(Modifier.width(14.dp))
                Text(
                    when {
                        trendRows.isEmpty() && !app.iptv.tv.data.Trends.isConfigured ->
                            "Für echte Trends von Netflix, Prime Video & Co.: Einstellungen → Trends (kostenloser TMDB-Schlüssel)"
                        trendRows.any { it.fromTmdb } -> "Beliebt diese Woche – nur Titel aus deiner Playlist"
                        app.iptv.tv.data.Trends.isConfigured -> "Wird geladen … bis dahin: neueste Titel aus den Kategorien deines Anbieters"
                        else -> "Neueste Titel aus den Kategorien deines Anbieters · für echte Trends: Einstellungen → Trends"
                    },
                    fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        trendRows.forEach { row ->
            homeRow("trend-${row.id}", row.title, memory) {
                itemsIndexed(row.items, key = { i, _ -> "t-${row.id}-$i" }) { _, item ->
                    val movie = item.movie
                    val show = item.show
                    PosterCard(
                        item.title, item.poster, initials = item.title.take(2).uppercase(),
                        onClick = {
                            if (movie != null) nav.push(Screen.MovieDetail(movie))
                            else if (show != null) nav.push(Screen.SeriesDetail(show))
                        },
                        onLongClick = { toggleFavorite(item.key, item.title) },
                        isFavorite = item.key in favorites,
                        progress = movie?.let { m -> progress[m.stableKey]?.let { it.position.toFloat() / it.duration } },
                        modifier = Modifier.rememberFocus(memory, "trend-${row.id}-${item.key}"),
                    )
                }
            }
        }

        item("hint") {
            Text(
                "Tipp: OK lange drücken = Favorit hinzufügen/entfernen · bei „Weiterschauen“ = Details öffnen",
                modifier = Modifier.padding(start = 48.dp, top = 24.dp),
                fontSize = 14.sp,
                color = AppColors.TextSecondary,
            )
        }
    }
}

/** Eine horizontale Reihe mit Titel; die Scrollposition bleibt beim Zurückkehren erhalten. */
private fun LazyListScope.homeRow(
    id: String,
    title: String?,
    memory: ScreenMemory,
    content: LazyListScope.() -> Unit,
) {
    item(id) {
        Column {
            if (title != null) SectionTitle(title) else Spacer(Modifier.height(14.dp))
            val saved = memory.scroll[id]
            val state = rememberLazyListState(saved?.first ?: 0, saved?.second ?: 0)
            DisposableEffect(Unit) {
                onDispose { memory.scroll[id] = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            }
            LazyRow(
                state = state,
                contentPadding = PaddingValues(horizontal = 40.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }
}
