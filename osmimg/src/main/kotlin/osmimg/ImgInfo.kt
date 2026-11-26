package osmimg

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** A map set inside an .img: Garmin units tell maps apart by [familyId], so two maps with the same one clash. */
data class MapFamily(val familyId: Int, val productId: Int, val name: String)

/** What an .img file says about itself: its description and the map sets it holds. */
data class ImgInfo(val description: String, val families: List<MapFamily>)

/** An area in degrees (WGS84). */
data class Bounds(val north: Double, val east: Double, val south: Double, val west: Double) {
    fun union(o: Bounds) = Bounds(maxOf(north, o.north), maxOf(east, o.east), minOf(south, o.south), minOf(west, o.west))
}

/**
 * One map tile (Garmin calls them segments): the TRE/RGN/LBL... subfiles sharing a name such as
 * "36240001", or one GMP subfile in NT maps. [name] is what tells two copies of a tile apart.
 */
data class MapTile(val name: String, val bounds: Bounds?, val locked: Boolean, val routable: Boolean, val elevation: Boolean)

/**
 * Everything the device page shows about an .img. [unlockCodes] counts the unlock records in the
 * file; the codes themselves are never kept. [damaged] says why the file can't be right, or is null.
 */
data class ImgDetails(
    val description: String,
    val families: List<MapFamily>,
    val tiles: List<MapTile>,
    val blockSize: Int,
    val hasSearch: Boolean,
    val hasTyp: Boolean,
    val unlockCodes: Int,
    val damaged: String?,
) {
    val info: ImgInfo get() = ImgInfo(description, families)
    val locked: Boolean get() = tiles.any { it.locked }
    val routable: Boolean get() = tiles.any { it.routable }
    val elevation: Boolean get() = tiles.any { it.elevation }
    val bounds: Bounds? get() = tiles.mapNotNull { it.bounds }.reduceOrNull(Bounds::union)
}

/**
 * Reads the header, the directory and the MPS file (the map set list) of a Garmin .img.
 * Only those few kilobytes are read, so it is quick on a 2 GB map too. Null when the file is not a
 * Garmin img. A map without an MPS (a Garmin base map, for example) has no families.
 */
fun readImgInfo(file: File): ImgInfo? = runCatching { RandomAccessFile(file, "r").use { readImgInfo(it.channel) } }.getOrNull()

/** The same from an open channel (on Android: a file descriptor from the content resolver). */
fun readImgInfo(ch: FileChannel): ImgInfo? = runCatching {
    val img = ImgReader.open(ch) ?: return null
    ImgInfo(img.description, img.subfile("MPS")?.let { parseMps(img.read(it, 0, minOf(it.size, 1L shl 20).toInt())) }.orEmpty())
}.getOrNull()

fun readImgDetails(file: File): ImgDetails? = runCatching { RandomAccessFile(file, "r").use { readImgDetails(it.channel) } }.getOrNull()

/**
 * The map sets, every tile with its area and flags, and a damage check. Reads a few hundred bytes
 * per tile, so a 400-tile City Navigator takes well under a second from a USB drive.
 */
fun readImgDetails(ch: FileChannel): ImgDetails? {
    val img = runCatching { ImgReader.open(ch) }.getOrNull() ?: return null
    var damaged = img.damaged
    val mps = img.subfile("MPS")?.let { runCatching { img.read(it, 0, minOf(it.size, 1L shl 20).toInt()) }.getOrNull() }
    val families = mps?.let(::parseMps).orEmpty()
    val unlocks = mps?.let { mpsRecords(it).count { r -> r.type == 'U'.code } } ?: 0
    val byName = img.subfiles.groupBy { it.name }
    val tiles = mutableListOf<MapTile>()
    var unreadable = 0
    for ((name, subs) in byName) {
        val types = subs.associateBy { it.type }
        val tile = runCatching {
            when {
                "GMP" in types -> {
                    val gmp = types.getValue("GMP")
                    val g = img.read(gmp, 0, 0x35)
                    check(String(g, 2, 10, Charsets.ISO_8859_1) == "GARMIN GMP") { "tile $name has a damaged header" }
                    val treOff = le32(g, 0x19).toLong()
                    val tre = if (treOff > 0) img.read(gmp, treOff, 0x21) else null
                    MapTile(name, tre?.let(::treBounds), (g[0x0D].toInt() and 0x80) != 0 || tre?.let { (it[0x0D].toInt() and 0x80) != 0 } == true,
                        routable = le32(g, 0x29) != 0, elevation = le32(g, 0x2D) != 0)
                }
                "TRE" in types -> {
                    val t = img.read(types.getValue("TRE"), 0, 0x21)
                    check(String(t, 2, 10, Charsets.ISO_8859_1) == "GARMIN TRE") { "tile $name has a damaged header" }
                    MapTile(name, treBounds(t), (t[0x0D].toInt() and 0x80) != 0, "NOD" in types, "DEM" in types)
                }
                else -> null
            }
        }.getOrElse { unreadable++; null }
        if (tile != null) tiles += tile
    }
    if (unreadable > 0 && damaged == null) {
        val total = unreadable + tiles.size
        damaged = if (unreadable == total) "none of its $total map tiles can be read (an unfinished download or copy?)"
            else "$unreadable of its $total map tiles can't be read (an unfinished download or copy?)"
    }
    if (mps != null && families.isEmpty() && damaged == null && mps.all { it.toInt() == 0 }) damaged = "its list of map sets is empty (an unfinished download or copy?)"
    return ImgDetails(img.description, families, tiles, img.blockSize,
        hasSearch = img.subfiles.any { it.type == "MDR" }, hasTyp = img.subfiles.any { it.type == "TYP" },
        unlockCodes = unlocks, damaged = damaged)
}

