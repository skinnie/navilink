@file:OptIn(ExperimentalLayoutApi::class)

package app.navilink.device

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.navilink.maps.size
import app.navilink.ui.*
import osmimg.*
import kotlin.math.*

private fun levelTone(l: Level) = when (l) { Level.BAD -> Tone.BAD; Level.WARN -> Tone.WARN; Level.INFO -> Tone.INFO }

private fun storageTitle(st: Storage) = if (st == Storage.INTERNAL) "Built-in memory" else "Memory card"

private fun kindLabel(k: FileKind) = when (k) {
    FileKind.MAP -> null; FileKind.SYSTEM -> "Device map"; FileKind.BIRDSEYE -> "BirdsEye"; FileKind.CUSTOM_MAP -> "Custom map"
}

/** The device, its memories and a one-line verdict. */
@Composable
private fun DeviceCard(m: DeviceManager) {
    val c = m.contents
    NaviCard(Modifier.fillMaxWidth()) {
        if (c == null && m.storages.isEmpty()) {
            if (m.canPick) CardTitle("Choose the Garmin", "Plug it in with a USB OTG cable, wait for Android to show it, then choose " +
                "its drive. Newer nüvis and zūmos may need USB mass storage switched on in their settings first.") {
                TextAction("Choose drive", { m.pick(false) })
            } else CardTitle("No Garmin found", "Plug it in with a USB cable, or put its memory card in a card reader. " +
                "Newer nüvis and zūmos may need USB mass storage switched on in their settings first.") {
                TextAction("Look again", { m.scan() })
            }
            return@NaviCard
        }
        val bad = c?.findings?.count { it.level == Level.BAD } ?: 0
        val warn = c?.findings?.count { it.level == Level.WARN } ?: 0
        val status = when {
            m.busy != null -> "${m.busy}…" to Tone.WORKING
            c == null -> "Looking at what's on it…" to Tone.WORKING
            bad > 0 -> "$bad ${if (bad == 1) "problem" else "problems"} to fix" to Tone.BAD
            warn > 0 -> "$warn ${if (warn == 1) "thing" else "things"} to check" to Tone.WARN
            else -> "Everything on it looks right" to Tone.OK
        }
        CardTitle(m.model ?: if (m.storages == listOf(Storage.SD)) "Memory card" else "Garmin (model unknown)", status = status) {
            TextAction("Look again", { m.scan() }, enabled = m.busy == null)
        }
        if (m.canPick) Row(horizontalArrangement = Arrangement.spacedBy(Space.small)) {
            TextAction("Change drive", { m.pick(false) }, enabled = m.busy == null)
            TextAction(if (Storage.SD in m.storages) "Change card" else "Add its memory card", { m.pick(true) }, enabled = m.busy == null)
        }
        c?.storages?.forEach { s ->
            val maps = c.files.count { it.storage == s.storage && it.kind != FileKind.SYSTEM }
            NestedRow(storageTitle(s.storage), listOfNotNull(
                if (maps == 1) "1 map" else "$maps maps", "${size(s.mapBytes)} of map files", s.freeBytes?.let { "${size(it)} free" }, s.name,
            ).joinToString("  ·  "))
        }
        m.progress?.let { pr ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pr, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = navi.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextAction("Stop", { m.stop() })
            }
        }
        if (m.model == null && c != null) {
            Text("Without its GarminDevice.xml the model isn't known, so file names and folders aren't checked.",
                style = MaterialTheme.typography.bodySmall, color = navi.muted)
        }
    }
}

@Composable
private fun ChecksCard(m: DeviceManager) {
    val c = m.contents ?: return
    if (c.findings.isEmpty()) return
    NaviCard(Modifier.fillMaxWidth()) {
        CardTitle("Checks")
        c.findings.forEach { f ->
            val target = f.files.firstOrNull()
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.small)).background(navi.nested)
                .then(if (target != null) Modifier.clickable(role = Role.Button) { m.select(target) } else Modifier)
                .padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.padding(top = 6.dp).size(8.dp).background(toneColor(levelTone(f.level)), RoundedCornerShape(50)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(f.title, style = MaterialTheme.typography.titleSmall, color = navi.text)
                    Text(f.detail, style = MaterialTheme.typography.bodyMedium, color = navi.muted)
                }
            }
        }
    }
}

