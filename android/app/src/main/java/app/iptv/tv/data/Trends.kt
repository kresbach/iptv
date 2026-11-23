package app.iptv.tv.data

import android.content.Context
import android.util.Log
import app.iptv.tv.model.Channel
import app.iptv.tv.model.MediaInfo
import app.iptv.tv.model.SeriesShow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * „Trends“ auf der Startseite: was bei Netflix, Prime Video, Disney+ & Co. gerade viel geschaut wird.
 *
 * Quelle sind die Beliebtheitslisten von TMDB (themoviedb.org, je Dienst für Deutschland). Gezeigt werden
 * nur Titel, die es auch in der eigenen Playlist gibt. Ohne TMDB-Schlüssel werden ersatzweise die
 * Kategorien des Anbieters genutzt (viele heißen „NETFLIX“, „AMAZON PRIME“ …), dort die neuesten Titel.
 */
object Trends {
    private const val TAG = "Trends"
    private const val MAX_AGE = 12 * 3600_000L
    private const val ROW_LIMIT = 30

    data class Provider(
        val id: String,
        val name: String,
        /** Anbieter-Nummer bei TMDB („watch provider“, Region DE). */
        val tmdbId: Int?,
        /** Erkennung in Kategorienamen des IPTV-Anbieters. */
        val keywords: Regex,
    )

    val providers = listOf(
        Provider("netflix", "Netflix", 8, Regex("netflix", RegexOption.IGNORE_CASE)),
        Provider("prime", "Prime Video", 9, Regex("amazon|prime\\s*video|\\bprime\\b", RegexOption.IGNORE_CASE)),
        Provider("disney", "Disney+", 337, Regex("disney", RegexOption.IGNORE_CASE)),
        Provider("apple", "Apple TV+", 350, Regex("apple", RegexOption.IGNORE_CASE)),
        Provider("paramount", "Paramount+", 531, Regex("paramount", RegexOption.IGNORE_CASE)),
        Provider("sky", "Sky / WOW", 30, Regex("\\bsky\\b|\\bwow\\b", RegexOption.IGNORE_CASE)),
        Provider("rtl", "RTL+", 298, Regex("rtl\\s*\\+|rtl\\s*plus|tv\\s*now", RegexOption.IGNORE_CASE)),
        Provider("joyn", "Joyn", 304, Regex("joyn", RegexOption.IGNORE_CASE)),
        Provider("magenta", "MagentaTV", 178, Regex("magenta", RegexOption.IGNORE_CASE)),
    )

    @Serializable
    data class TmdbEntry(
        val title: String,
        val original: String? = null,
        val year: String? = null,
        val poster: String? = null,
        val tv: Boolean = false,
        val popularity: Double = 0.0,
    )

    @Serializable
    data class Cache(val fetchedAt: Long = 0, val lists: Map<String, List<TmdbEntry>> = emptyMap())

    /** Ein Eintrag einer Trend-Reihe: Film oder Serie aus der eigenen Playlist. */
    data class Item(val movie: Channel? = null, val show: SeriesShow? = null, val poster: String? = null) {
        val title: String get() = movie?.name ?: show?.title ?: ""
        val key: String get() = movie?.stableKey ?: show?.favoriteKey ?: title
    }

    data class Row(val id: String, val title: String, val items: List<Item>, val fromTmdb: Boolean)

