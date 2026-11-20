package app.iptv.tv.data

import app.iptv.tv.model.Channel
import app.iptv.tv.model.ContentKind
import app.iptv.tv.model.EpgGuide
import app.iptv.tv.model.Programme
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/**
 * Liest einen TV-Guide im XMLTV-Format (auch `.xml.gz`) gestreamt und behält nur Sendungen
 * der eigenen Sender im Zeitfenster (Standard: 2 Std. zurück bis 36 Std. voraus).
 */
object EpgParser {

    /** Entpackt gzip automatisch (erkennt die Magic-Bytes 1F 8B). */
    fun maybeGunzip(input: InputStream): InputStream {
        val buffered = BufferedInputStream(input, 64 * 1024)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(buffered, 64 * 1024)) else buffered
    }

    fun parse(
        input: InputStream,
        channels: List<Channel>,
        parser: XmlPullParser,
        now: Long = System.currentTimeMillis(),
        pastMs: Long = 2 * 3600_000L,
        futureMs: Long = 36 * 3600_000L,
    ): EpgGuide {
        val wantedIds = HashSet<String>()
        val wantedNames = HashSet<String>()
        for (c in channels) {
            if (c.kind != ContentKind.LIVE) continue
            c.tvgId?.lowercase()?.takeIf { it.isNotEmpty() }?.let(wantedIds::add)
            wantedNames += c.name.lowercase()
        }
        val windowStart = now - pastMs
        val windowEnd = now + futureMs

        val keysForChannel = HashMap<String, MutableSet<String>>()
        val programmes = HashMap<String, MutableList<Programme>>()

        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(maybeGunzip(input), null)

        var channelId: String? = null
        var progChannel: String? = null
        var progStart = 0L
        var progEnd = 0L
        var title: String? = null
        var desc: String? = null
        val text = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    text.setLength(0)
                    when (parser.name) {
                        "channel" -> {
                            channelId = parser.getAttributeValue(null, "id")?.lowercase()
                            channelId?.takeIf { it in wantedIds }?.let {
                                keysForChannel.getOrPut(it) { HashSet() }.add(it)
                            }
                        }
                        "programme" -> {
                            progChannel = null
                            val ch = parser.getAttributeValue(null, "channel")?.lowercase()
                            val start = parseDate(parser.getAttributeValue(null, "start"))
                            val end = parseDate(parser.getAttributeValue(null, "stop"))
                            if (ch != null && start != null && end != null && end >= windowStart && start <= windowEnd) {
                                if (ch in wantedIds) keysForChannel.getOrPut(ch) { HashSet() }.add(ch)
                                if (keysForChannel.containsKey(ch)) {
                                    progChannel = ch
                                    progStart = start
                                    progEnd = end
                                    title = null
                                    desc = null
                                }
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val value = text.toString().trim()
                    when (parser.name) {
                        "display-name" -> channelId?.let { id ->
                            if (value.lowercase() in wantedNames) {
                                keysForChannel.getOrPut(id) { HashSet() }.add(value.lowercase())
                            }
                        }
                        "channel" -> channelId = null
                        "title" -> if (progChannel != null && title == null) title = value
                        "desc" -> if (progChannel != null && desc == null) desc = value
                        "programme" -> {
                            val ch = progChannel
                            if (ch != null) {
                                val p = Programme(title ?: "Ohne Titel", desc?.takeIf { it.isNotEmpty() }, progStart, progEnd)
                                keysForChannel[ch]?.forEach { key -> programmes.getOrPut(key) { ArrayList() }.add(p) }
                            }
                            progChannel = null
                        }
                    }
                    text.setLength(0)
                }
            }
            event = try {
                parser.next()
            } catch (e: Exception) {
                // Kaputtes Dateiende: bisher Gelesenes behalten.
                if (programmes.isEmpty()) throw e
                XmlPullParser.END_DOCUMENT
            }
        }

        val cleaned = programmes.mapValues { (_, list) ->
            val seen = HashSet<Long>()
            list.sortedBy { it.start }.filter { seen.add(it.start) }
        }
        return EpgGuide(cleaned, now)
    }

    private val zoned = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    }
    private val plain = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    /** „20240101203000 +0100“ bzw. ohne Zeitzone (dann UTC). */
    fun parseDate(value: String?): Long? {
        val v = value?.trim() ?: return null
        if (v.length < 14) return null
        return runCatching { zoned.get()!!.parse(v)!!.time }.getOrNull()
            ?: runCatching { plain.get()!!.parse(v.substring(0, 14))!!.time }.getOrNull()
    }
}
