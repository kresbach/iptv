package app.iptv.tv.data

import app.iptv.tv.model.Channel
import app.iptv.tv.model.ContentKind
import app.iptv.tv.model.Episode
import app.iptv.tv.model.MediaInfo
import app.iptv.tv.model.PlaylistData
import app.iptv.tv.model.SeriesShow
import app.iptv.tv.model.XtreamCredentials
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

/**
 * Zugriff auf die Xtream-Codes-API (`player_api.php`).
 * Xtream liefert Zahlen mal als Text, mal als Zahl – deshalb werden die Felder lose gelesen.
 */
class XtreamClient(val credentials: XtreamCredentials) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val xmltvUrl: String get() = endpoint("xmltv.php", emptyList())

    suspend fun loadContent(): PlaylistData = coroutineScope {
        val account = parse(Http.text(endpoint("player_api.php", emptyList())))
        val liveCats = async { list("get_live_categories") }
        val vodCats = async { list("get_vod_categories") }
        val seriesCats = async { list("get_series_categories") }
        val live = async { list("get_live_streams") }
        val vod = async { list("get_vod_streams") }
        val series = async { list("get_series") }
        buildContent(
            account, liveCats.await(), vodCats.await(), seriesCats.await(),
            live.await(), vod.await(), series.await(),
        )
    }

    /** Baut aus den API-Antworten die Playlist (ohne Netzwerk, testbar). */
    fun buildContent(
        account: JsonElement,
        liveCategories: List<JsonObject>,
        vodCategories: List<JsonObject>,
        seriesCategories: List<JsonObject>,
        liveStreams: List<JsonObject>,
        vodStreams: List<JsonObject>,
        series: List<JsonObject>,
    ): PlaylistData {
        val info = (account as? JsonObject)?.get("user_info") as? JsonObject
        if (info == null || info.int("auth") != 1) {
            throw UserFacingException("Anmeldung fehlgeschlagen. Bitte prüfe Benutzername und Passwort.")
        }
        info.str("status")?.let { status ->
            if (!status.equals("active", ignoreCase = true)) {
                throw UserFacingException("Das Konto ist nicht aktiv (Status: $status).")
            }
        }
        val formats = (info["allowed_output_formats"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        // MPEG-TS gibt die (einzige) Verbindung beim Senderwechsel sofort frei, HLS erst nach 20–60 s.
        val liveExt = if (formats.isEmpty() || "ts" in formats) "ts" else "m3u8"

        val liveNames = names(liveCategories)
        val vodNames = names(vodCategories)
        val seriesNames = names(seriesCategories)

        val channels = ArrayList<Channel>(liveStreams.size + vodStreams.size)
        for (s in liveStreams) {
            val id = s.int("stream_id") ?: continue
            channels += Channel(
                name = s.str("name") ?: "Sender $id",
                url = streamUrl("live", id.toString(), liveExt),
                group = s.str("category_id")?.let { liveNames[it] },
                tvgId = s.str("epg_channel_id")?.takeIf { it.isNotEmpty() },
                logo = s.str("stream_icon")?.takeIf { it.isNotEmpty() },
                kind = ContentKind.LIVE,
            )
        }
        for (s in vodStreams) {
            val id = s.int("stream_id") ?: continue
            channels += Channel(
                name = s.str("name") ?: "Film $id",
                url = streamUrl("movie", id.toString(), s.str("container_extension") ?: "mp4"),
                group = s.str("category_id")?.let { vodNames[it] },
                logo = s.str("stream_icon")?.takeIf { it.isNotEmpty() },
                kind = ContentKind.MOVIE,
                added = s.long("added"),
            )
        }
        val shows = series.mapNotNull { s ->
            val id = s.int("series_id") ?: return@mapNotNull null
            val name = s.str("name") ?: return@mapNotNull null
            SeriesShow(
                title = name,
                category = s.str("category_id")?.let { seriesNames[it] } ?: Channel.UNGROUPED,
                poster = s.str("cover")?.takeIf { it.isNotEmpty() },
                plot = s.str("plot")?.takeIf { it.isNotBlank() },
                xtreamSeriesId = id,
                added = s.long("last_modified"),
            )
        }
        return PlaylistData(channels, shows)
    }

    suspend fun movieInfo(streamId: Int): MediaInfo? {
        val root = parse(Http.text(endpoint("player_api.php", listOf("action" to "get_vod_info", "vod_id" to "$streamId"))))
        return mediaInfo((root as? JsonObject)?.get("info") as? JsonObject)
    }

    suspend fun seriesDetails(show: SeriesShow): Pair<MediaInfo?, List<Episode>> {
        val id = show.xtreamSeriesId ?: return null to show.episodes
        val text = Http.text(endpoint("player_api.php", listOf("action" to "get_series_info", "series_id" to "$id")))
        return parseSeriesInfo(text, show)
    }

    fun parseSeriesInfo(text: String, show: SeriesShow): Pair<MediaInfo?, List<Episode>> {
        val root = parse(text) as? JsonObject ?: return null to emptyList()
        val result = ArrayList<Episode>()
        // Ohne Episoden liefern manche Server ein leeres Array statt eines Objekts.
        (root["episodes"] as? JsonObject)?.forEach { (seasonKey, list) ->
            (list as? JsonArray)?.forEach { e ->
                val ep = e as? JsonObject ?: return@forEach
                val epId = ep.str("id") ?: return@forEach
                val season = ep.int("season") ?: seasonKey.toIntOrNull()
                val number = ep.int("episode_num")
                val epInfo = ep["info"] as? JsonObject
                val channel = Channel(
                    name = "${show.title} S%02d E%02d".format(season ?: 0, number ?: 0),
                    url = streamUrl("series", epId, ep.str("container_extension") ?: "mp4"),
                    group = show.category,
                    logo = epInfo?.str("movie_image")?.takeIf { it.isNotEmpty() } ?: show.poster,
                    kind = ContentKind.SERIES,
                )
                result += Episode(channel, season, number, ep.str("title"), epInfo?.str("plot"))
            }
        }
        result.sortWith(compareBy({ it.season ?: 0 }, { it.number ?: 0 }))
        return mediaInfo(root["info"] as? JsonObject) to result
    }

    fun mediaInfo(block: JsonObject?): MediaInfo? {
        block ?: return null
        fun value(vararg keys: String) = keys.firstNotNullOfOrNull { k -> block.str(k)?.trim()?.takeIf { it.isNotEmpty() } }
        val backdrop = when (val b = block["backdrop_path"]) {
            is JsonArray -> b.firstNotNullOfOrNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotEmpty() } }
            else -> value("backdrop_path")
        }
        val info = MediaInfo(
            plot = value("plot", "description"),
            cast = value("cast", "actors"),
            director = value("director"),
            genre = value("genre"),
            releaseDate = value("releasedate", "releaseDate", "release_date"),
            rating = value("rating", "rating_5based")?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 },
            durationMinutes = value("duration_secs")?.toIntOrNull()?.div(60)
                ?: minutes(value("duration")) ?: minutes(value("episode_run_time")),
            backdrop = backdrop,
            poster = value("movie_image", "cover_big", "cover"),
        )
        return info.takeUnless { it.isEmpty }
    }

    // region Intern

    private fun endpoint(path: String, query: List<Pair<String, String>>): String {
        val params = listOf("username" to credentials.username, "password" to credentials.password) + query
        return credentials.server.trimEnd('/') + "/" + path + "?" +
            params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    }

    private fun streamUrl(type: String, id: String, ext: String): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return "${credentials.server.trimEnd('/')}/$type/${enc(credentials.username)}/${enc(credentials.password)}/$id.$ext"
    }

    private fun parse(text: String): JsonElement =
        runCatching { json.parseToJsonElement(text) }.getOrElse {
            val t = text.trim()
            if (t.startsWith("<")) {
                throw UserFacingException("Der Server hat eine Webseite statt Daten geliefert. Ist die Server-Adresse richtig?")
            }
            throw UserFacingException("Die Antwort des Servers konnte nicht gelesen werden.")
        }

    /** Leere Ergebnisse kommen manchmal als `null` oder `{}` statt `[]`. */
    private suspend fun list(action: String): List<JsonObject> =
        parseList(Http.text(endpoint("player_api.php", listOf("action" to action))))

    fun parseList(text: String): List<JsonObject> {
        val t = text.trim()
        if (t.isEmpty() || t == "null") return emptyList()
        return when (val e = parse(t)) {
            is JsonArray -> e.mapNotNull { it as? JsonObject }
            else -> emptyList()
        }
    }

    private fun names(categories: List<JsonObject>): Map<String, String> =
        categories.mapNotNull { c ->
            val id = c.str("category_id") ?: return@mapNotNull null
            val name = c.str("category_name") ?: return@mapNotNull null
            id to name
        }.toMap()

    // endregion

    companion object {
        /** „01:52:03“ oder „112 min“ → Minuten. */
        fun minutes(value: String?): Int? {
            val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val parts = v.split(':').mapNotNull { it.trim().toIntOrNull() }
            if (parts.size == 3) return parts[0] * 60 + parts[1]
            if (parts.size == 2) return parts[0]
            return v.takeWhile { it.isDigit() }.toIntOrNull()
        }
    }
}

internal fun JsonObject.str(key: String): String? = when (val v = this[key]) {
    null, is JsonNull -> null
    is JsonPrimitive -> v.content
    else -> null
}

internal fun JsonObject.long(key: String): Long? {
    val s = str(key) ?: return null
    if (s.equals("true", ignoreCase = true)) return 1
    if (s.equals("false", ignoreCase = true)) return 0
    return s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong()
}

internal fun JsonObject.int(key: String): Int? = long(key)?.toInt()
