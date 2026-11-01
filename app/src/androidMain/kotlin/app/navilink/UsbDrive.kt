package app.navilink

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.navilink.nuvi.CABLE_FILE
import app.navilink.nuvi.CableFile
import app.navilink.nuvi.Place
import app.navilink.nuvi.Route
import app.navilink.nuvi.buildGpi
import app.navilink.nuvi.gpiFileName
import app.navilink.nuvi.readCableGpx
import app.navilink.nuvi.writeCableGpx

/*
 * The nüvi's drive over USB OTG (nüvi in mass-storage mode), through the folder the user picked:
 * the drive itself or its Garmin folder. Older versions also let the user pick Garmin/POI.
 */

private fun DocumentFile.child(name: String) = listFiles().firstOrNull { it.name.equals(name, true) }
private fun DocumentFile.dir(name: String) =
    child(name)?.takeIf { it.isDirectory } ?: createDirectory(name) ?: error("Can't create $name")

private fun picked(ctx: Context, tree: Uri): DocumentFile {
    val root = DocumentFile.fromTreeUri(ctx, tree)
    if (root == null || !root.exists()) error("The nüvi's drive isn't there. Plug it in and wait for Android to show it.")
    return root
}

/** Garmin folder on the drive. Refuses a drive without one, so nothing gets written to the phone by mistake. */
private fun garminDir(root: DocumentFile): DocumentFile = when {
    root.name.equals("Garmin", true) -> root
    else -> root.child("Garmin")?.takeIf { it.isDirectory } ?: error("No Garmin folder there. Pick the nüvi's drive.")
}

/** True when [tree] is still reachable and writable (the nüvi is plugged in). */
fun driveReady(ctx: Context, tree: Uri): Boolean = runCatching { picked(ctx, tree).canWrite() }.getOrDefault(false)

fun driveName(ctx: Context, tree: Uri): String? = runCatching { DocumentFile.fromTreeUri(ctx, tree)?.name }.getOrNull()

/**
 * Put POIs on the nüvi as Garmin/POI/<category>.gpi. Places that came with a category (from a GPI)
 * keep it; the rest go under [defaultCategory]. Returns the files written.
 */
fun installGpis(ctx: Context, tree: Uri, places: List<Place>, defaultCategory: String): List<String> {
    val root = picked(ctx, tree)
    val poiDir = if (root.name.equals("POI", true)) root else garminDir(root).dir("POI")
    return places.groupBy { it.category?.takeIf { c -> c.isNotBlank() } ?: defaultCategory }.map { (cat, ps) ->
        val name = gpiFileName(cat)
        val data = buildGpi(cat, ps, name)
        val f = poiDir.child(name) ?: poiDir.createFile("application/octet-stream", name) ?: error("Can't create $name")
        ctx.contentResolver.openOutputStream(f.uri, "wt")!!.use { it.write(data) }
        "Garmin/POI/${f.name} (${ps.size})"
    }
}

private fun gpxDir(ctx: Context, tree: Uri): DocumentFile {
    val root = picked(ctx, tree)
    if (root.name.equals("POI", true)) error("Pick the nüvi's drive (not its POI folder).")
    return garminDir(root).dir("GPX")
}

/**
 * Add places (for Saved) and/or a route (for Trip Planner) to Garmin/GPX/NaviLink.gpx, keeping what
 * is already in it. Returns the file's new contents. An unreadable old file is started again.
 */
fun sendByCable(ctx: Context, tree: Uri, places: List<Place>, route: Route?): CableFile {
    val dir = gpxDir(ctx, tree)
    val old = dir.child(CABLE_FILE)
    val before = old?.let { f ->
        runCatching { readCableGpx(ctx.contentResolver.openInputStream(f.uri)!!.use { it.readBytes() }) }
            .onFailure { State.log("Couldn't read the old $CABLE_FILE (${it.message}); starting a new one.") }.getOrNull()
    } ?: CableFile()
    val after = before.add(places, route)
    val f = old ?: dir.createFile("application/octet-stream", CABLE_FILE) ?: error("Can't create $CABLE_FILE")
    ctx.contentResolver.openOutputStream(f.uri, "wt")!!.use { it.write(writeCableGpx(after)) }
    return after
}

/** Delete Garmin/GPX/NaviLink.gpx. Returns false when there was none. */
fun removeCableFile(ctx: Context, tree: Uri): Boolean {
    val f = gpxDir(ctx, tree).child(CABLE_FILE) ?: return false
    if (!f.delete()) error("Can't delete $CABLE_FILE")
    return true
}
