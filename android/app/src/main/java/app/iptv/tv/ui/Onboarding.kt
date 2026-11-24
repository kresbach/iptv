package app.iptv.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

private class OnboardingPage(val icon: String, val title: String, val lines: List<Pair<String, String>>)

private val pages = listOf(
    OnboardingPage(
        "📺", "Live TV",
        listOf(
            "▲ ▼" to "Sender wechseln",
            "◀" to "Senderliste einblenden",
            "0–9" to "Sendernummer direkt eintippen",
            "OK" to "Was läuft gerade? (Sendungsinfo)",
        ),
    ),
    OnboardingPage(
        "⚙", "Menü & Favoriten",
        listOf(
            "OK lange drücken" to "im Player: Tonspur, Untertitel, Bildformat, Sendung von Anfang an …",
            "OK lange drücken" to "in Listen: als Favorit merken",
            "Zurück" to "eine Ebene zurück",
            "Farbtasten" to "Rot Favoriten · Grün TV-Programm · Gelb Tonspur · Blau Untertitel",
        ),
    ),
    OnboardingPage(
        "⚽", "Tipps",
        listOf(
            "Startseite" to "„Sport jetzt live“ – laufende Spiele mit einem Klick",
            "Senderliste" to "kurz auf einem Sender stehen bleiben = Vorschau",
            "Mikrofon-Taste" to "„Suche Tatort in IPTV“",
            "Einstellungen" to "Kindersicherung, Puffer, 50-Hz-Bild",
        ),
    ),
)

/** Kurze Einführung beim ersten Start: drei Seiten mit den wichtigsten Tasten. */
@Composable
fun Onboarding(onDone: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    BackHandler(enabled = page > 0) { page-- }
    LaunchedEffect(page) {
        kotlinx.coroutines.android.awaitFrame()
        runCatching { focus.requestFocus() }
    }
    val p = pages[page]
    Column(
        Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(p.icon, fontSize = 40.sp)
        Spacer(Modifier.height(4.dp))
        Text(p.title, fontSize = 32.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(18.dp))
        Column(Modifier.widthIn(max = 720.dp)) {
            p.lines.forEach { (key, text) ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.width(190.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Text(
                            key, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppColors.Orange,
                            modifier = Modifier.background(AppColors.Surface, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                    Spacer(Modifier.width(20.dp))
                    Text(text, fontSize = 18.sp)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            pages.indices.forEach { i ->
                Box(Modifier.size(10.dp).background(if (i == page) AppColors.Orange else AppColors.Surface, CircleShape))
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val last = page == pages.lastIndex
            AppButton(
                if (last) "Los geht's" else "Weiter",
                { if (last) onDone() else page++ },
                modifier = Modifier.focusRequester(focus),
            )
            if (!last) AppButton("Überspringen", onDone, primary = false)
        }
        Spacer(Modifier.height(12.dp))
        Text("Die Einführung erscheint nur einmal.", fontSize = 14.sp, color = AppColors.TextSecondary, textAlign = TextAlign.Center)
    }
}
