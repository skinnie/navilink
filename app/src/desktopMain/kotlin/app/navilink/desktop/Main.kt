package app.navilink.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import osmimg.*
import java.io.File
import javax.swing.JFileChooser

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "NaviLink Maps",
        state = rememberWindowState(width = 1280.dp, height = 820.dp)) {
        val scope = rememberCoroutineScope()
        val m = remember { MapsModel(scope) }
        LaunchedEffect(Unit) {
            m.refreshDrives()
            m.chooseSource(PremadeSource.OPENTOPOMAP)
            // Notice a device being plugged in or out.
            while (true) { delay(3000); if (m.busy == null) m.refreshDrives(quiet = true) }
        }
        NaviTheme { MapsWindow(m) }
    }
}

// ---- theme: one calm accent, neutrals carry the weight, status = icon + text ----

private val NaviColors = lightColorScheme(
    primary = Color(0xFF1F5A80), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE9F2), onPrimaryContainer = Color(0xFF0B2E45),
    secondaryContainer = Color(0xFFDCE9F2), onSecondaryContainer = Color(0xFF0B2E45),
    background = Color(0xFFF6F6F3), onBackground = Color(0xFF1D2125),
    surface = Color.White, onSurface = Color(0xFF1D2125),
    surfaceVariant = Color(0xFFEDEDE8), onSurfaceVariant = Color(0xFF565C62),
    surfaceContainer = Color(0xFFEDEDE8), surfaceContainerLow = Color(0xFFFAFAF7), surfaceContainerHigh = Color(0xFFE6E6E1),
    outline = Color(0xFFC4C6C0), outlineVariant = Color(0xFFDADBD5),
    error = Color(0xFFB3261E), errorContainer = Color(0xFFFBE8E6), onErrorContainer = Color(0xFF5C0F0A),
)

/** Status tones: always shown with an icon and words, never colour alone. */
enum class Tone(val fg: Color, val bg: Color, val icon: ImageVector) {
    OK(Color(0xFF2D6A3E), Color(0xFFE2F0E5), Icons.Outlined.CheckCircle),
    WARN(Color(0xFF7A5200), Color(0xFFFFF1D0), Icons.Outlined.WarningAmber),
    BAD(Color(0xFFB3261E), Color(0xFFFBE8E6), Icons.Outlined.ErrorOutline),
    INFO(Color(0xFF1F5A80), Color(0xFFE4EEF5), Icons.Outlined.Info),
}

private val NaviType = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = t.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = t.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    )
}

private val NaviShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(8.dp),
)

@Composable
fun NaviTheme(content: @Composable () -> Unit) =
    // The look shared with the phone app (app.navilink.ui), light for now on the desktop.
    app.navilink.ui.NaviTheme(dark = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }

private val tnum = TextStyle(fontFeatureSettings = "tnum")
private val hint @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

// ---- small building blocks ----

@Composable
fun StatusRow(tone: Tone, text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(tone.bg).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(tone.icon, null, tint = tone.fg, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun StepHeading(n: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Radio(selected: Boolean, text: String, hint: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).selectable(selected, role = Role.RadioButton, onClick = onClick)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        RadioButton(selected, onClick = null, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        Column {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            hint?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun Check(checked: Boolean, text: String, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).toggleable(checked, role = Role.Checkbox, onValueChange = onChange)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Checkbox(checked, onCheckedChange = null, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        Column {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            hint?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = hint, modifier = Modifier.size(32.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = hint)
    }
}

/** A button with a menu: the desktop stand-in for a drop-down list. */
@Composable
private fun MenuButton(icon: ImageVector, title: String, subtitle: String?, enabled: Boolean, menu: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = hint, maxLines = 1) }
            }
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Outlined.ArrowDropDown, null, tint = hint)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) { menu { open = false } }
    }
}

// ---- window ----

