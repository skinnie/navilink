package osmimg

import java.io.File
import java.io.InputStream

/*
 * Putting files of any kind on a Garmin (each in the folder the device reads it from), and backing a
 * device up to the computer and putting the backup back.
 */

/**
 * A kind of file a user may put on a device. The folder comes from the device's GarminDevice.xml: the
 * entries named [dataTypes] (or starting with one ending in '*') that take [exts] towards the unit.
 * [fallback] is used when there is no GarminDevice.xml. [onCard] says the card takes it in the same folder.
 */
enum class AddKind(val label: String, val exts: Set<String>, val dataTypes: List<String>, val fallback: String, val onCard: Boolean) {
    MAP("Map", setOf("img"), emptyList(), "Garmin", true),
    GPX("Tracks, routes and waypoints", setOf("gpx"), listOf("GPSData", "UserDataSync"), "Garmin/GPX", true),
    POI("Points of interest", setOf("gpi"), listOf("CustomPOI"), "Garmin/POI", true),
    BIRDSEYE("BirdsEye image", setOf("jnx"), listOf("BirdsEye"), "Garmin/BirdsEye", true),
    CUSTOM_MAP("Custom map", setOf("kmz"), listOf("CustomMaps"), "Garmin/CustomMaps", true),
    GEOCACHES("Geocaches", setOf("ggz"), listOf("GGZ"), "Garmin/GGZ", true),
    PHOTO("Photo", setOf("jpg", "jpeg"), listOf("GeotaggedPhotos"), "Garmin/JPEG", true),
    VOICE("Voice", setOf("vpm"), listOf("Voices", "VCTVoices"), "Garmin/Voice", false),
    VEHICLE("Vehicle", setOf("srf"), listOf("Vehicle"), "Garmin/Vehicle", false),
    FIT("Course, workout or settings file", setOf("fit"), listOf("FIT_TYPE_*"), "Garmin/NewFiles", false),
    TCX("Course or workout", setOf("tcx"), listOf("FitnessCourses", "FitnessWorkouts"), "Garmin/NewFiles", false),
    CONNECT_IQ("Connect IQ app", setOf("prg"), listOf("IQ*"), "Garmin/Apps", false),
    LANGUAGE("Language file", setOf("gtt"), listOf("TranslatedText"), "Garmin/Text", false);

    companion object {
        fun of(fileName: String): AddKind? = fileName.substringAfterLast('.', "").lowercase().let { e -> entries.firstOrNull { e in it.exts } }
        val allExts: List<String> get() = entries.flatMap { it.exts }
    }
}

private fun AddKind.matches(dataType: String) = dataTypes.any { d ->
    if (d.endsWith("*")) dataType.startsWith(d.dropLast(1), true) else dataType.equals(d, true)
}

/**
 * The folder [kind] goes in on the device's built-in memory, or null when the device's
 * GarminDevice.xml doesn't list that kind of file at all. Not for maps (see [planInstall]).
 * Among several folders, the one a "Garmin/..." path names is taken ("Garmin/JPEG" before "Pictures").
 */
fun addFolder(kind: AddKind, xml: DeviceXml?): String? {
    if (xml == null || xml.dataFiles.isEmpty()) return kind.fallback
    val ok = xml.dataFiles.filter { it.toUnit && it.baseName == null && it.ext in kind.exts && kind.matches(it.dataType) && !it.path.startsWith(".") }
    return (ok.firstOrNull { it.path.startsWith("Garmin/", true) } ?: ok.firstOrNull())?.path
}

/** One file to put on the device: what it is called, how big, how to read it, and its map details when it's an .img. */
class AddSource(val name: String, val bytes: Long, val details: ImgDetails? = null, val open: () -> InputStream)

/** Where one picked file would go; [problems] keep it off, [warnings] are for the user to weigh. */
data class AddPlan(
    val source: AddSource,
    val kind: AddKind?,
    val storage: Storage,
    val path: String?,
    val replaces: Boolean,
    val problems: List<String>,
    val warnings: List<String>,
) {
    val ok: Boolean get() = path != null && problems.isEmpty()
}

