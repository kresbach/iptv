package app.iptv.tv.data

import app.iptv.tv.model.Channel
import app.iptv.tv.model.ContentKind

/**
 * Andere Fassungen desselben Senders finden, z. B. „Sky Sport 1 HD“, „DE: Sky Sport 1 FHD“,
 * „Sky Sport 1 (Backup)“. Dient als Ersatz, wenn ein Stream ausfällt oder dauerhaft gestört ist.
 */
object Alternatives {
    /** Qualitäts- und Zusatzangaben, die für den Vergleich keine Rolle spielen. */
    private val noise = Regex(
        "\\b(f?hd|uhd|4k|8k|sd|hevc|h\\.?26[45]|raw|backup|b(ackup)?\\s?\\d?|alt(ernativ)?|reserve|\\d{2,3}\\s?fps|50p|1080[ip]?|720p|2160p|" +
            "multi(audio)?|vip|plus|\\+|ger|deu|de|dach|at|ch|live|tv-?stream)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val prefix = Regex("^\\s*([\\[(|]?\\s*[a-z]{2,4}\\s*[\\])|:]\\s*|[a-z]{2,4}\\s+[-|]\\s+)", RegexOption.IGNORE_CASE)
    private val memo = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Vergleichsname: „DE: Sky Sport 1 FHD (Backup)“ → „sky sport 1“. */
    fun baseName(name: String): String = memo.getOrPut(name) {
        var n = name.lowercase()
        n = prefix.replace(n, "")
        n = n.replace(Regex("[\\[\\](){}|:_*#~]"), " ")
        repeat(2) { n = noise.replace(n, " ") }
        n.replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    /** Rangfolge der Ersatzfassungen: gleiche Kategorie zuerst, dann höhere Qualität. */
    private fun rank(original: Channel, c: Channel): Int {
        val n = c.name.lowercase()
        val quality = when {
            Regex("\\bf?hd\\b|1080").containsMatchIn(n) -> 0
            Regex("\\b(uhd|4k|2160)\\b").containsMatchIn(n) -> 1 // 4K braucht viel Bandbreite: nach HD
            Regex("\\bsd\\b").containsMatchIn(n) -> 3
            else -> 2
        }
        return (if (c.category == original.category) 0 else 10) + quality
    }

    fun find(channel: Channel, all: List<Channel>, limit: Int = 5): List<Channel> {
        if (channel.kind != ContentKind.LIVE) return emptyList()
        val base = baseName(channel.name)
        if (base.length < 3) return emptyList()
        return all.asSequence()
            .filter { it.kind == ContentKind.LIVE && it.url != channel.url && baseName(it.name) == base }
            .sortedBy { rank(channel, it) }
            .take(limit)
            .toList()
    }
}
