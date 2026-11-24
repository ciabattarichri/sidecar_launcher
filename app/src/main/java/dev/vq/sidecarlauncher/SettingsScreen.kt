package dev.vq.sidecarlauncher

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Full-screen settings (long press on the widgets). Every change is saved immediately. */
@Composable
fun SettingsScreen(onClose: () -> Unit, requestPhotos: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val cfg by Settings.state.collectAsState()
    val apps by Apps.list.collectAsState()
    val home by GoogleHome.ui.collectAsState()
    val calendars by produceState(emptyList<AgendaRepo.CalendarInfo>()) { value = AgendaRepo.calendars(ctx) }
    val albums by produceState(emptyList<String>()) { value = PhotoSource.albums(ctx) }
    fun open(action: String) = runCatching { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    var page by remember { mutableStateOf<String?>(null) }
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
                val pals = listOf("dynamic", "pc", "dms")
                Choice(tr("Colori", "Colours"), listOf(tr("Dallo sfondo", "From wallpaper"), tr("Dal PC (DMS)", "From the PC (DMS)"), tr("DMS predefiniti", "DMS default")),
                    pals.indexOf(cfg.palette).coerceAtLeast(0)) { i -> Settings.update { it.copy(palette = pals[i]) } }
            }
            item { Choice(tr("Sfondo", "Background"), listOf(tr("Nero (risparmio)", "Black (saves power)"), tr("Scuro tenue", "Soft dark")), if (cfg.background == "surface") 1 else 0) { i -> Settings.update { it.copy(background = if (i == 1) "surface" else "black") } } }
            item { Choice(tr("Barra di stato in home", "Status bar on home"), listOf(tr("Fullscreen (solo batteria)", "Fullscreen (battery only)"), "Stock"), if (cfg.statusBar == "visible") 1 else 0) { i -> Settings.update { it.copy(statusBar = if (i == 1) "visible" else "hidden") } } }
            item { Choice(tr("Barra gesture", "Gesture bar"), listOf(tr("Visibile", "Visible"), tr("Nascosta", "Hidden")), if (cfg.navBar == "hidden") 1 else 0) { i -> Settings.update { it.copy(navBar = if (i == 1) "hidden" else "visible") } } }
            item {
                val sizes = listOf("normal", "large", "xl")
                Choice(tr("Icone app", "App icons"), listOf("Normali", tr("Grandi", "Large"), tr("Molto grandi", "Extra large")).let { if (I18n.en) listOf("Normal", "Large", "Extra large") else it },
                    sizes.indexOf(cfg.iconSize).coerceAtLeast(0)) { i -> Settings.update { it.copy(iconSize = sizes[i]) } }
            }
            item { Choice(tr("Nomi delle app", "App names"), listOf(tr("Mostra", "Show"), tr("Solo icone", "Icons only")), if (cfg.showLabels) 0 else 1) { i -> Settings.update { it.copy(showLabels = i == 0) } } }
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

            section(tr("Pagine sotto l'orologio (scorrevoli)", "Clock pages (swipe)"))
            orderedList(cfg.clockPages, LauncherConfig.ALL_CLOCK_PAGES, ::clockPageName) { Settings.update { c -> c.copy(clockPages = it.ifEmpty { listOf("clock") }) } }

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
                        Text(byKey.getValue(k).label, style = textStyle(15, p.onSurface, 500), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconBtn(Sym.ARROW_UP, i > 0) { Settings.movePinned(k, -1) }
                        IconBtn(Sym.ARROW_DOWN, i < pinned.lastIndex) { Settings.movePinned(k, 1) }
                        IconBtn(Sym.CLOSE, true) { Settings.togglePin(k) }
                    }
                }
            }

            section(tr("App nascoste", "Hidden apps"))
            val hidden = apps.filter { it.key in cfg.hidden }
            if (hidden.isEmpty()) item { Hint(tr("Nessuna. Scorri un'app verso sinistra per nasconderla.", "None. Swipe an app to the left to hide it.")) }
            hidden.forEach { a -> item(a.key) { RowAction(a.label, tr("Mostra", "Show")) { Settings.toggleHidden(a.key) } } }

        }
        if (page == "calendars") {
            section(tr("Calendari", "Calendars"))
            calendars.forEach { c ->
                item("cal${c.id}") {
                    val on = c.id !in cfg.hiddenCalendars
                    RowAction("${c.name} · ${c.account}", if (on) tr("Visibile", "Visible") else tr("Nascosto", "Hidden"), highlighted = on) { Settings.toggleCalendar(c.id) }
                }
            }

        }
        if (page == "services") {
            section("Google Home")
            item {
                if (home.signedIn) RowAction(tr("Collegato", "Connected"), tr("Scollega", "Disconnect")) { GoogleHome.signOut() }
                else RowAction(tr("Non collegato", "Not connected"), tr("Collega", "Connect"), highlighted = true) { ctx.startActivity(Intent(ctx, LoginActivity::class.java)) }
            }
            if (cfg.hiddenDevices.isNotEmpty()) item { RowAction(tr("${cfg.hiddenDevices.size} dispositivi nascosti", "${cfg.hiddenDevices.size} hidden devices"), tr("Mostra tutti", "Show all")) { Settings.update { it.copy(hiddenDevices = emptySet()) } } }

            section(tr("Meteo", "Weather"))
            item { Field(tr("Città (vuoto = posizione)", "City (blank = location)"), cfg.city) { v -> Settings.update { it.copy(city = v.trim()) } } }

            section("Billable Hours")
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
        if (page == "photos") {
            section(tr("Foto", "Photos"))
            item { Choice(tr("Origine", "Source"), listOf(tr("Cartella del PC", "PC folder"), tr("Galleria del telefono", "Phone gallery")), if (cfg.photoSource == "phone") 1 else 0) { i -> Settings.update { it.copy(photoSource = if (i == 1) "phone" else "pc") } } }
            item {
                val scope = rememberCoroutineScope()
                if (cfg.pcHost.isBlank()) Hint(tr("PC non collegato: telefono via USB, poi sul PC `phonedash pair-launcher`.", "PC not paired: phone on USB, then `phonedash pair-launcher` on the PC."))
                else RowAction("PC: ${cfg.pcHost.substringBefore(",")}", tr("Sincronizza ora", "Sync now")) { scope.launch { PcLink.syncPhotos(ctx, force = true) } }
            }
            if (cfg.photoSource == "phone" && !PhotoSource.allowed(ctx)) item { RowAction(tr("Accesso alle foto", "Photo access"), tr("Consenti", "Allow"), highlighted = true) { requestPhotos() } }
            item {
                if (cfg.photoSource == "phone") Choice(tr("Album", "Album"), listOf(tr("Tutte", "All")) + albums, (albums.indexOf(cfg.photoAlbum) + 1).coerceAtLeast(0)) { i ->
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
    Triple("apps", "App: preferite e nascoste", "Apps: pinned and hidden"),
    Triple("calendars", "Calendari", "Calendars"),
    Triple("services", "Google Home, meteo, Billable", "Google Home, weather, Billable"),
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
private fun Choice(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.surfaceContainer).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
