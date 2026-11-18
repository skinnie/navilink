package app.navilink

import app.navilink.core.*
import app.navilink.nuvi.Place
import app.navilink.nuvi.buildGpi
import app.navilink.pois.*
import osmimg.FileDriveFs
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PoiSetsTest {
    private fun le(vararg parts: Any): ByteArray {
        val o = ByteArrayOutputStream()
        for (p in parts) when (p) {
            is Short -> o.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(p).array())
            is Int -> o.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(p).array())
            is String -> o.write(p.toByteArray(Charsets.UTF_8))
            is ByteArray -> o.write(p)
        }
        return o.toByteArray()
    }
    private fun lstr(s: String) = le("EN", s.toByteArray().size.toShort(), s)

    /** A header laid out like iOverlander's (record 0 with its title, record 1 with name, copyright, part number). */
    private fun overlanderLike(title: String): ByteArray {
        val rec0 = le("GRMREC01", 0x447aeec9, 0x0301.toShort(), title.length.toShort(), title)
        val texts = le(lstr("iOverlander WW"), lstr("Copyright ©2026 by iOverlander, LLC"), 12.toShort(), "006-D8166-36")
        val rec1 = le("POI", 0.toShort(), 0.toShort(), "01", 65001.toShort(), 0.toShort(), texts)
        return le(0.toShort(), 8.toShort(), rec0.size, rec0.size, rec0, 1.toShort(), 0.toShort(), rec1.size, rec1, 0xffff.toShort(), 0.toShort(), 0)
    }

    @Test fun recognisesIOverlanderWhateverTheFileIsCalled() {
        val texts = gpiHeaderTexts(overlanderLike("iOverlander_2031_05"))!!
        assertTrue("iOverlander WW" in texts && "006-D8166-36" in texts, texts.toString())
        assertEquals(PoiPublisher.IOVERLANDER, publisherOf(texts))
        // Our own files say nothing of the kind.
        val ours = gpiHeaderTexts(buildGpi("OSM Fuel", listOf(Place("Galp", 39.7, -8.8)), "OSM Fuel.gpi"))!!
        assertNull(publisherOf(ours))
        assertNull(gpiHeaderTexts("not a gpi at all, just text".toByteArray()))
    }

    @Test fun imgDescriptionWithAndWithoutXor() {
        for (x in listOf(0, 0x44)) {
            val h = ByteArray(0x200)
            "DSKIMG".toByteArray().copyInto(h, 0x10)
            // The first 20 characters at 0x49, the rest at 0x65, as in City Navigator's add-on.
            "CN Europe NTU 2027.3".toByteArray().copyInto(h, 0x49)
            "0 Foursquare".toByteArray().copyInto(h, 0x65)
            for (i in h.indices) h[i] = (h[i].toInt() xor x).toByte()
            val d = imgDescription(h)!!
            assertTrue(d.startsWith("CN Europe NTU 2027.30") && d.endsWith("Foursquare"), d)
            assertEquals(PoiPublisher.FOURSQUARE, publisherOf(listOf(d)))
        }
        assertNull(imgDescription(ByteArray(0x200)))
    }

    /** The streaming reader finds the same places, under the same category names, as the full reader. */
    @Test fun placesMatchTheFullReader() {
        val places = (0 until 700).map { Place("P$it", 37.0 + (it % 30) * 0.13, -9.4 + (it / 30) * 0.11) }
        val gpi = buildGpi("OSM Fuel", places, "t.gpi")
        fun sorted(l: List<Pair<Double, Double>>) = l.sortedWith(compareBy({ it.first }, { it.second }))
        val full = readGpi(gpi).categories.associate { c -> c.name to sorted(c.points.map { it.lat to it.lon }) }
        val streamed = gpiPlacesByCategory(gpi.inputStream()).mapValues { sorted(it.value) }
        assertEquals(setOf("OSM Fuel"), streamed.keys)
        assertEquals(full, streamed)
    }

    @Test fun sameKindByCategoryName() {
        val fuel = sameKindWords(OsmPoiType.FUEL)
        assertTrue(fuel.containsMatchIn("Fuel Station") && fuel.containsMatchIn("Posto de combustível") && fuel.containsMatchIn("FSQ Fuel stations"))
        assertFalse(fuel.containsMatchIn("Water"))
        assertTrue(sameKindWords(OsmPoiType.CAMPSITE).containsMatchIn("Wild Camping"))
        assertTrue(sameKindWords(OsmPoiType.DRINKING_WATER).containsMatchIn("Água"))
        assertFalse(sameKindWords(OsmPoiType.DRINKING_WATER).containsMatchIn("Plateau"))
        assertFalse(sameKindWords(FsqPoiType.BAR).containsMatchIn("Barbershop"))
    }

    @Test fun nearIndexUses150Metres() {
        val n = NearIndex().apply { add(39.7436, -8.8071); add(60.0, 10.0) }
        assertTrue(n.near(39.7436 + 100 / 111_320.0, -8.8071))       // 100 m north
        assertFalse(n.near(39.7436 + 200 / 111_320.0, -8.8071))      // 200 m north
        assertTrue(n.near(60.0, 10.0 + 140 / (111_320.0 * 0.5)))     // 140 m east at 60°N
        assertFalse(n.near(60.0, 10.0 + 170 / (111_320.0 * 0.5)))
    }

    @Test fun findsSetsOnADrive() {
        val root = File("build/tmp/poisets").apply { deleteRecursively(); File(this, "Garmin/POI").mkdirs(); File(this, "Map/CN").mkdirs() }
        File(root, "Garmin/POI/iOverlander WW 31.05.gpi").writeBytes(overlanderLike("iOverlander_2031_05"))
        File(root, "Garmin/POI/My places.gpi").writeBytes(buildGpi("My places", listOf(Place("Home", 39.7, -8.8)), "My places.gpi"))
        val img = ByteArray(0x400).also { "DSKIMG".toByteArray().copyInto(it, 0x10); "Foursquare".toByteArray().copyInto(it, 0x49) }
        File(root, "Map/CN/D4935350A.img").writeBytes(img)
        File(root, "Garmin/gmapsupp.img").writeBytes(ByteArray(0x400).also { "DSKIMG".toByteArray().copyInto(it, 0x10); "OSM Portugal".toByteArray().copyInto(it, 0x49) })
        val found = findPoiSets(FileDriveFs(root), "nüvi").sortedBy { it.path }
        assertEquals(listOf("Garmin/POI/My places.gpi", "Garmin/POI/iOverlander WW 31.05.gpi", "Map/CN/D4935350A.img"), found.map { it.path })
        assertEquals(listOf(null, PoiPublisher.IOVERLANDER, PoiPublisher.FOURSQUARE), found.map { it.publisher })
        assertTrue(found[2].isImg && !found[2].readable)
    }

    /** The real files, when a folder holding them is given with -Pnl.poisets=<dir>. */
    @Test fun realFiles() {
        val dir = System.getProperty("nl.poisets")?.let(::File)?.takeIf { it.isDirectory } ?: return
        dir.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("gpi", "img") }.forEach { f ->
            val head = f.inputStream().use { readHead(it, 65536) }
            val texts = if (f.extension.equals("gpi", true)) gpiHeaderTexts(head) else imgDescription(head)?.let { listOf(it) }
            val pub = texts?.let { publisherOf(it) }
            println("${f.name}: $pub $texts")
            if (f.extension.equals("gpi", true) && pub == PoiPublisher.IOVERLANDER) {
                val t = System.currentTimeMillis()
                val cats = f.inputStream().use { gpiPlacesByCategory(it) }
                println("  ${cats.values.sumOf { it.size }} places read in ${System.currentTimeMillis() - t} ms")
                cats.forEach { (k, v) -> println("    $k: ${v.size}") }
                // The full reader agrees on every category.
                val full = readGpi(f.readBytes()).categories.groupBy { it.name }.mapValues { e -> e.value.sumOf { it.points.size } }
                assertEquals(full, cats.mapValues { it.value.size })
            }
        }
    }
}
