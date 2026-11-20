package app.iptv.tv.data

import app.iptv.tv.model.Channel
import app.iptv.tv.model.ContentKind
import app.iptv.tv.model.PlaylistData
import app.iptv.tv.model.SeriesShow

/** Eine Kategorie samt Anzahl. */
data class Category(val name: String, val count: Int)

/** Die aktive Playlist, aufgeteilt in Live TV, Filme und Serien (einmal berechnet, dann nur gelesen). */
class LibraryContent(val data: PlaylistData) {
    val live: List<Channel>
    val movies: List<Channel>
    val shows: List<SeriesShow>
    val liveCategories: List<Category>
    val movieCategories: List<Category>
    val showCategories: List<Category>
    private val liveByCategory: Map<String, List<Channel>>
    private val moviesByCategory: Map<String, List<Channel>>
    private val showsByCategory: Map<String, List<SeriesShow>>
    private val channelsByKey: Map<String, Channel>
    private val showsByKey: Map<String, SeriesShow>

    init {
        val l = ArrayList<Channel>()
        val m = ArrayList<Channel>()
        val e = ArrayList<Channel>()
        val byKey = HashMap<String, Channel>()
        for (c in data.channels) {
            when (c.kind) {
                ContentKind.LIVE -> l += c
                ContentKind.MOVIE -> m += c
                ContentKind.SERIES -> e += c
            }
            byKey.putIfAbsent(c.stableKey, c)
        }
        live = l
        movies = m
        shows = data.shows + SeriesShow.fromEpisodes(e)
        channelsByKey = byKey
        showsByKey = shows.associateBy { it.favoriteKey }

        val (lc, lg) = group(live) { it.category }
        val (mc, mg) = group(movies) { it.category }
        val (sc, sg) = group(shows) { it.category }
        liveCategories = lc; liveByCategory = lg
        movieCategories = mc; moviesByCategory = mg
        showCategories = sc; showsByCategory = sg
    }

    val isEmpty: Boolean get() = data.channels.isEmpty() && data.shows.isEmpty()

    // Vorbereitete Suchnamen (einmal berechnet, macht jede Suche schnell).
    val searchLive: Array<String> by lazy { Array(live.size) { Search.normalize(live[it].name) } }
    val searchMovies: Array<String> by lazy { Array(movies.size) { Search.normalize(movies[it].name) } }
    val searchShows: Array<String> by lazy { Array(shows.size) { Search.normalize(shows[it].title) } }

    fun live(category: String) = liveByCategory[category].orEmpty()
    fun movies(category: String) = moviesByCategory[category].orEmpty()
    fun shows(category: String) = showsByCategory[category].orEmpty()
    fun channel(key: String) = channelsByKey[key]
    fun show(key: String) = showsByKey[key]

    /** Neueste Filme (Xtream `added`), sonst Playlist-Reihenfolge – im Hintergrund vorberechnet. */
    val newestMovies: List<Channel> =
        if (movies.any { it.added != null }) movies.sortedByDescending { it.added ?: 0 }.take(30) else movies.take(30)
    val newestShows: List<SeriesShow> =
        if (shows.any { it.added != null }) shows.sortedByDescending { it.added ?: 0 }.take(30) else shows.take(30)

    companion object {
        val EMPTY = LibraryContent(PlaylistData())

        /** Gruppiert in Playlist-Reihenfolge, „Ohne Kategorie“ ans Ende. */
        private fun <T> group(items: List<T>, category: (T) -> String): Pair<List<Category>, Map<String, List<T>>> {
            val grouped = LinkedHashMap<String, MutableList<T>>()
            for (item in items) grouped.getOrPut(category(item)) { ArrayList() }.add(item)
            val order = grouped.keys.toMutableList()
            if (order.remove(Channel.UNGROUPED)) order.add(Channel.UNGROUPED)
            return order.map { Category(it, grouped.getValue(it).size) } to grouped
        }
    }
}

/** Suche über Live TV, Filme und Serien (Groß-/Kleinschreibung und Akzente egal, alle Wörter müssen passen). */
object Search {
    data class Results(
        val live: List<app.iptv.tv.model.Channel> = emptyList(),
        val movies: List<app.iptv.tv.model.Channel> = emptyList(),
        val shows: List<SeriesShow> = emptyList(),
    ) {
        val isEmpty get() = live.isEmpty() && movies.isEmpty() && shows.isEmpty()
    }

    private val accents = Regex("\\p{Mn}+")

    fun normalize(text: String): String =
        java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(accents, "")
            .replace("ß", "ss")

    fun search(content: LibraryContent, query: String, limit: Int = 150): Results {
        val words = normalize(query).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return Results()
        fun <T> find(items: List<T>, names: Array<String>): List<T> {
            val starts = ArrayList<T>()
            val others = ArrayList<T>()
            for (i in items.indices) {
                val n = names[i]
                if (words.all { n.contains(it) }) {
                    // Treffer am Wortanfang zuerst
                    if (n.startsWith(words[0]) || n.contains(" " + words[0])) starts += items[i] else others += items[i]
                    if (starts.size >= limit) break
                }
            }
            return (starts + others).take(limit)
        }
        return Results(
            live = find(content.live, content.searchLive),
            movies = find(content.movies, content.searchMovies),
            shows = find(content.shows, content.searchShows),
        )
    }
}
