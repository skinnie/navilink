package osmimg

import java.io.File

/** What a file on the device is for. */
enum class FileKind {
    /** A vector map (.img) the user can manage. */
    MAP,
    /** One of the device's own maps: base map, time zones, factory map, elevation, 3D... */
    SYSTEM,
    /** BirdsEye satellite or raster imagery (.jnx). */
    BIRDSEYE,
    /** A Garmin Custom Map (.kmz), a picture laid over the map. */
    CUSTOM_MAP,
}

/**
 * A map file on the device or its card. [readByDevice] is null when the model is unknown and we
 * can't tell; [note] says why the device would skip it.
 */
data class DeviceFile(
    val storage: Storage,
    val path: String,
    val bytes: Long,
    val kind: FileKind,
    val details: ImgDetails?,
    val readByDevice: Boolean?,
    val note: String? = null,
) {
    val name: String get() = path.substringAfterLast('/')
    /** The name to show: the map's own description, else the file name. */
    val title: String get() = details?.families?.map { it.name.replace(Regex("\\s+"), " ").trim() }?.filter { it.isNotEmpty() }?.distinct()
        ?.joinToString(" + ")?.ifEmpty { null } ?: details?.description?.ifEmpty { null } ?: name
}

enum class Level { INFO, WARN, BAD }

/** Something the scan found, with the files it is about. */
data class Finding(val level: Level, val title: String, val detail: String, val files: List<DeviceFile> = emptyList())

data class StorageSummary(val storage: Storage, val name: String, val freeBytes: Long?, val mapBytes: Long)

data class DeviceContents(val files: List<DeviceFile>, val findings: List<Finding>, val storages: List<StorageSummary>) {
    val maps: List<DeviceFile> get() = files.filter { it.kind != FileKind.SYSTEM }
    fun findingsFor(f: DeviceFile) = findings.filter { f in it.files }
}

private fun mb(bytes: Long) = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / 1048576.0)
    else -> "%.0f KB".format(bytes / 1024.0)
}

private fun where(f: DeviceFile) = "${f.path} on the ${storageName(f.storage)}"

/** Older handhelds that show at most 2025 map tiles (Garmin support FAQ for the 60/76 Cx series and eTrex Cx). */
private val TILE_LIMIT_2025 = Regex("""GPSMAP\s*(60|76|96)C|eTrex\s*(Vista|Legend|Venture)\s*(H?Cx|C)|Quest""", RegexOption.IGNORE_CASE)

/**
 * Reads every map file on [drives] and checks them against what the device can use.
 * [model] is the device's name from GarminDevice.xml, or null when it is not known (an SD card on its
 * own, an older unit): then naming rules aren't checked, as newer units read any name.
 */
