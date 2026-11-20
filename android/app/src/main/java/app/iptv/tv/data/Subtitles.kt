package app.iptv.tv.data

import app.iptv.tv.model.Channel
import app.iptv.tv.model.ContentKind
import app.iptv.tv.model.MediaInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder

/** Sucht und lädt Untertitel über die OpenSubtitles-API (api.opensubtitles.com), wie in der iOS-App. */
object Subtitles {
    data class Result(
        val fileId: Long,
        val language: String,
        val release: String,
        val downloads: Int,
        val hearingImpaired: Boolean,
    )

    private const val USER_AGENT = "IPTVApp v1.0"
    private val json = Json { ignoreUnknownKeys = true }
    private var token: String? = null
    private var host = "api.opensubtitles.com"
    var remaining: Int? = null
        private set

    private val client: OkHttpClient by lazy { OkHttpClient.Builder().build() }
    lateinit var cacheDir: File

    val isConfigured: Boolean get() = Prefs.subtitleApiKey.isNotEmpty()

    private fun languages(): List<String> =
        Prefs.subtitleLanguages.lowercase().split(Regex("[^a-z-]+")).filter { it.isNotEmpty() }

    /** Suchparameter (alphabetisch sortiert, wie die API es verlangt). */
    fun searchQuery(channel: Channel, languages: List<String>): String {
        val items = ArrayList<Pair<String, String>>()
        val episode = if (channel.kind == ContentKind.SERIES) EpisodeParser.parse(channel.name) else null
        if (episode != null) {
            items += "query" to MediaInfo.versionKey(episode.showTitle).first
            items += "season_number" to episode.season.toString()
            items += "episode_number" to episode.episode.toString()
        } else {
            val (name, year) = MediaInfo.versionKey(channel.name)
            items += "query" to name
            if (year != null) items += "year" to year
        }
        if (languages.isNotEmpty()) items += "languages" to languages.sorted().joinToString(",")
        items += "order_by" to "download_count"
        return items.sortedBy { it.first }.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20").replace("%2C", ",") }
    }

    suspend fun search(channel: Channel): List<Result> = withContext(Dispatchers.IO) {
        if (!isConfigured) throw UserFacingException("Es ist kein OpenSubtitles-API-Key hinterlegt. Trage ihn unter Einstellungen → Untertitel ein.")
        val langs = languages()
        val body = send(Request.Builder().url("https://$host/api/v1/subtitles?" + searchQuery(channel, langs)).build())
        parseResults(body, langs)
    }

    fun parseResults(body: String, preferred: List<String>): List<Result> {
        val data = (json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonArray ?: return emptyList()
        val seen = HashSet<Long>()
        val results = data.mapNotNull { item ->
            val attr = (item as? JsonObject)?.get("attributes") as? JsonObject ?: return@mapNotNull null
            val file = (attr["files"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@mapNotNull null
            val id = file.long("file_id") ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            Result(
                fileId = id,
                language = (attr.str("language") ?: "?").lowercase(),
                release = attr.str("release") ?: file.str("file_name") ?: "Untertitel",
                downloads = attr.int("download_count") ?: 0,
                hearingImpaired = attr.str("hearing_impaired") == "true",
            )
        }
        return results.sortedWith(compareBy({ preferred.indexOf(it.language).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { -it.downloads }))
    }

    /** Lädt den Untertitel als .srt (oder nimmt die bereits gespeicherte Datei). */
    suspend fun download(result: Result, channel: Channel): File = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "subtitles/${result.fileId}.srt")
        if (!file.exists()) {
            loginIfNeeded()
            val payload = buildJsonObject { put("file_id", result.fileId); put("sub_format", "srt") }.toString()
            val body = send(
                Request.Builder().url("https://$host/api/v1/download")
                    .post(payload.toRequestBody("application/json".toMediaType())).build(),
            )
            val info = json.parseToJsonElement(body) as JsonObject
            remaining = info.int("remaining")
            val link = info.str("link") ?: throw UserFacingException(
                "Das Tageslimit für Untertitel-Downloads ist erreicht." + (info.str("reset_time")?.let { " Zurückgesetzt in: $it." } ?: ""),
            )
            val bytes = client.newCall(Request.Builder().url(link).build()).execute().use { r ->
                if (!r.isSuccessful) throw UserFacingException("Der Untertitel konnte nicht geladen werden.")
                r.body!!.bytes()
            }
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
        Prefs.putStr("sub|" + channel.stableKey, file.absolutePath)
        file
    }

    /** Zuletzt für diesen Film gewählter Untertitel, falls noch vorhanden. */
    fun saved(channel: Channel): File? =
        Prefs.str("sub|" + channel.stableKey).takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.exists() }

    fun forget(channel: Channel) = Prefs.putStr("sub|" + channel.stableKey, null)

    private fun loginIfNeeded() {
        if (token != null || Prefs.subtitleUser.isEmpty() || Prefs.subtitlePassword.isEmpty()) return
        val payload = buildJsonObject { put("username", Prefs.subtitleUser); put("password", Prefs.subtitlePassword) }.toString()
        val body = send(
            Request.Builder().url("https://api.opensubtitles.com/api/v1/login")
                .post(payload.toRequestBody("application/json".toMediaType())).build(),
        )
        val login = json.parseToJsonElement(body) as JsonObject
        token = login.str("token")
        login.str("base_url")?.let { if (it.endsWith("opensubtitles.com") && !it.contains('/')) host = it }
    }

    private fun send(original: Request): String {
        val request = original.newBuilder()
            .header("Api-Key", Prefs.subtitleApiKey)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()
        client.newCall(request).execute().use { r ->
            val text = r.body?.string() ?: ""
            when {
                r.isSuccessful -> return text
                r.code == 401 || r.code == 403 -> throw UserFacingException("OpenSubtitles hat die Anfrage abgelehnt. Bitte prüfe API-Key, Benutzername und Passwort.")
                r.code == 406 || r.code == 429 -> throw UserFacingException("Das Tageslimit für Untertitel ist erreicht. Bitte später erneut versuchen.")
                else -> throw UserFacingException("OpenSubtitles meldet Fehler ${r.code}.")
            }
        }
    }
}
