package dev.vq.sidecarlauncher

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.io.File

/**
 * Material 3 colour roles, same names as DMS/matugen (`~/.cache/dms/dms-colors.json`).
 * Defaults are the PC palette at the time of writing; `sync-theme.sh` pushes the current one.
 */
data class Palette(
    val surface: Color = Color(0xFF130D0A),
    val surfaceContainerLow: Color = Color(0xFF1A110D),
    val surfaceContainer: Color = Color(0xFF221712),
    val surfaceContainerHigh: Color = Color(0xFF2A1C16),
    val surfaceContainerHighest: Color = Color(0xFF33221A),
    val onSurface: Color = Color(0xFFFDDFD2),
    val onSurfaceVariant: Color = Color(0xFFC0A69A),
    val primary: Color = Color(0xFFEDBCA5),
    val onPrimary: Color = Color(0xFF593827),
    val primaryContainer: Color = Color(0xFF613F2D),
    val onPrimaryContainer: Color = Color(0xFFF8C6AE),
    val secondary: Color = Color(0xFFB4988B),
    val secondaryContainer: Color = Color(0xFF4B372D),
    val onSecondaryContainer: Color = Color(0xFFD7B9AB),
    val tertiary: Color = Color(0xFFFCCC91),
    val tertiaryContainer: Color = Color(0xFFEDBE84),
    val onTertiaryContainer: Color = Color(0xFF593A0B),
    val outline: Color = Color(0xFF887166),
    val outlineVariant: Color = Color(0xFF57443A),
    val error: Color = Color(0xFFED7F64),
) {
    companion object {
        const val FILE_NAME = "dms-colors.json"

        fun file(context: Context): File? = context.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }

        /** Reads a dms-colors.json pushed from the PC; falls back to the built-in palette. */
        fun load(context: Context): Palette {
            val f = file(context) ?: return Palette()
            if (!f.isFile) return Palette()
            return try {
                val root = JSONObject(f.readText())
                val c = root.getJSONObject("colors").getJSONObject("dark")
                val d = Palette()
                fun col(key: String, fallback: Color): Color =
                    c.optString(key).takeIf { it.startsWith("#") && it.length == 7 }
                        ?.let { Color(0xFF000000 or it.substring(1).toLong(16)) } ?: fallback
                Palette(
                    surface = col("surface", d.surface),
                    surfaceContainerLow = col("surface_container_low", d.surfaceContainerLow),
                    surfaceContainer = col("surface_container", d.surfaceContainer),
                    surfaceContainerHigh = col("surface_container_high", d.surfaceContainerHigh),
                    surfaceContainerHighest = col("surface_container_highest", d.surfaceContainerHighest),
                    onSurface = col("on_surface", d.onSurface),
                    onSurfaceVariant = col("on_surface_variant", d.onSurfaceVariant),
                    primary = col("primary", d.primary),
                    onPrimary = col("on_primary", d.onPrimary),
                    primaryContainer = col("primary_container", d.primaryContainer),
                    onPrimaryContainer = col("on_primary_container", d.onPrimaryContainer),
                    secondary = col("secondary", d.secondary),
                    secondaryContainer = col("secondary_container", d.secondaryContainer),
                    onSecondaryContainer = col("on_secondary_container", d.onSecondaryContainer),
                    tertiary = col("tertiary", d.tertiary),
                    tertiaryContainer = col("tertiary_container", d.tertiaryContainer),
                    onTertiaryContainer = col("on_tertiary_container", d.onTertiaryContainer),
                    outline = col("outline", d.outline),
                    outlineVariant = col("outline_variant", d.outlineVariant),
                    error = col("error", d.error),
                )
            } catch (_: Exception) {
                Palette()
            }
        }
    }
}

val LocalPalette = staticCompositionLocalOf { Palette() }

