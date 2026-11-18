package app.navilink.pois

import osmimg.DriveFs
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/*
 * POI sets already on the Garmin: .gpi files (POI Loader, iOverlander, camper packs) and map add-ons in .img files
 * (City Navigator's Foursquare layer). File names change from release to release, so sets are recognised by
 * what is inside: a .gpi's header texts, an .img's description.
 */

/** Known POI publishers, and the texts that give them away (matched in any case). */
enum class PoiPublisher(val title: String, val markers: List<String>) {
    IOVERLANDER("iOverlander", listOf("ioverlander", "006-D8166")),
    FOURSQUARE("Foursquare", listOf("foursquare")),
}

/** A POI set found on the device. [readable]: its places can be read, so ours can be checked against them one by one. */
data class FoundPoiSet(
    val path: String, val drive: String, val title: String, val publisher: PoiPublisher?, val bytes: Long, val isImg: Boolean,
) {
    val readable get() = !isImg
}

private const val HEAD = 64 * 1024

/** Up to [n] bytes from the start of [input] (InputStream.readNBytes needs Android 13). */
internal fun readHead(input: InputStream, n: Int): ByteArray {
    val b = ByteArray(n); var got = 0
    while (got < n) { val r = input.read(b, got, n - got); if (r < 0) break; got += r }
    return if (got == n) b else b.copyOf(got)
}

/** The texts in a .gpi's header (file title, name, copyright, part number), or null when it isn't a GPI. */
fun gpiHeaderTexts(head: ByteArray): List<String>? {
    val at = (8..12).firstOrNull { head.size >= it + 6 && String(head, it, 6, Charsets.ISO_8859_1) == "GRMREC" } ?: return null
    val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
    val out = mutableListOf<String>()
    fun text(o: Int, n: Int) = String(head, o, n, Charsets.UTF_8).trim()
        .takeIf { s -> s.length >= 3 && s.count { it.isLetterOrDigit() } >= 2 && s.none { it < ' ' || it == '\uFFFD' } }
    // Record 0: "GRMRECnn", time, then a u16-counted file title.
    val tl = at + 8 + 6
    if (tl + 2 <= head.size) { val n = bb.getShort(tl).toInt() and 0xffff; if (n in 1..200 && tl + 2 + n <= head.size) text(tl + 2, n)?.let(out::add) }
    // The header texts are language strings: "EN", u16 length, text. They come before the first group (type 9).
    var end = head.size
    run {
        var o = 0
        while (o + 8 <= head.size) {
            val type = bb.getShort(o).toInt() and 0xffff; val flags = bb.getShort(o + 2).toInt() and 0xffff; val size = bb.getInt(o + 4)
            if (type == 9 || type == 2 || size < 0) { end = o; break }
            o += (if (flags and 8 != 0) 12 else 8) + size
        }
    }
    var p = 0
    while (p + 4 < minOf(end, head.size)) {
        if (head[p] in 'A'.code.toByte()..'Z'.code.toByte() && head[p + 1] in 'A'.code.toByte()..'Z'.code.toByte()) {
            val n = bb.getShort(p + 2).toInt() and 0xffff
            if (n in 3..300 && p + 4 + n <= head.size) text(p + 4, n)?.let { out += it; p += 4 + n; continue }
        }
        p++
    }
    // Part numbers like 006-D8166-36 are stored as plain counted strings.
    Regex("""\d{3}-[A-Z]\d{4}-\d{2}""").findAll(String(head, 0, minOf(end, head.size), Charsets.ISO_8859_1)).forEach { out += it.value }
    return out.distinct()
}

/** An .img's description ("CN Europe NTU 2027.30 Foursquare"), or null when it isn't a Garmin map. Handles the XOR byte. */
fun imgDescription(head: ByteArray): String? {
    if (head.size < 0x84) return null
    val x = head[0].toInt() and 0xff
    val h = ByteArray(0x84) { (head[it].toInt() xor x).toByte() }
    if (String(h, 0x10, 6, Charsets.ISO_8859_1) != "DSKIMG") return null
    return (String(h, 0x49, 20, Charsets.ISO_8859_1) + String(h, 0x65, 31, Charsets.ISO_8859_1)).trim()
        .trimEnd { !it.isLetterOrDigit() && it !in ".)]" }
}

fun publisherOf(texts: List<String>): PoiPublisher? = PoiPublisher.entries.firstOrNull { p ->
    texts.any { t -> p.markers.any { t.contains(it, ignoreCase = true) } }
}

/** Folders that hold POIs or map add-ons on Garmins and their memory cards. */
private val POI_DIRS = listOf("Garmin/POI", "POI", "Garmin", "Map", "")

