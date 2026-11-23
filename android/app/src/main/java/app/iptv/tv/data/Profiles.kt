package app.iptv.tv.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Profile (z. B. „Ich“ und „Kinder“): jedes mit eigenen Favoriten, eigenem Verlauf und Fortschritt.
 * In einem Kinderprofil sind gesperrte Kategorien und Erwachseneninhalte immer ausgeblendet.
 * Der Abgleich mit dem iPhone (Google Drive) betrifft nur das Hauptprofil.
 */
object Profiles {
    @Serializable
    data class Profile(val id: String, val name: String, val kids: Boolean = false)

    @Serializable
    private data class Stored(val profiles: List<Profile> = emptyList(), val activeId: String = MAIN)

    const val MAIN = "main"
    private val json = Json { ignoreUnknownKeys = true }
    private var file: File? = null

    val list = MutableStateFlow(listOf(Profile(MAIN, "Ich")))
    val activeId = MutableStateFlow(MAIN)
    /** In dieser Sitzung schon gewählt (sonst fragt die App beim Start „Wer schaut?“). */
    @Volatile var chosen = false

    val active: Profile get() = list.value.firstOrNull { it.id == activeId.value } ?: list.value.first()
    val isMain: Boolean get() = activeId.value == MAIN
    val isKids: Boolean get() = active.kids

    internal fun load(dir: File) {
        file = File(dir, "profiles.json")
        val stored = runCatching { json.decodeFromString(Stored.serializer(), file!!.readText()) }.getOrNull() ?: return
        if (stored.profiles.isNotEmpty()) list.value = stored.profiles
        activeId.value = stored.activeId.takeIf { id -> list.value.any { it.id == id } } ?: MAIN
    }

    private fun save() {
        runCatching { file?.writeText(json.encodeToString(Stored.serializer(), Stored(list.value, activeId.value))) }
    }

    /** Datei mit Favoriten/Verlauf/Fortschritt des Profils. */
    fun userDataFile(dir: File, id: String = activeId.value) = File(dir, if (id == MAIN) "userdata.json" else "userdata-$id.json")

    fun add(name: String, kids: Boolean): Profile {
        val p = Profile(UUID.randomUUID().toString().take(8), name.trim().ifEmpty { if (kids) "Kinder" else "Profil ${list.value.size + 1}" }, kids)
        list.value = list.value + p
        save()
        return p
    }

    fun rename(id: String, name: String) {
        if (name.isBlank()) return
        list.value = list.value.map { if (it.id == id) it.copy(name = name.trim()) else it }
        save()
    }

    fun remove(id: String, dir: File) {
        if (id == MAIN) return
        if (activeId.value == id) Repository.switchProfile(MAIN)
        list.value = list.value.filter { it.id != id }
        userDataFile(dir, id).delete()
        save()
    }

    /** Wechsel: Nutzerdaten des neuen Profils laden. Kinderprofil verlassen → nur mit PIN (Aufrufer prüft). */
    internal fun setActive(id: String) {
        activeId.value = id
        chosen = true
        save()
    }
}
