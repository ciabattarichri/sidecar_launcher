package dev.vq.sidecarlauncher

// Month view of the agenda widget: a calendar grid (arrows or vertical swipe change month), the
// selected day's events underneath.

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields

@Composable
fun MonthPage(agenda: List<CalEvent>, modifier: Modifier) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val cfg by Settings.state.collectAsState()
    val today = LocalDate.now()
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var selected by remember { mutableStateOf(today) }
    // Back to today after a minute untouched (like the agenda list snapping to the top).
    LaunchedEffect(month, selected) {
        if (month != YearMonth.from(today) || selected != today) { delay(60_000); month = YearMonth.from(today); selected = today }
    }
    val firstDow = WeekFields.of(I18n.locale).firstDayOfWeek
    val gridStart = month.atDay(1).let { d -> d.minusDays(((d.dayOfWeek.value - firstDow.value + 7) % 7).toLong()) }
    val weeks = ((java.time.temporal.ChronoUnit.DAYS.between(gridStart, month.atEndOfMonth()) / 7) + 1).toInt()
    val gridEnd = gridStart.plusDays(weeks * 7L - 1)
    // Reloaded with the month, and whenever the agenda refreshes (calendar changes).
    val events by produceState(emptyList<CalEvent>(), month, cfg.hiddenCalendars, agenda) {
        value = AgendaRepo.range(ctx, cfg.hiddenCalendars, gridStart, gridEnd)
    }
    val byDay = remember(events) {
        val m = HashMap<LocalDate, MutableList<CalEvent>>()
        events.forEach { e -> AgendaRepo.days(e).forEach { d -> m.getOrPut(d) { mutableListOf() } += e } }
        m
    }
    fun go(delta: Long) {
        month = month.plusMonths(delta)
        selected = if (month == YearMonth.from(today)) today else month.atDay(1)
    }

    Column(modifier) {
        // Header: ‹ November 2026 › — tap the title to go back to today.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val title = month.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", I18n.locale)).replaceFirstChar { it.uppercase() }
            Text(title, style = textStyle(17, p.onSurface, 700), modifier = Modifier.weight(1f).padding(start = 4.dp)
                .clip(CircleShape).clickable { month = YearMonth.from(today); selected = today }.padding(horizontal = 6.dp, vertical = 2.dp))
            ArrowBtn(Sym.CHEVRON_LEFT) { go(-1) }
            ArrowBtn(Sym.CHEVRON_RIGHT) { go(1) }
        }
        // Weekday initials.
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            (0 until 7).forEach { i ->
                Text(firstDow.plus(i.toLong()).getDisplayName(TextStyle.NARROW, I18n.locale), style = textStyle(12, p.onSurfaceVariant, 600),
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        // Grid: vertical swipe changes month (up = next), like scrolling a calendar.
        Column(
            Modifier.weight(2.6f).fillMaxWidth().pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total < -60f) go(1) else if (total > 60f) go(-1) },
                ) { change, dy -> change.consume(); total += dy }
            },
        ) {
            (0 until weeks).forEach { w ->
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    (0 until 7).forEach { d ->
                        val day = gridStart.plusDays(w * 7L + d)
                        DayCell(day, inMonth = YearMonth.from(day) == month, isToday = day == today, isSelected = day == selected,
                            dots = byDay[day].orEmpty().take(3).map { Color(it.color) }, modifier = Modifier.weight(1f).fillMaxHeight()) {
                            selected = day
                            if (YearMonth.from(day) != month) month = YearMonth.from(day)
                        }
                    }
                }
            }
        }
        // The selected day's events.
        val dayEvents = byDay[selected].orEmpty()
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.outlineVariant.copy(alpha = 0.4f)))
        Text(AgendaRepo.dayLabel(selected), style = textStyle(13, p.primary, 600), modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 4.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (dayEvents.isEmpty()) item { Text(tr("Nessun impegno", "Nothing planned"), style = textStyle(14, p.onSurfaceVariant, 400), modifier = Modifier.padding(start = 4.dp)) }
            items(dayEvents) { e ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(e.color)))
                    Text(if (e.allDay) tr("Giorno", "All day") else e.start.format(DateTimeFormatter.ofPattern("HH:mm")),
                        style = textStyle(13, p.onSurfaceVariant, 500), modifier = Modifier.width(52.dp))
                    Text(e.title, style = textStyle(15, p.onSurface, 500), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ArrowBtn(sym: Char, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.size(34.dp).clip(CircleShape).background(p.surfaceContainerHigh).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        MIcon(sym, 20.dp, p.onSurface)
    }
    Spacer(Modifier.width(4.dp))
}

@Composable
private fun DayCell(day: LocalDate, inMonth: Boolean, isToday: Boolean, isSelected: Boolean, dots: List<Color>, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    // Dots overlap the bottom of the cell so short rows (6-week months) never clip them.
    Box(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        val bg = when { isToday -> p.primary; isSelected -> p.secondaryContainer; else -> Color.Transparent }
        val fg = when { isToday -> p.onPrimary; isSelected -> p.onSecondaryContainer; inMonth -> p.onSurface; else -> p.outline }
        Box(Modifier.align(Alignment.Center).offset(y = (-2).dp).size(26.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            Text("${day.dayOfMonth}", style = textStyle(14, fg, if (isToday || isSelected) 700 else 500))
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            dots.forEach { Box(Modifier.size(4.dp).clip(CircleShape).background(if (inMonth) it else it.copy(alpha = 0.4f))) }
        }
    }
}
