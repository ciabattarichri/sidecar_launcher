package dev.vq.sidecarlauncher

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val ANIM_WINDOW_MS = 30_000L

/** Now-playing card modelled on the DMS "Bento" media player. */
@Composable
fun MediaCard(t: NowPlaying, source: NowPlayingSource, lastTouch: Long = 0L) {
    // Battery: animate only for ANIM_WINDOW_MS after a track change or a touch, then freeze.
    val mode = Remote.config.collectAsState().value.playerAnimation
    var live by remember { mutableStateOf(true) }
    LaunchedEffect(t.title, t.playing, lastTouch, mode) {
        when (mode) {
            "off" -> live = false
            "always" -> live = t.playing
            else -> {
                live = t.playing
                delay(ANIM_WINDOW_MS)
                live = false
            }
        }
    }
    val p = LocalPalette.current
    val position by produceState(t.currentPositionMs(), t) {
        while (true) {
            value = t.currentPositionMs()
            delay(if (live) 1000 else 5000)
        }
    }
    val progress = if (t.durationMs > 0) (position.toFloat() / t.durationMs).coerceIn(0f, 1f) else 0f
    val smoothProgress = progress
    val amplitude by animateFloatAsState(if (t.playing) 1f else 0f, tween(300), label = "amplitude")

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(p.surfaceContainer)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CookieArt(t, 118.dp, animate = live)
        Spacer(Modifier.size(10.dp))
        // Inner deck card: smaller radius nested in the outer card (the "cut-out" look)
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(p.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(t.title, style = textStyle(17, p.primary, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "),
                style = textStyle(13, p.onSurfaceVariant, 400), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            WavyProgress(
                progress = smoothProgress,
                amplitude = amplitude,
                playing = t.playing && live,
                color = p.primary,
                trackColor = p.onSurface.copy(alpha = 0.14f),
                modifier = Modifier.fillMaxWidth().height(14.dp),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(fmt(position), style = textStyle(11, p.onSurfaceVariant, 500))
                Spacer(Modifier.weight(1f))
                Text(t.appName, style = textStyle(11, p.onSurfaceVariant, 600), maxLines = 1,
                    modifier = Modifier.clip(CircleShape).background(p.surfaceContainerHighest).padding(horizontal = 8.dp, vertical = 1.dp))
                Spacer(Modifier.weight(1f))
                Text(fmt(t.durationMs), style = textStyle(11, p.onSurfaceVariant, 500))
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Segment(Sym.SKIP_PREVIOUS, p.primaryContainer, p.onPrimaryContainer, 1f, leading = true, trailing = false, onClick = source::previous)
                Segment(
                    if (t.playing) Sym.PAUSE else Sym.PLAY, p.secondaryContainer, p.onSecondaryContainer, 1.7f,
                    leading = false, trailing = false, onClick = source::togglePlayPause,
                )
                Segment(Sym.SKIP_NEXT, p.primaryContainer, p.onPrimaryContainer, 1f, leading = false, trailing = true, onClick = source::next)
            }
        }
    }
}

/** Album art clipped to the M3 12-sided cookie; the mask spins slowly while playing. */
@Composable
private fun CookieArt(t: NowPlaying, size: Dp, animate: Boolean) {
    val p = LocalPalette.current
    val cookie = remember { CookieShape(12, 0.07f) }
    // 10 fps stepped rotation (one turn / 40 s); state read only inside graphicsLayer -> no recomposition.
    val angle = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(t.playing, animate) {
        while (t.playing && animate) {
            delay(100)
            angle.floatValue = (angle.floatValue + 0.9f) % 360f
        }
    }
    Box(
        Modifier
            .size(size)
            .graphicsLayer { rotationZ = angle.floatValue }
            .clip(cookie)
            .background(p.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (t.art != null) {
            Image(
                t.art.asImageBitmap(), null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = -angle.floatValue },
            )
        } else {
            MIcon(Sym.MUSIC_NOTE, size / 3, p.primary, filled = true)
        }
    }
}

private class FloatHolder(var value: Float = 0f)
private fun mutableFloatHolder() = FloatHolder()

/** A segment of a connected button group: full radius on the outer ends, small inside; morphs when pressed. */
@Composable
private fun RowScope.Segment(
    symbol: Char,
    bg: Color,
    fg: Color,
    weight: Float,
    leading: Boolean,
    trailing: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val spec = spring<Dp>(dampingRatio = 0.6f, stiffness = 600f)
    val inner by animateDpAsState(if (pressed) 22.dp else 8.dp, spec, label = "inner")
    val outer = 22.dp
    val w by animateFloatAsState(if (pressed) weight * 1.15f else weight, spring(0.6f, 600f), label = "w")
    val shape = RoundedCornerShape(
        topStart = if (leading) outer else inner,
        bottomStart = if (leading) outer else inner,
        topEnd = if (trailing) outer else inner,
        bottomEnd = if (trailing) outer else inner,
    )
    Box(
        Modifier
            .weight(w)
            .fillMaxSize()
            .clip(shape)
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { MIcon(symbol, 24.dp, fg, filled = true) }
}

private fun fmt(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** Scalloped circle like M3 "Cookie12Sided": r(θ) = R·(1 − depth·(1 − cos(nθ))/2). */
private class CookieShape(private val lobes: Int, private val depth: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = minOf(size.width, size.height) / 2f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val path = Path()
        val steps = 360
        for (i in 0..steps) {
            val th = 2 * PI * i / steps
            val rr = r * (1 - depth * (1 - cos(lobes * th)) / 2).toFloat()
            val x = cx + rr * cos(th).toFloat()
            val y = cy + rr * sin(th).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

/** M3 expressive wavy progress: sine wave up to the playhead, flat track after, stop dot at the end. */
@Composable
private fun WavyProgress(
    progress: Float,
    amplitude: Float,
    playing: Boolean,
    color: Color,
    trackColor: Color,
    modifier: Modifier,
) {
    // 20 fps stepped phase; read only in the draw lambda -> redraw without recomposition.
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        while (playing) {
            delay(50)
            phase.floatValue = (phase.floatValue + (2 * PI / 32).toFloat()) % (2 * PI).toFloat()
        }
    }
    Canvas(modifier) {
        val ph = phase.floatValue
        val stroke = 4.dp.toPx()
        val gap = 6.dp.toPx()
        val wavelength = 22.dp.toPx()
        val amp = 3.dp.toPx() * amplitude
        val mid = size.height / 2
        val end = size.width * progress
        if (end > stroke) {
            val path = Path()
            var x = stroke / 2
            path.moveTo(x, mid + amp * sin(x / wavelength * 2 * PI.toFloat() - ph))
            while (x < end) {
                x = minOf(x + 2f, end)
                path.lineTo(x, mid + amp * sin(x / wavelength * 2 * PI.toFloat() - ph))
            }
            drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        // Playhead dot, fixed on the bar's centre line
        drawCircle(color, 6.dp.toPx(), Offset(end.coerceAtLeast(stroke / 2), mid))
        val trackStart = end + gap + 6.dp.toPx()
        val trackEnd = size.width - stroke / 2
        if (trackStart < trackEnd) {
            drawLine(trackColor, Offset(trackStart, mid), Offset(trackEnd, mid), stroke, StrokeCap.Round)
        }
        drawCircle(color, stroke / 2, Offset(trackEnd, mid))
    }
}
