package dev.vq.sidecarlauncher

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Everything the launcher lets you tune (long press on the widgets). Persisted as one JSON blob. */
data class LauncherConfig(
    val leftHanded: Boolean = false,
    /** Player wave/cover animation: "off", "30s", "always" (same as desk_sidecar). */
    val playerAnimation: String = "30s",
    /** Ken Burns zoom in the photo widget. */
    val photoMotion: Boolean = true,
    /** Synced lyrics (LRCLIB) on the full-screen slideshow. */
    val lyrics: Boolean = true,
    /** "system" (follows the phone: Italian if Italian, else English), "it" or "en". */
    val language: String = "system",
    /** "dynamic" = Material You from the wallpaper, "dms" = built-in DMS palette. */
    val palette: String = "dynamic",
    /** Top widget tabs, in order; missing = hidden. */
    val tabs: List<String> = ALL_TABS,
    /** Pages of the rolling clock pager, in order. */
    val clockPages: List<String> = ALL_CLOCK_PAGES,
    val pinned: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val hiddenCalendars: Set<Long> = emptySet(),
    val hiddenDevices: Set<String> = emptySet(),
    val city: String = "",
    val billableUrl: String = "",
    val billableToken: String = "",
    /** MediaStore bucket name for the slideshow ("" = all photos). */
    val photoAlbum: String = "",
    val slideshowSeconds: Int = 20,
    /** "pc" = photos pulled from phonedash (PC folder), "phone" = MediaStore. */
    val photoSource: String = "pc",
    /** App list: icon size "normal" | "large" | "xl", labels on/off, themed (monochrome) icons. */
    val iconSize: String = "normal",
    val showLabels: Boolean = true,
    val themedIcons: Boolean = false,
    /** Gesture bar: "hidden" (default: the system draws the handle white on black) or "visible". */
    val navBar: String = "visible",
    /** Status bar on the home screen: "hidden" (swipe down to see it) or "visible". */
    val statusBar: String = "hidden",
    /** Root background: "black" (AMOLED off), "surface" (palette surface, softer) or "image" (custom photo over black). */
    val background: String = "black",
    /** With a background image, opacity (0–100 %) of the widget cards (DMS "background") and of the pills inside them ("midground"). */
    val cardOpacity: Int = 70,
    /** Clock at the top (no widget tabs): "start", "center" or "end". */
    val topClockAlign: String = "start",
    /** App list with nothing beside it (clock on top, no Billable): "half" (like with Billable) or "full" width. Icons-only is always half. */
    val appListWidth: String = "half",
    val pillOpacity: Int = 85,
    /** Notifications in the custom status bar: "off", "dots" (one per app) or "carousel" (icon + app name, rolling). */
    val statusNotifs: String = "off",
    /** Custom status bar colours: "theme" (palette) or "mono" (white on black). */
    val statusStyle: String = "theme",
    /** Custom status bar items on a pill (readable over bright background images). */
    val statusPill: Boolean = false,
    /** Seed colour for palette = "seed" (Material You scheme generated on the phone). */
    val paletteSeed: Int = 0xFF3F6FD8.toInt(),
    /** Last first-run setup answered ([SETUP_VERSION]): below it the setup questions are shown again. */
    val setupVersion: Int = 0,
    /** App list: icons next to the names (off = names only; can't be off together with [showLabels]). */
    val showIcons: Boolean = true,
    /** App names text size: "small" | "normal" | "large" | "xl". */
    val labelSize: String = "normal",
    /** Search apps: open the app straight away when only one is left. */
    val autoOpenSingle: Boolean = false,
    /** Custom app names, by app key. */
    val renamed: Map<String, String> = emptyMap(),
    /** Long press on an app: "info" (app info) or "menu" (rename, uninstall, info…). */
    val appLongPress: String = "info",
    /** Bottom-left search pill: shown, and which app it opens ("" = Google, else a browser package). */
    val searchBar: Boolean = true,
    val searchApp: String = "",
    /** Camera button next to the search pill. */
    val cameraButton: Boolean = true,
    /** Agenda widget: "list" (next 7 days) or "month" (calendar grid, swipe between months). */
    val agendaView: String = "list",
    /** Smart home widget: "google" (Google Home) or "ha" (Home Assistant). */
    val homeProvider: String = "google",
    val haUrl: String = "",
    val haToken: String = "",
    // Home Assistant entities hidden from the widget live in hiddenDevices too (entity ids).
    /** phonedash pull server (set by `phonedash pair-launcher`). */
    val pcHost: String = "",
    val pcPort: Int = 8766,
    val pcToken: String = "",
    val configVersion: Int = 2,
) {
    companion object {
        val ALL_TABS = listOf("notifications", "agenda", "home", "weather", "photos")
        val ALL_CLOCK_PAGES = listOf("clock", "billable")
        /** Bump when an update adds a question to the first-run setup ([SetupPrompt]). */
        const val SETUP_VERSION = 2
    }
}

