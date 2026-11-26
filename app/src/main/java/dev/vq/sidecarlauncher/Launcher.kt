package dev.vq.sidecarlauncher

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

private const val PAUSED_HIDE_MS = 5_000L
/** How long a double-tapped player stays up with nothing playing. */
private const val PLAYER_SHOWN_MS = 20_000L

private fun Int.sp() = androidx.compose.ui.unit.TextUnit(toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

/** Runs [block] every [periodMs] while the launcher is on screen (battery: nothing runs in the background). */
@Composable
private fun WhileResumed(vararg keys: Any?, periodMs: Long, block: suspend () -> Unit) {
    LaunchedEffect(*keys) {
        while (true) {
            AppState.resumed.first { it }
            block()
            delay(periodMs)
        }
    }
}

@Composable
fun LauncherScreen(nowPlaying: NowPlayingSource, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val cfg by Settings.state.collectAsState()

    // Minute clock: the screen recomposes at most once a minute.
    val now by produceState(LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)) {
        while (true) {
            value = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)
            delay(60_000 - System.currentTimeMillis() % 60_000)
        }
    }

    var agenda by remember { mutableStateOf(emptyList<CalEvent>()) }
    WhileResumed(cfg.hiddenCalendars, periodMs = 5 * 60_000L) { agenda = AgendaRepo.load(ctx, cfg.hiddenCalendars) }
    var weather by remember { mutableStateOf<Weather?>(null) }
    WhileResumed(cfg.city, periodMs = 15 * 60_000L) { WeatherRepo.load(ctx, cfg.city)?.let { weather = it } }
    val home by GoogleHome.ui.collectAsState()
    WhileResumed(home.signedIn, "home" in cfg.tabs, cfg.homeProvider, periodMs = 30_000L) { if (home.signedIn && "home" in cfg.tabs && cfg.homeProvider == "google") GoogleHome.refresh() }
    WhileResumed(cfg.haUrl, cfg.haToken, "home" in cfg.tabs, cfg.homeProvider, periodMs = 30_000L) { if ("home" in cfg.tabs && cfg.homeProvider == "ha") HomeAssistant.refresh(cfg.haUrl, cfg.haToken) }
    WhileResumed(cfg.billableUrl, cfg.billableToken, periodMs = 5 * 60_000L) { Billable.refresh(cfg.billableUrl, cfg.billableToken) }

    // Player visibility: playing, or paused for less than 5 s (2 s tick only while something is paused).
    val track by nowPlaying.state.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(track?.playing, track?.pausedAtMs) {
        while (track != null && track?.playing == false && System.currentTimeMillis() - (track?.pausedAtMs ?: 0) < PAUSED_HIDE_MS + 2_000) {
            delay(2_000); tick++
        }
    }
    val visibleTrack = remember(track, tick) {
        track?.takeIf { it.playing || System.currentTimeMillis() - it.pausedAtMs < PAUSED_HIDE_MS }
    }
    var lastTouch by remember { mutableLongStateOf(0L) }
    // Double tap on the widgets shows/hides the player even when nothing is playing (last session).
    var playerShown by remember { mutableStateOf(false) }
    val shownTrack = visibleTrack ?: track?.takeIf { playerShown }
    var lastShown by remember { mutableStateOf<NowPlaying?>(null) }
    if (shownTrack != null) lastShown = shownTrack
    // Shown by double tap: once music plays the normal rules take over; with nothing playing it
    // goes away by itself after PLAYER_SHOWN_MS (the double tap is a peek, not a pin).
    LaunchedEffect(playerShown, track?.playing, lastTouch) {
        if (!playerShown) return@LaunchedEffect
        if (track?.playing == true) { playerShown = false; return@LaunchedEffect }
        delay(PLAYER_SHOWN_MS)
        playerShown = false
    }
    // Double tap with no media session at all: an empty player that opens the last music app.
    val showEmpty = playerShown && track == null
    var emptyMode by remember { mutableStateOf(false) }
    if (shownTrack != null) emptyMode = false else if (showEmpty) emptyMode = true
    var fullscreenPhotos by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    val pcPhotos by PcLink.photosVersion.collectAsState()
    WhileResumed(cfg.photoSource, cfg.pcHost, cfg.pcToken, pcPhotos == 0, periodMs = 10 * 60_000L) { if (cfg.photosFromPc) PcLink.syncPhotos(ctx) }

    Box(Modifier.fillMaxSize().background(if (cfg.background == "surface") p.surface else Color.Black)) {
    if (cfg.background == "image") WallpaperImage()
    Column(
        Modifier.fillMaxSize().onPinchIn(onSettings).onSwipeDown { Intents.notificationShade(ctx) }.stableBarsPadding(cfg).imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // With the system status bar hidden, keep just the battery (top-right), no clock / notification icons.
        if (cfg.statusBar == "hidden") StatusLine(cfg.statusNotifs, cfg.statusStyle == "mono", cfg.statusPill, onTap = { Intents.notificationShade(ctx) })
        // No widget tabs: the clock moves to the top, horizontal like desk_sidecar.
        val topless = cfg.tabs.isEmpty()
        // Clock on top only without widget tabs, and only if the clock page is on (it can be removed altogether).
        val topClock = topless && "clock" in cfg.clockPages
        val togglePlayer = { playerShown = !(playerShown || visibleTrack != null) }
        // ---- Top half: widgets (+ player when something plays) --------------------------------------
        // While searching apps the widgets smoothly fold away (weight → 0 + fade), so results fill the
        // space above the keyboard without a jump.
        val topWeight by androidx.compose.animation.core.animateFloatAsState(
            if (searching) 0.0001f else 1f,
            androidx.compose.animation.core.tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            label = "widgets",
        )
        @Composable
        fun ColumnScope.Player() = AnimatedVisibility(
                shownTrack != null || showEmpty,
                enter = fadeIn(androidx.compose.animation.core.tween(350)) + expandVertically(androidx.compose.animation.core.tween(350)),
                exit = fadeOut(androidx.compose.animation.core.tween(300)) + shrinkVertically(androidx.compose.animation.core.tween(350)),
            ) {
                val t = shownTrack ?: lastShown
                if (emptyMode || t == null) EmptyPlayer(onOpen = { playerShown = false }) else MediaCard(t, nowPlaying, lastTouch)
            }
        // The top half stays the top half (the app list keeps the bottom one, for one-hand use).
        if (!topless || topClock) { if (topWeight > 0.001f) Column(
            Modifier.weight(topWeight).fillMaxWidth().clipToBounds().graphicsLayer { alpha = topWeight },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (topless) {
                // Double tap anywhere above the player (clock, pills or empty space) shows/hides it.
                Column(Modifier.weight(1f).fillMaxWidth().onDoubleTapAnywhere(togglePlayer)) {
                    TopClock(now, weather, cfg.topClockAlign)
                }
                Player()
            } else {
            WidgetCard(
                agenda, weather, onSettings,
                onPhotos = { fullscreenPhotos = true },
                // Double tap anywhere on the widgets, even over lists and buttons, shows/hides the player.
                Modifier.weight(1f).onDoubleTapAnywhere(togglePlayer).pointerInput(Unit) {
                    detectTapGestures(onPress = { lastTouch = System.currentTimeMillis() })
                },
            )
            Player()
            }
        } } else if (!searching) Player()
        // ---- Bottom half: clock pager + app list -------------------------------------------------------
        // With the clock on top the side column keeps only the other pages (billable); none → full-width list.
        val snap by Billable.snapshot.collectAsState()
        val side = cfg.clockPages.any { (it == "clock" && !topless) || (it == "billable" && snap != null) }
        val fullList = !side && cfg.appListWidth == "full" && cfg.showLabels
        if (fullList) Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AppList(Modifier.weight(1f).fillMaxWidth()) { searching = it }
            SearchBar(cfg)
        } else Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (cfg.leftHanded) {
                AppList(Modifier.weight(0.56f).fillMaxHeight()) { searching = it }
                SideColumn(side, now, onSettings, Modifier.weight(0.44f).fillMaxHeight(), withClock = !topless)
            } else {
                SideColumn(side, now, onSettings, Modifier.weight(0.44f).fillMaxHeight(), withClock = !topless)
                AppList(Modifier.weight(0.56f).fillMaxHeight()) { searching = it }
            }
        }
    }
        RenameDialog()
        // Long press on the photo widget: full-screen slideshow (tap to close), like desk_sidecar's frame.
        if (fullscreenPhotos) {
            androidx.activity.compose.BackHandler { fullscreenPhotos = false }
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { fullscreenPhotos = false }) }) {
                PhotoPage(Modifier.fillMaxSize(), fullscreen = true)
                FrameOverlay(now, weather, track?.takeIf { it.playing }, cfg)
            }
        }
    }
}

