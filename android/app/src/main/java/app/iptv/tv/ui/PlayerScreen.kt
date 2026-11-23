package app.iptv.tv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.iptv.tv.data.Repository
import app.iptv.tv.model.ContentKind
import app.iptv.tv.player.PlaybackEngine
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Vollbild-Player.
 * Live: Hoch/Runter = Sender wechseln, OK = Info, Links = Senderliste, Zurück = schließen.
 * Filme/Serien: OK = Pause/Steuerung, Links/Rechts = spulen.
 */
@Composable
fun PlayerScreen(nav: Navigator, screen: Screen.Player) {
    val context = LocalContext.current
    val engine = remember { PlaybackEngine(context.applicationContext) }
    val status by engine.status.collectAsState()
    val epg by Repository.epg.collectAsState()
    val favorites by Repository.favorites.collectAsState()
    val toggleFavorite = rememberFavoriteToggle()
    val now = rememberNow()

    val items = screen.items
    /** Angezeigter Sender (wechselt sofort beim Zappen). */
    // Nach der Rückkehr aus dem Archiv denselben Sender wieder zeigen.
    var index by remember { mutableIntStateOf((screen.memory.selection?.toIntOrNull() ?: screen.index).coerceIn(0, items.lastIndex)) }
    /** Tatsächlich gestarteter Sender (nach kurzer Pause, damit schnelles Zappen nicht jedes Mal verbindet). */
    var playingIndex by remember { mutableIntStateOf(-1) }
    var startPosition by remember { mutableLongStateOf(screen.startPosition) }
    var showInfo by remember { mutableStateOf(true) }
    var showList by remember { mutableStateOf(false) }
    var infoTick by remember { mutableIntStateOf(0) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var stopped by remember { mutableStateOf(false) }
    /** Optionsmenü (OK lange drücken): null = zu, sonst Seite. */
    var options by remember { mutableStateOf<String?>(null) }
    var okLong by remember { mutableStateOf(false) }
    /** Eingetippte Sendernummer (Zifferntasten der Fernbedienung). */
    var digits by remember { mutableStateOf("") }
    /** Zuletzt gesehener Sender (Taste „Letzter Sender“ bzw. im Menü). */
    var previousIndex by remember { mutableIntStateOf(-1) }

    val channel = items[index]
    val isLive = channel.kind == ContentKind.LIVE
    val focus = remember { FocusRequester() }

    fun saveProgress() {
        if (playingIndex < 0) return
        val ch = items[playingIndex]
        if (ch.kind != ContentKind.LIVE && !screen.archive) Repository.saveProgress(ch, engine.position, engine.duration, screen.showKey ?: Repository.showKeyFor(ch))
    }

    LaunchedEffect(index) { screen.memory.selection = index.toString() }

    // Sleep-Timer: eine Minute vorher warnen, dann Wiedergabe beenden.
    val sleepEnd by app.iptv.tv.data.SleepTimer.endsAt.collectAsState()
    var sleepLeft by remember { mutableLongStateOf(Long.MAX_VALUE) }
    LaunchedEffect(sleepEnd) {
        val end = sleepEnd ?: run { sleepLeft = Long.MAX_VALUE; return@LaunchedEffect }
        while (true) {
            sleepLeft = end - System.currentTimeMillis()
            if (sleepLeft <= 0) {
                app.iptv.tv.data.SleepTimer.endsAt.value = null
                saveProgress()
                engine.stop()
                nav.home()
                return@LaunchedEffect
            }
            delay(1_000)
        }
    }
    val sleepWarning = sleepEnd != null && sleepLeft <= 60_000

    fun zap(delta: Int) {
        if (items.size < 2) return
        index = (index + delta + items.size) % items.size
        showInfo = true
        infoTick++
    }

    // Wiedergabe starten (beim Zappen mit 450 ms Verzögerung).
    LaunchedEffect(index, stopped) {
        if (stopped) return@LaunchedEffect
        if (playingIndex >= 0 && playingIndex != index) delay(450)
        if (playingIndex == index && engine.status.value.let { !it.failed }) return@LaunchedEffect
        saveProgress()
        val ch = items[index]
        val start = if (playingIndex < 0) startPosition else Repository.progress.value[ch.stableKey]?.position ?: 0
        if (playingIndex >= 0 && playingIndex != index) previousIndex = playingIndex
        playingIndex = index
        // Fernseher schon vor dem Start auf die bekannte Bildrate des Senders stellen (meist 50 Hz).
        if (ch.kind == ContentKind.LIVE && app.iptv.tv.data.Prefs.autoFrameRate) {
            app.iptv.tv.player.DisplayRate.init(context)
            app.iptv.tv.player.DisplayRate.remembered(ch.stableKey)?.let {
                app.iptv.tv.player.DisplayRate.apply(app.iptv.tv.player.DisplayRate.findActivity(context), it)
            }
        }
        engine.preferredBackend = if (ch.kind == ContentKind.LIVE) app.iptv.tv.data.Prefs.playerLive else app.iptv.tv.data.Prefs.playerVod
        val local = app.iptv.tv.data.Downloads.localFile(ch)
        engine.play(ch, start, local, if (ch.kind != ContentKind.LIVE) app.iptv.tv.data.Subtitles.saved(ch) else null)
        // Offline-Wiedergabe belegt keine Verbindung – Downloads dürfen weiterlaufen.
        Repository.playbackActive = local == null
        if (!screen.archive) Repository.addRecent(ch, screen.showKey ?: Repository.showKeyFor(ch))
    }

    // Sendernummer: 1,5 s nach der letzten Ziffer umschalten.
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1_500)
        val n = digits.toIntOrNull()
        digits = ""
        if (n != null && n in 1..items.size && n - 1 != index) { index = n - 1; showInfo = true; infoTick++ }
    }

    // Laufzeit für Filme aktualisieren und regelmäßig den Fortschritt speichern.
    LaunchedEffect(Unit) {
        var ticks = 0
        while (true) {
            delay(500)
            // Live: keine Laufzeit nötig – spart eine Neuberechnung des Bildschirms alle 0,5 s.
            if (items[index].kind != ContentKind.LIVE) {
                position = engine.position
                duration = engine.duration
            }
            if (++ticks % 20 == 0) saveProgress()
        }
    }

    // Info nach 5 s ausblenden.
    LaunchedEffect(showInfo, infoTick, status.playing) {
        if (showInfo && status.playing && !status.buffering) {
            delay(if (isLive) 5_000 else 4_000)
            showInfo = false
        }
    }

    // Folge zu Ende → nächste Episode.
    LaunchedEffect(status.ended) {
        if (status.ended) {
            saveProgress()
            if (!isLive && !screen.archive) app.iptv.tv.data.Downloads.watched(items[index])
            if (!isLive && index < items.lastIndex) {
                Repository.saveProgress(items[index], 0, 1)
                index++
            } else {
                nav.back()
            }
        }
    }

    // Bildwiederholrate des Fernsehers an das Video anpassen (gegen Ruckeln, z. B. 50 Hz für deutsches TV).
    val activity = app.iptv.tv.player.DisplayRate.findActivity(context)
    // Nur die Bildrate beobachten (nicht jede Zähleränderung der Wiedergabe-Info → weniger Neuberechnungen).
    val videoFps by remember { engine.videoInfo.map { it.fps }.distinctUntilChanged() }.collectAsState(0f)
    LaunchedEffect(videoFps) {
        if (videoFps > 0f && playingIndex >= 0 && items[playingIndex].kind == ContentKind.LIVE) {
            app.iptv.tv.player.DisplayRate.init(context)
            app.iptv.tv.player.DisplayRate.remember(items[playingIndex].stableKey, videoFps)
        }
        if (videoFps > 0f && app.iptv.tv.data.Prefs.autoFrameRate) {
            app.iptv.tv.player.DisplayRate.apply(activity, videoFps)
            // Nach dem Umschalten der Bildwiederholrate das Bild neu einpassen (VLC).
            delay(800)
            engine.refreshVideoLayout()
            delay(2_000)
            engine.refreshVideoLayout()
        }
    }
    DisposableEffect(Unit) { onDispose { app.iptv.tv.player.DisplayRate.reset(activity) } }

    // Beim Verlassen und wenn der Fernseher in den Standby/Home geht: Verbindung freigeben.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        Repository.playbackActive = true
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    saveProgress()
                    startPosition = engine.position
                    engine.stop()
                    playingIndex = -1
                    stopped = true
                    // Verbindung ist frei: Downloads und Aktualisierung dürfen weiterlaufen.
                    Repository.playbackActive = false
                    app.iptv.tv.data.Downloads.resume()
                }
                Lifecycle.Event.ON_START -> if (stopped) {
                    startPosition = Repository.progress.value[items[index].stableKey]?.position ?: 0
                    stopped = false
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            saveProgress()
            engine.release()
            Repository.playbackActive = false
            app.iptv.tv.data.Downloads.resume()
        }
    }

    /** Schließen: in der Senderliste darunter den zuletzt gesehenen Sender fokussieren. */
    fun close() {
        val prev = nav.stack.getOrNull(nav.stack.size - 2)
        if (prev is Screen.Live && isLive) {
            prev.memory.focusedKey = "ch-${items[index].stableKey}"
            prev.memory.scroll["channels"] = (index - 3).coerceAtLeast(0) to 0
            prev.memory.restored = false
        }
        nav.back()
    }

    BackHandler {
        when {
            options != null -> options = if (options == "main") null else "main"
            showList -> showList = false
            showInfo && !isLive && status.playing -> showInfo = false
            else -> close()
        }
    }

    // Sicherheitsnetz: kommt das Loslassen von OK nie an, Zustand nach kurzer Zeit zurücksetzen.
    LaunchedEffect(okLong) {
        if (okLong) {
            delay(1_500)
            okLong = false
        }
    }

    // Nach „Verbindung prüfen“: beim Schließen des Menüs automatisch weiterspielen.
    LaunchedEffect(options) {
        if (options == null && engine.isSuspended) engine.retryNow()
    }

    LaunchedEffect(showList, options) {
        if (!showList && options == null) {
            awaitFrame()
            runCatching { focus.requestFocus() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent { event ->
                val code = event.key.nativeKeyCode
                val isOk = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                // Langes OK: Tastenwiederholungen und das Loslassen schlucken, damit im Menü nichts ungewollt ausgelöst wird.
                if (isOk && okLong) {
                    if (event.type == KeyEventType.KeyUp) okLong = false
                    return@onKeyEvent true
                }
                if (showList || options != null) return@onKeyEvent false
                // OK lange drücken → Optionen; kurzes OK wird beim Loslassen ausgewertet.
                if (isOk && event.type == KeyEventType.KeyDown) {
                    if (event.nativeKeyEvent.repeatCount > 0 && !okLong) {
                        okLong = true
                        options = "main"
                    }
                    return@onKeyEvent true
                }
                if (isOk && event.type == KeyEventType.KeyUp) {
                    when {
                        sleepWarning -> app.iptv.tv.data.SleepTimer.extend(30)
                        status.failed -> engine.retryNow()
                        isLive -> { showInfo = !showInfo; infoTick++ }
                        showInfo -> { engine.togglePause(); infoTick++ }
                        else -> { showInfo = true; infoTick++ }
                    }
                    return@onKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (isLive) zap(-1) else { showInfo = true; infoTick++ }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (isLive) zap(1) else options = "main"
                        true
                    }
                    KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); true }
                    KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (isLive) showList = true else { engine.seekBy(-10_000); showInfo = true; infoTick++ }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (!isLive) { engine.seekBy(30_000); showInfo = true; infoTick++ } else { showInfo = true; infoTick++ }
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { engine.seekBy(-30_000); showInfo = true; infoTick++; true }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { engine.seekBy(60_000); showInfo = true; infoTick++; true }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        engine.togglePause(); showInfo = true; infoTick++; true
                    }
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { options = "main"; true }
                    KeyEvent.KEYCODE_INFO -> { showInfo = !showInfo; infoTick++; true }
                    KeyEvent.KEYCODE_CAPTIONS -> { options = "text"; true }
                    KeyEvent.KEYCODE_LAST_CHANNEL -> {
                        if (isLive && previousIndex in items.indices) { index = previousIndex; showInfo = true; infoTick++ }
                        true
                    }
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9, in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                        if (isLive) {
                            val d = if (code >= KeyEvent.KEYCODE_NUMPAD_0) code - KeyEvent.KEYCODE_NUMPAD_0 else code - KeyEvent.KEYCODE_0
                            digits = (digits + d).takeLast(4)
                            if (digits.length >= items.size.toString().length) {
                                // Nummer vollständig → sofort umschalten
                                val n = digits.toInt(); digits = ""
                                if (n in 1..items.size && n - 1 != index) { index = n - 1; showInfo = true; infoTick++ }
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
            // Solange Senderliste oder Menü offen sind, darf der Fokus nicht „hinter“ sie springen.
            .focusable(enabled = okLong || (!showList && options == null)),
    ) {
        AndroidView(factory = { engine.container }, modifier = Modifier.fillMaxSize())

        if (sleepWarning) {
            Column(
                Modifier.align(Alignment.TopCenter).padding(top = 40.dp)
                    .clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.8f)).padding(horizontal = 28.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Sleep-Timer: Wiedergabe endet in ${(sleepLeft / 1000).coerceAtLeast(0)} s", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("OK = 30 Minuten verlängern", fontSize = 16.sp, color = AppColors.OrangeLight)
            }
        }

        if (digits.isNotEmpty()) {
            Text(
                digits,
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.Orange,
                modifier = Modifier.align(Alignment.TopEnd).padding(40.dp)
                    .clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }

        // Laden / Wiederverbinden / Fehler
        if (status.buffering || status.message != null) {
            Column(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (status.buffering && !status.failed) Spinner()
                status.message?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, fontSize = 18.sp, textAlign = TextAlign.Center, color = Color.White)
                }
            }
        }

        // Info-Leiste unten
        AnimatedVisibility(
            visible = showInfo && !showList,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))))
                    .padding(start = 56.dp, end = 56.dp, top = 80.dp, bottom = 40.dp),
            ) {
                if (isLive) {
                    val current = epg?.current(channel, now)
                    val next = epg?.next(channel, now)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(width = 150.dp, height = 90.dp).clip(RoundedCornerShape(10.dp)).background(AppColors.Surface)) {
                            RemoteImage(channel.logo, channel.initials, Modifier.fillMaxSize().padding(10.dp))
                        }
                        Spacer(Modifier.width(28.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${index + 1}", fontSize = 22.sp, color = AppColors.Orange, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(14.dp))
                                Text(channel.name, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (channel.stableKey in favorites) {
                                    Spacer(Modifier.width(10.dp))
                                    Icon(AppIcons.Star, null, tint = AppColors.Orange, modifier = Modifier.size(22.dp))
                                }
                            }
                            if (current != null) {
                                Text(
                                    "${clock(current.start)} – ${clock(current.end)}   ${current.title}",
                                    fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                                ProgressLine(current.progress(now), Modifier.fillMaxWidth(0.7f).padding(top = 8.dp))
                                current.description?.let {
                                    Text(it, fontSize = 15.sp, color = AppColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                                }
                            } else {
                                Text("Keine Programminformationen", fontSize = 17.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 6.dp))
                            }
                            next?.let {
                                Text("Danach: ${clock(it.start)}  ${it.title}", fontSize = 16.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
                            }
                            Text(
                                "▲▼ Sender wechseln   0–9 Sendernummer   ◀ Senderliste   OK Info   OK lange = Optionen   Zurück = Schließen",
                                fontSize = 13.sp, color = AppColors.TextSecondary.copy(alpha = 0.8f), modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                } else {
                    Column {
                        Text(channel.name, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (status.playing) AppIcons.Pause else AppIcons.Play, null, tint = Color.White, modifier = Modifier.size(34.dp))
                            Spacer(Modifier.width(18.dp))
                            Text(formatTime(position), fontSize = 18.sp)
                            Spacer(Modifier.width(16.dp))
                            ProgressLine(if (duration > 0) position.toFloat() / duration else 0f, Modifier.weight(1f), 6.dp)
                            Spacer(Modifier.width(16.dp))
                            Text(if (duration > 0) formatTime(duration) else "--:--", fontSize = 18.sp)
                        }
                        Text(
                            "OK Pause/Weiter   ◀ 10 s zurück   ▶ 30 s vor   ▼ Optionen (Ton, Untertitel)   Zurück = Schließen",
                            fontSize = 13.sp, color = AppColors.TextSecondary.copy(alpha = 0.8f), modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }

        // Optionen rechts
        AnimatedVisibility(
            visible = options != null,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            PlayerOptions(
                page = options ?: "main",
                focusReady = !okLong,
                channel = channel,
                engine = engine,
                isFavorite = channel.stableKey in favorites,
                onPage = { options = it },
                onClose = { options = null },
                onToggleFavorite = { toggleFavorite(channel.stableKey, channel.name) },
                previousChannel = items.getOrNull(previousIndex)?.takeIf { previousIndex != index },
                onPreviousChannel = { options = null; index = previousIndex; showInfo = true; infoTick++ },
                onArchive = { p ->
                    options = null
                    app.iptv.tv.data.Catchup.channel(channel, p)?.let { nav.push(Screen.Player(listOf(it), 0, archive = true)) }
                },
                onEngineChange = { choice ->
                    if (isLive) app.iptv.tv.data.Prefs.playerLive = choice else app.iptv.tv.data.Prefs.playerVod = choice
                    app.iptv.tv.data.GoogleSync.scheduleSync()
                    saveProgress()
                    val pos = if (isLive) 0L else engine.position
                    engine.preferredBackend = choice
                    engine.play(channel, pos, app.iptv.tv.data.Downloads.localFile(channel), if (isLive) null else app.iptv.tv.data.Subtitles.saved(channel))
                    options = null
                },
            )
        }

        // Senderliste links
        AnimatedVisibility(
            visible = showList,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
        ) {
            val listFocus = remember { FocusRequester() }
            val listState = rememberLazyListState((index - 3).coerceAtLeast(0))
            Column(
                Modifier
                    .width(520.dp)
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.95f), Color.Black.copy(alpha = 0.75f))))
                    .padding(top = 28.dp),
            ) {
                Text("Sender", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 28.dp, bottom = 12.dp))
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(items, key = { i, c -> "$i|${c.url}" }) { i, ch ->
                        ChannelRow(
                            number = i + 1,
                            channel = ch,
                            programme = epg?.current(ch, now),
                            now = now,
                            onClick = {
                                index = i
                                showList = false
                                showInfo = true
                                infoTick++
                            },
                            onLongClick = { toggleFavorite(ch.stableKey, ch.name) },
                            isFavorite = ch.stableKey in favorites,
                            isPlaying = i == index,
                            modifier = if (i == index) Modifier.focusRequester(listFocus) else Modifier,
                        )
                    }
                }
            }
            LaunchedEffect(Unit) {
                awaitFrame()
                runCatching { listFocus.requestFocus() }
            }
        }
    }
}
