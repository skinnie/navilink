package osmimg

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** A file or folder on a [DriveFs]. */
data class FsEntry(val name: String, val isDir: Boolean, val bytes: Long)

/**
 * A drive that maps go on, seen through what the platform offers: a mounted folder ([FileDriveFs])
 * or, on Android, the document tree of a USB drive. Paths are relative to the drive root, use '/',
 * and match existing names in any case (FAT drives are case-insensitive, their mounts often not).
 */
interface DriveFs {
    /** The drive's name, for messages. */
    val name: String
    /** What is in [dir] ("" is the root), or null when there is no such folder. */
    fun list(dir: String): List<FsEntry>?
    fun length(path: String): Long?
    fun readBytes(path: String): ByteArray?
    /** The map sets in the .img at [path], read without loading the whole file. */
    fun info(path: String): ImgInfo?
    /** Free space, or null when the platform can't tell. */
    fun freeBytes(): Long?
    fun openRead(path: String): InputStream
    /** Writes [from] to [path], creating folders, and syncs it to the disk before returning the size written. */
    fun write(path: String, from: InputStream): Long
    /** Renames the file at [path] to [newName] in the same folder. */
    fun rename(path: String, newName: String)
    fun delete(path: String): Boolean
}

/** The real spelling of [rel] on [fs] ("garmin/GMAPSUPP.IMG" -> "Garmin/gmapsupp.img"), as far as it exists. */
fun DriveFs.spelling(rel: String): String {
    var dir = ""
    for (part in rel.split('/').filter { it.isNotEmpty() }) {
        val real = list(dir)?.firstOrNull { it.name.equals(part, true) }?.name ?: part
        dir = if (dir.isEmpty()) real else "$dir/$real"
    }
    return dir
}

/** Garmin/GarminDevice.xml, if the drive has one that reads. */
fun DriveFs.deviceXml(): DeviceXml? =
    runCatching { readBytes("Garmin/GarminDevice.xml")?.let(::readGarminDeviceXml) }.getOrNull()

/** The .img files on [fs] in the folders a profile reads maps from, plus Garmin/ and Map/, in the drive's own spelling. */
fun existingImgs(fs: DriveFs, profile: DeviceProfile, st: Storage): List<String> =
    (profile.slots(st).map { it.folder } + listOf("Garmin", "Map")).distinctBy { it.lowercase() }.flatMap { folder ->
        val dir = if (folder.isEmpty()) "" else fs.list("")?.firstOrNull { it.isDir && it.name.equals(folder, true) }?.name
            ?: return@flatMap emptyList()
        fs.list(dir).orEmpty().filter { !it.isDir && it.name.endsWith(".img", true) }
            .map { if (dir.isEmpty()) it.name else "$dir/${it.name}" }
    }.distinctBy { it.lowercase() }.sorted()

fun storageState(fs: DriveFs, profile: DeviceProfile, st: Storage) = StorageState(existingImgs(fs, profile, st), fs.freeBytes())

@JvmName("installedMapsOn")
fun installedMaps(drives: Map<Storage, DriveFs>, profile: DeviceProfile): List<InstalledMap> =
    drives.flatMap { (st, fs) ->
        existingImgs(fs, profile, st).map { p -> InstalledMap(st, p, fs.length(p) ?: 0, fs.info(p)) }
    }

/** One map file to put on a device: [bytes] from [open] go to [path] on [storage]. */
class MapCopy(val name: String, val bytes: Long, val storage: Storage, val path: String, val open: () -> InputStream)

/**
 * Copies maps to the device. Each file is written under a temporary name and renamed when complete,
 * so a pulled cable never leaves a half map under a name the device loads. A file that is replaced
 * is copied to [backupDir] first. [cancelled] is checked between files. Returns the backups made.
 */
@JvmName("installMapsOn")
fun installMaps(copies: List<MapCopy>, drives: Map<Storage, DriveFs>, backupDir: File, log: (String) -> Unit,
                cancelled: () -> Boolean = { false }): List<File> {
    val backups = mutableListOf<File>()
    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss").format(java.util.Date())
    // Check space first: a copy that fails half way is worse than one that doesn't start.
    for ((st, list) in copies.groupBy { it.storage }) {
        val fs = drives[st] ?: error("no ${storageName(st)} chosen")
        val free = fs.freeBytes() ?: continue
        val need = list.sumOf { c -> c.bytes - (fs.length(c.path) ?: 0) }
        check(need <= free) { "not enough room on the ${storageName(st)}: it needs ${need / 1_048_576} MB and has ${free / 1_048_576} MB free" }
    }
    for (c in copies) {
        check(!cancelled()) { "stopped" }
        val fs = drives.getValue(c.storage)
        val path = fs.spelling(c.path)
        if (fs.length(path) != null) {
            val b = File(backupDir, "$stamp/${c.storage.name.lowercase()}/$path")
            log("Backing up $path to ${b.path} ...")
            b.parentFile.mkdirs()
            fs.openRead(path).use { inp -> b.outputStream().use { inp.copyTo(it, 1 shl 20) } }
            backups += b
        }
        log("Copying ${c.name} to ${fs.name}/$path (${c.bytes / 1_048_576} MB) ...")
        val tmp = "$path.tmp"
        fs.delete(tmp)
        val n = c.open().use { fs.write(tmp, it) }
        check(n == c.bytes && fs.length(tmp) == c.bytes) { "the copy of ${c.name} is incomplete" }
        if (fs.length(path) != null) check(fs.delete(path)) { "could not remove the old $path" }
        fs.rename(tmp, path.substringAfterLast('/'))
    }
    log("Done. Eject the device before unplugging it.")
    return backups
}

/** A drive mounted as a folder on this computer. */
class FileDriveFs(val root: File, override val name: String = root.name.ifEmpty { root.path }) : DriveFs {
    private fun f(path: String) = resolveIgnoreCase(root, path)
    override fun list(dir: String): List<FsEntry>? = f(dir).takeIf { it.isDirectory }?.listFiles()
        ?.map { FsEntry(it.name, it.isDirectory, if (it.isFile) it.length() else 0) }
    override fun length(path: String): Long? = f(path).takeIf { it.isFile }?.length()
    override fun readBytes(path: String): ByteArray? = f(path).takeIf { it.isFile }?.readBytes()
    override fun info(path: String): ImgInfo? = readImgInfo(f(path))
    override fun freeBytes(): Long = root.usableSpace
    override fun openRead(path: String): InputStream = f(path).inputStream()
    override fun write(path: String, from: InputStream): Long {
        val dest = f(path)
        dest.parentFile.mkdirs()
        FileOutputStream(dest).use { out ->
            from.copyTo(out, 1 shl 20)
            out.fd.sync()
        }
        return dest.length()
    }
    override fun rename(path: String, newName: String) {
        val from = f(path)
        check(from.renameTo(File(from.parentFile, newName))) { "could not rename ${from.name} to $newName" }
    }
    override fun delete(path: String): Boolean = f(path).let { it.isFile && it.delete() }
}