@Composable
fun MapsWindow(m: MapsModel) {
    Column(Modifier.fillMaxSize()) {
        DeviceBar(m)
        Row(Modifier.weight(1f).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepHeading(1, "Choose a map")
                    Spacer(Modifier.weight(1f))
                    SingleChoiceSegmentedButtonRow(Modifier.width(340.dp)) {
                        listOf("Build a map", "Download a map").forEachIndexed { i, t ->
                            SegmentedButton(m.tab == i, onClick = { m.tab = i }, shape = SegmentedButtonDefaults.itemShape(i, 2), icon = {}) { Text(t, maxLines = 1) }
                        }
                    }
                }
                if (m.tab == 0) BuildChooser(m, Modifier.weight(1f)) else DownloadChooser(m, Modifier.weight(1f))
            }
            RightColumn(m, Modifier.width(420.dp).fillMaxHeight())
        }
        if (m.showLog) LogPanel(m, Modifier.fillMaxWidth().height(200.dp))
        StatusBar(m)
    }
}

private fun driveSub(d: Drive) = "${size(d.freeBytes)} free"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeviceBar(m: MapsModel) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("NaviLink Maps", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(end = 12.dp))
            val free = m.busy == null
            val maps = m.installed.count { it.path.substringAfterLast('/').lowercase() !in m.profile.systemNames }
            when (val d = m.device) {
                is DeviceChoice.OnDrive -> MenuButton(Icons.Outlined.Usb, m.deviceName ?: "Garmin",
                    "${driveSub(d.drive)} · ${if (maps == 1) "1 map" else "$maps maps"} on it", free) { close -> DeviceMenu(m, close) }
                DeviceChoice.DataCard -> MenuButton(Icons.Outlined.SdStorage, "GPSMAP 276C, 278 or 478", "Data card, written by WebUpdater", free) { close -> DeviceMenu(m, close) }
                DeviceChoice.Folder -> MenuButton(Icons.Outlined.FolderOpen, "No device", "Save maps to a folder", free) { close -> DeviceMenu(m, close) }
            }
            if (m.device is DeviceChoice.OnDrive) {
                val dev = (m.device as DeviceChoice.OnDrive).drive.root
                MenuButton(Icons.Outlined.SdCard, if (m.sd != null) "Memory card" else "No memory card", m.sd?.let(::driveSub), free) { close ->
                    DropdownMenuItem(text = { Text("No memory card") }, onClick = { close(); m.chooseSd(null) })
                    m.drives.filter { it.root != dev }.forEach { d ->
                        DropdownMenuItem(text = { Text("${d.label} · ${driveSub(d)}") }, onClick = { close(); m.chooseSd(d) },
                            leadingIcon = { Icon(Icons.Outlined.SdCard, null) })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Choose a card by hand…") },
                        onClick = { close(); chooseFolder("The memory card's drive")?.let { m.addDrive(it, true) } })
                }
            }
            if (m.device == DeviceChoice.Folder) {
                val limits = listOf<Long?>(null, NUVI_2GB_MAX_FILE, FAT32_MAX_FILE)
                fun t(l: Long?) = when (l) { null -> "No"; NUVI_2GB_MAX_FILE -> "Into files under 2 GB"; else -> "Into files under 4 GB" }
                MenuButton(Icons.Outlined.CallSplit, "Split large maps", t(m.folderLimit), free) { close ->
                    limits.forEach { l -> DropdownMenuItem(text = { Text(t(l)) }, onClick = { close(); m.folderLimit = l }) }
                }
            }
            Spacer(Modifier.weight(1f))
            if (m.drives.none { it.isGarmin } && m.device !is DeviceChoice.OnDrive) {
                Text("No Garmin found. Plug it in with a USB cable, or put its memory card in a card reader.",
                    style = MaterialTheme.typography.bodyMedium, color = hint, modifier = Modifier.widthIn(max = 360.dp))
            }
            TooltipArea(tooltip = {
                Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.onSurface) {
                    Text("Look for devices again", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.surface, style = MaterialTheme.typography.bodyMedium)
                }
            }) {
                IconButton(onClick = { m.refreshDrives() }, enabled = free) { Icon(Icons.Outlined.Refresh, "Look for devices again") }
            }
        }
    }
}

