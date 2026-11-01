package app.navilink

import app.navilink.core.readGpx
import app.navilink.nuvi.CableFile
import app.navilink.nuvi.Place
import app.navilink.nuvi.Route
import app.navilink.nuvi.readCableGpx
import app.navilink.nuvi.routeName
import app.navilink.nuvi.writeCableGpx
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CableGpxTest {
    private val castelo = Place("Castelo de Leiria", 39.7473, -8.8094, street = "Rua do Castelo", postcode = "2400-235",
        city = "Leiria", region = "Leiria", country = "Portugal", phone = "+351 244 839 670", category = "Castelos")
    private val batalha = Place("Mosteiro da Batalha", 39.6589, -8.8255)
    private val obidos = Place("Rua Direita 100 & Co <Óbidos>", 39.3606, -9.1571, city = "Óbidos")

    @Test fun roundTripKeepsAddressPhoneAndCategory() {
        val f = CableFile(listOf(castelo, obidos), listOf(Route("Leiria - Óbidos", listOf(castelo, batalha, obidos))))
        val back = readCableGpx(writeCableGpx(f))
        assertEquals(f.places, back.places)
        assertEquals("Leiria - Óbidos", back.routes.single().name)
        assertEquals(listOf(castelo.name, batalha.name, obidos.name), back.routes.single().stops.map { it.name })
        // The plain reader sees the same waypoints and the route line.
        val g = readGpx(writeCableGpx(f))
        assertEquals(2, g.waypoints.size)
        assertEquals(3, g.line.size)
    }

    @Test fun garminLayout() {
        val s = String(writeCableGpx(CableFile(listOf(castelo))), Charsets.UTF_8)
        assertTrue("xmlns:gpxx=\"http://www.garmin.com/xmlschemas/GpxExtensions/v3\"" in s)
        // GpxExtensionsv3.xsd order: Categories, Address (Street, City, State, Country, PostalCode), PhoneNumber.
        val order = listOf("<gpxx:Categories>", "<gpxx:StreetAddress>", "<gpxx:City>", "<gpxx:State>", "<gpxx:Country>",
            "<gpxx:PostalCode>", "<gpxx:PhoneNumber>").map { s.indexOf(it) }
        assertTrue(order.all { it > 0 } && order == order.sorted(), s)
        // A place with only a name has no empty extensions block.
        assertTrue("<extensions>" !in String(writeCableGpx(CableFile(listOf(batalha))), Charsets.UTF_8))
    }

    @Test fun addSkipsDuplicatesAndReplacesSameRoute() {
        var f = CableFile().add(listOf(castelo))
        f = f.add(listOf(castelo.copy(lat = castelo.lat + 1e-7), batalha))
        assertEquals(listOf(castelo, batalha), f.places)
        f = f.add(emptyList(), Route("Trip", listOf(castelo, batalha)))
        f = f.add(emptyList(), Route("Trip", listOf(castelo, obidos)))
        f = f.add(emptyList(), Route("Other", listOf(batalha, obidos)))
        assertEquals(listOf("Trip", "Other"), f.routes.map { it.name })
        assertEquals(obidos, f.routes.first().stops.last())
    }

    @Test fun routeNames() {
        assertEquals("Castelo de Leiria - Mosteiro da Batalha", routeName(listOf(castelo, obidos, batalha)))
        assertTrue(routeName(listOf(Place("A".repeat(40), 0.0, 0.0), Place("B".repeat(40), 0.0, 0.0))).length <= 50)
    }

    /** gpsbabel (which checks GPX strictly enough) reads the waypoints and the route. */
    @Test fun gpsbabelReadsIt() {
        val gb = listOf("/usr/bin/gpsbabel", "/usr/local/bin/gpsbabel").firstOrNull { File(it).canExecute() } ?: return
        val dir = File("build/tmp/cable").apply { mkdirs() }
        val f = File(dir, "NaviLink.gpx")
        f.writeBytes(writeCableGpx(CableFile(listOf(castelo, obidos), listOf(Route("Leiria - Óbidos", listOf(castelo, batalha, obidos))))))
        fun run(vararg a: String) = ProcessBuilder(gb, *a).redirectErrorStream(true).start().let { p ->
            p.inputStream.bufferedReader().readText().also { assertEquals(0, p.waitFor(), it) }
        }
        val out = run("-w", "-r", "-i", "gpx", "-f", f.path, "-o", "gpx", "-F", "-")
        assertEquals(2, Regex("<wpt ").findAll(out).count(), out)
        assertEquals(3, Regex("<rtept ").findAll(out).count(), out)
        assertTrue("Rua Direita 100 &amp; Co &lt;Óbidos&gt;" in out && "Mosteiro da Batalha" in out, out)
    }
}
