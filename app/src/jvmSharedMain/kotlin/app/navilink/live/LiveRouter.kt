package app.navilink.live

import app.navilink.core.Net
import app.navilink.nuvi.Message

/** Where weather comes from. */
enum class WeatherSource(val label: String) {
    GARMIN("Garmin"),
    GARMIN_THEN_OPEN_METEO("Garmin, Open-Meteo if Garmin can't"),
    OPEN_METEO("Open-Meteo"),
}

/** Counters for the status line and notification. */
class LiveStats {
    @Volatile var relayed = 0
    @Volatile var local = 0
    @Volatile var failed = 0
    @Volatile var last: String = ""
    fun summary() = buildString {
        append("$relayed relayed")
        if (local > 0) append(", $local answered here")
        if (failed > 0) append(", $failed failed")
    }
}

/**
 * Answers a nüvi request meant for the internet (Host is not "device"):
 *  - relays it to Garmin like Smartphone Link does (the nüvi signs it with the identity from /auth-info);
 *  - for protobuf weather requests, answers from Open-Meteo instead, or when Garmin refuses, per [weather].
 * [record] gets every exchange (request, reply, note) for the capture file.
 */
class LiveRouter(
    private val upstream: Upstream,
    private val net: Net,
    private val log: (String) -> Unit,
    private val online: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
    private val record: (Message, HttpReply, String) -> Unit = { _, _, _ -> },
) {
    @Volatile var weather = WeatherSource.GARMIN_THEN_OPEN_METEO
    val stats = LiveStats()

    fun handle(m: Message): ByteArray {
        val (host, port, tls) = relayTarget(m)
        val proto = Pb.looksLikeMessage(m.bodyBytes) && m.header("protocol")?.equals("cld", true) != true
        val what = if (proto) Obn.describeRequest(m.bodyBytes) else textKind(m)
        log("Live → $host: $what")
        val weatherAsk = if (proto) localWeatherPlan(m.bodyBytes) else null

        if (weatherAsk != null && weather == WeatherSource.OPEN_METEO) {
            local(m, weatherAsk, "Open-Meteo (setting)")?.let { return it }
        }
        if (!online()) {
            stats.failed++
            return HttpReply.noData().also { record(m, it, "offline"); log("Live: no mobile data") }.raw
        }
        val reply = try {
            upstream.exchange(host, port, tls, m.raw)
        } catch (e: Exception) {
            if (weatherAsk != null && weather != WeatherSource.GARMIN) local(m, weatherAsk, "Open-Meteo (Garmin unreachable)")?.let { return it }
            stats.failed++
            log("Live: $host failed: ${e.message}")
            return HttpReply.serverError(e).also { record(m, it, "error ${e.message}") }.raw
        }
        val plain = reply.plainBody
        val answer = if (proto && reply.status == 200) Obn.describeResponse(plain) else "HTTP ${reply.status}, ${reply.body.size} bytes"
        if (weatherAsk != null && weather == WeatherSource.GARMIN_THEN_OPEN_METEO) {
            val st = if (reply.status == 200) Obn.responseStatus(plain) else emptyMap()
            val bad = weatherAsk.seqs.filter { (st[it] ?: -1) != 0 }
            if (bad.isNotEmpty()) {
                val fixed = runCatching {
                    val body = Weather.answer(net, weatherAsk.ask)
                    val repl = bad.associateWith { Obn.serviceResponse(it, Obn.WEATHER, body) }
                    val out = if (reply.status == 200) Obn.spliceResponse(plain, repl) else Obn.transactionResponse(repl.values.toList(), now())
                    HttpReply.of(200, "OK", out)
                }.getOrNull()
                if (fixed != null) {
                    stats.local++; stats.last = "weather from Open-Meteo"
                    log("Live ← Garmin said: $answer; weather filled in from Open-Meteo")
                    record(m, fixed, "garmin: $answer; weather replaced")
                    return fixed.raw
                }
            }
        }
        stats.relayed++
        stats.last = what
        log("Live ← $answer")
        record(m, reply, answer)
        return reply.raw
    }

    class WeatherPlan(val ask: Weather.Ask, val seqs: List<Int>)

    /** A transaction we can answer here: every service in it is a weather request for conditions/hourly. */
    private fun localWeatherPlan(txn: ByteArray): WeatherPlan? = runCatching {
        val rs = Obn.requests(txn)
        if (rs.isEmpty() || rs.any { it.service != Obn.WEATHER }) return null
        val ask = rs.map { Weather.parseRequest(it.body) ?: return null }.first()
        WeatherPlan(ask, rs.map { it.seq })
    }.getOrNull()

    private fun local(m: Message, plan: WeatherPlan, why: String): ByteArray? = runCatching {
        val body = Weather.answer(net, plan.ask)
        val out = Obn.transactionResponse(plan.seqs.map { Obn.serviceResponse(it, Obn.WEATHER, body) }, now())
        HttpReply.of(200, "OK", out)
    }.onFailure { log("Live: Open-Meteo failed: ${it.message}") }.getOrNull()?.let {
        stats.local++; stats.last = "weather from Open-Meteo"
        log("Live ← weather from $why")
        record(m, it, why)
        it.raw
    }

    /** Garmin's text protocol: the activityType line says what it is (traffic, weather ...). */
    private fun textKind(m: Message): String {
        val t = runCatching { String(m.bodyBytes, Charsets.UTF_8) }.getOrDefault("")
        val kind = Regex("activityType=([^\\t\\r\\n]+)").find(t)?.groupValues?.get(1)
        return (kind ?: m.route.ifEmpty { m.path }) + " (${m.bodyBytes.size} bytes)"
    }
}