@Composable
private fun DeviceMenu(m: MapsModel, close: () -> Unit) {
    m.drives.forEach { d ->
        DropdownMenuItem(
            text = { Text("${d.xml?.model?.ifEmpty { null } ?: d.label} · ${driveSub(d)}" + if (d.xml == null) " (model unknown)" else "") },
            leadingIcon = { Icon(if (d.isGarmin) Icons.Outlined.Usb else Icons.Outlined.SdCard, null) },
            onClick = { close(); m.chooseDevice(DeviceChoice.OnDrive(d)) })
    }
    DropdownMenuItem(text = { Text("GPSMAP 276C, 278 or 478 (data card)") }, leadingIcon = { Icon(Icons.Outlined.SdStorage, null) },
        onClick = { close(); m.chooseDevice(DeviceChoice.DataCard) })
    DropdownMenuItem(text = { Text("No device: save to a folder") }, leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) },
        onClick = { close(); m.chooseDevice(DeviceChoice.Folder) })
    HorizontalDivider()
    DropdownMenuItem(text = { Text("Choose a drive by hand…") },
        onClick = { close(); chooseFolder("The device's drive (the one with the Garmin folder)")?.let { m.addDrive(it, false) } })
}

// ---- step 1: build ----

private fun labelsName(l: Labels) = when (l) {
    Labels.LATIN1_MIXED_CASE -> "Normal text"; Labels.LATIN1 -> "Upper case with accents"; Labels.ASCII -> "Upper case, no accents"
}

@Composable
fun BuildChooser(m: MapsModel, modifier: Modifier) {
    val adv = m.region?.let { m.advice(estimateImgBytes(it), m.opts.name) }
    val fits = adv?.options?.filterIsInstance<InstallOption.SmallerZone>()?.firstOrNull()?.maxBytes
    var options by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(m.search, { m.search = it }, Modifier.fillMaxWidth(), label = { Text("Find a country or region") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true)
        if (m.search.isBlank()) {
            val chain = generateSequence(m.regions.firstOrNull { it.id == m.parent }) { r -> m.regions.firstOrNull { it.id == r.parent } }.toList().reversed()
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { m.parent = "" }) { Text("All regions") }
                chain.forEach { r ->
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = hint, modifier = Modifier.size(16.dp))
                    TextButton(onClick = { m.parent = r.id }) { Text(r.name) }
                }
            }
        }
        val list = if (m.search.isBlank()) m.regions.childrenOf(m.parent)
        else m.regions.filter { it.name.contains(m.search.trim(), true) }.sortedBy { it.name }
        Surface(Modifier.weight(1f), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            if (list.isEmpty()) EmptyState(Icons.Outlined.Search, "No region by that name", "Try the country name in English, like \"Portugal\" or \"Germany\".")
            LazyColumn(Modifier.padding(4.dp)) {
                items(list, key = { it.id }) { r ->
                    val kids = m.regions.count { it.parent == r.id }
                    val est = estimateImgBytes(r)
                    val sel = m.region?.id == r.id
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .selectable(sel, role = Role.RadioButton) { if (kids > 0 && m.search.isBlank()) m.open(r) else m.pick(r) }
                        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                        if (fits != null && r.bytes > 0 && est <= fits) {
                            Icon(Icons.Outlined.CheckCircle, null, tint = Tone.OK.fg, modifier = Modifier.size(16.dp))
                            Text(" Fits   ", style = MaterialTheme.typography.labelLarge, color = Tone.OK.fg)
                        }
                        if (r.bytes > 0) Text(size(est), style = MaterialTheme.typography.bodyMedium.merge(tnum), color = hint)
                        if (kids > 0) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Open", tint = hint, modifier = Modifier.padding(start = 8.dp))
                        else Spacer(Modifier.width(32.dp))
                    }
                }
            }
        }
        Text("Sizes are estimates of the finished map. Open a region to pick a smaller area inside it.",
            style = MaterialTheme.typography.bodyMedium, color = hint)
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Tune, null, tint = hint)
            Spacer(Modifier.width(10.dp))
            val o = m.opts
            Text("Map options: " + listOf(
                when (o.routing) { Routing.CAR -> "Car directions"; Routing.BIKE -> "Bike directions"; Routing.NONE -> "No directions" },
                labelsName(o.labels),
                if (o.style == MapStyle.CLEAN) "Tidy places" else "Every place",
            ).joinToString(" · ") + (if (o.index) " · Address search" else "") + (if (o.transitStops) " · Bus stops" else ""),
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { options = true }) { Text("Change") }
        }
    }
    if (options) OptionsDialog(m) { options = false }
}

