package osmimg

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the clean style through mkgmap's own rule engine (test resources osmimg/StyleCheck.java) on single
 * OSM objects. Needs mkgmap in ~/.cache/osmimg/tools (any real build downloads it); skipped without.
 */
class StyleTest {
    private val mkgmap = MapTools(File(defaultCache(), "tools")).mkgmap

    /** Input line ("node k=v|k=v") -> what mkgmap made of it, as StyleCheck prints it. */
    private fun check(styleOption: String, cases: List<String>): Map<String, String> {
        val tmp = createTempDirectory("nlstyle").toFile()
        try {
            val style = Styles.clean(File(tmp, "style"))
            val src = File(tmp, "StyleCheck.java")
            src.writeBytes(javaClass.getResourceAsStream("/osmimg/StyleCheck.java")!!.readBytes())
            val java = File(System.getProperty("java.home"), "bin/java").path
            val p = ProcessBuilder(java, "-cp", mkgmap.path, src.path, style.path, styleOption).start()
            p.outputStream.bufferedWriter().use { w -> cases.forEach { w.write(it); w.newLine() } }
            val err = Thread { p.errorStream.readBytes() }.apply { start() }
            val out = p.inputStream.bufferedReader().readLines()
            err.join()
            assertEquals(0, p.waitFor(), out.joinToString("\n"))
            return out.associate { it.substringBefore(" => ") to it.substringAfter(" => ") }
        } finally {
            tmp.deleteRecursively()
        }
    }

    private val pois = mapOf(
        "node amenity=fuel|brand=Galp" to "0x2f01 \"Galp\"",
        "node amenity=fuel" to "0x2f01 \"Fuel\"",
        "node amenity=atm|operator=Multibanco" to "0x2f06 \"Multibanco\"",
        "node amenity=pharmacy" to "0x2e05 \"Pharmacy\"",
        "node shop=convenience|name=Minipreço" to "0x2e06 \"Minipreço\"",
        "node shop=bakery|name=Pastelaria Sol" to "0x2a05 \"Pastelaria Sol\"",
        "node shop=supermarket|brand=Pingo Doce" to "0x2e02 \"Pingo Doce\"",
        "node amenity=restaurant|name=O Tasco|cuisine=portuguese" to "0x2a00 \"O Tasco\"",
        "node amenity=restaurant|name=Bella|cuisine=italian;pizza" to "0x2a0a \"Bella\"",
        "node amenity=restaurant|name=Mar|cuisine=seafood" to "0x2a0b \"Mar\"",
        "node amenity=restaurant|name=X|cuisine=peruvian" to "0x2a13 \"X\"",
        "node amenity=fast_food|name=M" to "0x2a07 \"M\"",
        "node amenity=cafe|name=Brasileira" to "0x2a0e \"Brasileira\"",
        "node tourism=hotel|name=H|amenity=restaurant" to "0x2b01 \"H\" | 0x2a00 \"H\"",
        "node tourism=guest_house|name=Casa" to "0x2b02 \"Casa\"",
        "node tourism=camp_site" to "0x2b03 \"Campsite\"",
        "node amenity=hospital|name=Santa Maria" to "0x3002 \"Santa Maria\"",
        "node amenity=police" to "0x3001 \"Police\"",
        "node historic=castle|name=Castelo" to "0x2c02 \"Castelo\"",
        "node tourism=viewpoint" to "0x2c04 \"Viewpoint\"",
        "node amenity=place_of_worship|name=Sé" to "0x2c0b \"Sé\"",
        "node amenity=parking|parking=underground" to "0x2f0b \"Parking\"",
        "node shop=bicycle|name=Bikes" to "0x2f13 \"Bikes\"",
        "node place=city|name=Lisboa|population=545000" to "0x300 \"Lisboa\"",
        // Clutter that stays off the map
        "node barrier=gate" to "-",
        "node amenity=bench" to "-",
        "node amenity=post_box" to "-",
        "node amenity=telephone" to "-",
        "node shop=convenience" to "-",
        "node amenity=restaurant" to "-",
        "node amenity=parking" to "-",
        "node historic=memorial" to "-",
        "node leisure=pitch|sport=soccer" to "-",
        "node highway=bus_stop|name=Paragem" to "-",
    )

    @Test fun poiCategories() {
        if (!mkgmap.isFile) return println("StyleTest skipped: no mkgmap at $mkgmap")
        val got = check("", pois.keys.toList())
        val wrong = pois.filter { (k, v) -> got[k] != v }.map { (k, v) -> "$k: wanted $v, got ${got[k]}" }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test fun transitOption() {
        if (!mkgmap.isFile) return
        val c = "node highway=bus_stop|name=Paragem"
        assertEquals("0x2f17 \"Paragem\"", check("transit", listOf(c))[c])
    }

    /** Road lines are "road <type> class=<n> speed=<n> no:<modes>". */
    private fun road(line: String?) = line!!.substringAfter("speed=").let {
        it.substringBefore(' ').toInt() to it.substringAfter("no:").split(',').filter(String::isNotEmpty).toSet()
    }

    @Test fun bikeRouting() {
        if (!mkgmap.isFile) return
        val ways = listOf(
            "way highway=cycleway", "way highway=motorway", "way highway=trunk", "way highway=trunk|bicycle=yes",
            "way highway=residential", "way highway=footway", "way highway=primary",
            "way highway=primary|cycleway:right=lane", "way highway=residential|motor_vehicle=no",
            "way highway=service|access=private",
        )
        val car = check("", ways)
        val bike = check("bike", ways)
        // Normal map: cars stay off cycleways, use motorways.
        assertTrue("car" in road(car["way highway=cycleway"]).second)
        assertTrue("car" !in road(car["way highway=motorway"]).second)
        // Bike map: "car" means bicycle.
        val (cycleSpeed, cycleNo) = road(bike["way highway=cycleway"])
        assertTrue("car" !in cycleNo)
        for (w in listOf("way highway=motorway", "way highway=trunk", "way highway=footway", "way highway=service|access=private")) {
            assertTrue("car" in road(bike[w]).second, "$w should be closed: ${bike[w]}")
        }
        for (w in listOf("way highway=trunk|bicycle=yes", "way highway=residential|motor_vehicle=no")) {
            assertTrue("car" !in road(bike[w]).second, "$w should be open: ${bike[w]}")
        }
        // Preference: cycleway > quiet street and painted lane > main road.
        val quiet = road(bike["way highway=residential"]).first
        val main = road(bike["way highway=primary"]).first
        assertTrue(cycleSpeed > quiet && quiet > main, "speeds $cycleSpeed $quiet $main")
        assertEquals(quiet, road(bike["way highway=primary|cycleway:right=lane"]).first)
    }

    @Test fun options() {
        assertEquals(emptyList(), BuildOptions("x").styleOptions)
        assertEquals(listOf("bike", "transit"), BuildOptions("x", routing = Routing.BIKE, transitStops = true).styleOptions)
        assertEquals(null, Labels.ASCII.codePage)
        assertTrue(Labels.LATIN1_MIXED_CASE.lowerCase && !Labels.LATIN1.lowerCase)
        val root = createTempDirectory("nlstyle").toFile()
        try {
            val a = Styles.clean(root)
            assertTrue(File(a, "inc/bike-routing").isFile)
            assertEquals(a, Styles.clean(root)) // same contents, same folder
        } finally {
            root.deleteRecursively()
        }
    }
}
