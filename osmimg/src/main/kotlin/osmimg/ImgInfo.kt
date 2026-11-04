package osmimg

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** A map set inside an .img: Garmin units tell maps apart by [familyId], so two maps with the same one clash. */
data class MapFamily(val familyId: Int, val productId: Int, val name: String)

/** What an .img file says about itself: its description and the map sets it holds. */
data class ImgInfo(val description: String, val families: List<MapFamily>)

/**
 * Reads the header, the directory and the MPS file (the map set list) of a Garmin .img.
 * Only those few kilobytes are read, so it is quick on a 2 GB map too. Null when the file is not a
 * Garmin img or has no MPS (a base map from Garmin, for example, uses other records).
 */
fun readImgInfo(file: File): ImgInfo? = runCatching { RandomAccessFile(file, "r").use { readImgInfo(it.channel) } }.getOrNull()

/** The same from an open channel (on Android: a file descriptor from the content resolver). */
fun readImgInfo(ch: FileChannel): ImgInfo? = runCatching {
    fun read(pos: Long, n: Int): ByteArray {
        val b = ByteBuffer.allocate(n)
        while (b.hasRemaining()) if (ch.read(b, pos + b.position()) < 0) break
        check(!b.hasRemaining()) { "short file" }
        return b.array()
    }
    val length = ch.size()
    val head = read(0, 0x200)
    if (String(head, 0x10, 6, Charsets.ISO_8859_1) != "DSKIMG") return null
    val blockSize = 1 shl ((head[0x61].toInt() and 0xFF) + (head[0x62].toInt() and 0xFF))
    val desc = (String(head, 0x49, 20, Charsets.ISO_8859_1) + String(head, 0x65, 30, Charsets.ISO_8859_1))
        .replace('\u0000', ' ').replace(Regex("\\s+"), " ").trim()

    // Directory: 512-byte entries from 0x400 (or 0x600 after a partition block).
    // The first entry ("." with an empty name) spans the header and the directory, so its size is where it ends.
    val start = if (read(0x400, 1)[0].toInt() == 1) 0x400L else 0x600L
    var e = read(start, 512)
    val dirEnd = minOf(le32(e, 12).toLong().takeIf { it > start } ?: (start + 512 * 512), length)
    var mps: Pair<Int, Int>? = null // size, first block
    var off = start
    while (off + 512 <= dirEnd && mps == null) {
        e = read(off, 512)
        if (e[0].toInt() == 1 && le16(e, 0x11) == 0 && String(e, 9, 3, Charsets.ISO_8859_1) == "MPS") {
            mps = le32(e, 12) to le16(e, 0x20)
        }
        off += 512
    }
    val (size, block) = mps ?: return ImgInfo(desc, emptyList())
    ImgInfo(desc, parseMps(read(block.toLong() * blockSize, minOf(size, 1 shl 20))))
}.getOrNull()

/** MPS records: type byte, 2-byte length, data. 'F' = a map set (product, family, name); 'L' = one map tile of it. */
internal fun parseMps(d: ByteArray): List<MapFamily> {
    val out = linkedMapOf<Int, MapFamily>()
    var i = 0
    while (i + 3 <= d.size) {
        val type = d[i].toInt() and 0xFF
        val len = le16(d, i + 1)
        val r = i + 3
        if (r + len > d.size || type == 0) break
        if ((type == 'F'.code || type == 'L'.code) && len >= 4) {
            val product = le16(d, r)
            val family = le16(d, r + 2)
            val name = if (type == 'F'.code) cString(d, r + 4, r + len) else ""
            val old = out[family]
            if (old == null || (old.name.isEmpty() && name.isNotEmpty())) out[family] = MapFamily(family, product, name)
        }
        i = r + len
    }
    return out.values.toList()
}

private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)
private fun cString(b: ByteArray, from: Int, to: Int): String {
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