/** The POI sets on [fs]: every .gpi, and every .img whose description names a known POI publisher. */
fun findPoiSets(fs: DriveFs, drive: String): List<FoundPoiSet> {
    val out = mutableListOf<FoundPoiSet>()
    val seen = mutableSetOf<String>()
    fun scan(dir: String) {
        val entries = fs.list(dir) ?: return
        for (e in entries) {
            if (e.isDir) { if (dir.equals("Map", true)) scan("$dir/${e.name}"); continue }
            val path = if (dir.isEmpty()) e.name else "$dir/${e.name}"
            if (!seen.add(path.lowercase())) continue
            val gpi = e.name.endsWith(".gpi", true); val img = e.name.endsWith(".img", true)
            if (!gpi && !img) continue
            val head = runCatching { fs.openRead(path).use { readHead(it, HEAD) } }.getOrNull() ?: continue
            if (gpi) {
                val texts = gpiHeaderTexts(head) ?: continue
                val title = texts.firstOrNull { it.contains(' ') } ?: texts.firstOrNull() ?: e.name.substringBeforeLast('.')
                out += FoundPoiSet(path, drive, title, publisherOf(texts + e.name), e.bytes, isImg = false)
            } else {
                val d = imgDescription(head) ?: continue
                val pub = publisherOf(listOf(d)) ?: continue
                out += FoundPoiSet(path, drive, d, pub, e.bytes, isImg = true)
            }
        }
    }
    POI_DIRS.forEach { d ->
        val real = if (d.isEmpty()) "" else d.split('/').fold("") { acc, part ->
            val r = fs.list(acc)?.firstOrNull { it.isDir && it.name.equals(part, true) }?.name ?: return@forEach
            if (acc.isEmpty()) r else "$acc/$r"
        }
        scan(real)
    }
    return out
}

/**
 * Every place's position in a .gpi with its category's name, read as a stream so a 200 MB file needs no more
 * memory than its positions. Same record layout as [app.navilink.core.readGpi]: `u16 type, u16 flags, u32 size`
 * (+ `u32 mainSize` with flag 8). Places come before the category records (type 7) that name them, so names are
 * matched up at the end. An encrypted file gives an empty map.
 */
fun gpiPlacesByCategory(input: InputStream): Map<String, List<Pair<Double, Double>>> {
    val d = DataInputStream(input.buffered(1 shl 16))
    fun u16() = d.readUnsignedShort().let { ((it and 0xff) shl 8) or (it shr 8) }
    fun s32() = Integer.reverseBytes(d.readInt())
    fun skip(n: Long) { var left = n; while (left > 0) { val s = d.skip(left); if (s <= 0) { d.readByte(); left-- } else left -= s } }
    fun deg(semi: Int) = semi * (180.0 / (1L shl 31))
    /** A language string list (u32 total, then "EN", u16 length, text...): English or the first. */
    fun lstring(): Pair<String, Long> {
        val total = s32().toLong() and 0xffffffffL
        var left = total; var first: String? = null; var en: String? = null
        while (left >= 4) {
            val lang = String(byteArrayOf(d.readByte(), d.readByte()), Charsets.ISO_8859_1); val n = u16()
            if (n > left - 4) { skip(left - 4); left = 0; break }
            val b = ByteArray(n); d.readFully(b); left -= 4 + n
            val t = String(b, Charsets.UTF_8).trim()
            if (first == null) first = t
            if (lang == "EN" && en == null) en = t
        }
        skip(left)
        return (en ?: first ?: "") to 4 + total
    }
    var group = -1
    val groupNames = mutableListOf<String>()
    val catNames = HashMap<Long, String>()
    val points = LinkedHashMap<Long, MutableList<Pair<Double, Double>>>()
    fun key(cat: Int) = (group.toLong() shl 32) or (cat.toLong() and 0xffffffffL)

    /** Walks records in the next [budget] bytes; [onCategory] gets a place's category id (record 6). */
    fun walk(budget: Long, onCategory: ((Int) -> Unit)? = null) {
        var left = budget
        while (left >= 8) {
            val type = u16(); val flags = u16(); val size = s32().toLong() and 0xffffffffL
            val main = if (flags and 8 != 0) (s32().toLong() and 0xffffffffL).coerceAtMost(size) else size
            left -= (if (flags and 8 != 0) 12 else 8) + size
            if (type == 0xffff) return
            when (type) {
                9 -> { // group: name, areas inside its own data, then children
                    group++
                    val (name, n) = lstring()
                    groupNames += name.ifEmpty { "POIs" }
                    walk(main - n); walk(size - main)
                }
                8 -> { skip(main); walk(size - main) }
                2 -> {
                    if (main < 8) { skip(size); continue }
                    val lat = deg(s32()); val lon = deg(s32())
                    skip(main - 8)
                    var cat = -1
                    walk(size - main) { cat = it }
                    if (lat in -90.0..90.0 && lon in -180.0..180.0) points.getOrPut(key(cat)) { mutableListOf() } += lat to lon
                }
                6 -> if (main >= 2) { onCategory?.invoke(u16()); skip(size - 2) } else skip(size)
                7 -> if (main >= 6) {
                    val id = u16(); val (name, n) = lstring()
                    catNames[key(id)] = name
                    skip(size - 2 - n)
                } else skip(size)
                else -> skip(size)
            }
        }
    }
    try { walk(Long.MAX_VALUE) } catch (_: EOFException) {}
    val out = LinkedHashMap<String, MutableList<Pair<Double, Double>>>()
    for ((k, pts) in points) {
        val g = (k shr 32).toInt()
        val name = catNames[k]?.ifEmpty { null } ?: groupNames.getOrNull(g) ?: "POIs"
        out.getOrPut(name) { mutableListOf() } += pts
    }
    return out
}

