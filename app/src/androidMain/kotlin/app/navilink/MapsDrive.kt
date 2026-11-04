package app.navilink

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import app.navilink.maps.MapFile
import app.navilink.maps.MapsHost
import osmimg.DriveFs
import osmimg.FsEntry
import osmimg.ImgInfo
import osmimg.readImgInfo
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * The Garmin's drive (or its memory card) over USB OTG, through the folder the user picked with
 * Android's folder picker. If they picked the Garmin folder rather than the drive, only paths under
 * Garmin/ can be reached, which is where maps go on most units.
 */
class SafDriveFs(private val ctx: Context, private val tree: Uri) : DriveFs {
    private val root: DocumentFile = DocumentFile.fromTreeUri(ctx, tree)?.takeIf { it.exists() }
        ?: error("The drive isn't there. Plug it in and wait for Android to show it.")
    private val garminOnly = root.name.equals("Garmin", true)

    override val name: String = root.name ?: "drive"

    /** Path segments below [root], or null when the path is outside what was picked. */
    private fun parts(path: String): List<String>? {
        val p = path.split('/').filter { it.isNotEmpty() }
        if (!garminOnly) return p
        return if (p.firstOrNull().equals("Garmin", true)) p.drop(1) else null
    }

    private fun DocumentFile.child(n: String) = listFiles().firstOrNull { it.name.equals(n, true) }

    private fun find(path: String): DocumentFile? {
        val p = parts(path) ?: return null
        return p.fold(root as DocumentFile?) { d, n -> d?.child(n) }
    }

    override fun list(dir: String): List<FsEntry>? {
        if (garminOnly && dir.isEmpty()) {
            // The root of what we can see is the Garmin folder: show it as Garmin/.
            return listOf(FsEntry(root.name ?: "Garmin", true, 0))
        }
        val d = find(dir)?.takeIf { it.isDirectory } ?: return null
        return d.listFiles().map { FsEntry(it.name ?: "", it.isDirectory, it.length()) }
    }

    override fun length(path: String): Long? = find(path)?.takeIf { it.isFile }?.length()

    override fun readBytes(path: String): ByteArray? = find(path)?.takeIf { it.isFile }?.let { f ->
        ctx.contentResolver.openInputStream(f.uri)?.use { it.readBytes() }
    }

    override fun info(path: String): ImgInfo? = find(path)?.takeIf { it.isFile }?.let { f ->
        runCatching {
            ctx.contentResolver.openFileDescriptor(f.uri, "r")!!.use { pfd -> FileInputStream(pfd.fileDescriptor).channel.use(::readImgInfo) }
        }.getOrNull()
    }

    /** From the volume behind the tree ("1A2B-3C4D:Garmin" is mounted at /storage/1A2B-3C4D). */
    override fun freeBytes(): Long? = runCatching {
        val volume = DocumentsContract.getTreeDocumentId(tree).substringBefore(':')
        val path = if (volume == "primary") Environment.getExternalStorageDirectory().path else "/storage/$volume"
        StatFs(path).availableBytes
    }.getOrNull()

    override fun openRead(path: String): InputStream =
        ctx.contentResolver.openInputStream((find(path) ?: error("no $path")).uri) ?: error("can't read $path")

    override fun write(path: String, from: InputStream): Long {
        val p = parts(path) ?: error("Pick the drive itself (not its Garmin folder): this map goes in ${path.substringBeforeLast('/')}.")
        var d = root
        for (n in p.dropLast(1)) d = d.child(n)?.takeIf { it.isDirectory } ?: d.createDirectory(n) ?: error("can't create the folder $n")
        val fileName = p.last()
        val f = d.child(fileName) ?: d.createFile("application/octet-stream", fileName) ?: error("can't create $fileName")
        check(f.name == fileName) { "the drive named the file ${f.name} instead of $fileName" }
        ctx.contentResolver.openFileDescriptor(f.uri, "wt")!!.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { out ->
                from.copyTo(out, 1 shl 20)
                out.flush()
                out.fd.sync()
            }
        }
        return find(path)?.length() ?: 0
    }

    override fun rename(path: String, newName: String) {
        val f = find(path) ?: error("no $path")
        check(f.renameTo(newName)) { "could not rename ${f.name} to $newName" }
    }

    override fun delete(path: String): Boolean = find(path)?.takeIf { it.isFile }?.delete() ?: false
}

/**
 * What the Maps screen needs from Android. Lives as long as the app; [activity] is the screen that
 * is up now, for the pickers.
 */
class AndroidMapsHost(private val app: Context) : MapsHost {
    var activity: MainActivity? = null
    private val prefs get() = app.getSharedPreferences("navilink", Context.MODE_PRIVATE)
    // The nüvi's drive is the same one the cable features use.
    private fun key(card: Boolean) = if (card) "usbSdTree" else "usbTree"

    override val cache: java.io.File get() = java.io.File(app.getExternalFilesDir(null) ?: app.filesDir, "osmimg")
    override val backupDir: java.io.File get() = java.io.File(app.getExternalFilesDir(null) ?: app.filesDir, "backup")
    override val backupPlace = "Android/data/app.navilink/files/backup on the phone"

    override fun remembered(card: Boolean): DriveFs? = prefs.getString(key(card), null)?.let { runCatching { SafDriveFs(app, Uri.parse(it)) }.getOrNull() }

    override suspend fun pickDrive(card: Boolean): DriveFs? {
        val a = activity ?: return null
        val tree = a.pickTree(prefs.getString(key(card), null)?.let(Uri::parse)) ?: return null
        val fs = SafDriveFs(app, tree)
        val e = prefs.edit().putString(key(card), tree.toString())
        if (!card) e.putString("usbName", fs.name)
        e.apply()
        if (!card) State.drive = fs.name
        return fs
    }

    override fun forget(card: Boolean) { prefs.edit().remove(key(card)).apply() }

    override suspend fun pickImg(): MapFile? {
        val a = activity ?: return null
        val uri = a.pickFile(arrayOf("application/octet-stream", "*/*")) ?: return null
        val cr = app.contentResolver
        var name = "map.img"
        var bytes = -1L
        cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0) ?: name; if (!c.isNull(1)) bytes = c.getLong(1) }
        }
        val info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { cr.openFileDescriptor(uri, "r")!!.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch -> if (bytes < 0) bytes = ch.size(); readImgInfo(ch) }
            } }.getOrNull()
        }
        return MapFile(name, bytes, info) { cr.openInputStream(uri) ?: error("can't read $name") }
    }
}