/** TRE header: north, east, south, west as signed 24-bit map units from 0x15. */
private fun treBounds(t: ByteArray): Bounds? {
    fun s24(o: Int): Double {
        var v = (t[o].toInt() and 0xFF) or ((t[o + 1].toInt() and 0xFF) shl 8) or ((t[o + 2].toInt() and 0xFF) shl 16)
        if (v and 0x800000 != 0) v -= 1 shl 24
        return v * 360.0 / (1 shl 24)
    }
    val b = Bounds(s24(0x15), s24(0x18), s24(0x1B), s24(0x1E))
    return b.takeIf { it.north >= it.south && it.north <= 90 && it.south >= -90 }
}

/** A file inside the .img: [blocks] in order, [entries] = where its directory entries are, part 0 first. */
class ImgSubfile(val name: String, val type: String, val size: Long, val blocks: List<Int>, val entries: List<Long>)

/**
 * The container of a Garmin .img: a header, a directory of 512-byte entries starting at block
 * (header 0x40) x 512, and subfiles stored in blocks of [blockSize]. Some files are XOR-scrambled
 * with the header's first byte; [read] undoes that.
 */
internal class ImgReader private constructor(val ch: FileChannel, val xor: Int) {
    val length: Long = ch.size()
    lateinit var head: ByteArray; private set
    var blockSize = 512; private set
    var description = ""; private set
    var subfiles: List<ImgSubfile> = emptyList(); private set
    var damaged: String? = null; private set

    fun raw(pos: Long, n: Int): ByteArray {
        val b = ByteBuffer.allocate(n)
        while (b.hasRemaining()) if (ch.read(b, pos + b.position()) < 0) break
        check(!b.hasRemaining()) { "the file ends early" }
        val a = b.array()
        if (xor != 0) for (i in a.indices) a[i] = (a[i].toInt() xor xor).toByte()
        return a
    }

    fun subfile(type: String): ImgSubfile? = subfiles.firstOrNull { it.type == type }

    /** File position of byte [off] of [sub]. */
    fun position(sub: ImgSubfile, off: Long): Long {
        val i = (off / blockSize).toInt()
        check(i < sub.blocks.size) { "${sub.name}.${sub.type} is shorter than its directory says" }
        return sub.blocks[i].toLong() * blockSize + off % blockSize
    }

    /** [n] bytes of [sub] from [off], across blocks. */
    fun read(sub: ImgSubfile, off: Long, n: Int): ByteArray {
        val out = ByteArray(n)
        var done = 0
        while (done < n) {
            val o = off + done
            val chunk = minOf(n - done, (blockSize - o % blockSize).toInt())
            raw(position(sub, o), chunk).copyInto(out, done)
            done += chunk
        }
        return out
    }

