package app.iptv.tv.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** Die drei Bereiche der App. */
@Serializable
enum class ContentKind(val title: String, val unit: String) {
    LIVE("Live TV", "Sender"),
    MOVIE("Filme", "Filme"),
    SERIES("Serien", "Serien"),
}

/** Ein Eintrag der Playlist: Live-Sender, Film oder Serien-Episode. */
@Serializable
data class Channel(
    val name: String,
    val url: String,
    /** `group-title` bzw. Xtream-Kategorie. */
    val group: String? = null,
    /** Kennung für den TV-Guide (`tvg-id` bzw. Xtream `epg_channel_id`). */
    val tvgId: String? = null,
    val logo: String? = null,
    val kind: ContentKind = ContentKind.LIVE,
    /** Xtream: Zeitpunkt des Hinzufügens (Sekunden), für „Neu“-Reihen. */
    val added: Long? = null,
    /** Sendungsarchiv (Catch-up): wie viele Tage rückwärts abrufbar, sonst null. */
    val archiveDays: Int? = null,
    /** Bewertung 0–10 (Xtream), für Sortierung und Sterne auf den Kacheln. */
    val rating: Float? = null,
) {
    val category: String get() = group ?: UNGROUPED

    /** Bleibt beim Aktualisieren gleich – Schlüssel für Favoriten, Verlauf und Fortschritt. */
    val stableKey: String get() = "${kind.name.lowercase()}|$category|$name"

    /** Kürzel für Platzhalter, z. B. „DE“ für „Das Erste HD“. */
    val initials: String
        get() {
            val words = name.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.take(2)
            val letters = words.joinToString("") { it.first().uppercase() }
            return letters.ifEmpty { "TV" }
        }

    companion object {
        const val UNGROUPED = "Ohne Kategorie"
    }
}

@Serializable
data class XtreamCredentials(val server: String, val username: String, val password: String)

@Serializable
sealed class PlaylistSource {
    @Serializable
    data class M3u(val url: String) : PlaylistSource()

    @Serializable
    data class Xtream(val credentials: XtreamCredentials) : PlaylistSource()

    /** Importierte M3U-Datei (lokal gespeichert). */
    @Serializable
    data class File(val originalName: String) : PlaylistSource()

    val typeTitle: String
        get() = when (this) {
            is M3u -> "M3U-Link"
            is Xtream -> "Xtream Codes"
            is File -> "M3U-Datei"
        }

    /** Kurzbeschreibung ohne Passwort. */
    val displayDetail: String
        get() = when (this) {
            is M3u -> runCatching { java.net.URI(url).host }.getOrNull() ?: url
            is Xtream -> "${credentials.username} @ " +
                (runCatching { java.net.URI(credentials.server).host }.getOrNull() ?: credentials.server)
            is File -> originalName
        }
}

@Serializable
data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val source: PlaylistSource,
    val detectedEpgUrl: String? = null,
    val customEpgUrl: String? = null,
    val lastUpdated: Long? = null,
) {
    val epgUrl: String? get() = customEpgUrl ?: detectedEpgUrl
}

@Serializable
data class Episode(
    val channel: Channel,
    val season: Int? = null,
    val number: Int? = null,
    val title: String? = null,
    val plot: String? = null,
) {
    val displayTitle: String
        get() {
            if (number == null) return title?.takeIf { it.isNotBlank() } ?: channel.name
            val t = title?.takeIf { it.isNotBlank() && !it.contains(channel.name, ignoreCase = true) }
            return if (t != null) "$number. $t" else "Episode $number"
        }
}

/** Eine Serie. Bei M3U aus Episoden gebildet, bei Xtream werden Episoden beim Öffnen geladen. */
@Serializable
data class SeriesShow(
    val title: String,
    val category: String,
    val poster: String? = null,
    val plot: String? = null,
    val episodes: List<Episode> = emptyList(),
    val xtreamSeriesId: Int? = null,
    val added: Long? = null,
    val rating: Float? = null,
) {
    val favoriteKey: String get() = "series|$category|$title"
    val needsEpisodeLoading: Boolean get() = episodes.isEmpty() && xtreamSeriesId != null

    /** Episoden nach Staffel gruppiert (ohne Angabe = Staffel 0). */
    val seasons: List<Pair<Int, List<Episode>>>
        get() = episodes.groupBy { it.season ?: 0 }.toSortedMap().map { it.key to it.value }

    companion object {
        /** Fasst M3U-Episoden anhand des Serientitels zusammen (Playlist-Reihenfolge). */
        fun fromEpisodes(items: List<Channel>): List<SeriesShow> {
            val order = LinkedHashMap<String, MutableList<Episode>>()
            val titles = HashMap<String, Pair<String, String>>()
            val posters = HashMap<String, String?>()
            for (channel in items) {
                val info = app.iptv.tv.data.EpisodeParser.parse(channel.name)
                val title = info?.showTitle ?: channel.name
                val key = "${channel.category}|${title.lowercase()}"
                order.getOrPut(key) { mutableListOf() }
                    .add(Episode(channel, info?.season, info?.episode))
                titles.putIfAbsent(key, title to channel.category)
                if (posters[key] == null) posters[key] = channel.logo
            }
            return order.map { (key, eps) ->
                val (title, category) = titles.getValue(key)
                SeriesShow(
                    title = title,
                    category = category,
                    poster = posters[key],
                    episodes = eps.sortedWith(compareBy({ it.season ?: 0 }, { it.number ?: 0 })),
                )
            }
        }
    }
}

