package app.iptv.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.iptv.tv.data.Repository
import app.iptv.tv.model.ContentKind
import app.iptv.tv.model.Episode
import app.iptv.tv.model.MediaInfo
import coil.compose.AsyncImage

/** Raster mit Covern (Filme oder Serien), Kategorien links. */
@Composable
fun GridScreen(nav: Navigator, screen: Screen.Grid) {
    val memory = screen.memory
    val content by Repository.visible.collectAsState()
    val catVersion = app.iptv.tv.data.CategoryPrefs.version.collectAsState().value
    val favorites by Repository.favorites.collectAsState()
    val progress by Repository.progress.collectAsState()
    val toggleFavorite = rememberFavoriteToggle()
    val isMovies = screen.kind == ContentKind.MOVIE
    remember { memory.restored = false; 0 }

    val categories = remember(content, favorites, catVersion) {
        val cats = app.iptv.tv.data.CategoryPrefs.arranged(
            if (isMovies) content.movieCategories else content.showCategories,
            screen.kind,
        )
        val hasFav = if (isMovies) content.movies.any { it.stableKey in favorites } else content.shows.any { it.favoriteKey in favorites }
        buildList {
            if (hasFav) add(Triple(FAVORITES, "★ Favoriten", null as Int?))
            add(Triple(ALL, if (isMovies) "Alle Filme" else "Alle Serien", if (isMovies) content.movies.size else content.shows.size))
            cats.forEach { add(Triple(it.name, it.name, it.count)) }
            if (app.iptv.tv.data.CategoryPrefs.hasLocked(screen.kind) && !app.iptv.tv.data.CategoryPrefs.unlocked.value) add(Triple(UNLOCK, "🔒 Gesperrte freischalten", null))
        }
    }
    var selected by remember { mutableStateOf(memory.selection ?: ALL) }

    // Gemerkte Kategorie gibt es nicht mehr (ausgeblendet, gesperrt, letzte Favorit entfernt) → „Alle“.
    LaunchedEffect(categories) {
        if (categories.none { it.first == selected }) { selected = ALL; memory.selection = null }
    }

    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(260.dp).fillMaxHeight().background(AppColors.Surface.copy(alpha = 0.5f)).padding(top = 32.dp),
        ) {
            Text(
                if (isMovies) "Filme" else "Serien",
                fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 28.dp, bottom = 12.dp),
            )
            val catState = rememberLazyListState(memory.scroll["cats"]?.first ?: 0)
            DisposableEffect(Unit) { onDispose { memory.scroll["cats"] = catState.firstVisibleItemIndex to 0 } }
            LazyColumn(
                state = catState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.focusRestorer(),
            ) {
                items(categories, key = { it.first }) { (id, title, count) ->
                    CategoryItem(
                        title, count, id == selected,
                        onFocus = {
                            if (selected != id && id != UNLOCK) {
                                selected = id
                                memory.selection = id
                                memory.scroll.remove("grid")
                            }
                        },
                        onClick = { if (id == UNLOCK) nav.push(Screen.Unlock) else selected = id },
                        modifier = Modifier.rememberFocus(memory, "cat-$id", isDefault = id == selected && memory.focusedKey == null),
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxHeight().padding(top = 24.dp)) {
            val saved = memory.scroll["grid"]
            val gridState = rememberLazyGridState(saved?.first ?: 0, saved?.second ?: 0)
            DisposableEffect(selected) {
                onDispose { memory.scroll["grid"] = gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            }
            if (isMovies) {
                val movies = remember(content, selected, favorites) {
                    when (selected) {
                        ALL -> content.movies
                        FAVORITES -> content.movies.filter { it.stableKey in favorites }
                        else -> content.movies(selected)
                    }
                }
                if (movies.isEmpty()) EmptyHint("Keine Filme in dieser Kategorie.")
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    state = gridState,
                    contentPadding = PaddingValues(start = 28.dp, end = 40.dp, top = 12.dp, bottom = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.focusRestorer(),
                ) {
                    itemsIndexed(movies, key = { i, _ -> i }) { _, m ->
                        PosterCard(
                            m.name, m.logo, initials = m.initials,
                            onClick = { nav.push(Screen.MovieDetail(m)) },
                            onLongClick = { toggleFavorite(m.stableKey, m.name) },
                            isFavorite = m.stableKey in favorites,
                            progress = progress[m.stableKey]?.let { it.position.toFloat() / it.duration },
                            modifier = Modifier.rememberFocus(memory, "m-${m.stableKey}"),
                        )
                    }
                }
            } else {
                val shows = remember(content, selected, favorites) {
                    when (selected) {
                        ALL -> content.shows
                        FAVORITES -> content.shows.filter { it.favoriteKey in favorites }
                        else -> content.shows(selected)
                    }
                }
                if (shows.isEmpty()) EmptyHint("Keine Serien in dieser Kategorie.")
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    state = gridState,
                    contentPadding = PaddingValues(start = 28.dp, end = 40.dp, top = 12.dp, bottom = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.focusRestorer(),
                ) {
                    itemsIndexed(shows, key = { i, _ -> i }) { _, s ->
                        PosterCard(
                            s.title, s.poster, initials = s.title.take(2).uppercase(),
                            onClick = { nav.push(Screen.SeriesDetail(s)) },
                            onLongClick = { toggleFavorite(s.favoriteKey, s.title) },
                            isFavorite = s.favoriteKey in favorites,
                            modifier = Modifier.rememberFocus(memory, "s-${s.favoriteKey}"),
                        )
                    }
                }
            }
        }
    }
}

/** Hintergrund mit Bild und Abdunklung für Detailseiten. */
@Composable
private fun Backdrop(image: String?) {
    if (image.isNullOrBlank()) return
    AsyncImage(
        model = image, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(), alpha = 0.35f,
    )
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(listOf(AppColors.Background, AppColors.Background.copy(alpha = 0.7f), Color.Transparent)),
        ),
    )
}