@Composable
private fun OptionsDialog(m: MapsModel, close: () -> Unit) {
    val o = m.opts
    val dev = m.deviceName
    AlertDialog(onDismissRequest = close, confirmButton = { Button(onClick = close) { Text("Done") } },
        title = { Text("Map options") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).widthIn(min = 480.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Directions", style = MaterialTheme.typography.titleSmall)
                Radio(o.routing == Routing.CAR, "Car", "Turn-by-turn directions for driving.") { m.opts = o.copy(routing = Routing.CAR) }
                Radio(o.routing == Routing.BIKE, "Bicycle", "Prefers cycle paths and quiet roads. On a nüvi or 276C, choose Car on the device and it follows these routes.") {
                    m.opts = o.copy(routing = Routing.BIKE)
                }
                Radio(o.routing == Routing.NONE, "None", "Smaller map. Shows roads but gives no directions.") { m.opts = o.copy(routing = Routing.NONE) }
                Spacer(Modifier.height(12.dp))
                Text("Text on the map", style = MaterialTheme.typography.titleSmall)
                for (l in listOf(Labels.LATIN1_MIXED_CASE, Labels.LATIN1, Labels.ASCII)) {
                    val rec = if (l == m.recommendedLabels && dev != null) "Recommended for the $dev. " else ""
                    val h = when (l) {
                        Labels.LATIN1_MIXED_CASE -> "Like é, ü and ñ. For newer units: nüvi, eTrex, Oregon, Edge."
                        Labels.LATIN1 -> "For units that show accents but no lower case."
                        Labels.ASCII -> "For the 276C and older units."
                    }
                    Radio(o.labels == l, labelsName(l), rec + h) { m.setLabels(l) }
                }
                Spacer(Modifier.height(12.dp))
                Text("Places", style = MaterialTheme.typography.titleSmall)
                Radio(o.style == MapStyle.CLEAN && o.styleDir == null, "Tidy places",
                    "Shops, fuel and food sorted into the Garmin Where To? menus. Less clutter.") { m.opts = o.copy(style = MapStyle.CLEAN, styleDir = null) }
                Radio(o.style == MapStyle.MKGMAP_DEFAULT && o.styleDir == null, "Every place", "Every place in the map data, unsorted.") {
                    m.opts = o.copy(style = MapStyle.MKGMAP_DEFAULT, styleDir = null)
                }
                Check(o.transitStops, "Bus and tram stops", "Adds many points in cities. Works with Tidy places only.") { m.opts = o.copy(transitStops = it) }
                Check(o.index, "Address search", "Lets you search addresses and places. Turn off only to make the map smaller.") { m.opts = o.copy(index = it) }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(o.name, { v -> m.opts = o.copy(name = v, description = v.replace('_', ' ')) }, Modifier.fillMaxWidth(),
                    label = { Text("File name on the device") }, singleLine = true)
            }
        })
}

