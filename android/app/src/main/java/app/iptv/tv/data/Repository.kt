package app.iptv.tv.data

import android.content.Context
import android.util.Log
import android.util.Xml
import app.iptv.tv.model.Channel
import app.iptv.tv.model.EpgGuide
import app.iptv.tv.model.Episode
import app.iptv.tv.model.MediaInfo
import app.iptv.tv.model.Playlist
import app.iptv.tv.model.PlaylistData
import app.iptv.tv.model.PlaylistSource
import app.iptv.tv.model.SeriesShow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File

@Serializable
private data class LibraryFile(val playlists: List<Playlist> = emptyList(), val activeId: String? = null)

@Serializable
data class Progress(val position: Long, val duration: Long, val updated: Long)

/** Eintrag in „Zuletzt gesehen“. Bei Episoden zusätzlich der Schlüssel der Serie. */
@Serializable
data class RecentEntry(val channel: Channel, val time: Long, val showKey: String? = null)

@Serializable
private data class UserDataFile(
    val favorites: List<String> = emptyList(),
    val recents: List<RecentEntry> = emptyList(),
    val progress: Map<String, Progress> = emptyMap(),
)

/**
 * Verwaltet Playlists, lädt die aktive Playlist samt TV-Guide und speichert alles lokal,
 * damit die App sofort und offline startet. Nutzerdaten (Favoriten, Verlauf, Fortschritt) ebenso.
 */
object Repository {
    private const val TAG = "Repository"
    private const val EPG_MAX_AGE = 6 * 3600_000L
    private const val TOO_BIG = "Die Playlist ist zu groß für den Speicher des Fernsehers."

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var dir: File
    private lateinit var cacheDir: File
    private var started = false

    val playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val activeId = MutableStateFlow<String?>(null)
    val content = MutableStateFlow(LibraryContent.EMPTY)
    val epg = MutableStateFlow<EpgGuide?>(null)
    /** null = noch nicht geladen (Startbildschirm zeigt Ladeanzeige). */
    val restored = MutableStateFlow(false)
    val isLoading = MutableStateFlow(false)
    val statusMessage = MutableStateFlow<String?>(null)

    val favorites = MutableStateFlow<Set<String>>(emptySet())
    val recents = MutableStateFlow<List<RecentEntry>>(emptyList())
    val progress = MutableStateFlow<Map<String, Progress>>(emptyMap())

    @Volatile var playbackActive = false
    private var refreshJob: Job? = null
    private val seriesCache = HashMap<String, Pair<MediaInfo?, List<Episode>>>()
    private val movieInfoCache = HashMap<String, MediaInfo?>()

    val activePlaylist: Playlist? get() = playlists.value.firstOrNull { it.id == activeId.value }

    fun start(context: Context) {
        if (started) return
        started = true
        dir = context.filesDir
        cacheDir = context.cacheDir
        scope.launch {
            restoreUserData()
            read(File(dir, "library.json"), LibraryFile.serializer())?.let {
                playlists.value = it.playlists
                activeId.value = it.activeId ?: it.playlists.firstOrNull()?.id
            }
            val active = activePlaylist
            if (active != null) {
                read(contentFile(active.id), PlaylistData.serializer())?.let { content.value = LibraryContent(it) }
                epg.value = read(epgFile(active.id), EpgGuide.serializer())
            }
            restored.value = true
            if (active != null) {
                if (content.value.isEmpty) refresh() else refreshInBackground(8_000)
            }
        }
    }

    // region Playlists