private fun sansFlex(weight: Int) = Font(
    R.font.google_sans_flex,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Google Sans Flex, the DMS interface font. */
val SansFlex = FontFamily(sansFlex(300), sansFlex(400), sansFlex(500), sansFlex(600), sansFlex(700))

private fun roundedClock(weight: Int) = Font(
    R.font.google_sans_flex,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.Setting("ROND", 100f), // fully rounded terminals
        FontVariation.Setting("opsz", 144f), // display optical size
    ),
)

/** Playful clock face: Google Sans Flex, rounded and heavy (night: rounded, lighter). */
val ClockFont = FontFamily(roundedClock(800), roundedClock(400))

private val SymbolsOutlined = FontFamily(
    Font(R.font.material_symbols_rounded, variationSettings = FontVariation.Settings(FontVariation.Setting("FILL", 0f)))
)
private val SymbolsFilled = FontFamily(
    Font(R.font.material_symbols_rounded, variationSettings = FontVariation.Settings(FontVariation.Setting("FILL", 1f)))
)

/** Material Symbols Rounded codepoints bundled in the subset font. */
object Sym {
    const val SUNNY = ''
    const val CLEAR_NIGHT = ''
    const val PARTLY_CLOUDY_DAY = ''
    const val PARTLY_CLOUDY_NIGHT = ''
    const val CLOUD = ''
    const val FOGGY = ''
    const val RAINY = ''
    const val RAINY_LIGHT = ''
    const val RAINY_HEAVY = ''
    const val SNOWY = ''
    const val THUNDERSTORM = ''
    const val WATER_DROP = ''
    const val UMBRELLA = ''
    const val MUSIC_NOTE = ''
    const val PLAY = ''
    const val PAUSE = ''
    const val SKIP_NEXT = ''
    const val SKIP_PREVIOUS = ''
    const val EVENT = ''
    const val CALENDAR = ''
    const val SCHEDULE = ''
    const val LIGHTBULB = ''
    const val OUTLET = ''
    const val THERMOSTAT = ''
    const val HOME = ''
    const val SPEAKER = ''
    const val APPS = ''
    const val SETTINGS = ''
    const val CLOSE = ''
    const val LOCATION = ''
    const val AIR = ''
    const val HUMIDITY = ''
    const val ECO = ''
    const val POWER = ''
    const val MODE_HEAT = ''
    const val PHOTO = '\ue3f4'
    const val TIMER = '\ue425'
    const val WORK = '\ue943'
    const val NOTIFICATIONS = '\ue7f5'
    const val COMPUTER = '\ue31e'
    const val MEMORY = '\ue322'
    const val THERMOMETER = '\ue846'
    const val STICKY = '\uf1fc'
    const val LYRICS = '\uec0b'
    const val LOCK = '\ue899'
    const val SPEED = '\ue9e4'
    const val ARROW_DOWN = '\ue5db'
    const val ARROW_UP = '\ue5d8'
    const val INFO = '\ue88e'
    const val PIN = '\uf10d'
    const val SEARCH = '\uef7a'
    const val TUNE = '\ue429'
    const val HIDE = '\ue8f5'
    const val LENS = '\ue3b5'
    const val CAMERA = '\ue412'
    const val BATTERY = '\uf304'
    const val BATTERY_BOLT = '\uf305'
}

@Composable
fun MIcon(symbol: Char, size: Dp, color: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    val sp = with(LocalDensity.current) { size.toSp() }
    Text(
        symbol.toString(),
        modifier = modifier,
        style = TextStyle(
            fontFamily = if (filled) SymbolsFilled else SymbolsOutlined,
            fontSize = sp,
            lineHeight = sp,
            color = color,
        ),
    )
}

/** DMS bar widget: a fully rounded pill on surfaceContainer, icon + text, 8dp padding. */
@Composable
fun Pill(
    modifier: Modifier = Modifier,
    background: Color = LocalPalette.current.surfaceContainer,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .clip(CircleShape)
            .background(background)
            .heightIn(min = 40.dp)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

fun textStyle(size: Int, color: Color, weight: Int = 500) = TextStyle(
    fontFamily = SansFlex,
    fontSize = androidx.compose.ui.unit.TextUnit(size.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp),
    fontWeight = FontWeight(weight),
    color = color,
)