fun scanDevice(
    drives: Map<Storage, DriveFs>,
    profile: DeviceProfile,
    model: String?,
    rasterPaths: List<Pair<String, String>> = emptyList(),
    progress: (String) -> Unit = {},
): DeviceContents {
    val files = mutableListOf<DeviceFile>()
    val leftovers = mutableListOf<Pair<Storage, String>>()
    val summaries = mutableListOf<StorageSummary>()
    val known = model != null
    for ((st, fs) in drives) {
        val folders = (listOf("", "Garmin", "Map") + profile.slots(st).map { it.folder }).distinctBy { it.lowercase() }
        val raster = (rasterPaths + listOf("jnx" to "Garmin/BirdsEye", "kmz" to "Garmin/CustomMaps")).distinctBy { it.second.lowercase() }
        fun listDir(dir: String): Pair<String, List<FsEntry>>? {
            val real = fs.spelling(dir)
            return fs.list(real)?.let { real to it }
        }
        for (folder in folders) {
            val (dir, entries) = listDir(folder) ?: continue
            for (e in entries.filter { !it.isDir }) {
                val path = if (dir.isEmpty()) e.name else "$dir/${e.name}"
                val lower = e.name.lowercase()
                if (lower.endsWith(".img.tmp")) { leftovers += st to path; continue }
                if (!lower.endsWith(".img")) continue
                progress("Reading $path")
                val base = lower.removeSuffix(".img")
                val system = lower in profile.systemNames || (base == "gmapprom" && st == Storage.INTERNAL)
                val slot = profile.slots(st).firstOrNull { s ->
                    s.folder.equals(folder, true) && (s.baseName == null || s.baseName.equals(base, true))
                }
                val gmapsupp = folder.equals("Garmin", true) && (base == "gmapsupp" || Regex("gmapsup\\d").matches(base))
                val reads: Boolean?; val note: String?
                when {
                    system -> { reads = true; note = null }
                    slot != null -> { reads = true; note = null }
                    !known && (gmapsupp || folder.equals("Garmin", true) || folder.equals("Map", true)) -> {
                        reads = if (gmapsupp) true else null
                        note = if (gmapsupp) null else "Newer Garmins read any map name here; older ones only gmapsupp.img."
                    }
                    else -> {
                        reads = false
                        val slots = profile.slots(st).filter { it.role != SlotRole.FACTORY }
                        val anyIn = slots.filter { it.baseName == null }.map { it.folder.lowercase() }.toSet()
                        val wanted = slots.filter { it.baseName == null || it.folder.lowercase() !in anyIn }.map { sl ->
                            if (sl.baseName == null) "any .img in ${sl.folder.ifEmpty { "the top folder" }}/" else sl.path("")
                        }.distinct().joinToString(" or ").ifEmpty { "nothing on this storage" }
                        note = "The ${model ?: "device"} doesn't load maps from here; it looks for $wanted."
                    }
                }
                val details = fs.details(path)
                files += DeviceFile(st, path, e.bytes, if (system) FileKind.SYSTEM else FileKind.MAP, details, reads, note)
            }
        }
        for ((ext, folder) in raster) {
            val (dir, entries) = listDir(folder) ?: continue
            for (e in entries.filter { !it.isDir && it.name.endsWith(".$ext", true) }) {
                files += DeviceFile(st, "$dir/${e.name}", e.bytes, if (ext == "jnx") FileKind.BIRDSEYE else FileKind.CUSTOM_MAP, null,
                    if (known) rasterPaths.any { it.first == ext } else null,
                    if (known && rasterPaths.none { it.first == ext }) "The $model doesn't list ${if (ext == "jnx") "BirdsEye" else "custom maps"} among the files it reads." else null)
            }
        }
        summaries += StorageSummary(st, fs.name, fs.freeBytes(), files.filter { it.storage == st }.sumOf { it.bytes })
    }
    return DeviceContents(files, findings(files, leftovers, profile, model), summaries)
}