    private fun load() {
        head = raw(0, 0x200)
        check(String(head, 0x10, 6, Charsets.ISO_8859_1) == "DSKIMG") { "not a Garmin img" }
        blockSize = 1 shl ((head[0x61].toInt() and 0xFF) + (head[0x62].toInt() and 0xFF))
        description = (String(head, 0x49, 20, Charsets.ISO_8859_1) + String(head, 0x65, 30, Charsets.ISO_8859_1))
            .replace('\u0000', ' ').replace(Regex("\\s+"), " ").trim()
        // The first entry spans the header and the directory: its size is where the directory ends.
        val start = ((head[0x40].toInt() and 0xFF) * 512L).takeIf { it >= 0x200 } ?: 0x400L
        val first = raw(start, 512)
        val dirEnd = minOf(le32(first, 12).toLong().takeIf { it > start } ?: (start + 512L * 512), length)
        class Part(val part: Int, val size: Long, val blocks: List<Int>, val at: Long)
        val parts = linkedMapOf<String, MutableList<Part>>()
        var off = start
        while (off + 512 <= dirEnd) {
            val e = raw(off, 512)
            val name = String(e, 1, 8, Charsets.ISO_8859_1)
            val type = String(e, 9, 3, Charsets.ISO_8859_1)
            if (e[0].toInt() == 1 && type.isNotBlank()) {
                val blocks = (0 until 240).map { le16(e, 0x20 + 2 * it) }.takeWhile { it != 0xFFFF }
                // Part number from 0x11 (Garmin's own maps put 1, 2... there); some makers pad from 0x12 with 0xFF.
                val hi = e[0x12].toInt() and 0xFF
                val part = (e[0x11].toInt() and 0xFF) or (if (hi == 0xFF) 0 else hi shl 8)
                parts.getOrPut("$name.$type") { mutableListOf() } += Part(part, le32(e, 12).toLong() and 0xFFFFFFFFL, blocks, off)
            }
            off += 512
        }
        subfiles = parts.map { (key, ps) ->
            val sorted = ps.sortedBy { it.part }
            ImgSubfile(key.substringBefore('.').trim(), key.substringAfter('.'), sorted.first().size, sorted.flatMap { it.blocks }, sorted.map { it.at })
        }
        // A copy that stopped early: blocks past the end of the file, or fewer blocks than the size needs.
        damaged = subfiles.firstNotNullOfOrNull { s ->
            when {
                s.blocks.size.toLong() * blockSize < s.size -> "${s.name}.${s.type} is missing parts"
                s.blocks.any { it.toLong() * blockSize >= length } -> "the file is cut short (an unfinished copy?)"
                else -> null
            }
        }
    }

    companion object {
        /** Null when the file isn't a Garmin img. */
        fun open(ch: FileChannel): ImgReader? {
            if (ch.size() < 0x600) return null
            val b = ByteBuffer.allocate(1)
            ch.read(b, 0)
            return runCatching { ImgReader(ch, b.array()[0].toInt() and 0xFF).apply { load() } }.getOrNull()
        }
    }
}

internal class MpsRecord(val type: Int, val start: Int, val data: ByteArray)

/** MPS records: type byte, 2-byte length, data. */
internal fun mpsRecords(d: ByteArray): List<MpsRecord> {
    val out = mutableListOf<MpsRecord>()
    var i = 0
    while (i + 3 <= d.size) {
        val type = d[i].toInt() and 0xFF
        val len = le16(d, i + 1)
        val r = i + 3
        if (r + len > d.size || type == 0) break
        out += MpsRecord(type, i, d.copyOfRange(r, r + len))
        i = r + len
    }
    return out
}

/** 'F' = a map set (product, family, name); 'L' = one map tile of it. */
internal fun parseMps(d: ByteArray): List<MapFamily> {
    val out = linkedMapOf<Int, MapFamily>()
    for (r in mpsRecords(d)) {
        if ((r.type == 'F'.code || r.type == 'L'.code) && r.data.size >= 4) {
            val product = le16(r.data, 0)
            val family = le16(r.data, 2)
            val name = if (r.type == 'F'.code) cString(r.data, 4, r.data.size) else ""
            val old = out[family]
            if (old == null || (old.name.isEmpty() && name.isNotEmpty())) out[family] = MapFamily(family, product, name)
        }
    }
    return out.values.toList()
}

internal fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
internal fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)
internal fun cString(b: ByteArray, from: Int, to: Int): String {
    var end = from
    while (end < to && b[end].toInt() != 0) end++
    return String(b, from, end - from, Charsets.ISO_8859_1).trim()
}

/**
 * A family ID for a new map of [parts] files that clashes with none in [used]: [preferred] if its
 * whole range is free, otherwise the next free range from 6324 up. IDs below 1000 are left to Garmin.
 */
fun freeFamilyId(used: Set<Int>, parts: Int = 1, preferred: Int = 6324): Int {
    fun free(start: Int) = (start until start + parts).none { it in used } && start + parts - 1 <= 0xFFFF
    if (free(preferred)) return preferred
    var id = 6324
    while (!free(id)) id++
    return id
}
