@file:OptIn(ExperimentalMaterial3Api::class)

package app.navilink.maps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.navilink.ui.*
import osmimg.*

/** The Garmin and its memory card: what is plugged in and how much room there is. */
@Composable
private fun DeviceCard(m: PhoneMaps) {
    NaviCard(Modifier.fillMaxWidth()) {
        val d = m.drive
        if (d == null) {
            CardTitle("nüvi", "Plug it into this phone with a USB OTG cable. When Android shows it, pick its drive.")
            SecondaryButton("Pick the nüvi's drive", { m.pickDrive(false) }, Modifier.fillMaxWidth())
            return@NaviCard
        }
        CardTitle(m.deviceName) { TextAction("Change", { m.pickDrive(false) }, enabled = m.busy == null) }
        StorageRow("Built-in memory", m, Storage.INTERNAL, d)
        val c = m.card
        when {
            c != null -> StorageRow("Memory card", m, Storage.SD, c) { TextAction("Forget", { m.removeCard() }, enabled = m.busy == null) }
            m.profile.sd.isNotEmpty() -> NestedRow("Memory card", "Not picked. Android often shows only the built-in memory.") {
                TextAction("Add", { m.pickDrive(true) }, enabled = m.busy == null)
            }
        }
    }
}

@Composable
private fun StorageRow(label: String, m: PhoneMaps, st: Storage, fs: DriveFs, action: @Composable () -> Unit = {}) {
    val maps = m.installed.count { it.storage == st }
    NestedRow(label, listOfNotNull(m.storage[st]?.freeBytes?.let { "${size(it)} free" },
        if (maps == 0) "no maps" else "$maps ${if (maps == 1) "map" else "maps"}").joinToString("  ·  "), action = action)
}

/** Which map: one from a site, or a file on the phone. */
@Composable
private fun MapCard(m: PhoneMaps, modifier: Modifier, collapse: Boolean) {
    NaviCard(modifier.fillMaxWidth()) {
        CardTitle("Map")
        Segmented(listOf("Download", "File on this phone"), m.tab, { m.tab = it }, Modifier.fillMaxWidth())
        if (m.tab == 1) {
            val f = m.own
            if (f == null) Text("Any Garmin map (.img) works: one you downloaded, or one made on a PC with NaviLink.",
                style = MaterialTheme.typography.bodyMedium, color = navi.muted)
            else PickedRow(f.name, listOfNotNull(f.info?.description?.ifEmpty { null }, size(f.bytes)).joinToString("  ·  "))
            SecondaryButton(if (f == null) "Choose an .img file" else "Choose another file", { m.pickOwn() },
                Modifier.fillMaxWidth(), enabled = m.busy == null)
            return@NaviCard
        }
        val p = m.picked
        if (p != null && collapse) {
            // Phones: once a map is picked the list folds away, to leave room for what comes next.
            PickedRow(p.name, listOfNotNull(p.source.title, p.variant, m.pickedBytes?.let { size(it) }).joinToString("  ·  ")) {
                TextAction("Change", { m.pickPremade(null) }, enabled = m.busy == null)
            }
            return@NaviCard
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            PremadeSource.entries.forEach { s -> SourceChip(s.title, m.source == s) { m.chooseSource(s) } }
        }
        Text(m.source.about, style = MaterialTheme.typography.bodySmall, color = navi.muted)
        OutlinedTextField(m.search, { m.search = it }, Modifier.fillMaxWidth(), singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null, tint = navi.muted) },
            placeholder = { Text("Country, region or city", color = navi.muted) },
            shape = RoundedCornerShape(Radius.small),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = navi.border, focusedBorderColor = navi.primary,
                unfocusedContainerColor = navi.nested, focusedContainerColor = navi.card))
        val list = m.shown
        when {
            m.premade.isEmpty() && m.busy != null -> StatusLine("Loading the ${m.source.title} list…", Tone.WORKING)
            m.premade.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                StatusLine("Couldn't load the list. Check the internet connection.", Tone.BAD, Modifier.weight(1f))
                TextAction("Try again", { m.chooseSource(m.source) })
            }
            list.isEmpty() -> StatusLine("No map matches \"${m.search}\".")
            else -> Eyebrow("${list.size} maps")
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            itemsIndexed(list, key = { _, it -> it.id }) { i, pm ->
                if (i > 0) HorizontalDivider(color = navi.border, modifier = Modifier.padding(horizontal = 12.dp))
                ListRow({ m.pickPremade(pm) }, selected = m.picked == pm) {
                    Column(Modifier.weight(1f)) {
                        Text(pm.name, style = MaterialTheme.typography.bodyLarge, color = navi.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(pm.group, pm.variant).filter { it.isNotEmpty() }.joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall, color = navi.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    pm.bytes?.let { Text(size(it), style = MaterialTheme.typography.bodyMedium, color = navi.muted) }
                }
            }
        }
    }
}

