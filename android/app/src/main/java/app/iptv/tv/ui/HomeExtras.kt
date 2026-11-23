package app.iptv.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.iptv.tv.data.Sport
import app.iptv.tv.model.Channel
import app.iptv.tv.model.MediaInfo
import app.iptv.tv.model.SeriesShow
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

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

/** Ein Eintrag im großen Titelbild der Startseite. */
class HeroItem(
    val title: String,
    val subtitle: String?,
    val image: String?,
    val poster: String?,
    val movie: Channel? = null,
    val show: SeriesShow? = null,
    val sport: Sport.Event? = null,
)

/**
 * Großes Titelbild oben auf der Startseite (wie bei Netflix): Bild, Titel, kurze Beschreibung und
 * „Abspielen“/„Details“. Wechselt alle 10 Sekunden – aber nicht, solange ein Knopf markiert ist.
 */
@Composable
fun HeroBanner(
    items: List<HeroItem>,
    memory: ScreenMemory,
    onPlay: (HeroItem) -> Unit,
    onDetails: (HeroItem) -> Unit,
) {
    if (items.isEmpty()) return
    var index by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(items.size, focused) {
        while (!focused && items.size > 1) {
            delay(10_000)
            index = (index + 1) % items.size
        }
    }
    val item = items[index.coerceIn(0, items.lastIndex)]
    // Fehlende Beschreibung/Hintergrundbild nachladen (Anbieter, sonst TMDB)
    val info by produceState<MediaInfo?>(null, item) {
        value = null
        value = runCatching {
            val base = item.movie?.let { app.iptv.tv.data.Repository.movieInfo(it) }
            val year = base?.year ?: app.iptv.tv.data.MediaSort.year(item.title)?.toString()
            if (item.sport != null) null
            else app.iptv.tv.data.Similar.merge(base, app.iptv.tv.data.Similar.extras(item.title, year, tv = item.show != null))
        }.getOrNull()
    }
    val image = item.image ?: info?.backdrop
    Box(
        Modifier.fillMaxWidth().height(250.dp).padding(horizontal = 40.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(18.dp)).background(AppColors.Surface),
    ) {
        if (image != null) {
            AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.75f)
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.92f), Color.Black.copy(alpha = 0.55f), Color.Transparent)),
            ),
        )
        if (item.sport != null) {
            // Laufendes Spiel: Senderlogo groß rechts
            Box(
                Modifier.align(Alignment.CenterEnd).padding(end = 48.dp).width(260.dp).height(150.dp)
                    .clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.35f)).padding(20.dp),
            ) {
                RemoteImage(item.sport.channel.logo, item.sport.channel.initials, Modifier.fillMaxSize())
            }
        } else if (image == null && (item.poster ?: info?.poster) != null) {
            // Kein breites Bild: Cover rechts zeigen
            Box(
                Modifier.align(Alignment.CenterEnd).padding(18.dp).fillMaxHeight().aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(12.dp)),
            ) {
                RemoteImage(item.poster ?: info?.poster, item.title.take(2), Modifier.fillMaxSize(), ContentScale.Crop)
            }
        }
        Column(Modifier.fillMaxHeight().fillMaxWidth(0.62f).padding(start = 28.dp, top = 22.dp, bottom = 18.dp)) {
            if (item.sport != null) {
                Text("⚽ LIVE · ${item.sport.channel.name}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LiveRed)
            } else if (item.subtitle != null) {
                Text(item.subtitle, fontSize = 14.sp, color = AppColors.OrangeLight)
            }
            Text(item.title, fontSize = 30.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 34.sp)
            val facts = listOfNotNull(info?.year, info?.durationText, info?.rating?.let { "★ %.1f".format(it) })
            if (facts.isNotEmpty() || info?.age != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    info?.age?.let { AgeBadge(it); Spacer(Modifier.width(10.dp)) }
                    Text(facts.joinToString("  ·  "), fontSize = 14.sp, color = AppColors.TextSecondary)
                }
            }
            val text = item.sport?.programme?.description ?: info?.plot ?: item.show?.plot
            if (text != null) {
                Text(text, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp, color = AppColors.TextPrimary, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.weight(1f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.onFocusChanged { focused = it.hasFocus },
            ) {
                AppButton(
                    if (item.sport != null) "Jetzt einschalten" else "Abspielen", { onPlay(item) }, icon = AppIcons.Play,
                    modifier = Modifier.rememberFocus(memory, "hero-play"),
                )
                if (item.sport == null) {
                    AppButton("Details", { onDetails(item) }, primary = false, modifier = Modifier.rememberFocus(memory, "hero-details"))
                }
                if (items.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 8.dp)) {
                        items.indices.forEach { i ->
                            Box(Modifier.size(8.dp).background(if (i == index) AppColors.Orange else Color.White.copy(alpha = 0.3f), CircleShape))
                        }
                    }
                }
            }
        }
    }
}
