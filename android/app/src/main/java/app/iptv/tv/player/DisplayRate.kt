package app.iptv.tv.player

import android.app.Activity
import android.os.Build
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Passt die Bildwiederholrate des Fernsehers an das Video an. Ohne Anpassung zeigt ein 60-Hz-Bildschirm
 * 50-Bilder-Fernsehen (DE/EU) mit regelmäßig doppelten Bildern – das sieht man als Ruckeln.
 */
object DisplayRate {
    private val standardRates = listOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f)

    /**
     * Bildabstand aus gemessenen Zeitstempel-Abständen. Verworfene Bilder erzeugen Lücken (doppelte,
     * dreifache Abstände), deshalb zählt der kleinste Abstand, der mehrfach vorkommt.
     */
    fun frameIntervalUs(deltas: List<Long>): Long {
        val sorted = deltas.filter { it in 5_000..200_000 }.sorted()
        if (sorted.isEmpty()) return 40_000
        // 20-%-Quantil: robust gegen einzelne Ausreißer nach unten, ignoriert die Lücken nach oben.
        return sorted[(sorted.size * 0.2).toInt().coerceAtMost(sorted.lastIndex)]
    }

    /** Gemessene Bildrate auf übliche Werte runden (z. B. 24,98 → 25). */
    fun snapFps(raw: Float): Float? {
        if (raw <= 0f || raw.isNaN()) return null
        val best = standardRates.minByOrNull { abs(it - raw) } ?: return null
        return best.takeIf { abs(it - raw) / it < 0.03f }
    }

    /**
     * Passende Bildschirmfrequenz: ganzzahliges Vielfaches der Bildrate, möglichst genau und möglichst
     * niedriges Vielfaches (25 fps → 50 Hz, 23,976 → 23,976 Hz, 29,97 → 59,94 Hz).
     */
    fun bestRefreshRate(fps: Float, rates: List<Float>): Float? {
        // Fernsehen (25/50 Bilder, 1080i) nie auf 25 Hz stellen: nach dem Deinterlacing kommen 50 Bilder,
        // und 25 Hz flimmert. Ab 25 fps also nur Frequenzen ab ~48 Hz, sofern der Fernseher welche hat.
        if (fps >= 24.5f) {
            val high = rates.filter { it >= 47f }
            matching(fps, high)?.let { return it }
            if (high.isNotEmpty()) return null
        }
        return matching(fps, rates)
    }

    private fun matching(fps: Float, rates: List<Float>): Float? =
        rates.mapNotNull { r ->
            if (r < 23f) return@mapNotNull null
            val k = r / fps
            val n = k.roundToInt()
            val err = abs(k - n) / n
            if (n < 1 || err > 0.002f) null else Triple(r, n, err)
        }.sortedWith(compareBy({ if (it.third < 0.0005f) 0 else 1 }, { it.second }, { it.third })).firstOrNull()?.first

    private var originalModeId = -1

    /** Display des Fensters; fällt bei Problemen auf das Standard-Display zurück (nie Absturz). */
    @Suppress("DEPRECATION")
    private fun displayOf(activity: Activity?): Display? {
        activity ?: return null
        if (Build.VERSION.SDK_INT >= 30) runCatching { activity.display }.getOrNull()?.let { return it }
        return runCatching { activity.windowManager.defaultDisplay }.getOrNull()
    }

    /** Activity hinter einem (evtl. verpackten) Context finden. */
    fun findActivity(context: android.content.Context?): Activity? {
        var c = context
        while (c is android.content.ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    /** Wählt den passenden Bildmodus (gleiche Auflösung, passende Frequenz). Rückgabe: gesetzte Frequenz. */
    fun apply(activity: Activity?, fps: Float): Float? = runCatching { applyUnsafe(activity, fps) }.getOrNull()

    private fun applyUnsafe(activity: Activity?, fps: Float): Float? {
        val window = activity?.window ?: return null
        val display = displayOf(activity) ?: return null
        val current = runCatching { display.mode }.getOrNull() ?: return null
        val sameSize = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        val rate = bestRefreshRate(fps, sameSize.map { it.refreshRate }.distinct()) ?: return null
        val mode = sameSize.firstOrNull { abs(it.refreshRate - rate) < 0.01f } ?: return null
        if (originalModeId == -1) originalModeId = window.attributes.preferredDisplayModeId
        if (window.attributes.preferredDisplayModeId != mode.modeId && abs(current.refreshRate - rate) > 0.01f) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = mode.modeId }
        }
        return rate
    }

    /** Beim Verlassen des Players: Fernseher entscheidet wieder selbst. */
    fun reset(activity: Activity?) {
        val window = activity?.window ?: return
        if (originalModeId != -1) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = originalModeId }
            originalModeId = -1
        }
    }

    /**
     * Bildrate je Sender merken: Beim nächsten Umschalten auf diesen Sender wird der Fernseher schon
     * vor dem Start passend eingestellt – das kurze Schwarzbild beim Umstellen fällt dann nicht mitten
     * ins laufende Spiel.
     */
    private var store: android.content.SharedPreferences? = null

    fun init(context: android.content.Context) {
        if (store == null) store = runCatching { context.getSharedPreferences("framerates", android.content.Context.MODE_PRIVATE) }.getOrNull()
    }

    fun remembered(channelKey: String): Float? = store?.getFloat(channelKey, 0f)?.takeIf { it > 0f }
        ?: store?.getFloat(LAST_LIVE, 0f)?.takeIf { it > 0f }

    fun remember(channelKey: String, fps: Float) {
        val s = store ?: return
        if (s.getFloat(channelKey, 0f) == fps && s.getFloat(LAST_LIVE, 0f) == fps) return
        s.edit().putFloat(channelKey, fps).putFloat(LAST_LIVE, fps).apply()
    }

    private const val LAST_LIVE = "_last_live"

    fun currentRate(activity: Activity?): Float? = runCatching { displayOf(activity)?.mode?.refreshRate }.getOrNull()
}