    /** Legt eine Playlist an und lädt sie. Bei Fehlern wird nichts gespeichert (Rückgabe: Fehlermeldung). */
    suspend fun addPlaylist(name: String, source: PlaylistSource): String? = withContext(Dispatchers.Default) {
        isLoading.value = true
        try {
            var playlist = Playlist(name = name, source = source)
            val (data, epgUrl, usedSource) = fetch(playlist)
            playlist = playlist.copy(source = usedSource, detectedEpgUrl = epgUrl, lastUpdated = System.currentTimeMillis())
            write(contentFile(playlist.id), PlaylistData.serializer(), data)
            playlists.value = playlists.value + playlist
            activate(playlist.id, data) // lädt auch den TV-Guide
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "addPlaylist", e)
            TOO_BIG
        } catch (e: Exception) {
            Log.w(TAG, "addPlaylist", e)
            Http.message(e)
        } finally {
            isLoading.value = false
        }
    }

    suspend fun activate(id: String, preloaded: PlaylistData? = null): Unit = withContext(Dispatchers.Default) {
        val playlist = playlists.value.firstOrNull { it.id == id } ?: return@withContext
        activeId.value = id
        seriesCache.clear()
        movieInfoCache.clear()
        val data = preloaded ?: withContext(Dispatchers.IO) { read(contentFile(id), PlaylistData.serializer()) }
        content.value = data?.let { LibraryContent(it) } ?: LibraryContent.EMPTY
        epg.value = withContext(Dispatchers.IO) { read(epgFile(id), EpgGuide.serializer()) }
        saveLibrary()
        if (data == null) refresh() else if (epg.value == null) scope.launch { refreshEpg(force = true) }
        Log.i(TAG, "aktiv: ${playlist.name}")
    }

    suspend fun delete(id: String) {
        playlists.value = playlists.value.filterNot { it.id == id }
        contentFile(id).delete()
        epgFile(id).delete()
        if (activeId.value == id) {
            val next = playlists.value.firstOrNull()
            activeId.value = null
            content.value = LibraryContent.EMPTY
            epg.value = null
            if (next != null) activate(next.id)
        }
        saveLibrary()
    }

    /** Lädt die aktive Playlist neu (Fehler werden als Statusmeldung angezeigt, Stand bleibt erhalten). */
    suspend fun refresh(): Unit = withContext(Dispatchers.Default) {
        val playlist = activePlaylist ?: return@withContext
        isLoading.value = true
        statusMessage.value = null
        try {
            val (data, epgUrl, usedSource) = fetch(playlist)
            write(contentFile(playlist.id), PlaylistData.serializer(), data)
            if (activeId.value == playlist.id) {
                seriesCache.clear()
                content.value = LibraryContent(data)
            }
            updatePlaylist(playlist.id) {
                it.copy(source = usedSource, detectedEpgUrl = epgUrl, lastUpdated = System.currentTimeMillis())
            }
            refreshEpg(force = true)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "refresh", e)
            statusMessage.value = TOO_BIG
        } catch (e: Exception) {
            Log.w(TAG, "refresh", e)
            statusMessage.value = "Aktualisieren fehlgeschlagen: ${Http.message(e)}"
        } finally {
            isLoading.value = false
        }
    }

    /** Aktualisiert im Hintergrund – erst wenn gerade nichts abgespielt wird (spart Bandbreite). */
    fun refreshInBackground(delayMs: Long = 0) {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            delay(delayMs)
            while (playbackActive) delay(5_000)
            refresh()
        }
    }

    private data class Fetched(val data: PlaylistData, val epgUrl: String?, val source: PlaylistSource)

    private suspend fun fetch(playlist: Playlist): Fetched = withContext(Dispatchers.IO) {
        when (val source = playlist.source) {
            is PlaylistSource.Xtream -> {
                val client = XtreamClient(source.credentials)
                Fetched(client.loadContent(), client.xmltvUrl, source)
            }
            is PlaylistSource.M3u -> {
                // get.php-Links lieber über die (zuverlässigere) Xtream-Schnittstelle laden.
                val credentials = UrlTools.xtreamCredentials(source.url)
                if (credentials != null) {
                    try {
                        val client = XtreamClient(credentials)
                        return@withContext Fetched(client.loadContent(), client.xmltvUrl, PlaylistSource.Xtream(credentials))
                    } catch (e: Exception) {
                        Log.w(TAG, "Xtream-API fehlgeschlagen, versuche M3U", e)
                    }
                }
                val result = Http.stream(completedPanelUrl(source.url)) { M3UParser.parse(it.bufferedReader()) }
                Fetched(PlaylistData(result.channels), result.epgUrl, source)
            }
        }
    }

    /** Ergänzt bei get.php fehlende Parameter (`type=m3u_plus`, `output=ts`). */
    private fun completedPanelUrl(url: String): String {
        if (!UrlTools.path(url).lowercase().endsWith("get.php")) return url
        var result = url
        val sep = { if (result.contains('?')) "&" else "?" }
        if (!url.contains("type=", ignoreCase = true)) result += sep() + "type=m3u_plus"
        if (!url.contains("output=", ignoreCase = true)) result += sep() + "output=ts"
        return result
    }

    private suspend fun refreshEpg(force: Boolean) {
        val playlist = activePlaylist ?: return
        val url = playlist.epgUrl ?: return
        val current = epg.value
        if (!force && current != null && System.currentTimeMillis() - current.fetchedAt < EPG_MAX_AGE) return
        val channels = content.value.live
        if (channels.isEmpty()) return
        withContext(Dispatchers.IO) {
            val tmp = File(cacheDir, "epg-download.tmp")
            try {
                Http.download(url, tmp)
                val guide = tmp.inputStream().use { EpgParser.parse(it, channels, Xml.newPullParser()) }
                if (guide.programmes.isNotEmpty() || current == null) {
                    write(epgFile(playlist.id), EpgGuide.serializer(), guide)
                    if (activeId.value == playlist.id) epg.value = guide
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "EPG", e)
            } finally {
                tmp.delete()
            }
        }
    }

    // endregion

    // region Details

    suspend fun seriesDetails(show: SeriesShow): Pair<MediaInfo?, List<Episode>> {
        seriesCache[show.favoriteKey]?.let { return it }
        val source = activePlaylist?.source as? PlaylistSource.Xtream
        if (source == null || show.xtreamSeriesId == null) return null to show.episodes
        val result = withContext(Dispatchers.IO) { XtreamClient(source.credentials).seriesDetails(show) }
        seriesCache[show.favoriteKey] = result
        return result
    }

    suspend fun movieInfo(movie: Channel): MediaInfo? {
        if (movieInfoCache.containsKey(movie.stableKey)) return movieInfoCache[movie.stableKey]
        val source = activePlaylist?.source as? PlaylistSource.Xtream ?: return null
        val id = UrlTools.streamId(movie.url) ?: return null
        val info = withContext(Dispatchers.IO) { runCatching { XtreamClient(source.credentials).movieInfo(id) }.getOrNull() }
        movieInfoCache[movie.stableKey] = info
        return info
    }

    // endregion

    // region Nutzerdaten

    fun isFavorite(key: String) = key in favorites.value

    /** Schaltet Favorit um; Rückgabe: jetzt Favorit? */
    fun toggleFavorite(key: String): Boolean {
        val now = key !in favorites.value
        favorites.value = if (now) favorites.value + key else favorites.value - key
        saveUserData()
        return now
    }

    fun addRecent(channel: Channel, showKey: String? = null) {
        val list = recents.value.filterNot { it.channel.stableKey == channel.stableKey }
        recents.value = (listOf(RecentEntry(channel, System.currentTimeMillis(), showKey)) + list).take(40)
        saveUserData()
    }

    fun saveProgress(key: String, position: Long, duration: Long) {
        if (duration <= 0) return
        val map = progress.value.toMutableMap()
        // Fast zu Ende geschaut: Fortschritt löschen.
        if (position > duration * 0.95 || position < 10_000) map.remove(key)
        else map[key] = Progress(position, duration, System.currentTimeMillis())
        progress.value = map
        saveUserData()
    }

    private fun restoreUserData() {
        read(File(dir, "userdata.json"), UserDataFile.serializer())?.let {
            favorites.value = it.favorites.toSet()
            recents.value = it.recents
            progress.value = it.progress
        }
    }

    private fun saveUserData() {
        val snapshot = UserDataFile(favorites.value.toList(), recents.value, progress.value)
        scope.launch(Dispatchers.IO) { write(File(dir, "userdata.json"), UserDataFile.serializer(), snapshot) }
    }

    // endregion

    // region Speicher

    private fun updatePlaylist(id: String, change: (Playlist) -> Playlist) {
        playlists.value = playlists.value.map { if (it.id == id) change(it) else it }
        scope.launch { saveLibrary() }
    }

    private suspend fun saveLibrary() = withContext(Dispatchers.IO) {
        write(File(dir, "library.json"), LibraryFile.serializer(), LibraryFile(playlists.value, activeId.value))
    }

    private fun contentFile(id: String) = File(dir, "content-$id.json")
    private fun epgFile(id: String) = File(dir, "epg-$id.json")

    @OptIn(ExperimentalSerializationApi::class)
    private fun <T> read(file: File, serializer: KSerializer<T>): T? {
        if (!file.exists()) return null
        return try {
            file.inputStream().buffered().use { json.decodeFromStream(serializer, it) }
        } catch (e: Throwable) {
            Log.w(TAG, "Lesen fehlgeschlagen: ${file.name}", e)
            null
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Synchronized
    private fun <T> write(file: File, serializer: KSerializer<T>, value: T) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.outputStream().buffered().use { json.encodeToStream(serializer, value, it) }
            tmp.renameTo(file)
        } catch (e: Throwable) {
            Log.w(TAG, "Schreiben fehlgeschlagen: ${file.name}", e)
            tmp.delete()
        }
    }

    // endregion
}