@Composable
private fun MapsCard(m: DeviceManager, modifier: Modifier = Modifier) {
    val c = m.contents ?: return
    var showSystem by remember { mutableStateOf(false) }
    NaviCard(modifier.fillMaxWidth()) {
        val system = c.files.count { it.kind == FileKind.SYSTEM }
        CardTitle("Maps on it") {
            if (system > 0) TextAction(if (showSystem) "Hide the device's own maps" else "Show the device's own maps ($system)", { showSystem = !showSystem })
        }
        for (st in m.storages) {
            val files = c.files.filter { it.storage == st && (showSystem || it.kind != FileKind.SYSTEM) }
            Eyebrow(storageTitle(st))
            if (files.isEmpty()) StatusLine(if (showSystem) "No map files." else "No extra maps.")
            files.forEach { f ->
                val worst = c.findingsFor(f).maxByOrNull { it.level.ordinal }?.level
                ListRow({ m.select(f) }, selected = m.selected?.let { it.storage == f.storage && it.path == f.path } == true) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(f.title, style = MaterialTheme.typography.titleSmall, color = navi.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${f.path}  ·  ${size(f.bytes)}", style = MaterialTheme.typography.bodyMedium, color = navi.muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    kindLabel(f.kind)?.let { Pill(it) }
                    if (f.details?.locked == true) Pill("Locked")
                    when {
                        f.readByDevice == false -> Pill("Not used", Tone.WARN)
                        worst == Level.BAD -> Pill("Problem", Tone.BAD)
                        worst == Level.WARN -> Pill("Check", Tone.WARN)
                    }
                }
            }
        }
    }
}

// ---- the chosen map ----

/** A one-field dialog for a new name; [check] returns what's wrong with the text, or null. */
@Composable
private fun NameDialog(title: String, hint: String, initial: String, check: (String) -> String?, onDone: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val problem = if (text.trim() == initial.trim()) "" else check(text.trim())
    AlertDialog(onClose, containerColor = navi.card,
        title = { Text(title, style = MaterialTheme.typography.titleMedium, color = navi.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = navi.muted)
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true, isError = !problem.isNullOrEmpty(),
                    shape = RoundedCornerShape(Radius.small),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = navi.border, focusedBorderColor = navi.primary,
                        unfocusedContainerColor = navi.nested, focusedContainerColor = navi.card))
                if (!problem.isNullOrEmpty()) StatusLine(problem, Tone.BAD)
            }
        },
        confirmButton = { PrimaryButton("Save", { onDone(text.trim()); onClose() }, enabled = problem == null) },
        dismissButton = { TextAction("Cancel", onClose) })
}

@Composable
private fun RemoveDialog(f: DeviceFile, deviceName: String, onDone: (keepCopy: Boolean) -> Unit, onClose: () -> Unit) {
    var keep by remember { mutableStateOf(true) }
    AlertDialog(onClose, containerColor = navi.card,
        title = { Text("Remove ${f.name}?", style = MaterialTheme.typography.titleMedium, color = navi.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
                Text("\"${f.title}\" will no longer be on the $deviceName. This frees ${size(f.bytes)}.",
                    style = MaterialTheme.typography.bodyMedium, color = navi.text)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.small)).clickable(role = Role.Checkbox) { keep = !keep }
                    .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(keep, { keep = it }, colors = CheckboxDefaults.colors(checkedColor = navi.primary))
                    Text("Keep a copy on this computer first", style = MaterialTheme.typography.bodyMedium, color = navi.text)
                }
                if (!keep) StatusLine("Without a copy, the map can only come back from where you got it.", Tone.WARN)
            }
        },
        confirmButton = { PrimaryButton("Remove", { onDone(keep); onClose() }) },
        dismissButton = { TextAction("Cancel", onClose) })
}

