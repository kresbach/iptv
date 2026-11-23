package app.iptv.tv.data

import app.iptv.tv.model.Channel
import app.iptv.tv.model.ContentKind
import app.iptv.tv.model.Programme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Sendungsarchiv (Catch-up) im Xtream-Format:
 * `http://server/timeshift/benutzer/passwort/<Minuten>/<JJJJ-MM-TT:HH-MM>/<Sender-ID>.ts`.
 */
object Catchup {
    private val livePattern = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(?:\.\w+)?$""")
    private const val DAY = 24 * 3600_000L

    fun supported(channel: Channel): Boolean =
        channel.kind == ContentKind.LIVE && (channel.archiveDays ?: 0) > 0 && livePattern.matches(channel.url)

    /** Lässt sich diese Sendung aus dem Archiv abspielen? (begonnen und nicht älter als das Archiv) */
    fun available(channel: Channel, p: Programme, now: Long = System.currentTimeMillis()): Boolean =
        supported(channel) && p.start < now - 60_000 && p.start > now - channel.archiveDays!! * DAY

    fun url(channel: Channel, p: Programme, timeZone: String?): String? {
        val m = livePattern.matchEntire(channel.url) ?: return null
        val (base, user, pass, id) = m.destructured
        val minutes = ((p.end - p.start) / 60_000).coerceIn(1, 24 * 60)
        val format = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply {
            timeZone?.let { this.timeZone = TimeZone.getTimeZone(it) }
        }
        return "$base/timeshift/$user/$pass/$minutes/${format.format(Date(p.start))}/$id.ts"
    }

    /** Abspielbarer Eintrag für die Sendung (spulbar wie ein Film). */
    fun channel(channel: Channel, p: Programme, timeZone: String? = Repository.content.value.data.serverTimeZone): Channel? {
        val link = url(channel, p, timeZone) ?: return null
        return Channel(
            name = p.title,
            url = link,
            group = channel.name,
            tvgId = channel.tvgId,
            logo = channel.logo,
            kind = ContentKind.MOVIE,
        )
    }

    /** Vergangene Sendungen eines Senders aus dem TV-Guide, neueste zuerst. */
    fun pastProgrammes(channel: Channel, guide: app.iptv.tv.model.EpgGuide?, now: Long = System.currentTimeMillis()): List<Programme> =
        guide?.schedule(channel)?.filter { available(channel, it, now) }?.sortedByDescending { it.start } ?: emptyList()
}
