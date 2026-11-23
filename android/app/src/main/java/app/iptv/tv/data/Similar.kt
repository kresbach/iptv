package app.iptv.tv.data

import app.iptv.tv.model.Channel
import app.iptv.tv.model.MediaInfo
import app.iptv.tv.model.SeriesShow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

/**
 * „Das könnte dir auch gefallen“: Empfehlungen von TMDB (falls eingerichtet), sonst ähnliche Titel
 * aus derselben Kategorie mit ähnlicher Bewertung. Es erscheinen nur Titel aus der eigenen Playlist.
 */
object Similar {
    private const val LIMIT = 20
    private val json = Json { ignoreUnknownKeys = true }
    private val tmdbIds = HashMap<String, Int?>()
    private val trailers = HashMap<String, String?>()

    private fun key(title: String) = MediaInfo.versionKey(title).first

    // region TMDB

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** TMDB-Kennung eines Titels (zwischengespeichert). */
    private fun tmdbId(title: String, year: String?, tv: Boolean): Int? {
        val cacheKey = "$tv|$title|$year"
        if (tmdbIds.containsKey(cacheKey)) return tmdbIds[cacheKey]
        val clean = MediaInfo.cleanTitle(title).replace(Regex("[(\\[].*?[)\\]]"), "").trim()
        val yearParam = year?.let { if (tv) "&first_air_date_year=$it" else "&year=$it" } ?: ""
        val body = Trends.get("search/${if (tv) "tv" else "movie"}?query=${enc(clean)}&language=de-DE$yearParam")
        val first = ((json.parseToJsonElement(body) as? JsonObject)?.get("results") as? JsonArray)?.firstOrNull() as? JsonObject
        val id = first?.str("id")?.toIntOrNull()
        tmdbIds[cacheKey] = id
        return id
    }

    /** Deutscher Trailer von TMDB (sonst englisch) als YouTube-Link, wenn der Anbieter keinen liefert. */
    suspend fun trailer(title: String, year: String?, tv: Boolean): String? = withContext(Dispatchers.IO) {
        if (!Trends.isConfigured) return@withContext null
        val cacheKey = "$tv|$title"
        if (trailers.containsKey(cacheKey)) return@withContext trailers[cacheKey]
        val result = runCatching {
            val id = tmdbId(title, year, tv) ?: return@runCatching null
            val type = if (tv) "tv" else "movie"
            listOf("de-DE", "en-US").firstNotNullOfOrNull { lang ->
                val videos = (json.parseToJsonElement(Trends.get("$type/$id/videos?language=$lang")) as? JsonObject)?.get("results") as? JsonArray
                videos?.mapNotNull { it as? JsonObject }
                    ?.filter { it.str("site") == "YouTube" }
                    ?.sortedBy { if (it.str("type") == "Trailer") 0 else 1 }
                    ?.firstOrNull()?.str("key")
            }?.let { "https://www.youtube.com/watch?v=$it" }
        }.getOrNull()
        trailers[cacheKey] = result
        result
    }

    private fun tmdbRecommendations(title: String, year: String?, tv: Boolean): List<String> = runCatching {
        val id = tmdbId(title, year, tv) ?: return@runCatching emptyList()
        Trends.parse(Trends.get("${if (tv) "tv" else "movie"}/$id/recommendations?language=de-DE"), tv = tv)
            .flatMap { listOfNotNull(it.title, it.original) }
    }.getOrDefault(emptyList())

    // endregion

    suspend fun movies(movie: Channel, all: List<Channel>): List<Channel> = withContext(Dispatchers.Default) {
        val own = key(movie.name)
        val out = LinkedHashMap<String, Channel>()
        if (Trends.isConfigured) {
            val wanted = withContext(Dispatchers.IO) { tmdbRecommendations(movie.name, MediaSort.year(movie.name)?.toString(), tv = false) }
            if (wanted.isNotEmpty()) {
                val byKey = HashMap<String, Channel>()
                val wantedKeys = wanted.map(::key).toHashSet()
                for (m in all) { val k = key(m.name); if (k in wantedKeys) byKey.putIfAbsent(k, m) }
                for (w in wanted) byKey[key(w)]?.let { if (key(it.name) != own) out.putIfAbsent(key(it.name), it) }
            }
        }
        if (out.size < LIMIT) {
            val rating = movie.rating ?: 6f
            val year = MediaSort.year(movie.name)
            all.asSequence()
                .filter { it.category == movie.category && it.url != movie.url }
                .take(3000)
                .sortedBy { c ->
                    val r = kotlin.math.abs((c.rating ?: 5f) - rating)
                    val y = if (year != null) kotlin.math.abs((MediaSort.year(c.name) ?: year) - year) / 10f else 0f
                    r + y
                }
                .forEach { c -> if (out.size < LIMIT) { val k = key(c.name); if (k != own) out.putIfAbsent(k, c) } }
        }
        out.values.take(LIMIT)
    }

    suspend fun shows(show: SeriesShow, all: List<SeriesShow>): List<SeriesShow> = withContext(Dispatchers.Default) {
        val own = key(show.title)
        val out = LinkedHashMap<String, SeriesShow>()
        if (Trends.isConfigured) {
            val wanted = withContext(Dispatchers.IO) { tmdbRecommendations(show.title, null, tv = true) }
            if (wanted.isNotEmpty()) {
                val wantedKeys = wanted.map(::key).toHashSet()
                val byKey = HashMap<String, SeriesShow>()
                for (s in all) { val k = key(s.title); if (k in wantedKeys) byKey.putIfAbsent(k, s) }
                for (w in wanted) byKey[key(w)]?.let { if (key(it.title) != own) out.putIfAbsent(key(it.title), it) }
            }
        }
        if (out.size < LIMIT) {
            val rating = show.rating ?: 6f
            all.asSequence().filter { it.category == show.category && it !== show }.take(2000)
                .sortedBy { kotlin.math.abs((it.rating ?: 5f) - rating) }
                .forEach { s -> if (out.size < LIMIT) { val k = key(s.title); if (k != own) out.putIfAbsent(k, s) } }
        }
        out.values.take(LIMIT)
    }
}