private sealed interface Edit {
    data object Description : Edit
    data class SetName(val family: MapFamily) : Edit
    data object FileName : Edit
    data object Remove : Edit
}

@Composable
private fun DetailsCard(m: DeviceManager, host: DeviceHost, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    val f = m.selected
    NaviCard(modifier.fillMaxWidth()) {
        if (f == null) {
            CardTitle("Choose a map", "Pick a map on the left to see where it covers and to rename, move or remove it.")
            return@NaviCard
        }
        val d = f.details
        val free = m.busy == null
        var edit by remember(f) { mutableStateOf<Edit?>(null) }
        if (onBack != null) CardTitle(f.title, "${storageTitle(f.storage)}  ·  ${f.path}  ·  ${size(f.bytes)}") {
            TextAction("All maps", onBack)
        } else CardTitle(f.title, "${storageTitle(f.storage)}  ·  ${f.path}  ·  ${size(f.bytes)}")

        val others = m.contents?.files.orEmpty().filter { it != f && it.kind == FileKind.MAP && it.readByDevice != false }
            .flatMap { o -> o.details?.tiles.orEmpty().mapNotNull { it.bounds } }
        d?.tiles?.mapNotNull { it.bounds }?.takeIf { it.isNotEmpty() }?.let { tiles ->
            CoverageMap(tiles, others, host, Modifier.fillMaxWidth().height(240.dp))
        }

        m.contents?.findingsFor(f)?.forEach { Note("${it.title}. ${it.detail}", levelTone(it.level)) }

        val editable = f.kind == FileKind.MAP && d != null && d.damaged == null
        if (d != null) {
            NestedRow("Map name", d.description.ifEmpty { "(none)" }) {
                if (editable) TextAction("Edit", { edit = Edit.Description }, enabled = free)
            }
            d.families.forEach { fam ->
                NestedRow("Map set", "${fam.name.replace(Regex("\\s+"), " ").ifEmpty { "(no name)" }}  ·  ID ${fam.familyId}, product ${fam.productId}") {
                    if (editable) TextAction("Edit", { edit = Edit.SetName(fam) }, enabled = free)
                }
            }
            val features = listOfNotNull(
                "${d.tiles.size} ${if (d.tiles.size == 1) "tile" else "tiles"}",
                if (d.routable) "routable" else "no routing",
                if (d.hasSearch) "address search" else null,
                if (d.elevation) "elevation" else null,
                if (d.hasTyp) "own symbols (TYP)" else null,
                if (d.locked) (if (d.unlockCodes > 0) "locked, unlock code inside" else "locked") else null,
            )
            NestedRow("Contents", features.joinToString("  ·  "))
        }
        NestedRow("File", f.name) {
            if (f.kind == FileKind.MAP) TextAction("Rename", { edit = Edit.FileName }, enabled = free)
        }
        val other = m.storages.firstOrNull { it != f.storage }
        NestedRow("Stored on", storageTitle(f.storage)) {
            if (other != null && f.kind != FileKind.SYSTEM) TextAction("Move to ${storageTitle(other).lowercase()}", { m.move(f, other) }, enabled = free)
        }
        if (f.kind == FileKind.SYSTEM) {
            Text("This is one of the device's own maps. NaviLink shows it but doesn't change it.",
                style = MaterialTheme.typography.bodySmall, color = navi.muted)
        } else {
            SecondaryButton("Remove from the ${m.deviceName}", { edit = Edit.Remove }, Modifier.fillMaxWidth(), enabled = free)
        }

        when (val e = edit) {
            Edit.Description -> NameDialog("Map name", "Shown in the device's map list and in BaseCamp. Up to $MAX_DESCRIPTION characters.",
                d!!.description, { imgTextProblem(it) ?: if (it.length > MAX_DESCRIPTION) "At most $MAX_DESCRIPTION characters." else null },
                { m.setDescription(f, it) }, { edit = null })
            is Edit.SetName -> NameDialog("Map set name", "The name of the map set (ID ${e.family.familyId}) on the device and in BaseCamp.",
                e.family.name, { imgTextProblem(it) ?: if (it.length > 100) "At most 100 characters." else null },
                { m.setMapSetName(f, e.family, it) }, { edit = null })
            Edit.FileName -> NameDialog("File name", "The device reads any name ending in .img in this folder, unless the checks above say otherwise.",
                f.name, { n -> when {
                    !n.endsWith(".img", true) || n.length <= 4 -> "The name must end in .img."
                    n.any { it in "/\\:*?\"<>|" } -> "A file name can't contain / \\ : * ? \" < > |"
                    else -> null
                } }, { m.renameFile(f, it) }, { edit = null })
            Edit.Remove -> RemoveDialog(f, m.deviceName, { keep -> m.remove(f, keep) }, { edit = null })
            null -> {}
        }
    }
}

