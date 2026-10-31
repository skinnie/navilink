package app.navilink

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.navilink.nuvi.Place
import app.navilink.nuvi.buildGpi
import app.navilink.nuvi.gpiFileName

/**
 * Put POIs on the nüvi's disk (USB OTG, nüvi in mass-storage mode) as Garmin/POI/<category>.gpi.
 * [tree] is the folder the user picked: the nüvi's root, its Garmin folder or Garmin/POI.
 * Places that came with a category (from a GPI) keep it; the rest go under [defaultCategory].
 * Returns the files written.
 */
fun installGpis(ctx: Context, tree: Uri, places: List<Place>, defaultCategory: String): List<String> {
    val root = DocumentFile.fromTreeUri(ctx, tree) ?: error("Can't open that folder")
    fun DocumentFile.child(name: String) = listFiles().firstOrNull { it.name.equals(name, true) }
    fun DocumentFile.dir(name: String) = child(name)?.takeIf { it.isDirectory } ?: createDirectory(name) ?: error("Can't create $name")
    val poiDir = when {
        root.name.equals("POI", true) -> root
        root.name.equals("Garmin", true) -> root.dir("POI")
        else -> root.dir("Garmin").dir("POI")
    }
    return places.groupBy { it.category?.takeIf { c -> c.isNotBlank() } ?: defaultCategory }.map { (cat, ps) ->
        val name = gpiFileName(cat)
        val data = buildGpi(cat, ps, name)
        val f = poiDir.child(name) ?: poiDir.createFile("application/octet-stream", name) ?: error("Can't create $name")
        ctx.contentResolver.openOutputStream(f.uri, "wt")!!.use { it.write(data) }
        "Garmin/POI/${f.name} (${ps.size})"
    }
}
