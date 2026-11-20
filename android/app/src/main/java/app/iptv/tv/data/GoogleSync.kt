package app.iptv.tv.data

import android.util.Log
import app.iptv.tv.model.Channel
import app.iptv.tv.model.ContentKind
import app.iptv.tv.model.Playlist
import app.iptv.tv.model.PlaylistSource
import app.iptv.tv.model.XtreamCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone

// region Datenformat (identisch zur iOS-App: SyncSnapshot, JSON mit ISO-8601-Daten)

data class FavoriteState(val isFavorite: Boolean, val changedAt: Long)
data class HistoryEntry(val key: String, val channel: Channel, val date: Long)
data class SyncProgress(val channel: Channel, val position: Double, val duration: Double, val updated: Long)
data class SyncSettings(val playerLive: String, val playerVod: String, val changedAt: Long)

data class SyncSnapshot(
    val updatedAt: Long,
    val favorites: Map<String, FavoriteState>,
    val history: List<HistoryEntry>,
    val progress: Map<String, SyncProgress>,
    val settings: SyncSettings?,
    val playlists: List<Playlist>?,
    val deletedPlaylists: Map<String, Long>?,
) {
    fun toJson(): String = SnapshotCodec.encode(this)

    companion object {
        fun fromJson(text: String): SyncSnapshot = SnapshotCodec.decode(text)

        /** Führt zwei Stände zusammen; je Eintrag gewinnt die neuere Änderung (wie iOS). */
        fun merge(local: SyncSnapshot, remote: SyncSnapshot): SyncSnapshot {
            val favorites = HashMap(local.favorites)
            remote.favorites.forEach { (k, theirs) ->
                val mine = favorites[k]
                if (mine == null || theirs.changedAt > mine.changedAt) favorites[k] = theirs
            }
            val history = HashMap<String, HistoryEntry>()
            for (e in local.history + remote.history) {
                if ((history[e.key]?.date ?: Long.MIN_VALUE) < e.date) history[e.key] = e
            }
            val progress = HashMap(local.progress)
            remote.progress.forEach { (k, theirs) ->
                val mine = progress[k]
                if (mine == null || theirs.updated > mine.updated) progress[k] = theirs
            }
            val settings = remote.settings?.takeIf { it.changedAt > (local.settings?.changedAt ?: Long.MIN_VALUE) } ?: local.settings
            val deleted = HashMap<String, Long>()
            (local.deletedPlaylists.orEmpty().entries + remote.deletedPlaylists.orEmpty().entries).forEach { (k, v) ->
                val key = k.uppercase()
                deleted[key] = maxOf(deleted[key] ?: Long.MIN_VALUE, v)
            }
            val playlists: List<Playlist>?
            val deletedOut: Map<String, Long>?
            if (local.playlists != null) {
                val byId = LinkedHashMap<String, Playlist>()
                local.playlists.forEach { byId[it.id.uppercase()] = it }
                remote.playlists.orEmpty().forEach { byId.putIfAbsent(it.id.uppercase(), it) }
                playlists = byId.filterKeys { it !in deleted }.values.sortedBy { it.name }
                deletedOut = deleted
            } else {
                // Dieses Gerät synchronisiert keine Playlists: die der anderen Geräte erhalten.
                playlists = remote.playlists
                deletedOut = remote.deletedPlaylists
            }
            return SyncSnapshot(
                updatedAt = local.updatedAt,
                favorites = favorites,
                history = history.values.sortedByDescending { it.date },
                progress = progress,
                settings = settings,
                playlists = playlists,
                deletedPlaylists = deletedOut,
            )
        }
    }
}

internal object SnapshotCodec {
    private val json = Json { ignoreUnknownKeys = true }

    private fun formatter() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** Swift `.iso8601` erwartet Datumsangaben ohne Sekundenbruchteile. */
    fun date(ms: Long): String = formatter().format(java.util.Date(ms))

