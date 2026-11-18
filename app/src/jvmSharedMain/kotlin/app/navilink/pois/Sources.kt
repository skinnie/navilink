package app.navilink.pois

import app.navilink.core.*

/*
 * Where POIs come from: OpenStreetMap (Overpass API, ODbL, "© OpenStreetMap contributors") and Foursquare Open
 * Source Places (Apache-2.0). Shared with MapsToTom, minus its TomTom parts; each kind becomes one .gpi on the
 * Garmin, named after [category].
 */

/** What every kind of POI has, whatever its source. */
sealed interface PoiKind {
    val label: String
    /** The category on the Garmin, which is also the .gpi file's name. */
    val category: String
    val source: Source

    enum class Source(val title: String, val credit: String) {
        OSM("OpenStreetMap", "© OpenStreetMap contributors (ODbL)"),
        FOURSQUARE("Foursquare", "Foursquare Open Source Places (Apache-2.0)"),
    }
}

/** POI kinds from OpenStreetMap. [filter] is an Overpass selector; several are separated by ';'. */
enum class OsmPoiType(override val label: String, override val category: String, val filter: String) : PoiKind {
    FUEL("Fuel stations", "OSM Fuel", "nwr[amenity=fuel]"),
    LPG("LPG (autogas)", "OSM LPG", "nwr[amenity=fuel][\"fuel:lpg\"=yes]"),
    EV("EV chargers", "OSM EV chargers", "nwr[amenity=charging_station]"),
    MOTORHOME("Motorhome areas", "OSM Motorhome areas", "nwr[tourism=caravan_site]"),
    CAMPSITE("Campsites", "OSM Campsites", "nwr[tourism=camp_site]"),
    SUPERMARKET("Supermarkets", "OSM Supermarkets", "nwr[shop=supermarket]"),
    PHARMACY("Pharmacies", "OSM Pharmacies", "nwr[amenity=pharmacy]"),
    BEACH("Beaches", "OSM Beaches", "nwr[natural=beach]"),
    TOILETS("Public toilets", "OSM Toilets", "nwr[amenity=toilets][access!=private][access!=customers]"),
    DRINKING_WATER("Drinking water", "OSM Drinking water", "node[amenity=drinking_water][access!=private]"),
    VIEWPOINT("Viewpoints", "OSM Viewpoints", "node[tourism=viewpoint]"),
    PICNIC("Picnic sites", "OSM Picnic sites", "nwr[tourism=picnic_site]"),
    // Unnamed leisure=swimming_pool areas are mostly garden pools, so only named, non-private ones count.
    POOL("Swimming pools (public)", "OSM Swimming pools",
        "nwr[leisure=sports_centre][sport~\"swimming\"];nwr[leisure=swimming_pool][name][access!~\"^(private|no)$\"]"),
    SPEED_CAMERA("Speed cameras", "OSM Speed cameras", "node[highway=speed_camera]");

    override val source get() = PoiKind.Source.OSM

    /** Countries where this kind may not be used (France bans warnings of camera positions). */
    val blockedIn: Set<String> get() = if (this == SPEED_CAMERA) setOf("FR") else emptySet()
}

private val ISO_COUNTRIES = java.util.Locale.getISOCountries().toSet()

/** "PT, ES" → [PT, ES]; anything that isn't a 2-letter country code is an error naming it. */
fun parseCountries(text: String): List<String> {
    val parts = text.split(',', ' ', ';').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
    val bad = parts.filterNot { it.length == 2 && it in ISO_COUNTRIES }
    require(bad.isEmpty()) { "Not a country code: ${bad.joinToString()}. Use 2-letter codes separated by commas, like PT, ES." }
    require(parts.isNotEmpty()) { "Choose at least one country (2-letter code, like PT)" }
    return parts.distinct()
}

fun countriesProblem(text: String): String? = runCatching { parseCountries(text) }.exceptionOrNull()?.message

/** Public Overpass servers, tried in order: they are often busy. */
private val OVERPASS = listOf(
    "https://overpass-api.de/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
)

fun overpassQuery(type: OsmPoiType, countries: List<String>): String {
    val areas = countries.joinToString("") { "area[\"ISO3166-1\"=\"$it\"][admin_level=2];" }
    val selectors = type.filter.split(';').joinToString("") { "$it(area.a);" }
    return "[out:json][timeout:300];($areas)->.a;($selectors);out center tags;"
}

/** Downloads one kind for these countries, trying the next server when one is busy. */
fun fetchOsmPois(type: OsmPoiType, countries: List<String>, net: Net): List<Point> {
    val cc = countries.map { it.trim().uppercase() }.filter { it.matches(Regex("[A-Z]{2}")) }.distinct()
    require(cc.isNotEmpty()) { "Choose at least one country (2-letter code, like PT)" }
    val blocked = cc.filter { it in type.blockedIn }
    require(blocked.isEmpty()) { "${type.label} can't be installed for ${blocked.joinToString()}: warning of camera positions is illegal there." }
    val q = overpassQuery(type, cc)
    var last: String? = null
    for (server in OVERPASS) {
        val r = runCatching { net.get("$server?" + urlEncode(listOf("data" to q)), mapOf("User-Agent" to USER_AGENT), timeoutMs = 300_000) }
            .getOrElse { last = it.message; null } ?: continue
        if (r.code == 200) return parseOverpass(r.text, type)
        last = if (r.code == 429 || r.code == 504) "server busy (HTTP ${r.code})" else "HTTP ${r.code}"
    }
    error("OpenStreetMap download failed: $last. Try again in a minute.")
}

