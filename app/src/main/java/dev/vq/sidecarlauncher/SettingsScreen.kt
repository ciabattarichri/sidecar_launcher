package dev.vq.sidecarlauncher

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Full-screen settings (long press on the widgets). Every change is saved immediately. */
@Composable
fun SettingsScreen(onClose: () -> Unit, requestPhotos: () -> Unit, initialPage: String? = null) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val cfg by Settings.state.collectAsState()
    val apps by Apps.list.collectAsState()
    val home by GoogleHome.ui.collectAsState()
    val calendars by produceState(emptyList<AgendaRepo.CalendarInfo>()) { value = AgendaRepo.calendars(ctx) }
    val albums by produceState(emptyList<String>()) { value = PhotoSource.albums(ctx) }
    fun open(action: String) = runCatching { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    var page by remember(initialPage) { mutableStateOf(initialPage) }
    // Background image: Android photo picker (no permission), copied and scaled into the app's files.
    val wallpaperScope = rememberCoroutineScope()
    val wallpaperPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) wallpaperScope.launch { if (Wallpaper.import(ctx, uri)) Settings.update { it.copy(background = "image") } }
    }
    fun pickWallpaper() = wallpaperPicker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
    androidx.activity.compose.BackHandler(enabled = page != null) { page = null }

    LazyColumn(
        Modifier.fillMaxSize().background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .stableBarsPadding(cfg)
            // With the status bar hidden, keep the header clear of the camera punch-hole (cutout inset never changes).
            .then(if (cfg.statusBar == "hidden") Modifier.windowInsetsPadding(WindowInsets.displayCutout) else Modifier),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (page == null) MIcon(Sym.TUNE, 26.dp, p.primary)
                else Box(Modifier.size(40.dp).clip(CircleShape).background(p.surfaceContainer).clickable { page = null }, contentAlignment = Alignment.Center) {
                    MIcon(Sym.ARROW_UP, 22.dp, p.onSurface, modifier = Modifier.graphicsLayer { rotationZ = -90f })
                }
                Spacer(Modifier.width(10.dp))
                Text(CATEGORIES.firstOrNull { it.first == page }?.let { tr(it.second, it.third) } ?: tr("Impostazioni launcher", "Launcher settings"),
                    style = textStyle(22, p.onSurface, 700), modifier = Modifier.weight(1f))
                Box(Modifier.size(40.dp).clip(CircleShape).background(p.surfaceContainer).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    MIcon(Sym.CLOSE, 22.dp, p.onSurface)
                }
            }
        }

        if (page == null) {
            CATEGORIES.forEach { (key, it_, en) ->
                item("cat:$key") {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.surfaceContainer).clickable { page = key }
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tr(it_, en), style = textStyle(17, p.onSurface, 600), modifier = Modifier.weight(1f))
                        MIcon(Sym.ARROW_UP, 20.dp, p.onSurfaceVariant, modifier = Modifier.graphicsLayer { rotationZ = 90f })
                    }
                }
            }
        }
        if (page == "look") {
            section(tr("Aspetto", "Look"))
            item { Choice(tr("Lista app", "App list"), listOf(tr("A destra", "Right"), tr("A sinistra", "Left")), if (cfg.leftHanded) 1 else 0) { i -> Settings.update { it.copy(leftHanded = i == 1) } } }
            item {
                // "From the PC" only when paired with phonedash; built-in DMS amber and seed colours are "Palette".
                val pals = listOfNotNull("dynamic", "pc".takeIf { cfg.pcPaired }, "palette")
                val names = listOfNotNull(tr("Dal sistema (sfondo)", "System (wallpaper)"), tr("Dal PC (DMS)", "From the PC (DMS)").takeIf { cfg.pcPaired }, "Palette")
                val cur = when (cfg.palette) { "dms", "seed" -> "palette"; "pc" -> if (cfg.pcPaired) "pc" else "dynamic"; else -> "dynamic" }
                Group(tr("Colori", "Colours"), names, pals.indexOf(cur).coerceAtLeast(0),
                    onSelect = { i -> Settings.update { it.copy(palette = if (pals[i] == "palette") (if (it.palette == "dms") "dms" else "seed") else pals[i]) } },
                    subEnabled = cur == "palette", disabledNote = tr("Scegli «Palette» per usare questi colori", "Choose «Palette» to use these colours"),
                ) { PalettePicker(cfg, card = false) }
            }
            item {
                val bgs = listOf("black", "surface", "image")
                Group(tr("Sfondo", "Background"), listOf(tr("Nero (risparmio)", "Black (saves power)"), tr("Scuro tenue", "Soft dark"), tr("Immagine", "Image")),
                    bgs.indexOf(cfg.background).coerceAtLeast(0),
                    onSelect = { i -> if (bgs[i] == "image" && !Wallpaper.exists(ctx)) pickWallpaper() else Settings.update { it.copy(background = bgs[i]) } },
                    subEnabled = cfg.background == "image", disabledNote = tr("Solo con «Immagine»", "Only with «Image»"),
                ) { WallpaperSettings(cfg, card = false, onPick = ::pickWallpaper) }
            }
            item { StatusBarGroup(cfg) }
            item { NavBarChoice(cfg) }
            item {
                val sizes = listOf("normal", "large", "xl")
                Dimmed(cfg.showIcons, tr("Solo con le icone visibili", "Only with icons shown")) { Choice(tr("Icone app", "App icons"), listOf("Normali", tr("Grandi", "Large"), tr("Molto grandi", "Extra large")).let { if (I18n.en) listOf("Normal", "Large", "Extra large") else it },
                    sizes.indexOf(cfg.iconSize).coerceAtLeast(0)) { i -> Settings.update { it.copy(iconSize = sizes[i]) } } }
            }
            item {
                val note = if (!cfg.showLabels) tr("Con solo icone la lista resta a metà", "With icons only the list stays half width")
                    else tr("Vale quando accanto alla lista non c'è niente", "Applies when nothing sits beside the list")
                val sideBusy = cfg.clockPages.any { (it == "clock" && cfg.tabs.isNotEmpty()) || (it == "billable" && Pebble.billableAvailable(ctx, cfg)) }
                Dimmed(cfg.showLabels && !sideBusy, note) {
                    Choice(tr("Larghezza lista app", "App list width"), listOf(tr("Metà", "Half"), tr("Tutta la larghezza", "Full width")),
                        if (cfg.appListWidth == "full" && cfg.showLabels) 1 else 0) { i -> Settings.update { it.copy(appListWidth = if (i == 1) "full" else "half") } }
                }
            }
            item {
                val cur = when { !cfg.showLabels -> 1; !cfg.showIcons -> 2; else -> 0 }
                Choice(tr("Nella lista app", "App list shows"), listOf(tr("Icone e nomi", "Icons and names"), tr("Solo icone", "Icons only"), tr("Solo nomi", "Names only")), cur) { i ->
                    Settings.update { it.copy(showLabels = i != 1, showIcons = i != 2) }
                }
            }
            item {
                val sizes = listOf("small", "normal", "large", "xl")
                Dimmed(cfg.showLabels, tr("Solo con i nomi visibili", "Only with names shown")) {
                    Choice(tr("Dimensione nomi delle app", "App name size"), listOf(tr("Piccola", "Small"), tr("Normale", "Normal"), tr("Grande", "Large"), tr("Molto grande", "Extra large")),
                        sizes.indexOf(cfg.labelSize).coerceAtLeast(0)) { i -> Settings.update { it.copy(labelSize = sizes[i]) } }
                }
            }
            item { Choice(tr("Icone a tema", "Themed icons"), listOf("Off", "On"), if (cfg.themedIcons) 1 else 0) { i -> Settings.update { it.copy(themedIcons = i == 1) } } }
            item {
            val langs = listOf("system", "it", "en")
            Choice(tr("Lingua", "Language"), listOf(tr("Sistema", "System"), "Italiano", "English"), langs.indexOf(cfg.language).coerceAtLeast(0)) { i -> Settings.update { it.copy(language = langs[i]) } }
        }

        }
        if (page == "animations") {
            section(tr("Animazioni", "Animations"))
            item {
                val modes = listOf("off", "30s", "always")
                Choice(tr("Player (onda e copertina)", "Player (wave and cover)"), listOf("Off", "30 s", tr("Sempre", "Always")), modes.indexOf(cfg.playerAnimation).coerceAtLeast(0)) { i ->
                    Settings.update { it.copy(playerAnimation = modes[i]) }
                }
            }
            item { Choice(tr("Zoom lento delle foto", "Slow photo zoom"), listOf("On", "Off"), if (cfg.photoMotion) 0 else 1) { i -> Settings.update { it.copy(photoMotion = i == 0) } } }

        }
        if (page == "home") {
            section(tr("Widget in alto", "Top widgets"))
            orderedList(cfg.tabs, LauncherConfig.ALL_TABS, ::tabName) { Settings.update { c -> c.copy(tabs = it) } }

            item {
                val aligns = listOf("start", "center", "end")
                Dimmed(cfg.tabs.isEmpty(), tr("Solo senza widget in alto: l'orologio prende il loro posto", "Only with no top widgets: the clock takes their place")) {
                    Choice(tr("Orologio in alto", "Clock at the top"), listOf(tr("A sinistra", "Left"), tr("Al centro", "Centre"), tr("A destra", "Right")),
                        aligns.indexOf(cfg.topClockAlign).coerceAtLeast(0)) { i -> Settings.update { it.copy(topClockAlign = aligns[i]) } }
                }
            }

            section(tr("Pagine sotto l'orologio (scorrevoli)", "Clock pages (swipe)"))
            orderedList(cfg.clockPages, LauncherConfig.ALL_CLOCK_PAGES.filter { it != "billable" || Pebble.billableAvailable(ctx, cfg) }, ::clockPageName) { Settings.update { c -> c.copy(clockPages = it) } }

            section(tr("In basso: ricerca e fotocamera", "Bottom: search and camera"))
            item { Choice(tr("Barra di ricerca", "Search bar"), listOf(tr("Mostra", "Show"), tr("Nascondi", "Hide")), if (cfg.searchBar) 0 else 1) { i -> Settings.update { it.copy(searchBar = i == 0) } } }
            item {
                val browsers = remember { Intents.browsers(ctx) }
                val keys = listOf("") + browsers.map { it.first }
                Dimmed(cfg.searchBar, tr("Solo con la barra di ricerca", "Only with the search bar")) {
                    Choice(tr("La ricerca apre", "Search opens"), listOf("Google") + browsers.map { it.second }, keys.indexOf(cfg.searchApp).coerceAtLeast(0)) { i ->
                        Settings.update { it.copy(searchApp = keys[i]) }
                    }
                }
            }
            item { Choice(tr("Pulsante fotocamera", "Camera button"), listOf(tr("Mostra", "Show"), tr("Nascondi", "Hide")), if (cfg.cameraButton) 0 else 1) { i -> Settings.update { it.copy(cameraButton = i == 0) } } }

        }
        if (page == "apps") {
            section(tr("Preferiti (in cima alla lista)", "Pinned (top of the list)"))
            val byKey = apps.associateBy { it.key }
            val pinned = cfg.pinned.filter { it in byKey }
            if (pinned.isEmpty()) item { Hint(tr("Nessuno. Scorri un'app verso destra per fissarla in cima.", "None. Swipe an app to the right to pin it.")) }
            pinned.forEachIndexed { i, k ->
                item("pin:$k") {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.surfaceContainer).padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(cfg.labelOf(byKey.getValue(k)), style = textStyle(15, p.onSurface, 500), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconBtn(Sym.ARROW_UP, i > 0) { Settings.movePinned(k, -1) }
                        IconBtn(Sym.ARROW_DOWN, i < pinned.lastIndex) { Settings.movePinned(k, 1) }
                        IconBtn(Sym.CLOSE, true) { Settings.togglePin(k) }
                    }
                }
            }

            section(tr("Comportamento", "Behaviour"))
            item {
                Choice(tr("Pressione lunga su un'app", "Long press on an app"), listOf(tr("Info app", "App info"), tr("Menu (rinomina, disinstalla…)", "Menu (rename, uninstall…)")),
                    if (cfg.appLongPress == "menu") 1 else 0) { i -> Settings.update { it.copy(appLongPress = if (i == 1) "menu" else "info") } }
            }
            item {
                Choice(tr("Ricerca: se resta una sola app, aprila subito", "Search: open the app when only one is left"), listOf("Off", "On"),
                    if (cfg.autoOpenSingle) 1 else 0) { i -> Settings.update { it.copy(autoOpenSingle = i == 1) } }
            }

            section(tr("App rinominate", "Renamed apps"))
            val renamed = apps.filter { it.key in cfg.renamed }
            if (renamed.isEmpty()) item { Hint(tr("Nessuna. Con «Pressione lunga → Menu» puoi rinominare un'app.", "None. With «Long press → Menu» you can rename an app.")) }
            renamed.forEach { a -> item("ren:" + a.key) { RowAction("${cfg.labelOf(a)} (${a.label})", tr("Ripristina", "Restore")) { Settings.rename(a.key, null) } } }

            section(tr("App nascoste", "Hidden apps"))
            val hidden = apps.filter { it.key in cfg.hidden }
            if (hidden.isEmpty()) item { Hint(tr("Nessuna. Scorri un'app verso sinistra per nasconderla.", "None. Swipe an app to the left to hide it.")) }
            hidden.forEach { a -> item(a.key) { RowAction(cfg.labelOf(a), tr("Mostra", "Show")) { Settings.toggleHidden(a.key) } } }

        }
        if (page == "calendars") {
            section(tr("Widget", "Widget"))
            item {
                Choice(tr("Vista", "View"), listOf(tr("Agenda (prossimi 7 giorni)", "Agenda (next 7 days)"), tr("Calendario del mese", "Month calendar")),
                    if (cfg.agendaView == "month") 1 else 0) { i -> Settings.update { it.copy(agendaView = if (i == 1) "month" else "list") } }
            }
            if (cfg.agendaView == "month") item { Hint(tr("Frecce o swipe verticale sulla griglia per cambiare mese; tocca il titolo per tornare a oggi.",
                "Arrows or a vertical swipe on the grid change month; tap the title to go back to today.")) }
            section(tr("Calendari", "Calendars"))
            calendars.forEach { c ->
                item("cal${c.id}") {
                    val on = c.id !in cfg.hiddenCalendars
                    RowAction("${c.name} · ${c.account}", if (on) tr("Visibile", "Visible") else tr("Nascosto", "Hidden"), highlighted = on) { Settings.toggleCalendar(c.id) }
                }
            }

        }
        if (page == "services") {
            section(tr("Casa", "Home"))
            item {
                Choice(tr("Widget Casa", "Home widget"), listOf("Google Home", "Home Assistant"), if (cfg.homeProvider == "ha") 1 else 0) { i ->
                    Settings.update { it.copy(homeProvider = if (i == 1) "ha" else "google") }
                }
            }
            if (cfg.homeProvider == "ha") {
                item { Hint(tr("Indirizzo (es. http://homeassistant.local:8123) e un token di accesso a lunga durata (Home Assistant → Profilo → Sicurezza).",
                    "Address (e.g. http://homeassistant.local:8123) and a long-lived access token (Home Assistant → Profile → Security).")) }
                item { Field(tr("Indirizzo Home Assistant", "Home Assistant address"), cfg.haUrl) { v -> Settings.update { it.copy(haUrl = v.trim()) } } }
                item { Field(tr("Token di accesso", "Access token"), cfg.haToken, secret = true) { v -> Settings.update { it.copy(haToken = v.trim()) } } }
                item {
                    val ha by HomeAssistant.ui.collectAsState()
                    val scope = rememberCoroutineScope()
                    val status = when {
                        cfg.haUrl.isBlank() || cfg.haToken.isBlank() -> tr("Non configurato", "Not set up")
                        ha.error != null -> ha.error!!
                        ha.devices.isNotEmpty() -> tr("${ha.devices.size} dispositivi", "${ha.devices.size} devices")
                        else -> tr("Non ancora collegato", "Not connected yet")
                    }
                    RowAction(status, tr("Prova", "Test")) { scope.launch { HomeAssistant.refresh(cfg.haUrl, cfg.haToken) } }
                }
            } else item {
                if (home.signedIn) RowAction(tr("Collegato", "Connected"), tr("Scollega", "Disconnect")) { GoogleHome.signOut() }
                else RowAction(tr("Non collegato", "Not connected"), tr("Collega", "Connect"), highlighted = true) { ctx.startActivity(Intent(ctx, LoginActivity::class.java)) }
            }
            if (cfg.hiddenDevices.isNotEmpty()) item { RowAction(tr("${cfg.hiddenDevices.size} dispositivi nascosti", "${cfg.hiddenDevices.size} hidden devices"), tr("Mostra tutti", "Show all")) { Settings.update { it.copy(hiddenDevices = emptySet()) } } }

            section(tr("Meteo", "Weather"))
            item { Field(tr("Città (vuoto = posizione)", "City (blank = location)"), cfg.city) { v -> Settings.update { it.copy(city = v.trim()) } } }

            if (Pebble.billableAvailable(ctx, cfg)) {
            section("Billable Hours")
            item { Hint(tr("Ore lavorate registrate con la watchapp Pebble «Billable Hours»: incolla l'URL e la parola segreta del suo backup web per vederle sotto l'orologio.",
                "Hours tracked with the «Billable Hours» Pebble watchapp: paste its web backup URL and secret word to see them under the clock.")) }
            item { Field(tr("URL web app backup", "Backup web app URL"), cfg.billableUrl) { v -> Settings.update { it.copy(billableUrl = v.trim()) } } }
            item { Field(tr("Parola segreta", "Secret word"), cfg.billableToken, secret = true) { v -> Settings.update { it.copy(billableToken = v.trim()) } } }
            item {
                val st by Billable.status.collectAsState()
                val scope = rememberCoroutineScope()
                RowAction(if (st.isEmpty()) tr("Non ancora aggiornato", "Not refreshed yet") else st, tr("Aggiorna", "Refresh")) {
                    scope.launch { Billable.refresh(cfg.billableUrl, cfg.billableToken) }
                }
            }
            }

        }
        if (page == "photos") {
            section(tr("Foto", "Photos"))
            // PC folder (phonedash) only when paired; otherwise the gallery, without mentioning the PC.
            if (cfg.pcPaired) {
                item { Choice(tr("Origine", "Source"), listOf(tr("Cartella del PC", "PC folder"), tr("Galleria del telefono", "Phone gallery")), if (cfg.photoSource == "phone") 1 else 0) { i -> Settings.update { it.copy(photoSource = if (i == 1) "phone" else "pc") } } }
                item {
                    val scope = rememberCoroutineScope()
                    RowAction("PC: ${cfg.pcHost.substringBefore(",")}", tr("Sincronizza ora", "Sync now")) { scope.launch { PcLink.syncPhotos(ctx, force = true) } }
                }
            }
            if (!cfg.photosFromPc && !PhotoSource.allowed(ctx)) item { RowAction(tr("Accesso alle foto", "Photo access"), tr("Consenti", "Allow"), highlighted = true) { requestPhotos() } }
            item {
                if (!cfg.photosFromPc) Choice(tr("Album", "Album"), listOf(tr("Tutte", "All")) + albums, (albums.indexOf(cfg.photoAlbum) + 1).coerceAtLeast(0)) { i ->
                    Settings.update { it.copy(photoAlbum = if (i == 0) "" else albums[i - 1]) }
                }
            }
            item { Choice(tr("Testi delle canzoni (slideshow a schermo intero)", "Song lyrics (full-screen slideshow)"), listOf("On", "Off"), if (cfg.lyrics) 0 else 1) { i -> Settings.update { it.copy(lyrics = i == 0) } } }
            item {
                val opts = listOf(10, 20, 30, 60, 120)
                Choice(tr("Durata foto", "Time per photo"), opts.map { "$it s" }, opts.indexOf(cfg.slideshowSeconds).coerceAtLeast(0)) { i ->
                    Settings.update { it.copy(slideshowSeconds = opts[i]) }
                }
            }

        }
        if (page == "system") {
            section(tr("Sistema", "System"))
            item { RowAction(tr("Accesso notifiche e musica", "Notification & media access"), tr("Apri", "Open")) { open(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS) } }
            item { RowAction(tr("Launcher predefinito", "Default launcher"), tr("Apri", "Open")) { open(AndroidSettings.ACTION_HOME_SETTINGS) } }
            item {
                var done by remember { mutableStateOf(false) }
                RowAction(
                    if (done) tr("Sfondo home impostato a nero", "Home wallpaper set to black") else tr("Sfondo di sistema nero (solo home)", "Black system wallpaper (home only)"),
                    tr("Imposta", "Set"), highlighted = !done,
                ) {
                    // Solid black home-screen wallpaper so it never flashes through transitions; lock screen untouched.
                    runCatching {
                        val bmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
                        android.app.WallpaperManager.getInstance(ctx).setBitmap(bmp, null, true, android.app.WallpaperManager.FLAG_SYSTEM)
                        done = true
                    }
                }
            }
            item { Hint(tr("I colori Material You derivano dallo sfondo: con il nero scegli un colore di base in Sfondo e stile, o qui Colori → Dal PC.", "Material You colours come from the wallpaper: with black, pick a basic colour in Wallpaper & style, or Colours → From the PC here.")) }
            item { RowAction(tr("Impostazioni Android", "Android settings"), tr("Apri", "Open")) { open(AndroidSettings.ACTION_SETTINGS) } }
        }
    }
}

