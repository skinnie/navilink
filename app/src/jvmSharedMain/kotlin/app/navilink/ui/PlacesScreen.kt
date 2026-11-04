@file:OptIn(ExperimentalMaterial3Api::class)

package app.navilink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.navilink.nuvi.Place
import java.util.Locale

/**
 * What the Places page shows and does. Android implements it with Bluetooth and the USB drive;
 * the desktop tests with a fake, so the page can be rendered and checked off the phone.
 */
interface PlacesPage {
    val places: List<Place>
    val selected: List<Int>
    fun select(i: Int, on: Boolean)
    /** Send by cable (USB) instead of Bluetooth. */
    var cable: Boolean
    val busy: Boolean
    // Bluetooth
    /** Paired devices: name and address. */
    val devices: List<Pair<String, String>>
    /** The device in use, or null. */
    val device: Pair<String, String>?
    fun chooseDevice(address: String)
    val connected: Boolean
    val status: String
    val listening: Boolean
    fun connect()
    fun disconnect()
    fun toggleListen()
    fun ping()
    // Cable
    val driveName: String?
    fun pickDrive()
    fun removeCableFile()
    // Places
    fun add(text: String)
    fun openFile()
    fun saveGpx()
    fun clear()
    /** One place: drive there (Bluetooth) or save it (cable). Several: a route. */
    fun send(stops: List<Place>)
    var category: String
    fun installPois()
    fun saveGpi()
    val log: List<String>
}

/** The Maps icon (a folded map); not in the small icon set the app ships. */
val FoldedMap: ImageVector by lazy {
    ImageVector.Builder("FoldedMap", 24.dp, 24.dp, 24f, 24f).path(
        stroke = SolidColor(androidx.compose.ui.graphics.Color.Black), strokeLineWidth = 1.8f, strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(3.5f, 6.5f); lineTo(9f, 4.5f); lineTo(15f, 6.5f); lineTo(20.5f, 4.5f); lineTo(20.5f, 17.5f)
        lineTo(15f, 19.5f); lineTo(9f, 17.5f); lineTo(3.5f, 19.5f); close()
        moveTo(9f, 4.5f); lineTo(9f, 17.5f); moveTo(15f, 6.5f); lineTo(15f, 19.5f)
    }.build()
}

/** The app frame: a title bar and Places / Maps at the bottom (a rail on wide screens). */
@Composable
fun AppShell(page: Int, onPage: (Int) -> Unit, modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    val items = listOf("Places" to Icons.Outlined.Place, "Maps" to FoldedMap)
    BoxWithConstraints(modifier.fillMaxSize().background(navi.background)) {
        if (maxWidth >= 720.dp) Row(Modifier.fillMaxSize()) {
            NavigationRail(Modifier.fillMaxHeight(), containerColor = navi.card, header = { Spacer(Modifier.height(Space.small)) }) {
                items.forEachIndexed { i, (label, icon) ->
                    NavigationRailItem(page == i, { onPage(i) }, icon = { Icon(icon, null) }, label = { Text(label) },
                        colors = NavigationRailItemDefaults.colors(indicatorColor = navi.primarySoft, selectedIconColor = navi.primary,
                            selectedTextColor = navi.text, unselectedIconColor = navi.muted, unselectedTextColor = navi.muted))
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(navi.border))
            content(Modifier.weight(1f).fillMaxHeight().padding(Space.medium))
        } else Column(Modifier.fillMaxSize()) {
            Text(items[page].first, style = MaterialTheme.typography.headlineSmall, color = navi.text,
                modifier = Modifier.padding(start = Space.medium, end = Space.medium, top = Space.medium, bottom = Space.small))
            content(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.medium).padding(bottom = 12.dp))
            HorizontalDivider(color = navi.border)
            NavigationBar(containerColor = navi.card, tonalElevation = 0.dp) {
                items.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(page == i, { onPage(i) }, icon = { Icon(icon, null) }, label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = navi.primarySoft, selectedIconColor = navi.primary,
                            selectedTextColor = navi.text, unselectedIconColor = navi.muted, unselectedTextColor = navi.muted))
                }
            }
        }
    }
}

