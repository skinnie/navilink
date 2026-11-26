package osmimg

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.*

/** A tile of a test map: its name and area (N, E, S, W in degrees). */
data class T(val name: String, val n: Double = 39.0, val e: Double = -8.5, val s: Double = 38.5, val w: Double = -9.5)

/**
 * Writes a small but well-formed Garmin .img: header, directory, an MPS and a TRE (+ NOD) per tile,
 * in 512-byte blocks. [xor] scrambles the whole file the way some Garmin maps are.
 */
fun fakeImg(file: File, desc: String = "Test map", family: Int = 6324, product: Int = 1, setName: String = "Test set",
            tiles: List<T> = listOf(T("00000001")), locked: Boolean = false, unlock: Boolean = false, xor: Int = 0) {
    val bs = 512
    class Sub(val name: String, val type: String, val data: ByteArray)
    fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    fun le24(deg: Double): ByteArray { val v = Math.round(deg * (1 shl 24) / 360.0).toInt(); return byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte()) }
    val subs = mutableListOf<Sub>()
    val mps = java.io.ByteArrayOutputStream()
    fun rec(type: Char, data: ByteArray) { mps.write(type.code); mps.write(le16(data.size)); mps.write(data) }
    for (t in tiles) {
        val num = t.name.toLongOrNull() ?: 1
        rec('L', le16(product) + le16(family) + byteArrayOf(num.toByte(), (num shr 8).toByte(), (num shr 16).toByte(), (num shr 24).toByte()) +
            (setName + "\u0000" + "Area ${t.name}\u0000").toByteArray(Charsets.ISO_8859_1))
    }
    rec('F', le16(product) + le16(family) + (setName + "\u0000").toByteArray(Charsets.ISO_8859_1))
    if (unlock) rec('U', "ABCDEFGHIJKLMNOPQRSTUVWXY\u0000".toByteArray())
    subs += Sub("MAKEGMAP", "MPS", mps.toByteArray())
    for (t in tiles) {
        val tre = ByteArray(0x40)
        le16(0x40).copyInto(tre, 0); "GARMIN TRE".toByteArray().copyInto(tre, 2)
        if (locked) tre[0x0D] = 0x80.toByte()
        le24(t.n).copyInto(tre, 0x15); le24(t.e).copyInto(tre, 0x18); le24(t.s).copyInto(tre, 0x1B); le24(t.w).copyInto(tre, 0x1E)
        subs += Sub(t.name, "TRE", tre)
        subs += Sub(t.name, "NOD", ByteArray(0x20).also { "GARMIN NOD".toByteArray().copyInto(it, 2) })
    }
    val dirStart = 0x400
    val dirEnd = dirStart + 512 * (1 + subs.size)
    var block = dirEnd / bs
    val out = ByteArray(dirEnd + subs.sumOf { (it.data.size + bs - 1) / bs * bs })
    val h = out
    "DSKIMG".toByteArray().copyInto(h, 0x10); "GARMIN".toByteArray().copyInto(h, 0x41)
    h[0x40] = (dirStart / 512).toByte(); h[0x61] = 9; h[0x62] = 0
    val d = desc.padEnd(50).toByteArray(Charsets.ISO_8859_1)
    d.copyOfRange(0, 20).copyInto(h, 0x49); d.copyOfRange(20, 50).copyInto(h, 0x65)
    // root entry: spans header + directory
    out[dirStart] = 1; "           ".toByteArray().copyInto(out, dirStart + 1)
    byteArrayOf(dirEnd.toByte(), (dirEnd shr 8).toByte(), (dirEnd shr 16).toByte(), 0).copyInto(out, dirStart + 12)
    subs.forEachIndexed { i, s ->
        val e = dirStart + 512 * (i + 1)
        out[e] = 1
        s.name.padEnd(8).toByteArray().copyInto(out, e + 1); s.type.toByteArray().copyInto(out, e + 9)
        val n = s.data.size
        byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), 0).copyInto(out, e + 12)
        val blocks = (n + bs - 1) / bs
        for (k in 0 until 240) le16(if (k < blocks) block + k else 0xFFFF).copyInto(out, e + 0x20 + 2 * k)
        s.data.copyInto(out, block * bs)
        block += blocks
    }
    if (xor != 0) { for (i in out.indices) out[i] = (out[i].toInt() xor xor).toByte() }
    file.parentFile.mkdirs()
    file.writeBytes(out)
}

private val ETREX_XML = """<?xml version="1.0" encoding="UTF-8"?>
<Device xmlns="http://www.garmin.com/xmlschemas/GarminDevice/v2"><Model><PartNumber>006-B1-00</PartNumber><SoftwareVersion>100</SoftwareVersion><Description>eTrex 10</Description></Model>
<MassStorageMode>
<DataType><Name>NMaps</Name><File><Specification><Identifier>IMG</Identifier></Specification><Location><Path>Garmin</Path><FileExtension>img</FileExtension></Location><TransferDirection>InputToUnit</TransferDirection></File></DataType>
<DataType><Name>BaseMaps</Name><File><Specification><Identifier>IMG</Identifier></Specification><Location><Path>Garmin</Path><BaseName>gmapbmap</BaseName><FileExtension>img</FileExtension></Location></File></DataType>
<DataType><Name>SupplementalMaps</Name><File><Specification><Identifier>IMG</Identifier></Specification><Location><Path>Garmin</Path><BaseName>gmapsupp</BaseName><FileExtension>img</FileExtension></Location></File></DataType>
</MassStorageMode></Device>"""