private val CATEGORIES = listOf(
    Triple("look", "Aspetto", "Look"),
    Triple("animations", "Animazioni", "Animations"),
    Triple("home", "Widget e orologio", "Widgets and clock"),
    Triple("apps", "App: preferite, nascoste, rinominate", "Apps: pinned, hidden, renamed"),
    Triple("calendars", "Calendari", "Calendars"),
    Triple("services", "Casa, meteo, Billable", "Home, weather, Billable"),
    Triple("photos", "Foto", "Photos"),
    Triple("system", "Sistema", "System"),
)

private fun tabName(k: String) = when (k) {
    "notifications" -> tr("Notifiche", "Notifications"); "agenda" -> "Agenda"; "home" -> tr("Casa", "Home")
    "weather" -> tr("Meteo", "Weather"); else -> tr("Foto", "Photos")
}

private fun clockPageName(k: String) = if (k == "clock") tr("Orologio e data", "Clock and date") else tr("Ore lavorate (Billable)", "Billable hours")

private fun LazyListScope.section(title: String) = item(title) {
    Text(title, style = textStyle(14, LocalPalette.current.primary, 700), modifier = Modifier.padding(top = 10.dp, start = 4.dp))
}

/** Visible items in order (with up/down), then the hidden ones. */
private fun LazyListScope.orderedList(current: List<String>, all: List<String>, name: (String) -> String, onChange: (List<String>) -> Unit) {
    val rows = current + all.filter { it !in current }
    rows.forEach { k ->
        item("ord:$k") {
            val p = LocalPalette.current
            val on = k in current
            val i = current.indexOf(k)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.surfaceContainer).padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(name(k), style = textStyle(15, if (on) p.onSurface else p.outline, 500), modifier = Modifier.weight(1f))
                if (on) {
                    IconBtn(Sym.ARROW_UP, i > 0) { onChange(current.toMutableList().apply { add(i - 1, removeAt(i)) }) }
                    IconBtn(Sym.ARROW_DOWN, i < current.lastIndex) { onChange(current.toMutableList().apply { add(i + 1, removeAt(i)) }) }
                }
                Pill(Modifier.clickable { onChange(if (on) current - k else current + k) }, background = if (on) p.primaryContainer else p.surfaceContainerHigh,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                    Text(if (on) tr("Visibile", "Visible") else tr("Nascosto", "Hidden"), style = textStyle(13, if (on) p.onPrimaryContainer else p.onSurfaceVariant, 600))
                }
            }
        }
    }
}