// ---- coverage map ----

private const val TILE = 256

private fun mercX(lon: Double, z: Int) = (lon + 180) / 360 * (TILE shl z)
private fun mercY(lat: Double, z: Int): Double {
    val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
    return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (TILE shl z)
}

/**
 * Where a map covers: its tiles outlined in the accent over OpenStreetMap, with the device's other
 * maps in grey, so overlaps show. Without network the outlines are still drawn.
 */
@Composable
fun CoverageMap(tiles: List<Bounds>, others: List<Bounds>, host: DeviceHost, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.clip(RoundedCornerShape(Radius.small)).background(navi.nested).border(1.dp, navi.border, RoundedCornerShape(Radius.small))) {
        val wPx = constraints.maxWidth.coerceAtLeast(1); val hPx = constraints.maxHeight.coerceAtLeast(1)
        val all = tiles.reduce(Bounds::union)
        // The highest zoom at which the whole map fits, with a margin.
        val z = (0..14).lastOrNull { zz ->
            (mercX(all.east, zz) - mercX(all.west, zz)) * 1.15 <= wPx && (mercY(all.south, zz) - mercY(all.north, zz)) * 1.15 <= hPx
        } ?: 0
        val cx = (mercX(all.west, z) + mercX(all.east, z)) / 2; val cy = (mercY(all.north, z) + mercY(all.south, z)) / 2
        val left = cx - wPx / 2.0; val top = cy - hPx / 2.0
        val n = 1 shl z
        val keys = buildList {
            for (tx in floor(left / TILE).toInt()..floor((left + wPx) / TILE).toInt())
                for (ty in floor(top / TILE).toInt().coerceAtLeast(0)..floor((top + hPx) / TILE).toInt().coerceAtMost(n - 1))
                    add(Triple(z, tx, ty))
        }
        val images = remember(keys) { mutableStateMapOf<Triple<Int, Int, Int>, ImageBitmap>() }
        LaunchedEffect(keys) {
            for (k in keys) { host.mapTile(k.first, ((k.second % n) + n) % n, k.third)?.let { images[k] = it } }
        }
        val accent = navi.primary; val grey = navi.muted
        Canvas(Modifier.fillMaxSize()) {
            for (k in keys) images[k]?.let { img ->
                drawImage(img, dstOffset = IntOffset((k.second * TILE - left).roundToInt(), (k.third * TILE - top).roundToInt()),
                    dstSize = IntSize(TILE, TILE))
            }
            fun rect(b: Bounds): Pair<Offset, Size> {
                val x0 = mercX(b.west, z) - left; val x1 = mercX(b.east, z) - left
                val y0 = mercY(b.north, z) - top; val y1 = mercY(b.south, z) - top
                return Offset(x0.toFloat(), y0.toFloat()) to Size((x1 - x0).toFloat().coerceAtLeast(1f), (y1 - y0).toFloat().coerceAtLeast(1f))
            }
            for (b in others) { val (o, s) = rect(b); drawRect(grey, o, s, style = Stroke(1.dp.toPx())) }
            for (b in tiles) {
                val (o, s) = rect(b)
                drawRect(accent.copy(alpha = 0.16f), o, s)
                drawRect(accent, o, s, style = Stroke(1.5.dp.toPx()))
            }
        }
        Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall, color = navi.text,
            modifier = Modifier.align(Alignment.BottomEnd).background(navi.card.copy(alpha = 0.8f)).padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

// ---- page ----

@Composable
private fun EditBar(m: DeviceManager) {
    m.error?.let { Note(it, Tone.BAD) }
    m.lastEdit?.let { e ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.small)).background(navi.success.copy(alpha = 0.10f))
            .border(1.dp, navi.success.copy(alpha = 0.35f), RoundedCornerShape(Radius.small)).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(e.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = navi.text)
            if (e.undo != null) TextAction("Undo", { m.undo() }, enabled = m.busy == null)
            TextAction("OK", { m.dismissEdit() })
        }
    }
}