// ---- Widgets (top) ---------------------------------------------------------------------------------

private val TAB_INFO = mapOf(
    "notifications" to (Sym.NOTIFICATIONS to "Notifiche|Notifications"),
    "agenda" to (Sym.EVENT to "Agenda|Agenda"),
    "home" to (Sym.HOME to "Casa|Home"),
    "weather" to (Sym.SUNNY to "Meteo|Weather"),
    "photos" to (Sym.PHOTO to "Foto|Photos"),
)

@Composable
private fun WidgetCard(agenda: List<CalEvent>, weather: Weather?, onSettings: () -> Unit, onPhotos: () -> Unit, modifier: Modifier) {
    val p = LocalPalette.current
    val cfg by Settings.state.collectAsState()
    val tabs = cfg.tabs
    val ctx = LocalContext.current
    if (tabs.isEmpty()) {
        Box(modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(p.surfaceContainer), contentAlignment = Alignment.Center) {
            Pill(Modifier.clickable(onClick = onSettings)) {
                MIcon(Sym.SETTINGS, 18.dp, p.primary)
                Text(tr("Impostazioni", "Settings"), style = textStyle(14, p.onSurface, 500))
            }
        }
        return
    }
    val pager = rememberPagerState(initialPage = tabs.indexOf("agenda").coerceAtLeast(0)) { tabs.size }
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(p.surfaceContainer)) {
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tabs.forEachIndexed { i, key ->
                val (icon, label) = TAB_INFO.getValue(key)
                val sel = pager.currentPage == i
                val fg = if (sel) p.onPrimaryContainer else p.onSurfaceVariant
                Pill(
                    Modifier.clickable { scope.launch { pager.animateScrollToPage(i) } },
                    background = if (sel) p.primaryContainer else p.surfaceContainerHigh,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    MIcon(icon, 18.dp, fg, filled = sel)
                    val l = if (key == "agenda" && cfg.agendaView == "month") "Calendario|Calendar" else label
                    if (sel) Text(l.split("|").let { if (I18n.en) it.last() else it.first() }, style = textStyle(14, fg, 600))
                }
            }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), key = { tabs.getOrElse(it) { "" } }) { page ->
            val key = tabs.getOrNull(page)
            // Long press on a widget opens the full app behind it.
            val longPress = Modifier.pointerInput(key, cfg.homeProvider, cfg.haUrl) {
                detectTapGestures(
                    onLongPress = { if (key == "photos") onPhotos() else key?.let { Intents.openFor(ctx, it, cfg) } },
                )
            }
            val m = Modifier.fillMaxSize().then(longPress).padding(12.dp)
            when (key) {
                "notifications" -> NotificationsPage(m)
                "agenda" -> if (cfg.agendaView == "month") MonthPage(agenda, Modifier.fillMaxSize().then(longPress).padding(horizontal = 12.dp, vertical = 8.dp))
                    else AgendaPage(agenda, Modifier.fillMaxSize().then(longPress).padding(horizontal = 16.dp, vertical = 12.dp))
                "home" -> HomePage(m)
                "weather" -> WeatherTab(weather, Modifier.fillMaxSize().then(longPress).padding(horizontal = 14.dp, vertical = 12.dp))
                "photos" -> PhotoPage(m)
            }
        }
    }
}

