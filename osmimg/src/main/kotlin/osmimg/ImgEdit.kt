package osmimg

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.channels.FileChannel

/** Bytes to write at [offset] of a file, already in the file's own form (scrambled if the file is). */
class Patch(val offset: Long, val bytes: ByteArray)

/** The longest map name the header holds (20 + 30 characters). */
const val MAX_DESCRIPTION = 50

/** Characters an .img name can hold: Latin-1, no control characters. */
fun imgTextProblem(text: String): String? = when {
    text.isBlank() -> "The name can't be empty."
    text.any { it.code > 0xFF || it.code < 0x20 } -> "Use letters, digits and Western European accents only."
    else -> null
}

private fun latin1(text: String) = text.toByteArray(Charsets.ISO_8859_1)

private fun ImgReader.scramble(b: ByteArray): ByteArray =
    if (xor == 0) b else ByteArray(b.size) { (b[it].toInt() xor xor).toByte() }

/**
 * Changes the map name in the .img header (what the device and BaseCamp show as the map's
 * description). Two fields of 20 and 30 characters, padded with spaces.
 */
fun descriptionPatches(ch: FileChannel, text: String): List<Patch> {
    imgTextProblem(text)?.let { error(it) }
    require(text.length <= MAX_DESCRIPTION) { "At most $MAX_DESCRIPTION characters." }
    val img = ImgReader.open(ch) ?: error("not a Garmin map")
    val b = latin1(text.padEnd(MAX_DESCRIPTION))
    return listOf(Patch(0x49, img.scramble(b.copyOfRange(0, 20))), Patch(0x65, img.scramble(b.copyOfRange(20, 50))))
}

/**
 * Renames map set [familyId] in the MPS (the name in the device's map list and in BaseCamp).
 * Tiles whose series name was the old name get the new one too. The MPS must still fit in the
 * blocks it has, which leaves room for any normal name; otherwise this fails and nothing changes.
 */
fun mapSetNamePatches(ch: FileChannel, familyId: Int, newName: String): List<Patch> {
    imgTextProblem(newName)?.let { error(it) }
    val img = ImgReader.open(ch) ?: error("not a Garmin map")
    val sub = img.subfile("MPS") ?: error("This map has no map set list to rename.")
    val old = img.read(sub, 0, sub.size.toInt())
    val recs = mpsRecords(old)
    val oldName = recs.firstOrNull { it.type == 'F'.code && it.data.size >= 4 && le16(it.data, 2) == familyId }
        ?.let { cString(it.data, 4, it.data.size) } ?: error("No map set with ID $familyId in this map.")
    val out = java.io.ByteArrayOutputStream()
    var end = 0
    for (r in recs) {
        var data = r.data
        val fam = if (data.size >= 4) le16(data, 2) else -1
        // Replace the zero-terminated name at [at]; anything after its terminator stays as it was.
        fun renamed(at: Int): ByteArray {
            var z = at
            while (z < data.size && data[z].toInt() != 0) z++
            return data.copyOfRange(0, at) + latin1(newName) + (if (z < data.size) data.copyOfRange(z, data.size) else byteArrayOf(0))
        }
        if (fam == familyId && r.type == 'F'.code) data = renamed(4)
        else if (fam == familyId && r.type == 'L'.code && data.size > 8 && cString(data, 8, data.size) == oldName) data = renamed(8)
        check(data.size <= 0xFFFF) { "name too long" }
        out.write(r.type); out.write(data.size and 0xFF); out.write(data.size shr 8); out.write(data)
        end = r.start + 3 + r.data.size
    }
    out.write(old, end, old.size - end) // whatever followed the records (usually nothing)
    val mps = out.toByteArray()
    val room = sub.blocks.size.toLong() * img.blockSize
    check(mps.size <= room) { "The new name doesn't fit in this map's file; choose a shorter one." }
    // The new list, zero-filled to the old length when shorter, written block by block.
    val data = if (mps.size < old.size) mps.copyOf(old.size) else mps
    val patches = mutableListOf<Patch>()
    var o = 0
    while (o < data.size) {
        val n = minOf(data.size - o, img.blockSize - o % img.blockSize)
        patches += Patch(img.position(sub, o.toLong()), img.scramble(data.copyOfRange(o, o + n)))
        o += n
    }
    if (mps.size.toLong() != sub.size) {
        val size = mps.size
        patches += Patch(sub.entries.first() + 12, img.scramble(byteArrayOf(size.toByte(), (size shr 8).toByte(), (size shr 16).toByte(), (size shr 24).toByte())))
    }
    return patches
}

/** What [patches] overwrite, read from [ch] first, so they can be undone. */
fun undoFor(ch: FileChannel, patches: List<Patch>): List<Patch> = patches.map { p ->
    val b = java.nio.ByteBuffer.allocate(p.bytes.size)
    while (b.hasRemaining()) if (ch.read(b, p.offset + b.position()) < 0) break
    Patch(p.offset, b.array())
}

/** Writes patches to [ch] and forces them to the disk. */
fun applyPatches(ch: FileChannel, patches: List<Patch>) {
    for (p in patches) {
        val b = java.nio.ByteBuffer.wrap(p.bytes)
        while (b.hasRemaining()) ch.write(b, p.offset + b.position())
    }
    ch.force(true)
}

/** Saves an undo record: "NLPATCH1", then per patch offset, length, bytes. */
fun savePatches(file: File, patches: List<Patch>) {
    file.parentFile?.mkdirs()
    DataOutputStream(file.outputStream().buffered()).use { o ->
        o.writeBytes("NLPATCH1"); o.writeInt(patches.size)
        for (p in patches) { o.writeLong(p.offset); o.writeInt(p.bytes.size); o.write(p.bytes) }
    }
}

fun loadPatches(file: File): List<Patch> = DataInputStream(file.inputStream().buffered()).use { i ->
    val magic = ByteArray(8).also(i::readFully)
    check(String(magic, Charsets.ISO_8859_1) == "NLPATCH1") { "not an undo record" }
    List(i.readInt()) { Patch(i.readLong(), ByteArray(i.readInt()).also(i::readFully)) }
}
