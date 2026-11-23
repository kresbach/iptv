package app.iptv.tv.player

/**
 * Wertet VLCs Zähler „gezeigte/verlorene Bilder“ aus (Aufruf etwa alle 2 s).
 * Meldet `true`, wenn der Fernseher über mehrere Messungen hinweg deutlich Bilder verliert –
 * dann ist das Gerät überlastet (nicht das Internet: Messungen mit Stream-Fehlern zählen nicht).
 */
class FrameMonitor(
    /** Ab diesem Anteil verlorener Bilder gilt eine Messung als schlecht. */
    private val badPercent: Float = 6f,
    /** So viele schlechte Messungen in Folge lösen die Meldung aus (≈ 8 s). */
    private val badInRow: Int = 4,
    /** Die ersten Sekunden nach dem Start (Einschwingen) nicht bewerten. */
    private val warmupMs: Long = 8_000,
) {
    private var lastTime = -1L
    private var lastShown = 0
    private var lastLost = 0
    private var lastErrors = 0
    private var bad = 0

    var shownFps = 0f
        private set
    var lostPercent = 0f
        private set

    fun reset() {
        lastTime = -1L
        bad = 0
        shownFps = 0f
        lostPercent = 0f
    }

    fun update(nowMs: Long, shown: Int, lost: Int, streamErrors: Int, playingMs: Long): Boolean {
        if (lastTime < 0 || shown < lastShown || lost < lastLost) {
            // erste Messung oder Zähler zurückgesetzt (neuer Stream)
            lastTime = nowMs; lastShown = shown; lastLost = lost; lastErrors = streamErrors
            return false
        }
        val dt = (nowMs - lastTime).coerceAtLeast(1)
        val dShown = shown - lastShown
        val dLost = lost - lastLost
        val dErrors = streamErrors - lastErrors
        lastTime = nowMs; lastShown = shown; lastLost = lost; lastErrors = streamErrors
        val total = dShown + dLost
        if (total < 10) return false // Pause/Nachladen: nichts zu bewerten
        shownFps = dShown * 1000f / dt
        lostPercent = dLost * 100f / total
        if (playingMs < warmupMs || dErrors > 0) return false
        bad = if (lostPercent >= badPercent) bad + 1 else 0
        if (bad >= badInRow) {
            bad = 0
            return true
        }
        return false
    }
}