/** Rohdaten einer geladenen Playlist (so werden sie auch gespeichert). */
@Serializable
data class PlaylistData(
    val channels: List<Channel> = emptyList(),
    val shows: List<SeriesShow> = emptyList(),
    /** Zeitzone des Xtream-Servers (für Archiv-Links), z. B. „Europe/Berlin“. */
    val serverTimeZone: String? = null,
)

/** Zusatzinfos zu Film/Serie. */
data class MediaInfo(
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val rating: Double? = null,
    val durationMinutes: Int? = null,
    val backdrop: String? = null,
    val poster: String? = null,
    /** YouTube-Link zum Trailer (nur echte YouTube-Adressen). */
    val trailerUrl: String? = null,
) {
    val year: String? get() = releaseDate?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }

    val durationText: String?
        get() {
            val m = durationMinutes ?: return null
            if (m <= 0) return null
            val h = m / 60
            val r = m % 60
            return when {
                h == 0 -> "$r Min."
                r == 0 -> "$h Std."
                else -> "$h Std. $r Min."
            }
        }

    val isEmpty: Boolean get() = this == MediaInfo()

    companion object {
        /** Xtream liefert meist nur die YouTube-ID, manchmal einen vollständigen Link. */
        fun trailerUrl(value: String?): String? {
            val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (v.lowercase().startsWith("http")) {
                val host = runCatching { java.net.URI(v).host?.lowercase() }.getOrNull() ?: return null
                return v.takeIf { host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") }
            }
            if (v.length > 20 || !v.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }) return null
            return "https://www.youtube.com/watch?v=$v"
        }

        /** Entfernt Anbieter-Präfixe wie „DE - “, „[EN] “ oder „4K | “ aus Titeln. */
        fun cleanTitle(title: String): String {
            var r = title.trim()
            if (r.startsWith("[")) r.indexOf(']').takeIf { it > 0 }?.let { r = r.substring(it + 1) }
            for (sep in listOf(" - ", " | ", ": ", "|")) {
                val i = r.indexOf(sep)
                if (i >= 0) {
                    val prefix = r.substring(0, i).trim()
                    if (prefix.length in 2..4 && prefix == prefix.uppercase()) {
                        r = r.substring(i + sep.length)
                        break
                    }
                }
            }
            return r.trim()
        }

        private val yearRegex = Regex("\\b(19|20)\\d{2}\\b")
        private val bracketRegex = Regex("[(\\[{][^)\\]}]*[)\\]}]")
        private val qualityRegex = Regex(
            "\\b(4k|8k|uhd|fhd|hd|sd|hq|lq|2160p|1080p|720p|576p|480p|hevc|h265|h264|x265|x264|hdr|hdr10|remux|bluray|webrip)\\b",
        )

        /** Vergleichsschlüssel: ohne Präfix, Klammerzusätze und Qualitätsangaben, plus Jahr. */
        fun versionKey(title: String): Pair<String, String?> {
            var text = cleanTitle(title).lowercase()
            var year: String? = null
            yearRegex.find(text)?.let { m ->
                if (text.trim() != m.value) {
                    year = m.value
                    text = text.removeRange(m.range)
                }
            }
            text = text.replace(bracketRegex, " ").replace(qualityRegex, " ")
            val words = text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            return words.joinToString(" ") to year
        }

        /** Andere Einträge der Playlist mit demselben Film (z. B. SD, HD, 4K). */
        fun versions(movie: Channel, movies: List<Channel>): List<Channel> {
            val (name, year) = versionKey(movie.name)
            if (name.isEmpty()) return emptyList()
            val anchor = name.split(' ').maxByOrNull { it.length } ?: name
            return movies.filter { c ->
                if (c === movie || c.url == movie.url || !c.name.contains(anchor, ignoreCase = true)) return@filter false
                val (n, y) = versionKey(c.name)
                n == name && (y == null || year == null || y == year)
            }
        }
    }
}

/** Eine Sendung im TV-Guide (Zeiten in Millisekunden). */
@Serializable
data class Programme(val title: String, val description: String? = null, val start: Long, val end: Long) {
    fun isLive(now: Long) = now in start until end
    fun progress(now: Long): Float =
        if (end <= start) 0f else ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f)
}

@Serializable
data class EpgGuide(val programmes: Map<String, List<Programme>> = emptyMap(), val fetchedAt: Long = 0) {
    fun schedule(channel: Channel): List<Programme> {
        for (key in lookupKeys(channel)) {
            programmes[key]?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptyList()
    }

    fun current(channel: Channel, now: Long = System.currentTimeMillis()) =
        schedule(channel).firstOrNull { it.isLive(now) }

    fun next(channel: Channel, now: Long = System.currentTimeMillis()) =
        schedule(channel).firstOrNull { it.start > now }

    companion object {
        fun lookupKeys(channel: Channel): List<String> =
            listOfNotNull(channel.tvgId, channel.name).map { it.lowercase() }.filter { it.isNotEmpty() }
    }
}