/** A name FAT drives and Garmins take: no \ / : * ? " < > | or control characters, not too long. */
fun safeFileName(name: String): String {
    val ext = name.substringAfterLast('.', "")
    val base = name.removeSuffix(".$ext").replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), "_").trim(' ', '.', '_').take(64)
    return base.ifEmpty { "file" } + if (ext.isEmpty()) "" else ".${ext.lowercase()}"
}

/**
 * Plans putting [files] on [storage]. [contents] is what the device holds now (for names already
 * taken and maps that would clash), [xml] the device's own description.
 */
fun planAdd(files: List<AddSource>, storage: Storage, contents: DeviceContents?, xml: DeviceXml?, profile: DeviceProfile,
            existing: Collection<String>): List<AddPlan> {
    val taken = existing.map { it.lowercase() }.toMutableSet()
    val model = xml?.model?.ifEmpty { null } ?: "device"
    return files.map { src ->
        val problems = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val kind = AddKind.of(src.name)
        var path: String? = null
        var replaces = false
        when {
            kind == null -> problems += "A Garmin doesn't use .${src.name.substringAfterLast('.', "?")} files."
            kind == AddKind.MAP -> {
                val d = src.details
                if (d == null) problems += "This isn't a Garmin map (.img) that can be read."
                else {
                    d.damaged?.let { problems += "The map is damaged: $it" }
                    val plan = planInstall(profile, storage, src.name, src.bytes, taken)
                    problems += plan.problems; warnings += plan.warnings
                    path = plan.target?.path
                    // A map that is already there, or another map with the same ID: the device would show only one.
                    contents?.maps?.forEach { m ->
                        val same = m.details?.families.orEmpty().map { it.familyId }.intersect(d.families.map { it.familyId }.toSet())
                        if (same.isNotEmpty()) warnings += if (m.bytes == src.bytes && m.details?.description == d.description)
                            "This map is already on the device as ${m.path} (${storageName(m.storage)})."
                        else "${m.path} on the ${storageName(m.storage)} uses the same map ID (${same.first()}); the device may show only one of them."
                    }
                    if (d.locked && d.unlockCodes == 0) warnings += "The map is locked and has no unlock code inside; it may need one from Garmin to show."
                }
            }
            storage == Storage.SD && !kind.onCard -> problems += "${kind.label} files go on the built-in memory, not the card."
            else -> {
                val folder = if (storage == Storage.SD && xml != null) addFolder(kind, xml)?.let { cardFolder(it) } else addFolder(kind, xml)
                if (folder == null) problems += "The $model doesn't list .${kind.exts.first()} files (${kind.label}) among those it reads."
                else {
                    path = "$folder/${safeFileName(src.name)}"
                    if (xml == null) warnings += "Without its GarminDevice.xml, NaviLink uses the usual folder, $folder."
                    if (storage == Storage.SD && !folder.equals(addFolder(kind, xml), true)) warnings += "On the card this goes in $folder; not checked on a real $model card yet."
                }
            }
        }
        profile.maxFileBytes?.let { max -> if (kind != AddKind.MAP && src.bytes > max) problems += "The file is bigger than the drive takes (${max / 1_048_576} MB per file)." }
        val p = path
        if (p != null && problems.isEmpty()) {
            replaces = p.lowercase() in taken
            if (replaces && kind != AddKind.MAP) warnings += "Replaces the ${p.substringAfterLast('/')} on the device (it is copied to this computer first)."
            taken += p.lowercase()
        }
        AddPlan(src, kind, storage, p, replaces, problems, warnings)
    }
}

/** A folder from the built-in memory's list, as it is on the card: under Garmin/ ("POI" on a nüvi -> "Garmin/POI"). */
private fun cardFolder(path: String) = if (path.startsWith("Garmin/", true) || path.equals("Garmin", true)) path else "Garmin/$path"

