package osmimg


/** A Geofabrik extract: [bytes] is the .osm.pbf download size (0 if unknown), [bbox] is minLon, minLat, maxLon, maxLat. */
data class Region(
    val id: String,
    val name: String,
    val parent: String,
    val pbfUrl: String,
    val bbox: List<Double>?,
    val bytes: Long,
)

/** Reads the slim region list (an array of {id, name, parent, pbf, bbox, bytes}) shipped with the app. */
fun parseRegions(text: String): List<Region> {
    @Suppress("UNCHECKED_CAST")
    val arr = Json.parse(text) as List<Map<String, Any?>>
    return arr.map { o ->
        Region(
            id = o["id"] as String,
            name = o["name"] as? String ?: o["id"] as String,
            parent = o["parent"] as? String ?: "",
            pbfUrl = o["pbf"] as String,
            bbox = (o["bbox"] as? List<*>)?.map { (it as Number).toDouble() },
            bytes = (o["bytes"] as? Number)?.toLong() ?: 0L,
        )
    }
}

/** The region list bundled with the app (Geofabrik, 555 extracts). */
fun bundledRegions(): List<Region> {
    val s = Region::class.java.getResourceAsStream("/osmimg/geofabrik-regions.json")
        ?: error("bundled region list missing")
    return parseRegions(s.use { it.readBytes().toString(Charsets.UTF_8) })
}

/** The regions directly inside [parentId] ("" = continents), by name. */
fun List<Region>.childrenOf(parentId: String) = filter { it.parent == parentId }.sortedBy { it.name }

/**
 * Rough size of the Garmin map built from a region, from its .pbf size. A guess until the
 * first real build; [MapBuilder] reports the real size, and the install plan is redone with it.
 */
fun estimateImgBytes(region: Region, ratio: Double = IMG_PER_PBF): Long = (region.bytes * ratio).toLong()

/**
 * img bytes per pbf byte. Measured 2026-11-03 on Portugal with the default style, routing and index:
 * 427 MB download -> 255 MB gmapsupp.img (0.60; 0.58 against the splitter tiles). 0.65 leaves room.
 */
const val IMG_PER_PBF = 0.65