/** The nüvi: Bluetooth or cable, which one, and its state. */
@Composable
private fun NuviCard(p: PlacesPage) {
    var more by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf(false) }
    NaviCard(Modifier.fillMaxWidth()) {
        val title = if (p.cable) "nüvi" else p.device?.first ?: "nüvi"
        CardTitle(title, status = if (p.cable) null else p.status.removePrefix("Connected to ${p.device?.first} ").let {
            if (p.connected) "Connected" + (if (p.listening) ", listening" else "") to Tone.OK else "Not connected" to Tone.MUTED
        }) {
            Box {
                IconButton({ more = true }) { Icon(Icons.Outlined.MoreVert, "More", tint = navi.muted) }
                DropdownMenu(more, { more = false }, containerColor = navi.card) {
                    if (p.cable) DropdownMenuItem({ Text("Remove NaviLink.gpx from the nüvi") }, { more = false; p.removeCableFile() })
                    else {
                        DropdownMenuItem({ Text(if (p.listening) "Stop listening (GarminLink)" else "Listen for the nüvi (GarminLink)") },
                            { more = false; p.toggleListen() })
                        DropdownMenuItem({ Text("Ping") }, { more = false; p.ping() })
                        if (p.connected) DropdownMenuItem({ Text("Disconnect") }, { more = false; p.disconnect() })
                    }
                }
            }
        }
        Segmented(listOf("Bluetooth", "Cable (USB)"), if (p.cable) 1 else 0, { p.cable = it == 1 }, Modifier.fillMaxWidth())
        if (p.cable) {
            NestedRow(p.driveName ?: "No drive picked", "Plug the nüvi in with a USB OTG cable.") {
                TextAction(if (p.driveName == null) "Pick" else "Change", { p.pickDrive() }, enabled = !p.busy)
            }
            Text("Places go to Saved and routes to Trip Planner when the nüvi next starts.",
                style = MaterialTheme.typography.bodySmall, color = navi.muted)
        } else {
            Box {
                NestedRow(if (p.device == null) "No nüvi picked" else "Paired device",
                    p.device?.let { "${it.first}  ·  ${it.second}" } ?: "Pair it in Android's Bluetooth settings first.") {
                    IconButton({ pick = true }) { Icon(Icons.Outlined.ArrowDropDown, "Choose the nüvi", tint = navi.muted) }
                }
                DropdownMenu(pick, { pick = false }, containerColor = navi.card) {
                    if (p.devices.isEmpty()) DropdownMenuItem({ Text("No paired devices") }, { pick = false })
                    p.devices.forEach { (n, a) -> DropdownMenuItem({ Text("$n  ·  $a") }, { pick = false; p.chooseDevice(a) }) }
                }
            }
            if (!p.connected) SecondaryButton("Connect", { p.connect() }, Modifier.fillMaxWidth(), enabled = !p.busy && p.device != null)
        }
    }
}

/** Typing or opening places. */
@Composable
private fun AddCard(p: PlacesPage) {
    var text by remember { mutableStateOf("") }
    NaviCard(Modifier.fillMaxWidth()) {
        CardTitle("Add places", "Paste a Google Maps link, or share a place from Google Maps to NaviLink.")
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Link, coordinates or address", color = navi.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = {
                IconButton({ val t = text; text = ""; p.add(t) }, enabled = text.isNotBlank()) { Icon(Icons.Outlined.Add, "Add", tint = if (text.isNotBlank()) navi.text else navi.muted) }
            },
            shape = RoundedCornerShape(Radius.small),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = navi.border, focusedBorderColor = navi.primary,
                unfocusedContainerColor = navi.nested, focusedContainerColor = navi.card))
        TextAction("Open a GPX or GPI file", { p.openFile() }, modifier = Modifier.offset(x = (-10).dp))
    }
}

@Composable
private fun PlacesList(p: PlacesPage, modifier: Modifier, lazy: Boolean) {
    var more by remember { mutableStateOf(false) }
    NaviCard(modifier.fillMaxWidth()) {
        CardTitle("Places", if (p.places.isEmpty()) null else
            if (p.selected.isEmpty()) "${p.places.size} in the list. Tick two or more to send a route." else "${p.selected.size} of ${p.places.size} ticked") {
            if (p.places.isNotEmpty()) Box {
                IconButton({ more = true }) { Icon(Icons.Outlined.MoreVert, "More", tint = navi.muted) }
                DropdownMenu(more, { more = false }, containerColor = navi.card) {
                    DropdownMenuItem({ Text("Save as a GPX file") }, { more = false; p.saveGpx() })
                    DropdownMenuItem({ Text("Clear the list") }, { more = false; p.clear() })
                }
            }
        }
        if (p.places.isEmpty()) {
            Text("No places yet.", style = MaterialTheme.typography.bodyMedium, color = navi.muted)
            ActivityLine(p)
            return@NaviCard
        }
        if (lazy) LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            itemsIndexed(p.places) { i, pl -> PlaceRow(p, i, pl) }
        } else Column { p.places.forEachIndexed { i, pl -> PlaceRow(p, i, pl) } }
        ActivityLine(p)
    }
}