/**
 * Words that mean the same kind of place in a POI category's name (English and the languages iOverlander uses),
 * so a new fuel station is only checked against fuel stations already on the Garmin.
 */
fun sameKindWords(kind: PoiKind): Regex = Regex(when (kind) {
    OsmPoiType.FUEL, FsqPoiType.FUEL -> "fuel|petrol|gas station|tankst|gasolin|combust|station-service|distributore"
    OsmPoiType.LPG -> "propan|propaan|lpg|gpl|autogas"
    OsmPoiType.EV -> "charg|\\bev\\b|elektro|recarga|carregamento|borne"
    OsmPoiType.MOTORHOME, OsmPoiType.CAMPSITE, FsqPoiType.CAMPGROUND ->
        "camp|motorhome|caravan|stellplatz|aire|\\brv\\b|bivouac|kampeer|sosta|wild"
    OsmPoiType.SUPERMARKET, FsqPoiType.SUPERMARKET -> "supermar|grocer|shopping|compras|einkauf|commerces|negozi|winkels"
    OsmPoiType.PHARMACY, FsqPoiType.PHARMACY -> "pharm|farm[aá]c|apothe"
    FsqPoiType.HOSPITAL -> "hospital|medical|médic|medic|saúde|medizin|medisch|emergenc|urgenc"
    OsmPoiType.TOILETS -> "toilet|\\bwc\\b|restroom|sanitari"
    OsmPoiType.DRINKING_WATER -> "water|água|agua|\\beau\\b|wasser|acqua"
    OsmPoiType.VIEWPOINT, FsqPoiType.VIEWPOINT -> "viewpoint|scenic|lookout|miradou|mirador|belvedere|aussicht"
    OsmPoiType.PICNIC -> "picnic|piquenique|merendero"
    OsmPoiType.BEACH, FsqPoiType.BEACH -> "beach|praia|playa|plage|strand|spiaggia"
    OsmPoiType.POOL -> "pool|piscin|swim|schwimm|zwem"
    OsmPoiType.SPEED_CAMERA -> "camera|radar|blitz|speed"
    FsqPoiType.LODGING -> "hotel|hostel|lodg|albergue|auberge|ostello|pousada"
    FsqPoiType.RESTAURANT -> "restaur|ristorante"
    FsqPoiType.CAFE -> "caf[eé]|coffee|bakery|padaria|panader|bäcker"
    FsqPoiType.BAR -> "\\bbars?\\b|\\bpubs?\\b|brew|winer"
    FsqPoiType.PARKING -> "parking|parkplatz|estacionamento|aparcamiento|parcheggio|parkeren"
    FsqPoiType.CAR_REPAIR -> "mechanic|garage|werkstatt|mecânic|mecánic|meccanic|repair"
    FsqPoiType.ATM -> "\\batms?\\b|bank|multibanco|cash|financ"
    FsqPoiType.MUSEUM, FsqPoiType.MONUMENT -> "museu|museo|musée|attraction|sehensw|monument|histor|castle|castelo|castillo"
}, RegexOption.IGNORE_CASE)

/** How close two places must be to count as the same one (as in MapsToTom). */
const val SAME_PLACE_METERS = 150.0

/** Positions on a grid of about [SAME_PLACE_METERS] cells, to ask "is there one nearby?" quickly. */
class NearIndex {
    private val cell = SAME_PLACE_METERS / 111_320.0
    private val grid = HashMap<Long, MutableList<Long>>()
    var size = 0; private set

    private fun k(i: Int, j: Int) = (i.toLong() shl 32) or (j.toLong() and 0xffffffffL)
    private fun pack(lat: Double, lon: Double) = (Math.round(lat * 1e6) shl 32) or (Math.round(lon * 1e6) and 0xffffffffL)

    fun add(lat: Double, lon: Double) {
        grid.getOrPut(k(floor(lat / cell).toInt(), floor(lon / cell).toInt())) { mutableListOf() } += pack(lat, lon)
        size++
    }

    fun near(lat: Double, lon: Double): Boolean {
        val i = floor(lat / cell).toInt(); val j = floor(lon / cell).toInt()
        val cl = cos(Math.toRadians(lat))
        // A cell is narrower east-west away from the equator, so look further sideways there.
        val dj = (1 / cl.coerceAtLeast(0.05)).toInt() + 1
        for (a in i - 1..i + 1) for (b in j - dj..j + dj) {
            for (v in grid[k(a, b)] ?: continue) {
                val la = (v shr 32) / 1e6; val lo = v.toInt() / 1e6
                if (hypot((la - lat) * 111_320.0, (lo - lon) * 111_320.0 * cl) <= SAME_PLACE_METERS) return true
            }
        }
        return false
    }
}