    val rows = MutableStateFlow<List<Row>>(emptyList())
    val cache = MutableStateFlow(Cache())
    val status = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }
    private var file: File? = null
    private var started = false

    val isConfigured: Boolean get() = Prefs.tmdbKey.isNotBlank()

    fun start(context: Context) {
        if (started) return
        started = true
        file = File(context.filesDir, "trends.json")
        scope.launch(Dispatchers.IO) {
            cache.value = runCatching { json.decodeFromString(Cache.serializer(), file!!.readText()) }.getOrDefault(Cache())
            var first = true
            combine(Repository.visible, cache) { c, t -> c to t }.collectLatest { (c, t) ->
                // Beim Start erst die Startseite fertig aufbauen lassen, dann die Trends berechnen.
                if (first && !c.isEmpty) { first = false; delay(1_500) }
                rows.value = withContext(Dispatchers.Default) { runCatching { build(c, t) }.getOrDefault(emptyList()) }
            }
        }
        scope.launch {
            Repository.restored.first { it }
            delay(15_000)
            refresh(force = false)
        }
    }

    /** Beliebtheitslisten bei TMDB neu laden (höchstens alle 12 Stunden, außer `force`). */
    fun refresh(force: Boolean) {
        if (!isConfigured || loading.value) return
        if (!force && System.currentTimeMillis() - cache.value.fetchedAt < MAX_AGE) return
        scope.launch(Dispatchers.IO) {
            loading.value = true
            status.value = null
            try {
                // Nicht während ein Stream läuft (Bandbreite).
                while (Repository.playbackActive && !force) delay(10_000)
                val lists = LinkedHashMap<String, List<TmdbEntry>>()
                lists["trending"] = get("trending/all/week?language=de-DE").let { parse(it, tv = null) } +
                    get("trending/all/week?language=de-DE&page=2").let { parse(it, tv = null) }
                for (p in providers) {
                    val id = p.tmdbId ?: continue
                    val base = "watch_region=DE&with_watch_providers=$id&with_watch_monetization_types=flatrate&sort_by=popularity.desc&language=de-DE"
                    val entries = ArrayList<TmdbEntry>()
                    for (page in 1..2) {
                        entries += parse(get("discover/movie?$base&page=$page"), tv = false)
                        entries += parse(get("discover/tv?$base&page=$page"), tv = true)
                    }
                    lists[p.id] = entries.sortedByDescending { it.popularity }
                }
                val newCache = Cache(System.currentTimeMillis(), lists)
                cache.value = newCache
                file?.writeText(json.encodeToString(Cache.serializer(), newCache))
            } catch (e: Exception) {
                Log.w(TAG, "TMDB", e)
                status.value = Http.message(e)
            } finally {
                loading.value = false
            }
        }
    }

    internal fun get(path: String): String {
        val key = Prefs.tmdbKey.trim()
        // Lesezugriffs-Token (v4, beginnt mit „eyJ“) oder klassischer API-Schlüssel (v3)
        val bearer = key.startsWith("eyJ")
        val url = "https://api.themoviedb.org/3/$path" + if (bearer) "" else "&api_key=$key"
        val request = Request.Builder().url(url.replaceFirst("?&", "?"))
            .apply { if (bearer) header("Authorization", "Bearer $key") }
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { r ->
            val body = r.body?.string() ?: ""
            if (r.code == 401) throw UserFacingException("TMDB lehnt den Schlüssel ab. Bitte unter Einstellungen → Trends prüfen.")
            if (!r.isSuccessful) throw UserFacingException("TMDB meldet Fehler ${r.code}.")
            return body
        }
    }

    /** TMDB-Antwort (`results`) lesen. `tv = null`: gemischte Liste (trending/all). */
    fun parse(body: String, tv: Boolean?): List<TmdbEntry> {
        val results = (json.parseToJsonElement(body) as? JsonObject)?.get("results") as? JsonArray ?: return emptyList()
        return results.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val isTv = tv ?: when (o.str("media_type")) {
                "tv" -> true
                "movie" -> false
                else -> return@mapNotNull null
            }
            val title = (if (isTv) o.str("name") else o.str("title")) ?: return@mapNotNull null
            TmdbEntry(
                title = title,
                original = if (isTv) o.str("original_name") else o.str("original_title"),
                year = (if (isTv) o.str("first_air_date") else o.str("release_date"))?.take(4)?.takeIf { it.length == 4 },
                poster = o.str("poster_path")?.let { "https://image.tmdb.org/t/p/w342$it" },
                tv = isTv,
                popularity = o.str("popularity")?.toDoubleOrNull() ?: 0.0,
            )
        }
    }

    private fun norm(title: String) = MediaInfo.versionKey(title).first

    /** Reihen aus Playlist + TMDB-Listen bauen (ohne Netzwerk, testbar). */
    fun build(content: LibraryContent, cache: Cache): List<Row> {
        if (content.movies.isEmpty() && content.shows.isEmpty()) return emptyList()
        // Titel-Index nur aufbauen, wenn es TMDB-Listen gibt (teuer bei großen Playlists).
        val moviesByTitle by lazy {
            HashMap<String, Channel>().also { m -> for (c in content.movies) m.putIfAbsent(norm(c.name), c) }
        }
        val showsByTitle by lazy {
            HashMap<String, SeriesShow>().also { m -> for (x in content.shows) m.putIfAbsent(norm(x.title), x) }
        }

        fun match(list: List<TmdbEntry>): List<Item> {
            val seen = HashSet<String>()
            val out = ArrayList<Item>()
            for (e in list) {
                val names = listOfNotNull(e.title, e.original).map(::norm).filter { it.isNotEmpty() }
                val item = if (e.tv) {
                    names.firstNotNullOfOrNull { showsByTitle[it] }?.let { Item(show = it, poster = it.poster ?: e.poster) }
                } else {
                    names.firstNotNullOfOrNull { moviesByTitle[it] }?.let { Item(movie = it, poster = it.logo ?: e.poster) }
                } ?: continue
                if (seen.add(item.key)) out += item
                if (out.size >= ROW_LIMIT) break
            }
            return out
        }

        fun fromCategories(p: Provider): List<Item> {
            val movieCats = content.movieCategories.map { it.name }.filter { p.keywords.containsMatchIn(it) }
            val showCats = content.showCategories.map { it.name }.filter { p.keywords.containsMatchIn(it) }
            val movies = movieCats.flatMap { content.movies(it) }.sortedByDescending { it.added ?: 0 }.map { Item(movie = it, poster = it.logo) }
            val shows = showCats.flatMap { content.shows(it) }.sortedByDescending { it.added ?: 0 }.map { Item(show = it, poster = it.poster) }
            // Filme und Serien abwechselnd
            val mixed = ArrayList<Item>()
            val a = movies.iterator()
            val b = shows.iterator()
            while ((a.hasNext() || b.hasNext()) && mixed.size < ROW_LIMIT) {
                if (a.hasNext()) mixed += a.next()
                if (b.hasNext() && mixed.size < ROW_LIMIT) mixed += b.next()
            }
            return mixed
        }

        val rows = ArrayList<Row>()
        cache.lists["trending"]?.let { match(it) }?.takeIf { it.isNotEmpty() }?.let {
            rows += Row("trending", "Trends in Deutschland", it, fromTmdb = true)
        }
        for (p in providers) {
            val tmdb = cache.lists[p.id]?.let { match(it) } ?: emptyList()
            val items = if (tmdb.size >= 6) tmdb else {
                // Zu wenige Treffer: mit den Kategorien des Anbieters auffüllen
                val keys = tmdb.map { it.key }.toHashSet()
                tmdb + fromCategories(p).filter { keys.add(it.key) }
            }.take(ROW_LIMIT)
            if (items.isNotEmpty()) rows += Row(p.id, p.name, items, fromTmdb = tmdb.isNotEmpty())
        }
        return rows
    }

    suspend fun refreshNow() = withContext(Dispatchers.Default) { refresh(force = true) }
}
