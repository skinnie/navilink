package app.navilink.nuvi

import org.w3c.dom.Element
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

/**
 * Sending by cable: the nüvi reads GPX files in Garmin/GPX when it starts, adds their waypoints
 * to its saved places and their routes to its trips (the same way Garmin BaseCamp sends data).
 * NaviLink keeps everything it sends in one file, [CABLE_FILE], and adds to it on each send.
 */
const val CABLE_FILE = "NaviLink.gpx"

data class Route(val name: String, val stops: List<Place>)

/** What [CABLE_FILE] holds. */
data class CableFile(val places: List<Place> = emptyList(), val routes: List<Route> = emptyList()) {
    /** Adds places not already in the file, and a route (replacing one with the same name). */
    fun add(newPlaces: List<Place>, route: Route? = null): CableFile {
        val ps = places.toMutableList()
        for (p in newPlaces) if (ps.none { it.samePlace(p) }) ps += p
        val rs = routes.filter { it.name != route?.name } + listOfNotNull(route)
        return CableFile(ps, rs)
    }
}

/** Same name, and within about a metre. */
private fun Place.samePlace(o: Place) =
    name.trim().equals(o.name.trim(), true) && abs(lat - o.lat) < 1e-5 && abs(lon - o.lon) < 1e-5

/** "First stop - last stop", short enough for the nüvi's list. */
fun routeName(stops: List<Place>): String {
    val n = if (stops.size < 2) stops.firstOrNull()?.name ?: "Route" else "${stops.first().name} - ${stops.last().name}"
    return if (n.length <= 50) n else n.take(49).trimEnd() + "…"
}

private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
private fun latLon(p: Place) = "lat=\"%.7f\" lon=\"%.7f\"".format(Locale.ROOT, p.lat, p.lon)

/** GPX 1.1 with Garmin's extensions (address, phone, category), laid out like BaseCamp's. */
fun writeCableGpx(f: CableFile): ByteArray = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\" ?>\n")
    append("<gpx xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:gpxx=\"http://www.garmin.com/xmlschemas/GpxExtensions/v3\"")
    append(" creator=\"NaviLink\" version=\"1.1\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
    append(" xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd")
    append(" http://www.garmin.com/xmlschemas/GpxExtensions/v3 http://www8.garmin.com/xmlschemas/GpxExtensionsv3.xsd\">\n")
    append("  <metadata><name>NaviLink</name></metadata>\n")
    for (p in f.places) {
        append("  <wpt ${latLon(p)}>\n")
        append("    <name>${xml(p.name)}</name>\n")
        append("    <sym>Flag, Blue</sym>\n")
        val cat = p.category?.takeIf { it.isNotBlank() }
        val addr = listOf("StreetAddress" to p.street, "City" to p.city, "State" to p.region, "Country" to p.country, "PostalCode" to p.postcode)
            .filter { !it.second.isNullOrBlank() }
        val phone = p.phone?.takeIf { it.isNotBlank() }
        if (cat != null || addr.isNotEmpty() || phone != null) {
            append("    <extensions>\n      <gpxx:WaypointExtension>\n")
            if (cat != null) append("        <gpxx:Categories><gpxx:Category>${xml(cat)}</gpxx:Category></gpxx:Categories>\n")
            if (addr.isNotEmpty()) {
                append("        <gpxx:Address>\n")
                for ((tag, v) in addr) append("          <gpxx:$tag>${xml(v!!)}</gpxx:$tag>\n")
                append("        </gpxx:Address>\n")
            }
            if (phone != null) append("        <gpxx:PhoneNumber>${xml(phone)}</gpxx:PhoneNumber>\n")
            append("      </gpxx:WaypointExtension>\n    </extensions>\n")
        }
        append("  </wpt>\n")
    }
    for (r in f.routes) {
        append("  <rte>\n")
        append("    <name>${xml(r.name)}</name>\n")
        append("    <extensions><gpxx:RouteExtension><gpxx:IsAutoNamed>false</gpxx:IsAutoNamed>")
        append("<gpxx:DisplayColor>Magenta</gpxx:DisplayColor></gpxx:RouteExtension></extensions>\n")
        for (p in r.stops) append("    <rtept ${latLon(p)}><name>${xml(p.name)}</name><sym>Flag, Blue</sym></rtept>\n")
        append("  </rte>\n")
    }
    append("</gpx>\n")
}.toByteArray(Charsets.UTF_8)

/** Reads a file written by [writeCableGpx] (or any GPX: its waypoints and routes). */
fun readCableGpx(data: ByteArray): CableFile {
    val f = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        isExpandEntityReferences = false
    }
    val root = f.newDocumentBuilder().parse(data.inputStream()).documentElement
    fun Element.kids(tag: String) = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
        .filter { it.tagName.substringAfter(':') == tag }
    fun Element.deep(tag: String) = getElementsByTagName("*").let { nl -> (0 until nl.length).map { nl.item(it) as Element } }
        .firstOrNull { it.tagName.substringAfter(':') == tag }?.textContent?.trim()?.takeIf { it.isNotEmpty() }
    fun Element.place(fallback: String): Place? {
        val lat = getAttribute("lat").toDoubleOrNull() ?: return null
        val lon = getAttribute("lon").toDoubleOrNull() ?: return null
        val name = kids("name").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
        return Place(name, lat, lon, street = deep("StreetAddress"), postcode = deep("PostalCode"), city = deep("City"),
            region = deep("State"), country = deep("Country"), phone = deep("PhoneNumber"), category = deep("Category"))
    }
    val places = root.kids("wpt").mapIndexedNotNull { i, e -> e.place("Place ${i + 1}") }
    val routes = root.kids("rte").mapIndexed { i, r ->
        val stops = r.kids("rtept").mapIndexedNotNull { j, e -> e.place("Stop ${j + 1}") }
        Route(r.kids("name").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: "Route ${i + 1}", stops)
    }
    return CableFile(places, routes)
}
