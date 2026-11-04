package osmimg

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapBuildTest {
    @Test fun bundledRegionList() {
        val r = bundledRegions()
        assertEquals(555, r.size)
        val pt = r.single { it.id == "portugal" }
        assertEquals("europe", pt.parent)
        assertTrue(pt.pbfUrl.endsWith("/europe/portugal-latest.osm.pbf"))
        assertTrue(pt.bytes > 100_000_000)
        assertTrue(pt.bbox!![0] < -9 && pt.bbox!![3] > 42)
        assertTrue(r.childrenOf("").any { it.id == "europe" })
        assertTrue(r.childrenOf("europe").any { it.id == "portugal" })
    }

    @Test fun grouping() {
        assertEquals(listOf(0..1, 2..2, 3..4), groupBySize(listOf(4, 5, 8, 3, 3), 10))
        assertEquals(listOf(0..0, 1..1), groupBySize(listOf(15, 2), 10)) // one oversized tile gets its own group
        assertEquals(listOf(0..2), groupBySize(listOf(1, 1, 1), 10))
        assertEquals(emptyList(), groupBySize(emptyList(), 10))
    }

    /** Real build, off by default: ./gradlew :app:desktopTest -Pnl.maps=portugal [-Pnl.maps.max=2147483647] [-Pnl.maps.bike=true] */
    @Test fun realBuild() {
        val id = System.getProperty("nl.maps") ?: return
        val region = bundledRegions().single { it.id == id }
        val cache = defaultCache()
        val max = System.getProperty("nl.maps.max")?.toLong()
        val routing = if (System.getProperty("nl.maps.bike") == "true") Routing.BIKE else Routing.CAR
        val out = File(cache, "out/$id" + if (routing == Routing.BIKE) "-bike" else "")
        val res = MapBuilder(MapTools(File(cache, "tools")), cache, ::println)
            .build(region, BuildOptions("OSM_$id", maxPartBytes = max, routing = routing), out)
        assertTrue(res.parts.isNotEmpty())
        res.parts.forEach { assertTrue(max == null || it.length() <= max, "${it.name} is ${it.length()}") }
    }
}