// ---- other files, backup, restore ----

@Composable
private fun FilesCard(m: DeviceManager) {
    if (m.storages.isEmpty()) return
    var startupEdit by remember { mutableStateOf(false) }
    val free = m.busy == null
    NaviCard(Modifier.fillMaxWidth()) {
        CardTitle("Files and backups")
        NestedRow("Add files", "Maps, GPX, POIs, BirdsEye, voices, vehicles and more, each into the folder the ${m.deviceName} reads it from.") {
            TextAction("Add…", { m.addFiles() }, enabled = free)
        }
        NestedRow("Back up", "Copy what is on the ${m.deviceName} to ${m.here}.") {
            TextAction("Back up…", { m.openBackup() }, enabled = free)
        }
        NestedRow("Put a backup back", "Missing files are copied back. Nothing on the device is deleted.") {
            TextAction("Restore…", { m.openRestore() }, enabled = free)
        }
        m.startup?.let { st ->
            NestedRow("Startup message", if (st.message.isEmpty()) "None. Shown while the ${m.deviceName} starts, e.g. how to reach you if it is found."
                else st.message.replace('\n', ' ') + if (st.seconds > 0) "  ·  ${st.seconds} s" else "") {
                TextAction("Edit…", { startupEdit = true }, enabled = free)
            }
        }
    }
    if (startupEdit) m.startup?.let { StartupDialog(m, it) { startupEdit = false } }
    if (m.adding != null) AddDialog(m)
    m.backupList?.let { BackupDialog(m, it) }
    m.backups?.let { RestoreDialog(m, it) }
}

private fun count(n: Int) = when (n) { 0 -> "none"; 1 -> "1 file"; else -> "$n files" }

@Composable
private fun StartupDialog(m: DeviceManager, now: StartupText, onClose: () -> Unit) {
    var text by remember { mutableStateOf(now.message) }
    var secs by remember { mutableStateOf(now.seconds.toString()) }
    val s = secs.toIntOrNull() ?: -1
    val problem = startupTextProblem(text, s)
    AlertDialog(onClose, containerColor = navi.card,
        title = { Text("Startup message", style = MaterialTheme.typography.titleMedium, color = navi.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
                DialogText("Shown while the ${m.deviceName} starts. Many people put how to reach them if it is lost.")
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp), label = { Text("Message") },
                    supportingText = { Text("${text.length} of $STARTUP_MAX characters") }, isError = text.length > STARTUP_MAX)
                OutlinedTextField(secs, { v -> secs = v.filter { it.isDigit() }.take(2) }, Modifier.width(200.dp), singleLine = true,
                    label = { Text("Show for at least (seconds)") })
                problem?.let { DialogText(it, Tone.BAD) }
                if (text.any { it.code > 127 }) DialogText("Accented letters: one eTrex 30 owner reported a hang with them. Plain letters are safest.", Tone.WARN)
                DialogText("The old file is copied to ${m.here} first. The device shows the new message after one full power cycle.", Tone.MUTED)
            }
        },
        confirmButton = { PrimaryButton("Save", { m.changeStartup(StartupText(s, text.trimEnd())); onClose() }, enabled = problem == null) },
        dismissButton = { TextAction("Cancel", onClose) })
}

@Composable
private fun DialogText(text: String, tone: Tone? = null) {
    if (tone != null) StatusLine(text, tone) else Text(text, style = MaterialTheme.typography.bodyMedium, color = navi.text)
}