internal fun parseOverpass(json: String, type: OsmPoiType): List<Point> {
    val root = Json.parse(json) as? Map<*, *> ?: error("Unexpected answer from OpenStreetMap")
    val out = mutableListOf<Point>()
    for (e in root["elements"] as? List<*> ?: emptyList<Any>()) {
        val el = e as? Map<*, *> ?: continue
        val center = el["center"] as? Map<*, *>
        val lat = (el["lat"] ?: center?.get("lat")) as? Number ?: continue
        val lon = (el["lon"] ?: center?.get("lon")) as? Number ?: continue
        out += Point(osmName((el["tags"] as? Map<*, *>).orEmpty(), type), lat.toDouble(), lon.toDouble())
    }
    return out
}

/** "Galp, Leiria", "Speed camera 50 km/h", or the kind's name when nothing better is tagged. */
internal fun osmName(tags: Map<*, *>, type: OsmPoiType): String {
    fun t(k: String) = (tags[k] as? String)?.trim()?.takeIf { it.isNotEmpty() }
    if (type == OsmPoiType.SPEED_CAMERA) {
        val speed = t("maxspeed")?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }
        return listOfNotNull("Speed camera", speed?.let { "$it km/h" }).joinToString(" ")
    }
    if (type == OsmPoiType.TOILETS) {
        val fee = when (t("fee")?.lowercase()) { "no" -> "free"; "yes" -> "paid"; else -> null }
        val access = if (t("wheelchair") == "yes") "wheelchair" else null
        val extra = listOfNotNull(fee, access).joinToString(", ").takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
        val where = t("name") ?: t("addr:city") ?: t("addr:place")
        return "Toilets" + extra + (where?.let { ", $it" } ?: "")
    }
    val base = when (type) {
        OsmPoiType.FUEL, OsmPoiType.LPG, OsmPoiType.SUPERMARKET -> t("brand") ?: t("name")
        OsmPoiType.EV -> listOfNotNull(t("operator") ?: t("brand") ?: t("name"), t("capacity")?.let { "x$it" }).joinToString(" ").ifEmpty { null }
        else -> t("name") ?: t("brand")
    } ?: when (type) {
        OsmPoiType.BEACH -> "Beach"; OsmPoiType.DRINKING_WATER -> "Drinking water"
        OsmPoiType.PHARMACY -> "Pharmacy"; OsmPoiType.POOL -> "Swimming pool"
        else -> type.label.removeSuffix("s")
    }
    val town = t("addr:city") ?: t("addr:place")
    return if (town != null && !base.contains(town, ignoreCase = true)) "$base, $town" else base
}

/** Foursquare kinds. [prefixes] are Foursquare category paths; the first kind that matches wins. */
enum class FsqPoiType(override val label: String, val prefixes: List<String>) : PoiKind {
    FUEL("Fuel stations", listOf("Travel and Transportation > Fuel Station")),
    PHARMACY("Pharmacies", listOf("Retail > Pharmacy")),
    HOSPITAL("Hospitals and emergency", listOf("Health and Medicine > Hospital", "Health and Medicine > Emergency Service",
        "Health and Medicine > Urgent Care Center")),
    CAMPGROUND("Campgrounds", listOf("Travel and Transportation > Lodging > Campground", "Landmarks and Outdoors > Campground")),
    LODGING("Hotels and lodging", listOf("Travel and Transportation > Lodging")),
    RESTAURANT("Restaurants", listOf("Dining and Drinking > Restaurant", "Dining and Drinking > Steakhouse")),
    CAFE("Cafés and bakeries", listOf("Dining and Drinking > Cafe, Coffee, and Tea House", "Dining and Drinking > Bakery",
        "Dining and Drinking > Dessert Shop", "Dining and Drinking > Breakfast Spot", "Dining and Drinking > Snack Place")),
    BAR("Bars", listOf("Dining and Drinking > Bar", "Dining and Drinking > Pub", "Dining and Drinking > Brewery", "Dining and Drinking > Winery")),
    SUPERMARKET("Supermarkets", listOf("Retail > Food and Beverage Retail > Supermarket", "Retail > Food and Beverage Retail > Grocery Store",
        "Retail > Convenience Store")),
    VIEWPOINT("Viewpoints", listOf("Landmarks and Outdoors > Scenic Lookout")),
    BEACH("Beaches", listOf("Landmarks and Outdoors > Beach")),
    MUSEUM("Museums", listOf("Arts and Entertainment > Museum")),
    MONUMENT("Monuments and historic sites", listOf("Landmarks and Outdoors > Historic and Protected Site", "Landmarks and Outdoors > Monument",
        "Landmarks and Outdoors > Castle", "Landmarks and Outdoors > Palace")),
    PARKING("Parking", listOf("Travel and Transportation > Parking")),
    ATM("Banks and cash machines", listOf("Business and Professional Services > Financial Service > ATM",
        "Business and Professional Services > Financial Service > Banking and Finance > Bank")),
    CAR_REPAIR("Car repair", listOf("Business and Professional Services > Automotive Service > Automotive Repair Shop",
        "Business and Professional Services > Automotive Service > Tire Repair Shop"));

