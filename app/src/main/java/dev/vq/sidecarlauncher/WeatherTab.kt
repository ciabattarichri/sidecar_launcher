package dev.vq.sidecarlauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import kotlin.math.roundToInt

private val RAIN = Color(0xFF6FA8DC)
private val LEGEND_W = 30.dp

/**
 * Today in 3-hour slots: one column per slot (hour, icon, temperature, rain %, humidity %),
 * a legend column on the left, the current slot highlighted.
 */
@Composable
fun WeatherTab(w: Weather?, modifier: Modifier) {
    val p = LocalPalette.current
    if (w == null || w.hours.size < 24) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(tr("Meteo non disponibile", "Weather unavailable"), style = textStyle(15, p.onSurfaceVariant, 400))
        }
        return
    }
    val hours = w.hours
    val slots = (0 until 24 step 3).toList()
    val nowSlot = LocalTime.now().hour / 3 * 3

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Oggi", "Today"), style = textStyle(16, p.onSurface, 600))
            Text(
                "min ${hours.minOf { it.temp }.roundToInt()}° · max ${hours.maxOf { it.temp }.roundToInt()}°" +
                    tr(" · pioggia max ", " · max rain ") + "${hours.maxOf { it.rainProb ?: 0 }}%",
                style = textStyle(13, p.onSurfaceVariant, 400),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Legend
            Column(Modifier.width(LEGEND_W).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Cell { }
                Cell { }
                Cell { MIcon(Sym.THERMOSTAT, 18.dp, p.onSurfaceVariant) }
                Cell { MIcon(Sym.UMBRELLA, 18.dp, RAIN) }
                Cell { MIcon(Sym.HUMIDITY, 18.dp, p.onSurfaceVariant) }
            }
            slots.forEach { hr ->
                val slice = hours.subList(hr, hr + 3)
                val h = hours[hr]
                val current = hr == nowSlot
                val fg = if (current) p.onPrimaryContainer else p.onSurface
                val sub = if (current) p.onPrimaryContainer else p.onSurfaceVariant
                val rain = slice.maxOf { it.rainProb ?: 0 }
                val hum = slice.mapNotNull { it.humidity }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (current) p.primaryContainer else p.surfaceContainerHigh),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Cell { Text("%02d".format(hr), style = textStyle(13, sub, 600)) }
                    Cell { MIcon(WeatherRepo.describe(h.code, h.isDay).first, 24.dp, if (current) fg else p.primary, filled = current) }
                    Cell { Text("${h.temp.roundToInt()}°", style = textStyle(16, fg, 600)) }
                    Cell { Text("$rain%", style = textStyle(12, if (rain >= 30) RAIN else sub, if (rain >= 30) 700 else 500), maxLines = 1, softWrap = false) }
                    Cell { Text(hum?.let { "$it%" } ?: "–", style = textStyle(12, sub, 500), maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}

/** One of the five equally tall rows. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Cell(content: @Composable () -> Unit) {
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { content() }
}