private fun findings(files: List<DeviceFile>, leftovers: List<Pair<Storage, String>>, profile: DeviceProfile, model: String?): List<Finding> {
    val out = mutableListOf<Finding>()
    val maps = files.filter { it.kind == FileKind.MAP }
    val loaded = maps.filter { it.readByDevice != false }

    for (f in files.filter { it.path.endsWith(".img", true) }) {
        val d = f.details
        when {
            f.bytes == 0L -> out += Finding(if (f.kind == FileKind.SYSTEM) Level.WARN else Level.BAD, "${f.name} is empty",
                "The file has no data in it, so the device ignores it. ${if (f.kind == FileKind.SYSTEM) "It is one of the device's own maps: restore it from a backup." else "Remove it, or copy the map again."}", listOf(f))
            d == null -> out += Finding(Level.BAD, "${f.name} isn't a map the device can read",
                "It doesn't start like a Garmin map. It may be damaged or only named .img.", listOf(f))
            d.damaged != null -> out += Finding(Level.BAD, "${f.name} looks damaged", "${d.damaged.replaceFirstChar { it.uppercase() }}. Copy the map to the device again.", listOf(f))
        }
    }

    for (f in maps.filter { it.readByDevice == false }) {
        out += Finding(Level.WARN, "The device skips ${f.name}", f.note ?: "", listOf(f))
    }
    for (f in files.filter { it.readByDevice == false && it.kind != FileKind.MAP }) {
        out += Finding(Level.WARN, "The device skips ${f.name}", f.note ?: "", listOf(f))
    }

    profile.maxFileBytes?.let { max ->
        for (f in maps.filter { it.bytes > max }) out += Finding(Level.BAD, "${f.name} is too big for the ${model ?: "device"}",
            "It is ${mb(f.bytes)}; the device reads files up to ${mb(max + 1)}. Split it into smaller files.", listOf(f))
    }

    // The same family ID in two files: the same tiles twice is always a problem; different maps
    // sharing an ID show as one. A Garmin map split over files (same product, other tiles) is fine.
    val byFamily = loaded.flatMap { f -> f.details?.families.orEmpty().map { it.familyId to f } }.groupBy({ it.first }, { it.second })
    val reported = mutableSetOf<Set<DeviceFile>>()
    for ((fid, fs) in byFamily) {
        val distinct = fs.distinct()
        if (distinct.size < 2) continue
        for (i in distinct.indices) for (j in i + 1 until distinct.size) {
            val a = distinct[i]; val b = distinct[j]
            if (!reported.add(setOf(a, b))) continue
            // Garmin repeats the overview tile (the one covering the whole map) in every part of a split map: it doesn't count.
            fun detail(d: ImgDetails): Set<String> {
                val all = d.bounds
                return d.tiles.filter { d.tiles.size == 1 || it.bounds != all }.map { it.name }.toSet()
            }
            val shared = detail(a.details!!) intersect detail(b.details!!)
            val fa = a.details.families.first { it.familyId == fid }; val fb = b.details.families.first { it.familyId == fid }
            when {
                shared.isNotEmpty() -> out += Finding(Level.BAD, "The same map is on the device twice",
                    "\"${a.title}\" (${where(a)}) and \"${b.title}\" (${where(b)}) share ${shared.size} map tile${if (shared.size == 1) "" else "s"}. " +
                        "The device may show the wrong one or route badly. Remove one of them.", listOf(a, b))
                fa.productId != fb.productId || !fa.name.equals(fb.name, true) -> out += Finding(Level.BAD, "Two maps use the same map ID ($fid)",
                    "\"${a.title}\" (${where(a)}) and \"${b.title}\" (${where(b)}) are different maps with one ID, so the device shows only one of them. " +
                        "Rebuild one with another family ID, or keep one.", listOf(a, b))
            }
        }
    }

    for (f in maps.filter { it.details?.locked == true }) {
        out += if (f.details!!.unlockCodes > 0) Finding(Level.INFO, "${f.title} is a locked map",
            "Its unlock code is inside the file and is tied to one device or memory card. NaviLink can't check which, so if the map doesn't show, it was bought for another unit.", listOf(f))
        else Finding(Level.WARN, "${f.title} is locked and has no unlock code inside",
            "It shows only if this device already has the code (Garmin Express adds it). Check on the device that the map appears.", listOf(f))
    }

    profile.maxMapSets?.let { max ->
        val sets = loaded.flatMap { f -> f.details?.families.orEmpty().map { it.familyId } }.distinct().size
        if (sets > max) out += Finding(Level.WARN, "More map sets than the ${model ?: "device"} handles",
            "There are $sets map sets on it; this model shows at most $max. Remove the ones you don't use.", loaded)
    }
    if (model != null && TILE_LIMIT_2025.containsMatchIn(model)) {
        val tiles = loaded.sumOf { it.details?.tiles?.size ?: 0 }
        if (tiles > 2025) out += Finding(Level.WARN, "Too many map tiles for the $model",
            "The maps hold $tiles tiles; this model shows at most 2025, and the rest stay blank. Use smaller areas.", loaded)
    }

    for ((st, p) in leftovers) out += Finding(Level.INFO, "An unfinished copy was left behind",
        "${p.substringAfterLast('/')} on the ${storageName(st)} is a copy that didn't finish. It takes space and can be removed.")
    if (maps.isEmpty()) out += Finding(Level.INFO, "No extra maps on the device", "Only the device's own maps are on it.")
    return out.sortedBy { -it.level.ordinal }
}

// ---- changing files ----

private val STAMP get() = java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss").format(java.util.Date())

/** A change made to a map, with what it takes to put it back. */
data class Undo(val label: String, val storage: Storage, val path: String, val patches: List<Patch>, val record: File)

/**
 * Edits an .img in place: [make] builds the patches from the file as it is. What gets overwritten is
 * saved first under [backupDir]/edits, so the change can be undone even after NaviLink is closed.
 */
fun editMap(fs: DriveFs, st: Storage, path: String, backupDir: File, label: String, make: (java.nio.channels.FileChannel) -> List<Patch>): Undo {
    val (patches, undo) = fs.readChannel(path) { ch -> make(ch).let { it to undoFor(ch, it) } } ?: error("can't read $path")
    val record = File(backupDir, "edits/$STAMP-${path.substringAfterLast('/')}.undo")
    savePatches(record, undo)
    fs.patch(path, patches)
    // Read it back: the change must be there and the map must still read.
    val ok = fs.readChannel(path) { ch -> undoFor(ch, patches).zip(patches).all { (a, b) -> a.bytes.contentEquals(b.bytes) } && readImgDetails(ch)?.damaged == null }
    if (ok != true) { fs.patch(path, undo); error("the change didn't read back; the map is as it was") }
    return Undo(label, st, path, undo, record)
}

fun undoEdit(fs: DriveFs, u: Undo) = fs.patch(u.path, u.patches)

/** Renames a file in its folder; refuses names the device's rules don't allow for a fixed slot. */
fun renameMapFile(fs: DriveFs, path: String, newName: String) {
    require(newName.endsWith(".img", true) && newName.length > 4) { "The name must end in .img." }
    require(newName.none { it in "/\\:*?\"<>|" }) { "A file name can't contain / \\ : * ? \" < > |" }
    val folder = path.substringBeforeLast('/', "")
    val target = if (folder.isEmpty()) newName else "$folder/$newName"
    val old = path.substringAfterLast('/')
    check(newName != old) { "That is its name already." }
    // A change of case only is the same file on FAT; anything else must not overwrite a file.
    check(newName.equals(old, true) || fs.length(target) == null) { "There is already a $newName there." }
    fs.rename(path, newName)
}

/**
 * Moves a map to [toPath] on [to] (the other storage, or another folder of the same one). Copies under
 * a temporary name, checks the size, then removes the original; on the same drive it only renames.
 */
fun moveMap(from: DriveFs, path: String, to: DriveFs, toPath: String, log: (String) -> Unit, cancelled: () -> Boolean = { false }) {
    check(to.length(toPath) == null) { "There is already a ${toPath.substringAfterLast('/')} there." }
    if (from === to && from.moveWithin(path, toPath)) { log("Moved $path to $toPath."); return }
    val bytes = from.length(path) ?: error("$path is gone")
    to.freeBytes()?.let { free -> check(bytes <= free) { "Not enough room: it needs ${mb(bytes)} and there is ${mb(free)} free." } }
    log("Copying $path to ${to.name}/$toPath (${mb(bytes)}) ...")
    val tmp = "$toPath.tmp"
    to.delete(tmp)
    val n = from.openRead(path).use { to.write(tmp, it) }
    if (cancelled()) { to.delete(tmp); error("stopped") }
    if (n != bytes || to.length(tmp) != bytes) { to.delete(tmp); error("the copy is incomplete; the map stays where it was") }
    to.rename(tmp, toPath.substringAfterLast('/'))
    check(from.delete(path)) { "copied, but the original $path could not be removed" }
    log("Moved $path to ${to.name}/$toPath.")
}

/** Removes a map; with [keepCopyIn] it is copied there first (and removal stops if that fails). */
fun removeMap(fs: DriveFs, st: Storage, path: String, keepCopyIn: File?, log: (String) -> Unit): File? {
    val copy = keepCopyIn?.let { File(it, "removed/$STAMP/${st.name.lowercase()}/$path") }
    if (copy != null) {
        log("Saving a copy of $path to ${copy.path} ...")
        copy.parentFile.mkdirs()
        fs.openRead(path).use { inp -> copy.outputStream().use { inp.copyTo(it, 1 shl 20) } }
        check(copy.length() == fs.length(path)) { "the copy is incomplete, so the map was not removed" }
    }
    check(fs.delete(path)) { "could not remove $path" }
    log("Removed $path.")
    return copy
}
