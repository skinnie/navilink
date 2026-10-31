package app.navilink

import app.navilink.nuvi.Place
import app.navilink.nuvi.buildGpi
import app.navilink.nuvi.fromSemi
import app.navilink.nuvi.handleDeviceRequest
import app.navilink.nuvi.newRouteRequest
import app.navilink.nuvi.placesFromFile
import app.navilink.nuvi.placesToGpx
import app.navilink.nuvi.readMessage
import app.navilink.nuvi.request
import app.navilink.nuvi.toSemi
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtocolTest {
    @Test fun semicircles() {
        assertEquals(474159617L, toSemi(39.7436))     // Leiria
        assertTrue(abs(fromSemi(toSemi(-8.8071)) + 8.8071) < 1e-6)
    }

    @Test fun newRouteMatchesSplFraming() {
        val s = String(newRouteRequest(listOf(Place("Castelo de Leiria", 39.7473, -8.8094, city = "Leiria"))), Charsets.UTF_8)
        val body = "lat=474203760&lon=-105100235&name=Castelo+de+Leiria&locality=Leiria\r\n"
        assertEquals("POST /new-route HTTP/1.1\r\nHost: gps-device\r\nContent-Length: ${body.length}\r\n\r\n$body\r\n", s)
    }

    @Test fun accentsAreUtf8UrlEncoded() {
        val s = String(newRouteRequest(listOf(Place("Café São João", 0.0, 0.0))), Charsets.UTF_8)
        assertTrue("name=Caf%C3%A9+S%C3%A3o+Jo%C3%A3o" in s, s)
    }

    @Test fun parsesNuviRequestsAndAnswersLikeSpl() {
        val raw = request("auth-info?unit-identifier=3812345678", "category=1\r\ncategory=33", host = "device") +
            request("find-destination", "lat=474159617&lon=-105073360", host = "device")
        val input = ByteArrayInputStream("ping".toByteArray() + "\r\n".toByteArray() + raw)
        val a = readMessage(input)!!
        assertEquals("/auth-info", a.route)
        val h = handleDeviceRequest(a)
        assertEquals("HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nOK\r\n\r\n", String(h.reply))
        assertTrue("3812345678" in h.event && "1,33" in h.event, h.event)
        val f = handleDeviceRequest(readMessage(input)!!)
        assertEquals(1, f.places.size)
        assertTrue(abs(f.places[0].lat - 39.7436) < 1e-6)
    }

    @Test fun readsResponses() {
        val m = readMessage(ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nOK\r\n\r\n".toByteArray()))!!
        assertEquals(200, m.status)
        assertEquals("OK", m.body)
    }

    @Test fun gpxRoundTrip() {
        val ps = listOf(Place("A & B", 39.1, -8.2, category = "Food"), Place("C", 40.0, -9.0))
        val back = placesFromFile("x.gpx", placesToGpx(ps))
        assertEquals(listOf("A & B", "C"), back.map { it.name })
    }

    /** A GPI made by gpsbabel from our GPX (generated in the build dir when gpsbabel is installed). */
    @Test fun readsGpiFromGpsbabel() {
        val gpsbabel = listOf("/usr/bin/gpsbabel", "/usr/local/bin/gpsbabel").firstOrNull { File(it).canExecute() } ?: return
        val dir = File("build/tmp/gpitest").apply { mkdirs() }
        File(dir, "in.gpx").writeBytes(placesToGpx(listOf(Place("Castelo", 39.7473, -8.8094), Place("Sé", 39.7459, -8.8077))))
        val p = ProcessBuilder(gpsbabel, "-i", "gpx", "-f", "${dir}/in.gpx", "-o", "garmin_gpi,category=Test", "-F", "${dir}/out.gpi")
            .redirectErrorStream(true).start()
        p.waitFor()
        val back = placesFromFile("out.gpi", File(dir, "out.gpi").readBytes())
        assertEquals(setOf("Castelo", "Sé"), back.map { it.name }.toSet())
        assertTrue(back.all { it.category == "Test" })
    }

    private fun gpsbabel(): String? = listOf("/usr/bin/gpsbabel", "/usr/local/bin/gpsbabel").firstOrNull { File(it).canExecute() }

    /** Our GPI must equal gpsbabel's (icon off), apart from the timestamp. */
    @Test fun gpiMatchesGpsbabel() {
        val gb = gpsbabel() ?: return
        val dir = File("build/tmp/gpitest2").apply { mkdirs() }
        val ps = listOf(Place("Castelo", 39.7473, -8.8094, street = "Rua X 1"), Place("Sé", 39.7459, -8.8077))
        File(dir, "in.gpx").writeText("""<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="t" xmlns="http://www.topografix.com/GPX/1/1">
<wpt lat="39.7473" lon="-8.8094"><name>Castelo</name><desc>Rua X 1</desc></wpt>
<wpt lat="39.7459" lon="-8.8077"><name>Sé</name></wpt>
</gpx>""")
        ProcessBuilder(gb, "-i", "gpx", "-f", "$dir/in.gpx", "-o", "garmin_gpi,category=Test,unique=0,hide=1", "-F", "$dir/my.gpi")
            .redirectErrorStream(true).start().waitFor()
        val ref = File(dir, "my.gpi").readBytes()
        val ours = buildGpi("Test", ps, "my.gpi")
        for (i in 8..11) { ref[i + 8] = 0; ours[i + 8] = 0 } // header timestamp
        assertEquals(ref.joinToString("") { "%02x".format(it) }, ours.joinToString("") { "%02x".format(it) })
    }

    /** 1000 places: areas of at most 128, all readable back, and gpsbabel can read the file. */
    @Test fun bigGpiReadsBack() {
        val r = java.util.Random(7)
        val ps = (0 until 1000).map { Place("P$it", 37 + r.nextDouble() * 5, -9.5 + r.nextDouble() * 3) }
        val gpi = buildGpi("Big", ps)
        val back = placesFromFile("x.gpi", gpi)
        assertEquals(1000, back.size)
        assertEquals(ps.map { it.name }.toSet(), back.map { it.name }.toSet())
        val gb = gpsbabel() ?: return
        val f = File("build/tmp/big.gpi").apply { parentFile.mkdirs(); writeBytes(gpi) }
        val p = ProcessBuilder(gb, "-i", "garmin_gpi", "-f", f.path, "-o", "csv", "-F", "-").redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        assertEquals(1000, lines.count { it.contains(", P") }, lines.take(3).joinToString("\n"))
    }
}
