package app.iptv.tv.ui

import android.content.Context
import app.iptv.tv.data.Repository
import app.iptv.tv.model.Channel
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Lädt Senderlogos im Hintergrund in den Bildspeicher (Datei-Cache), nacheinander und nur solange
 * nichts abgespielt wird. Danach kommen die Logos beim Scrollen sofort von der Festplatte.
 */
object LogoPrefetch {
    private val done = HashSet<String>()

    suspend fun run(context: Context, channels: List<Channel>, limit: Int = 400) {
        delay(800) // erst die sichtbaren Zeilen laden lassen
        val urls = channels.asSequence().mapNotNull { it.logo?.takeIf(String::isNotBlank) }
            .distinct().filter { synchronized(done) { it !in done } }.take(limit).toList()
        if (urls.isEmpty()) return
        val loader = context.imageLoader
        withContext(Dispatchers.IO) {
            for (url in urls) {
                while (Repository.playbackActive) delay(5_000)
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(96, 56) // klein dekodieren: es geht nur um die Datei im Cache
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .build()
                runCatching { loader.execute(request) }
                synchronized(done) { done += url }
            }
        }
    }
}