// ---- step 1: download ----

@Composable
fun DownloadChooser(m: MapsModel, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PremadeSource.entries.forEach { s ->
                FilterChip(m.source == s, { m.chooseSource(s) }, label = { Text(s.title) }, enabled = m.busy == null,
                    leadingIcon = if (m.source == s) { { Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) } } else null)
            }
        }
        Text(m.source.about, style = MaterialTheme.typography.bodyMedium, color = hint)
        OutlinedTextField(m.premadeSearch, { m.premadeSearch = it }, Modifier.fillMaxWidth(), label = { Text("Find a map") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true)
        val list = m.premade.filter { p ->
            m.premadeSearch.isBlank() || listOf(p.name, p.group, p.variant).any { it.contains(m.premadeSearch.trim(), true) }
        }.sortedWith(compareBy({ it.group }, { it.name }, { it.variant }))
        Surface(Modifier.weight(1f), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            when {
                m.premade.isEmpty() && m.busy != null -> EmptyState(Icons.Outlined.CloudDownload, "Loading the map list…", "This takes a few seconds.")
                m.premade.isEmpty() -> EmptyState(Icons.Outlined.CloudOff, "We couldn't load the map list",
                    "Check your internet connection, then choose the source again.")
                list.isEmpty() -> EmptyState(Icons.Outlined.Search, "No map by that name", "Try a country or city name in English.")
            }
            LazyColumn(Modifier.padding(4.dp)) {
                items(list, key = { it.id }) { p ->
                    val sel = m.picked == p
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .selectable(sel, role = Role.RadioButton) { m.pickPremade(p) }
                        .padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                            Text("${p.variant} · ${p.group}", style = MaterialTheme.typography.bodyMedium, color = hint)
                        }
                        p.bytes?.let { Text(size(it), style = MaterialTheme.typography.bodyMedium.merge(tnum), color = hint) }
                    }
                }
            }
        }
    }
}

// ---- steps 2 and 3: fit and install ----

@Composable
fun RightColumn(m: MapsModel, modifier: Modifier) {
    var replacing by remember { mutableStateOf<Pair<Storage, SlotChoice>?>(null) }
    Surface(modifier, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                m.done?.let { StatusRow(Tone.OK, it) }
                if (m.tab == 0) BuildFit(m) else DownloadFit(m)
                val r = m.ready
                if (r != null && m.readyHere) {
                    HorizontalDivider()
                    StepHeading(3, m.deviceName?.let { "Put it on your $it" } ?: "Save it")
                    InstallSection(m, r) { replacing = it }
                } else if (r != null) {
                    StatusRow(Tone.INFO, "${r.title} is ready to install. Choose it again on the left to install it.")
                }
            }
            HorizontalDivider()
            ActionBar(m)
        }
    }
    replacing?.let { (st, c) ->
        val old = m.installed.firstOrNull { it.storage == st && it.path.equals(c.path, true) }
        val name = old?.info?.description?.ifEmpty { null }?.let { "\"$it\"" } ?: "the map"
        AlertDialog(onDismissRequest = { replacing = null },
            title = { Text("Replace this map?") },
            text = { Text("$name (${c.path}) on the ${storageName(st)} will be replaced. NaviLink saves a copy of it on this computer first.") },
            confirmButton = {
                Button(onClick = { replacing = null; m.ready?.let { r -> m.install(listOf(Copy(r.files.first(), st, c.path))) } },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Replace map") }
            },
            dismissButton = { TextButton(onClick = { replacing = null }) { Text("Cancel") } })
    }
}

