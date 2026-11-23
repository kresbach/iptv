package app.iptv.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.iptv.tv.data.Sport

private val LiveRed = Color(0xFFE53935)

/** Kachel für „Sport jetzt live“: Senderlogo, Begegnung, LIVE bzw. Anstoßzeit. */
@Composable
fun SportCard(event: Sport.Event, now: Long, reminded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = event.programme
    val live = event.isLive(now)
    FocusSurface(onClick = onClick, modifier = modifier.width(240.dp)) {
        Column {
            Box(Modifier.fillMaxWidth().height(70.dp).background(Color(0xFF14141A))) {
                RemoteImage(event.channel.logo, event.channel.initials, Modifier.fillMaxSize().padding(10.dp))
                Row(
                    Modifier.align(Alignment.TopStart).padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (live) LiveRed else Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (live) {
                        Box(Modifier.size(7.dp).background(Color.White, CircleShape))
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        if (live) "LIVE" else dayClock(p.start, now),
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    )
                }
                if (reminded) Text("⏰", fontSize = 14.sp, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(p.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
                Text(
                    event.channel.name + if (live) " · noch ${((p.end - now) / 60_000).coerceAtLeast(1)} Min." else " · OK = erinnern",
                    fontSize = 11.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                ProgressLine(if (live) p.progress(now) else 0f, Modifier.fillMaxWidth(), 2.dp)
            }
        }
    }
}

/** „20:45“ bzw. „Morgen 01:00“. */
private fun dayClock(ms: Long, now: Long): String {
    val day = 86_400_000L
    val tz = java.util.TimeZone.getDefault()
    val d1 = (ms + tz.getOffset(ms)) / day
    val d0 = (now + tz.getOffset(now)) / day
    return (if (d1 > d0) "Morgen " else "") + clock(ms)
}