@Composable
private fun PlaceRow(p: PlacesPage, i: Int, pl: Place) {
    val on = i in p.selected
    if (i > 0) HorizontalDivider(color = navi.border, modifier = Modifier.padding(start = 52.dp))
    ListRow({ p.select(i, !on) }) {
        Checkbox(on, null, colors = CheckboxDefaults.colors(checkedColor = navi.primary, uncheckedColor = navi.borderStrong))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(pl.name, style = MaterialTheme.typography.bodyLarge, color = navi.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("%.5f, %.5f".format(Locale.ROOT, pl.lat, pl.lon),
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = navi.muted)
                pl.category?.takeIf { it.isNotBlank() }?.let { Pill(it) }
            }
        }
        TextAction(if (p.cable) "Save" else "Drive here", { p.send(listOf(pl)) }, enabled = !p.busy)
    }
}

/** Custom POIs: the category name and where they go. */
@Composable
private fun PoiDialog(p: PlacesPage, count: Int, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = navi.card, shape = RoundedCornerShape(Radius.card),
        title = { Text("Add $count as custom POIs", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("They show on the nüvi under Where To? › Extras › Custom POIs, in this category:",
                    style = MaterialTheme.typography.bodyMedium, color = navi.muted)
                OutlinedTextField(p.category, { p.category = it }, Modifier.fillMaxWidth(), singleLine = true,
                    shape = RoundedCornerShape(Radius.small), label = { Text("Category") })
                TextAction("Save a .gpi file instead", { onDismiss(); p.saveGpi() })
            }
        },
        confirmButton = { PrimaryButton("Put on the nüvi (cable)", { onDismiss(); p.installPois() }) },
        dismissButton = { TextAction("Cancel", onDismiss) })
}

/** The latest line of the activity log; tap for the whole log. */
@Composable
private fun ActivityLine(p: PlacesPage) {
    var open by remember { mutableStateOf(false) }
    val last = p.log.firstOrNull() ?: return
    HorizontalDivider(color = navi.border)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { StatusLine(last.substringAfter("  ")) }
        TextAction("Log", { open = true })
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, containerColor = navi.card, shape = RoundedCornerShape(Radius.card),
        title = { Text("Activity", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                p.log.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = navi.text) }
            }
        },
        confirmButton = { TextAction("Close", { open = false }) })
}

/** The Places page: the nüvi, adding places, the list, and sending. */
@Composable
fun PlacesScreen(p: PlacesPage, modifier: Modifier = Modifier) {
    var poi by remember { mutableStateOf(false) }
    val chosen = if (p.selected.isEmpty()) p.places.size else p.selected.size
    @Composable fun Actions() = Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
        if (p.places.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            SecondaryButton(if (p.selected.isEmpty()) "Add all as POIs" else "Add as POIs", { poi = true }, Modifier.weight(1f), enabled = !p.busy)
            val stops = p.selected.sorted().mapNotNull { p.places.getOrNull(it) }
            PrimaryButton(if (stops.size >= 2) "Send ${stops.size} as a route" else "Send as a route", { p.send(stops) },
                Modifier.weight(1f), enabled = !p.busy && stops.size >= 2)
        }
    }
    BoxWithConstraints(modifier) {
        val gap = Arrangement.spacedBy(12.dp)
        if (maxWidth >= 720.dp) Row(horizontalArrangement = Arrangement.spacedBy(Space.medium)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = gap) {
                NuviCard(p)
                AddCard(p)
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = gap) {
                PlacesList(p, Modifier.weight(1f), lazy = true)
                Actions()
            }
        } else Column(verticalArrangement = gap) {
            // Phones: the page scrolls as one, so the list is never squeezed under the cards above it.
            ScrollPage(Modifier.weight(1f)) { NuviCard(p); AddCard(p); PlacesList(p, Modifier, lazy = false) }
            Actions()
        }
    }
    if (poi) PoiDialog(p, chosen) { poi = false }
}
