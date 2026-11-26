package dev.vq.sidecarlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Custom home background: the picked photo is copied once, scaled down to the screen, into the app's
 * private files (the picker's URI grant is temporary). The widgets over it can be translucent
 * ([LauncherConfig.cardOpacity], [LauncherConfig.pillOpacity]).
 */
object Wallpaper {
    /** Bumped on every new image so the launcher reloads it. */
    val version = MutableStateFlow(0)
    /** Screenshot demo: show this image instead of the saved one (never copied or saved). */
    val override = MutableStateFlow<Uri?>(null)

    private fun file(ctx: Context) = File(ctx.filesDir, "wallpaper.jpg")
    fun exists(ctx: Context) = file(ctx).exists()

    suspend fun import(ctx: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val dm = ctx.resources.displayMetrics
            val maxW = minOf(dm.widthPixels, dm.heightPixels)
            val maxH = maxOf(dm.widthPixels, dm.heightPixels)
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
                // Cover-fit: scale so the image just covers the screen (no point keeping more pixels).
                val s = maxOf(maxW.toFloat() / info.size.width, maxH.toFloat() / info.size.height).coerceAtMost(1f)
                dec.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val tmp = File(ctx.filesDir, "wallpaper.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            tmp.renameTo(file(ctx))
            version.value++
        }.isSuccess
    }

    suspend fun load(ctx: Context): ImageBitmap? = withContext(Dispatchers.IO) {
        file(ctx).takeIf { it.exists() }?.let { f -> runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() }
    }
}

/** The custom background (nothing while the image is loading or missing: the black underneath shows). */
@Composable
fun WallpaperImage(opacityPercent: Int = 100, modifier: Modifier = Modifier.fillMaxSize()) {
    val ctx = LocalContext.current
    val v by Wallpaper.version.collectAsState()
    val ov by Wallpaper.override.collectAsState()
    val img by produceState<ImageBitmap?>(null, v, ov) {
        value = ov?.let { u ->
            withContext(Dispatchers.IO) {
                runCatching { ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, u)) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }.asImageBitmap() }.getOrNull()
            }
        } ?: Wallpaper.load(ctx)
    }
    img?.let { Image(it, null, modifier, contentScale = ContentScale.Crop, alpha = opacityPercent.coerceIn(0, 100) / 100f) }
}