// ---- Clock pager (bottom, rolling) -----------------------------------------------------------------

private val HOUR_FMT = DateTimeFormatter.ofPattern("HH")
private val MIN_FMT = DateTimeFormatter.ofPattern("mm")
private val SHORT_DATE get() = DateTimeFormatter.ofPattern(if (I18n.en) "EEE, MMM d" else "EEE d MMM", I18n.locale)

@Composable
private fun ClockPager(now: LocalDateTime, onSettings: () -> Unit, modifier: Modifier, withClock: Boolean = true) {
    val p = LocalPalette.current
    val cfg by Settings.state.collectAsState()
    val snap by Billable.snapshot.collectAsState()
    val pages = cfg.clockPages.filter { (it != "billable" || snap != null) && (withClock || it != "clock") }
    if (pages.isEmpty()) { Column(modifier, verticalArrangement = Arrangement.Bottom) { SearchBar(cfg) }; return }
    val count = if (pages.size > 1) 10_000 else 1
    val pager = rememberPagerState(initialPage = if (count > 1) 5_000 - 5_000 % pages.size else 0) { count }
    Column(modifier) {
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
            when (pages[page % pages.size]) {
                "clock" -> ClockPage(now, onSettings)
                "billable" -> snap?.let { BillableCard(remember(it, now) { Billable.totals(it) }) }
            }
        }
        if (pages.size > 1) {
            Row(Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pages.indices.forEach { i ->
                    val on = pager.currentPage % pages.size == i
                    Box(Modifier.size(if (on) 8.dp else 6.dp).clip(CircleShape).background(if (on) p.primary else p.outlineVariant))
                }
            }
        }
        if (cfg.searchBar || cfg.cameraButton) {
            Spacer(Modifier.height(10.dp))
            SearchBar(cfg)
        }
    }
}

@Composable
private fun ClockPage(now: LocalDateTime, onSettings: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    // Long press on the clock opens the Clock app.
    Column(Modifier.fillMaxSize().padding(top = 18.dp).pointerInput(Unit) { detectTapGestures(onLongPress = { Intents.clock(ctx) }) }, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        // The rounded clock font has a very tall line box: crop each row to the digits so they sit close.
        Text(now.format(HOUR_FMT), style = textStyle(100, p.onSurface, 800).copy(fontFamily = ClockFont), modifier = Modifier.cropVertical(22.dp, 20.dp))
        Text(now.format(MIN_FMT), style = textStyle(100, p.primary, 800).copy(fontFamily = ClockFont), modifier = Modifier.cropVertical(22.dp, 20.dp))
        Spacer(Modifier.height(22.dp))
        Pill {
            MIcon(Sym.CALENDAR, 18.dp, p.primary)
            Text(now.format(SHORT_DATE).split(" ").joinToString(" ") { s -> s.replaceFirstChar { it.uppercase() } }.replace(".", ""),
                style = textStyle(15, p.onSurface), maxLines = 1)
        }
    }
}

/** Beside the app list: the clock pager, or (clock on top, nothing to page) just Google search + camera at the bottom. */
@Composable
private fun SideColumn(pages: Boolean, now: LocalDateTime, onSettings: () -> Unit, modifier: Modifier, withClock: Boolean) {
    if (pages) ClockPager(now, onSettings, modifier, withClock)
    else Column(modifier, verticalArrangement = Arrangement.Bottom) { SearchBar(Settings.state.collectAsState().value) }
}

