package dev.vq.sidecarlauncher

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.os.UserHandle
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

data class AppEntry(val key: String, val label: String, val component: ComponentName, val user: UserHandle)

/** Launchable apps of every profile (incl. work), kept fresh via LauncherApps callbacks. */
object Apps {
    val list = MutableStateFlow<List<AppEntry>>(emptyList())
    private lateinit var la: LauncherApps
    private lateinit var app: Context
    private val icons = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun init(context: Context) {
        if (::la.isInitialized) return
        app = context.applicationContext
        la = app.getSystemService(LauncherApps::class.java)
        la.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(p: String, u: UserHandle) = reload()
            override fun onPackageAdded(p: String, u: UserHandle) = reload()
            override fun onPackageChanged(p: String, u: UserHandle) = reload()
            override fun onPackagesAvailable(p: Array<out String>, u: UserHandle, r: Boolean) = reload()
            override fun onPackagesUnavailable(p: Array<out String>, u: UserHandle, r: Boolean) = reload()
        })
        reload()
    }

    private fun keyOf(i: LauncherActivityInfo) = "${i.componentName.flattenToShortString()}#${i.user.hashCode()}"

    fun reload() {
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
        list.value = la.profiles.flatMap { u -> la.getActivityList(null, u) }
            .filter { it.componentName.packageName != app.packageName }
            .map { AppEntry(keyOf(it), it.label.toString(), it.componentName, it.user) }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    /** App icon; with [themed] the adaptive monochrome layer is tinted [fg] on a [bg] circle (Pixel "themed icons"). */
    suspend fun icon(e: AppEntry, sizePx: Int, themed: Boolean = false, fg: Int = 0, bg: Int = 0): Bitmap? {
        val key = "${e.key}|$sizePx|$themed|$fg|$bg"
        return icons.get(key) ?: withContext(Dispatchers.IO) {
            runCatching {
                val d = la.resolveActivity(android.content.Intent().setComponent(e.component), e.user)?.getIcon(0) ?: return@runCatching null
                val mono = (d as? android.graphics.drawable.AdaptiveIconDrawable)?.monochrome
                if (themed && mono != null) {
                    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                    val c = android.graphics.Canvas(bmp)
                    c.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = bg })
                    val m = mono.mutate()
                    m.setTint(fg)
                    // The adaptive layers are 108 dp with a 72 dp safe zone: draw them 1.5x to fill the circle.
                    m.setBounds(-sizePx / 4, -sizePx / 4, sizePx * 5 / 4, sizePx * 5 / 4)
                    m.draw(c)
                    bmp
                } else d.toBitmap(sizePx, sizePx)
            }.getOrNull()?.also { icons.put(key, it) }
        }
    }

    fun launch(e: AppEntry) = runCatching { la.startMainActivity(e.component, e.user, null, null) }
    fun info(e: AppEntry) = runCatching { la.startAppDetailsActivity(e.component, e.user, null, null) }
}
