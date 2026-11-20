package app.iptv.tv.data

import app.iptv.tv.model.ContentKind
import app.iptv.tv.model.XtreamCredentials
import java.net.URI
import java.net.URLDecoder

/**
 * Ordnet einen Playlist-Eintrag Live TV, Filmen oder Serien zu (wie in der iOS-App):
 * Xtream-Pfade (`/movie/`, `/series/`), Dateiendung, Gruppenname und Episodenkennung.
 */
object ContentClassifier {
    private val vodExtensions = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv", "webm", "mpg", "mpeg")

    fun kind(url: String, group: String?, name: String): ContentKind {
        val path = UrlTools.path(url).lowercase()
        val g = group?.lowercase() ?: ""
        val looksLikeEpisode = EpisodeParser.parse(name) != null

        if (path.contains("/series/")) return ContentKind.SERIES
        if (path.contains("/movie/") || path.contains("/movies/") || path.contains("/vod/")) {
            return if (looksLikeEpisode) ContentKind.SERIES else ContentKind.MOVIE
        }
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        if (ext in vodExtensions) {
            return if (looksLikeEpisode || g.contains("serie")) ContentKind.SERIES else ContentKind.MOVIE
        }
        if (g.startsWith("vod")) return if (looksLikeEpisode) ContentKind.SERIES else ContentKind.MOVIE
        // Gruppen wie „Serien HD“ enthalten oft Live-Sender – nur mit Episodenkennung als Serie werten.
        if (looksLikeEpisode && g.contains("serie")) return ContentKind.SERIES
        return ContentKind.LIVE
    }
}

/** Erkennt Staffel und Episode in Titeln wie „Dark S01 E03“ oder „Dark - s1e3“. */
object EpisodeParser {
    data class Info(val showTitle: String, val season: Int, val episode: Int)

    fun parse(name: String): Info? {
        var index = 0
        while (index < name.length) {
            val c = name[index]
            if ((c == 'S' || c == 's') && (index == 0 || !name[index - 1].isLetter())) {
                var cursor = index + 1
                val (season, afterSeason) = readNumber(name, cursor, 3)
                if (season != null) {
                    cursor = afterSeason
                    while (cursor < name.length && name[cursor] in " ._-") cursor++
                    if (cursor < name.length && (name[cursor] == 'E' || name[cursor] == 'e')) {
                        val (episode, _) = readNumber(name, cursor + 1, 4)
                        if (episode != null) {
                            val title = name.substring(0, index).trim { it.isWhitespace() || it in "-:|._" }
                            return Info(title.ifEmpty { name }, season, episode)
                        }
                    }
                }
            }
            index++
        }
        return null
    }

    private fun readNumber(s: String, start: Int, maxDigits: Int): Pair<Int?, Int> {
        var cursor = start
        var value = 0
        var digits = 0
        while (cursor < s.length && digits < maxDigits && s[cursor].isDigit()) {
            value = value * 10 + (s[cursor] - '0')
            digits++
            cursor++
        }
        return (if (digits > 0) value else null) to cursor
    }
}

/** Hilfen rund um Links (ohne android.net.Uri, damit Unit-Tests auf der JVM laufen). */
object UrlTools {
    /**
     * Macht aus einer Nutzereingabe einen Link: Leerzeichen/Zeilenumbrüche raus, `&amp;` → `&`,
     * fehlendes Schema → `http://`. Nur http/https mit Host.
     */
    fun userInput(text: String): String? {
        var t = text.filterNot { it.isWhitespace() }.replace("&amp;", "&")
        if (t.isEmpty()) return null
        if (!t.contains("://")) t = "http://$t"
        val uri = runCatching { URI(t) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrEmpty()) return null
        return t
    }

    fun path(url: String): String =
        runCatching { URI(url).rawPath }.getOrNull()
            ?: url.substringAfter("://", url).substringAfter('/', "").substringBefore('?').let { "/$it" }

    fun extension(url: String): String =
        path(url).substringAfterLast('/').substringAfterLast('.', "").lowercase()

    private fun queryParams(url: String): Map<String, String> {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return emptyMap()
        return query.split('&').mapNotNull { part ->
            val k = part.substringBefore('=')
            if (k.isEmpty()) null
            else k.lowercase() to runCatching { URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }
                .getOrDefault(part.substringAfter('=', ""))
        }.toMap()
    }

    /** `…/get.php?username=…&password=…` → Xtream-Zugangsdaten. */
    fun xtreamCredentials(url: String): XtreamCredentials? {
        val file = path(url).substringAfterLast('/').lowercase()
        if (file != "get.php" && file != "player_api.php") return null
        val params = queryParams(url)
        val user = params["username"]?.takeIf { it.isNotEmpty() } ?: return null
        val pass = params["password"]?.takeIf { it.isNotEmpty() } ?: return null
        val server = normalizedServer(url) ?: return null
        return XtreamCredentials(server, user, pass)
    }

    /** „host:8080/player_api.php?x“ → „http://host:8080“. */
    fun normalizedServer(input: String): String? {
        val url = userInput(input) ?: return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        var path = (uri.rawPath ?: "").replace("player_api.php", "").replace("get.php", "")
        while (path.endsWith("/")) path = path.dropLast(1)
        val port = if (uri.port != -1) ":${uri.port}" else ""
        return "${uri.scheme.lowercase()}://${uri.host}$port$path"
    }

    /** Xtream-Live-Links: `.m3u8` ↔ `.ts` (TS gibt die Verbindung beim Server sofort frei). */
    fun liveTsVariant(url: String): String? {
        val p = path(url)
        if (!p.contains("/live/") || !p.endsWith(".m3u8")) return null
        return url.replaceFirst(Regex("\\.m3u8(?=$|\\?)"), ".ts")
    }

    fun liveHlsVariant(url: String): String? {
        val p = path(url)
        if (!p.contains("/live/") || !p.endsWith(".ts")) return null
        return url.replaceFirst(Regex("\\.ts(?=$|\\?)"), ".m3u8")
    }

    /** Xtream-ID aus `…/movie/USER/PASS/1234.mkv`. */
    fun streamId(url: String): Int? =
        path(url).substringAfterLast('/').substringBefore('.').toIntOrNull()
}
