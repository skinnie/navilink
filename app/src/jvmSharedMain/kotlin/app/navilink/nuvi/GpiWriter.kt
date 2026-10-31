package app.navilink.nuvi

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/*
 * Writes Garmin POI files (.gpi) in the same layout gpsbabel's garmin_gpi writer uses (no icon):
 *   0 header "GRMREC00" + Garmin time + file name
 *   1 "POI" + code page 1252
 *   9 group = category name, then areas (8) of at most [AREA_MAX] places, each with its bounding box
 *       2 place: lat, lon, name; child 10 = description (address / phone)
 *   0xffff end
 * Every record is u16 type, u16 flags, u32 size (+ u32 own-data size when flags & 8), little-endian.
 * gpsbabel's GPIs load on nüvis; ProtocolTest checks our bytes against gpsbabel's.
 */
private val CP1252: Charset = Charset.forName("windows-1252")
const val AREA_MAX = 128
private const val GARMIN_EPOCH = 631065600L // 1989-12-31 00:00 UTC in Unix seconds

private class Buf {
    val out = ByteArrayOutputStream()
    fun u8(v: Int) = out.write(v and 0xff)
    fun u16(v: Int) { u8(v); u8(v shr 8) }
    fun u32(v: Long) { u16((v and 0xffff).toInt()); u16(((v shr 16) and 0xffff).toInt()) }
    fun s32(v: Int) = u32(v.toLong() and 0xffffffffL)
    fun bytes(b: ByteArray) = out.write(b)
    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Text the nüvi can show: Windows-1252, unknown characters as '?'. */
private fun cp(s: String): ByteArray = CP1252.newEncoder()
    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
    .replaceWith("?".toByteArray()).encode(java.nio.CharBuffer.wrap(s)).let { bb -> ByteArray(bb.remaining()).also { bb.get(it) } }

/** u32 total, then one "EN" entry: u16 length + text. */
private fun lstring(s: String): ByteArray {
    val t = cp(s)
    return Buf().apply { u32((t.size + 4).toLong()); bytes("EN".toByteArray()); u16(t.size); bytes(t) }.toByteArray()
}

private fun record(type: Int, main: ByteArray, children: ByteArray? = null): ByteArray = Buf().apply {
    u16(type)
    if (children == null) { u16(0); u32(main.size.toLong()) }
    else { u16(8); u32((main.size + children.size).toLong()); u32(main.size.toLong()) }
    bytes(main); children?.let { bytes(it) }
}.toByteArray()

private fun describe(p: Place): String? =
    listOfNotNull(p.street, listOfNotNull(p.postcode, p.city).joinToString(" ").ifBlank { null }, p.phone)
        .joinToString(", ").ifBlank { null }

private fun placeRecord(p: Place): ByteArray {
    val main = Buf().apply { s32(semiRound(p.lat)); s32(semiRound(p.lon)); u16(1); u8(0); bytes(lstring(p.name)) }.toByteArray()
    // Like gpsbabel: the description falls back to the name.
    return record(2, main, record(10, lstring(describe(p) ?: p.name)))
}

/** Split into areas of at most [AREA_MAX] places: halve along the longer side until small enough. */
private fun areas(ps: List<Place>): List<List<Place>> {
    if (ps.size <= AREA_MAX) return listOf(ps)
    val latSpan = ps.maxOf { it.lat } - ps.minOf { it.lat }
    val lonSpan = ps.maxOf { it.lon } - ps.minOf { it.lon }
    val sorted = if (lonSpan >= latSpan) ps.sortedBy { it.lon } else ps.sortedBy { it.lat }
    val half = sorted.size / 2
    return areas(sorted.subList(0, half)) + areas(sorted.subList(half, sorted.size))
}

private fun areaRecord(ps: List<Place>): ByteArray {
    val main = Buf().apply {
        s32(semiRound(ps.maxOf { it.lat })); s32(semiRound(ps.maxOf { it.lon }))
        s32(semiRound(ps.minOf { it.lat })); s32(semiRound(ps.minOf { it.lon }))
        u32(0); u16(1); u8(0)
    }.toByteArray()
    val kids = ByteArrayOutputStream().apply { ps.forEach { write(placeRecord(it)) } }.toByteArray()
    return record(8, main, kids)
}

/**
 * One .gpi holding [places] under one category ([category] is what the nüvi lists under Custom POIs).
 * [fileName] and [unixTime] only go in the header.
 */
fun buildGpi(category: String, places: List<Place>, fileName: String = "poi.gpi", unixTime: Long = System.currentTimeMillis() / 1000): ByteArray {
    require(places.isNotEmpty()) { "No places to write" }
    val header = Buf().apply {
        bytes("GRMREC00".toByteArray()); u32(unixTime - GARMIN_EPOCH); u16(0)
        val n = fileName.toByteArray(CP1252); u16(n.size); bytes(n)
    }.toByteArray()
    val poi = Buf().apply { bytes("POI".toByteArray()); u8(0); u16(0); bytes("00".toByteArray()); u16(1252); u16(0) }.toByteArray()
    val groupMain = ByteArrayOutputStream().apply {
        write(lstring(category))
        areas(places).forEach { write(areaRecord(it)) }
    }.toByteArray()
    return ByteArrayOutputStream().apply {
        write(record(0, header)); write(record(1, poi))
        write(record(9, groupMain, ByteArray(0)))
        write(record(0xffff, ByteArray(0)))
    }.toByteArray()
}

/** A file name the nüvi's FAT disk accepts. */
fun gpiFileName(category: String): String =
    category.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(40).ifEmpty { "NaviLink" } + ".gpi"

/** Semicircles rounded to nearest, as gpsbabel does (SPL's Bluetooth path truncates instead). */
private fun semiRound(deg: Double): Int = Math.round(deg * (2147483648.0 / 180.0)).toInt()
