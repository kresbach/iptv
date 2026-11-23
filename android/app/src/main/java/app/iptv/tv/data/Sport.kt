package app.iptv.tv.data

import app.iptv.tv.model.Channel
import app.iptv.tv.model.EpgGuide
import app.iptv.tv.model.Programme

/**
 * „Sport jetzt live“: laufende und heute noch kommende Fußballspiele aus dem TV-Programm der eigenen
 * Sender – nur Fußball, Bundesliga und Champions League zuerst.
 */
object Sport {
    data class Event(val channel: Channel, val programme: Programme) {
        fun isLive(now: Long) = programme.isLive(now)
    }

    /** Bundesliga und Champions League stehen vorne. */
    private val top = Regex("bundesliga|champions league", RegexOption.IGNORE_CASE)

    /** Fußball-Wettbewerbe (andere Sportarten mit gleichem Namen werden unten ausgeschlossen). */
    private val football = Regex(
        "fu(ß|ss)ball|bundesliga|champions league|europa league|conference league|dfb-pokal|supercup|" +
            "länderspiel|laenderspiel|nations league|wm-qualifikation|em-qualifikation|" +
            "premier league|la ?liga|serie a\\b|ligue 1|eredivisie|süper lig|fa cup|3\\. liga",
        RegexOption.IGNORE_CASE,
    )

    /** Begegnungen wie „FC Bayern - Dortmund“ oder „Real Madrid vs. Barcelona“. */
    private val versus = Regex("\\s(vs\\.?|v\\.?|gegen)\\s|\\S\\s[-–]\\s\\S", RegexOption.IGNORE_CASE)

    /** Typische Vereinsnamen – nur zusammen mit einer Begegnung auf einem Sportsender. */
    private val club = Regex(
        "\\b(FC|SC|SV|VfB|VfL|TSG|BVB|RB|Borussia|Bayern|Bayer|Eintracht|Hertha|Union|Werder|Schalke|Mainz|Freiburg|" +
            "Hoffenheim|Wolfsburg|Augsburg|Bochum|Heidenheim|St\\. Pauli|Kiel|HSV|Real|Atl[eé]tico|Barcelona|Inter|Juventus|" +
            "Milan|Napoli|Arsenal|Chelsea|Liverpool|Manchester|Tottenham|PSG|Paris|Ajax|Benfica|Porto|Celtic|Galatasaray|Fenerbah[cç]e)\\b",
    )

    /** Sender, deren Programm (fast) nur aus Sport besteht. */
    private val sportChannel = Regex("sport|dazn|sky|eurosport|sportdigital|magenta|bein|arena|laola|servus", RegexOption.IGNORE_CASE)

    /** Andere Sportarten (z. B. „Handball-Bundesliga“, „Darts Premier League“) und Nicht-Spiele. */
    private val exclude = Regex(
        "handball|basketball|eishockey|volleyball|darts|tennis|golf|formel|motogp|nfl|nba|nhl|football league|american|" +
            "boxen|ufc|\\bski|biathlon|radsport|tischtennis|hockey|futsal|esports|e-sport|beach|fifa \\d|ea fc|" +
            "magazin|news|nachrichten|quiz|dokumentation|doku\\b|wetter|teleshopping|talk",
        RegexOption.IGNORE_CASE,
    )

    /** Nur Fußball: Wettbewerb im Titel oder eine Vereins-Begegnung auf einem Sportsender. */
    fun isSport(channel: Channel, p: Programme): Boolean {
        val t = p.title
        if (exclude.containsMatchIn(t)) return false
        if (football.containsMatchIn(t)) return true
        val sportyChannel = sportChannel.containsMatchIn(channel.name) || sportChannel.containsMatchIn(channel.category)
        return sportyChannel && versus.containsMatchIn(t) && club.containsMatchIn(t)
    }

    /** Bundesliga und Champions League zuerst. */
    fun isTop(p: Programme) = top.containsMatchIn(p.title)

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
        return best.values.sortedWith(
            compareBy({ if (it.isLive(now)) 0 else 1 }, { if (isTop(it.programme)) 0 else 1 }, { it.programme.start }),
        ).take(limit)
    }
}
