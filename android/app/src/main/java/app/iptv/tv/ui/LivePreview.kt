package app.iptv.tv.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import app.iptv.tv.model.Channel
import app.iptv.tv.model.EpgGuide
import app.iptv.tv.player.PlaybackEngine

/**
 * Übergabe des Vorschau-Players an den Vollbild-Player: Der Sender läuft einfach weiter,
 * ohne neue Verbindung (wichtig bei nur einer erlaubten Verbindung – und schneller).
 */
object LivePreview {
    private var engine: PlaybackEngine? = null
    private var key: String? = null
    /** Für Tests: wie oft ein laufender Sender übernommen wurde. */
    @Volatile var adoptions = 0
        private set

    fun handOver(e: PlaybackEngine, channelKey: String) {
        e.detachSurface()
        (e.container.parent as? ViewGroup)?.removeView(e.container)
        engine = e
        key = channelKey
    }

    /** Übernimmt den Player, wenn er genau diesen Sender spielt; sonst wird ein Rest freigegeben. */
    fun take(channelKey: String): PlaybackEngine? {
        val e = engine ?: return null
        val match = key == channelKey
        engine = null
        key = null
        if (!match) { e.release(); return null }
        adoptions++
        return e
    }
}

/** Vorschaufenster rechts in Live TV: Video, Sendung, Beschreibung, danach. */
@Composable
fun PreviewPanel(engine: PlaybackEngine, channel: Channel?, epg: EpgGuide?, now: Long, active: Boolean, modifier: Modifier = Modifier) {
    val status by engine.status.collectAsState()
    Column(modifier.fillMaxHeight().padding(top = 32.dp, end = 32.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { (engine.container.parent as? ViewGroup)?.removeView(engine.container); engine.container },
                modifier = Modifier.fillMaxSize(),
            )
            when {
                channel == null -> Text("Vorschau: auf einem Sender kurz stehen bleiben", fontSize = 14.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(16.dp))
                !active || status.buffering -> Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    RemoteImage(channel.logo, channel.initials, Modifier.width(120.dp).height(70.dp))
                    if (active) Spinner(Modifier.align(Alignment.BottomCenter).padding(10.dp), size = 26.dp)
                }
            }
        }
        if (channel != null) {
            Spacer(Modifier.height(14.dp))
            Text(channel.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val current = epg?.current(channel, now)
            val next = epg?.next(channel, now)
            if (current != null) {
                Text("${clock(current.start)} – ${clock(current.end)}  ${current.title}", fontSize = 16.sp, color = AppColors.OrangeLight, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                ProgressLine(current.progress(now), Modifier.fillMaxWidth().padding(top = 6.dp))
                current.description?.let {
                    Text(it, fontSize = 14.sp, color = AppColors.TextPrimary, maxLines = 5, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
            if (next != null) {
                Text("Danach ${clock(next.start)}  ${next.title}", fontSize = 14.sp, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text("OK = Vollbild", fontSize = 13.sp, color = AppColors.TextSecondary)
        }
    }
}
