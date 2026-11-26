package dev.vq.sidecarlauncher

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var nowPlaying: NowPlayingSource
    private val settingsOpen = mutableStateOf(false)
    private val settingsPage = mutableStateOf<String?>(null)
    /** false as soon as we lose the top: the home fades to black so the recents transition looks clean. */
    private val onTop = mutableStateOf(true)
    private val palette = MutableStateFlow(Palette())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light status icons; *dark* gesture handle so it barely shows on the black background.
        enableEdgeToEdge(
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        Settings.init(this)
        Apps.init(this)
        GoogleHome.init(this)
        nowPlaying = NowPlayingSource(this)
        PcLink.importPairing(intent)
        demo(intent)
        refreshPalette()

        setContent {
            val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { Apps.reload() }
            LaunchedEffect(Unit) {
                perms.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.READ_MEDIA_IMAGES))
            }
            BackHandler { settingsOpen.value = false }

            val cfg by Settings.state.collectAsState()
            // Language switch: restart so every string and formatter is rebuilt.
            val startLanguage = remember { cfg.language }
            LaunchedEffect(cfg.language) { if (cfg.language != startLanguage) recreate() }
            LaunchedEffect(cfg.palette, cfg.paletteSeed, cfg.pcHost) { refreshPalette() }
            LaunchedEffect(cfg.navBar, cfg.statusBar) { applyNavBar() }

            val pal by palette.collectAsState()
            val alpha by androidx.compose.animation.core.animateFloatAsState(
                if (onTop.value) 1f else 0f,
                androidx.compose.animation.core.tween(if (onTop.value) 180 else 90),
                label = "fade",
            )
            CompositionLocalProvider(LocalPalette provides pal) {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize().background(Color.Black)
                ) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
                        // Background image: translucent widgets on the home only (settings and dialogs stay solid).
                        val homePal = remember(pal, cfg.background, cfg.cardOpacity, cfg.pillOpacity) {
                            if (cfg.background == "image") pal.translucent(cfg.cardOpacity / 100f, cfg.pillOpacity / 100f) else pal
                        }
                        CompositionLocalProvider(LocalPalette provides homePal) {
                            LauncherScreen(nowPlaying, onSettings = { settingsOpen.value = true })
                        }
                        PairingPrompt()
                        if (!settingsOpen.value) SetupPrompt()
                        if (settingsOpen.value) {
                            SettingsScreen(
                                onClose = { settingsOpen.value = false },
                                requestPhotos = { perms.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES)) },
                                initialPage = settingsPage.value,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Screenshot demo for the README, debug builds only. Look-only
     * settings in memory, never saved; `demo_end` restores everything:
     *   am start -n dev.vq.sidecarlauncher/.MainActivity --es demo_cfg '{"tabs":[],"palette":"seed"}' \
     *     [--es demo_wallpaper content://…] [--es demo_settings look] | --ez demo_end true
     */
    private fun demo(i: Intent?) {
        i ?: return
        // Debug builds only (./gradlew assembleDebug): the release APK ignores these extras.
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (i.getBooleanExtra("demo_end", false)) {
            Settings.endDemo(); Wallpaper.override.value = null; settingsOpen.value = false; refreshPalette(); return
        }
        i.getStringExtra("demo_cfg")?.let { runCatching { Settings.demo(org.json.JSONObject(it)) }.onFailure { e -> android.util.Log.w("SidecarLauncher", "demo: $e") } }
        i.getStringExtra("demo_wallpaper")?.let { Wallpaper.override.value = android.net.Uri.parse(it) }
        i.getStringExtra("demo_settings")?.let { settingsPage.value = it.ifBlank { null }; settingsOpen.value = true }
    }

    private val scope = kotlinx.coroutines.MainScope()

    private fun refreshPalette() {
        val cfg = Settings.state.value
        when (cfg.palette) {
            "dms" -> palette.value = Palette()
            "seed" -> palette.value = runCatching { seedPalette(cfg.paletteSeed) }
                .onFailure { android.util.Log.w("SidecarLauncher", "seed palette", it) }.getOrDefault(Palette())
            "pc" -> if (!cfg.pcPaired) palette.value = runCatching { dynamicPalette(this) }.getOrDefault(Palette()) else {
                palette.value = Palette.load(this) // last palette fetched from the PC
                scope.launch { PcLink.fetchPalette(this@MainActivity)?.let { palette.value = it } }
            }
            else -> palette.value = runCatching { dynamicPalette(this) }.getOrDefault(Palette())
        }
    }

    private fun applyNavBar() {
        val c = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        // Dark gesture handle (it would be white on our black background); status icons stay light.
        c.isAppearanceLightNavigationBars = true
        c.isAppearanceLightStatusBars = false
        window.isNavigationBarContrastEnforced = false
        c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val cfg = Settings.state.value
        val nav = androidx.core.view.WindowInsetsCompat.Type.navigationBars()
        val status = androidx.core.view.WindowInsetsCompat.Type.statusBars()
        if (cfg.navBar == "hidden") c.hide(nav) else c.show(nav)
        if (cfg.statusBar == "hidden") c.hide(status) else c.show(status)
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        onTop.value = isTopResumedActivity
    }

    override fun onResume() {
        super.onResume()
        onTop.value = true
        AppState.resumed.value = true
        applyNavBar()
        nowPlaying.start()
        refreshPalette() // the wallpaper may have changed
    }

    override fun onPause() {
        onTop.value = false
        AppState.resumed.value = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        settingsOpen.value = false // HOME pressed while on the launcher
        settingsPage.value = null
        PcLink.importPairing(intent)
        demo(intent)
    }

    override fun onDestroy() {
        nowPlaying.stop()
        super.onDestroy()
    }
}

