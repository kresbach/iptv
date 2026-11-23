package app.iptv.tv.data

import app.iptv.tv.model.Channel
import app.iptv.tv.model.EpgGuide
import app.iptv.tv.model.Programme

/**
 * „Sport jetzt live“: laufende und heute noch kommende Fußballspiele (und andere Live-Sportereignisse)
 * aus dem TV-Programm der eigenen Sender.
 */
object Sport {
    data class Event(val channel: Channel, val programme: Programme) {
        fun isLive(now: Long) = programme.isLive(now)
    }

    /** Wettbewerbe und Sendungen, die eindeutig Fußball/Live-Sport sind. */
    private val keywords = Regex(
        "fu(ß|ss)ball|bundesliga|champions league|europa league|conference league|dfb-pokal|supercup|" +
            "länderspiel|laenderspiel|nations league|wm-qualifikation|em-qualifikation|weltmeisterschaft|europameisterschaft|" +
            "premier league|la ?liga|serie a\\b|ligue 1|eredivisie|süper lig|super lig|mls\\b|uefa|fifa|" +
            "sportschau|sportstudio|topspiel|konferenz|2\\. liga|3\\. liga|frauen-bundesliga|copa |fa cup|carabao",
        RegexOption.IGNORE_CASE,
    )

    /** Begegnungen wie „FC Bayern - Dortmund“ oder „Real Madrid vs. Barcelona“. */
    private val versus = Regex("\\s(vs\\.?|v\\.?|gegen)\\s|\\S\\s[-–]\\s\\S", RegexOption.IGNORE_CASE)

    /** Sender, deren Programm (fast) nur aus Sport besteht. */
    private val sportChannel = Regex("sport|dazn|sky ?sport|eurosport|sportdigital|magenta ?sport|bein|arena|laola|servus ?tv", RegexOption.IGNORE_CASE)

    /** Keine Spiele, auch wenn ein Stichwort vorkommt. */
    private val exclude = Regex("magazin|news|nachrichten|quiz|dokumentation|doku\\b|wetter|teleshopping", RegexOption.IGNORE_CASE)

    fun isSport(channel: Channel, p: Programme): Boolean {
        val t = p.title
        if (exclude.containsMatchIn(t)) return false
        if (keywords.containsMatchIn(t)) return true
        val sportyChannel = sportChannel.containsMatchIn(channel.name) || sportChannel.containsMatchIn(channel.category)
        return sportyChannel && versus.containsMatchIn(t)
    }

    /**
     * Laufende Spiele zuerst, danach die nächsten [hours] Stunden. Gleiche Sendung auf mehreren Sendern
     * nur einmal (bevorzugt Favoriten, dann HD).
     */
    fun events(
        guide: EpgGuide?,
        channels: List<Channel>,
        favorites: Set<String>,
        now: Long = System.currentTimeMillis(),
        hours: Int = 12,
        limit: Int = 30,
    ): List<Event> {
        guide ?: return emptyList()
        val until = now + hours * 3_600_000L
        val best = LinkedHashMap<String, Event>()
        fun score(c: Channel) = (if (c.stableKey in favorites) 2 else 0) + (if (Regex("\\b(F?HD|4K|UHD)\\b", RegexOption.IGNORE_CASE).containsMatchIn(c.name)) 1 else 0)
        for (ch in channels) {
            val list = guide.schedule(ch)
            if (list.isEmpty()) continue
            for (p in list) {
                if (p.end <= now) continue
                if (p.start >= until) break
                if (!isSport(ch, p)) continue
                val key = p.title.lowercase().replace(Regex("\\s+"), " ").trim() + "|" + p.start / 60_000
                val old = best[key]
                if (old == null || score(ch) > score(old.channel)) best[key] = Event(ch, p)
            }
        }
        return best.values.sortedWith(compareBy({ if (it.isLive(now)) 0 else 1 }, { it.programme.start })).take(limit)
    }
}