@Composable
private fun CheckLine(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.small)).clickable(enabled = enabled, role = Role.Checkbox) { onChange(!checked) }
        .padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, enabled = enabled, colors = CheckboxDefaults.colors(checkedColor = navi.primary))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (enabled) navi.text else navi.muted)
    }
}

@Composable
private fun StorageChoice(m: DeviceManager, chosen: Storage, onChoose: (Storage) -> Unit) {
    if (m.storages.size < 2) return
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        m.storages.forEachIndexed { i, st ->
            SegmentedButton(chosen == st, { onChoose(st) }, SegmentedButtonDefaults.itemShape(i, m.storages.size), icon = {}) {
                Text(storageTitle(st), maxLines = 1)
            }
        }
    }
}

@Composable
private fun AddDialog(m: DeviceManager) {
    val plans = m.addPlans
    val ok = plans.orEmpty().filter { it.ok }
    AlertDialog({ m.cancelAdd() }, containerColor = navi.card,
        title = { Text("Put files on the ${m.deviceName}", style = MaterialTheme.typography.titleMedium, color = navi.text) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.small)) {
                StorageChoice(m, m.addStorage) { m.addTo(it) }
                if (plans == null) DialogText("Looking at the files…")
                plans?.forEach { p ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.small)).background(navi.nested).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(p.source.name, style = MaterialTheme.typography.titleSmall, color = navi.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(p.kind?.label, size(p.source.bytes), p.path?.let { "→ $it" }).joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall, color = navi.muted)
                        p.problems.forEach { StatusLine(it, Tone.BAD) }
                        p.warnings.forEach { StatusLine(it, Tone.WARN) }
                    }
                }
            }
        },
        confirmButton = {
            PrimaryButton(if (ok.size == 1) "Copy 1 file" else "Copy ${ok.size} files", { m.confirmAdd() }, enabled = ok.isNotEmpty())
        },
        dismissButton = { TextAction("Cancel", { m.cancelAdd() }) })
}

@Composable
private fun BackupDialog(m: DeviceManager, files: List<BackupFile>) {
    // The device's own maps can be gigabytes and come back from Garmin, so they are left out unless asked.
    var parts by remember { mutableStateOf(setOf(BackupPart.YOUR_MAPS, BackupPart.OTHER)) }
    val chosen = files.filter { it.part in parts }
    AlertDialog({ m.closeBackup() }, containerColor = navi.card,
        title = { Text("Back up the ${m.deviceName}", style = MaterialTheme.typography.titleMedium, color = navi.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.small)) {
                BackupPart.entries.forEach { part ->
                    val these = files.filter { it.part == part }
                    CheckLine("${part.label} (${count(these.size)}${if (these.isEmpty()) "" else ", " + size(these.sumOf { it.bytes })})", part in parts, these.isNotEmpty()) { on ->
                        parts = if (on) parts + part else parts - part
                    }
                }
                DialogText("Goes to ${m.backupsPlace.path}, in a new folder named after the device and the time.")
            }
        },
        confirmButton = { PrimaryButton("Back up ${size(chosen.sumOf { it.bytes })}", { m.backUp(parts) }, enabled = chosen.isNotEmpty()) },
        dismissButton = { TextAction("Cancel", { m.closeBackup() }) })
}

