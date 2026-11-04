package osmimg

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PremadeTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/osmimg/premade/$name")!!.readBytes().toString(Charsets.UTF_8)

    @Test fun freizeitkarte() {
        val maps = parseFreizeitkarte(fixture("freizeitkarte.html"))
        assertEquals(listOf("AND-en", "AUT+-de", "AZORES-pt"), maps.map { it.id }.sorted())
        val az = maps.single { it.id == "AZORES-pt" }
        assertEquals("Azores", az.name)
        assertEquals("Portuguese", az.group)
        assertEquals("https://download.freizeitkarte-osm.de/garmin/latest/AZORES_pt_gmapsupp.img.zip", az.url)
        val aut = maps.single { it.id == "AUT+-de" }
        assertEquals("Austria and border area", aut.name)
        assertEquals("Spain (canarias)", fzkName("ESP_CANARIAS"))
        assertEquals("Norway (north) and border area", fzkName("NOR+NORTH"))
        assertEquals("Alps (east)", fzkName("ALPS_EAST"))
        assertTrue(aut.url.endsWith("/AUT%2B_de_gmapsupp.img.zip"), aut.url)
        assertEquals("FZK_AUTplus_de", aut.fileStem)
        assertEquals((3.9 * 1048576).toLong(), maps.single { it.id == "AND-en" }.bytes)
    }

    @Test fun openTopoMap() {
        val maps = parseOpenTopoMap(fixture("opentopomap.html"))
        // Map + contours per country; the BaseCamp download is left out.
        assertEquals(6, maps.size)
        val and = maps.filter { it.name == "Andorra" }
        assertEquals(listOf("contour lines (add to the map)", "topo"), and.map { it.variant }.sorted())
        val topo = and.single { it.variant == "topo" }
        assertEquals("https://garmin.opentopomap.org/europe/andorra/otm-andorra.zip", topo.url)
        assertEquals((9.8 * 1048576).toLong(), topo.bytes)
        assertEquals("2026-05-24", topo.updated)
        assertEquals("Europe", topo.group)
        assertEquals((1.7 * 1073741824).toLong(), maps.single { it.name == "Alps" && it.variant == "topo" }.bytes)
    }

    @Test fun bbbike() {
        val maps = parseBbbike(fixture("bbbike.html"))
        assertEquals(setOf("Amsterdam", "Lisbon", "Porto", "San Francisco"), maps.map { it.name }.toSet())
        assertEquals(16, maps.size)
        val l = maps.single { it.name == "Lisbon" && it.variant.startsWith("onroad") }
        assertEquals("https://download.bbbike.org/osm/bbbike/Lisbon/Lisbon.osm.garmin-onroad-latin1.zip", l.url)
        assertEquals("BBBike_Lisbon_onroad", l.fileStem)
        assertNull(l.bytes)
    }

    @Test fun sizes() {
        assertEquals(4L shl 20, parseListedSize("4 MB"))
        assertEquals(512L shl 10, parseListedSize("512K"))
        assertNull(parseListedSize("-"))
    }
}