/** Horizontal clock + date/weather pills at the top, used when every widget tab is turned off. */
@Composable
private fun TopClock(now: LocalDateTime, weather: Weather?, align: String) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 8.dp)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { Intents.clock(ctx) }) },
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = when (align) { "center" -> Alignment.CenterHorizontally; "end" -> Alignment.End; else -> Alignment.Start },
    ) {
        Row(Modifier.cropVertical(24.dp, 22.dp)) {
            Text(now.format(HOUR_FMT) + ":", style = textStyle(108, p.onSurface, 800).copy(fontFamily = ClockFont), maxLines = 1, softWrap = false)
            Text(now.format(MIN_FMT), style = textStyle(108, p.primary, 800).copy(fontFamily = ClockFont), maxLines = 1, softWrap = false)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill {
                MIcon(Sym.CALENDAR, 18.dp, p.primary)
                Text(now.format(SHORT_DATE).split(" ").joinToString(" ") { s -> s.replaceFirstChar { it.uppercase() } }.replace(".", ""),
                    style = textStyle(15, p.onSurface), maxLines = 1)
            }
            weather?.let { w ->
                val (icon, label) = WeatherRepo.describe(w.code, w.isDay)
                Pill(Modifier.weight(1f, fill = false)) {
                    MIcon(icon, 18.dp, p.primary, filled = true)
                    Text("${w.temperature.roundToInt()}°", style = textStyle(15, p.onSurface, 600), maxLines = 1)
                    Text(label, style = textStyle(15, p.onSurfaceVariant), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun BillableCard(t: Billable.Totals) {
    val p = LocalPalette.current
    val fg = p.onSecondaryContainer
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(p.secondaryContainer).padding(18.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        @Composable
        fun block(label: String, icon: Char, filled: Boolean, sec: Long, amount: Double) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MIcon(icon, 18.dp, p.primary, filled = filled)
                    Text(label, style = textStyle(14, fg, 600))
                }
                Text(Billable.hm(sec), style = textStyle(40, p.primary, 700).copy(fontFamily = ClockFont, lineHeight = 44.sp()))
                if (t.hasRates) Text(Billable.money(amount, t.currency), style = textStyle(14, fg, 500), maxLines = 1)
            }
        }
        block(if (t.running != null) tr("In corso", "Running") else tr("Oggi", "Today"), Sym.TIMER, t.running != null, t.todaySec, t.todayAmount)
        Box(Modifier.fillMaxWidth().height(1.dp).background(fg.copy(alpha = 0.15f)))
        block(tr("Mese", "Month"), Sym.WORK, false, t.monthSec, t.monthAmount)
    }
}

// ---- App list (bottom) -------------------------------------------------------------------------------

private fun iconDp(c: LauncherConfig) = when (c.iconSize) { "large" -> 46.dp; "xl" -> 56.dp; else -> 36.dp }

@Composable
private fun AppList(modifier: Modifier, onSearching: (Boolean) -> Unit) {
    val p = LocalPalette.current
    val cfg by Settings.state.collectAsState()
    val raw by Apps.list.collectAsState()
    // Custom names: shown and sorted by the name you gave them.
    val all = remember(raw, cfg.renamed) {
        if (cfg.renamed.isEmpty()) raw else {
            val col = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
            raw.sortedWith { a, b -> col.compare(cfg.labelOf(a), cfg.labelOf(b)) }
        }
    }
    val byKey = remember(all) { all.associateBy { it.key } }
    var query by remember { mutableStateOf<String?>(null) } // null = search closed
    val q = query?.trim().orEmpty()
    val searching = query != null
    LaunchedEffect(searching) { onSearching(searching) }
    // While searching: every app matching, hidden ones included (shown dimmed).
    val matches = if (searching) all.filter { q.isEmpty() || cfg.labelOf(it).contains(q, ignoreCase = true) || it.label.contains(q, ignoreCase = true) } else emptyList()
    // Optional: one app left → open it (short pause so it doesn't fire mid-word).
    val single = matches.singleOrNull()?.takeIf { cfg.autoOpenSingle && q.isNotEmpty() }
    LaunchedEffect(single?.key, q) {
        if (single != null) { delay(350); Apps.launch(single); query = null }
    }
    val pinned = if (searching) emptyList() else cfg.pinned.mapNotNull { byKey[it] }.filter { it.key !in cfg.hidden }
    val rest = if (searching) matches else all.filter { it.key !in cfg.hidden && it.key !in cfg.pinned }
    val box = modifier.clip(RoundedCornerShape(28.dp)).background(p.surfaceContainerLow)
    if (searching) androidx.activity.compose.BackHandler { query = null }
    Column(box) {
        AppSearch(query, onQuery = { query = it }, onSubmit = { matches.firstOrNull()?.let { Apps.launch(it); query = null } })
        AppListBody(Modifier.weight(1f), cfg, pinned, rest, searching = searching)
    }
}

@Composable
private fun AppSearch(query: String?, onQuery: (String?) -> Unit, onSubmit: () -> Unit) {
    val p = LocalPalette.current
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    Pill(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp).then(if (query == null) Modifier.clickable { onQuery("") } else Modifier),
        background = p.surfaceContainerHigh,
    ) {
        MIcon(Sym.SEARCH, 18.dp, p.primary)
        if (query == null) {
            Text(tr("Cerca app", "Search apps"), style = textStyle(14, p.onSurfaceVariant, 500), maxLines = 1)
        } else {
            androidx.compose.foundation.text.BasicTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                textStyle = textStyle(15, p.onSurface, 500), cursorBrush = androidx.compose.ui.graphics.SolidColor(p.primary),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { onSubmit() }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
            Box(Modifier.size(24.dp).clip(CircleShape).clickable { onQuery(null) }, contentAlignment = Alignment.Center) {
                MIcon(Sym.CLOSE, 16.dp, p.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AppListBody(modifier: Modifier, cfg: LauncherConfig, pinned: List<AppEntry>, rest: List<AppEntry>, searching: Boolean) {
    val p = LocalPalette.current
    val box = modifier
    if (!cfg.showLabels) {
        // Icons only: one centred column (thumb-friendly), pinned first in their own pill.
        LazyColumn(box, contentPadding = PaddingValues(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Full-width rows with the icon centred: the whole row is the touch/swipe target.
            if (pinned.isNotEmpty()) item("pinned") {
                Column(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(22.dp)).background(p.surfaceContainerHigh).padding(vertical = 2.dp)) {
                    pinned.forEach { key(it.key) { AppRow(it, pinned = true, iconOnly = true) } }
                }
            }
            items(rest, key = { it.key }) { AppRow(it, pinned = searching && it.key in cfg.pinned, iconOnly = true, dim = searching && it.key in cfg.hidden, inSearch = searching) }
        }
        return
    }
    LazyColumn(box, contentPadding = PaddingValues(8.dp)) {
        if (pinned.isNotEmpty()) item("pinned") {
            // All pinned apps share one highlighted pill.
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(22.dp)).background(p.surfaceContainerHigh)) {
                pinned.forEach { key(it.key) { AppRow(it, pinned = true) } }
            }
        }
        items(rest, key = { it.key }) { AppRow(it, pinned = searching && it.key in cfg.pinned, dim = searching && it.key in cfg.hidden, inSearch = searching) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(app: AppEntry, pinned: Boolean, iconOnly: Boolean = false, dim: Boolean = false, inSearch: Boolean = false) {
    val p = LocalPalette.current
    val cfg by Settings.state.collectAsState()
    val density = LocalDensity.current
    val iconSize = iconDp(cfg)
    val px = with(density) { iconSize.roundToPx() }
    val threshold = with(density) { 72.dp.toPx() }
    val fg = p.primary.toArgbInt()
    val bg = p.primaryContainer.toArgbInt()
    val withIcon = cfg.showIcons || iconOnly
    val icon by produceState<android.graphics.Bitmap?>(null, app.key, px, cfg.themedIcons, fg, bg, withIcon) { if (withIcon) value = Apps.icon(app, px, cfg.themedIcons, fg, bg) }
    var menu by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxWidth()) {
        // Swipe hints: right = pin/unpin, left = hide
        if (offset.value != 0f) Row(Modifier.matchParentSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (offset.value > 0) MIcon(Sym.PIN, 22.dp, if (offset.value > threshold) p.primary else p.outline, filled = !pinned)
            Spacer(Modifier.weight(1f))
            if (offset.value < 0) MIcon(Sym.HIDE, 22.dp, if (-offset.value > threshold) p.error else p.outline)
        }
        Row(
            Modifier.fillMaxWidth()
                .offset { androidx.compose.ui.unit.IntOffset(offset.value.toInt(), 0) }
                .pointerInput(app.key) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val v = offset.value
                            scope.launch {
                                if (v > threshold) { Settings.togglePin(app.key); haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm) }
                                else if (v < -threshold) { Settings.toggleHidden(app.key); haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm) }
                                offset.animateTo(0f)
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f) } },
                    ) { change, drag ->
                        change.consume()
                        scope.launch { offset.snapTo((offset.value + drag).coerceIn(-threshold * 1.6f, threshold * 1.6f)) }
                    }
                }
                .clip(RoundedCornerShape(18.dp))
                .background(if (offset.value != 0f) p.surfaceContainerLow else Color.Transparent)
                .combinedClickable(onClick = { Apps.launch(app) }, onLongClick = { if (cfg.appLongPress == "menu") menu = true else Apps.info(app) })
                .then(if (dim) Modifier.alpha(0.45f) else Modifier)
                .padding(horizontal = if (iconOnly) 4.dp else if (withIcon) 10.dp else 14.dp, vertical = if (iconOnly) 6.dp else if (withIcon) 7.dp else 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (iconOnly) Arrangement.Center else Arrangement.Start,
        ) {
            if (withIcon) Box(Modifier.size(iconSize)) { icon?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) } }
            if (!iconOnly) {
                if (withIcon) Spacer(Modifier.width(12.dp))
                Text(cfg.labelOf(app), style = textStyle(labelSp(cfg), p.onSurface, if (pinned) 600 else 400), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                // While searching, pinned apps are marked (swipe right toggles it: you see it happen).
                if (inSearch && pinned) MIcon(Sym.PIN, 18.dp, p.primary, filled = true)
            }
        }
        if (menu) AppMenu(app, cfg, onDismiss = { menu = false })
    }
}