@Composable
private fun RestoreDialog(m: DeviceManager, list: List<DeviceBackup>) {
    var parts by remember { mutableStateOf(BackupPart.entries.toSet()) }
    var replace by remember { mutableStateOf(false) }
    val b = m.restoreFrom
    val plan = m.restorePlan
    val fmt = remember { java.text.SimpleDateFormat("d MMM yyyy, HH:mm") }
    AlertDialog({ m.closeRestore() }, containerColor = navi.card,
        title = { Text(if (b == null) "Choose a backup" else "Put back the backup of ${fmt.format(java.util.Date(b.made))}",
            style = MaterialTheme.typography.titleMedium, color = navi.text) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.small)) {
                if (list.isEmpty()) DialogText("There are no backups in ${m.backupsPlace.path} yet.")
                if (b == null) list.forEach { x ->
                    NestedRow("${x.model}  ·  ${fmt.format(java.util.Date(x.made))}", "${x.files.size} files, ${size(x.bytes)}",
                        Modifier.clip(RoundedCornerShape(Radius.small)).clickable(role = Role.Button) { m.chooseRestore(x, parts) })
                } else {
                    if (m.model != null && !b.model.equals(m.model, true)) DialogText("This backup is from a ${b.model}; this is a ${m.model}. " +
                        "Files may not suit it.", Tone.WARN)
                    BackupPart.entries.forEach { part ->
                        val these = b.files.filter { it.part == part }
                        CheckLine("${part.label} (${count(these.size)})", part in parts, these.isNotEmpty()) { on ->
                            parts = if (on) parts + part else parts - part
                            m.chooseRestore(b, parts)
                        }
                    }
                    if (plan == null) DialogText("Comparing with the device…")
                    else {
                        val n = plan.groupingBy { it.action }.eachCount()
                        DialogText(listOfNotNull(
                            "${n[RestoreAction.COPY] ?: 0} missing ${if (n[RestoreAction.COPY] == 1) "file" else "files"} to copy back",
                            n[RestoreAction.SAME]?.let { "$it already the same" },
                            n[RestoreAction.NO_STORAGE]?.let { "$it from a memory card that isn't there" },
                        ).joinToString(", ") + ".")
                        n[RestoreAction.CHANGED]?.let { c ->
                            CheckLine("Also put back $c ${if (c == 1) "file" else "files"} that changed since", replace) { replace = it }
                            if (replace) DialogText("The device's versions are copied to ${m.here} first.", Tone.MUTED)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (b != null) {
                val count = plan.orEmpty().count { it.action == RestoreAction.COPY || (replace && it.action == RestoreAction.CHANGED) }
                PrimaryButton(if (count == 1) "Put back 1 file" else "Put back $count files", { m.restore(replace) }, enabled = count > 0)
            }
        },
        dismissButton = { TextAction(if (b != null) "Back" else "Cancel", { if (b != null) m.chooseRestore(null, parts) else m.closeRestore() }) })
}

// ---- updates from Garmin ----

@Composable
private fun UpdatesCard(m: DeviceManager) {
    if (Storage.INTERNAL !in m.storages || m.model == null) return
    val c = m.updates
    var confirmFirmware by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(setOf<UpdateKind>()) }
    NaviCard(Modifier.fillMaxWidth()) {
        CardTitle("Updates from Garmin", when {
            c == null -> "Device software, voices, vehicles, languages and the time-zone map, as Garmin's WebUpdater offered them. " +
                "Map updates and safety cameras need Garmin Express."
            c.fromExpress -> "From Garmin Express's service (asked with a made-up unit ID)."
            else -> "From Garmin's WebUpdater service (asked with part numbers only)."
        }, status = if (m.checkingUpdates) "Asking Garmin…" to Tone.WORKING else null) {
            TextAction(if (c == null) "Check" else "Check again", { m.checkUpdates() }, enabled = !m.checkingUpdates && m.busy == null)
        }
        if (c == null) return@NaviCard
        if (c.items.isEmpty()) { StatusLine("Garmin has nothing for the ${m.deviceName}.", Tone.MUTED); return@NaviCard }

        @Composable
        fun row(u: GarminUpdate) {
            val copied = u.partNumber in m.copiedUpdates
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.small)).background(navi.nested)
                .clickable(enabled = !copied && m.busy == null, role = Role.Checkbox) { m.toggleUpdate(u) }
                .padding(end = 12.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(u.partNumber in m.chosenUpdates, { m.toggleUpdate(u) }, enabled = !copied && m.busy == null,
                    colors = CheckboxDefaults.colors(checkedColor = navi.primary))
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(u.name, style = MaterialTheme.typography.bodyMedium, color = navi.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(
                        when {
                            copied -> "${u.versionText} copied; the device takes it in when it next starts"
                            u.isUpdate -> "${u.versionText}, it has ${versionText(u.installedVersion!!)}"
                            u.installed -> "${u.versionText}, on it"
                            else -> "${u.versionText}, not on it"
                        },
                        u.bytes?.let { size(it) },
                    ).joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = navi.muted)
                }
            }
        }

        val newer = c.items.filter { it.isUpdate }
        Text(if (newer.isEmpty()) "Everything on it is up to date." else "Newer than what's on it",
            style = MaterialTheme.typography.titleSmall, color = navi.text)
        newer.sortedBy { it.kind }.forEach { row(it) }
        // The rest, by kind, folded away: voices and languages to add, and what is already current.
        val rest = c.items.filter { !it.isUpdate }.groupBy { it.kind }.toSortedMap()
        if (rest.isNotEmpty()) Text("Also from Garmin", style = MaterialTheme.typography.titleSmall, color = navi.text)
        rest.forEach { (k, list) ->
            val add = list.count { !it.installed }
            NestedRow(k.label, listOfNotNull((list.size - add).takeIf { it > 0 }?.let { "$it on it" }, add.takeIf { it > 0 }?.let { "$it to add" }).joinToString(", "),
                Modifier.clip(RoundedCornerShape(Radius.small)).clickable(role = Role.Button) { open = if (k in open) open - k else open + k }) {
                Text(if (k in open) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = navi.text)
            }
            if (k in open) list.sortedWith(compareBy({ it.installed }, { it.name })).forEach { row(it) }
        }
        val chosen = c.items.filter { it.partNumber in m.chosenUpdates }
        c.licence?.let { Text("Garmin's software licence applies: $it", style = MaterialTheme.typography.bodySmall, color = navi.muted) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            PrimaryButton(if (chosen.size == 1) "Get 1 item" else "Get ${chosen.size} items", {
                if (chosen.any { it.kind == UpdateKind.FIRMWARE }) confirmFirmware = true else m.installUpdates()
            }, enabled = chosen.isNotEmpty() && m.busy == null)
        }
    }
    if (confirmFirmware) {
        val fw = c?.items.orEmpty().filter { it.partNumber in m.chosenUpdates && it.kind == UpdateKind.FIRMWARE }
        AlertDialog({ confirmFirmware = false }, containerColor = navi.card,
            title = { Text("Update the ${m.deviceName}'s software?", style = MaterialTheme.typography.titleMedium, color = navi.text) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.small)) {
                    DialogText("The files are copied now; the device installs them the next time it starts. Charge it fully first: " +
                        "if the power fails while it installs, the device may need repair by Garmin.", Tone.WARN)
                    fw.forEach { u ->
                        Text("${u.name}  ${u.installedVersion?.let { versionText(it) + " → " } ?: ""}${u.versionText}", style = MaterialTheme.typography.titleSmall, color = navi.text)
                        u.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = navi.muted) }
                    }
                }
            },
            confirmButton = { PrimaryButton("Copy the update", { confirmFirmware = false; m.installUpdates() }) },
            dismissButton = { TextAction("Cancel", { confirmFirmware = false }) })
    }
}

/** The "On the device" page: the device, the checks, its maps, and the chosen map. */
@Composable
fun DeviceScreen(m: DeviceManager, host: DeviceHost, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { m.scan() }
    BoxWithConstraints(modifier) {
        if (maxWidth >= 900.dp) Row(horizontalArrangement = Arrangement.spacedBy(Space.large)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DeviceCard(m); FilesCard(m); UpdatesCard(m); MapsCard(m); ChecksCard(m)
            }
            Column(Modifier.width(460.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EditBar(m)
                DetailsCard(m, host)
            }
        } else ScrollPage(Modifier.fillMaxSize()) {
            // A phone: the list, or one map with a way back to the list.
            EditBar(m)
            if (m.selected != null) DetailsCard(m, host, onBack = { m.select(null) })
            else { DeviceCard(m); ChecksCard(m); MapsCard(m); FilesCard(m); UpdatesCard(m) }
        }
    }
}