@Composable
private fun IconBtn(sym: Char, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.size(36.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        MIcon(sym, 20.dp, if (enabled) p.onSurface else p.outlineVariant)
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = textStyle(14, LocalPalette.current.onSurfaceVariant, 400), modifier = Modifier.padding(start = 4.dp))

@Composable
private fun Choice(label: String, options: List<String>, selected: Int, card: Boolean = true, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().then(cardModifier(card)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = textStyle(15, p.onSurface, 500))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { i, o ->
                val on = i == selected
                Pill(Modifier.clickable { onSelect(i) }, background = if (on) p.primaryContainer else p.surfaceContainerHigh,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    Text(o, style = textStyle(14, if (on) p.onPrimaryContainer else p.onSurfaceVariant, 600), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun cardModifier(card: Boolean) =
    if (card) Modifier.clip(RoundedCornerShape(20.dp)).background(LocalPalette.current.surfaceContainer).padding(12.dp) else Modifier

/**
 * A main choice with its sub-options in the same card. The sub-options are always shown (so you can see
 * what the launcher can do) but greyed out and inert when the main choice doesn't use them, with a note why.
 */
@Composable
private fun Group(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit, subEnabled: Boolean, disabledNote: String, sub: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.surfaceContainer).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Choice(label, options, selected, card = false, onSelect = onSelect)
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.outlineVariant.copy(alpha = 0.5f)))
        Box {
            Column(Modifier.padding(start = 12.dp).alpha(if (subEnabled) 1f else 0.38f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!subEnabled) Text(disabledNote, style = textStyle(13, p.onSurfaceVariant, 500))
                sub()
            }
            // Greyed out: swallow every touch so nothing underneath reacts.
            if (!subEnabled) Box(Modifier.matchParentSize().pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.forEach { it.consume() } }
            })
        }
    }
}