private fun labelSp(c: LauncherConfig) = when (c.labelSize) { "small" -> 14; "large" -> 18; "xl" -> 21; else -> if (c.iconSize == "normal" || !c.showIcons) 16 else 17 }

/** Long-press menu on an app (when chosen in settings): rename, pin, hide, info, uninstall. */
@Composable
private fun AppMenu(app: AppEntry, cfg: LauncherConfig, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val removable = remember(app.key) { Apps.removable(app) }
    androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(surfaceContainer = p.surfaceContainerHigh, surface = p.surfaceContainerHigh)) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss, shape = RoundedCornerShape(20.dp), containerColor = p.surfaceContainerHigh) {
        @Composable
        fun entry(icon: Char, label: String, enabled: Boolean = true, action: () -> Unit) = DropdownMenuItem(
            text = { Text(label, style = textStyle(15, if (enabled) p.onSurface else p.outline, 500)) },
            leadingIcon = { MIcon(icon, 20.dp, if (enabled) p.primary else p.outline) },
            enabled = enabled,
            onClick = { onDismiss(); action() },
        )
        Text(cfg.labelOf(app), style = textStyle(13, p.onSurfaceVariant, 600), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).widthIn(max = 220.dp))
        entry(Sym.EDIT, tr("Rinomina", "Rename")) { AppMenuState.renaming.value = app }
        entry(Sym.PIN, if (app.key in cfg.pinned) tr("Togli dai preferiti", "Unpin") else tr("Fissa in cima", "Pin to top")) { Settings.togglePin(app.key) }
        entry(Sym.HIDE, if (app.key in cfg.hidden) tr("Mostra", "Unhide") else tr("Nascondi", "Hide")) { Settings.toggleHidden(app.key) }
        entry(Sym.INFO, tr("Info app", "App info")) { Apps.info(app) }
        entry(Sym.DELETE, tr("Disinstalla", "Uninstall"), enabled = removable) { Apps.uninstall(app) }
    }
    }
}

object AppMenuState {
    val renaming = kotlinx.coroutines.flow.MutableStateFlow<AppEntry?>(null)
}