@Composable
private fun InfoLine(info: MediaInfo?) {
    val parts = listOfNotNull(
        info?.year, info?.durationText, info?.genre,
        info?.rating?.let { "★ %.1f".format(it) },
    )
    if (parts.isNotEmpty()) {
        Text(parts.joinToString("  ·  "), fontSize = 17.sp, color = AppColors.OrangeLight, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun MovieDetailScreen(nav: Navigator, screen: Screen.MovieDetail) {
    val movie = screen.movie
    val memory = screen.memory
    val favorites by Repository.favorites.collectAsState()
    val progress by Repository.progress.collectAsState()
    var info by remember { mutableStateOf<MediaInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    val toggleFavorite = rememberFavoriteToggle()
    remember { memory.restored = false; 0 }
    LaunchedEffect(movie) {
        info = Repository.movieInfo(movie)
        loading = false
    }
    val p = progress[movie.stableKey]
    val context = androidx.compose.ui.platform.LocalContext.current
    val downloads by app.iptv.tv.data.Downloads.items.collectAsState()
    val download = downloads.firstOrNull { it.key == movie.stableKey }
    val all by Repository.content.collectAsState()
    // Im Hintergrund suchen: bei sehr großen Filmlisten sonst spürbar langsam.
    val versions by androidx.compose.runtime.produceState(emptyList<app.iptv.tv.model.Channel>(), movie, all) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { MediaInfo.versions(movie, all.movies).take(8) }
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(info?.backdrop)
        Row(Modifier.fillMaxSize().padding(56.dp)) {
            Box(
                Modifier.width(260.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(AppColors.Surface),
            ) {
                RemoteImage(info?.poster ?: movie.logo, movie.initials, Modifier.fillMaxSize(), ContentScale.Crop)
            }
            Spacer(Modifier.width(44.dp))
            Column(Modifier.weight(1f)) {
                Text(movie.name, fontSize = 36.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                InfoLine(info)
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (p != null) {
                        AppButton(
                            "Weiterschauen (${formatTime(p.position)})",
                            { nav.push(Screen.Player(listOf(movie), 0, p.position)) },
                            icon = AppIcons.Play,
                            modifier = Modifier.rememberFocus(memory, "resume", isDefault = true),
                        )
                        AppButton("Von vorne", { nav.push(Screen.Player(listOf(movie), 0, 0)) }, primary = false,
                            modifier = Modifier.rememberFocus(memory, "start"))
                    } else {
                        AppButton(
                            "Abspielen", { nav.push(Screen.Player(listOf(movie), 0, 0)) }, icon = AppIcons.Play,
                            modifier = Modifier.rememberFocus(memory, "play", isDefault = true),
                        )
                    }
                    val fav = movie.stableKey in favorites
                    AppButton(
                        if (fav) "Favorit ✓" else "Zu Favoriten", { toggleFavorite(movie.stableKey, movie.name) },
                        icon = AppIcons.Star, primary = false, modifier = Modifier.rememberFocus(memory, "fav"),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppButton("Trailer", { openTrailer(context, info?.trailerUrl, movie.name, info?.year) }, primary = false,
                        modifier = Modifier.rememberFocus(memory, "trailer"))
                    AppButton(
                        when {
                            download == null -> "Herunterladen"
                            download.state == app.iptv.tv.data.DownloadState.COMPLETED -> "Download löschen"
                            else -> "Download abbrechen"
                        },
                        {
                            if (download == null) app.iptv.tv.data.Downloads.enqueue(movie, movie.name, movie.category, info?.poster ?: movie.logo)
                            else app.iptv.tv.data.Downloads.remove(download)
                        },
                        icon = AppIcons.Download, primary = false, modifier = Modifier.rememberFocus(memory, "dl"),
                    )
                    download?.let { Text(downloadStatus(it), fontSize = 14.sp, color = AppColors.OrangeLight) }
                }
                if (versions.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text("Weitere Fassungen", fontSize = 15.sp, color = AppColors.TextSecondary)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                        items(versions.size) { i ->
                            val v = versions[i]
                            val label = if (v.name == movie.name) "${v.name} · ${v.category} · ${app.iptv.tv.data.UrlTools.extension(v.url).uppercase()}" else v.name
                            SmallButton(label, Modifier.rememberFocus(memory, "v-${v.url}")) { nav.replace(Screen.MovieDetail(v)) }
                        }
                    }
                }
                if (p != null) {
                    Spacer(Modifier.height(14.dp))
                    ProgressLine(p.position.toFloat() / p.duration, Modifier.width(420.dp))
                }
                Spacer(Modifier.height(28.dp))
                when {
                    loading -> Spinner(size = 32.dp)
                    info?.plot != null -> Text(info!!.plot!!, fontSize = 19.sp, lineHeight = 28.sp, color = AppColors.TextPrimary, maxLines = 8, overflow = TextOverflow.Ellipsis)
                    else -> Text(movie.category, fontSize = 17.sp, color = AppColors.TextSecondary)
                }
                info?.director?.let { Text("Regie: $it", fontSize = 16.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 16.dp)) }
                info?.cast?.let { Text("Mit: $it", fontSize = 16.sp, color = AppColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
            }
        }
    }
}

@Composable
fun SeriesDetailScreen(nav: Navigator, screen: Screen.SeriesDetail) {
    val show = screen.show
    val memory = screen.memory
    val favorites by Repository.favorites.collectAsState()
    val progress by Repository.progress.collectAsState()
    val toggleFavorite = rememberFavoriteToggle()
    var info by remember { mutableStateOf<MediaInfo?>(null) }
    var episodes by remember { mutableStateOf(show.episodes) }
    var loading by remember { mutableStateOf(show.needsEpisodeLoading) }
    var error by remember { mutableStateOf<String?>(null) }
    val downloads by app.iptv.tv.data.Downloads.items.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    remember { memory.restored = false; 0 }

    LaunchedEffect(show) {
        try {
            val (i, eps) = Repository.seriesDetails(show)
            info = i
            if (eps.isNotEmpty()) episodes = eps
        } catch (e: Exception) {
            error = app.iptv.tv.data.Http.message(e)
        }
        loading = false
    }
    val seasons = remember(episodes) { episodes.groupBy { it.season ?: 0 }.toSortedMap().toList() }
    // Staffel der zuletzt gesehenen Episode vorauswählen.
    var season by remember(seasons) {
        val recentKeys = Repository.recents.value.filter { it.showKey == show.favoriteKey }.map { it.channel.stableKey }
        val last = episodes.firstOrNull { it.channel.stableKey in recentKeys.take(1) }
        mutableStateOf(memory.selection?.toIntOrNull() ?: last?.season ?: seasons.firstOrNull()?.first ?: 0)
    }
    val seasonEpisodes = seasons.firstOrNull { it.first == season }?.second ?: emptyList()

    fun play(list: List<Episode>, index: Int) {
        val ep = list[index]
        val pos = progress[ep.channel.stableKey]?.position ?: 0
        nav.push(Screen.Player(list.map { it.channel }, index, pos, show.favoriteKey))
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(info?.backdrop)
        Row(Modifier.fillMaxSize().padding(start = 56.dp, top = 48.dp, end = 40.dp)) {
            Column(Modifier.width(200.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(AppColors.Surface)) {
                    RemoteImage(info?.poster ?: show.poster, show.title.take(2).uppercase(), Modifier.fillMaxSize(), ContentScale.Crop)
                }
                Spacer(Modifier.height(18.dp))
                val fav = show.favoriteKey in favorites
                AppButton(
                    if (fav) "Favorit ✓" else "Zu Favoriten", { toggleFavorite(show.favoriteKey, show.title) },
                    icon = AppIcons.Star, primary = false, modifier = Modifier.rememberFocus(memory, "fav"),
                )
                Spacer(Modifier.height(10.dp))
                AppButton("Trailer", { openTrailer(context, info?.trailerUrl, show.title, info?.year) }, primary = false)
            }
            Spacer(Modifier.width(40.dp))
            Column(Modifier.weight(1f)) {
                Text(show.title, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                InfoLine(info)
                (info?.plot ?: show.plot)?.let {
                    Text(it, fontSize = 17.sp, lineHeight = 25.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
                }
                Spacer(Modifier.height(18.dp))
                when {
                    loading -> Spinner(size = 36.dp)
                    error != null -> Text("Episoden konnten nicht geladen werden: $error", color = AppColors.OrangeLight, fontSize = 17.sp)
                    episodes.isEmpty() -> Text("Keine Episoden gefunden.", color = AppColors.TextSecondary, fontSize = 17.sp)
                    else -> {
                        if (seasons.size > 1) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.focusRestorer()) {
                                items(seasons, key = { it.first }) { (number, _) ->
                                    CategoryItem(
                                        name = if (number == 0) "Episoden" else "Staffel $number",
                                        count = null,
                                        selected = number == season,
                                        onFocus = { season = number; memory.selection = "$number" },
                                        onClick = { season = number },
                                        modifier = Modifier.width(150.dp).rememberFocus(memory, "season-$number"),
                                    )
                                }
                            }
                        }
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp, end = 8.dp),
                            modifier = Modifier.focusRestorer(),
                        ) {
                            itemsIndexed(seasonEpisodes, key = { i, _ -> i }) { index, ep ->
                                val p = progress[ep.channel.stableKey]
                                val dl = downloads.firstOrNull { it.key == ep.channel.stableKey }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FocusSurface(
                                    onClick = { play(seasonEpisodes, index) },
                                    modifier = Modifier.weight(1f).rememberFocus(memory, "ep-${ep.channel.url}", isDefault = index == 0),
                                    scale = 1.02f,
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(ep.displayTitle, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            ep.plot?.takeIf { it.isNotBlank() }?.let {
                                                Text(it, fontSize = 14.sp, color = AppColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            }
                                            if (p != null) {
                                                Spacer(Modifier.height(6.dp))
                                                ProgressLine(p.position.toFloat() / p.duration, Modifier.width(300.dp))
                                            }
                                        }
                                        if (p != null) Text("Weiter bei ${formatTime(p.position)}", fontSize = 14.sp, color = AppColors.OrangeLight)
                                        if (dl != null) Text("  ⬇ " + downloadStatus(dl).substringBefore(" ·"), fontSize = 12.sp, color = AppColors.TextSecondary)
                                    }
                                }
                                IconSmallButton(if (dl == null) AppIcons.Download else AppIcons.Delete) {
                                    if (dl == null) app.iptv.tv.data.Downloads.enqueue(
                                        ep.channel, show.title, ep.displayTitle + (ep.season?.let { " · Staffel $it" } ?: ""),
                                        show.poster ?: ep.channel.logo, show.favoriteKey,
                                    ) else app.iptv.tv.data.Downloads.remove(dl)
                                }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}



/** Öffnet den Trailer in der YouTube-App (oder sucht dort nach „Titel Trailer“). */
fun openTrailer(context: android.content.Context, url: String?, title: String, year: String?) {
    val query = listOfNotNull(MediaInfo.cleanTitle(title), year, "Trailer").joinToString(" ")
    val id = url?.let { u -> Regex("(?:v=|youtu\\.be/)([A-Za-z0-9_-]{6,20})").find(u)?.groupValues?.get(1) }
    val intents = buildList {
        if (id != null) {
            add(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$id")))
            add(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$id")))
        }
        for (pkg in listOf("com.google.android.youtube.tv", "com.google.android.youtube")) {
            add(android.content.Intent(android.content.Intent.ACTION_SEARCH).setPackage(pkg).putExtra("query", query))
        }
        add(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(query, "UTF-8"))))
    }
    for (intent in intents) {
        try {
            context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: Exception) {
        }
    }
    android.widget.Toast.makeText(context, "YouTube ist auf diesem Gerät nicht verfügbar.", android.widget.Toast.LENGTH_SHORT).show()
}
