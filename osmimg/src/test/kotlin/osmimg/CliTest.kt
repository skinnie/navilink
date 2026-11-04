package osmimg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliTest {
    @Test fun sizes() {
        assertEquals(NUVI_2GB_MAX_FILE, parseSize("2G"))
        assertEquals(700L * 1024 * 1024 - 1, parseSize("700M"))
        assertEquals(1610612736L, parseSize("1.5G"))
        assertEquals(104857600L, parseSize("104857600"))
    }

    /** Every POI type the clean style makes has a category, so readers can group all of them. */
    @Test fun everyStyleTypeHasACategory() {
        val known = poiCategories().map { it.type }.toSet()
        val points = javaClass.getResourceAsStream("/osmimg/style/clean/points")!!.bufferedReader().readText()
        val used = Regex("\\[(0x[0-9a-fA-F]+) ").findAll(points).map { Integer.decode(it.groupValues[1]) }.toSet()
        assertTrue(used.size > 80, "found ${used.size} types")
        val missing = used - known
        assertTrue(missing.isEmpty(), "no category for " + missing.joinToString { "0x%04x".format(it) })
    }
}