/** The planned files that can go on, as copies for [installMaps] (which checks space, copies via .tmp and keeps replaced files). */
fun addCopies(plans: List<AddPlan>): List<MapCopy> = plans.filter { it.ok }.map { MapCopy(it.source.name, it.source.bytes, it.storage, it.path!!, it.source.open) }

// ---- Backup and restore ----

/** What a backup can hold, so a user can leave out the big parts. */
enum class BackupPart(val label: String) {
    YOUR_MAPS("Your maps, BirdsEye images and custom maps"),
    DEVICE_MAPS("The device's own maps and system files"),
    OTHER("Everything else: tracks, saved places, POIs, settings"),
}

data class BackupFile(val storage: Storage, val path: String, val bytes: Long, val part: BackupPart)

/** Folders that belong to the computer or phone that last had the drive, not to the Garmin. */
private val NOT_DEVICE = setOf("system volume information", "\$recycle.bin", ".trashes", ".spotlight-v100", ".fseventsd", "lost.dir", ".android_secure")

/** Every file on [drives], sorted into [BackupPart]s by what [contents] found. Left-over .tmp copies are skipped. */
fun backupFiles(drives: Map<Storage, DriveFs>, contents: DeviceContents?): List<BackupFile> {
    val kinds = contents?.files.orEmpty().associateBy { it.storage to it.path.lowercase() }
    val out = mutableListOf<BackupFile>()
    for ((st, fs) in drives.toSortedMap()) {
        fun walk(dir: String, depth: Int) {
            if (depth > 12) return
            for (e in fs.list(dir).orEmpty().sortedBy { it.name.lowercase() }) {
                val p = if (dir.isEmpty()) e.name else "$dir/${e.name}"
                if (e.isDir) { if (dir.isNotEmpty() || e.name.lowercase() !in NOT_DEVICE) walk(p, depth + 1); continue }
                if (e.name.endsWith(".tmp", true)) continue
                val part = when (kinds[st to p.lowercase()]?.kind) {
                    FileKind.MAP, FileKind.BIRDSEYE, FileKind.CUSTOM_MAP -> BackupPart.YOUR_MAPS
                    FileKind.SYSTEM -> BackupPart.DEVICE_MAPS
                    null -> if (p.startsWith(".System/", true) || e.name.endsWith(".img", true)) BackupPart.DEVICE_MAPS else BackupPart.OTHER
                }
                out += BackupFile(st, p, e.bytes, part)
            }
        }
        walk("", 0)
    }
    return out
}

/** A backup on this computer: a folder with int/ and sd/ copies of the drives and a list of what's in them. */
data class DeviceBackup(val dir: File, val model: String, val partNumber: String, val made: Long, val files: List<BackupFile>) {
    val bytes: Long get() = files.sumOf { it.bytes }
    fun file(f: BackupFile) = File(dir, "${f.storage.name.lowercase()}/${f.path}")
}

private const val MANIFEST = "navilink-backup.txt"

private fun stampNow() = java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss").format(java.util.Date())

/**
 * Copies [files] from [drives] to a new folder in [root] named after the model and the time. Each file is
 * checked for its size after the copy; the list of files is written last, so a stopped backup has none
 * and isn't offered for restore.
 */
fun backupDevice(drives: Map<Storage, DriveFs>, files: List<BackupFile>, root: File, xml: DeviceXml?,
                 log: (String) -> Unit, cancelled: () -> Boolean = { false }): DeviceBackup {
    val model = xml?.model?.ifEmpty { null } ?: "Garmin"
    val dir = File(root, "${model.replace(Regex("[^A-Za-z0-9 ._-]"), "_")} ${stampNow()}")
    var done = 0L
    val total = files.sumOf { it.bytes }
    files.forEachIndexed { i, f ->
        check(!cancelled()) { "stopped" }
        val fs = drives[f.storage] ?: error("the ${storageName(f.storage)} isn't there any more")
        val t = File(dir, "${f.storage.name.lowercase()}/${f.path}")
        t.parentFile.mkdirs()
        log("Backing up ${i + 1} of ${files.size}: ${f.path} (${pct(done, total)})")
        fs.openRead(f.path).use { inp -> t.outputStream().use { inp.copyTo(it, 1 shl 20) } }
        check(t.length() == f.bytes) { "the copy of ${f.path} is ${t.length()} bytes, the device has ${f.bytes}" }
        done += f.bytes
    }
    val b = DeviceBackup(dir, model, xml?.partNumber ?: "", System.currentTimeMillis(), files)
    File(dir, MANIFEST).writeText(buildString {
        append("NLBACKUP1\n"); append("model\t${b.model}\n"); append("part\t${b.partNumber}\n"); append("made\t${b.made}\n")
        files.forEach { append("${it.storage.name}\t${it.part.name}\t${it.bytes}\t${it.path}\n") }
    })
    log("Backed up ${files.size} files (${total / 1_048_576} MB) to ${dir.path}")
    return b
}

