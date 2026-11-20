package app.iptv.tv.data

import android.content.Context
import android.content.SharedPreferences
import app.iptv.tv.model.Channel
import app.iptv.tv.model.ContentKind
import app.iptv.tv.model.SeriesShow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Welcher Player verwendet wird (Werte wie in der iOS-App, damit der Sync passt). */
enum class PlayerChoice(val raw: String, val title: String) {
    AUTOMATIC("automatic", "Automatisch"),
    SYSTEM("apple", "ExoPlayer (System)"),
    VLC("vlc", "VLC");

    companion object {
        fun from(raw: String?, default: PlayerChoice) = entries.firstOrNull { it.raw == raw } ?: default
    }
}

/** Einstellungen der App (SharedPreferences). */
object Prefs {
    private lateinit var sp: SharedPreferences
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        initialized = true
        version.value++
        CategoryPrefs.load()
    }

    /** Erhöht sich bei jeder Änderung (für die Oberfläche). */
    val version = MutableStateFlow(0)

    internal fun str(key: String, default: String = ""): String = if (initialized) sp.getString(key, default) ?: default else default
    internal fun putStr(key: String, value: String?) {
        if (!initialized) return
        sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        version.value++
    }
    private fun bool(key: String, default: Boolean) = if (initialized) sp.getBoolean(key, default) else default
    private fun putBool(key: String, value: Boolean) {
        sp.edit().putBoolean(key, value).apply(); version.value++
    }
    private fun long(key: String, default: Long) = if (initialized) sp.getLong(key, default) else default
    private fun putLong(key: String, value: Long) {
        sp.edit().putLong(key, value).apply(); version.value++
    }

    // region Wiedergabe

    var playerLive: PlayerChoice
        get() = PlayerChoice.from(str("playerLive"), PlayerChoice.AUTOMATIC)
        set(v) { putStr("playerLive", v.raw); settingsChangedAt = System.currentTimeMillis() }

    var playerVod: PlayerChoice
        get() = PlayerChoice.from(str("playerVod"), PlayerChoice.AUTOMATIC)
        set(v) { putStr("playerVod", v.raw); settingsChangedAt = System.currentTimeMillis() }

    var settingsChangedAt: Long
        get() = long("settingsChangedAt", 0)
        set(v) = putLong("settingsChangedAt", v)

    /** Bildwiederholrate des Fernsehers an das Video anpassen (z. B. 50 Hz für deutsches TV). */
    var autoFrameRate: Boolean get() = bool("afr", true); set(v) = putBool("afr", v)
    /** Größerer Puffer für Live TV (wackelige Verbindung, dafür langsameres Umschalten). */
    var bigBuffer: Boolean get() = bool("bigBuffer", false); set(v) = putBool("bigBuffer", v)
    /** Bild und Ton von der Hardware synchronisieren lassen (manche Fernseher laufen damit flüssiger). */
    var tunneling: Boolean get() = bool("tunneling", false); set(v) = putBool("tunneling", v)

    // endregion

    // region Untertitel (OpenSubtitles)

    var subtitleApiKey: String get() = str("osApiKey"); set(v) = putStr("osApiKey", v.trim())
    var subtitleUser: String get() = str("osUser"); set(v) = putStr("osUser", v.trim())
    var subtitlePassword: String get() = str("osPass"); set(v) = putStr("osPass", v)
    var subtitleLanguages: String get() = str("osLanguages", "de,en"); set(v) = putStr("osLanguages", v.trim())

    // endregion

    // region Downloads

    /** Speicherlimit in GB, 0 = kein Limit. */
    var downloadLimitGb: Int
        get() = long("dlLimit", 0).toInt()
        set(v) = putLong("dlLimit", v.toLong())
    var downloadAutoDelete: Boolean get() = bool("dlAutoDelete", false); set(v) = putBool("dlAutoDelete", v)
    var downloadPauseDuringPlayback: Boolean get() = bool("dlPause", true); set(v) = putBool("dlPause", v)

    // endregion

    // region Google Drive

    var googleClientId: String get() = str("gClientId"); set(v) = putStr("gClientId", v.trim())
    var googleClientSecret: String get() = str("gClientSecret"); set(v) = putStr("gClientSecret", v.trim())
    var googleRefreshToken: String get() = str("gRefresh"); set(v) = putStr("gRefresh", v)
    var googleEmail: String get() = str("gEmail"); set(v) = putStr("gEmail", v)
    var syncPlaylists: Boolean get() = bool("syncPlaylists", false); set(v) = putBool("syncPlaylists", v)
    var lastSync: Long get() = long("lastSync", 0); set(v) = putLong("lastSync", v)

    // endregion

    // region Kindersicherung

    var pinHash: String get() = str("pinHash"); set(v) = putStr("pinHash", v.ifEmpty { null })
    val hasPin: Boolean get() = pinHash.isNotEmpty()

    fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest(("iptv-pin:" + pin).toByteArray())
            .joinToString("") { "%02x".format(it) }

    // endregion
}

