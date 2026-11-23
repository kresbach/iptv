package app.iptv.tv.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.iptv.tv.data.Http
import app.iptv.tv.data.UrlTools
import app.iptv.tv.model.Channel
import app.iptv.tv.model.ContentKind
import kotlinx.coroutines.flow.MutableStateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** Technische Daten der laufenden Wiedergabe (für „Wiedergabe-Info“ und die Bildratenanpassung). */
data class VideoInfo(
    val width: Int = 0,
    val height: Int = 0,
    /** Bildrate des Videos (gemessen bzw. aus dem Stream), 0 = unbekannt. */
    val fps: Float = 0f,
    val droppedFrames: Int = 0,
    val rebuffers: Int = 0,
    val bufferMs: Long = 0,
    val decoder: String = "",
    /** Tatsächlich gezeigte Bilder pro Sekunde (VLC), 0 = unbekannt. */
    val shownFps: Float = 0f,
    /** Anteil verlorener Bilder in den letzten Sekunden (0–100). */
    val lostPercent: Float = 0f,
    /** Datenrate vom Anbieter in Mbit/s (VLC). */
    val mbps: Float = 0f,
    /** Beschädigte bzw. fehlende Datenpakete im Stream (Anbieter/Internet). */
    val streamErrors: Int = 0,
)

/** Eine Ton- oder Untertitelspur zur Auswahl. */
data class TrackOption(val id: String, val label: String, val selected: Boolean)

data class PlayerStatus(
    val buffering: Boolean = true,
    val playing: Boolean = false,
    /** z. B. „Verbindung unterbrochen – verbinde neu (2)…“ */
    val message: String? = null,
    /** Endgültig fehlgeschlagen (keine weiteren Versuche). */
    val failed: Boolean = false,
    val ended: Boolean = false,
    val backend: String = "",
)

/**
 * Spielt genau einen Stream ab. Wichtig bei Konten mit nur einer gleichzeitigen Verbindung:
 * Vor jedem neuen Stream wird der alte Player vollständig freigegeben (Verbindung geschlossen).
 * Bei Fehlern wird automatisch neu verbunden, statt vorab Verbindungszähler der API zu prüfen.
 *
 * ExoPlayer (HLS, MPEG-TS, MP4, MKV) ist Standard; libVLC übernimmt exotische Formate
 * (AVI, WMV, FLV …) und Filme, die ExoPlayer nicht dekodieren kann.
 */
@OptIn(UnstableApi::class)
class PlaybackEngine(private val context: Context) {
    private enum class Backend { EXO, VLC }

    val status = MutableStateFlow(PlayerStatus())
    val audioTracks = MutableStateFlow<List<TrackOption>>(emptyList())
    val videoInfo = MutableStateFlow(VideoInfo())
    /** Zeitstempel-Abstände der ersten Bilder, daraus wird die Bildrate bestimmt. */
    private val frameDeltas = ArrayList<Long>()
    private var lastFrameUs = -1L
    val textTracks = MutableStateFlow<List<TrackOption>>(emptyList())

