package app.navilink.nuvi

import app.navilink.core.readGpi
import app.navilink.core.readGpx
import java.util.Locale

/** POIs from a .gpx (waypoints, then route points) or a Garmin .gpi file, recognised by content. */
fun placesFromFile(name: String, data: ByteArray): List<Place> {
    val isGpi = data.size > 8 && String(data, 0, 6, Charsets.ISO_8859_1) == "GRMREC"
    if (isGpi || name.endsWith(".gpi", true)) {
        return readGpi(data).categories.flatMap { c ->
            c.points.filter { it.hasCoords }.map { Place(it.name, it.lat, it.lon, category = c.name) }
        }
    }
    val g = readGpx(data)
    // No waypoints: a route or track, thinned to the points that shape it most.
    val pts = g.waypoints.ifEmpty { app.navilink.core.thinLine(g.line, 30) }
    return pts.filter { it.hasCoords }.map { Place(it.name, it.lat, it.lon) }
}

private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** Places as GPX 1.1 waypoints (Garmin's own POI Loader and BaseCamp read this). */
fun placesToGpx(places: List<Place>, title: String = "NaviLink"): ByteArray = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<gpx version=\"1.1\" creator=\"NaviLink\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
    append("  <metadata><name>${xml(title)}</name></metadata>\n")
    for (p in places) {
        append("  <wpt lat=\"%.7f\" lon=\"%.7f\">".format(Locale.ROOT, p.lat, p.lon))
        append("<name>${xml(p.name)}</name>")
        val desc = listOfNotNull(p.street, p.city, p.phone).joinToString(", ")
        if (desc.isNotEmpty()) append("<desc>${xml(desc)}</desc>")
        p.category?.let { append("<type>${xml(it)}</type>") }
        append("</wpt>\n")
    }
    append("</gpx>\n")
}.toByteArray(Charsets.UTF_8)