/**
 * Ausgeblendete, gesperrte und umsortierte Kategorien (wie iOS `CategoryPreferences`).
 * Gesperrte Kategorien sind nur nach Eingabe der PIN sichtbar (bis die App geschlossen wird).
 */
object CategoryPrefs {
    @Serializable
    private data class Stored(
        val hidden: Set<String> = emptySet(),
        val locked: Set<String> = emptySet(),
        val order: Map<String, List<String>> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var stored = Stored()

    /** Erhöht sich bei jeder Änderung (löst Neuberechnung der sichtbaren Inhalte aus). */
    val version = MutableStateFlow(0)
    val unlocked = MutableStateFlow(false)

    internal fun load() {
        stored = runCatching { json.decodeFromString(Stored.serializer(), Prefs.str("categories", "{}")) }.getOrDefault(Stored())
        version.value++
    }

    private fun save() {
        Prefs.putStr("categories", json.encodeToString(Stored.serializer(), stored))
        version.value++
    }

    private fun key(kind: ContentKind, name: String) = "${kind.name.lowercase()}|$name"

    fun isHidden(kind: ContentKind, name: String) = key(kind, name) in stored.hidden
    fun isLocked(kind: ContentKind, name: String) = key(kind, name) in stored.locked
    fun isBlocked(kind: ContentKind, name: String) = Prefs.hasPin && !unlocked.value && isLocked(kind, name)
    val hasLocked: Boolean get() = Prefs.hasPin && stored.locked.isNotEmpty()
    fun hasLocked(kind: ContentKind): Boolean = Prefs.hasPin && stored.locked.any { it.startsWith(kind.name.lowercase() + "|") }

    private val hasRestrictions get() = stored.hidden.isNotEmpty() || (Prefs.hasPin && !unlocked.value && stored.locked.isNotEmpty())

    fun isVisible(c: Channel) = !isHidden(c.kind, c.category) && !isBlocked(c.kind, c.category)
    fun isVisible(s: SeriesShow) = !isHidden(ContentKind.SERIES, s.category) && !isBlocked(ContentKind.SERIES, s.category)

    fun filterChannels(list: List<Channel>) = if (hasRestrictions) list.filter { isVisible(it) } else list
    fun filterShows(list: List<SeriesShow>) = if (hasRestrictions) list.filter { isVisible(it) } else list

    /** Kategorien in eigener Reihenfolge; ausgeblendete nur auf Wunsch. */
    fun arranged(categories: List<Category>, kind: ContentKind, includeHidden: Boolean = false): List<Category> {
        val order = stored.order[kind.name.lowercase()]
        val result = if (order.isNullOrEmpty()) categories else {
            val pos = order.withIndex().associate { it.value to it.index }
            categories.withIndex().sortedWith(compareBy({ pos[it.value.name] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
        }
        return if (includeHidden) result else result.filter { !isHidden(kind, it.name) && !isBlocked(kind, it.name) }
    }

    fun setHidden(kind: ContentKind, name: String, hidden: Boolean) {
        val k = key(kind, name)
        stored = stored.copy(hidden = if (hidden) stored.hidden + k else stored.hidden - k); save()
    }

    fun setLocked(kind: ContentKind, name: String, locked: Boolean) {
        val k = key(kind, name)
        stored = stored.copy(locked = if (locked) stored.locked + k else stored.locked - k); save()
    }

    /** Verschiebt eine Kategorie um `delta` Plätze (in der aktuell angezeigten Reihenfolge). */
    fun move(kind: ContentKind, current: List<Category>, index: Int, delta: Int) {
        val target = index + delta
        if (index !in current.indices || target !in current.indices) return
        val names = current.map { it.name }.toMutableList()
        names.add(target, names.removeAt(index))
        stored = stored.copy(order = stored.order + (kind.name.lowercase() to names)); save()
    }

    fun resetOrder(kind: ContentKind) {
        stored = stored.copy(order = stored.order - kind.name.lowercase()); save()
    }

    fun unlock(pin: String): Boolean {
        if (!Prefs.hasPin || Prefs.hash(pin) != Prefs.pinHash) return false
        unlocked.value = true
        version.value++
        return true
    }

    fun lock() {
        if (unlocked.value) {
            unlocked.value = false
            version.value++
        }
    }

    fun setPin(pin: String?) {
        if (pin.isNullOrEmpty()) {
            Prefs.pinHash = ""
            unlocked.value = false
            stored = stored.copy(locked = emptySet()); save()
        } else {
            Prefs.pinHash = Prefs.hash(pin)
            unlocked.value = true
            version.value++
        }
    }
}
