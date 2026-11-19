package app.navilink

import app.navilink.core.RealNet
import app.navilink.live.Identity
import app.navilink.live.LiveRouter
import app.navilink.live.Obn
import app.navilink.live.Pb
import app.navilink.live.PbWriter
import app.navilink.live.SocketUpstream
import app.navilink.live.Weather
import app.navilink.live.WeatherSource
import app.navilink.live.first
import app.navilink.nuvi.readMessage
import app.navilink.nuvi.toSemi
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real servers; runs only with NAVILINK_NET=1 (one Garmin registration call, one Open-Meteo call). */
class LiveNetTest {
    private val on = System.getenv("NAVILINK_NET") == "1"

    @Test fun garminAnswersThroughTheRelay() {
        if (!on) return
        val id = Identity.fresh()
        val ts = System.currentTimeMillis() / 1000
        val pt = PbWriter().sint32(1, toSemi(38.7223).toInt()).sint32(2, toSemi(-9.1393).toInt())
        val auth = PbWriter()
            .msg(1, PbWriter().string(1, id.deviceId).varint(2, id.token.toLong()))
            .string(2, "2.11.12")
            .msg(3, PbWriter().string(1, id.appHash(ts)).varint(2, ts).varint(3, 0))
            .msg(7, PbWriter().string(5, "com.garmin.android.apps.phonelink"))
            .msg(9, pt)
        val txn = PbWriter().varint(2, 105).string(3, "2.11.12").msg(7, PbWriter().msg(80, PbWriter().msg(1, auth))).toByteArray()
        // Absolute-form request line, as a device talking through a proxy would send it.
        val req = "POST http://gold.garmin.com/OBN/OBNServlet HTTP/1.1\r\nHost: gold.garmin.com\r\nprotocol: proto\r\nContent-Length: ${txn.size}\r\n\r\n"
            .toByteArray() + txn
        val logs = ArrayList<String>()
        val router = LiveRouter(SocketUpstream(), RealNet(), { logs += it; println(it) })
        val raw = router.handle(readMessage(ByteArrayInputStream(req))!!)
        val status = String(raw, 0, 40, Charsets.ISO_8859_1)
        println(status.lineSequence().first())
        assertTrue(status.startsWith("HTTP/1.1 200"), status)
        assertTrue(logs.any { "app auth" in it && "OK" in it }, logs.toString())

        // Traffic around Lisbon with the session key, in Garmin's text protocol (like probe/traffic.py).
        val r = app.navilink.live.HttpReply.read(ByteArrayInputStream(raw))
        val key = Pb.parse(r.plainBody).first(3)!!.msg().first(80)!!.msg().first(1)!!.msg().first(3)!!.text
        fun line(vararg kv: Pair<String, Any>) = kv.joinToString("") { "${it.first}=${it.second}\t" } + "\n"
        val body = (line("version" to "1.1.2", "recordNum" to 1, "contentType" to "basic") + line("activityType" to "traffic",
            "olat" to toSemi(38.7223), "olon" to toSemi(-9.1393), "locale" to "pt_PT", "distance" to 20000, "useMetric" to 1, "returnLine" to 1)).toByteArray()
        val treq = ("POST /OBN/OBNServlet HTTP/1.1\r\nHost: gold.garmin.com\r\nprotocol: cld\r\ntxnKey: $key\r\nclienttypeid: 105\r\n" +
            "Accept-Encoding: gzip\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray() + body
        val traw = router.handle(readMessage(ByteArrayInputStream(treq))!!)
        val tr = app.navilink.live.HttpReply.read(ByteArrayInputStream(traw))
        val text = String(tr.plainBody, Charsets.UTF_8)
        println("traffic: HTTP ${tr.status}, ${tr.header("Content-Encoding") ?: "plain"}, ${tr.header("Transfer-Encoding") ?: "length"}; " + text.lineSequence().first())
        assertEquals(200, tr.status)
        assertTrue(text.startsWith("transactionStatus=0"), text.take(200))
    }

    @Test fun openMeteoBuildsAWeatherAnswer() {
        if (!on) return
        val a = Weather.Ask(39.7436, -8.8071, "pt", 3, 6, false, 2)
        val body = Weather.answer(RealNet(), a)
        val w = Pb.parse(body)
        val cur = w.first(11)!!.msg().first(1)!!.msg()
        println("Leiria now: ${cur.first(1)!!.text}, ${cur.first(2)!!.sint} °C, wind ${cur.first(4)!!.int} km/h ${cur.first(3)!!.text}")
        assertEquals(0, w.first(1)!!.int)
        assertTrue(w.first(16)!!.msg().filter { it.num == 1 }.size == 6)
        println(Obn.describeResponse(Obn.transactionResponse(listOf(Obn.serviceResponse(1, 30, body)), 0)))
    }
}
