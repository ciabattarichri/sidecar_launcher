package dev.vq.sidecarlauncher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

// ---- Palette ------------------------------------------------------------------------------------

/** Material You colours from the wallpaper (Android 12+), mapped to the DMS role names. */
fun dynamicPalette(context: Context): Palette {
    val c = dynamicDarkColorScheme(context)
    return Palette(
        surface = c.surface, surfaceContainerLow = c.surfaceContainerLow, surfaceContainer = c.surfaceContainer,
        surfaceContainerHigh = c.surfaceContainerHigh, surfaceContainerHighest = c.surfaceContainerHighest,
        onSurface = c.onSurface, onSurfaceVariant = c.onSurfaceVariant,
        primary = c.primary, onPrimary = c.onPrimary, primaryContainer = c.primaryContainer, onPrimaryContainer = c.onPrimaryContainer,
        secondary = c.secondary, secondaryContainer = c.secondaryContainer, onSecondaryContainer = c.onSecondaryContainer,
        tertiary = c.tertiary, tertiaryContainer = c.tertiaryContainer, onTertiaryContainer = c.onTertiaryContainer,
        outline = c.outline, outlineVariant = c.outlineVariant, error = c.error,
    )
}

// ---- Photos (MediaStore) ------------------------------------------------------------------------

object PhotoSource {
    fun allowed(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

    suspend fun uris(ctx: Context, album: String): List<Uri> = withContext(Dispatchers.IO) {
        if (!allowed(ctx)) return@withContext emptyList()
        val sel = if (album.isBlank()) null else "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?"
        val args = if (album.isBlank()) null else arrayOf(album)
        val out = mutableListOf<Uri>()
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), sel, args,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC",
        )?.use { c -> while (c.moveToNext() && out.size < 500) out += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) }
        out.shuffled()
    }

    suspend fun albums(ctx: Context): List<String> = withContext(Dispatchers.IO) {
        if (!allowed(ctx)) return@withContext emptyList()
        val out = sortedSetOf<String>()
        ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME), null, null, null)
            ?.use { c -> while (c.moveToNext()) c.getString(0)?.let { out += it } }
        out.toList()
    }

    suspend fun loadFile(f: java.io.File): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching { android.graphics.BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull()
    }

    /** Decoded down to ~1080 px on the long side: enough for the widget, cheap on memory. */
    suspend fun load(ctx: Context, uri: Uri): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                val s = maxOf(info.size.width, info.size.height)
                if (s > 1080) d.setTargetSampleSize(s / 1080)
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }.asImageBitmap()
        }.getOrNull()
    }
}

/** Slideshow from the PC folder (pulled from phonedash) or the phone's gallery. Also used full screen. */
@Composable
fun PhotoPage(modifier: Modifier, fullscreen: Boolean = false) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val cfg by Settings.state.collectAsState()
    val pcVersion by PcLink.photosVersion.collectAsState()
    val items by produceState(emptyList<Any>(), cfg.photoSource, cfg.photoAlbum, pcVersion) {
        value = if (cfg.photoSource == "pc") withContext(Dispatchers.IO) { PcLink.photos(ctx).shuffled() } else PhotoSource.uris(ctx, cfg.photoAlbum)
    }
    var current by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(items, cfg.slideshowSeconds) {
        var i = 0
        while (items.isNotEmpty()) {
            AppState.resumed.first { it } // no decoding while the launcher is hidden
            val it = items[i++ % items.size]
            (if (it is java.io.File) PhotoSource.loadFile(it) else PhotoSource.load(ctx, it as Uri))?.let { b -> current = b }
            delay(cfg.slideshowSeconds * 1000L)
        }
    }
    Box(
        if (fullscreen) modifier.background(androidx.compose.ui.graphics.Color.Black)
        else modifier.clip(RoundedCornerShape(20.dp)).background(p.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (items.isEmpty()) {
            Text(
                when {
                    cfg.photoSource == "pc" && cfg.pcHost.isBlank() -> tr("Collega il PC: phonedash pair-launcher", "Pair the PC: phonedash pair-launcher")
                    cfg.photoSource == "pc" -> tr("Foto in arrivo dal PC…", "Photos coming from the PC…")
                    PhotoSource.allowed(ctx) -> tr("Nessuna foto", "No photos")
                    else -> tr("Permesso foto mancante", "Photo permission missing")
                },
                style = textStyle(15, p.onSurfaceVariant, 400),
            )
        } else {
            Crossfade(current, animationSpec = tween(1200), label = "photo") { img ->
                if (img != null) {
                    // Ken Burns like desk_sidecar: zoom + slow drift, direction varies per photo.
                    val zoom = remember(img) { Animatable(1f) }
                    if (cfg.photoMotion) LaunchedEffect(img) { zoom.animateTo(1.18f, tween(cfg.slideshowSeconds * 1000 + 1200, easing = LinearEasing)) }
                    val dx = remember(img) { if (img.hashCode() % 2 == 0) 1f else -1f }
                    val dy = remember(img) { if ((img.hashCode() / 2) % 2 == 0) 1f else -1f }
                    Image(img, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = zoom.value
                            scaleY = zoom.value
                            translationX = (zoom.value - 1f) * size.width * 0.35f * dx
                            translationY = (zoom.value - 1f) * size.height * 0.2f * dy
                        })
                }
            }
        }
    }
}

// ---- Phone notifications ------------------------------------------------------------------------

@Composable
fun NotificationsPage(modifier: Modifier) {
    val p = LocalPalette.current
    val items by PhoneListener.notifications.collectAsState()
    if (items.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { Text(tr("Nessuna notifica", "No notifications"), style = textStyle(15, p.onSurfaceVariant, 400)) }
        return
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.key }) { n ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.surfaceContainerHigh)
                    .clickable { runCatching { n.intent?.send() }; if (n.clearable) PhoneListener.dismiss(n.key) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(p.primary))
                        Spacer(Modifier.width(8.dp))
                        Text(n.app, style = textStyle(13, p.primary, 600), maxLines = 1, modifier = Modifier.weight(1f))
                        Text(ago(n.timeMs), style = textStyle(12, p.onSurfaceVariant, 400))
                    }
                    if (n.title.isNotBlank()) Text(n.title, style = textStyle(15, p.onSurface, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (n.text.isNotBlank()) Text(n.text, style = textStyle(14, p.onSurfaceVariant, 400), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (n.clearable) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(32.dp).clip(CircleShape).clickable { PhoneListener.dismiss(n.key) }, contentAlignment = Alignment.Center) {
                        MIcon(Sym.CLOSE, 18.dp, p.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

fun ago(ms: Long): String {
    val min = (System.currentTimeMillis() - ms) / 60_000
    return when {
        min < 1 -> tr("ora", "now")
        min < 60 -> tr("$min min fa", "$min min ago")
        min < 24 * 60 -> tr("${min / 60} h fa", "${min / 60} h ago")
        else -> tr("${min / (24 * 60)} g fa", "${min / (24 * 60)} d ago")
    }
}