/** Shows [content] greyed out and inert (with [note] above it) when not [enabled]. */
@Composable
private fun Dimmed(enabled: Boolean, note: String, content: @Composable () -> Unit) {
    Box {
        Column(Modifier.alpha(if (enabled) 1f else 0.38f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!enabled) Hint(note)
            content()
        }
        if (!enabled) Box(Modifier.matchParentSize().pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.forEach { it.consume() } }
        })
    }
}

/** Background image (preview + "Change") and how see-through the widgets over it are (DMS-style). */
@Composable
private fun WallpaperSettings(cfg: LauncherConfig, card: Boolean = true, onPick: () -> Unit) {
    val p = LocalPalette.current
    // Sliders move locally (live preview); saved when released (one write instead of one per frame).
    var cards by remember(cfg.cardOpacity) { mutableFloatStateOf(cfg.cardOpacity.toFloat()) }
    var pills by remember(cfg.pillOpacity) { mutableFloatStateOf(cfg.pillOpacity.toFloat()) }
    Column(Modifier.fillMaxWidth().then(cardModifier(card)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Mini home: image, a card and a pill at the chosen opacities.
            Box(Modifier.size(64.dp, 140.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
                WallpaperImage(modifier = Modifier.fillMaxSize())
                Column(Modifier.fillMaxSize().padding(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)).background(p.surfaceContainer.copy(alpha = cards / 100f)).padding(5.dp)) {
                        Box(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(p.surfaceContainerHigh.copy(alpha = pills / 100f)))
                    }
                    Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)).background(p.surfaceContainer.copy(alpha = cards / 100f)).padding(5.dp)) {
                        Box(Modifier.width(24.dp).height(5.dp).clip(CircleShape).background(p.onSurface))
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Immagine di sfondo", "Background image"), style = textStyle(15, p.onSurface, 500))
                Pill(Modifier.clickable(onClick = onPick), background = p.surfaceContainerHigh, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)) {
                    Text(tr("Cambia", "Change"), style = textStyle(13, p.onSurface, 600))
                }
            }
        }
        @Composable
        fun slider(label: String, value: Float, onChange: (Float) -> Unit, onDone: () -> Unit) {
            Text("$label ${value.toInt()}%", style = textStyle(14, p.onSurfaceVariant, 500))
            androidx.compose.material3.Slider(
                value = value, onValueChange = onChange, valueRange = 0f..100f, steps = 19, onValueChangeFinished = onDone,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = p.primary, activeTrackColor = p.primary, inactiveTrackColor = p.surfaceContainerHighest,
                    activeTickColor = p.onPrimary, inactiveTickColor = p.onSurfaceVariant,
                ),
            )
        }
        slider(tr("Opacità riquadri", "Card opacity"), cards, { cards = it }) { Settings.update { it.copy(cardOpacity = cards.toInt()) } }
        slider(tr("Opacità pulsanti e pillole", "Button and pill opacity"), pills, { pills = it }) { Settings.update { it.copy(pillOpacity = pills.toInt()) } }
        Hint(tr("Testi e icone restano sempre pieni.", "Text and icons always stay solid."))
    }
}

