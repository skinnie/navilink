package app.navilink

import app.navilink.core.HttpResponse
import app.navilink.core.Net
import app.navilink.live.HttpReply
import app.navilink.live.Identity
import app.navilink.live.LiveRouter
import app.navilink.live.Obn
import app.navilink.live.Pb
import app.navilink.live.PbWriter
import app.navilink.live.Upstream
import app.navilink.live.Weather
import app.navilink.live.WeatherSource
import app.navilink.live.first
import app.navilink.live.all
import app.navilink.live.relayTarget
import app.navilink.nuvi.handleDeviceRequest
import app.navilink.nuvi.readMessage
import app.navilink.nuvi.request
import app.navilink.nuvi.toSemi
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTest {
    private val id = Identity("0d4e1b43-1c8e-4f1e-9a63-3c1b2b7e2f10", "6a3f6c2e-1111-4222-8333-944455556666")

    @Test fun appHashIsSplsFormula() {
        val want = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
            .digest("561418213${id.deviceId}1700000000".toByteArray()))
        assertEquals(want, id.appHash(1700000000))
        assertEquals(Math.abs(id.tokenSeed.hashCode()), id.token)
    }

    @Test fun authInfoSendsIdentity() {
        val m = readMessage(ByteArrayInputStream(request("auth-info?unit-identifier=3812345678", "category=1", host = "device")))!!
        assertTrue(m.forPhone)
        val h = handleDeviceRequest(m) { id.authInfoText(1700000000, "14") }
        val r = readMessage(ByteArrayInputStream(h.reply))!!
        assertEquals(200, r.status)
        val first = app.navilink.nuvi.parseParams(r.body.lines()[0])
        assertEquals(id.deviceId, first["device-identifier"])
        assertEquals(id.appHash(1700000000), first["app-hash"])
        assertEquals("105", first["client-type-id"])
        assertEquals("Android14", first["device-type"])
        assertEquals("subscription-token=0", r.body.lines()[1])
        assertTrue("account-id" !in r.body)
    }

    /** A protobuf request that isn't valid UTF-8 must reach Garmin byte for byte. */
    private fun obnRequest(body: ByteArray, host: String = "gold.garmin.com"): ByteArray {
        val head = "POST http://$host/OBN/OBNServlet HTTP/1.1\r\nHost: $host\r\nprotocol: proto\r\nContent-Length: ${body.size}\r\n\r\n"
        return head.toByteArray(Charsets.ISO_8859_1) + body
    }

    private fun weatherTxn(seq: Int = 3): ByteArray {
        val pos = PbWriter().sint32(1, toSemi(39.7436).toInt()).sint32(2, toSemi(-8.8071).toInt())
        val cond = PbWriter().msg(1, pos).msg(2, PbWriter().string(1, "pt").string(2, "PT")).varint(3, 3).varint(7, 1)
        val sr = PbWriter().varint(1, seq.toLong()).msg(30, PbWriter().msg(11, cond))
        return PbWriter().string(1, "KEY").varint(2, 105).string(3, "2.11.12").msg(7, sr).toByteArray()
    }

    private val meteo = """{"timezone":"Europe/Lisbon","current":{"temperature_2m":14.6,"relative_humidity_2m":81,
        "apparent_temperature":13.2,"weather_code":61,"wind_speed_10m":17.3,"wind_direction_10m":300},
        "daily":{"time":[1763510400,1763596800,1763683200],"weather_code":[61,3,0],"temperature_2m_max":[16.1,15.2,17.9],
        "temperature_2m_min":[9.4,8.1,-1.6],"precipitation_probability_max":[85,20,0]},
        "hourly":{"time":[1763550000,1763553600],"temperature_2m":[14.6,14.1],"precipitation_probability":[80,70],
        "weather_code":[61,63],"wind_speed_10m":[17.0,18.0],"wind_direction_10m":[300,310]}}"""

    private class FakeNet(val text: String) : Net {
        var calls = 0
        override fun get(url: String, headers: Map<String, String>, followRedirects: Boolean, timeoutMs: Int): HttpResponse {
            calls++; assertTrue("open-meteo" in url && "latitude=39.7436" in url, url)
            return HttpResponse(200, emptyMap(), text.toByteArray())
        }
    }

    @Test fun relayTargetsLikeSpl() {
        val m = readMessage(ByteArrayInputStream(obnRequest(byteArrayOf(8, 1))))!!
        assertTrue(!m.forPhone)
        assertEquals(Triple("gold.garmin.com", 443, true), relayTarget(m))
        val other = readMessage(ByteArrayInputStream(obnRequest(byteArrayOf(8, 1), "example.org:8080")))!!
        assertEquals(Triple("example.org", 8080, false), relayTarget(other))
    }

    @Test fun relaysBytesAndGarminsAnswerUnchanged() {
        val body = byteArrayOf(0x0a, 0x03, 0xff.toByte(), 0xfe.toByte(), 0x80.toByte(), 0x10, 0x69)
        val req = obnRequest(body)
        val garmin = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nTransfer-Encoding: chunked\r\n\r\n3\r\n\u0008\u0000\u0010\r\n0\r\n\r\n"
            .toByteArray(Charsets.ISO_8859_1)
        var sent: ByteArray? = null
        val up = Upstream { h, p, tls, r -> assertEquals("gold.garmin.com", h); assertEquals(443, p); assertTrue(tls); sent = r; HttpReply.read(ByteArrayInputStream(garmin)) }
        val router = LiveRouter(up, FakeNet(meteo), {})
        val out = router.handle(readMessage(ByteArrayInputStream(req))!!)
        assertContentEquals(req, sent)
        assertContentEquals(garmin, out)
        assertEquals(1, router.stats.relayed)
    }

    @Test fun weatherAnsweredHereWhenAsked() {
        val net = FakeNet(meteo)
        var up = 0
        val router = LiveRouter({ _, _, _, _ -> up++; error("no") }, net, {}, now = { 1763553000000 })
        router.weather = WeatherSource.OPEN_METEO
        val raw = router.handle(readMessage(ByteArrayInputStream(obnRequest(weatherTxn(3))))!!)
        assertEquals(0, up)
        val r = HttpReply.read(ByteArrayInputStream(raw))
        assertEquals(200, r.status)
        val t = Pb.parse(r.body)
        assertEquals(0, t.first(1)!!.int)
        val sr = t.first(3)!!.msg()
        assertEquals(3, sr.first(1)!!.int)
        assertEquals(0, sr.first(2)!!.int)
        val cond = sr.first(30)!!.msg().first(11)!!.msg()
        val cur = cond.first(1)!!.msg()
        assertEquals("Chuva fraca", cur.first(1)!!.text)
        assertEquals(15, cur.first(2)!!.sint)
        assertEquals(Weather.LIGHT_RAIN, cur.first(8)!!.int)
        assertEquals("NO", cur.first(3)!!.text)
        val days = cond.all(2).map { it.msg() }
        assertEquals(3, days.size)
        assertEquals(-2, days[2].first(3)!!.sint)          // -1.6 rounds to -2
        assertEquals(Weather.CLEAR, days[2].first(5)!!.int)
        assertEquals("Open-Meteo", cond.first(4)!!.msg().first(1)!!.text)
        assertEquals("weather #3: OK", Obn.describeResponse(r.body))
    }

    @Test fun garminRefusalIsFilledFromOpenMeteo() {
        // Garmin: transaction OK, weather #3 NOT_AUTHORIZED (5), plus an untouched extra field 4.
        val garminBody = PbWriter().varint(1, 0).varint(2, 1763553000000)
            .msg(3, PbWriter().varint(1, 3).varint(2, 5).string(3, "nope")).varint(4, 77).toByteArray()
        val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(garminBody) } }.toByteArray()
        val head = "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: ${gz.size}\r\n\r\n".toByteArray()
        val net = FakeNet(meteo)
        val router = LiveRouter({ _, _, _, _ -> HttpReply.read(ByteArrayInputStream(head + gz)) }, net, {})
        val raw = router.handle(readMessage(ByteArrayInputStream(obnRequest(weatherTxn(3))))!!)
        val r = HttpReply.read(ByteArrayInputStream(raw))
        assertNull(r.header("Content-Encoding"))
        val t = Pb.parse(r.body)
        assertEquals(77, t.first(4)!!.int)
        assertEquals(1763553000000, t.first(2)!!.varint)
        assertEquals(mapOf(3 to 0), Obn.responseStatus(r.body))
        assertNotNull(t.first(3)!!.msg().first(30))
        assertEquals(1, net.calls); assertEquals(1, router.stats.local)
    }

    @Test fun garminWeatherKeptWhenItWorks() {
        val ok = PbWriter().varint(1, 0).msg(3, PbWriter().varint(1, 3).varint(2, 0).msg(30, PbWriter().varint(1, 0))).toByteArray()
        val reply = "HTTP/1.1 200 OK\r\nContent-Length: ${ok.size}\r\n\r\n".toByteArray() + ok
        val net = FakeNet(meteo)
        val router = LiveRouter({ _, _, _, _ -> HttpReply.read(ByteArrayInputStream(reply)) }, net, {})
        assertContentEquals(reply, router.handle(readMessage(ByteArrayInputStream(obnRequest(weatherTxn())))!!))
        assertEquals(0, net.calls)
    }

    @Test fun offlineGivesSplsAnswer() {
        val router = LiveRouter({ _, _, _, _ -> error("not called") }, FakeNet(meteo), {}, online = { false })
        router.weather = WeatherSource.GARMIN
        val r = String(router.handle(readMessage(ByteArrayInputStream(obnRequest(byteArrayOf(8, 1))))!!), Charsets.ISO_8859_1)
        assertTrue(r.startsWith("HTTP/1.1 598 No Mobile Data Connection"), r)
    }

    @Test fun textProtocolIsDescribedAndRelayed() {
        val body = "version=1.1.2\trecordNum=1\tcontentType=basic\t\nactivityType=traffic\tolat=1\tolon=2\t\n".toByteArray()
        val req = "POST /OBN/OBNServlet HTTP/1.1\r\nHost: gold.garmin.com\r\nprotocol: cld\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body
        val logs = ArrayList<String>()
        val reply = "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\ntransactionStatus=0\trecordNum=0\n".toByteArray()
        val router = LiveRouter({ _, _, _, r -> assertContentEquals(req, r); HttpReply.read(ByteArrayInputStream(reply)) }, FakeNet(meteo), { logs += it })
        assertContentEquals(reply, router.handle(readMessage(ByteArrayInputStream(req))!!))
        assertTrue(logs.any { "traffic" in it }, logs.toString())
    }
}