/** "Rename app" sheet: empty name or "Original" restores the app's own name. */
@Composable
private fun RenameDialog() {
    val p = LocalPalette.current
    val cfg by Settings.state.collectAsState()
    val app = AppMenuState.renaming.collectAsState().value ?: return
    var text by remember(app.key) { mutableStateOf(cfg.labelOf(app)) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    fun close() { AppMenuState.renaming.value = null }
    androidx.activity.compose.BackHandler { close() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).pointerInput(Unit) { detectTapGestures { close() } }.imePadding(),
        contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp).clip(RoundedCornerShape(28.dp)).background(p.surfaceContainerHigh)
                .pointerInput(Unit) { detectTapGestures { } }.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Rinomina app", "Rename app"), style = textStyle(20, p.onSurface, 700))
            Text(tr("Nome originale: ${app.label}", "Original name: ${app.label}"), style = textStyle(13, p.onSurfaceVariant, 500))
            androidx.compose.foundation.text.BasicTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                textStyle = textStyle(16, p.onSurface, 500), cursorBrush = androidx.compose.ui.graphics.SolidColor(p.primary),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { Settings.rename(app.key, text.takeIf { it.trim() != app.label }); close() }),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surfaceContainerHighest).padding(horizontal = 14.dp, vertical = 12.dp).focusRequester(focus),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (app.key in cfg.renamed) Pill(Modifier.clickable { Settings.rename(app.key, null); close() }, background = p.surfaceContainerHighest) {
                    Text(tr("Originale", "Original"), style = textStyle(15, p.onSurface, 600))
                }
                Pill(Modifier.clickable { close() }, background = p.surfaceContainerHighest) { Text(tr("Annulla", "Cancel"), style = textStyle(15, p.onSurface, 600)) }
                Pill(Modifier.clickable { Settings.rename(app.key, text.takeIf { it.trim() != app.label }); close() }, background = p.primary) {
                    Text(tr("Salva", "Save"), style = textStyle(15, p.onPrimary, 600))
                }
            }
        }
    }
}

/**
 * Double tap that works over anything (lists, buttons): watched in the Initial pass without consuming,
 * so children still get their taps. Fires on the second release; moves, long presses and multi-touch don't count.
 */
private fun Modifier.onDoubleTapAnywhere(action: () -> Unit) = pointerInput(Unit) {
    val slop = viewConfiguration.touchSlop
    val timeout = viewConfiguration.doubleTapTimeoutMillis
    val longPress = viewConfiguration.longPressTimeoutMillis
    var lastUp = 0L
    var lastPos = androidx.compose.ui.geometry.Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
        val second = lastUp != 0L && down.uptimeMillis - lastUp <= timeout && (down.position - lastPos).getDistance() < slop * 6
        var moved = false
        var multi = false
        var up: androidx.compose.ui.input.pointer.PointerInputChange? = null
        while (true) {
            val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            if (e.changes.count { it.pressed } > 1) multi = true
            val c = e.changes.firstOrNull { it.id == down.id } ?: break
            if ((c.position - down.position).getDistance() > slop) moved = true
            if (!c.pressed) { up = c; break }
        }
        val u = up
        if (u == null || moved || multi || u.uptimeMillis - down.uptimeMillis >= longPress) { lastUp = 0L; return@awaitEachGesture }
        if (second) { lastUp = 0L; action() } else { lastUp = u.uptimeMillis; lastPos = u.position }
    }
}

/** Player placeholder when nothing has a media session: open the last music app (or the default one). */
@Composable
private fun EmptyPlayer(onOpen: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val app = remember { LastMusic.label(ctx) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(p.surfaceContainer).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(p.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            MIcon(Sym.MUSIC_NOTE, 24.dp, p.primary, filled = true)
        }
        Text(tr("Niente in riproduzione", "Nothing playing"), style = textStyle(16, p.onSurface, 600), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Pill(Modifier.clickable { LastMusic.open(ctx); onOpen() }, background = p.primaryContainer, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
            Text(app?.let { tr("Apri $it", "Open $it") } ?: tr("Apri musica", "Open music"), style = textStyle(14, p.onPrimaryContainer, 600), maxLines = 1)
        }
    }
}

/** Two-finger pinch-in anywhere: the hidden way into the launcher settings. Never consumes events. */
private fun Modifier.onPinchIn(action: () -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        var start = 0f
        var fired = false
        while (true) {
            val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            val down = e.changes.filter { it.pressed }
            if (down.isEmpty()) break
            if (down.size >= 2) {
                val d = (down[0].position - down[1].position).getDistance()
                if (start == 0f) start = d else if (!fired && d < start * 0.6f) { fired = true; action() }
            } else start = 0f
        }
    }
}

// ---- Google search + camera (bottom-left) ------------------------------------

@Composable
private fun SearchBar(cfg: LauncherConfig) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    if (!cfg.searchBar && !cfg.cameraButton) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (cfg.searchBar) Pill(Modifier.weight(1f).clickable { Intents.search(ctx, cfg.searchApp) }, background = p.surfaceContainerHigh) {
            MIcon(Sym.SEARCH, 20.dp, p.primary)
            Text(tr("Cerca", "Search"), style = textStyle(15, p.onSurfaceVariant, 500), maxLines = 1)
        }
        if (cfg.cameraButton) Box(Modifier.size(44.dp).clip(CircleShape).background(p.surfaceContainerHigh).clickable { Intents.camera(ctx) }, contentAlignment = Alignment.Center) {
            MIcon(Sym.CAMERA, 22.dp, p.primary)
        }
    }
}

/** Opening the "real" apps behind the widgets, Google search and Lens. */
object Intents {
    private const val GSA = "com.google.android.googlequicksearchbox"

    private fun start(ctx: android.content.Context, i: android.content.Intent) =
        runCatching { ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    private fun app(ctx: android.content.Context, pkg: String) =
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { start(ctx, it) } ?: false

    fun clock(ctx: android.content.Context) {
        start(ctx, android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)) || app(ctx, "com.google.android.deskclock")
    }