@Composable
private fun RowAction(label: String, action: String, highlighted: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.surfaceContainer).padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = textStyle(15, p.onSurface, 500), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Pill(Modifier.clickable(onClick = onClick), background = if (highlighted) p.primary else p.surfaceContainerHigh,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)) {
            Text(action, style = textStyle(13, if (highlighted) p.onPrimary else p.onSurface, 600))
        }
    }
}

@Composable
private fun Field(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    val p = LocalPalette.current
    var text by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.surfaceContainer).padding(12.dp)) {
        Text(label, style = textStyle(13, p.onSurfaceVariant, 500))
        BasicTextField(
            value = text, onValueChange = { text = it; onChange(it) }, singleLine = true,
            textStyle = textStyle(15, p.onSurface, 500), cursorBrush = SolidColor(p.primary),
            visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/** Status bar on the home, with the custom bar's options nested under it. */
@Composable
private fun StatusBarGroup(cfg: LauncherConfig) {
    val custom = cfg.statusBar == "hidden"
    Group(
        tr("Barra di stato in home", "Status bar on home"), listOf(tr("Personalizzata", "Custom"), tr("Di sistema", "System")), if (custom) 0 else 1,
        onSelect = { i -> Settings.update { it.copy(statusBar = if (i == 1) "visible" else "hidden") } },
        subEnabled = custom, disabledNote = tr("Solo con la barra personalizzata", "Only with the custom bar"),
    ) {
        Hint(tr("Batteria a destra e, se vuoi, le notifiche a sinistra. Tocca la barra per aprire la tendina.",
            "Battery on the right and, if you like, notifications on the left. Tap the bar to open the shade."))
        val modes = listOf("off", "dots", "carousel")
        Choice(tr("Notifiche", "Notifications"), listOf("Off", tr("Pallini", "Dots"), tr("Carosello (icona + app)", "Carousel (icon + app)")),
            modes.indexOf(cfg.statusNotifs).coerceAtLeast(0), card = false) { i -> Settings.update { it.copy(statusNotifs = modes[i]) } }
        Choice(tr("Colori", "Colours"), listOf(tr("Del tema", "Theme"), tr("Bianco su nero", "White on black")),
            if (cfg.statusStyle == "mono") 1 else 0, card = false) { i -> Settings.update { it.copy(statusStyle = if (i == 1) "mono" else "theme") } }
        Choice(tr("Sfondo", "Background"), listOf(tr("Nessuno", "None"), tr("Pillola (più leggibile su immagini chiare)", "Pill (easier to read on bright images)")),
            if (cfg.statusPill) 1 else 0, card = false) { i -> Settings.update { it.copy(statusPill = i == 1) } }
    }
}

private val PRESETS = listOf(
    0 to ("Ambra (DMS)" to "Amber (DMS)"), // built-in hand-made palette
    0xFF3F6FD8.toInt() to ("Blu" to "Blue"),
    0xFF1E9AA0.toInt() to ("Turchese" to "Teal"),
    0xFF3E9B5B.toInt() to ("Verde" to "Green"),
    0xFF7E57C2.toInt() to ("Viola" to "Purple"),
    0xFFD8578A.toInt() to ("Rosa" to "Pink"),
    0xFFD04A3A.toInt() to ("Rosso" to "Red"),
    0xFF808890.toInt() to ("Neutro" to "Neutral"),
)

/** Preset swatches + custom hue slider / hex code, all generated with the Material You algorithm. */
@Composable
private fun PalettePicker(cfg: LauncherConfig, card: Boolean = true) {
    val p = LocalPalette.current
    val presetSeeds = PRESETS.map { it.first }
    val custom = cfg.palette == "seed" && cfg.paletteSeed !in presetSeeds
    var hue by remember(cfg.paletteSeed) { mutableFloatStateOf(hueOf(cfg.paletteSeed)) }
    Column(Modifier.fillMaxWidth().then(cardModifier(card)), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("Palette predefinite", "Preset palettes"), style = textStyle(15, p.onSurface, 500))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PRESETS.forEach { (seed, names) ->
                val on = if (seed == 0) cfg.palette == "dms" else cfg.palette == "seed" && cfg.paletteSeed == seed
                val swatch = remember(seed) { if (seed == 0) Palette() else seedPalette(seed) }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(if (on) p.onSurface else Color.Transparent).padding(3.dp)
                        .clip(CircleShape).background(swatch.primaryContainer)
                        .clickable { Settings.update { it.copy(palette = if (seed == 0) "dms" else "seed", paletteSeed = if (seed == 0) it.paletteSeed else seed) } },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(16.dp).clip(CircleShape).background(swatch.primary)) }
            }
        }
        Text(PRESETS.firstOrNull { (seed, _) -> if (seed == 0) cfg.palette == "dms" else cfg.palette == "seed" && cfg.paletteSeed == seed }
            ?.second?.let { tr(it.first, it.second) } ?: tr("Personalizzato", "Custom"), style = textStyle(13, p.onSurfaceVariant, 500))
        Text(tr("Colore personalizzato", "Custom colour"), style = textStyle(15, p.onSurface, 500))
        // Rainbow track: drag to pick the hue; saved when released.
        Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Brush.horizontalGradient(
            (0..12).map { androidx.compose.ui.graphics.Color(seedFromHue(it * 30f)) })))
        androidx.compose.material3.Slider(
            value = hue, onValueChange = { hue = it }, valueRange = 0f..360f,
            onValueChangeFinished = { Settings.update { it.copy(palette = "seed", paletteSeed = seedFromHue(hue)) } },
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = androidx.compose.ui.graphics.Color(seedFromHue(hue)), activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent,
            ),
        )
        val hex = "#%06X".format(cfg.paletteSeed and 0xFFFFFF)
        Field(tr("Codice colore (es. #3F6FD8)", "Colour code (e.g. #3F6FD8)"), if (custom || cfg.palette == "seed") hex else "") { v ->
            val t = v.trim().removePrefix("#")
            if (t.length == 6) t.toLongOrNull(16)?.let { c -> Settings.update { it.copy(palette = "seed", paletteSeed = (0xFF000000 or c).toInt()) } }
        }
    }
}