class ImgInfoTest {
    /** A minimal Garmin img: header, the "." entry, one MPS entry, and the MPS data in block 2. */
    private fun fakeImg(dir: File, name: String, families: List<Pair<Int, String>>): File {
        val bs = 1024
        val b = ByteArray(bs * 3)
        "DSKIMG".toByteArray().copyInto(b, 0x10)
        b[0x61] = 9; b[0x62] = 1 // 2^(9+1) = 1024
        "Test map".toByteArray().copyInto(b, 0x49)
        fun le16(o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
        fun le32(o: Int, v: Int) { le16(o, v and 0xFFFF); le16(o + 2, v ushr 16) }
        b[0x400] = 1; le32(0x400 + 12, 0x800) // "." entry: header + directory end at 0x800
        b[0x600] = 1; "MAKEGMAP".toByteArray().copyInto(b, 0x601); "MPS".toByteArray().copyInto(b, 0x609)
        val mps = java.io.ByteArrayOutputStream()
        for ((fid, n) in families) {
            val nb = n.toByteArray() + 0
            mps.write('F'.code); mps.write(4 + nb.size); mps.write(0)
            mps.write(1); mps.write(0); mps.write(fid and 0xFF); mps.write(fid shr 8); mps.write(nb)
            mps.write('L'.code); mps.write(8); mps.write(0)
            mps.write(1); mps.write(0); mps.write(fid and 0xFF); mps.write(fid shr 8); mps.write(ByteArray(4))
        }
        val m = mps.toByteArray()
        le32(0x600 + 12, m.size); le16(0x600 + 0x20, 2)
        m.copyInto(b, 2 * bs)
        return File(dir, name).apply { parentFile.mkdirs(); writeBytes(b) }
    }

    @Test fun readsFamilies() {
        val d = Files.createTempDirectory("img").toFile()
        val info = readImgInfo(fakeImg(d, "a.img", listOf(630 to "Lisbon style=onroad", 6324 to "OSM")))!!
        assertEquals("Test map", info.description)
        assertEquals(listOf(630, 6324), info.families.map { it.familyId })
        assertEquals("Lisbon style=onroad", info.families[0].name)
        assertNull(readImgInfo(File(d, "a.img").also { it.writeText("not an img") }))
    }

    /** Real maps: -Pnl.imgs=<folder> reads every .img under it (used on BBBike/Freizeitkarte/OpenTopoMap downloads). */
    @Test fun realImgs() {
        val dir = System.getProperty("nl.imgs") ?: return
        File(dir).walk().filter { it.name.endsWith(".img", true) }.forEach { f ->
            val info = readImgInfo(f)
            println("${f.name}: ${info?.description} ${info?.families}")
            assertTrue(info != null && info.families.isNotEmpty(), f.path)
        }
    }

    @Test fun freeIds() {
        assertEquals(6324, freeFamilyId(setOf(630)))
        assertEquals(6326, freeFamilyId(setOf(6324, 6325), parts = 2))
        assertEquals(7000, freeFamilyId(setOf(6324), preferred = 7000))
        assertEquals(6325, freeFamilyId(setOf(6324, 6327), parts = 2))
    }

    @Test fun clashesAndInstall() {
        val d = Files.createTempDirectory("dev").toFile()
        val dev = File(d, "NUVI"); val sd = File(d, "SD")
        fakeImg(dev, "GARMIN/gmapsupp.img", listOf(630 to "Porto"))
        fakeImg(dev, "GARMIN/gmapbmap.img", listOf(1 to "Base"))
        File(sd, "Garmin").mkdirs()
        val profile = Profiles.UNKNOWN_DRIVE
        val roots = mapOf(Storage.INTERNAL to dev, Storage.SD to sd)
        assertEquals(listOf("GARMIN/gmapbmap.img", "GARMIN/gmapsupp.img"), existingImgs(dev, profile, Storage.INTERNAL))
        val installed = installedMaps(roots, profile)
        val clash = familyClashes(installed, listOf(630))
        assertEquals(1, clash.size); assertTrue(clash[0].contains("(GARMIN/gmapsupp.img) on the built-in memory"), clash[0])
        assertTrue(familyClashes(installed, listOf(630), replacing = listOf("garmin/GMAPSUPP.img")).isEmpty())

        // Replacing keeps a backup and matches the existing folder's case; a new file goes to the SD card.
        val newMap = fakeImg(d, "new.img", listOf(630 to "Lisbon"))
        val backups = installMaps(listOf(Copy(newMap, Storage.INTERNAL, "Garmin/gmapsupp.img"), Copy(newMap, Storage.SD, "Garmin/gmapsupp.img")),
            roots, File(d, "backup")) {}
        assertEquals(1, backups.size)
        assertEquals("Porto", readImgInfo(backups[0])!!.families[0].name)
        assertEquals("Lisbon", readImgInfo(File(dev, "GARMIN/gmapsupp.img"))!!.families[0].name)
        assertTrue(File(sd, "Garmin/gmapsupp.img").isFile)
        assertTrue(dev.walk().none { it.name.endsWith(".tmp") })
    }
}
