package app.iptv.tv.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File

/**
 * App-Updates aus dem GitHub-Repository: `android/release/version.json` beschreibt die neueste
 * Version, `IPTV-TV.apk` daneben ist die App. Installation über den Android-Paketinstaller.
 */
object AppUpdate {
    @Serializable
    data class Info(val versionCode: Int, val versionName: String, val notes: String = "", val apk: String? = null)

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data object UpToDate : State()
        data class Available(val info: Info) : State()
        data class Downloading(val info: Info, val percent: Int) : State()
        data class Failed(val message: String) : State()
    }

    private const val REPO = "https://raw.githubusercontent.com/kresbach/iptv"
    /** Zuerst der Entwicklungszweig, dann der Hauptzweig. */
    private val sources = listOf("claude/loving-maxwell-iaygiz", "main")
    private const val CHECK_INTERVAL = 12 * 3600_000L

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val state = MutableStateFlow<State>(State.Idle)
    private var source: String = sources.first()

    fun currentVersion(context: Context): Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }.getOrDefault(0)

    /** Beim Start: höchstens alle 12 Stunden still nachsehen. */
    fun checkInBackground(context: Context) {
        if (!Prefs.autoUpdateCheck) return
        if (System.currentTimeMillis() - Prefs.lastUpdateCheck < CHECK_INTERVAL) {
            // Bekannte neuere Version weiter anzeigen
            Prefs.knownUpdate?.let { runCatching { json.decodeFromString(Info.serializer(), it) }.getOrNull() }
                ?.takeIf { it.versionCode > currentVersion(context) }?.let { state.value = State.Available(it) }
            return
        }
        scope.launch { check(context, quiet = true) }
    }

    fun checkNow(context: Context) {
        scope.launch { check(context, quiet = false) }
    }

    private suspend fun check(context: Context, quiet: Boolean) {
        if (!quiet) state.value = State.Checking
        val info = sources.firstNotNullOfOrNull { branch ->
            runCatching {
                json.decodeFromString(Info.serializer(), Http.text("$REPO/$branch/android/release/version.json"))
            }.getOrNull()?.also { source = branch }
        }
        Prefs.lastUpdateCheck = System.currentTimeMillis()
        state.value = when {
            info == null -> if (quiet) State.Idle else State.Failed("Keine Verbindung zum Update-Server")
            info.versionCode > currentVersion(context) -> {
                Prefs.knownUpdate = json.encodeToString(Info.serializer(), info)
                State.Available(info)
            }
            else -> { Prefs.knownUpdate = null; if (quiet) State.Idle else State.UpToDate }
        }
    }

    /** Lädt die neue Version und öffnet den Installationsdialog von Android. */
    fun downloadAndInstall(context: Context, info: Info) {
        if (state.value is State.Downloading) return
        scope.launch {
            try {
                val file = File(context.cacheDir, "updates/IPTV-TV.apk").apply { parentFile?.mkdirs(); delete() }
                val url = info.apk ?: "$REPO/$source/android/release/IPTV-TV.apk"
                state.value = State.Downloading(info, 0)
                Http.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw Http.StatusException(response.code)
                    val body = response.body ?: throw java.io.IOException("Leere Antwort")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var last = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                                if (pct != last) { last = pct; state.value = State.Downloading(info, pct) }
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) { install(context, file) }
                state.value = State.Available(info)
            } catch (e: Throwable) {
                state.value = State.Failed("Download fehlgeschlagen: " + Http.message(e))
            }
        }
    }

    private fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
