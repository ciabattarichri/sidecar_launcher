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
    /** Root background: "black" (AMOLED off) or "surface" (palette surface, softer). */
    val background: String = "black",
    /** phonedash pull server (set by `phonedash pair-launcher`). */
    val pcHost: String = "",
    val pcPort: Int = 8766,
    val pcToken: String = "",
    val configVersion: Int = 2,
) {
    companion object {
        val ALL_TABS = listOf("notifications", "agenda", "home", "weather", "photos")
        val ALL_CLOCK_PAGES = listOf("clock", "billable")
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
        sp.edit().putString("config", encode(c).toString()).apply()
    }

    fun togglePin(key: String) = update { c -> c.copy(pinned = if (key in c.pinned) c.pinned - key else c.pinned + key) }
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
        .put("configVersion", 2).put("navBar", c.navBar).put("statusBar", c.statusBar).put("background", c.background).put("pcHost", c.pcHost).put("pcPort", c.pcPort).put("pcToken", c.pcToken)

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
            clockPages = strs("clockPages")?.filter { it in LauncherConfig.ALL_CLOCK_PAGES }?.ifEmpty { null } ?: d.clockPages,
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
            showLabels = j.optBoolean("showLabels", d.showLabels),
            themedIcons = j.optBoolean("themedIcons", d.themedIcons),
            // v1 stored "hidden" as the old default: from v2 the gesture bar is visible unless chosen otherwise.
            navBar = if (j.optInt("configVersion", 1) >= 2 && j.optString("navBar") == "hidden") "hidden" else "visible",
            statusBar = if (j.optString("statusBar") == "visible") "visible" else "hidden",
            background = j.optString("background", d.background),
            pcHost = j.optString("pcHost", d.pcHost),
            pcPort = j.optInt("pcPort", d.pcPort),
            pcToken = j.optString("pcToken", d.pcToken),
        )
    }
}

/** Compatibility with the files shared with desk_sidecar (`Remote.config.value.language`, …). */
object Remote {
    val config: StateFlow<LauncherConfig> get() = Settings.state
}

/** Whether the launcher is on screen: background loops pause otherwise (battery). */
object AppState {
    val resumed = MutableStateFlow(false)
}