    /** [pkg] blank = Google search; otherwise that browser (falls back to Google if it's gone). */
    fun search(ctx: android.content.Context, pkg: String = "") {
        if (pkg.isNotBlank() && pkg != GSA && app(ctx, pkg)) return
        start(ctx, android.content.Intent("android.search.action.GLOBAL_SEARCH").setPackage(GSA)) || app(ctx, GSA)
    }

    /** Installed browsers (package to label), for the search pill setting. */
    fun browsers(ctx: android.content.Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com")).addCategory(android.content.Intent.CATEGORY_BROWSABLE)
        return pm.queryIntentActivities(i, android.content.pm.PackageManager.ResolveInfoFlags.of(android.content.pm.PackageManager.MATCH_ALL.toLong()))
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != GSA && pm.getLaunchIntentForPackage(it.first) != null }
            .distinctBy { it.first }.sortedBy { it.second.lowercase() }
    }

    /** Opens the notification shade (StatusBarManager.expandNotificationsPanel, needs EXPAND_STATUS_BAR). */
    fun notificationShade(ctx: android.content.Context) {
        runCatching {
            @android.annotation.SuppressLint("WrongConstant")
            val sbm = ctx.getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sbm)
        }.onFailure { android.util.Log.w("SidecarLauncher", "expandNotificationsPanel: $it") }
    }

    fun camera(ctx: android.content.Context) {
        start(ctx, android.content.Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
    }

    fun lens(ctx: android.content.Context) {
        // Same deep link the Pixel launcher's Lens shortcut uses (package + extra), then the Lens activity, then the Google app.
        start(ctx, android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("googleapp://lens"))
            .setPackage(GSA).putExtra("LensHomescreenShortcut", true)) ||
            start(ctx, android.content.Intent().setClassName(GSA, "com.google.android.apps.lens.MainActivity")) || app(ctx, GSA)
    }

    fun openFor(ctx: android.content.Context, tab: String, cfg: LauncherConfig) {
        when (tab) {
            "agenda" -> app(ctx, "com.google.android.calendar") ||
                start(ctx, android.content.Intent(android.content.Intent.ACTION_VIEW, android.provider.CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build()))
            "home" -> if (cfg.homeProvider == "ha") app(ctx, "io.homeassistant.companion.android") || app(ctx, "io.homeassistant.companion.android.minimal") ||
                    (cfg.haUrl.isNotBlank() && start(ctx, android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(cfg.haUrl))))
                else app(ctx, "com.google.android.apps.chromecast.app")
            "weather" -> app(ctx, "com.google.android.apps.weather") ||
                start(ctx, android.content.Intent(android.content.Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, tr("meteo", "weather")))
            "photos" -> app(ctx, "com.google.android.apps.photos")
            "notifications" -> start(ctx, android.content.Intent("android.settings.NOTIFICATION_HISTORY"))
        }
    }
}

private fun Color.toArgbInt(): Int = android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

/** Lays the content out shorter than it measures (cuts [top]/[bottom] of empty line space; drawing isn't clipped). */
private fun Modifier.cropVertical(top: androidx.compose.ui.unit.Dp, bottom: androidx.compose.ui.unit.Dp) = layout { m, c ->
    val pl = m.measure(c.copy(maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
    val t = top.roundToPx()
    val b = bottom.roundToPx()
    layout(pl.width, (pl.height - t - b).coerceAtLeast(0)) { pl.place(0, -t) }
}

/** One app's notifications, for the custom status bar. */
private data class NotifGroup(val pkg: String, val app: String, val icon: android.graphics.drawable.Icon?, val count: Int)

/**
 * Custom status bar (system one hidden): notifications on the left ([mode]: off / dots / carousel), battery
 * on the right. Battery from the sticky ACTION_BATTERY_CHANGED broadcast: event-driven, no polling.
 */
@Composable
private fun StatusLine(mode: String, mono: Boolean, pill: Boolean, onTap: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var level by remember { mutableIntStateOf(-1) }
    var charging by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                val l = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val sc = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
                level = if (l >= 0 && sc > 0) l * 100 / sc else -1
                charging = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
            }
        }
        val sticky = ctx.registerReceiver(r, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        sticky?.let { r.onReceive(ctx, it) }
        onDispose { ctx.unregisterReceiver(r) }
    }
    val notifs by PhoneListener.notifications.collectAsState()
    // Grouped per app, most recent app first (4 from Telegram + 1 from Gmail → Telegram, Gmail).
    val groups = remember(notifs) { notifs.groupBy { it.pkg }.map { (pkg, l) -> NotifGroup(pkg, l.first().app, l.first().icon, l.size) } }
    // A bit taller than the system bar so the widgets start below the camera punch-hole.
    Row(
        Modifier.fillMaxWidth().height(30.dp).padding(start = 10.dp, end = 8.dp).pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Optional pills behind the notifications and the battery (theme surface, or black for white-on-black).
        val pillMod = if (!pill) Modifier else Modifier.clip(CircleShape)
            .background(if (mono) Color.Black.copy(alpha = 0.55f) else p.surfaceContainer).padding(horizontal = 10.dp, vertical = 3.dp)
        val hasNotifs = mode != "off" && groups.isNotEmpty()
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds(), contentAlignment = Alignment.CenterStart) {
          Box(if (hasNotifs) pillMod else Modifier) {
            val colors = if (mono) StatusColors(NOTIF_FG, NOTIF_FG, NOTIF_FG.copy(alpha = 0.55f)) else StatusColors(p.primary, p.onSurface, p.onSurfaceVariant)
            when (mode) {
                "dots" -> NotifDots(groups, colors)
                "carousel" -> NotifCarousel(groups, colors)
            }
          }
        }
        if (level >= 0) Row(pillMod, verticalAlignment = Alignment.CenterVertically) {
            val low = level <= 15 && !charging
            MIcon(if (charging) Sym.BATTERY_BOLT else Sym.BATTERY, 22.dp, if (low) p.error else if (mono) NOTIF_FG else p.primary, filled = true)
            Spacer(Modifier.width(4.dp))
            Text("$level%", style = textStyle(15, if (mono) NOTIF_FG else p.onSurfaceVariant, 600))
        }
    }
}

