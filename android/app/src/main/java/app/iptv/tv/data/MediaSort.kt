package app.iptv.tv.data

import app.iptv.tv.model.Channel
import app.iptv.tv.model.MediaInfo
import app.iptv.tv.model.SeriesShow
import java.text.Collator
import java.util.Locale

/** Sortieren und Filtern der Film- und Serienübersicht. */
object MediaSort {
    val modes = listOf("default" to "Standard", "new" to "Neu", "az" to "A–Z", "rating" to "Bewertung", "year" to "Jahr")
    const val GOOD = 7f

    private val collator = Collator.getInstance(Locale.GERMAN).apply { strength = Collator.PRIMARY }

    fun year(title: String): Int? = MediaInfo.versionKey(title).second?.toIntOrNull()

    fun movies(list: List<Channel>, mode: String, onlyGood: Boolean): List<Channel> {
        val base = if (onlyGood) list.filter { (it.rating ?: 0f) >= GOOD } else list
        return sorted(base, mode, { it.name }, { it.added }, { it.rating })
    }

    fun shows(list: List<SeriesShow>, mode: String, onlyGood: Boolean): List<SeriesShow> {
        val base = if (onlyGood) list.filter { (it.rating ?: 0f) >= GOOD } else list
        return sorted(base, mode, { it.title }, { it.added }, { it.rating })
    }

    private fun <T> sorted(list: List<T>, mode: String, title: (T) -> String, added: (T) -> Long?, rating: (T) -> Float?): List<T> =
        when (mode) {
            "new" -> list.sortedByDescending { added(it) ?: Long.MIN_VALUE }
            "rating" -> list.sortedByDescending { rating(it) ?: -1f }
            "az" -> {
                val keys = list.associateWith { MediaInfo.cleanTitle(title(it)) }
                list.sortedWith { a, b -> collator.compare(keys[a], keys[b]) }
            }
            "year" -> {
                val years = list.associateWith { year(title(it)) ?: Int.MIN_VALUE }
                list.sortedByDescending { years[it] }
            }
            else -> list
        }
}