class DeviceContentsTest {
    private val tmp = Files.createTempDirectory("devtest").toFile()
    @AfterTest fun clean() { tmp.deleteRecursively() }

    @Test fun readsTilesFamiliesAndFlags() {
        for (xor in listOf(0, 0x5A)) {
            val f = File(tmp, "m$xor.img")
            fakeImg(f, desc = "Lisbon", tiles = listOf(T("00000001"), T("00000002", n = 40.0, s = 39.0)), locked = true, unlock = true, xor = xor)
            val d = readImgDetails(f)!!
            assertEquals("Lisbon", d.description)
            assertEquals(listOf(MapFamily(6324, 1, "Test set")), d.families)
            assertEquals(listOf("00000001", "00000002"), d.tiles.map { it.name })
            assertTrue(d.locked && d.routable && !d.elevation)
            assertEquals(1, d.unlockCodes)
            assertNull(d.damaged)
            val b = d.bounds!!
            assertEquals(40.0, b.north, 1e-4); assertEquals(38.5, b.south, 1e-4); assertEquals(-9.5, b.west, 1e-4)
            assertEquals(readImgInfo(f), d.info)
        }
    }

    @Test fun cutShortFileIsDamaged() {
        val f = File(tmp, "cut.img")
        fakeImg(f, tiles = listOf(T("00000001"), T("00000002")))
        RandomAccessFile(f, "rw").use { it.setLength(it.length() - 512) }
        assertNotNull(readImgDetails(f)!!.damaged)
    }

    @Test fun editsNamesInPlaceAndUndoes() {
        for (xor in listOf(0, 0x33)) {
            val dev = File(tmp, "dev$xor"); val img = File(dev, "Garmin/map.img")
            fakeImg(img, xor = xor, tiles = listOf(T("00000001"), T("00000002")))
            val before = img.readBytes()
            val fs = FileDriveFs(dev)
            val u1 = editMap(fs, Storage.INTERNAL, "Garmin/map.img", File(tmp, "bk"), "name") { descriptionPatches(it, "Lisboa à pé") }
            val u2 = editMap(fs, Storage.INTERNAL, "Garmin/map.img", File(tmp, "bk"), "set") { mapSetNamePatches(it, 6324, "A much longer map set name than before, still fine") }
            val d = readImgDetails(img)!!
            assertEquals("Lisboa à pé", d.description)
            assertEquals("A much longer map set name than before, still fine", d.families.single().name)
            assertEquals(2, d.tiles.size)
            assertTrue(u2.record.isFile)
            undoEdit(fs, Undo(u2.label, u2.storage, u2.path, loadPatches(u2.record), u2.record))
            undoEdit(fs, u1)
            assertContentEquals(before, img.readBytes())
        }
    }

    @Test fun refusesNamesTheMapCantHold() {
        val img = File(tmp, "x.img"); fakeImg(img)
        RandomAccessFile(img, "r").use { r ->
            assertFails { descriptionPatches(r.channel, "x".repeat(51)) }
            assertFails { descriptionPatches(r.channel, "Łódź") }
            assertFails { mapSetNamePatches(r.channel, 6324, "y".repeat(2000)) } // more than the MPS's one block
            assertFails { mapSetNamePatches(r.channel, 999, "nope") }
        }
    }

    private fun etrex(): File = File(tmp, "etrex").also { File(it, "Garmin").mkdirs(); File(it, "Garmin/GarminDevice.xml").writeText(ETREX_XML) }

    private fun scan(dev: File, sd: File? = null): DeviceContents {
        val xml = FileDriveFs(dev).deviceXml()!!
        val drives = buildMap<Storage, DriveFs> { put(Storage.INTERNAL, FileDriveFs(dev)); sd?.let { put(Storage.SD, FileDriveFs(it)) } }
        return scanDevice(drives, Profiles.forDevice(xml), xml.model, xml.rasterPaths)
    }

