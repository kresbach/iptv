package app.iptv.tv.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iptv.tv.data.Repository
import app.iptv.tv.model.Channel
import app.iptv.tv.model.ContentKind
import app.iptv.tv.model.SeriesShow

/** Bildschirme der App. Jede Instanz merkt sich Fokus und Scrollposition. */
sealed class Screen {
    val memory = ScreenMemory()
    open val key: String get() = javaClass.simpleName

    data object Home : Screen()
    data object Playlists : Screen()
    data object AddPlaylist : Screen()
    data object Search : Screen()
    data object Settings : Screen()
    data object Downloads : Screen()
    data object EpgGrid : Screen()
    data object Parental : Screen()
    data object GoogleDrive : Screen()
    data object SubtitleSettings : Screen()
    data object Unlock : Screen()
    data object TrendsSettings : Screen()
    data object FavoriteOrder : Screen()
    data class EditPlaylist(val id: String) : Screen() {
        override val key get() = "Edit-$id"
    }
    data class CategoryEdit(val kind: ContentKind = ContentKind.LIVE) : Screen() {
        override val key get() = "Cat-$kind"
    }
    data class Live(val startCategory: String? = null) : Screen()
    data class Grid(val kind: ContentKind) : Screen() {
        override val key get() = "Grid-$kind"
    }
    data class MovieDetail(val movie: Channel) : Screen() {
        override val key get() = "Movie-${movie.stableKey}"
    }
    data class SeriesDetail(val show: SeriesShow) : Screen() {
        override val key get() = "Series-${show.favoriteKey}"
    }

    /** Player: `items` ist die Liste für Hoch/Runter (Sender einer Kategorie bzw. Episoden). */
    data class Player(
        val items: List<Channel>,
        val index: Int,
        val startPosition: Long = 0,
        val showKey: String? = null,
    ) : Screen()
}

class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()

    fun push(screen: Screen) {
        stack.add(screen)
    }

    /** Ersetzt den obersten Bildschirm (z. B. nach „Playlist hinzufügen“). */
    fun replace(screen: Screen) {
        stack.removeAt(stack.lastIndex)
        stack.add(screen)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun home() {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

class MainActivity : ComponentActivity() {
    override fun onStop() {
        super.onStop()
        // Gesperrte Kategorien wieder sperren, sobald die App verlassen wird.
        app.iptv.tv.data.CategoryPrefs.lock()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repository.start(applicationContext)
        app.iptv.tv.data.Downloads.start(applicationContext)
        app.iptv.tv.data.Subtitles.cacheDir = applicationContext.filesDir
        app.iptv.tv.data.GoogleSync.start()
        app.iptv.tv.data.Trends.start(applicationContext)
        setContent {
            AppTheme {
                AppRoot(onExit = { finish() })
            }
        }
    }
}

@Composable
fun AppRoot(onExit: () -> Unit) {
    val nav = remember { Navigator() }
    val holder = rememberSaveableStateHolder()
    BackHandler { if (!nav.back()) onExit() }

    val context = androidx.compose.ui.platform.LocalContext.current
    var crash by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(
            app.iptv.tv.IptvApp.crashFile(context).takeIf { it.exists() }?.let { f -> runCatching { f.readText() }.getOrNull().also { f.delete() } },
        )
    }
    crash?.let { text ->
        CrashReport(text) { crash = null }
        return
    }

    Box(Modifier.fillMaxSize().background(AppColors.Background)) {
        val screen = nav.current
        app.iptv.tv.IptvApp.lastScreen = screen.javaClass.simpleName +
            if (screen is Screen.Player) " (" + screen.items.getOrNull(screen.index)?.kind?.name?.lowercase() + ")" else ""
        holder.SaveableStateProvider(screen.key + System.identityHashCode(screen)) {
            when (screen) {
                Screen.Home -> HomeScreen(nav, screen.memory)
                Screen.Playlists -> PlaylistsScreen(nav, screen.memory)
                Screen.AddPlaylist -> AddPlaylistScreen(nav)
                Screen.Search -> SearchScreen(nav, Screen.Search)
                Screen.Settings -> SettingsScreen(nav, screen.memory)
                Screen.Downloads -> DownloadsScreen(nav, screen.memory)
                Screen.EpgGrid -> EpgGridScreen(nav, screen.memory)
                Screen.Parental -> ParentalScreen(nav)
                Screen.GoogleDrive -> GoogleDriveScreen(nav)
                Screen.SubtitleSettings -> SubtitleSettingsScreen(nav)
                Screen.Unlock -> UnlockScreen(nav)
                Screen.TrendsSettings -> TrendsSettingsScreen(nav)
                Screen.FavoriteOrder -> FavoriteOrderScreen(screen.memory)
                is Screen.EditPlaylist -> AddPlaylistScreen(nav, editId = screen.id)
                is Screen.CategoryEdit -> CategoryEditScreen(nav, screen)
                is Screen.Live -> LiveScreen(nav, screen)
                is Screen.Grid -> GridScreen(nav, screen)
                is Screen.MovieDetail -> MovieDetailScreen(nav, screen)
                is Screen.SeriesDetail -> SeriesDetailScreen(nav, screen)
                is Screen.Player -> PlayerScreen(nav, screen)
            }
        }
    }
}

/** Zeigt nach einem Absturz den Grund an (zum Abfotografieren und Weitergeben). */
@Composable
private fun CrashReport(text: String, onClose: () -> Unit) {
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxSize().background(AppColors.Background).padding(56.dp),
    ) {
        androidx.tv.material3.Text("Die App ist beim letzten Mal abgestürzt", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        androidx.tv.material3.Text(
            "Bitte ein Foto von diesem Bildschirm machen und weitergeben – damit lässt sich der Fehler beheben.",
            fontSize = 18.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        androidx.tv.material3.Text(text, fontSize = 15.sp, color = AppColors.OrangeLight, modifier = Modifier.weight(1f))
        val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
        AppButton("Weiter", onClose, modifier = Modifier.focusRequester(focus))
        androidx.compose.runtime.LaunchedEffect(Unit) {
            kotlinx.coroutines.android.awaitFrame()
            runCatching { focus.requestFocus() }
        }
    }
}
