package app.navilink

import app.navilink.core.*
import app.navilink.pois.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Parquet reader against files written by pyarrow (fixtures from MapsToTom), and the Foursquare source. */
class FoursquareTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/parquet/$name")!!.readBytes()
    private val expected = Json.parse(res("expected.json").decodeToString()) as List<*>

    private class Bytes(val b: ByteArray) : RangeSource {
        override val size get() = b.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray = b.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    /** Serves [files] like a web server that answers range requests. */
    private class RangeNet(val files: Map<String, ByteArray>) : Net {
        override fun get(url: String, headers: Map<String, String>, followRedirects: Boolean, timeoutMs: Int): HttpResponse {
            val b = files[url] ?: return HttpResponse(404, emptyMap(), ByteArray(0))
            val r = headers["Range"]!!.removePrefix("bytes=")
            val (s, e) = if (r.startsWith("-")) (b.size - r.drop(1).toInt()) to b.size - 1 else r.substringBefore('-').toInt() to r.substringAfter('-').toInt()
            return HttpResponse(206, mapOf("Content-Range" to "bytes $s-$e/${b.size}"), b.copyOfRange(s, e + 1))
        }
    }

    @Test fun readsEveryLayoutLikePyarrowWroteIt() {
        for (f in listOf("v1-snappy-dict", "v2-none-plain", "v2-snappy-dict")) {
            val pf = ParquetFile(Bytes(res("$f.parquet")))
            assertEquals(3, pf.rowGroups.size, f)
            var row = 0
            pf.rowGroups.indices.forEach { rg ->
                val cols = listOf("name", "latitude", "longitude", "country", "date_refreshed", "date_closed").associateWith { pf.read(rg, it) }
                val labels = pf.read(rg, "fsq_category_labels.list.element")
                for (i in labels.indices) {
                    val e = expected[row++] as Map<*, *>
                    for ((k, v) in cols) assertEquals(e[k], v[i], "$f row ${row - 1} $k")
                    assertEquals((e["fsq_category_labels"] as? List<*>).orEmpty(), labels[i], "$f row ${row - 1} labels")
                }
            }
            assertEquals(expected.size, row)
        }
    }

    @Test fun fetchesOnlyTheChosenCountryAndKinds() {
        val url = "https://example.org/places/"
        val net = RangeNet(mapOf(url + "0.parquet" to res("v1-snappy-dict.parquet")))
        val index = FsqIndex("2025-02-06", url, mapOf("PT" to mapOf("0.parquet" to listOf(0, 1)), "ES" to mapOf("0.parquet" to listOf(1, 2))))
        val got = fetchFoursquare(index, listOf("PT"), setOf(FsqPoiType.RESTAURANT, FsqPoiType.PHARMACY), 2020, net)
        val want = expected.map { it as Map<*, *> }.filter { e ->
            e["country"] == "PT" && e["date_closed"] == null && (e["date_refreshed"] as String) >= "2020" && e["name"] != null
        }
        fun kind(e: Map<*, *>) = FsqPoiType.of((e["fsq_category_labels"] as? List<*>).orEmpty())
        assertEquals(want.count { kind(it) == FsqPoiType.RESTAURANT }, got.getValue(FsqPoiType.RESTAURANT).size)
        assertEquals(want.count { kind(it) == FsqPoiType.PHARMACY }, got.getValue(FsqPoiType.PHARMACY).size)
    }

    @Test fun kindsMatchWholePathSteps() {
        assertEquals(FsqPoiType.RESTAURANT, FsqPoiType.of(listOf("Dining and Drinking > Restaurant > Pizzeria")))
        assertEquals(FsqPoiType.CAMPGROUND, FsqPoiType.of(listOf("Travel and Transportation > Lodging > Campground")))
        assertEquals(FsqPoiType.LODGING, FsqPoiType.of(listOf("Travel and Transportation > Lodging > Hostel")))
        assertEquals(null, FsqPoiType.of(listOf("Dining and Drinking > Restaurants Guide")))
    }

    @Test fun osmNamesAndCameras() {
        assertEquals("Galp, Leiria", osmName(mapOf("brand" to "Galp", "name" to "Posto Galp X", "addr:city" to "Leiria"), OsmPoiType.FUEL))
        assertEquals("Speed camera 50 km/h", osmName(mapOf("maxspeed" to "50"), OsmPoiType.SPEED_CAMERA))
        val r = runCatching { fetchOsmPois(OsmPoiType.SPEED_CAMERA, listOf("FR"), object : Net {
            override fun get(url: String, headers: Map<String, String>, followRedirects: Boolean, timeoutMs: Int) = error("no network")
        }) }
        assertTrue(r.exceptionOrNull()!!.message!!.contains("illegal"))
    }

    @Test fun bundledIndexHasPortugal() = assertTrue("PT" in FsqIndex.bundled().countries)
}