    @Test fun findsDuplicatesClashesAndSkippedFiles() {
        val dev = etrex(); val sd = File(tmp, "sd")
        fakeImg(File(dev, "Garmin/a.img"), setName = "Alpha", tiles = listOf(T("00000001")))
        fakeImg(File(sd, "Garmin/gmapsupp.img"), setName = "Alpha", tiles = listOf(T("00000001")))       // same tiles twice
        fakeImg(File(dev, "Garmin/b.img"), family = 7000, setName = "Bravo", tiles = listOf(T("00000010")))
        fakeImg(File(sd, "Garmin/c.img"), family = 7000, product = 2, setName = "Charlie", tiles = listOf(T("00000020"))) // other map, same ID
        fakeImg(File(dev, "loose.img"))                                                                    // not where an eTrex looks
        File(dev, "Garmin/gmapbmap.img").writeBytes(ByteArray(0))                                         // empty base map
        File(sd, "Garmin/BirdsEye").mkdirs(); File(sd, "Garmin/BirdsEye/x.jnx").writeBytes(ByteArray(10)) // eTrex 10: no BirdsEye
        val c = scan(dev, sd)
        val titles = c.findings.map { it.title }
        assertTrue("The same map is on the device twice" in titles, titles.toString())
        assertTrue("Two maps use the same map ID (7000)" in titles, titles.toString())
        assertTrue("The device skips loose.img" in titles)
        assertTrue("The device skips x.jnx" in titles)
        assertTrue("gmapbmap.img is empty" in titles)
        assertEquals(Level.BAD, c.findings.first().level)
        assertEquals(FileKind.SYSTEM, c.files.single { it.name == "gmapbmap.img" }.kind)
        assertEquals(false, c.files.single { it.name == "loose.img" }.readByDevice)
        assertTrue(c.files.single { it.name == "loose.img" }.note!!.contains("any .img in Garmin/"))
    }

    @Test fun aGarminMapSplitOverTwoFilesIsFine() {
        val dev = etrex(); val sd = File(tmp, "sd")
        fakeImg(File(dev, "Garmin/north.img"), family = 13605, setName = "CN Europe", tiles = listOf(T("I0000001")))
        fakeImg(File(sd, "Garmin/south.img"), family = 13605, setName = "CN Europe", tiles = listOf(T("I0000002")))
        assertTrue(scan(dev, sd).findings.none { it.level == Level.BAD }, scan(dev, sd).findings.toString())
    }

    @Test fun theOverviewTileOfASplitMapIsNotADuplicate() {
        val dev = etrex(); val sd = File(tmp, "sd")
        val overview = T("05391000", n = 52.0, e = 58.0, s = -23.0, w = -64.0)
        fakeImg(File(dev, "Garmin/gmapnord.img"), family = 7453, setName = "TOPO France", tiles = listOf(overview, T("05391212", n = 50.0, s = 49.0, e = 3.0, w = 2.0)))
        fakeImg(File(sd, "Garmin/gmapsud.img"), family = 7453, setName = "TOPO France", tiles = listOf(overview, T("05391300", n = 44.0, s = 43.0, e = 3.0, w = 2.0)))
        assertTrue(scan(dev, sd).findings.none { it.level == Level.BAD }, scan(dev, sd).findings.toString())
        // A real second copy is still found.
        fakeImg(File(sd, "Garmin/copy.img"), family = 7453, setName = "TOPO France", tiles = listOf(overview, T("05391212", n = 50.0, s = 49.0, e = 3.0, w = 2.0)))
        assertTrue(scan(dev, sd).findings.any { it.title == "The same map is on the device twice" })
    }

    @Test fun cardOnItsOwnDoesNotGuessNamingRules() {
        val sd = File(tmp, "card")
        fakeImg(File(sd, "Garmin/topo.img"))
        fakeImg(File(sd, "Garmin/gmapsupp.img"), family = 6400)
        val c = scanDevice(mapOf(Storage.SD to FileDriveFs(sd)), Profiles.UNKNOWN_DRIVE, null)
        assertNull(c.files.single { it.name == "topo.img" }.readByDevice)
        assertEquals(true, c.files.single { it.name == "gmapsupp.img" }.readByDevice)
        assertTrue(c.findings.none { it.level != Level.INFO }, c.findings.toString())
    }

    @Test fun movesRenamesAndRemoves() {
        val dev = etrex(); val sd = File(tmp, "sd").also { it.mkdirs() }
        fakeImg(File(dev, "Garmin/a.img"))
        val bytes = File(dev, "Garmin/a.img").readBytes()
        val int = FileDriveFs(dev); val card = FileDriveFs(sd)
        moveMap(int, "Garmin/a.img", card, "Garmin/a.img", {})
        assertFalse(File(dev, "Garmin/a.img").exists())
        assertContentEquals(bytes, File(sd, "Garmin/a.img").readBytes())
        assertFalse(File(sd, "Garmin/a.img.tmp").exists())
        renameMapFile(card, "Garmin/a.img", "Topo.img")
        assertTrue(File(sd, "Garmin/Topo.img").isFile)
        assertFails { renameMapFile(card, "Garmin/Topo.img", "Topo") }
        fakeImg(File(sd, "Garmin/other.img"))
        assertFails { renameMapFile(card, "Garmin/Topo.img", "other.img") }
        moveMap(card, "Garmin/Topo.img", card, "Map/Topo.img", {}) // same drive: a rename
        assertTrue(File(sd, "Map/Topo.img").isFile)
        val kept = removeMap(card, Storage.SD, "Map/Topo.img", File(tmp, "bk"), {})!!
        assertFalse(File(sd, "Map/Topo.img").exists())
        assertContentEquals(bytes, kept.readBytes())
    }
}
