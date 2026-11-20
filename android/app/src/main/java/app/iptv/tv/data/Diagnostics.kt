package app.iptv.tv.data

import app.iptv.tv.model.Channel
import app.iptv.tv.model.PlaylistSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.text.DateFormat
import java.util.Date

/**
 * Findet heraus, warum ein Stream nicht startet: fragt den Stream-Link an (nur die
 * Antwort-Header, keine Daten) und bei Xtream den Kontostatus.
 */
object Diagnostics {
    enum class Level { OK, WARNING, PROBLEM }
    data class Finding(val level: Level, val text: String)

    suspend fun run(channel: Channel, source: PlaylistSource?): List<Finding> {
        val result = ArrayList<Finding>()
        if (source is PlaylistSource.Xtream) result += account(XtreamClient(source.credentials))
        else UrlTools.xtreamCredentials(channel.url)?.let { result += account(XtreamClient(it)) }
        result += stream(channel.url)
        return result
    }

    private suspend fun account(client: XtreamClient): List<Finding> = try {
        val a = client.accountStatus()
        if (!a.authenticated) {
            listOf(Finding(Level.PROBLEM, "Anmeldung abgelehnt: Benutzername oder Passwort stimmen nicht (mehr)."))
        } else buildList {
            val status = a.status ?: "unbekannt"
            add(Finding(if (status.equals("active", true)) Level.OK else Level.PROBLEM, "Kontostatus: $status"))
            a.expires?.let {
                val text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
                add(Finding(if (it < System.currentTimeMillis()) Level.PROBLEM else Level.OK, "Gültig bis: $text"))
            }
            val active = a.activeConnections
            val max = a.maxConnections
            if (active != null && max != null) {
                add(
                    if (active >= max) Finding(
                        Level.PROBLEM,
                        "Verbindungen: $active von $max belegt – das Limit ist erreicht. Nach dem Schließen eines Streams " +
                            "zählt der Server die Verbindung oft noch bis zu einer Minute mit. Bleibt es dauerhaft belegt, " +
                            "nutzt ein anderes Gerät den Zugang.",
                    ) else Finding(Level.OK, "Verbindungen: $active von $max belegt"),
                )
            }
            if (a.formats.isNotEmpty()) add(Finding(Level.OK, "Erlaubte Formate: ${a.formats.joinToString(", ")}"))
        }
    } catch (e: Exception) {
        listOf(Finding(Level.PROBLEM, "Kontostatus nicht abrufbar: ${Http.message(e)}"))
    }

    private suspend fun stream(url: String): List<Finding> = withContext(Dispatchers.IO) {
        if (!url.startsWith("http")) return@withContext listOf(Finding(Level.OK, "Lokale Datei bzw. kein HTTP-Stream."))
        try {
            val request = Request.Builder().url(url).header("Range", "bytes=0-1023").build()
            // Nur die Header lesen und sofort schließen, damit keine Verbindung offen bleibt.
            Http.client.newCall(request).execute().use { r ->
                val type = r.header("Content-Type") ?: "unbekannt"
                val host = r.request.url.host
                val originalHost = runCatching { java.net.URI(url).host }.getOrNull()
                buildList {
                    when (r.code) {
                        in 200..299 -> add(
                            if (type.contains("text/html")) Finding(
                                Level.PROBLEM,
                                "Der Server liefert eine Webseite statt eines Videos ($type). Meist bedeutet das: Zugang gesperrt oder Link abgelaufen.",
                            ) else Finding(Level.OK, "Stream erreichbar (HTTP ${r.code}, $type)."),
                        )
                        401, 403 -> add(
                            Finding(
                                Level.PROBLEM,
                                "Der Server verweigert den Zugriff (HTTP ${r.code}). Häufige Gründe: Verbindungslimit erreicht, " +
                                    "vorübergehende Sperre nach zu vielen Versuchen oder Konto abgelaufen.",
                            ),
                        )
                        404 -> add(Finding(Level.PROBLEM, "Den Stream gibt es auf dem Server nicht (HTTP 404). Aktualisiere die Playlist."))
                        429, 458 -> add(Finding(Level.PROBLEM, "Zu viele Anfragen (HTTP ${r.code}). Der Anbieter hat vorübergehend gesperrt – warte einige Minuten."))
                        else -> add(Finding(Level.PROBLEM, "Der Server antwortet mit Fehler ${r.code}."))
                    }
                    if (originalHost != null && host != originalHost) add(Finding(Level.OK, "Weitergeleitet zu: $host"))
                }
            }
        } catch (e: Exception) {
            listOf(Finding(Level.PROBLEM, "Stream nicht erreichbar: ${Http.message(e)}"))
        }
    }
}