    /** Externe Untertiteldatei (.srt), z. B. von OpenSubtitles. */
    private var subtitleFile: java.io.File? = null
    private var localFile: java.io.File? = null
    val isLocal: Boolean get() = localFile != null
    /** Erzwingt einen Player (aus den Einstellungen bzw. dem Player-Menü). */
    var preferredBackend: app.iptv.tv.data.PlayerChoice = app.iptv.tv.data.PlayerChoice.AUTOMATIC
    val container = FrameLayout(context).apply {
        keepScreenOn = true
        // Die Videofläche darf den Fokus der Fernbedienung nicht übernehmen (Tasten steuert Compose).
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        // Größe der Videofläche geändert (z. B. nach Umschalten der Bildwiederholrate) → VLC anpassen.
        addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) post { refreshVideoLayout() }
        }
        setBackgroundColor(android.graphics.Color.BLACK)
    }

    private val main = Handler(Looper.getMainLooper())
    private var exo: ExoPlayer? = null
    private var vlc: MediaPlayer? = null
    private var vlcLayout: VLCVideoLayout? = null

    private var channel: Channel? = null
    private var currentUrl: String = ""
    private var backend = Backend.EXO
    private var attempts = 0
    private var everPlayed = false
    private var resumePosition = 0L
    private var generation = 0
    /** Live-Links ohne Endung, die sich als HLS entpuppen: als HLS erzwingen. */
    private var forceHls = false
    /** VLC wird im Hintergrund freigegeben (kann bei hängendem Netz blockieren); bis dahin nicht neu starten. */
    private var bufferingSince = 0L

    /** Wächter: hängt der Stream ohne Fehlermeldung beim Laden, wird neu verbunden. */
    private val watchdog = object : Runnable {
        override fun run() {
            val st = status.value
            if (vlc != null && videoInfo.value.fps == 0f) readVlcVideoTrack()
            if (vlc != null && st.playing && !st.buffering) checkSmoothness()
            if (channel != null && st.buffering && !st.failed && (exo != null || vlc != null)) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (bufferingSince == 0L) bufferingSince = now
                else if (now - bufferingSince > STALL_TIMEOUT_MS) {
                    bufferingSince = 0L
                    handleFailure("keine Daten vom Server")
                }
            } else {
                bufferingSince = 0L
            }
            main.postDelayed(this, 2_000)
        }
    }
    private val isLive get() = channel?.kind == ContentKind.LIVE

    /**
     * @param localFile heruntergeladene Datei (spielt offline, ohne Verbindung zum Anbieter).
     */
    fun play(channel: Channel, startPositionMs: Long = 0, localFile: java.io.File? = null, subtitle: java.io.File? = null) {
        main.removeCallbacksAndMessages(null)
        val next = chooseBackend(channel)
        // Beim Zappen zwischen VLC-Sendern die Videofläche behalten (schnelleres, ruhigeres Umschalten).
        releasePlayers(keepVlcSurface = next == Backend.VLC && vlcAvailable && !surfaceReuseBroken)
        generation++
        this.channel = channel
        attempts = 0
        forceHls = false
        bufferingSince = 0L
        videoInfo.value = VideoInfo()
        everPlayed = false
        resumePosition = startPositionMs
        subtitleFile = subtitle
        this.localFile = localFile
        currentUrl = localFile?.let { Uri.fromFile(it).toString() } ?: channel.url
        backend = next
        start()
    }

    private fun chooseBackend(channel: Channel) = when (preferredBackend) {
        app.iptv.tv.data.PlayerChoice.SYSTEM -> Backend.EXO
        app.iptv.tv.data.PlayerChoice.VLC -> Backend.VLC
        app.iptv.tv.data.PlayerChoice.AUTOMATIC -> when {
            UrlTools.extension(channel.url) in vlcExtensions -> Backend.VLC
            // Live TV: VLC läuft auf Fernsehern wie Sony (MediaTek) deutlich flüssiger und setzt
            // 1080i-Halbbilder zusammen; ExoPlayer verwirft dort bei 1080 sehr viele Bilder.
            channel.kind == ContentKind.LIVE -> Backend.VLC
            else -> Backend.EXO
        }
    }

    /** Externen Untertitel setzen (Wiedergabe startet an der aktuellen Stelle neu). */
    fun setSubtitleFile(file: java.io.File?) {
        if (channel == null) return
        val pos = position
        val url = currentUrl
        val b = backend
        releasePlayers()
        generation++
        subtitleFile = file
        resumePosition = pos
        currentUrl = url
        backend = b
        attempts = 0
        start()
    }

    val backendName: String get() = if (backend == Backend.EXO || !vlcAvailable) "ExoPlayer" else "VLC"

    // region Spuren

    private fun refreshTracks() {
        exo?.let { p ->
            val audio = ArrayList<TrackOption>()
            val text = ArrayList<TrackOption>()
            p.currentTracks.groups.forEachIndexed { gi, g ->
                for (ti in 0 until g.length) {
                    if (!g.isTrackSupported(ti)) continue
                    val f = g.getTrackFormat(ti)
                    val label = trackLabel(f.label, f.language, f.channelCount.takeIf { g.type == C.TRACK_TYPE_AUDIO }, audio.size + text.size + 1)
                    val opt = TrackOption("$gi:$ti", label, g.isTrackSelected(ti))
                    when (g.type) {
                        C.TRACK_TYPE_AUDIO -> audio += opt
                        C.TRACK_TYPE_TEXT -> text += opt
                    }
                }
            }
            audioTracks.value = audio
            textTracks.value = text
        }
        vlc?.let { p ->
            val currentA = p.audioTrack
            audioTracks.value = (p.audioTracks ?: emptyArray()).filter { it.id >= 0 }
                .map { TrackOption(it.id.toString(), it.name ?: "Spur ${it.id}", it.id == currentA) }
            val currentS = p.spuTrack
            textTracks.value = (p.spuTracks ?: emptyArray()).filter { it.id >= 0 }
                .map { TrackOption(it.id.toString(), it.name ?: "Untertitel ${it.id}", it.id == currentS) }
        }
    }

    private fun trackLabel(label: String?, language: String?, channels: Int?, n: Int): String {
        val lang = language?.takeIf { it.isNotEmpty() && it != "und" }?.let {
            java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.GERMAN).ifEmpty { it }
        }
        val parts = listOfNotNull(label?.takeIf { it.isNotBlank() }, lang?.takeIf { label == null || !label.contains(it, true) })
        val base = parts.joinToString(" · ").ifEmpty { "Spur $n" }
        return when (channels) {
            null, -1, 0 -> base
            1 -> "$base (Mono)"
            2 -> "$base (Stereo)"
            6 -> "$base (5.1)"
            8 -> "$base (7.1)"
            else -> "$base ($channels Kanäle)"
        }
    }

    fun selectAudio(id: String) {
        exo?.let { p ->
            val (gi, ti) = id.split(':').map { it.toInt() }
            val group = p.currentTracks.groups.getOrNull(gi) ?: return
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, ti))
                .build()
        }
        vlc?.let { it.audioTrack = id.toInt() }
        main.postDelayed({ refreshTracks() }, 300)
    }

    /** `null` = Untertitel aus. */
    fun selectText(id: String?) {
        exo?.let { p ->
            val b = p.trackSelectionParameters.buildUpon()
            if (id == null) {
                b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                val (gi, ti) = id.split(':').map { it.toInt() }
                val group = p.currentTracks.groups.getOrNull(gi) ?: return
                b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, ti))
            }
            p.trackSelectionParameters = b.build()
        }
        vlc?.let { it.spuTrack = id?.toInt() ?: -1 }
        main.postDelayed({ refreshTracks() }, 300)
    }

    // endregion

    /**
     * Verbindung freigeben, ohne den Sender zu vergessen (z. B. für die Diagnose, damit sie
     * bei nur einer erlaubten Verbindung nicht mit dem laufenden Stream kollidiert).
     * `retryNow()` startet wieder.
     */
    var isSuspended = false
        private set

    fun suspendConnection() {
        isSuspended = true
        generation++
        main.removeCallbacksAndMessages(null)
        if (!isLive) position.takeIf { it > 0 }?.let { resumePosition = it }
        releasePlayers()
        status.value = PlayerStatus(buffering = false, message = "Wiedergabe für die Prüfung angehalten")
    }

    /** Gibt Player und Netzwerkverbindung vollständig frei. */
    fun stop() {
        main.removeCallbacks(watchdog)
        generation++
        main.removeCallbacksAndMessages(null)
        releasePlayers()
        channel = null
    }

    fun release() {
        stop()
        val lib = libVlc ?: return
        libVlc = null
        // LibVLC erst freigeben, wenn alle Player im Hintergrund beendet sind.
        Thread({
            while (releasing.get() > 0) Thread.sleep(50)
            runCatching { lib.release() }
        }, "vlc-lib-release").start()
    }

    val position: Long
        get() = exo?.currentPosition ?: vlc?.time ?: 0L

    val duration: Long
        get() = exo?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: vlc?.length?.takeIf { it > 0 } ?: 0L

    /**
     * VLC die aktuelle Größe der Videofläche mitteilen und das Bild neu einpassen. Nötig nach dem
     * Start, nach einem Wechsel der Videoauflösung und nach dem Umschalten der Bildwiederholrate –
     * sonst zeichnet VLC das Video manchmal in Originalgröße in die linke untere Ecke.
     */
    fun refreshVideoLayout() {
        val p = vlc ?: return
        val layout = vlcLayout ?: return
        val cw = container.width
        val ch = container.height
        if (cw <= 0 || ch <= 0) return
        runCatching {
            // Seitenverhältnis selbst berechnen und die Bildfläche passend zentrieren (Originalformat,
            // schwarze Ränder). VLC zeichnet dann nur noch innerhalb dieser Fläche – nie gestreckt.
            val t = p.currentVideoTrack
            val (w, h) = if (t != null && t.width > 0 && t.height > 0) {
                VideoFit.fit(cw, ch, t.width, t.height, t.sarNum, t.sarDen, videoMode)
            } else cw to ch
            val lp = layout.layoutParams as? FrameLayout.LayoutParams
            if (lp == null || lp.width != w || lp.height != h) {
                layout.layoutParams = FrameLayout.LayoutParams(w, h, android.view.Gravity.CENTER)
            }
            p.vlcVout.setWindowSize(w, h)
            p.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
            // Strecken: VLC das Seitenverhältnis des Fernsehers vorgeben
            p.aspectRatio = if (videoMode == VideoFit.MODE_STRETCH) "$cw:$ch" else null
            p.updateVideoSurfaces()
        }
    }

    /** Auflösung und Bildrate von VLC lesen (die Bildrate steht oft erst nach einigen Sekunden fest). */
    private fun readVlcVideoTrack() {
        val t = runCatching { vlc?.currentVideoTrack }.getOrNull() ?: return
        if (t.width != videoInfo.value.width || t.height != videoInfo.value.height) {
            main.post { refreshVideoLayout() }
            // nach der Layout-Änderung der Fläche erneut einpassen
            main.postDelayed({ refreshVideoLayout() }, 300)
        }
        val fps = if (t.frameRateDen > 0) t.frameRateNum.toFloat() / t.frameRateDen else 0f
        videoInfo.value = videoInfo.value.copy(
            width = t.width, height = t.height, decoder = "VLC",
            fps = DisplayRate.snapFps(fps) ?: videoInfo.value.fps,
        )
    }

    /** VLC: verlorene Bilder laut VLC-Statistik (für die Wiedergabe-Info). */
    fun refreshVlcStats() {
        val lost = runCatching {
            vlc?.media?.let { m ->
                try { m.stats?.lostPictures } finally { m.release() }
            }
        }.getOrNull() ?: return
        videoInfo.value = videoInfo.value.copy(droppedFrames = lost)
    }

    // region Flüssigkeit überwachen

    private val frames = FrameMonitor()
    private var playingSince = 0L

    /**
     * Alle 2 s: VLC-Statistik lesen (gezeigte/verlorene Bilder, Datenrate). Verliert der Fernseher
     * dauerhaft Bilder, weil die Halbbild-Wandlung zu aufwendig ist, wird automatisch eine leichtere
     * Stufe gewählt (Yadif 2x → Bob → Fernseher selbst) und für dieses Gerät gemerkt.
     */
    private fun checkSmoothness() {
        val stats = runCatching {
            vlc?.media?.let { m -> try { m.stats } finally { m.release() } }
        }.getOrNull() ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        val verdict = frames.update(
            now, stats.displayedPictures, stats.lostPictures,
            stats.demuxCorrupted + stats.demuxDiscontinuity, now - playingSince,
        )
        videoInfo.value = videoInfo.value.copy(
            droppedFrames = stats.lostPictures,
            shownFps = frames.shownFps,
            lostPercent = frames.lostPercent,
            // VLC liefert Bytes pro Millisekunde → ×8 = Mbit/s
            mbps = stats.inputBitrate * 8f,
            streamErrors = stats.demuxCorrupted + stats.demuxDiscontinuity,
        )
        if (verdict && isLive && deinterlaceActive != null && app.iptv.tv.data.Prefs.deinterlace == "auto") {
            val level = app.iptv.tv.data.Prefs.deinterlaceAutoLevel
            if (level >= DEINTERLACE_LEVELS.lastIndex) return
            app.iptv.tv.data.Prefs.deinterlaceAutoLevel = level + 1
            Log.i(TAG, "Zu viele verlorene Bilder (${frames.lostPercent} %) → Deinterlacing ${DEINTERLACE_LEVELS[level + 1] ?: "Fernseher"}")
            restartSame("Bild wird für diesen Fernseher optimiert …")
        }
    }

    /** Sender, der gerade läuft (für die Übergabe Vorschau → Vollbild). */
    val currentKey: String? get() = channel?.stableKey

    /** Vor dem Umhängen der Videofläche (Vorschau → Vollbild): VLC von der alten Fläche lösen. */
    fun detachSurface() {
        vlc?.let { runCatching { it.detachViews() } }
    }

    /**
     * Nach dem Umhängen: VLC wieder an die Fläche binden. Kommt danach kein Bild, wird der Sender
     * sicherheitshalber neu gestartet (einmalige kurze Unterbrechung statt schwarzem Bild).
     */
    fun reattachSurface() {
        val p = vlc ?: return
        val layout = vlcLayout ?: return
        runCatching { p.detachViews() }
        voutSeen = false
        runCatching { p.attachViews(layout, null, true, false) }
        refreshVideoLayout()
        main.postDelayed({ refreshVideoLayout() }, 500)
        val before = shownPictures()
        val gen = generation
        main.postDelayed({
            if (gen != generation || vlc !== p) return@postDelayed
            val moved = (shownPictures() ?: 0) > (before ?: 0)
            if (!voutSeen && !moved && runCatching { p.videoTracksCount }.getOrDefault(0) > 0) {
                Log.w(TAG, "Kein Bild nach dem Umhängen der Videofläche → neu starten")
                restartSame()
            }
        }, 3_000)
    }

    private fun shownPictures(): Int? = runCatching {
        vlc?.media?.let { m -> try { m.stats?.displayedPictures } finally { m.release() } }
    }.getOrNull()

    /** Denselben Stream neu starten (z. B. mit anderer Halbbild-Wandlung). */
    private fun restartSame(message: String? = null) {
        if (channel == null) return
        main.removeCallbacksAndMessages(null)
        releasePlayers(keepVlcSurface = backend == Backend.VLC && !surfaceReuseBroken)
        generation++
        attempts = 0
        start()
        if (message != null) status.value = status.value.copy(message = message)
    }

    // endregion

    /** Vorrat im Puffer (ms), nur ExoPlayer. */
    val bufferedMs: Long get() = exo?.let { (it.bufferedPosition - it.currentPosition).coerceAtLeast(0) } ?: 0L

    val isPlaying: Boolean get() = exo?.isPlaying ?: vlc?.isPlaying ?: false

    fun togglePause() {
        exo?.let { it.playWhenReady = !it.playWhenReady }
        vlc?.let { if (it.isPlaying) it.pause() else it.play() }
        status.value = status.value.copy(playing = isPlaying)
    }

    fun seekBy(deltaMs: Long) {
        if (isLive) return
        val d = duration
        val target = (position + deltaMs).coerceAtLeast(0).let { if (d > 0) it.coerceAtMost(d - 1000) else it }
        exo?.seekTo(target)
        vlc?.time = target
    }

    // region Start / Wiederverbinden

    private fun start() {
        val ch = channel ?: return
        if (backend == Backend.VLC && !vlcAvailable) backend = Backend.EXO
        // Erst starten, wenn der vorherige VLC-Player die Verbindung wirklich geschlossen hat.
        if (releasing.get() > 0) {
            val gen = generation
            main.postDelayed({ if (gen == generation) start() }, 100)
            return
        }
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 2_000)
        bufferingSince = 0L
        isSuspended = false
        status.value = PlayerStatus(
            buffering = true,
            message = if (attempts == 0) null else retryMessage(),
            backend = if (backend == Backend.EXO) "ExoPlayer" else "VLC",
        )
        Log.i(TAG, "Starte ${ch.name} ($backend, Versuch ${attempts + 1})")
        when (backend) {
            Backend.EXO -> startExo(currentUrl)
            Backend.VLC -> startVlc(currentUrl)
        }
    }

    private fun retryMessage() =
        if (everPlayed) "Verbindung unterbrochen – verbinde neu ($attempts)…" else "Verbinde erneut ($attempts)…"

    /** Fehler: neu versuchen (mit wachsender Pause), ggf. Format bzw. Player wechseln. */
    private fun handleFailure(reason: String, exoError: PlaybackException? = null) {
        val ch = channel ?: return
        Log.w(TAG, "Fehler bei ${ch.name}: $reason", exoError)
        val pos = position
        if (!isLive && pos > 0) resumePosition = pos
        releasePlayers()
        attempts++

        // Film kann ExoPlayer nicht dekodieren → VLC versuchen.
        if (!isLive && backend == Backend.EXO && exoError != null && isFormatProblem(exoError)) {
            backend = Backend.VLC
            attempts = 0
        }
        // Live-Link ohne erkennbares Format: erst als HLS versuchen, dann mit VLC.
        if (isLive && backend == Backend.EXO && exoError != null && isFormatProblem(exoError)) {
            if (!forceHls && UrlTools.extension(currentUrl) != "m3u8") {
                forceHls = true
            } else {
                backend = Backend.VLC
                forceHls = false
            }
            attempts = 0
        }
        // Xtream-Live: nach mehreren Fehlschlägen mit TS das HLS-Format probieren (und umgekehrt).
        if (isLive && attempts > 0 && attempts % 3 == 0) {
            (UrlTools.liveHlsVariant(currentUrl) ?: UrlTools.liveTsVariant(currentUrl))?.let { currentUrl = it }
        }
        // Live: am Ende ExoPlayer ↔ VLC tauschen.
        if (isLive && attempts == 6) backend = if (backend == Backend.EXO) Backend.VLC else Backend.EXO

        val maxAttempts = if (isLive) 25 else 8
        if (attempts > maxAttempts) {
            status.value = PlayerStatus(
                buffering = false,
                failed = true,
                message = "Der Stream lässt sich nicht abspielen. ($reason)\nOK = erneut versuchen · OK lange drücken = Verbindung prüfen",
            )
            return
        }
        status.value = status.value.copy(buffering = true, message = retryMessage())
        val gen = generation
        val delay = (attempts * 1_500L).coerceAtMost(8_000L)
        main.postDelayed({ if (gen == generation) start() }, delay)
    }

    /** Nach endgültigem Fehler: OK drückt erneut. */
    fun retryNow() {
        val ch = channel ?: return
        play(ch, resumePosition, localFile, subtitleFile)
    }

    private fun isFormatProblem(e: PlaybackException) = e.errorCode in listOf(
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    )

    // region Bildformat und Ton-Versatz

    /** Bildformat: Original (Ränder), Zoom (ohne Ränder, abgeschnitten) oder Strecken. */
    var videoMode: String = app.iptv.tv.data.Prefs.videoMode
        set(value) {
            field = value
            app.iptv.tv.data.Prefs.videoMode = value
            (container.getChildAt(0) as? PlayerView)?.resizeMode = exoResizeMode()
            refreshVideoLayout()
        }

    private fun exoResizeMode() = when (videoMode) {
        VideoFit.MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoFit.MODE_STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    /** Ton gegenüber dem Bild verschieben (nur VLC kann das). Positiv = Ton später. */
    val supportsAudioDelay: Boolean get() = vlc != null

    fun setAudioDelay(ms: Int) {
        app.iptv.tv.data.Prefs.audioDelayMs = ms
        vlc?.let { runCatching { it.setAudioDelay(ms * 1000L) } }
    }

    // endregion

    private fun onPlaying() {
        vlc?.let { p -> app.iptv.tv.data.Prefs.audioDelayMs.takeIf { it != 0 }?.let { runCatching { p.setAudioDelay(it * 1000L) } } }
        everPlayed = true
        attempts = 0
        playingSince = android.os.SystemClock.elapsedRealtime()
        frames.reset()
        // Wiederverwendete Videofläche: kommt kein Bild, einmal mit frischer Fläche neu starten.
        if (vlc != null && surfaceReused) {
            val gen = generation
            main.postDelayed({
                val p = vlc
                if (gen == generation && p != null && !voutSeen && runCatching { p.videoTracksCount }.getOrDefault(0) > 0) {
                    Log.w(TAG, "Kein Bild auf wiederverwendeter Videofläche → neue Fläche")
                    surfaceReuseBroken = true
                    restartSame()
                }
            }, 5_000)
        }
        status.value = status.value.copy(buffering = false, playing = true, message = null, failed = false)
        refreshTracks()
    }

    // endregion

    // region ExoPlayer

    private fun startExo(url: String) {
        // Eine evtl. behaltene VLC-Fläche entfernen.
        if (vlcLayout != null) { container.removeAllViews(); vlcLayout = null }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
            )
            .setConstantBitrateSeekingEnabled(true)
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http), extractors)
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        // Live: etwas Vorrat vor dem Start, damit kleine Netzaussetzer nicht sofort zum Hänger werden.
        val big = app.iptv.tv.data.Prefs.bigBuffer
        val fast = app.iptv.tv.data.Prefs.fastZap && isLive
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (big) 30_000 else 15_000,
                if (big) 90_000 else 50_000,
                when { !isLive -> 1_500; big -> 6_000; fast -> 1_000; else -> 2_500 },
                when { !isLive -> 3_000; big -> 10_000; fast -> 3_000; else -> 5_000 },
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setTunnelingEnabled(app.iptv.tv.data.Prefs.tunneling)
                // Bevorzugte Sprachen (Einstellungen → Wiedergabe)
                .setPreferredAudioLanguage(app.iptv.tv.data.Prefs.audioLanguage.ifEmpty { null })
                .setPreferredTextLanguage(app.iptv.tv.data.Prefs.subtitleLanguage.ifEmpty { null })
                .build()
        }
        val player = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(sources)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .build()
        exo = player
        frameDeltas.clear()
        lastFrameUs = -1L
        // Bildrate aus den Zeitstempeln der ersten Bilder bestimmen (viele Live-Streams nennen sie nicht).
        player.setVideoFrameMetadataListener { presentationTimeUs, _, format, mediaFormat ->
            if (videoInfo.value.fps == 0f) {
                // Manche Decoder nennen die Bildrate im Ausgabeformat.
                runCatching { mediaFormat?.getFloat(android.media.MediaFormat.KEY_FRAME_RATE) }.getOrNull()
                    ?.let { DisplayRate.snapFps(it) }?.let { f -> videoInfo.value = videoInfo.value.copy(fps = f) }
            }
            if (format.frameRate > 0 && videoInfo.value.fps == 0f) {
                DisplayRate.snapFps(format.frameRate)?.let { f -> videoInfo.value = videoInfo.value.copy(fps = f) }
            }
            if (videoInfo.value.fps > 0f) return@setVideoFrameMetadataListener
            if (lastFrameUs >= 0 && presentationTimeUs > lastFrameUs) frameDeltas += presentationTimeUs - lastFrameUs
            lastFrameUs = presentationTimeUs
            if (frameDeltas.size >= 48) {
                // Verworfene Bilder erzeugen größere Lücken – der kleinste häufige Abstand ist der Bildabstand.
                val interval = DisplayRate.frameIntervalUs(frameDeltas)
                frameDeltas.clear()
                DisplayRate.snapFps(1_000_000f / interval)?.let { f -> videoInfo.value = videoInfo.value.copy(fps = f) }
            }
        }
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                videoInfo.value = videoInfo.value.copy(droppedFrames = videoInfo.value.droppedFrames + droppedFrames)
            }

            override fun onVideoDecoderInitialized(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                videoInfo.value = videoInfo.value.copy(decoder = decoderName)
            }

            override fun onVideoSizeChanged(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, videoSize: androidx.media3.common.VideoSize) {
                videoInfo.value = videoInfo.value.copy(width = videoSize.width, height = videoSize.height)
            }
        })

        val view = PlayerView(context).apply {
            useController = false
            resizeMode = exoResizeMode()
            setShutterBackgroundColor(android.graphics.Color.BLACK)
            this.player = player
        }
        container.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val gen = generation
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (gen != generation) return
                when (state) {
                    Player.STATE_BUFFERING -> {
                        // Nachladen mitten in der Wiedergabe = „Hänger“ (für die Wiedergabe-Info zählen)
                        if (everPlayed) videoInfo.value = videoInfo.value.copy(rebuffers = videoInfo.value.rebuffers + 1)
                        status.value = status.value.copy(buffering = true)
                    }
                    Player.STATE_READY -> onPlaying()
                    Player.STATE_ENDED ->
                        // Live endet nie von selbst – Ende heißt: Verbindung abgebrochen.
                        if (isLive) handleFailure("Stream beendet")
                        else status.value = status.value.copy(ended = true, playing = false, buffering = false)
                    else -> Unit
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (gen == generation) status.value = status.value.copy(playing = isPlaying)
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (gen == generation) refreshTracks()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (gen != generation) return
                val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                handleFailure(if (code != null) "HTTP $code" else error.errorCodeName, error)
            }
        })

        val ext = UrlTools.extension(url)
        val mime = when {
            forceHls || ext == "m3u8" || url.contains("m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
            ext == "ts" -> MimeTypes.VIDEO_MP2T
            else -> null
        }
        val item = MediaItem.Builder().setUri(url).setMimeType(mime)
        subtitleFile?.let { f ->
            item.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f))
                        .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                        .setLanguage("und")
                        .setLabel("Heruntergeladen")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
                        .build(),
                ),
            )
        }
        player.setMediaItem(item.build())
        if (!isLive && resumePosition > 0) player.seekTo(resumePosition)
        player.playWhenReady = true
        player.prepare()
    }

    // endregion

    // region VLC

    private var libVlc: LibVLC? = null

    /**
     * Sind VLCs Programmteile für diesen Prozessor vorhanden? LibVLC beendet sonst beim Laden den
     * ganzen Prozess. Fehlen sie, wird ExoPlayer verwendet.
     */
    private val vlcAvailable: Boolean by lazy {
        runCatching {
            val dir = java.io.File(context.applicationInfo.nativeLibraryDir ?: return@runCatching false)
            java.io.File(dir, "libvlc.so").exists() || java.io.File(dir, "libvlcjni.so").exists()
        }.getOrDefault(false)
    }
    private var libVlcDeinterlace: String? = null

    /** Gewähltes Deinterlacing ("bob"/"yadif2x") oder null. */
    private val deinterlaceMode: String?
        get() = when (val d = app.iptv.tv.data.Prefs.deinterlace) {
            "auto" -> DEINTERLACE_LEVELS[app.iptv.tv.data.Prefs.deinterlaceAutoLevel.coerceIn(0, DEINTERLACE_LEVELS.lastIndex)]
            "bob", "yadif2x" -> d
            else -> null
        }

    /** Für die Wiedergabe-Info. */
    val deinterlaceActive: String?
        get() = if (backend == Backend.VLC && isLive) deinterlaceMode else null

    private fun startVlc(url: String) {
        // Deinterlacing ist eine Einstellung der LibVLC-Instanz → bei Änderung neu anlegen.
        val wanted = if (isLive) deinterlaceMode else null
        if (libVlc != null && libVlcDeinterlace != wanted) {
            val old = libVlc
            libVlc = null
            Thread({ while (releasing.get() > 0) Thread.sleep(50); runCatching { old?.release() } }, "vlc-lib-release").start()
        }
        val lib = libVlc ?: LibVLC(
            context,
            arrayListOf("--http-reconnect").apply {
                if (wanted != null) {
                    // -1 = automatisch: nur Bilder entflechten, die wirklich interlaced sind (1080i), 720p/1080p bleiben unberührt
                    add("--deinterlace=-1")
                    add("--deinterlace-mode=$wanted")
                }
            },
        ).also { libVlc = it; libVlcDeinterlace = wanted }
        surfaceReused = vlcLayout != null && vlcLayout?.parent === container
        voutSeen = false
        val layout = vlcLayout?.takeIf { surfaceReused } ?: VLCVideoLayout(context).also {
            container.removeAllViews()
            container.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, android.view.Gravity.CENTER))
        }
        vlcLayout = layout
        val player = MediaPlayer(lib)
        vlc = player
        player.attachViews(layout, null, true, false)
        player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
        refreshVideoLayout()

        val gen = generation
        player.setEventListener { event ->
            if (gen != generation) return@setEventListener
            when (event.type) {
                MediaPlayer.Event.Playing -> onPlaying()
                MediaPlayer.Event.Vout -> {
                    if (event.voutCount > 0) voutSeen = true
                    readVlcVideoTrack()
                    // Bildausgabe steht: Bildschirmgröße mitteilen (sonst landet z. B. 720p unskaliert in der Ecke).
                    refreshVideoLayout()
                    main.postDelayed({ if (gen == generation) refreshVideoLayout() }, 500)
                    main.postDelayed({ if (gen == generation) refreshVideoLayout() }, 2_000)
                }
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESSelected -> refreshTracks()
                MediaPlayer.Event.Buffering ->
                    status.value = status.value.copy(buffering = event.buffering < 100f)
                MediaPlayer.Event.Paused -> status.value = status.value.copy(playing = false)
                MediaPlayer.Event.EncounteredError -> main.post { handleFailure("VLC-Fehler") }
                MediaPlayer.Event.EndReached -> main.post {
                    if (isLive) handleFailure("Stream beendet")
                    else status.value = status.value.copy(ended = true, playing = false, buffering = false)
                }
            }
        }

        val media = Media(lib, Uri.parse(url)).apply {
            addOption(":http-user-agent=${Http.USER_AGENT}")
            Languages.vlcCodes(app.iptv.tv.data.Prefs.audioLanguage)?.let { addOption(":audio-language=$it") }
            Languages.vlcCodes(app.iptv.tv.data.Prefs.subtitleLanguage)?.let { addOption(":sub-language=$it") }
            addOption(":network-caching=" + when {
                app.iptv.tv.data.Prefs.bigBuffer -> 6000
                app.iptv.tv.data.Prefs.fastZap && isLive -> 1500
                else -> 3000
            })
            if (wanted != null) {
                // Hardware dekodiert, Bild aber über VLC ausgeben – nur so greift der Deinterlace-Filter.
                setHWDecoderEnabled(true, true)
                addOption(":no-mediacodec-dr")
                addOption(":no-omxil-dr")
                addOption(":deinterlace=-1")
                addOption(":deinterlace-mode=$wanted")
            } else {
                setHWDecoderEnabled(true, false)
            }
            if (!isLive && resumePosition > 0) addOption(":start-time=${resumePosition / 1000}")
        }
        player.media = media
        media.release()
        subtitleFile?.let { player.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle, Uri.fromFile(it), true) }
        player.play()
    }

    // endregion

    private var surfaceReused = false
    private var voutSeen = false

    private fun releasePlayers(keepVlcSurface: Boolean = false) {
        exo?.let {
            it.stop()
            it.release()
        }
        exo = null
        vlc?.let { player ->
            player.setEventListener(null)
            player.detachViews()
            // stop()/release() können bei hängendem Netz blockieren → im Hintergrund.
            releasing.incrementAndGet()
            Thread({
                try {
                    player.stop()
                    player.release()
                } catch (e: Throwable) {
                    Log.w(TAG, "VLC freigeben", e)
                } finally {
                    releasing.decrementAndGet()
                }
            }, "vlc-release").start()
        }
        vlc = null
        val keep = vlcLayout?.takeIf { keepVlcSurface }
        if (keep != null) {
            // nur andere Ansichten (z. B. ExoPlayer) entfernen, die VLC-Fläche bleibt stehen
            for (i in container.childCount - 1 downTo 0) if (container.getChildAt(i) !== keep) container.removeViewAt(i)
        } else {
            vlcLayout = null
            container.removeAllViews()
        }
        audioTracks.value = emptyList()
        textTracks.value = emptyList()
    }

    companion object {
        /** Gemeinsam für alle Player: ein neuer Player wartet, bis der alte seine Verbindung geschlossen hat. */
        private val releasing = java.util.concurrent.atomic.AtomicInteger(0)
        /** Stufen der automatischen Halbbild-Wandlung; null = der Fernseher macht es selbst. */
        val DEINTERLACE_LEVELS = listOf("yadif2x", "bob", null)
        /** Liefert eine wiederverwendete Videofläche kein Bild, für den Rest der Sitzung immer neue Flächen. */
        @Volatile private var surfaceReuseBroken = false
        private const val TAG = "PlaybackEngine"
        private const val STALL_TIMEOUT_MS = 30_000L
        private val vlcExtensions = setOf("avi", "wmv", "flv", "mpg", "mpeg", "divx", "rmvb", "3gp", "vob", "ogm", "asf", "m2ts", "mts")
    }
}

/** Sprachen für die automatische Wahl von Tonspur und Untertitel. */
object Languages {
    /** Auswahl in den Einstellungen ("" = Standard der Sendung). */
    val choices = listOf("de", "en", "fr", "it", "es", "tr", "pl", "ru", "ar", "")

    fun name(code: String): String =
        if (code.isEmpty()) "Standard" else java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.GERMAN)

    /** VLC erkennt Sprachen an 2- und 3-stelligen Kürzeln. */
    fun vlcCodes(code: String): String? {
        if (code.isEmpty()) return null
        val locale = java.util.Locale.forLanguageTag(code)
        val three = runCatching { locale.isO3Language }.getOrNull()
        val alt = mapOf("de" to "ger", "fr" to "fre")[code]
        return listOfNotNull(code, three, alt).distinct().joinToString(",")
    }
}
