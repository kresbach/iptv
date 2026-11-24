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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
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
    data object PhoneKeys : Screen()
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
        /** Sendung aus dem Archiv: kein Fortschritt, kein Verlauf. */
        val archive: Boolean = false,
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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
    }

    /** Link vom Google-TV-Startbildschirm („Weiterschauen“). */
    private fun handleLink(intent: android.content.Intent?) {
        // Sprachsuche („Hey Google, suche Tatort in IPTV“)
        if (intent?.action == android.content.Intent.ACTION_SEARCH || intent?.action == "com.google.android.gms.actions.SEARCH_ACTION") {
            intent.getStringExtra(android.app.SearchManager.QUERY)?.takeIf { it.isNotBlank() }?.let { pendingSearch.value = it.trim() }
            return
        }
        val data = intent?.data ?: return
        if (data.scheme == "iptvtv") data.getQueryParameter("key")?.let { pendingOpen.value = it }
    }

    companion object {
        /** Zu öffnender Eintrag (Schlüssel aus „Weiterschauen“). */
        val pendingOpen = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        /** Suchbegriff aus der Sprachsuche. */
        val pendingSearch = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repository.start(applicationContext)
        app.iptv.tv.data.Downloads.start(applicationContext)
        app.iptv.tv.data.Subtitles.cacheDir = applicationContext.filesDir
        app.iptv.tv.data.GoogleSync.start()
        app.iptv.tv.data.Trends.start(applicationContext)
        app.iptv.tv.data.AppUpdate.checkInBackground(applicationContext)
        app.iptv.tv.data.WatchNext.start(applicationContext)
        handleLink(intent)
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

    // Vom Startbildschirm geöffnet: direkt weiterspielen.
    val pending by MainActivity.pendingOpen.collectAsState()
    val restoredState by Repository.restored.collectAsState()
    val loaded = !Repository.content.collectAsState().value.isEmpty
    androidx.compose.runtime.LaunchedEffect(pending, restoredState, loaded) {
        val key = pending ?: return@LaunchedEffect
        if (!restoredState || !loaded) return@LaunchedEffect
        MainActivity.pendingOpen.value = null
        val p = Repository.progress.value[key]
        val ch = (p?.channel ?: Repository.content.value.channel(key))
        nav.home()
        if (ch != null) nav.push(Screen.Player(listOf(ch), 0, p?.position ?: 0, p?.showKey))
    }

    // Erinnerungen: kurz vor Beginn einer vorgemerkten Sendung einblenden.
    var dueReminder by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<app.iptv.tv.data.Reminders.Reminder?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            if (restoredState) dueReminder = app.iptv.tv.data.Reminders.due()
            kotlinx.coroutines.delay(15_000)
        }
    }
    fun switchToReminder(r: app.iptv.tv.data.Reminders.Reminder) {
        app.iptv.tv.data.Reminders.remove(r)
        dueReminder = null
        val live = Repository.visible.value.live
        val i = live.indexOfFirst { it.stableKey == r.channelKey }
        if (i < 0) return
        if (nav.current is Screen.Player) nav.back()
        nav.push(Screen.Player(live, i))
    }

    // Sprachsuche: Suche mit dem gesprochenen Begriff öffnen.
    val searchQuery by MainActivity.pendingSearch.collectAsState()
    androidx.compose.runtime.LaunchedEffect(searchQuery, restoredState) {
        val q = searchQuery ?: return@LaunchedEffect
        if (!restoredState) return@LaunchedEffect
        MainActivity.pendingSearch.value = null
        nav.home()
        Screen.Search.memory.selection = q
        Screen.Search.memory.focusedKey = null
        nav.push(Screen.Search)
    }

    // Erster Start: kurze Einführung in die wichtigsten Tasten.
    var onboarding by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(!app.iptv.tv.data.Prefs.onboardingDone) }
    if (restoredState && onboarding && pending == null) {
        Box(Modifier.fillMaxSize().background(AppColors.Background)) {
            Onboarding(onDone = { app.iptv.tv.data.Prefs.onboardingDone = true; onboarding = false })
        }
        return
    }

    // Wie ein normaler Fernseher: gleich mit dem zuletzt gesehenen Sender starten (Einstellungen).
    var autoStarted by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(restoredState, loaded) {
        if (autoStarted || !restoredState || !loaded) return@LaunchedEffect
        autoStarted = true
        if (!app.iptv.tv.data.Prefs.startLastChannel || pending != null || MainActivity.pendingSearch.value != null) return@LaunchedEffect
        if (nav.current != Screen.Home) return@LaunchedEffect
        val last = Repository.recents.value.firstOrNull { it.channel.kind == app.iptv.tv.model.ContentKind.LIVE }?.channel ?: return@LaunchedEffect
        val live = Repository.visible.value.live
        val i = live.indexOfFirst { it.stableKey == last.stableKey }
        if (i >= 0) nav.push(Screen.Player(live, i))
    }

    Box(
        Modifier.fillMaxSize().background(AppColors.Background).onPreviewKeyEvent { e ->
            // Farbtasten (außerhalb des Players): Rot = Favoriten, Grün = TV-Programm, Gelb = Suche, Blau = Startseite
            if (nav.current !is Screen.Player && dueReminder == null) {
                val target = when (e.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_PROG_RED -> Screen.Live(FAVORITES)
                    android.view.KeyEvent.KEYCODE_PROG_GREEN -> Screen.EpgGrid
                    android.view.KeyEvent.KEYCODE_PROG_YELLOW -> Screen.Search
                    android.view.KeyEvent.KEYCODE_PROG_BLUE -> Screen.Home
                    else -> null
                }
                if (target != null) {
                    if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyUp) {
                        nav.home()
                        if (target != Screen.Home) nav.push(target)
                    }
                    return@onPreviewKeyEvent true
                }
            }
            val r = dueReminder ?: return@onPreviewKeyEvent false
            val code = e.nativeKeyEvent.keyCode
            val ok = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER
            val back = code == android.view.KeyEvent.KEYCODE_BACK || code == android.view.KeyEvent.KEYCODE_ESCAPE
            if (!ok && !back) return@onPreviewKeyEvent false
            if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyUp) {
                if (ok) switchToReminder(r) else { app.iptv.tv.data.Reminders.remove(r); dueReminder = null }
            }
            true
        },
    ) {
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
                Screen.PhoneKeys -> PhoneKeysScreen(nav)
                is Screen.EditPlaylist -> AddPlaylistScreen(nav, editId = screen.id)
                is Screen.CategoryEdit -> CategoryEditScreen(nav, screen)
                is Screen.Live -> LiveScreen(nav, screen)
                is Screen.Grid -> GridScreen(nav, screen)
                is Screen.MovieDetail -> MovieDetailScreen(nav, screen)
                is Screen.SeriesDetail -> SeriesDetailScreen(nav, screen)
                is Screen.Player -> PlayerScreen(nav, screen)
            }
        }
        dueReminder?.let { r -> ReminderBanner(r, Modifier.align(Alignment.TopEnd)) }
    }
}

@Composable
private fun ReminderBanner(r: app.iptv.tv.data.Reminders.Reminder, modifier: Modifier) {
    androidx.compose.foundation.layout.Column(
        modifier.padding(32.dp).width(460.dp)
            .background(AppColors.Surface, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .border(2.dp, AppColors.Orange, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .padding(20.dp),
    ) {
        val started = r.start <= System.currentTimeMillis()
        androidx.tv.material3.Text(if (started) "⏰ Läuft jetzt" else "⏰ Beginnt gleich (${clock(r.start)})", fontSize = 15.sp, color = AppColors.OrangeLight)
        androidx.tv.material3.Text(r.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2)
        androidx.tv.material3.Text("auf ${r.channelName}", fontSize = 16.sp, color = AppColors.TextSecondary)
        androidx.tv.material3.Text("OK = einschalten  ·  Zurück = ausblenden", fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
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
