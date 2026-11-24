package dev.vq.sidecarlauncher

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Synced lyrics from LRCLIB (free, open, no key). */
object Lyrics {
    data class Line(val ms: Long, val text: String)

    private val cache = mutableMapOf<String, List<Line>>()
    private val LRC = Regex("""^\[(\d+):(\d+)(?:[.:](\d+))?](.*)$""")

    suspend fun fetch(t: NowPlaying): List<Line> = withContext(Dispatchers.IO) {
        val key = "${t.artist}|${t.title}"
        cache[key]?.let { return@withContext it }
        val lines = try {
            fun e(s: String) = URLEncoder.encode(s, "UTF-8")
            var url = "https://lrclib.net/api/get?artist_name=${e(t.artist)}&track_name=${e(t.title)}"
            if (t.album.isNotBlank()) url += "&album_name=${e(t.album)}"
            if (t.durationMs > 0) url += "&duration=${t.durationMs / 1000}"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "sidecar_launcher/1.0")
            val text = try {
                if (conn.responseCode != 200) "" else conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
            if (text.isEmpty()) emptyList() else parse(JSONObject(text).optString("syncedLyrics"))
        } catch (_: Exception) {
            emptyList()
        }
        cache[key] = lines
        lines
    }

    private fun parse(lrc: String): List<Line> = lrc.lines().mapNotNull { l ->
        LRC.find(l.trim())?.let { m ->
            val (mm, ss, frac, txt) = m.destructured
            val f = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
            Line(mm.toLong() * 60_000 + ss.toLong() * 1000 + f, txt.trim())
        }
    }.sortedBy { it.ms }
}

/**
 * Current + next lyric line. Event-driven: sleeps until the next line is due, so it costs
 * one recomposition per line instead of polling.
 */
@Composable
fun LyricsView(t: NowPlaying, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val lines by produceState(emptyList<Lyrics.Line>(), t.artist, t.title) { value = Lyrics.fetch(t) }
    if (lines.isEmpty()) return // no synced lyrics for this track: the card is hidden
    var idx by remember(lines) { mutableIntStateOf(-1) }
    LaunchedEffect(lines, t) {
        while (true) {
            val pos = t.currentPositionMs()
            idx = lines.indexOfLast { it.ms <= pos }
            if (!t.playing) break
            val next = lines.getOrNull(idx + 1) ?: break
            delay(((next.ms - pos) / t.speed).toLong().coerceIn(50, 60_000))
        }
    }
    val current = lines.getOrNull(idx)?.text?.takeIf { it.isNotBlank() } ?: "♪"
    val next = lines.getOrNull(idx + 1)?.text.orEmpty()
    Column(modifier, verticalArrangement = Arrangement.Center) {
        AnimatedContent(current, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "lyric") { line ->
            Text(
                line, style = textStyle(24, p.onSurface, 700), textAlign = TextAlign.Start,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(next, style = textStyle(16, p.onSurfaceVariant, 500), textAlign = TextAlign.Start, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
