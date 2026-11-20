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
    val textTracks = MutableStateFlow<List<TrackOption>>(emptyList())

    /** Externe Untertiteldatei (.srt), z. B. von OpenSubtitles. */
    private var subtitleFile: java.io.File? = null
    private var localFile: java.io.File? = null
    val isLocal: Boolean get() = localFile != null
    /** Erzwingt einen Player (aus den Einstellungen bzw. dem Player-Menü). */
    var preferredBackend: app.iptv.tv.data.PlayerChoice = app.iptv.tv.data.PlayerChoice.AUTOMATIC
    val container = FrameLayout(context).apply {
        keepScreenOn = true
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

    private val isLive get() = channel?.kind == ContentKind.LIVE

    /**
     * @param localFile heruntergeladene Datei (spielt offline, ohne Verbindung zum Anbieter).
     */
    fun play(channel: Channel, startPositionMs: Long = 0, localFile: java.io.File? = null, subtitle: java.io.File? = null) {
        releasePlayers()
        generation++
        this.channel = channel
        attempts = 0
        everPlayed = false
        resumePosition = startPositionMs
        subtitleFile = subtitle
        this.localFile = localFile
        currentUrl = localFile?.let { Uri.fromFile(it).toString() } ?: channel.url
        backend = when (preferredBackend) {
            app.iptv.tv.data.PlayerChoice.SYSTEM -> Backend.EXO
            app.iptv.tv.data.PlayerChoice.VLC -> Backend.VLC
            app.iptv.tv.data.PlayerChoice.AUTOMATIC ->
                if (UrlTools.extension(channel.url) in vlcExtensions) Backend.VLC else Backend.EXO
        }
        start()
    }

    /** Externen Untertitel setzen (Wiedergabe startet an der aktuellen Stelle neu). */
    fun setSubtitleFile(file: java.io.File?) {
        val ch = channel ?: return
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

    val backendName: String get() = if (backend == Backend.EXO) "ExoPlayer" else "VLC"

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

    /** Gibt Player und Netzwerkverbindung vollständig frei. */
    fun stop() {
        generation++
        main.removeCallbacksAndMessages(null)
        releasePlayers()
        channel = null
    }

    fun release() {
        stop()
        libVlc?.release()
        libVlc = null
    }

    val position: Long
        get() = exo?.currentPosition ?: vlc?.time ?: 0L

    val duration: Long
        get() = exo?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: vlc?.length?.takeIf { it > 0 } ?: 0L

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

    private fun onPlaying() {
        everPlayed = true
        attempts = 0
        status.value = status.value.copy(buffering = false, playing = true, message = null, failed = false)
        refreshTracks()
    }

    // endregion

    // region ExoPlayer

    private fun startExo(url: String) {
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
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_500, 3_000)
            .build()
        val player = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(sources)
            .setLoadControl(loadControl)
            .build()
        exo = player

        val view = PlayerView(context).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(android.graphics.Color.BLACK)
            this.player = player
        }
        container.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val gen = generation
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (gen != generation) return
                when (state) {
                    Player.STATE_BUFFERING -> status.value = status.value.copy(buffering = true)
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
            ext == "m3u8" || url.contains("m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
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

    private fun startVlc(url: String) {
        val lib = libVlc ?: LibVLC(context, arrayListOf("--network-caching=2000", "--http-reconnect")).also { libVlc = it }
        val layout = VLCVideoLayout(context)
        container.addView(layout, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        vlcLayout = layout
        val player = MediaPlayer(lib)
        vlc = player
        player.attachViews(layout, null, true, false)

        val gen = generation
        player.setEventListener { event ->
            if (gen != generation) return@setEventListener
            when (event.type) {
                MediaPlayer.Event.Playing -> onPlaying()
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
            setHWDecoderEnabled(true, false)
            if (!isLive && resumePosition > 0) addOption(":start-time=${resumePosition / 1000}")
        }
        player.media = media
        media.release()
        subtitleFile?.let { player.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle, Uri.fromFile(it), true) }
        player.play()
    }

    // endregion

    private fun releasePlayers() {
        exo?.let {
            it.stop()
            it.release()
        }
        exo = null
        vlc?.let {
            it.setEventListener(null)
            it.stop()
            it.detachViews()
            it.release()
        }
        vlc = null
        vlcLayout = null
        container.removeAllViews()
        audioTracks.value = emptyList()
        textTracks.value = emptyList()
    }

    companion object {
        private const val TAG = "PlaybackEngine"
        private val vlcExtensions = setOf("avi", "wmv", "flv", "mpg", "mpeg", "divx", "rmvb", "3gp", "vob", "ogm", "asf", "m2ts", "mts")
    }
}