/** The fit verdict for an advice: one status row, then the plain summary, details on request. */
@Composable
private fun FitStatus(m: MapsModel, adv: Advice, estimate: Long, canDivide: Boolean) {
    val dev = m.deviceName ?: "device"
    val first = adv.options.firstOrNull()
    when {
        first is InstallOption.OneFile -> StatusRow(Tone.OK,
            "Fits " + (first.plan.placements.firstOrNull()?.let { if (m.device == DeviceChoice.DataCard) "on the data card" else "on the ${storageName(it.storage)}" } ?: "") + ".")
        first is InstallOption.Divide && canDivide -> StatusRow(Tone.WARN, "Too big for one map on the $dev (about ${size(estimate)}). It can be split into ${first.parts} maps.")
        first == null -> StatusRow(Tone.BAD, "This map doesn't fit: the $dev has no room for another map. Replace a map after it's ready, or save it to a folder.")
        else -> {
            val max = adv.options.filterIsInstance<InstallOption.SmallerZone>().firstOrNull()?.maxBytes
            StatusRow(Tone.BAD, "Too big for the $dev (about ${size(estimate)}" + (max?.let { "; up to ${size(it)} fits" } ?: "") + "). " +
                if (canDivide) "Choose a smaller region." else "Choose a smaller map, or build this region on the Build tab.")
        }
    }
    adv.summary.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = hint) }
    var details by remember { mutableStateOf(false) }
    TextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp)) { Text(if (details) "Hide where maps go" else "Show where maps go") }
    if (details) adv.howItWorks.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = hint) }
}

/** "Both parts go on the built-in memory." or "Part 1 goes on the memory card, part 2 on the built-in memory." */
private fun splitWhere(plan: SplitPlan): String {
    val by = plan.placements.groupBy { it.storage }
    if (by.size == 1) return "${if (plan.placements.size == 2) "Both parts" else "All ${plan.placements.size} parts"} go on the ${storageName(by.keys.first())}."
    return by.entries.joinToString("; ") { (st, ps) ->
        (if (ps.size == 1) "part ${ps[0].part}" else "parts ${ps.joinToString(", ") { "${it.part}" }}") + " on the ${storageName(st)}"
    }.replaceFirstChar { it.uppercase() } + "."
}

@Composable
private fun BuildFit(m: MapsModel) {
    val r = m.region ?: run {
        EmptyState(Icons.Outlined.Map, "No region chosen", "Choose a country or region on the left to see if it fits" +
            (m.deviceName?.let { " your $it." } ?: "."))
        return
    }
    val est = estimateImgBytes(r)
    StepHeading(2, "Will it fit?")
    Text("${r.name} · about ${size(est)}", style = MaterialTheme.typography.bodyLarge)
    val adv = m.advice(est, m.opts.name)
    if (adv == null) {
        StatusRow(Tone.INFO, "No device chosen: you save the map to a folder" + (m.folderLimit?.let { ", in files under ${size(it + 1)}" } ?: "") + ".")
        return
    }
    FitStatus(m, adv, est, canDivide = true)
    if (adv.options.size > 1 || adv.options.firstOrNull() !is InstallOption.OneFile) {
        adv.options.forEachIndexed { i, opt ->
            when (opt) {
                is InstallOption.OneFile -> Radio(m.choice == i, "One map", "Goes on the ${opt.plan.placements.firstOrNull()?.storage?.let(::storageName)}.") { m.choice = i }
                is InstallOption.Divide -> Radio(m.choice == i, "Split into ${opt.parts} maps", splitWhere(opt.plan)) { m.choice = i }
                is InstallOption.SmallerZone -> Radio(m.choice == i, "Choose a smaller region",
                    "Up to ${size(opt.maxBytes)} fits. Regions marked Fits will work.") { m.choice = i }
            }
        }
    }
    (adv.options.getOrNull(m.choice) as? InstallOption.Divide)?.plan?.warnings?.forEach { StatusRow(Tone.WARN, it) }
}