@Composable
private fun NavBarChoice(cfg: LauncherConfig) =
    Choice(tr("Barra gesture", "Gesture bar"), listOf(tr("Visibile", "Visible"), tr("Nascosta", "Hidden")),
        if (cfg.navBar == "hidden") 1 else 0) { i -> Settings.update { it.copy(navBar = if (i == 1) "hidden" else "visible") } }

/** First run (or an update that adds questions): status bar, notifications and gesture bar. Changes apply live. */
@Composable
fun SetupPrompt() {
    val p = LocalPalette.current
    val cfg by Settings.state.collectAsState()
    if (cfg.setupVersion >= LauncherConfig.SETUP_VERSION) return
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center) {
        Column(Modifier.padding(20.dp).clip(RoundedCornerShape(28.dp)).background(p.surfaceContainerHigh)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("Benvenuto", "Welcome"), style = textStyle(22, p.onSurface, 700))
            Text(tr("Scegli come vuoi le barre di sistema. Puoi cambiarlo quando vuoi in Impostazioni → Aspetto (pizzico con due dita).",
                "Choose how you want the system bars. You can change this any time in Settings → Look (two-finger pinch)."),
                style = textStyle(14, p.onSurfaceVariant, 400))
            StatusBarGroup(cfg)
            NavBarChoice(cfg)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Pill(Modifier.clickable { Settings.update { it.copy(setupVersion = LauncherConfig.SETUP_VERSION) } }, background = p.primary) {
                    Text(tr("Fatto", "Done"), style = textStyle(15, p.onPrimary, 600))
                }
            }
        }
    }
}

/** "Pair with this PC?" confirmation for `phonedash pair-launcher`. */
@Composable
fun PairingPrompt() {
    val p = LocalPalette.current
    val pending by PcLink.pendingPairing.collectAsState()
    val j = pending ?: return
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp).clip(RoundedCornerShape(28.dp)).background(p.surfaceContainerHigh).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Collegare questo PC?", "Pair with this PC?"), style = textStyle(20, p.onSurface, 700))
            Text(j.optString("host").substringBefore(","), style = textStyle(15, p.primary, 600))
            Text(tr("Foto, colori e impostazioni Billable arriveranno da questo PC (phonedash).", "Photos, colours and Billable settings will come from this PC (phonedash)."),
                style = textStyle(14, p.onSurfaceVariant, 400))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                Pill(Modifier.clickable { PcLink.confirmPairing(false) }, background = p.surfaceContainerHighest) { Text(tr("Annulla", "Cancel"), style = textStyle(15, p.onSurface, 600)) }
                Pill(Modifier.clickable { PcLink.confirmPairing(true) }, background = p.primary) { Text(tr("Collega", "Pair"), style = textStyle(15, p.onPrimary, 600)) }
            }
        }
    }
}