    fun parseDate(text: String?): Long? {
        val t = text?.trim() ?: return null
        val clean = t.replace(Regex("\\.\\d+"), "")
        return runCatching { formatter().parse(clean.replace(Regex("\\+00:00$"), "Z"))!!.time }.getOrNull()
            ?: runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(clean)!!.time }.getOrNull()
    }

    fun channelJson(c: Channel) = buildJsonObject {
        put("name", c.name)
        put("url", c.url)
        c.group?.let { put("group", it) }
        c.tvgId?.let { put("tvgID", it) }
        c.logo?.let { put("logoURL", it) }
        put("kind", c.kind.name.lowercase())
    }

    fun channelFrom(o: JsonObject): Channel? {
        val name = o.str("name") ?: return null
        val url = o.str("url") ?: return null
        val group = o.str("group")
        val kind = when (o.str("kind")) {
            "live" -> ContentKind.LIVE
            "movie" -> ContentKind.MOVIE
            "series" -> ContentKind.SERIES
            else -> ContentClassifier.kind(url, group, name)
        }
        return Channel(name = name, url = url, group = group, tvgId = o.str("tvgID"), logo = o.str("logoURL"), kind = kind)
    }

    fun playlistJson(p: Playlist) = buildJsonObject {
        put("id", p.id.uppercase())
        put("name", p.name)
        put(
            "source",
            when (val s = p.source) {
                is PlaylistSource.M3u -> buildJsonObject { put("m3u", buildJsonObject { put("url", s.url) }) }
                is PlaylistSource.Xtream -> buildJsonObject {
                    put(
                        "xtream",
                        buildJsonObject {
                            put(
                                "_0",
                                buildJsonObject {
                                    put("server", s.credentials.server)
                                    put("username", s.credentials.username)
                                    put("password", s.credentials.password)
                                },
                            )
                        },
                    )
                }
                is PlaylistSource.File -> buildJsonObject { put("file", buildJsonObject { put("originalName", s.originalName) }) }
            },
        )
        p.customEpgUrl?.let { put("customEPGURL", it) }
        p.detectedEpgUrl?.let { put("detectedEPGURL", it) }
        p.lastUpdated?.let { put("lastUpdated", date(it)) }
    }

    fun playlistFrom(o: JsonObject): Playlist? {
        val id = o.str("id") ?: return null
        val name = o.str("name") ?: return null
        val src = o["source"] as? JsonObject ?: return null
        val source = when {
            src["m3u"] is JsonObject -> PlaylistSource.M3u((src["m3u"] as JsonObject).str("url") ?: return null)
            src["xtream"] is JsonObject -> {
                val x = (src["xtream"] as JsonObject).let { (it["_0"] as? JsonObject) ?: it }
                PlaylistSource.Xtream(
                    XtreamCredentials(x.str("server") ?: return null, x.str("username") ?: return null, x.str("password") ?: return null),
                )
            }
            src["file"] is JsonObject -> PlaylistSource.File((src["file"] as JsonObject).str("originalName") ?: "Datei")
            else -> return null
        }
        return Playlist(
            id = id,
            name = name,
            source = source,
            customEpgUrl = o.str("customEPGURL"),
            detectedEpgUrl = o.str("detectedEPGURL"),
            lastUpdated = parseDate(o.str("lastUpdated")),
        )
    }

    fun encode(s: SyncSnapshot): String = buildJsonObject {
        put("version", 1)
        put("updatedAt", date(s.updatedAt))
        put("favorites", buildJsonObject {
            s.favorites.forEach { (k, f) ->
                put(k, buildJsonObject { put("isFavorite", f.isFavorite); put("changedAt", date(f.changedAt)) })
            }
        })
        put("history", buildJsonArray {
            s.history.forEach { h ->
                add(buildJsonObject { put("key", h.key); put("channel", channelJson(h.channel)); put("date", date(h.date)) })
            }
        })
        put("progress", buildJsonObject {
            s.progress.forEach { (k, p) ->
                put(k, buildJsonObject {
                    put("channel", channelJson(p.channel))
                    put("position", p.position)
                    put("duration", p.duration)
                    put("updated", date(p.updated))
                })
            }
        })
        s.settings?.let {
            put("settings", buildJsonObject {
                put("playerLive", it.playerLive); put("playerVOD", it.playerVod); put("changedAt", date(it.changedAt))
            })
        }
        s.playlists?.let { list -> put("playlists", buildJsonArray { list.forEach { add(playlistJson(it)) } }) }
        // Swift kodiert [UUID: Date] als flache Liste [id, datum, id, datum, …].
        s.deletedPlaylists?.let { map ->
            put("deletedPlaylists", buildJsonArray { map.forEach { (k, v) -> add(JsonPrimitive(k.uppercase())); add(JsonPrimitive(date(v))) } })
        }
    }.toString()

    fun decode(text: String): SyncSnapshot {
        val root = json.parseToJsonElement(text) as JsonObject
        val favorites = (root["favorites"] as? JsonObject)?.mapNotNull { (k, v) ->
            val o = v as? JsonObject ?: return@mapNotNull null
            k to FavoriteState(o.str("isFavorite") == "true", parseDate(o.str("changedAt")) ?: 0)
        }?.toMap() ?: emptyMap()
        val history = (root["history"] as? JsonArray)?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val ch = (o["channel"] as? JsonObject)?.let(::channelFrom) ?: return@mapNotNull null
            HistoryEntry(o.str("key") ?: ch.stableKey, ch, parseDate(o.str("date")) ?: 0)
        } ?: emptyList()
        val progress = (root["progress"] as? JsonObject)?.mapNotNull { (k, v) ->
            val o = v as? JsonObject ?: return@mapNotNull null
            val ch = (o["channel"] as? JsonObject)?.let(::channelFrom) ?: return@mapNotNull null
            k to SyncProgress(ch, o.str("position")?.toDoubleOrNull() ?: 0.0, o.str("duration")?.toDoubleOrNull() ?: 0.0, parseDate(o.str("updated")) ?: 0)
        }?.toMap() ?: emptyMap()
        val settings = (root["settings"] as? JsonObject)?.let {
            SyncSettings(it.str("playerLive") ?: "automatic", it.str("playerVOD") ?: "automatic", parseDate(it.str("changedAt")) ?: 0)
        }
        val playlists = (root["playlists"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::playlistFrom) }
        val deleted = when (val d = root["deletedPlaylists"]) {
            is JsonArray -> d.chunked(2).mapNotNull { pair ->
                val k = (pair.getOrNull(0) as? JsonPrimitive)?.content ?: return@mapNotNull null
                val v = parseDate((pair.getOrNull(1) as? JsonPrimitive)?.content) ?: return@mapNotNull null
                k.uppercase() to v
            }.toMap()
            is JsonObject -> d.mapNotNull { (k, v) -> parseDate((v as? JsonPrimitive)?.content)?.let { k.uppercase() to it } }.toMap()
            else -> null
        }
        return SyncSnapshot(parseDate(root.str("updatedAt")) ?: 0, favorites, history, progress, settings, playlists, deleted)
    }
}