@Composable
private fun DownloadFit(m: MapsModel) {
    val p = m.picked ?: run {
        EmptyState(Icons.Outlined.CloudDownload, "No map chosen", "Choose a map on the left to see if it fits" +
            (m.deviceName?.let { " your $it." } ?: "."))
        return
    }
    StepHeading(2, "Will it fit?")
    Text("${p.source.title} ${p.name}", style = MaterialTheme.typography.bodyLarge)
    Text("${p.variant.replaceFirstChar { it.uppercase() }} · download ${size(m.pickedBytes)}" + (p.updated?.let { " · updated $it" } ?: ""),
        style = MaterialTheme.typography.bodyMedium, color = hint)
    val est = m.pickedBytes?.let { (it * 1.35).toLong() }
    val adv = est?.let { m.advice(it, p.fileStem) }
    if (adv != null) FitStatus(m, adv, est, canDivide = false)
    if (p.source == PremadeSource.BBBIKE) StatusRow(Tone.INFO, "Only one BBBike map shows on a device at a time: a new one replaces the old one.")
    if (p.variant.startsWith("contour")) StatusRow(Tone.INFO, "Contour lines only. Install them next to the OpenTopoMap map of the same country.")
    SelectionContainer { Text("From ${p.source.site}", style = MaterialTheme.typography.bodyMedium, color = hint) }
}