@Composable
private fun SourceChip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.clickable(role = Role.Tab, onClick = onClick).heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) navi.primary else navi.muted,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) navi.primarySoft else navi.nested)
                .padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

/** The chosen map. */
@Composable
private fun PickedRow(title: String, sub: String, action: @Composable () -> Unit = {}) = NestedRow(title, sub, action = action)

/** Does it fit, where it goes, what could be replaced. */
@Composable
private fun InstallCard(m: PhoneMaps, showPicked: Boolean, modifier: Modifier = Modifier, onReplace: (Pair<Storage, SlotChoice>) -> Unit) {
    val r = m.ready?.takeIf { m.readyHere }
    val p = m.picked
    val empty = m.done == null && r == null && (m.tab == 1 || p == null)
    if (empty && !showPicked) return // phone, nothing chosen yet: no empty card
    NaviCard(modifier.fillMaxWidth()) {
        CardTitle("Install")
        if (empty) {
            Text(if (m.tab == 1) "Choose an .img file on the left." else "Pick a map on the left. It shows here with where it goes on the ${if (m.drive == null) "nüvi" else m.deviceName}.",
                style = MaterialTheme.typography.bodyMedium, color = navi.muted)
            return@NaviCard
        }
        m.done?.let { StatusLine(it, Tone.OK); return@NaviCard }
        if (r == null && p != null) {
            val est = p.estimatedImgBytes ?: m.pickedBytes?.let { (it * 1.35).toLong() }
            if (showPicked) PickedRow(p.name, listOf(p.source.title, p.variant).joinToString("  ·  "))
            StatusLine("Download it first: ${size(m.pickedBytes)}. On the ${m.deviceName} it takes about ${size(est)}.")
            val max = m.profile.maxFileBytes
            if (m.drive != null && est != null && max != null && est > max) {
                Note("Too big for the ${m.deviceName}, which takes up to ${size(max)} per map. Choose a smaller region, " +
                    "or make it on a PC with NaviLink, which splits big maps.", Tone.BAD)
            }
            if (p.source == PremadeSource.BBBIKE) Note("All BBBike maps share one map ID, so the device shows one of them at a time.", Tone.INFO)
            return@NaviCard
        }
        r!!
        if (m.tab == 0) Text(r.title + "  ·  " + size(r.files.sumOf { it.bytes }), style = MaterialTheme.typography.bodyLarge, color = navi.text)
        if (m.drive == null) { StatusLine("Pick the nüvi's drive above.", Tone.INFO); return@NaviCard }
        val plan = m.plan(r)
        val max = m.profile.maxFileBytes
        when {
            plan.ok -> {
                val clashes = m.clashes(r)
                (clashes + plan.warnings).forEach { Note(it, Tone.WARN) }
                plan.placements.forEach { pl ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        StatusLine("Goes on the ${storageName(pl.storage)}" + (pl.note?.let { ": $it" } ?: "") + ".",
                            if (clashes.isEmpty()) Tone.OK else Tone.WARN)
                        Text(pl.path, style = MaterialTheme.typography.bodySmall, color = navi.muted, modifier = Modifier.padding(start = 18.dp))
                    }
                }
            }
            max != null && r.files.any { it.bytes > max } -> {
                // The phone can't split maps, so a map over the limit is the only thing worth saying.
                Note("This map is ${size(r.files.maxOf { it.bytes })} and the ${m.deviceName} takes up to ${size(max)} per map. " +
                    "Choose a smaller region, or make the map on a PC with NaviLink, which splits big maps.", Tone.BAD)
                return@NaviCard
            }
            else -> plan.problems.forEach { Note(it, Tone.BAD) }
        }
        if (r.files.size > 1 && !plan.ok) StatusLine("This map comes in ${r.files.size} files. Each needs its own place on the device.", Tone.INFO)
        val swaps = m.swaps(r, plan.ok)
        if (swaps.isNotEmpty()) {
            Eyebrow(if (plan.ok) "Or replace a map" else "Replace a map", Modifier.padding(top = 4.dp))
            swaps.forEach { (st, c) ->
                val old = m.installed.firstOrNull { it.storage == st && it.path.equals(c.path, true) }
                NestedRow(old?.info?.description?.ifEmpty { null } ?: c.path.substringAfterLast('/'),
                    listOfNotNull(storageName(st).replaceFirstChar { it.uppercase() }, old?.let { size(it.bytes) }).joinToString("  ·  ")) {
                    TextAction("Replace", { onReplace(st to c) }, enabled = m.busy == null)
                }
            }
            Text("The old map is saved on the phone first.", style = MaterialTheme.typography.bodySmall, color = navi.muted)
        }
    }
}

