package app.iptv.tv.data

import app.iptv.tv.model.Channel
import app.iptv.tv.model.Programme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Erinnerungen an Sendungen aus dem TV-Programm (erscheinen kurz vor Beginn, solange die App läuft). */
object Reminders {
    @Serializable
    data class Reminder(val channelKey: String, val channelName: String, val title: String, val start: Long, val end: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Reminder.serializer())
    val items = MutableStateFlow<List<Reminder>>(emptyList())
    private var loaded = false

    private fun load() {
        if (loaded) return
        loaded = true
        items.value = runCatching { json.decodeFromString(serializer, Prefs.str("reminders", "[]")) }.getOrDefault(emptyList())
            .filter { it.end > System.currentTimeMillis() }
    }

    private fun save() = Prefs.putStr("reminders", json.encodeToString(serializer, items.value))

    fun isSet(channel: Channel, p: Programme): Boolean {
        load()
        return items.value.any { it.channelKey == channel.stableKey && it.start == p.start }
    }

    /** Erinnerung setzen bzw. entfernen; Rückgabe: jetzt gesetzt? */
    fun toggle(channel: Channel, p: Programme): Boolean {
        load()
        val set = isSet(channel, p)
        items.value = if (set) items.value.filterNot { it.channelKey == channel.stableKey && it.start == p.start }
        else (items.value + Reminder(channel.stableKey, channel.name, p.title, p.start, p.end)).sortedBy { it.start }
        save()
        return !set
    }

    /** Fällige Erinnerung (90 s vor bis 5 min nach Beginn). */
    fun due(now: Long = System.currentTimeMillis()): Reminder? {
        load()
        return items.value.firstOrNull { now >= it.start - 90_000 && now < it.start + 5 * 60_000 }
    }

    fun remove(r: Reminder) {
        items.value = items.value.filter { it != r && it.end > System.currentTimeMillis() }
        save()
    }
}
