package app.iptv.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.iptv.tv.model.Channel
import app.iptv.tv.model.Programme

/** Große Kachel mit orangem Verlauf (Live TV, Filme, Serien …). */
@Composable
fun TileCard(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = true,
) {
    FocusSurface(onClick = onClick, modifier = modifier.width(180.dp).height(96.dp), color = AppColors.Surface) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (highlighted) Modifier.background(AppColors.Gradient) else Modifier)
                .padding(12.dp),
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp).align(Alignment.TopStart))
            Column(Modifier.align(Alignment.BottomStart)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
            }
        }
    }
}

/** Sender-Kachel (Logo, Name, aktuelle Sendung). */
@Composable
fun ChannelCard(
    channel: Channel,
    programme: Programme?,
    now: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    isFavorite: Boolean = false,
) {
    FocusSurface(onClick = onClick, onLongClick = onLongClick, modifier = modifier.width(176.dp)) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .background(Color(0xFF14141A)),
            ) {
                RemoteImage(channel.logo, channel.initials, Modifier.fillMaxSize().padding(10.dp))
                if (isFavorite) FavoriteBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(channel.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    programme?.title ?: " ",
                    fontSize = 11.sp,
                    color = AppColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                ProgressLine(programme?.progress(now) ?: 0f, Modifier.fillMaxWidth(), 2.dp)
            }
        }
    }
}

/** Cover-Kachel für Filme und Serien (2:3). */
@Composable
fun PosterCard(
    title: String,
    image: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    isFavorite: Boolean = false,
    initials: String = "",
) {
    Column(modifier.width(112.dp)) {
        FocusSurface(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            Box(Modifier.fillMaxSize().background(Color(0xFF14141A))) {
                RemoteImage(image, initials, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (isFavorite) FavoriteBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
                if (progress != null) {
                    ProgressLine(progress, Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(6.dp))
                }
            }
        }
        Text(
            title,
            modifier = Modifier.padding(top = 5.dp, start = 2.dp, end = 2.dp),
            fontSize = 12.sp,
            lineHeight = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = AppColors.TextPrimary,
        )
    }
}

@Composable
fun FavoriteBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.Orange),
        contentAlignment = Alignment.Center,
    ) {
        Icon(AppIcons.Star, null, tint = Color.White, modifier = Modifier.size(12.dp))
    }
}

/** Zeile in der Senderliste (Live TV und Player-Liste). */
@Composable
fun ChannelRow(
    number: Int,
    channel: Channel,
    programme: Programme?,
    now: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    isFavorite: Boolean = false,
    isPlaying: Boolean = false,
) {
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth(),
        scale = 1.02f,
        color = if (isPlaying) AppColors.Orange.copy(alpha = 0.2f) else AppColors.Surface,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("$number", fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.width(34.dp))
            Box(
                Modifier
                    .size(width = 54.dp, height = 32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF14141A)),
            ) {
                RemoteImage(channel.logo, channel.initials, Modifier.fillMaxSize().padding(3.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        channel.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isFavorite) {
                        Spacer(Modifier.width(8.dp))
                        Icon(AppIcons.Star, null, tint = AppColors.Orange, modifier = Modifier.size(16.dp))
                    }
                }
                if (programme != null) {
                    Text(
                        "${clock(programme.start)}  ${programme.title}",
                        fontSize = 12.sp,
                        color = AppColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    ProgressLine(programme.progress(now), Modifier.fillMaxWidth(0.6f), 2.dp)
                }
            }
        }
    }
}
