package osmimg

import java.io.File

/**
 * A mounted drive that could be a Garmin or its SD card. [xml] is read from Garmin/GarminDevice.xml;
 * a drive without one is an SD card, an older Garmin, or just a USB stick.
 */
data class Drive(val root: File, val label: String, val xml: DeviceXml?, val freeBytes: Long, val totalBytes: Long) {
    val isGarmin: Boolean get() = xml != null
    val hasGarminFolder: Boolean get() = File(root, "Garmin").isDirectory || File(root, "garmin").isDirectory
}

/** Where removable drives are mounted on this computer. */
private fun mountRoots(): List<File> {
    val os = System.getProperty("os.name").lowercase()
    val user = System.getProperty("user.name")
    return when {
        os.contains("win") -> File.listRoots().filter { it.path.uppercase() !in setOf("C:\\") }
        os.contains("mac") -> File("/Volumes").listFiles()?.toList().orEmpty()
        else -> listOf(File("/media/$user"), File("/run/media/$user"), File("/mnt"))
            .flatMap { it.listFiles()?.filter(File::isDirectory).orEmpty() }
    }
}

/** A child of [dir] named [name] in any case (FAT drives mounted case-sensitively). */
internal fun childIgnoreCase(dir: File, name: String): File? =
    File(dir, name).takeIf { it.exists() } ?: dir.listFiles()?.firstOrNull { it.name.equals(name, true) }

fun readDrive(root: File): Drive {
    val xml = childIgnoreCase(root, "Garmin")?.let { childIgnoreCase(it, "GarminDevice.xml") }
        ?.let { runCatching { readGarminDeviceXml(it.readBytes()) }.getOrNull() }
    return Drive(root, root.name.ifEmpty { root.path }, xml, root.usableSpace, root.totalSpace)
}

/**
 * On Linux: whether the disk under a mount point is removable (a Garmin, a card reader, a stick) per
 * /sys/block, so internal disks and USB hard disks are left out. Null when it can't be told.
 */
internal fun linuxRemovable(root: File): Boolean? = runCatching {
    val mounts = File("/proc/mounts").readLines().map { it.split(' ') }
    val dev = mounts.lastOrNull { it.size > 1 && it[1].replace("\\040", " ") == root.path }?.get(0) ?: return null
    val name = dev.substringAfterLast('/')
    if (name.startsWith("mmcblk")) return true // the PC's own SD slot
    val disk = name.replace(Regex("(?<=[a-z])\\d+$"), "")
    File("/sys/block/$disk/removable").readText().trim() == "1"
}.getOrNull()

/** Drives that could take a map: mounted, writable, removable where that can be told, and not over 1 TB. */
fun findDrives(): List<Drive> = mountRoots()
    .filter { runCatching { it.canWrite() && it.totalSpace in 1..(1L shl 40) && linuxRemovable(it) != false }.getOrDefault(false) }
    .map(::readDrive)
    .sortedWith(compareByDescending<Drive> { it.isGarmin }.thenByDescending { it.hasGarminFolder }.thenBy { it.label })

/** The .img files already on [root], as paths relative to it in the device's own spelling. */
fun existingImgs(root: File, profile: DeviceProfile, st: Storage): List<String> = existingImgs(FileDriveFs(root), profile, st)

fun storageState(root: File, profile: DeviceProfile, st: Storage) = storageState(FileDriveFs(root), profile, st)

/** A map already on the device and the map sets in it. */
data class InstalledMap(val storage: Storage, val path: String, val bytes: Long, val info: ImgInfo?)

fun installedMaps(roots: Map<Storage, File>, profile: DeviceProfile): List<InstalledMap> =
    installedMaps(roots.mapValues<Storage, File, DriveFs> { FileDriveFs(it.value) }, profile)

/** [rel] under [root], matching existing folders and files in any case. */
internal fun resolveIgnoreCase(root: File, rel: String): File =
    rel.split('/').filter { it.isNotEmpty() }.fold(root) { dir, part -> childIgnoreCase(dir, part) ?: File(dir, part) }

/** Maps on the device that use one of the family IDs of a map about to go on, so one of them would not show. */
fun familyClashes(installed: List<InstalledMap>, incoming: Collection<Int>, replacing: Collection<String> = emptyList()): List<String> {
    val skip = replacing.map { it.lowercase() }.toSet()
    return installed.filter { it.path.lowercase() !in skip }.mapNotNull { m ->
        val same = m.info?.families?.filter { it.familyId in incoming }.orEmpty()
        if (same.isEmpty()) null
        else "This map clashes with \"${m.info?.description?.ifEmpty { null } ?: same.first().name}\" (${m.path}) " +
            "on the ${storageName(m.storage)}: the device shows only one of them. Replace that map instead."
    }
}

/** One file to copy: [file] goes to [path] on [storage]. */
data class Copy(val file: File, val storage: Storage, val path: String)

/** Copies maps to drives mounted on this computer; see the [DriveFs] version. */
fun installMaps(copies: List<Copy>, roots: Map<Storage, File>, backupDir: File, log: (String) -> Unit): List<File> =
    installMaps(copies.map { c -> MapCopy(c.file.name, c.file.length(), c.storage, c.path) { c.file.inputStream() } },
        roots.mapValues<Storage, File, DriveFs> { FileDriveFs(it.value, it.value.path) }, backupDir, log)
