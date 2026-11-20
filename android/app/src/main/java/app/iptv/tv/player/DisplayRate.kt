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
    fun bestRefreshRate(fps: Float, rates: List<Float>): Float? =
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

    fun currentRate(activity: Activity?): Float? = runCatching { displayOf(activity)?.mode?.refreshRate }.getOrNull()
}