object Settings {
    private lateinit var sp: SharedPreferences
    private val _state = MutableStateFlow(LauncherConfig())
    val state: StateFlow<LauncherConfig> = _state

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        _state.value = runCatching { decode(JSONObject(sp.getString("config", "{}")!!)) }.getOrDefault(LauncherConfig())
    }

    fun update(f: (LauncherConfig) -> LauncherConfig) {
        val c = f(_state.value)
        _state.value = c
        if (beforeDemo == null) sp.edit().putString("config", encode(c).toString()).apply()
    }

    // ---- Screenshot demo (adb only, see ReadmeDemo): look-only overrides, in memory, never saved ----
    private var beforeDemo: LauncherConfig? = null
    private val DEMO_KEYS = setOf(
        "tabs", "palette", "paletteSeed", "background", "cardOpacity", "pillOpacity", "statusBar", "statusNotifs",
        "statusStyle", "statusPill", "showLabels", "clockPages", "iconSize", "themedIcons", "leftHanded", "topClockAlign", "appListWidth", "navBar",
        "showIcons", "labelSize", "searchBar", "cameraButton", "agendaView", "appLongPress", "setupVersion",
    )

    fun demo(overrides: JSONObject) {
        val base = beforeDemo ?: _state.value
        beforeDemo = base
        val j = encode(base)
        overrides.keys().forEach { k -> if (k in DEMO_KEYS) j.put(k, overrides.get(k)) }
        _state.value = decode(j)
    }

    fun endDemo() {
        beforeDemo?.let { _state.value = it }
        beforeDemo = null
    }

    /** Pinning a hidden app (found through search) also un-hides it, or it would never show up. */
    fun togglePin(key: String) = update { c -> if (key in c.pinned) c.copy(pinned = c.pinned - key) else c.copy(pinned = c.pinned + key, hidden = c.hidden - key) }
    fun rename(key: String, name: String?) = update { c -> c.copy(renamed = if (name.isNullOrBlank()) c.renamed - key else c.renamed + (key to name.trim())) }
    fun toggleHidden(key: String) = update { c -> c.copy(hidden = if (key in c.hidden) c.hidden - key else c.hidden + key, pinned = c.pinned - key) }
    fun toggleDevice(id: String) = update { c -> c.copy(hiddenDevices = if (id in c.hiddenDevices) c.hiddenDevices - id else c.hiddenDevices + id) }
    fun toggleCalendar(id: Long) = update { c -> c.copy(hiddenCalendars = if (id in c.hiddenCalendars) c.hiddenCalendars - id else c.hiddenCalendars + id) }
    fun movePinned(key: String, delta: Int) = update { c ->
        val l = c.pinned.toMutableList()
        val i = l.indexOf(key)
        val j = i + delta
        if (i < 0 || j !in l.indices) c else c.copy(pinned = l.apply { add(j, removeAt(i)) })
    }

    private fun encode(c: LauncherConfig) = JSONObject()
        .put("leftHanded", c.leftHanded).put("playerAnimation", c.playerAnimation).put("photoMotion", c.photoMotion).put("lyrics", c.lyrics)
        .put("language", c.language).put("palette", c.palette)
        .put("tabs", JSONArray(c.tabs)).put("clockPages", JSONArray(c.clockPages))
        .put("pinned", JSONArray(c.pinned)).put("hidden", JSONArray(c.hidden.toList()))
        .put("hiddenCalendars", JSONArray(c.hiddenCalendars.toList())).put("hiddenDevices", JSONArray(c.hiddenDevices.toList()))
        .put("city", c.city).put("billableUrl", c.billableUrl).put("billableToken", c.billableToken)
        .put("photoAlbum", c.photoAlbum).put("slideshowSeconds", c.slideshowSeconds)
        .put("photoSource", c.photoSource).put("iconSize", c.iconSize).put("showLabels", c.showLabels).put("themedIcons", c.themedIcons)
        .put("showIcons", c.showIcons).put("labelSize", c.labelSize).put("autoOpenSingle", c.autoOpenSingle)
        .put("renamed", JSONObject(c.renamed as Map<*, *>)).put("appLongPress", c.appLongPress)
        .put("searchBar", c.searchBar).put("searchApp", c.searchApp).put("cameraButton", c.cameraButton)
        .put("agendaView", c.agendaView).put("homeProvider", c.homeProvider).put("haUrl", c.haUrl).put("haToken", c.haToken)
        .put("configVersion", 2).put("navBar", c.navBar).put("statusBar", c.statusBar).put("background", c.background).put("cardOpacity", c.cardOpacity).put("topClockAlign", c.topClockAlign).put("appListWidth", c.appListWidth).put("pillOpacity", c.pillOpacity).put("statusNotifs", c.statusNotifs).put("statusStyle", c.statusStyle).put("statusPill", c.statusPill).put("paletteSeed", c.paletteSeed).put("setupVersion", c.setupVersion).put("pcHost", c.pcHost).put("pcPort", c.pcPort).put("pcToken", c.pcToken)

    private fun decode(j: JSONObject): LauncherConfig {
        val d = LauncherConfig()
        fun strs(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }
        return LauncherConfig(
            leftHanded = j.optBoolean("leftHanded", d.leftHanded),
            playerAnimation = j.optString("playerAnimation", d.playerAnimation),
            photoMotion = j.optBoolean("photoMotion", d.photoMotion),
            lyrics = j.optBoolean("lyrics", d.lyrics),
            language = j.optString("language", d.language),
            configVersion = j.optInt("configVersion", 1),
            palette = j.optString("palette", d.palette),
            tabs = strs("tabs")?.filter { it in LauncherConfig.ALL_TABS } ?: d.tabs,
            // Empty is allowed: no clock/date at all.
            clockPages = strs("clockPages")?.filter { it in LauncherConfig.ALL_CLOCK_PAGES } ?: d.clockPages,
            pinned = strs("pinned") ?: d.pinned,
            hidden = strs("hidden")?.toSet() ?: d.hidden,
            hiddenCalendars = j.optJSONArray("hiddenCalendars")?.let { a -> (0 until a.length()).map { a.getLong(it) }.toSet() } ?: d.hiddenCalendars,
            hiddenDevices = strs("hiddenDevices")?.toSet() ?: d.hiddenDevices,
            city = j.optString("city", d.city),
            billableUrl = j.optString("billableUrl", d.billableUrl),
            billableToken = j.optString("billableToken", d.billableToken),
            photoAlbum = j.optString("photoAlbum", d.photoAlbum),
            slideshowSeconds = j.optInt("slideshowSeconds", d.slideshowSeconds),
            photoSource = j.optString("photoSource", d.photoSource),
            iconSize = j.optString("iconSize", d.iconSize),
            themedIcons = j.optBoolean("themedIcons", d.themedIcons),
            // v1 stored "hidden" as the old default: from v2 the gesture bar is visible unless chosen otherwise.
            navBar = if (j.optInt("configVersion", 1) >= 2 && j.optString("navBar") == "hidden") "hidden" else "visible",
            statusBar = if (j.optString("statusBar") == "visible") "visible" else "hidden",
            background = j.optString("background", d.background),
            cardOpacity = j.optInt("cardOpacity", d.cardOpacity).coerceIn(0, 100),
            topClockAlign = j.optString("topClockAlign", d.topClockAlign),
            appListWidth = j.optString("appListWidth", d.appListWidth),
            pillOpacity = j.optInt("pillOpacity", d.pillOpacity).coerceIn(0, 100),
            statusNotifs = j.optString("statusNotifs", d.statusNotifs),
            statusStyle = j.optString("statusStyle", d.statusStyle),
            statusPill = j.optBoolean("statusPill", d.statusPill),
            paletteSeed = j.optInt("paletteSeed", d.paletteSeed),
            setupVersion = j.optInt("setupVersion", d.setupVersion),
            showLabels = j.optBoolean("showLabels", d.showLabels),
            showIcons = j.optBoolean("showIcons", d.showIcons) || !j.optBoolean("showLabels", d.showLabels),
            labelSize = j.optString("labelSize", d.labelSize),
            autoOpenSingle = j.optBoolean("autoOpenSingle", d.autoOpenSingle),
            renamed = j.optJSONObject("renamed")?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } } ?: d.renamed,
            appLongPress = j.optString("appLongPress", d.appLongPress),
            searchBar = j.optBoolean("searchBar", d.searchBar),
            searchApp = j.optString("searchApp", d.searchApp),
            cameraButton = j.optBoolean("cameraButton", d.cameraButton),
            agendaView = j.optString("agendaView", d.agendaView),
            homeProvider = j.optString("homeProvider", d.homeProvider),
            haUrl = j.optString("haUrl", d.haUrl),
            haToken = j.optString("haToken", d.haToken),
            pcHost = j.optString("pcHost", d.pcHost),
            pcPort = j.optInt("pcPort", d.pcPort),
            pcToken = j.optString("pcToken", d.pcToken),
        )
    }
}

