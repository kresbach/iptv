package app.iptv.tv.data

import app.iptv.tv.model.Channel
import java.io.BufferedReader

/** Fehler mit verständlicher deutscher Meldung. */
class UserFacingException(message: String) : Exception(message)

/**
 * Liest M3U/M3U8-Playlists im erweiterten IPTV-Format. Tolerant: unbekannte Tags werden
 * übersprungen, fehlende Namen fallen auf `tvg-name` bzw. den Dateinamen zurück.
 */
object M3UParser {
    data class Result(val channels: List<Channel>, val epgUrl: String?)

    private val allowedSchemes = setOf("http", "https", "rtmp", "rtmps", "rtsp", "rtp", "udp", "mms", "srt")

    fun parse(text: String): Result = parse(text.reader().buffered())

    fun parse(reader: BufferedReader): Result {
        val channels = ArrayList<Channel>(1024)
        var epgUrl: String? = null
        var pendingName: String? = null
        var pendingAttributes: Map<String, String> = emptyMap()
        var pendingGroup: String? = null
        val head = StringBuilder()
        var first = true

        reader.useLines { lines ->
            for (raw in lines) {
                var line = raw.trim()
                if (first) {
                    line = line.removePrefix("\uFEFF")
                    first = false
                }
                if (line.isEmpty()) continue
                if (head.length < 300) head.append(line).append('\n')

                when {
                    line.startsWith("#EXTM3U") -> {
                        val attrs = parseAttributes(line.removePrefix("#EXTM3U"))
                        epgUrl = (attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"])
                            ?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                    }
                    line.startsWith("#EXTINF") -> {
                        val (name, attrs) = parseExtInf(line)
                        pendingName = name
                        pendingAttributes = attrs
                    }
                    line.startsWith("#EXTGRP:") -> pendingGroup = line.removePrefix("#EXTGRP:").trim()
                    line.startsWith("#") -> Unit
                    else -> {
                        val scheme = line.substringBefore("://", "").lowercase()
                        if (scheme in allowedSchemes) {
                            val group = (pendingAttributes["group-title"] ?: pendingGroup)?.takeIf { it.isNotEmpty() }
                            val name = listOfNotNull(pendingName, pendingAttributes["tvg-name"])
                                .firstOrNull { it.isNotEmpty() }
                                ?: line.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
                            channels.add(
                                Channel(
                                    name = name,
                                    url = line,
                                    group = group,
                                    tvgId = pendingAttributes["tvg-id"]?.takeIf { it.isNotEmpty() },
                                    logo = pendingAttributes["tvg-logo"]?.takeIf { it.isNotEmpty() },
                                    kind = ContentClassifier.kind(line, group, name),
                                ),
                            )
                            pendingName = null
                            pendingAttributes = emptyMap()
                            pendingGroup = null
                        }
                    }
                }
            }
        }

        if (channels.isEmpty()) {
            val start = head.toString().trim()
            val lower = start.take(200).lowercase()
            if (lower.startsWith("<") || lower.contains("<html")) {
                throw UserFacingException(
                    "Der Server hat eine Webseite statt einer Playlist geliefert. Meist sind Zugangsdaten " +
                        "falsch, das Konto ist abgelaufen oder der Link unvollständig.",
                )
            }
            if (start.isNotEmpty() && !start.startsWith("#")) {
                throw UserFacingException("Der Server hat keine Playlist geliefert, sondern: „${start.take(120)}“")
            }
            throw UserFacingException("Die Datei enthält keine Sender. Ist es wirklich eine M3U-Playlist?")
        }
        return Result(channels, epgUrl)
    }

    /** `#EXTINF:-1 key="value" …,Name` → Name + Attribute (erstes Komma außerhalb von Anführungszeichen). */
    fun parseExtInf(line: String): Pair<String, Map<String, String>> {
        val body = line.substringAfter(':', "")
        var inQuotes = false
        var comma = -1
        for (i in body.indices) {
            val c = body[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) {
                comma = i
                break
            }
        }
        if (comma < 0) return "" to parseAttributes(body)
        return body.substring(comma + 1).trim() to parseAttributes(body.substring(0, comma))
    }

    /** `key="value"` bzw. `key=value`; Schlüssel kleingeschrieben. */
    fun parseAttributes(text: String): Map<String, String> {
        val result = HashMap<String, String>()
        var cursor = 0
        while (cursor < text.length) {
            val equals = text.indexOf('=', cursor)
            if (equals < 0) break
            var keyStart = cursor
            for (i in equals - 1 downTo cursor) {
                if (text[i].isWhitespace()) {
                    keyStart = i + 1
                    break
                }
            }
            val key = text.substring(keyStart, equals).lowercase()
            var valueStart = equals + 1
            if (valueStart >= text.length) break
            val valueEnd: Int
            if (text[valueStart] == '"') {
                valueStart++
                val close = text.indexOf('"', valueStart)
                valueEnd = if (close < 0) text.length else close
                cursor = if (close < 0) text.length else close + 1
            } else {
                var e = valueStart
                while (e < text.length && !text[e].isWhitespace()) e++
                valueEnd = e
                cursor = e
            }
            if (key.isNotEmpty()) result[key] = text.substring(valueStart, valueEnd).trim()
        }
        return result
    }
}
