package app.navilink

import app.navilink.core.*
import app.navilink.nuvi.Place
import app.navilink.nuvi.buildGpi
import app.navilink.pois.*
import kotlinx.coroutines.runBlocking
import osmimg.DriveFs
import osmimg.FileDriveFs
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PoiPacksTest {
    private val leiria = 39.7436 to -8.8071
    private fun east(m: Double) = leiria.second + m / (111_320.0 * Math.cos(Math.toRadians(leiria.first)))

    /** Answers Overpass with two places of whatever kind is asked: one at Leiria, one 2 km away. */
    private class FakeNet(val lat: Double, val lon: Double, val farLon: Double) : Net {
        override fun get(url: String, headers: Map<String, String>, followRedirects: Boolean, timeoutMs: Int): HttpResponse {
            val body = """{"elements":[{"type":"node","lat":$lat,"lon":$lon,"tags":{"name":"Near"}},
                {"type":"node","lat":$lat,"lon":$farLon,"tags":{"name":"Far"}}]}"""
            return HttpResponse(200, emptyMap(), body.toByteArray())
        }
    }

    private fun drive(): File = File("build/tmp/poipacks").apply {
        deleteRecursively(); File(this, "Garmin/POI").mkdirs(); File(this, "Map").mkdirs()
        // iOverlander-like file: a fuel station at Leiria, and some water 40 m east of it.
        File(this, "Garmin/POI/Overlander.gpi").writeBytes(buildGpi("Fuel Station", listOf(Place("Galp", leiria.first, leiria.second)), "x.gpi"))
        File(this, "Garmin/POI/Water.gpi").writeBytes(buildGpi("Water", listOf(Place("Tap", leiria.first, east(40.0))), "y.gpi"))
        File(this, "Map/D4935350A.img").writeBytes(ByteArray(0x200).also {
            "DSKIMG".toByteArray().copyInto(it, 0x10); "CN Europe NTU 2027.3".toByteArray().copyInto(it, 0x49); "0 Foursquare".toByteArray().copyInto(it, 0x65)
        })
    }

    private fun packs(root: File) = PoiPacks(kotlinx.coroutines.GlobalScope, object : PoiHost {
        override fun drives() = listOf("built-in memory" to (FileDriveFs(root) as DriveFs))
        override val canPick = false
        override suspend fun pickDrive() = false
        override fun deviceName() = "nüvi 3490"
    }, FakeNet(leiria.first, east(20.0), east(2000.0)))

    @Test fun leavesOutWhatTheGarminHasOfTheSameKind() = runBlocking {
        val root = drive()
        val m = packs(root)
        m.scanNow()
        assertEquals(3, m.found.size)
        assertTrue(m.fsqLeftOut)
        m.fsq += FsqPoiType.RESTAURANT
        assertEquals(listOf<PoiKind>(OsmPoiType.FUEL, OsmPoiType.DRINKING_WATER), m.osm.clear().let { m.osm += OsmPoiType.FUEL; m.osm += OsmPoiType.DRINKING_WATER; m.chosen })
        m.run(m.chosen)
        val fuel = m.results.first { it.kind == OsmPoiType.FUEL }
        // "Near" is 20 m from the Galp already there: left out. "Far" is 2 km away: kept.
        assertEquals(1, fuel.written); assertEquals(1, fuel.alreadyThere)
        assertEquals(listOf("Far"), readGpi(File(root, "Garmin/POI/OSM Fuel.gpi").readBytes()).categories.flatMap { it.points }.map { it.name })
        // Water: "Near" is 20 m from the tap, so it goes too; the fuel station next to it doesn't count for water.
        val water = m.results.first { it.kind == OsmPoiType.DRINKING_WATER }
        assertEquals(1, water.written)
        assertFalse(File(root, "Garmin/POI/FSQ Restaurants.gpi").exists())
    }

    @Test fun runningAgainReplacesOurOwnFiles() = runBlocking {
        val root = drive()
        val m = packs(root).apply { osm.clear(); osm += OsmPoiType.CAMPSITE }
        m.scanNow(); m.run(m.chosen)
        m.scanNow(); m.run(m.chosen)
        // The second run doesn't count the first run's file as "already there".
        assertEquals(2, m.results.single().written)
        assertEquals(2, readGpi(File(root, "Garmin/POI/OSM Campsites.gpi").readBytes()).categories.sumOf { it.points.size })
    }

    /** Real downloads against a real .gpi: `-Pnl.poilive=<folder with iOverlander .gpi>`. */
    @Test fun live() = runBlocking {
        val src = System.getProperty("nl.poilive")?.let(::File)?.takeIf { it.isDirectory } ?: return@runBlocking
        val root = File("build/tmp/poilive").apply { deleteRecursively(); File(this, "Garmin/POI").mkdirs() }
        src.listFiles()!!.filter { it.name.endsWith(".gpi", true) }.forEach {
            java.nio.file.Files.createSymbolicLink(File(root, "Garmin/POI/${it.name}").toPath(), it.canonicalFile.toPath())
        }
        val m = PoiPacks(kotlinx.coroutines.GlobalScope, object : PoiHost {
            override fun drives() = listOf("built-in memory" to (FileDriveFs(root) as DriveFs))
            override val canPick = false
            override suspend fun pickDrive() = false
            override fun deviceName() = "nüvi"
        }, log = ::println).apply { osm.clear(); osm += OsmPoiType.LPG; fsq += FsqPoiType.FUEL }
        m.scanNow()
        println("found: " + m.found.map { "${it.title} (${it.publisher})" })
        val t = System.currentTimeMillis()
        m.run(m.chosen)
        println("took ${(System.currentTimeMillis() - t) / 1000} s")
        m.results.forEach { println("${it.kind.category}: ${it.written} written, ${it.alreadyThere} left out") }
        assertTrue(m.results.all { it.written > 0 })
    }
}