@Composable
private fun InstallSection(m: MapsModel, r: Ready, onReplace: (Pair<Storage, SlotChoice>) -> Unit) {
    Text(r.title + " · " + size(r.files.sumOf { it.length() }), style = MaterialTheme.typography.bodyLarge)
    when (m.device) {
        DeviceChoice.Folder -> Text("Save it to a folder. Later, copy it to the Garmin folder of the device or its memory card.",
            style = MaterialTheme.typography.bodyMedium, color = hint)
        DeviceChoice.DataCard -> {
            listOf("Save the map with the button below.", "On a Windows PC, connect the 276C and open Garmin WebUpdater.",
                "Choose the saved map file. The unit writes it to the data card.").forEachIndexed { i, s ->
                Text("${i + 1}. $s", style = MaterialTheme.typography.bodyLarge)
            }
            StatusRow(Tone.WARN, "This replaces the map on the card now. The card must be bigger than ${size(r.files.sumOf { it.length() })}.")
            if (r.files.size > 1) StatusRow(Tone.BAD, "The data card holds one map. Build the region as one file.")
            var cmd by remember { mutableStateOf(false) }
            TextButton(onClick = { cmd = !cmd }, contentPadding = PaddingValues(0.dp)) { Text(if (cmd) "Hide the command" else "Show the command for advanced users") }
            if (cmd) SelectionContainer {
                Text("WebUpdater.exe /upload_file rgn=10 path=\"<saved file>\" -autorun usb /i", fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        is DeviceChoice.OnDrive -> {
            val plan = m.plan(r)
            val clashes = familyClashes(m.installed, r.families)
            if (plan.ok) {
                plan.placements.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(if (p.storage == Storage.SD) Icons.Outlined.SdCard else Icons.Outlined.Usb, null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text("Goes on the ${storageName(p.storage)}" + (m.storage[p.storage]?.freeBytes?.let { " (${size(it)} free)" } ?: ""),
                                style = MaterialTheme.typography.bodyLarge)
                            Text(p.path + (p.note?.let { ": $it" } ?: ""), style = MaterialTheme.typography.bodyMedium, color = hint)
                        }
                    }
                }
                (plan.warnings + clashes).forEach { StatusRow(Tone.WARN, it) }
            } else plan.problems.forEach { StatusRow(Tone.BAD, it) }
            // One map can replace one already there (backed up first), e.g. on units that read only gmapsupp.img.
            if (r.files.size == 1) {
                val swaps = m.storage.flatMap { (st, s) ->
                    planInstall(m.profile, st, r.name, r.files.first().length(), s.existing).replaceable.map { st to it }
                }.filter { (_, c) -> c.slot.role != SlotRole.FACTORY || !plan.ok }
                if (swaps.isNotEmpty()) {
                    Text(if (plan.ok) "Or replace a map (the old one is saved on this computer first):" else "Replace a map (the old one is saved on this computer first):",
                        style = MaterialTheme.typography.titleSmall)
                    swaps.forEach { (st, c) ->
                        val old = m.installed.firstOrNull { it.storage == st && it.path.equals(c.path, true) }
                        OutlinedButton(onClick = { onReplace(st to c) }, enabled = m.busy == null, modifier = Modifier.fillMaxWidth()) {
                            Text("Replace " + (old?.info?.description?.ifEmpty { null }?.let { "\"$it\"" } ?: c.path.substringAfterLast('/')) +
                                " on the ${storageName(st)}" + (old?.let { " (${size(it.bytes)})" } ?: ""), maxLines = 2)
                        }
                    }
                }
            }
            TextButton(onClick = { chooseFolder()?.let { m.saveTo(it, r) } }, enabled = m.busy == null,
                contentPadding = PaddingValues(0.dp)) { Text("Save a copy to a folder…") }
        }
    }
}

/** The only filled button in the window, always visible at the bottom of the right column. */
@Composable
private fun ActionBar(m: MapsModel) {
    val r = m.ready
    val dev = m.deviceName
    var label: String
    var enabled = m.busy == null
    var caption: String? = null
    var action: () -> Unit = {}
    when {
        r != null && m.readyHere -> when (m.device) {
            DeviceChoice.Folder -> { label = "Save map to a folder…"; action = { chooseFolder()?.let { m.saveTo(it, r) } } }
            DeviceChoice.DataCard -> { label = "Save map for WebUpdater…"; action = { chooseFolder()?.let { m.saveTo(it, r) } } }
            is DeviceChoice.OnDrive -> {
                val plan = m.plan(r)
                label = "Install on the $dev"
                enabled = enabled && plan.ok
                action = { m.install(plan.placements.map { Copy(r.files[it.part - 1], it.storage, it.path) }) }
                if (!plan.ok) caption = "No room as it is: replace a map above."
            }
        }
        m.tab == 0 -> {
            val reg = m.region
            label = reg?.let { "Build ${it.name} map" } ?: "Build map"
            val adv = reg?.let { m.advice(estimateImgBytes(it), m.opts.name) }
            val chosen = adv?.options?.getOrNull(m.choice)
            enabled = enabled && reg != null && (adv == null || adv.options.isEmpty() || chosen !is InstallOption.SmallerZone)
            caption = reg?.let(::buildTime)
            action = m::build
        }
        else -> {
            label = "Download map"
            enabled = enabled && m.picked != null
            caption = m.pickedBytes?.let { "${size(it)} to download." }
            action = m::download
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val building = m.busy?.startsWith("Building") == true
            if (m.busy != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("${m.busy}…", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (building) OutlinedButton(onClick = m::stop) { Text("Stop") }
                }
            } else {
                Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(label) }
                caption?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = hint) }
            }
        }
    }
}

// ---- status bar and log ----

@Composable
private fun StatusBar(m: MapsModel) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            if (m.busy != null) LinearProgressIndicator(Modifier.width(120.dp).padding(end = 12.dp))
            Text(m.log.lastOrNull()?.trim() ?: "Ready.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = hint,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { m.showLog = !m.showLog }) { Text(if (m.showLog) "Hide details" else "Show details") }
        }
    }
}

@Composable
fun LogPanel(m: MapsModel, modifier: Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(m.log.size) { if (m.log.isNotEmpty()) state.scrollToItem(m.log.size - 1) }
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        SelectionContainer {
            LazyColumn(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), state = state) {
                items(m.log.size) { i -> Text(m.log[i], fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

private fun chooseFolder(title: String = "Save the map to"): File? {
    val c = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; dialogTitle = title }
    return if (c.showDialog(null, "Choose") == JFileChooser.APPROVE_OPTION) c.selectedFile else null
}