private fun pct(done: Long, total: Long) = if (total <= 0) "0%" else "${done * 100 / total}%"

/** The finished backups in [root], newest first. */
fun listBackups(root: File): List<DeviceBackup> = root.listFiles().orEmpty().mapNotNull { d ->
    runCatching {
        val lines = File(d, MANIFEST).readLines()
        check(lines.first() == "NLBACKUP1")
        val head = lines.drop(1).takeWhile { it.count { c -> c == '\t' } == 1 }.associate { it.substringBefore('\t') to it.substringAfter('\t') }
        val files = lines.drop(1 + head.size).filter { it.isNotBlank() }.map { l ->
            val (st, part, bytes, path) = l.split('\t', limit = 4)
            BackupFile(Storage.valueOf(st), path, bytes.toLong(), BackupPart.valueOf(part))
        }
        DeviceBackup(d, head["model"] ?: "Garmin", head["part"] ?: "", head["made"]?.toLong() ?: d.lastModified(), files)
    }.getOrNull()
}.sortedByDescending { it.made }

enum class RestoreAction { COPY, SAME, CHANGED, NO_STORAGE }

data class RestoreItem(val file: BackupFile, val action: RestoreAction)

/**
 * What putting [b] back would do on [drives]: copy files that are missing, skip the ones that are the same,
 * and list those that changed since. Files up to 16 MB are compared byte by byte, bigger ones by size.
 */
fun planRestore(b: DeviceBackup, drives: Map<Storage, DriveFs>, parts: Set<BackupPart>): List<RestoreItem> =
    b.files.filter { it.part in parts }.map { f ->
        val fs = drives[f.storage] ?: return@map RestoreItem(f, RestoreAction.NO_STORAGE)
        val now = fs.length(f.path)
        val action = when {
            now == null -> RestoreAction.COPY
            now != f.bytes -> RestoreAction.CHANGED
            f.bytes <= 16L shl 20 && !(fs.readBytes(f.path)?.contentEquals(b.file(f).readBytes()) ?: false) -> RestoreAction.CHANGED
            else -> RestoreAction.SAME
        }
        RestoreItem(f, action)
    }

/**
 * Puts the files of [items] back: the missing ones, and the changed ones too when [replaceChanged].
 * The device's own versions of replaced files go to [safetyDir] first. Nothing on the device is deleted.
 */
fun restoreBackup(b: DeviceBackup, items: List<RestoreItem>, replaceChanged: Boolean, drives: Map<Storage, DriveFs>,
                  safetyDir: File, log: (String) -> Unit, cancelled: () -> Boolean = { false }): Int {
    val copies = items.filter { it.action == RestoreAction.COPY || (replaceChanged && it.action == RestoreAction.CHANGED) }.map { i ->
        val src = b.file(i.file)
        check(src.length() == i.file.bytes) { "the backup's copy of ${i.file.path} isn't complete" }
        MapCopy(i.file.path.substringAfterLast('/'), i.file.bytes, i.file.storage, i.file.path) { src.inputStream() }
    }
    if (copies.isEmpty()) { log("Nothing to put back: the device already has every file."); return 0 }
    installMaps(copies, drives, safetyDir, log, cancelled)
    return copies.size
}
