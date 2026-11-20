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
                    val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
                    append("Version ").append(version).append(" · Bildschirm: ").append(lastScreen).append('\n')
                    append(error.javaClass.simpleName).append(": ").append(error.message ?: "").append('\n')
                    // Erste Zeilen + alle Zeilen aus der App selbst (zeigen, welcher Bildschirm/Teil betroffen ist)
                    val frames = error.stackTrace
                    frames.take(3).forEach { append("  bei ").append(it.toString()).append('\n') }
                    val own = frames.drop(3).filter { it.className.startsWith("app.iptv.tv") }.take(6)
                    if (own.isNotEmpty()) {
                        append("  … App:\n")
                        own.forEach { append("  bei ").append(it.toString()).append('\n') }
                    }
                    error.cause?.let { append("Ursache: ").append(it.javaClass.simpleName).append(": ").append(it.message ?: "") }
                }
                crashFile(this).writeText(text)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        /** Zuletzt angezeigter Bildschirm (für die Absturzmeldung). */
        @Volatile var lastScreen: String = "?"

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
