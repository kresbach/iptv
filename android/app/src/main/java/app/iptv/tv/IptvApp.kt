package app.iptv.tv

import android.app.Application
import app.iptv.tv.data.Http
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/** App-Start: Bilder (Logos/Cover) mit VLC-User-Agent und Festplatten-Cache laden. */
class IptvApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        // Absturzgrund speichern, damit er beim nächsten Start angezeigt werden kann.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val text = buildString {
                    append(error.javaClass.simpleName).append(": ").append(error.message ?: "").append('\n')
                    error.stackTrace.take(8).forEach { append("  bei ").append(it.toString()).append('\n') }
                    error.cause?.let { append("Ursache: ").append(it.javaClass.simpleName).append(": ").append(it.message ?: "") }
                }
                crashFile(this).writeText(text)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        fun crashFile(context: android.content.Context) = java.io.File(context.filesDir, "last_crash.txt")
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { Http.client }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(150L * 1024 * 1024).build() }
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()
}
