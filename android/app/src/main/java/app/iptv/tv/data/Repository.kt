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
import kotlinx.coroutines.flow.collectLatest
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
private data class LibraryFile(
    val playlists: List<Playlist> = emptyList(),
    val activeId: String? = null,
    /** Gelöschte Playlists (ID → Zeitpunkt), damit der Sync sie nicht wiederbringt. */
    val deleted: Map<String, Long> = emptyMap(),
)

/** Wiedergabefortschritt (ms). `channel`/`showKey` für die Reihe „Weiterschauen“. */
@Serializable
data class Progress(
    val position: Long,
    val duration: Long,
    val updated: Long,
    val channel: Channel? = null,
    val showKey: String? = null,
)

/** Eintrag in „Zuletzt gesehen“. Bei Episoden zusätzlich der Schlüssel der Serie. */
@Serializable
data class RecentEntry(val channel: Channel, val time: Long, val showKey: String? = null)

@Serializable
private data class UserDataFile(
    val favorites: List<String> = emptyList(),
    val recents: List<RecentEntry> = emptyList(),
    val progress: Map<String, Progress> = emptyMap(),
    /** Letzte Änderung je Favoriten-Schlüssel (auch entfernte) – für den Abgleich zwischen Geräten. */
    val favoriteChanges: Map<String, Long> = emptyMap(),
    /** Bekannte Episodenzahl je gemerkter Serie (für „Neue Folgen“). */
    val episodeCounts: Map<String, Int> = emptyMap(),
    val newEpisodes: List<String> = emptyList(),
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
    /** Nutzerdaten der Reihe nach schreiben, damit kein älterer Stand einen neueren überschreibt. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val serialIo = Dispatchers.IO.limitedParallelism(1)
    private const val PROGRESS_LIMIT = 300
    private lateinit var dir: File
    private lateinit var cacheDir: File
    private var started = false

    val playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val activeId = MutableStateFlow<String?>(null)
    val content = MutableStateFlow(LibraryContent.EMPTY)
    /** Wie `content`, aber ohne ausgeblendete bzw. gesperrte Kategorien – das sieht der Nutzer. */
    val visible = MutableStateFlow(LibraryContent.EMPTY)
    val epg = MutableStateFlow<EpgGuide?>(null)
    /** null = noch nicht geladen (Startbildschirm zeigt Ladeanzeige). */
    val restored = MutableStateFlow(false)
    val isLoading = MutableStateFlow(false)
    /** Gespeicherte Playlist wird beim Start noch eingelesen. */
    val contentLoading = MutableStateFlow(false)
    val statusMessage = MutableStateFlow<String?>(null)

    val favorites = MutableStateFlow(OrderedKeys(emptyList()))
    val recents = MutableStateFlow<List<RecentEntry>>(emptyList())
    val progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val deletedPlaylists = MutableStateFlow<Map<String, Long>>(emptyMap())
    var favoriteChanges: Map<String, Long> = emptyMap()
        private set

    /** Wird nach Änderungen an Nutzerdaten/Playlists aufgerufen (Google-Drive-Sync). */
    var onChange: (() -> Unit)? = null

    @Volatile var playbackActive = false
    private var refreshJob: Job? = null
    private val seriesCache = java.util.Collections.synchronizedMap(HashMap<String, Pair<MediaInfo?, List<Episode>>>())
    private val movieInfoCache = java.util.Collections.synchronizedMap(HashMap<String, MediaInfo?>())

    val activePlaylist: Playlist? get() = playlists.value.firstOrNull { it.id == activeId.value }

    /** Speicherorte festlegen (ohne zu laden) – auch für Oberflächen-Tests. */
    fun attachStorage(context: Context) {
        dir = context.filesDir
        cacheDir = context.cacheDir
        Prefs.init(context)
    }

    fun start(context: Context) {
        if (started) return
        started = true
        attachStorage(context)
        // Sichtbare Inhalte neu berechnen, wenn sich Playlist oder Kategorie-Einstellungen ändern.
        scope.launch {
            kotlinx.coroutines.flow.combine(content, CategoryPrefs.version) { c, _ -> c }
                .collectLatest { c -> visible.value = filtered(c) }
        }
        scope.launch {
            Profiles.load(dir)
            restoreUserData()
            read(File(dir, "library.json"), LibraryFile.serializer())?.let {
                playlists.value = it.playlists
                activeId.value = it.activeId ?: it.playlists.firstOrNull()?.id
                deletedPlaylists.value = it.deleted
            }
            val active = activePlaylist
            // Startseite sofort zeigen (Kacheln, Weiterschauen); Listen füllen sich, sobald geladen.
            if (active != null) contentLoading.value = true
            restored.value = true
            if (active != null) {
                readContent(active.id)?.let { content.value = LibraryContent(it) }
                contentLoading.value = false
                // TV-Guide erst danach – er wird für die Startseite nicht gebraucht.
                epg.value = readEpg(active.id)
                if (content.value.isEmpty) refresh() else refreshInBackground(8_000)
                checkNewEpisodesLater()
            }
        }
    }

    // region Playlists

    private fun filtered(c: LibraryContent): LibraryContent {
        val channels = CategoryPrefs.filterChannels(c.data.channels)
        val shows = CategoryPrefs.filterShows(c.data.shows)
        return if (channels.size == c.data.channels.size && shows.size == c.data.shows.size) c
        else LibraryContent(PlaylistData(channels, shows))
    }

    /**
     * Legt eine Playlist an und lädt sie. Bei Fehlern wird nichts gespeichert (Rückgabe: Fehlermeldung).
     * @param importFile bei M3U-Dateien: die hochgeladene Datei (wird übernommen).
     */
    suspend fun addPlaylist(
        name: String,
        source: PlaylistSource,
        customEpg: String? = null,
        importFile: File? = null,
    ): String? = withContext(Dispatchers.Default) {
        isLoading.value = true
        var playlist = Playlist(name = name, source = source, customEpgUrl = customEpg?.takeIf { it.isNotBlank() })
        try {
            if (importFile != null) importFile.copyTo(sourceFile(playlist.id), overwrite = true)
            val (data, epgUrl, usedSource) = fetch(playlist)
            playlist = playlist.copy(source = usedSource, detectedEpgUrl = epgUrl, lastUpdated = System.currentTimeMillis())
            writeContent(playlist.id, data)
            playlists.value = playlists.value + playlist
            activate(playlist.id, data) // lädt auch den TV-Guide
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            sourceFile(playlist.id).delete()
            throw e
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "addPlaylist", e)
            sourceFile(playlist.id).delete()
            TOO_BIG
        } catch (e: Exception) {
            Log.w(TAG, "addPlaylist", e)
            sourceFile(playlist.id).delete()
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
        val data = preloaded ?: withContext(Dispatchers.IO) { readContent(id) }
        content.value = data?.let { LibraryContent(it) } ?: LibraryContent.EMPTY
        epg.value = withContext(Dispatchers.IO) { readEpg(id) }
        saveLibrary()
        if (data == null) refresh() else if (epg.value == null) scope.launch { refreshEpg(force = true) }
        Log.i(TAG, "aktiv: ${playlist.name}")
    }

    /** Name, Quelle bzw. TV-Guide-Adresse ändern; bei geänderter Quelle neu laden. */
    suspend fun editPlaylist(id: String, name: String, source: PlaylistSource, customEpg: String?, importFile: File? = null) {
        val old = playlists.value.firstOrNull { it.id == id } ?: return
        statusMessage.value = null
        if (importFile != null) withContext(Dispatchers.IO) { importFile.copyTo(sourceFile(id), overwrite = true) }
        val epgChanged = (customEpg?.takeIf { it.isNotBlank() }) != old.customEpgUrl
        updatePlaylist(id) { it.copy(name = name, source = source, customEpgUrl = customEpg?.takeIf { e -> e.isNotBlank() }) }
        saveLibrary()
        onChange?.invoke()
        if (id == activeId.value && (old.source != source || importFile != null)) refresh()
        else if (id == activeId.value && epgChanged) withContext(Dispatchers.Default) { refreshEpg(force = true) }
    }

    suspend fun delete(id: String) {
        playlists.value = playlists.value.filterNot { it.id == id }
        deletedPlaylists.value = deletedPlaylists.value + (id to System.currentTimeMillis())
        contentFile(id).delete()
        epgFile(id).delete()
        legacyContentFile(id).delete()
        legacyEpgFile(id).delete()
        sourceFile(id).delete()
        if (activeId.value == id) {
            val next = playlists.value.firstOrNull()
            activeId.value = null
            content.value = LibraryContent.EMPTY
            epg.value = null
            if (next != null) activate(next.id)
        }
        saveLibrary()
        onChange?.invoke()
    }

    /** Vom Google-Drive-Sync: Playlists anderer Geräte übernehmen, gelöschte entfernen. */
    suspend fun applySyncedPlaylists(remote: List<Playlist>, deleted: Map<String, Long>) {
        val deletedIds = deleted.keys.map { it.uppercase() }.toSet()
        val local = playlists.value.filterNot { it.id.uppercase() in deletedIds }
        val localIds = local.map { it.id.uppercase() }.toSet()
        val added = remote.filter { it.id.uppercase() !in localIds && it.id.uppercase() !in deletedIds && it.source !is PlaylistSource.File }
        deletedPlaylists.value = deletedPlaylists.value + deleted
        if (added.isEmpty() && local.size == playlists.value.size) return
        playlists.value = local + added
        if (activeId.value != null && playlists.value.none { it.id == activeId.value }) {
            playlists.value.firstOrNull()?.let { activate(it.id) } ?: run {
                activeId.value = null; content.value = LibraryContent.EMPTY
            }
        } else if (activeId.value == null) {
            playlists.value.firstOrNull()?.let { activate(it.id) }
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
            writeContent(playlist.id, data)
            if (activeId.value == playlist.id) {
                seriesCache.clear()
                content.value = LibraryContent(data)
            }
            updatePlaylist(playlist.id) {
                it.copy(source = usedSource, detectedEpgUrl = epgUrl, lastUpdated = System.currentTimeMillis())
            }
            refreshEpg(force = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
            is PlaylistSource.File -> {
                val file = sourceFile(playlist.id)
                if (!file.exists()) throw UserFacingException("Die M3U-Datei ist nicht mehr vorhanden. Bitte erneut hochladen.")
                val result = file.bufferedReader().use { M3UParser.parse(it) }
                Fetched(PlaylistData(result.channels), result.epgUrl, source)
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
                // Mit Sendungsarchiv: vergangene Tage behalten (für „Verpasste Sendungen“).
                val archiveDays = channels.maxOf { it.archiveDays ?: 0 }.coerceAtMost(7)
                val past = if (archiveDays > 0) archiveDays * 24 * 3600_000L else 2 * 3600_000L
                val guide = tmp.inputStream().use { EpgParser.parse(it, channels, Xml.newPullParser(), pastMs = past) }
                if (guide.programmes.isNotEmpty() || current == null) {
                    writeEpg(playlist.id, guide)
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
        favorites.value = OrderedKeys(if (now) favorites.value + key else favorites.value - key)
        favoriteChanges = favoriteChanges + (key to System.currentTimeMillis())
        saveUserData()
        return now
    }

    /** Favorit um `delta` Plätze verschieben (eigene Reihenfolge, z. B. Lieblingssender ganz oben). */
    fun moveFavorite(key: String, delta: Int) {
        val list = favorites.value.toMutableList()
        val i = list.indexOf(key)
        val target = i + delta
        if (i < 0 || target !in list.indices) return
        list.add(target, list.removeAt(i))
        favorites.value = OrderedKeys(list)
        saveUserData()
    }

    /** Favorit ganz nach oben. */
    fun moveFavoriteToTop(key: String) {
        if (key !in favorites.value) return
        favorites.value = OrderedKeys(listOf(key) + favorites.value)
        saveUserData()
    }

    /** „Weiterschauen“: angefangene Filme/Episoden (mind. 30 s, noch nicht fertig), neueste zuerst. */
    fun continueWatching(map: Map<String, Progress>): List<Progress> =
        map.values.filter { it.channel != null && it.position >= 30_000 && it.position < it.duration * 0.95 }
            .sortedByDescending { it.updated }

    /** Höchstens die 300 zuletzt geänderten Einträge behalten (wie iOS). */
    private fun trimProgress(map: Map<String, Progress>): Map<String, Progress> =
        if (map.size <= PROGRESS_LIMIT) map
        else map.entries.sortedByDescending { it.value.updated }.take(PROGRESS_LIMIT).associate { it.key to it.value }

    fun clearHistory() {
        recents.value = emptyList()
        progress.value = emptyMap()
        saveUserData()
    }

    /** Vom Sync: zusammengeführte Nutzerdaten übernehmen (ohne erneuten Sync auszulösen). */
    fun applySyncedUserData(favs: Set<String>, changes: Map<String, Long>, history: List<RecentEntry>, prog: Map<String, Progress>) {
        // Eigene Reihenfolge behalten, neue Favorite hinten anhängen.
        favorites.value = OrderedKeys(favorites.value.filter { it in favs } + favs)
        favoriteChanges = changes
        recents.value = history.take(40)
        progress.value = trimProgress(prog)
        saveUserData(notify = false)
    }

    fun addRecent(channel: Channel, showKey: String? = showKeyFor(channel)) {
        val list = recents.value.filterNot { it.channel.stableKey == channel.stableKey }
        recents.value = (listOf(RecentEntry(channel, System.currentTimeMillis(), showKey)) + list).take(40)
        saveUserData()
    }

    /** Schlüssel der Serie zu einer Episode („series|Kategorie|Titel“). */
    fun showKeyFor(channel: Channel): String? {
        if (channel.kind != app.iptv.tv.model.ContentKind.SERIES) return null
        val title = EpisodeParser.parse(channel.name)?.showTitle ?: return null
        return "series|${channel.category}|$title"
    }

    fun saveProgress(channel: Channel, position: Long, duration: Long, showKey: String? = showKeyFor(channel)) {
        val key = channel.stableKey
        if (duration <= 0) return
        val map = progress.value.toMutableMap()
        // Fast zu Ende geschaut: Fortschritt löschen.
        if (position > duration * 0.95 || position < 10_000) map.remove(key)
        else map[key] = Progress(position, duration, System.currentTimeMillis(), channel, showKey)
        progress.value = trimProgress(map)
        saveUserData()
    }

    private fun restoreUserData() {
        val data = read(Profiles.userDataFile(dir), UserDataFile.serializer()) ?: UserDataFile()
        favorites.value = OrderedKeys(data.favorites)
        recents.value = data.recents
        progress.value = data.progress
        favoriteChanges = data.favoriteChanges
        episodeCounts = data.episodeCounts
        newEpisodes.value = data.newEpisodes.toSet()
    }

    private fun saveUserData(notify: Boolean = true) {
        val snapshot = UserDataFile(favorites.value.toList(), recents.value, progress.value, favoriteChanges, episodeCounts, newEpisodes.value.toList())
        val target = Profiles.userDataFile(dir)
        scope.launch(serialIo) { write(target, UserDataFile.serializer(), snapshot) }
        // Abgleich mit dem iPhone nur für das Hauptprofil
        if (notify && Profiles.isMain) onChange?.invoke()
    }

    /** Profil wechseln: Nutzerdaten speichern und die des neuen Profils laden. */
    fun switchProfile(id: String) {
        if (id == Profiles.activeId.value) { Profiles.chosen = true; return }
        saveUserData(notify = false)
        Profiles.setActive(id)
        restoreUserData()
        CategoryPrefs.lock()
        CategoryPrefs.refresh()
    }

    // region Neue Folgen

    @Volatile internal var episodeCounts: Map<String, Int> = emptyMap()
    /** Gemerkte Serien mit neuen Episoden seit dem letzten Ansehen. */
    val newEpisodes = MutableStateFlow<Set<String>>(emptySet())

    /** Gemerkte Serien auf neue Folgen prüfen (höchstens alle 6 Stunden, nicht während der Wiedergabe). */
    fun checkNewEpisodesLater(delayMs: Long = 90_000) {
        scope.launch {
            delay(delayMs)
            if (System.currentTimeMillis() - Prefs.lastEpisodeCheck < 6 * 3600_000L) return@launch
            checkNewEpisodes()
        }
    }

    suspend fun checkNewEpisodes() {
        val shows = favorites.value.mapNotNull { content.value.show(it) }
        if (shows.isEmpty()) return
        var counts = episodeCounts
        val fresh = newEpisodes.value.toMutableSet()
        for (show in shows) {
            while (playbackActive) delay(10_000)
            val count = runCatching {
                if (show.xtreamSeriesId != null) seriesDetails(show).second.size else show.episodes.size
            }.getOrDefault(0)
            if (count <= 0) continue
            val known = counts[show.favoriteKey]
            if (known == null) counts = counts + (show.favoriteKey to count)
            else if (count > known) fresh += show.favoriteKey
            delay(300)
        }
        episodeCounts = counts
        newEpisodes.value = fresh
        Prefs.lastEpisodeCheck = System.currentTimeMillis()
        saveUserData(notify = false)
    }

    /** Serie geöffnet: aktuelle Episodenzahl merken, Hinweis entfernen. */
    fun markEpisodesSeen(show: SeriesShow, count: Int) {
        if (count <= 0 || show.favoriteKey !in favorites.value) return
        if (episodeCounts[show.favoriteKey] == count && show.favoriteKey !in newEpisodes.value) return
        episodeCounts = episodeCounts + (show.favoriteKey to count)
        newEpisodes.value = newEpisodes.value - show.favoriteKey
        saveUserData(notify = false)
    }

    // endregion

    // endregion

    // region Speicher

    private fun updatePlaylist(id: String, change: (Playlist) -> Playlist) {
        playlists.value = playlists.value.map { if (it.id == id) change(it) else it }
        scope.launch { saveLibrary() }
    }

    private suspend fun saveLibrary() = withContext(Dispatchers.IO) {
        write(File(dir, "library.json"), LibraryFile.serializer(), LibraryFile(playlists.value, activeId.value, deletedPlaylists.value))
    }

    private fun contentFile(id: String) = File(dir, "content-$id.bin")
    private fun epgFile(id: String) = File(dir, "epg-$id.bin")
    // Ältere Versionen speicherten JSON (wird beim ersten Lesen umgewandelt)
    private fun legacyContentFile(id: String) = File(dir, "content-$id.json")
    private fun legacyEpgFile(id: String) = File(dir, "epg-$id.json")

    /** Playlist lesen: schnelles Binärformat, sonst alte JSON-Datei (dann einmalig umwandeln). */
    private fun readContent(id: String): PlaylistData? {
        readBinary(contentFile(id)) { BinaryStore.readData(it) }?.let { return it }
        val legacy = read(legacyContentFile(id), PlaylistData.serializer()) ?: return null
        writeContent(id, legacy)
        legacyContentFile(id).delete()
        return legacy
    }

    private fun readEpg(id: String): EpgGuide? {
        readBinary(epgFile(id)) { BinaryStore.readEpg(it) }?.let { return it }
        val legacy = read(legacyEpgFile(id), EpgGuide.serializer()) ?: return null
        writeEpg(id, legacy)
        legacyEpgFile(id).delete()
        return legacy
    }

    private fun writeContent(id: String, data: PlaylistData) = writeBinary(contentFile(id)) { BinaryStore.writeData(data, it) }
    private fun writeEpg(id: String, guide: EpgGuide) = writeBinary(epgFile(id)) { BinaryStore.writeEpg(guide, it) }

    private fun <T> readBinary(file: File, reader: (java.io.InputStream) -> T): T? {
        if (!file.exists()) return null
        return try {
            file.inputStream().use(reader)
        } catch (e: Throwable) {
            Log.w(TAG, "Lesen fehlgeschlagen: ${file.name}", e)
            null
        }
    }

    @Synchronized
    private fun writeBinary(file: File, writer: (java.io.OutputStream) -> Unit) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.outputStream().use(writer)
            tmp.renameTo(file)
        } catch (e: Throwable) {
            Log.w(TAG, "Schreiben fehlgeschlagen: ${file.name}", e)
            tmp.delete()
        }
    }
    private fun sourceFile(id: String) = File(dir, "source-$id.m3u")

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

/**
 * Favoriten-Menge, bei der auch die Reihenfolge zählt. Sonst würde ein reines Umsortieren als
 * „keine Änderung“ gelten (StateFlow und Compose vergleichen mit equals) und nicht angezeigt.
 */
class OrderedKeys(keys: Collection<String>) : Set<String> by LinkedHashSet(keys) {
    private val order = LinkedHashSet(keys).toList()
    override fun equals(other: Any?) = other is OrderedKeys && other.order == order
    override fun hashCode() = order.hashCode()
    override fun toString() = order.toString()
}
