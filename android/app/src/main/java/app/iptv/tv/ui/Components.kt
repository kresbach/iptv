package app.iptv.tv.ui

import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.iptv.tv.data.Repository
import coil.compose.AsyncImage
import kotlinx.coroutines.android.awaitFrame

/** Merkt sich pro Bildschirm den zuletzt fokussierten Eintrag und Scrollpositionen (für „Zurück“). */
class ScreenMemory {
    var focusedKey: String? = null
    var restored = false
    val scroll = HashMap<String, Pair<Int, Int>>()
    var selection: String? = null
}

/**
 * Fokus wiederherstellen: Der zuletzt fokussierte Eintrag (oder `isDefault`) bekommt beim
 * Öffnen des Bildschirms den Fokus.
 */
@Composable
fun Modifier.rememberFocus(memory: ScreenMemory, key: String, isDefault: Boolean = false): Modifier {
    val requester = remember { FocusRequester() }
    val wanted = memory.focusedKey == key || (memory.focusedKey == null && isDefault)
    LaunchedEffect(wanted) {
        if (wanted && !memory.restored) {
            awaitFrame()
            if (runCatching { requester.requestFocus() }.isSuccess) memory.restored = true
        }
    }
    // Gemerkter Eintrag existiert nicht mehr (gelöscht, ausgeblendet …) → Standard-Eintrag fokussieren,
    // damit die Fernbedienung nie „ins Leere“ zeigt.
    if (isDefault) {
        LaunchedEffect(Unit) {
            repeat(3) { awaitFrame() }
            if (!memory.restored && runCatching { requester.requestFocus() }.isSuccess) memory.restored = true
        }
    }
    return this
        .focusRequester(requester)
        .onFocusChanged { if (it.isFocused) memory.focusedKey = key }
}

private val cardShape = RoundedCornerShape(12.dp)

@Composable
fun focusBorder() = ClickableSurfaceDefaults.border(
    focusedBorder = Border(BorderStroke(3.dp, AppColors.Orange), shape = cardShape),
)

/** Grundbaustein: fokussierbare Fläche mit orangem Rahmen und leichter Vergrößerung bei Fokus. */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    scale: Float = 1.06f,
    color: Color = AppColors.Surface,
    focusedColor: Color = AppColors.SurfaceHigh,
    shape: RoundedCornerShape = cardShape,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = color,
            focusedContainerColor = focusedColor,
            pressedContainerColor = focusedColor,
            contentColor = AppColors.TextPrimary,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = scale),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, AppColors.Orange), shape = shape),
        ),
    ) { content() }
}

/** Orangefarbene Schaltfläche (Verlauf). */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    FocusSurface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        scale = 1.05f,
        color = if (primary) Color.Transparent else AppColors.Surface,
        focusedColor = Color.Transparent,
        shape = RoundedCornerShape(28.dp),
    ) {
        val bg = if (primary || focused) Modifier.background(AppColors.GradientHorizontal) else Modifier
        Row(
            modifier = bg.padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = Color.White)
                Spacer(Modifier.width(10.dp))
            }
            Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 40.dp, top = 12.dp, bottom = 4.dp),
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = AppColors.TextPrimary,
    )
}

/** Logo/Cover aus dem Netz, sonst Platzhalter mit Kürzel. */
@Composable
fun RemoteImage(
    url: String?,
    placeholder: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(placeholder, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.Orange.copy(alpha = 0.8f))
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(height))
            .background(Color.White.copy(alpha = 0.18f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height)
                .background(AppColors.GradientHorizontal),
        )
    }
}

/** Drehende Ladeanzeige in Orange. */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "angle",
    )
    Canvas(modifier.size(size)) {
        val stroke = size.toPx() / 10
        drawArc(
            color = AppColors.Orange,
            startAngle = angle,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** Texteingabe, die sich per Fernbedienung bedienen lässt (OK öffnet die Bildschirmtastatur). */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Uri,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, fontSize = 14.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
            cursorBrush = SolidColor(AppColors.Orange),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .clip(RoundedCornerShape(10.dp))
                .background(if (focused) AppColors.SurfaceHigh else AppColors.Surface)
                .border(
                    BorderStroke(if (focused) 3.dp else 1.dp, if (focused) AppColors.Orange else Color.White.copy(alpha = 0.15f)),
                    RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, color = AppColors.TextSecondary.copy(alpha = 0.6f), fontSize = 17.sp, maxLines = 1)
                    inner()
                }
            },
        )
    }
}

/** Kategorie-Eintrag in der linken Spalte. */
@Composable
fun CategoryItem(
    name: String,
    count: Int?,
    selected: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocus() },
        scale = 1.02f,
        color = if (selected) AppColors.Orange.copy(alpha = 0.22f) else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name,
                modifier = Modifier.weight(1f),
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
            if (count != null) {
                Text("$count", fontSize = 12.sp, color = AppColors.TextSecondary, textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = AppColors.TextSecondary, fontSize = 20.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun rememberFavoriteToggle(): (key: String, name: String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { key, name ->
            val now = Repository.toggleFavorite(key)
            Toast.makeText(
                context,
                if (now) "„$name“ zu Favoriten hinzugefügt" else "„$name“ aus Favoriten entfernt",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}

fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun clock(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMANY).format(java.util.Date(ms))

