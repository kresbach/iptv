package app.iptv.tv.data

import android.content.Context
import android.util.Log
import app.iptv.tv.model.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

@Serializable
enum class DownloadState { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED }

/** Ein heruntergeladener (oder wartender) Film bzw. eine Episode. */
@Serializable
data class DownloadItem(
    val id: String = UUID.randomUUID().toString(),
    val channel: Channel,
    val title: String,
    val subtitle: String? = null,
    val poster: String? = null,
    val showKey: String? = null,
    val state: DownloadState = DownloadState.QUEUED,
    val error: String? = null,
    val bytesWritten: Long = 0,
    val totalBytes: Long = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val ext: String = "mp4",
) {
    val key: String get() = channel.stableKey
    val fraction: Float get() = if (totalBytes > 0) (bytesWritten.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    val isActive: Boolean get() = state == DownloadState.QUEUED || state == DownloadState.DOWNLOADING || state == DownloadState.PAUSED
}

/**
 * Lädt Filme und Episoden auf den Fernseher (solange die App läuft).
 * Immer nur ein Download gleichzeitig (IPTV-Konten erlauben oft nur eine Verbindung);
 * während ein Stream läuft, pausiert der Download und setzt danach an derselben Stelle fort.
 */
object Downloads {
    private const val TAG = "Downloads"
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dir: File
    private lateinit var listFile: File
    private var worker: Job? = null

    val items = MutableStateFlow<List<DownloadItem>>(emptyList())

    fun start(context: Context) {
        if (::dir.isInitialized) return
        dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads").apply { mkdirs() }
        listFile = File(context.filesDir, "downloads.json")
        items.value = runCatching {
            json.decodeFromString(ListSerializer(DownloadItem.serializer()), listFile.readText())
        }.getOrDefault(emptyList()).map {
            // Nach Neustart: unterbrochene Downloads wieder einreihen.
            if (it.state == DownloadState.DOWNLOADING) it.copy(state = DownloadState.QUEUED) else it
        }
        kick()
    }

    fun file(item: DownloadItem) = File(dir, "${item.id}.${item.ext}")

    /** Lokale Datei eines fertigen Downloads, sonst null. */
    fun localFile(channel: Channel): File? =
        items.value.firstOrNull { it.key == channel.stableKey && it.state == DownloadState.COMPLETED }
            ?.let(::file)?.takeIf { it.exists() }

    fun item(channel: Channel) = items.value.firstOrNull { it.key == channel.stableKey }

    fun usedBytes(): Long = items.value.filter { it.state == DownloadState.COMPLETED }.sumOf { file(it).length() }

    fun enqueue(channel: Channel, title: String, subtitle: String? = null, poster: String? = null, showKey: String? = null) {
        if (item(channel) != null) return
        val ext = UrlTools.extension(channel.url).takeIf { it.isNotEmpty() && it.length <= 4 } ?: "mp4"
        update { it + DownloadItem(channel = channel, title = title, subtitle = subtitle, poster = poster, showKey = showKey, ext = ext) }
        kick()
    }

    /** Bricht einen Download ab bzw. löscht eine fertige Datei. */
    fun remove(item: DownloadItem) {
        update { list -> list.filterNot { it.id == item.id } }
        file(item).delete()
        File(dir, "${item.id}.part").delete()
    }

    fun retry(item: DownloadItem) {
        failures.remove(item.id)
        update { list -> list.map { if (it.id == item.id) it.copy(state = DownloadState.QUEUED, error = null) else it } }
        kick()
    }

    fun removeAll() {
        items.value.forEach { file(it).delete(); File(dir, "${it.id}.part").delete() }
        update { emptyList() }
    }

    /** Vom Player: zu Ende gesehen → bei „Gesehene automatisch löschen“ entfernen. */
    fun watched(channel: Channel) {
        if (!Prefs.downloadAutoDelete) return
        items.value.firstOrNull { it.key == channel.stableKey && it.state == DownloadState.COMPLETED }?.let(::remove)
    }

    private fun update(change: (List<DownloadItem>) -> List<DownloadItem>) {
        synchronized(this) {
            items.value = change(items.value)
            val snapshot = items.value
            runCatching { listFile.writeText(json.encodeToString(ListSerializer(DownloadItem.serializer()), snapshot)) }
        }
    }

    private fun set(id: String, change: (DownloadItem) -> DownloadItem) =
        update { list -> list.map { if (it.id == id) change(it) else it } }

    /** Netzwerkfehler in Folge je Download (danach: fehlgeschlagen, statt endlos zu versuchen). */
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Int>()

    @Synchronized
    private fun kick() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (isActive) {
                val next = items.value.firstOrNull { it.state == DownloadState.QUEUED || it.state == DownloadState.PAUSED }
                    ?: break
                runOne(next)
            }
        }
    }

    private fun shouldPause() = Prefs.downloadPauseDuringPlayback && Repository.playbackActive

    private suspend fun runOne(start: DownloadItem) {
        // Während ein Stream läuft: warten (Verbindung des Kontos freihalten).
        if (shouldPause()) {
            set(start.id) { it.copy(state = DownloadState.PAUSED) }
            while (shouldPause()) delay(3_000)
        }
        val item = items.value.firstOrNull { it.id == start.id } ?: return
        val part = File(dir, "${item.id}.part")
        val limit = Prefs.downloadLimitGb.toLong() * 1_000_000_000L
        set(item.id) { it.copy(state = DownloadState.DOWNLOADING, error = null) }
        try {
            val offset = if (part.exists()) part.length() else 0L
            val request = Request.Builder().url(item.channel.url)
                .apply { if (offset > 0) header("Range", "bytes=$offset-") }
                .build()
            Http.client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) throw Http.StatusException(r.code)
                val resumed = r.code == 206
                val body = r.body ?: throw UserFacingException("Leere Antwort vom Server.")
                val total = if (resumed) offset + body.contentLength() else body.contentLength()
                if (limit > 0 && total > 0 && usedBytes() + total > limit) {
                    throw UserFacingException("Speicherlimit erreicht (${Prefs.downloadLimitGb} GB). Lösche alte Downloads oder erhöhe das Limit.")
                }
                var written = if (resumed) offset else 0L
                set(item.id) { it.copy(totalBytes = total.coerceAtLeast(0), bytesWritten = written) }
                RandomAccessFile(part, "rw").use { out ->
                    out.setLength(written)
                    out.seek(written)
                    val buffer = ByteArray(256 * 1024)
                    val input = body.byteStream()
                    var lastUpdate = 0L
                    while (true) {
                        if (items.value.none { it.id == item.id }) return // gelöscht
                        if (shouldPause()) {
                            set(item.id) { it.copy(state = DownloadState.PAUSED, bytesWritten = written) }
                            return // Verbindung schließen; Fortsetzung später per Range
                        }
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 1_000) {
                            lastUpdate = now
                            set(item.id) { it.copy(bytesWritten = written) }
                        }
                    }
                }
                if (total > 0 && written < total) throw java.io.IOException("Verbindung abgebrochen")
                part.renameTo(file(item))
                failures.remove(item.id)
                set(item.id) { it.copy(state = DownloadState.COMPLETED, bytesWritten = written, totalBytes = written) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Download ${item.title}", e)
            val retryable = e is java.io.IOException && e !is Http.StatusException
            val count = (failures[item.id] ?: 0) + 1
            failures[item.id] = count
            if (retryable && count <= 5 && (items.value.firstOrNull { it.id == item.id }?.state == DownloadState.DOWNLOADING)) {
                // Netzwerkfehler: kurz warten und fortsetzen.
                set(item.id) { it.copy(state = DownloadState.PAUSED, error = Http.message(e)) }
                delay(10_000)
            } else {
                set(item.id) { it.copy(state = DownloadState.FAILED, error = Http.message(e)) }
            }
        }
    }

    /** Nach Ende der Wiedergabe aufrufen: pausierte Downloads fortsetzen. */
    fun resume() = kick()
}