private val NOTIF_FG = Color.White.copy(alpha = 0.9f)

/** Icon/dot, app name, counter. */
private data class StatusColors(val accent: Color, val text: Color, val dim: Color)

/** One dot per app, at most 3: from the 4th app on, a "+". */
@Composable
private fun NotifDots(groups: List<NotifGroup>, c: StatusColors) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        groups.take(3).forEach { Box(Modifier.size(7.dp).clip(CircleShape).background(c.accent)) }
        if (groups.size > 3) Text("+", style = textStyle(15, c.accent, 700))
    }
}

/** One app at a time, sliding up every few seconds (only while the launcher is on screen). */
@Composable
private fun NotifCarousel(groups: List<NotifGroup>, c: StatusColors) {
    if (groups.isEmpty()) return
    var i by remember { mutableIntStateOf(0) }
    val resumed by AppState.resumed.collectAsState()
    LaunchedEffect(groups.size, resumed) {
        while (resumed && groups.size > 1) { delay(4_000); i++ }
    }
    val g = groups[i % groups.size]
    androidx.compose.animation.AnimatedContent(
        g, contentKey = { it.pkg }, label = "notif-carousel",
        transitionSpec = {
            (androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(350)) { it } + fadeIn(androidx.compose.animation.core.tween(350))) togetherWith
                (androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(350)) { -it } + fadeOut(androidx.compose.animation.core.tween(250)))
        },
    ) { gr ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NotifIcon(gr, c.accent)
            Text(if (gr.app.length > 15) gr.app.take(15).trimEnd() + "…" else gr.app, style = textStyle(14, c.text, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (gr.count > 1) Text("${gr.count}", style = textStyle(13, c.dim, 600))
        }
    }
}

/** The app's status-bar icon (a monochrome mask) tinted white; a dot until it's loaded or if missing. */
@Composable
private fun NotifIcon(g: NotifGroup, tint: Color) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { 16.dp.roundToPx() }
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, g.pkg) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                g.icon?.loadDrawable(ctx)?.let { d ->
                    val b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
                    d.setBounds(0, 0, px, px); d.draw(android.graphics.Canvas(b)); b.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    val b = bmp
    if (b != null) Image(b, null, Modifier.size(16.dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint))
    else Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(7.dp).clip(CircleShape).background(tint)) }
}

/**
 * One-finger swipe down that no child scrolled with (checked in the Final pass, so lists still scroll):
 * opens the notification shade like the Pixel home screen.
 */
private fun Modifier.onSwipeDown(action: () -> Unit) = pointerInput(Unit) {
    val trigger = 90.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Final)
        var fired = false
        var consumedByChild = false
        while (true) {
            val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Final)
            if (e.changes.size > 1) consumedByChild = true // multi-touch (pinch): not ours
            val c = e.changes.firstOrNull { it.id == down.id } ?: break
            if (c.isConsumed) consumedByChild = true
            if (!c.pressed) break
            val d = c.position - down.position
            if (!fired && !consumedByChild && d.y > trigger && kotlin.math.abs(d.x) < d.y / 2) { fired = true; action() }
        }
    }
}

/**
 * Insets that never change while on screen: a hidden bar reserves no space at all (so when it pops up
 * transiently — recents, swipe — it draws over the content instead of pushing it down), a visible bar
 * reserves its full size even while it animates.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Modifier.stableBarsPadding(cfg: LauncherConfig): Modifier = this
    .then(if (cfg.statusBar == "hidden") Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility))
    .then(if (cfg.navBar == "hidden") Modifier else Modifier.windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility))

/** Clock/weather, now playing and lyrics over the full-screen slideshow (like desk_sidecar's frame). */
@Composable
private fun FrameOverlay(now: LocalDateTime, weather: Weather?, track: NowPlaying?, cfg: LauncherConfig) {
    val p = LocalPalette.current
    val glass = p.surface.copy(alpha = 0.6f)
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout).padding(20.dp)) {
        if (cfg.lyrics && track != null) {
            LyricsView(
                track,
                Modifier.align(Alignment.TopStart).fillMaxWidth().height(124.dp)
                    .clip(RoundedCornerShape(28.dp)).background(glass).padding(horizontal = 22.dp, vertical = 14.dp),
            )
        }
        Column(Modifier.align(Alignment.BottomStart), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            track?.let {
                Pill(background = glass, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
                    MIcon(Sym.MUSIC_NOTE, 26.dp, p.primary, filled = true)
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(it.title, style = textStyle(20, p.onSurface, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(it.artist, style = textStyle(15, p.onSurfaceVariant, 500), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Pill(background = glass, contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp)) {
                Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), style = textStyle(56, p.onSurface, 800).copy(fontFamily = ClockFont))
                weather?.let {
                    Spacer(Modifier.width(4.dp))
                    MIcon(WeatherRepo.describe(it.code, it.isDay).first, 34.dp, p.primary, filled = true)
                    Text("${it.temperature.roundToInt()}°", style = textStyle(28, p.onSurface, 600))
                }
            }
        }
    }
}
