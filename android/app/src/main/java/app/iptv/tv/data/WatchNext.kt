package app.iptv.tv.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import app.iptv.tv.model.ContentKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * „Weiterschauen“ auf dem Startbildschirm von Google TV / Android TV: angefangene Filme und
 * Episoden erscheinen dort; ein Klick öffnet die App und spielt an der Stelle weiter.
 */
object WatchNext {
    private const val TAG = "WatchNext"
    private const val LIMIT = 10
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    /** Link, über den der Startbildschirm die App öffnet. */
    fun link(key: String): Uri = Uri.Builder().scheme("iptvtv").authority("play").appendQueryParameter("key", key).build()

    @OptIn(FlowPreview::class)
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return
        scope.launch {
            // Während der Wiedergabe nicht schreiben (der Fernseher soll sich ganz aufs Bild konzentrieren).
            Repository.progress.drop(1).debounce(5_000).collectLatest {
                while (Repository.playbackActive) kotlinx.coroutines.delay(15_000)
                if (Prefs.watchNext) sync(app)
            }
        }
    }

    /** Einträge auf dem Startbildschirm an „Weiterschauen“ der App angleichen. */
    @SuppressLint("RestrictedApi")
    fun sync(context: Context) {
        try {
            val sp = context.getSharedPreferences("watchnext", Context.MODE_PRIVATE)
            val known = sp.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()
            val wanted = if (Prefs.watchNext) Repository.continueWatching(Repository.progress.value).take(LIMIT) else emptyList()
            val wantedKeys = wanted.mapNotNull { it.channel?.stableKey }.toSet()
            val editor = sp.edit()
            // Nicht mehr aktuelle entfernen
            for ((key, id) in known) if (key !in wantedKeys) {
                runCatching { context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null) }
                editor.remove(key)
            }
            for (p in wanted) {
                val ch = p.channel ?: continue
                val key = ch.stableKey
                val show = p.showKey?.let { Repository.content.value.show(it) }
                val builder = WatchNextProgram.Builder()
                    .setType(if (ch.kind == ContentKind.SERIES) TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE else TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                    .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                    .setLastEngagementTimeUtcMillis(p.updated)
                    .setLastPlaybackPositionMillis(p.position.toInt())
                    .setDurationMillis(p.duration.toInt())
                    .setTitle(show?.title ?: ch.name)
                    .setInternalProviderId(key)
                    .setIntentUri(link(key))
                if (show != null) builder.setEpisodeTitle(ch.name)
                (ch.logo ?: show?.poster)?.let { builder.setPosterArtUri(Uri.parse(it)).setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3) }
                val values = builder.build().toContentValues()
                val id = known[key]
                val updated = id != null && runCatching {
                    context.contentResolver.update(TvContractCompat.buildWatchNextProgramUri(id), values, null, null) > 0
                }.getOrDefault(false)
                if (!updated) {
                    val uri = context.contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values) ?: continue
                    editor.putLong(key, android.content.ContentUris.parseId(uri))
                }
            }
            editor.apply()
        } catch (e: Throwable) {
            // Gerät ohne TV-Startbildschirm oder Zugriff verweigert – dann eben nicht.
            Log.w(TAG, "Startbildschirm nicht aktualisiert", e)
        }
    }
}