    override val category get() = "FSQ $label"
    override val source get() = PoiKind.Source.FOURSQUARE

    companion object {
        fun of(labels: List<*>): FsqPoiType? = entries.firstOrNull { t ->
            labels.any { l -> l is String && t.prefixes.any { p -> l == p || l.startsWith("$p >") } }
        }
    }
}

/** Which files and row groups hold each country (2-letter code). */
class FsqIndex(val release: String, val base: String, val countries: Map<String, Map<String, List<Int>>>) {
    companion object {
        fun parse(json: String): FsqIndex {
            val m = Json.parse(json) as Map<*, *>
            val c = (m["countries"] as Map<*, *>).entries.associate { (k, v) ->
                k as String to (v as Map<*, *>).entries.associate { (f, rgs) -> f as String to (rgs as List<*>).map { (it as Number).toInt() } }
            }
            return FsqIndex(m["release"] as String, m["base"] as String, c)
        }

        /** The index bundled with the app (made by MapsToTom's tools/fsq/fsqcountryindex.py). */
        fun bundled(): FsqIndex = parse(
            (FsqIndex::class.java.getResourceAsStream("/navilink/fsq-index.json") ?: error("fsq-index.json missing")).use { it.readBytes() }.decodeToString())
    }
}

/** Places confirmed in or after this year count as current. */
const val FSQ_DEFAULT_SINCE = 2020

/**
 * Downloads the places of [types] in [countries], leaving out closed places and those not confirmed since [since].
 * Reads only the row groups the index lists, a range at a time. [progress] gets (done, of) row groups.
 */
fun fetchFoursquare(
    index: FsqIndex, countries: List<String>, types: Set<FsqPoiType>, since: Int, net: Net,
    progress: (Int, Int) -> Unit = { _, _ -> },
): Map<FsqPoiType, List<Point>> {
    val cc = countries.map { it.uppercase() }.toSet()
    require(cc.any { it in index.countries }) { "Foursquare has no places for ${cc.joinToString()}" }
    val parts = HashMap<String, MutableSet<Int>>()
    for (c in cc) index.countries[c]?.forEach { (f, rgs) -> parts.getOrPut(f) { sortedSetOf() } += rgs }
    val total = parts.values.sumOf { it.size }
    var done = 0
    val out = types.associateWith { LinkedHashMap<String, Point>() }
    val sinceText = "%04d".format(since)
    for ((file, rgs) in parts.toSortedMap()) {
        val pf = ParquetFile(HttpRangeSource(net, index.base + file))
        for (rg in rgs) {
            progress(done++, total)
            val g = pf.rowGroups.getOrNull(rg) ?: continue
            val country = g.columns["country"] ?: continue
            val lo = country.min?.toString(Charsets.UTF_8); val hi = country.max?.toString(Charsets.UTF_8)
            if (lo != null && hi != null && cc.none { it in lo..hi }) continue
            val ctry = pf.read(rg, "country")
            val wanted = ctry.indices.filter { ctry[it] in cc }
            if (wanted.isEmpty()) continue
            val labels = pf.read(rg, "fsq_category_labels.list.element")
            val kinds = arrayOfNulls<FsqPoiType>(ctry.size)
            for (i in wanted) kinds[i] = FsqPoiType.of(labels[i] as? List<*> ?: continue)?.takeIf { it in types }
            if (kinds.all { it == null }) continue
            val closed = pf.read(rg, "date_closed"); val refreshed = pf.read(rg, "date_refreshed")
            val name = pf.read(rg, "name"); val lat = pf.read(rg, "latitude"); val lon = pf.read(rg, "longitude")
            for (i in wanted) {
                val k = kinds[i] ?: continue
                if (closed[i] != null) continue
                if (((refreshed[i] as? String) ?: "0000") < sinceText) continue
                val la = lat[i] as? Double ?: continue; val lo2 = lon[i] as? Double ?: continue
                val n = (name[i] as? String)?.trim().orEmpty()
                if (n.isEmpty()) continue
                out.getValue(k).putIfAbsent("${Math.rint(la * 1e5)},${Math.rint(lo2 * 1e5)},$n", Point(n, la, lo2))
            }
        }
    }
    progress(total, total)
    return out.mapValues { it.value.values.toList() }
}