/**
 * Entfernt Zugangsdaten aus Stream-Links, bevor sie in Google Drive landen, und setzt sie
 * beim Herunterladen mit den lokal gespeicherten Daten wieder ein (wie iOS `CredentialRedactor`).
 */
class CredentialRedactor(playlists: List<Playlist>) {
    private data class Cred(val host: String?, val user: String, val pass: String)

    private val creds: List<Cred> = playlists.mapNotNull { p ->
        when (val s = p.source) {
            is PlaylistSource.Xtream -> Cred(host(s.credentials.server), s.credentials.username, s.credentials.password)
            is PlaylistSource.M3u -> UrlTools.xtreamCredentials(s.url)?.let { Cred(host(s.url), it.username, it.password) }
                ?: queryCreds(s.url)?.let { (u, pw) -> Cred(host(s.url), u, pw) }
            is PlaylistSource.File -> null
        }
    }

    private fun host(url: String) = runCatching { URI(url).host }.getOrNull()

    private fun queryCreds(url: String): Pair<String, String>? {
        val q = url.substringAfter('?', "").split('&').associate {
            it.substringBefore('=') to runCatching { URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
        }
        val u = q["username"]?.takeIf { it.isNotEmpty() } ?: return null
        val p = q["password"]?.takeIf { it.isNotEmpty() } ?: return null
        return u to p
    }

    fun redact(c: Channel): Channel {
        val h = host(c.url)
        var url = c.url
        for (cred in creds) {
            if (cred.host != null && cred.host != h) continue
            val schemeEnd = url.indexOf("://").let { if (it < 0) 0 else it + 3 }
            val pathStart = url.indexOf('/', schemeEnd).let { if (it < 0) url.length else it }
            val queryStart = url.indexOf('?', pathStart).let { if (it < 0) url.length else it }
            val path = url.substring(pathStart, queryStart).split('/').joinToString("/") { seg ->
                val decoded = runCatching { URLDecoder.decode(seg, "UTF-8") }.getOrDefault(seg)
                when (decoded) {
                    cred.user -> USER
                    cred.pass -> PASS
                    else -> seg
                }
            }
            var query = url.substring(queryStart)
            if (query.isNotEmpty()) {
                query = "?" + query.drop(1).split('&').joinToString("&") { item ->
                    val k = item.substringBefore('=')
                    val v = runCatching { URLDecoder.decode(item.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
                    when {
                        k == "username" && v == cred.user -> "$k=$USER"
                        k == "password" && v == cred.pass -> "$k=$PASS"
                        else -> item
                    }
                }
            }
            url = url.substring(0, pathStart) + path + query
        }
        return if (url == c.url) c else c.copy(url = url)
    }

    fun restore(c: Channel): Channel {
        if (!c.url.contains(USER) && !c.url.contains(PASS)) return c
        val h = host(c.url)
        val cred = creds.firstOrNull { it.host == h } ?: creds.firstOrNull() ?: return c
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return c.copy(url = c.url.replace(USER, enc(cred.user)).replace(PASS, enc(cred.pass)))
    }

    companion object {
        const val USER = "__IPTV_USER__"
        const val PASS = "__IPTV_PASS__"
    }
}

// endregion

/**
 * Gleicht Favoriten, Verlauf, Fortschritt, Player-Einstellungen und optional Playlists über
 * eine Datei im versteckten App-Ordner von Google Drive ab (dieselbe Datei wie die iOS-App).
 *
 * Anmeldung per „Geräte-Code“ (wie YouTube auf dem Fernseher): Der Fernseher zeigt einen Code,
 * der am Handy unter google.com/device bestätigt wird.
 */
object GoogleSync {
    private const val TAG = "GoogleSync"
    private const val FILE_NAME = "iptv-sync.json"
    private const val SCOPES = "openid email https://www.googleapis.com/auth/drive.appdata"
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client: OkHttpClient by lazy { OkHttpClient.Builder().build() }

    data class DeviceLogin(val userCode: String, val url: String, val expiresAt: Long)

    val isSyncing = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)
    val login = MutableStateFlow<DeviceLogin?>(null)
    val signedIn = MutableStateFlow(false)

    private var accessToken: String? = null
    private var accessExpires = 0L
    private var debounce: Job? = null
    private var loginJob: Job? = null

    val isConfigured: Boolean get() = Prefs.googleClientId.isNotEmpty() && Prefs.googleClientSecret.isNotEmpty()

    fun start() {
        signedIn.value = Prefs.googleRefreshToken.isNotEmpty()
        Repository.onChange = { scheduleSync() }
        // Erst abgleichen, wenn die eigenen Daten geladen sind (sonst würde ein leerer Stand verglichen).
        if (signedIn.value) scope.launch {
            Repository.restored.first { it }
            delay(5_000)
            sync()
        }
    }

    // region Anmeldung

    fun startSignIn() {
        loginJob?.cancel()
        lastError.value = null
        loginJob = scope.launch {
            try {
                val res = post(
                    "https://oauth2.googleapis.com/device/code",
                    FormBody.Builder().add("client_id", Prefs.googleClientId).add("scope", SCOPES).build(),
                )
                val deviceCode = res.str("device_code") ?: throw UserFacingException("Google hat keinen Code geliefert.")
                var interval = (res.int("interval") ?: 5).toLong()
                val expires = System.currentTimeMillis() + (res.int("expires_in") ?: 1800) * 1000L
                login.value = DeviceLogin(
                    res.str("user_code") ?: "",
                    res.str("verification_url") ?: res.str("verification_uri") ?: "https://www.google.com/device",
                    expires,
                )
                while (System.currentTimeMillis() < expires) {
                    delay(interval * 1000)
                    val token = postRaw(
                        "https://oauth2.googleapis.com/token",
                        FormBody.Builder()
                            .add("client_id", Prefs.googleClientId)
                            .add("client_secret", Prefs.googleClientSecret)
                            .add("device_code", deviceCode)
                            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                            .build(),
                    )
                    when (token.str("error")) {
                        null -> {
                            Prefs.googleRefreshToken = token.str("refresh_token") ?: ""
                            accessToken = token.str("access_token")
                            accessExpires = System.currentTimeMillis() + ((token.int("expires_in") ?: 3600) - 60) * 1000L
                            Prefs.googleEmail = token.str("id_token")?.let(::emailFromIdToken) ?: ""
                            signedIn.value = Prefs.googleRefreshToken.isNotEmpty()
                            login.value = null
                            sync()
                            return@launch
                        }
                        "authorization_pending" -> Unit
                        "slow_down" -> interval += 5
                        "access_denied" -> throw UserFacingException("Die Anmeldung wurde abgelehnt.")
                        else -> throw UserFacingException("Google-Anmeldung fehlgeschlagen: ${token.str("error_description") ?: token.str("error")}")
                    }
                }
                throw UserFacingException("Der Code ist abgelaufen. Bitte erneut versuchen.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Anmeldung", e)
                login.value = null
                lastError.value = Http.message(e)
            }
        }
    }

    fun cancelSignIn() {
        loginJob?.cancel()
        login.value = null
    }

    fun signOut() {
        Prefs.googleRefreshToken = ""
        Prefs.googleEmail = ""
        Prefs.lastSync = 0
        accessToken = null
        signedIn.value = false
    }

    fun emailFromIdToken(idToken: String): String? = runCatching {
        val payload = String(Base64.getUrlDecoder().decode(idToken.split('.')[1]))
        (json.parseToJsonElement(payload) as JsonObject).str("email")
    }.getOrNull()

    private suspend fun validToken(): String {
        accessToken?.takeIf { System.currentTimeMillis() < accessExpires }?.let { return it }
        val refresh = Prefs.googleRefreshToken.takeIf { it.isNotEmpty() } ?: throw NotSignedIn()
        val res = postRaw(
            "https://oauth2.googleapis.com/token",
            FormBody.Builder()
                .add("client_id", Prefs.googleClientId)
                .add("client_secret", Prefs.googleClientSecret)
                .add("refresh_token", refresh)
                .add("grant_type", "refresh_token")
                .build(),
        )
        if (res.str("error") == "invalid_grant") throw NotSignedIn()
        val token = res.str("access_token") ?: throw UserFacingException("Google-Anmeldung fehlgeschlagen: ${res.str("error") ?: "?"}")
        accessToken = token
        accessExpires = System.currentTimeMillis() + ((res.int("expires_in") ?: 3600) - 60) * 1000L
        return token
    }

    private class NotSignedIn : Exception()

    // endregion

    // region Abgleich

    /** Nach lokalen Änderungen: in 20 Sekunden abgleichen (mehrere Änderungen gebündelt). */
    fun scheduleSync() {
        if (!signedIn.value) return
        debounce?.cancel()
        debounce = scope.launch {
            delay(20_000)
            sync()
        }
    }

    fun syncNow() {
        scope.launch { sync() }
    }

    suspend fun sync() = withContext(Dispatchers.IO) {
        if (!signedIn.value || !isConfigured || isSyncing.value) return@withContext
        isSyncing.value = true
        lastError.value = null
        try {
            val token = validToken()
            val remoteFile = download(token)
            val remote = remoteFile?.second?.let { runCatching { SyncSnapshot.fromJson(it) }.getOrNull() }
            val local = localSnapshot()
            val merged = remote?.let { SyncSnapshot.merge(local, it) } ?: local

            // Zuerst Playlists (ihre Zugangsdaten werden für Verlauf/Fortschritt gebraucht).
            if (Prefs.syncPlaylists && merged.playlists != null) {
                Repository.applySyncedPlaylists(merged.playlists, merged.deletedPlaylists.orEmpty())
            }
            applyLocally(merged)

            val redactor = CredentialRedactor(Repository.playlists.value)
            val upload = merged.copy(
                updatedAt = System.currentTimeMillis(),
                history = merged.history.map { it.copy(channel = redactor.redact(it.channel)) },
                progress = merged.progress.mapValues { (_, p) -> p.copy(channel = redactor.redact(p.channel)) },
            )
            upload(token, upload.toJson(), remoteFile?.first)
            Prefs.lastSync = System.currentTimeMillis()
        } catch (e: NotSignedIn) {
            signOut()
            lastError.value = "Die Google-Anmeldung ist abgelaufen. Bitte melde dich erneut an."
        } catch (e: Exception) {
            Log.w(TAG, "Sync", e)
            lastError.value = Http.message(e)
        } finally {
            isSyncing.value = false
        }
    }

    private fun localSnapshot(): SyncSnapshot {
        val playlists = Repository.playlists.value
        val redactor = CredentialRedactor(playlists)
        val favs = Repository.favorites.value
        val changes = Repository.favoriteChanges
        val favorites = (changes.keys + favs).associateWith { FavoriteState(it in favs, changes[it] ?: 0L) }
        return SyncSnapshot(
            updatedAt = System.currentTimeMillis(),
            favorites = favorites,
            history = Repository.recents.value.map { HistoryEntry(it.channel.stableKey, redactor.redact(it.channel), it.time) },
            progress = Repository.progress.value.mapNotNull { (k, p) ->
                p.channel?.let { k to SyncProgress(redactor.redact(it), p.position / 1000.0, p.duration / 1000.0, p.updated) }
            }.toMap(),
            // Player-Einstellungen nicht zwischen iPhone und Fernseher abgleichen: die passende Wahl hängt
            // vom Gerät ab (iPhone: Apple-Player, Sony-TV: VLC). Die Werte des iPhones bleiben unverändert.
            settings = null,
            playlists = if (Prefs.syncPlaylists) playlists else null,
            deletedPlaylists = if (Prefs.syncPlaylists) Repository.deletedPlaylists.value else null,
        )
    }

    private fun applyLocally(merged: SyncSnapshot) {
        val redactor = CredentialRedactor(Repository.playlists.value)
        val old = Repository.progress.value
        Repository.applySyncedUserData(
            favs = merged.favorites.filterValues { it.isFavorite }.keys,
            changes = merged.favorites.mapValues { it.value.changedAt },
            history = merged.history.map { h ->
                val ch = redactor.restore(h.channel)
                RecentEntry(ch, h.date, Repository.showKeyFor(ch))
            },
            prog = merged.progress.mapValues { (k, p) ->
                val ch = redactor.restore(p.channel)
                Progress((p.position * 1000).toLong(), (p.duration * 1000).toLong(), p.updated, ch, old[k]?.showKey ?: Repository.showKeyFor(ch))
            },
        )

    }

    // endregion

    // region Google Drive (versteckter App-Ordner)

    private fun download(token: String): Pair<String, String>? {
        val q = URLEncoder.encode("name = '$FILE_NAME'", "UTF-8")
        val list = get("https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=$q&fields=files(id)", token)
        val id = ((json.parseToJsonElement(list) as JsonObject)["files"] as? JsonArray)
            ?.firstOrNull()?.let { (it as JsonObject).str("id") } ?: return null
        return id to get("https://www.googleapis.com/drive/v3/files/$id?alt=media", token)
    }

    private fun upload(token: String, data: String, existingId: String?) {
        val request = if (existingId != null) {
            Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files/$existingId?uploadType=media")
                .patch(data.toRequestBody("application/json".toMediaType()))
        } else {
            val boundary = "iptv-" + java.util.UUID.randomUUID()
            val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" +
                "{\"name\":\"$FILE_NAME\",\"parents\":[\"appDataFolder\"]}\r\n" +
                "--$boundary\r\nContent-Type: application/json\r\n\r\n$data\r\n--$boundary--\r\n"
            Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
                .post(body.toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
        }
        send(request.header("Authorization", "Bearer $token").build())
    }

    private fun get(url: String, token: String) = send(Request.Builder().url(url).header("Authorization", "Bearer $token").build())

    private fun send(request: Request): String = client.newCall(request).execute().use { r ->
        val text = r.body?.string() ?: ""
        if (r.code == 401 || r.code == 403) throw UserFacingException("Google Drive hat den Zugriff verweigert. Bitte melde dich erneut an.")
        if (!r.isSuccessful) throw UserFacingException("Google Drive meldet Fehler ${r.code}. ${text.take(150)}")
        text
    }

    private fun post(url: String, body: FormBody): JsonObject {
        val res = postRaw(url, body)
        res.str("error")?.let {
            throw UserFacingException(
                if (it == "invalid_client" || it == "unauthorized_client") {
                    "Google lehnt die Client-ID ab. Bitte prüfe Client-ID und Client-Secret (Typ „Fernseher und Geräte mit begrenzter Eingabe“)."
                } else "Google meldet: ${res.str("error_description") ?: it}",
            )
        }
        return res
    }

    private fun postRaw(url: String, body: FormBody): JsonObject = client.newCall(Request.Builder().url(url).post(body).build()).execute().use { r ->
        val text = r.body?.string() ?: "{}"
        runCatching { json.parseToJsonElement(text) as JsonObject }.getOrElse { throw UserFacingException("Unerwartete Antwort von Google (${r.code}).") }
    }

    // endregion

    @Suppress("unused")
    private fun JsonElement.asObj() = this as? JsonObject
}
