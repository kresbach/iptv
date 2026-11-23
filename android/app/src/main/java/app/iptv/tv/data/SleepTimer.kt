package app.iptv.tv.data

import kotlinx.coroutines.flow.MutableStateFlow

/** Sleep-Timer: Wiedergabe endet zur eingestellten Zeit (gilt über Senderwechsel hinweg). */
object SleepTimer {
    val endsAt = MutableStateFlow<Long?>(null)
    val steps = listOf(0, 15, 30, 60, 90, 120)

    /** Nächste Stufe: Aus → 15 → 30 → 60 → 90 → 120 → Aus. */
    fun cycle(now: Long = System.currentTimeMillis()) {
        val current = minutesLeft(now)
        val next = steps.firstOrNull { it > current } ?: 0
        endsAt.value = if (next == 0) null else now + next * 60_000L
    }

    fun extend(minutes: Int = 30, now: Long = System.currentTimeMillis()) {
        endsAt.value = maxOf(endsAt.value ?: now, now) + minutes * 60_000L
    }

    fun minutesLeft(now: Long = System.currentTimeMillis()): Int =
        endsAt.value?.let { ((it - now + 59_999) / 60_000).toInt().coerceAtLeast(0) } ?: 0

    fun label(now: Long = System.currentTimeMillis()): String =
        if (endsAt.value == null) "Aus" else "noch ${minutesLeft(now)} Min."
}