/** Name shown for an app (custom name if renamed). */
fun LauncherConfig.labelOf(a: AppEntry) = renamed[a.key] ?: a.label

/** Paired with a PC running phonedash (`phonedash pair-launcher`): only then PC/DMS options make sense. */
val LauncherConfig.pcPaired get() = pcHost.isNotBlank() && pcToken.isNotBlank()
val LauncherConfig.photosFromPc get() = photoSource == "pc" && pcPaired

/** Billable Hours is a Pebble watchapp: its options show only with a Pebble app installed (or already set up). */
object Pebble {
    private val PACKAGES = listOf("coredevices.coreapp", "com.getpebble.android.basalt", "com.getpebble.android")
    fun installed(ctx: Context) = PACKAGES.any { pkg ->
        runCatching { ctx.packageManager.getPackageInfo(pkg, android.content.pm.PackageManager.PackageInfoFlags.of(0)) }.isSuccess
    }
    fun billableAvailable(ctx: Context, c: LauncherConfig) = c.billableUrl.isNotBlank() || installed(ctx)
}

/** Compatibility with the files shared with desk_sidecar (`Remote.config.value.language`, …). */
object Remote {
    val config: StateFlow<LauncherConfig> get() = Settings.state
}

/** Whether the launcher is on screen: background loops pause otherwise (battery). */
object AppState {
    val resumed = MutableStateFlow(false)
}
