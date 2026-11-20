package app.iptv.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * HTTP mit VLC-User-Agent: viele IPTV-Anbieter sperren unbekannte Clients.
 */
object Http {
    const val USER_AGENT = "VLC/3.0.21 LibVLC/3.0.21"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build()
    }

    class StatusException(val code: Int) : IOException("HTTP $code")

    private fun execute(url: String): Response {
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw StatusException(response.code)
        }
        return response
    }

    suspend fun text(url: String): String = withContext(Dispatchers.IO) {
        execute(url).use { it.body?.string() ?: "" }
    }

    suspend fun <T> stream(url: String, block: (java.io.InputStream) -> T): T = withContext(Dispatchers.IO) {
        execute(url).use { response -> block(response.body!!.byteStream()) }
    }

    /** Große Dateien (TV-Guide) auf die Festplatte statt in den Speicher. */
    suspend fun download(url: String, target: File): File = withContext(Dispatchers.IO) {
        execute(url).use { response ->
            target.outputStream().use { out -> response.body!!.byteStream().copyTo(out, 64 * 1024) }
        }
        target
    }

    /** Verständliche Fehlermeldung auf Deutsch. */
    fun message(error: Throwable): String = when (error) {
        is UserFacingException -> error.message ?: "Unbekannter Fehler"
        is StatusException -> when (error.code) {
            401, 403 -> "Zugriff verweigert (Fehler ${error.code}). Bitte prüfe Adresse bzw. Zugangsdaten."
            404 -> "Die Adresse wurde auf dem Server nicht gefunden (Fehler 404)."
            else -> "Der Server hat mit Fehler ${error.code} geantwortet."
        }
        is UnknownHostException -> "Server nicht gefunden. Bitte prüfe die Adresse und die Internetverbindung."
        is SocketTimeoutException -> "Der Server antwortet nicht (Zeitüberschreitung)."
        is java.net.ConnectException -> "Keine Verbindung zum Server möglich."
        is IllegalArgumentException -> "Ungültige Adresse."
        is kotlinx.serialization.SerializationException -> "Die Antwort des Servers konnte nicht gelesen werden."
        else -> error.message ?: "Unbekannter Fehler"
    }
}
