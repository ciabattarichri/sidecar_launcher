package dev.vq.sidecarlauncher

// Widget pages shared with desk_sidecar (agenda, Google Home).

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

private sealed interface AgendaRow {
    data class Header(val day: LocalDate) : AgendaRow
    data class Item(val event: CalEvent, val today: Boolean) : AgendaRow
}

@Composable
fun AgendaPage(events: List<CalEvent>, modifier: Modifier) {
    val p = LocalPalette.current
    val today = LocalDate.now()
    val rows = remember(events, today) {
        buildList {
            var last: LocalDate? = null
            events.forEach { e ->
                val day = maxOf(e.start.toLocalDate(), today)
                if (day != last) { add(AgendaRow.Header(day)); last = day }
                add(AgendaRow.Item(e, day == today))
            }
        }
    }
    Column(modifier) {
        NextEventBanner(events)
        if (rows.isEmpty()) {
            Text(tr("Nessun impegno nei prossimi giorni", "Nothing planned in the next days"), style = textStyle(15, p.onSurfaceVariant, 400))
            return@Column
        }
        // Scrollable through the next 7 days; snaps back to the top after 20 s untouched.
        val list = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(list.isScrollInProgress, list.firstVisibleItemIndex) {
            if (!list.isScrollInProgress && (list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0)) {
                delay(20_000)
                list.animateScrollToItem(0)
            }
        }
        androidx.compose.foundation.lazy.LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(rows.size) { i ->
                when (val r = rows[i]) {
                    is AgendaRow.Header -> Text(
                        AgendaRepo.dayLabel(r.day),
                        style = textStyle(13, p.primary, 600),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    is AgendaRow.Item -> EventRow(r.event, r.today)
                }
            }
        }
    }
}

/** "Tra 25 min · Christian 1:1" for the next timed event today; highlighted in the last 10 minutes. */
@Composable
private fun NextEventBanner(events: List<CalEvent>) {
    val p = LocalPalette.current
    val now = LocalDateTime.now()
    val next = events.firstOrNull { !it.allDay && it.start.isAfter(now) && it.start.toLocalDate() == now.toLocalDate() } ?: return
    val min = java.time.Duration.between(now, next.start).toMinutes() + 1
    val soon = min <= 10
    val whenText = if (min < 60) tr("tra $min min", "in $min min") else tr("tra ${min / 60} h ${min % 60} min", "in ${min / 60} h ${min % 60} min")
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(16.dp))
            .background(if (soon) p.primaryContainer else p.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MIcon(Sym.TIMER, 18.dp, if (soon) p.onPrimaryContainer else p.primary, filled = soon)
        Text(whenText, style = textStyle(14, if (soon) p.onPrimaryContainer else p.primary, 700))
        Text(next.title, style = textStyle(14, if (soon) p.onPrimaryContainer else p.onSurface, 500), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EventRow(e: CalEvent, today: Boolean) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(64.dp)
                .clip(CircleShape)
                .background(if (today) p.secondaryContainer else p.surfaceContainerHigh)
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (e.allDay) tr("Giorno", "All day") else e.start.format(TIME_FMT),
                style = textStyle(13, if (today) p.onSecondaryContainer else p.onSurfaceVariant),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(e.title, style = textStyle(16, p.onSurface, if (today) 500 else 400), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


@Composable
fun HomePage(modifier: Modifier) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Observed (not a one-off read) so hiding/restoring a device updates the grid immediately.
    val cfg by Settings.state.collectAsState()
    val ha = cfg.homeProvider == "ha"
    val ui by (if (ha) HomeAssistant.ui else GoogleHome.ui).collectAsState()
    var showHidden by remember { mutableStateOf(false) }

    if (ha && (cfg.haUrl.isBlank() || cfg.haToken.isBlank())) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(tr("Imposta indirizzo e token di Home Assistant in Impostazioni → Casa, meteo, Billable (pizzico).",
                "Set the Home Assistant address and token in Settings → Home, weather, Billable (pinch)."),
                style = textStyle(15, p.onSurfaceVariant, 400), textAlign = TextAlign.Center)
        }
        return
    }
    if (!ha && !ui.signedIn) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Pill(
                Modifier.clickable { context.startActivity(Intent(context, LoginActivity::class.java)) },
                background = p.primary,
            ) {
                MIcon(Sym.HOME, 20.dp, p.onPrimary, filled = true)
                Text(tr("Collega Google Home", "Connect Google Home"), style = textStyle(15, p.onPrimary, 600))
            }
        }
        return
    }
    if (ui.devices.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(ui.error ?: tr("Caricamento dispositivi…", "Loading devices…"), style = textStyle(15, p.onSurfaceVariant, 400))
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(168.dp),
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val hidden = cfg.hiddenDevices
        items(ui.devices.filter { showHidden || it.id !in hidden }, key = { it.id }) { d ->
            DevicePill(
                d, isHidden = d.id in hidden,
                onToggle = { scope.launch { if (ha) HomeAssistant.toggle(cfg.haUrl, cfg.haToken, d) else GoogleHome.toggle(d) } },
                onLongPress = { Settings.toggleDevice(d.id) },
            )
        }
        if (hidden.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    if (showHidden) tr("Tieni premuto per ripristinare · nascondi", "Long-press to restore · hide") else tr("${hidden.size} nascosti · mostra", "${hidden.size} hidden · show"),
                    style = textStyle(13, p.onSurfaceVariant, 500),
                    modifier = Modifier.clip(CircleShape).background(p.surfaceContainerHigh)
                        .clickable { showHidden = !showHidden }.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun DevicePill(d: HomeDevice, isHidden: Boolean, onToggle: () -> Unit, onLongPress: () -> Unit) {
    val p = LocalPalette.current
    val s = d.state
    val on = s?.online == true && (s.on == true || (d.isThermostat && s.mode != null && s.mode != "off"))
    val bg = if (on) p.primaryContainer else p.surfaceContainerHigh
    val fg = if (on) p.onPrimaryContainer else p.onSurface
    val icon = when {
        d.isThermostat -> Sym.THERMOSTAT
        "LIGHT" in d.type -> Sym.LIGHTBULB
        "OUTLET" in d.type || "SWITCH" in d.type -> Sym.OUTLET
        else -> Sym.POWER
    }
    val detail = when {
        s == null -> ""
        !s.online -> "Offline"
        d.isThermostat -> listOfNotNull(
            s.ambientC?.let { "%.1f°".format(Locale.ITALIAN, it) },
            s.setpointC?.let { "→ %.1f°".format(Locale.ITALIAN, it) },
        ).joinToString(" ")
        s.on == true && s.brightness != null -> "${s.brightness}%"
        s.on == true -> tr("Acceso", "On")
        else -> tr("Spento", "Off")
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .combinedClickable(
                onClick = { if (d.canOnOff && s?.online != false) onToggle() },
                onLongClick = onLongPress,
            )
            .alpha(if (isHidden) 0.35f else if (s?.online == false) 0.45f else 1f)
            .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(if (on) p.primary else p.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) { MIcon(icon, 20.dp, if (on) p.onPrimary else p.onSurfaceVariant, filled = on) }
        Column(Modifier.padding(start = 10.dp)) {
            Text(d.name, style = textStyle(14, fg, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, style = textStyle(12, if (on) fg else p.onSurfaceVariant, 400), maxLines = 1)
        }
    }
}