/** The one filled button, at the bottom, with progress while a job runs. */
@Composable
private fun ActionBar(m: PhoneMaps, modifier: Modifier = Modifier) {
    val r = m.ready?.takeIf { m.readyHere }
    val working = m.busy != null && m.busy?.startsWith("Loading") != true
    val button = m.done == null && (r != null || (m.tab == 0 && m.picked != null))
    if (m.error == null && !working && !button) return // nothing to show: take no room
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.small)) {
        m.error?.let { Note(it, Tone.BAD) }
        // Loading a site's list shows in the list itself; only downloads and installs get a bar here.
        if (m.busy?.startsWith("Loading") == true) return@Column
        if (m.busy != null) {
            NaviCard(Modifier.fillMaxWidth(), padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.busy + "…", style = MaterialTheme.typography.bodyMedium, color = navi.text, modifier = Modifier.weight(1f))
                    m.progress?.let { Text("${(it * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = navi.muted) }
                    if (m.busy!!.startsWith("Downloading") || m.busy == "Installing") TextAction("Stop", { m.stop() })
                }
                val pr = m.progress
                if (pr != null) LinearProgressIndicator(progress = { pr }, modifier = Modifier.fillMaxWidth(), trackColor = navi.nested, drawStopIndicator = {})
                else LinearProgressIndicator(Modifier.fillMaxWidth(), trackColor = navi.nested)
            }
            return@Column
        }
        when {
            m.done != null -> {}
            r != null -> {
                val plan = m.plan(r)
                PrimaryButton("Install on the ${m.deviceName}", { m.installPlan(r, plan) }, Modifier.fillMaxWidth(), enabled = m.drive != null && plan.ok)
            }
            m.tab == 0 && m.picked != null -> PrimaryButton("Download  ·  ${size(m.pickedBytes)}", { m.download() }, Modifier.fillMaxWidth())
        }
    }
}

/**
 * The Maps page of the phone app. On a wide screen the map list sits on the left; on a phone it
 * folds away once a map is chosen.
 */
@Composable
fun PhoneMapsScreen(m: PhoneMaps, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { m.start() }
    var replacing by remember { mutableStateOf<Pair<Storage, SlotChoice>?>(null) }
    BoxWithConstraints(modifier) {
        val wide = maxWidth >= 720.dp
        // The window shrinks while the keyboard is up (adjustResize): then the nüvi card steps aside so the search field stays in view.
        val typing = maxHeight < 520.dp
        val gap = Arrangement.spacedBy(12.dp)
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(Space.medium)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = gap) {
                if (!typing) DeviceCard(m)
                MapCard(m, Modifier.weight(1f), collapse = false)
            }
            // The main button is pinned at the bottom, as on the Places page and on phones.
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = gap) {
                InstallCard(m, showPicked = true, Modifier.weight(1f).verticalScroll(rememberScrollState())) { replacing = it }
                ActionBar(m)
            }
        } else Column {
            if (m.tab == 0 && m.picked == null) Column(Modifier.weight(1f), verticalArrangement = gap) {
                if (!typing) DeviceCard(m)
                // The list card wraps its content; it only grows to the bottom when the list is long.
                MapCard(m, Modifier.weight(1f, fill = false), collapse = true)
            } else {
                ScrollPage(Modifier.weight(1f)) {
                    DeviceCard(m)
                    MapCard(m, Modifier, collapse = true)
                    InstallCard(m, showPicked = false) { replacing = it }
                }
            }
            ActionBar(m, Modifier.padding(top = 12.dp))
        }
    }
    replacing?.let { (st, c) ->
        val r = m.ready ?: return@let
        val old = m.installed.firstOrNull { it.storage == st && it.path.equals(c.path, true) }
        AlertDialog(onDismissRequest = { replacing = null }, containerColor = navi.card, shape = RoundedCornerShape(Radius.card),
            title = { Text("Replace this map?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text("\"${old?.info?.description?.ifEmpty { null } ?: c.path}\" on the ${storageName(st)} is replaced by ${r.title}. " +
                    "The old map is saved on the phone first, so you can put it back.", style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = { PrimaryButton("Replace", { replacing = null; m.replace(r, st, c.path) }) },
            dismissButton = { TextAction("Cancel", { replacing = null }) })
    }
}
