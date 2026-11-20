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

    fun live(category: String) = liveByCategory[category].orEmpty()
    fun movies(category: String) = moviesByCategory[category].orEmpty()
    fun shows(category: String) = showsByCategory[category].orEmpty()
    fun channel(key: String) = channelsByKey[key]
    fun show(key: String) = showsByKey[key]

    /** Neueste Filme (Xtream `added`), sonst Playlist-Reihenfolge. */
    val newestMovies: List<Channel> by lazy {
        if (movies.any { it.added != null }) movies.sortedByDescending { it.added ?: 0 }.take(30) else movies.take(30)
    }
    val newestShows: List<SeriesShow> by lazy {
        if (shows.any { it.added != null }) shows.sortedByDescending { it.added ?: 0 }.take(30) else shows.take(30)
    }

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
