package app.navilink.nuvi

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/*
 * The Smartphone Link Bluetooth protocol, as used by SPL 2.11.12 and nüvi 34xx firmware 8.50.
 *
 * Two RFCOMM links, both carrying small HTTP/1.1-style messages framed by Content-Length:
 *  - phone -> nüvi: the phone connects to the nüvi on [NUVI_UUID] and POSTs /new-route (places to drive to).
 *  - nüvi -> phone: the nüvi connects to the phone's "GarminLink" service ([PHONE_UUID]) and POSTs
 *    /auth-info, /find-destination, /parking-spot, /save-locations ...; the phone answers each one.
 * Coordinates travel as Garmin semicircles (degrees * 2^31 / 180). Values are URL-encoded UTF-8.
 */
val NUVI_UUID: UUID = UUID.fromString("59845525-f612-4fde-83d9-1c6c914c4272")
val PHONE_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
const val PHONE_SERVICE_NAME = "GarminLink"

fun toSemi(deg: Double): Long = (deg * (2147483648.0 / 180.0)).toLong()
fun fromSemi(semi: Long): Double = semi * (180.0 / 2147483648.0)

/** A place for the nüvi. Only name/lat/lon are required; the rest shows on the nüvi's confirm page. */
data class Place(
    val name: String,
    val lat: Double,
    val lon: Double,
    val street: String? = null,
    val postcode: String? = null,
    val city: String? = null,
    val region: String? = null,
    val country: String? = null,
    val phone: String? = null,
    val category: String? = null,
)

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/** One line of a /new-route body, in the same field order SPL uses. */
fun newRouteLine(p: Place): String {
    val f = mutableListOf("lat" to toSemi(p.lat).toString(), "lon" to toSemi(p.lon).toString())
    p.name.takeIf { it.isNotBlank() }?.let { f += "name" to it }
    p.street?.let { f += "thoroughfare" to it }
    p.postcode?.let { f += "postalCode" to it }
    p.city?.let { f += "locality" to it }
    p.region?.let { f += "adminArea" to it }
    p.country?.let { f += "country" to it }
    return f.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
}

/** POST /new-route: one line per stop; a single stop is a plain "go here". */
fun newRouteRequest(stops: List<Place>): ByteArray {
    require(stops.isNotEmpty())
    val body = stops.joinToString("") { newRouteLine(it) + "\r\n" }
    return request("new-route", body)
}

/** Request framing copied from SPL (requests/g.java): body bytes counted, then one extra CRLF after the body. */
fun request(path: String, body: String?, host: String = "gps-device"): ByteArray {
    val b = body?.toByteArray(Charsets.UTF_8)
    val sb = StringBuilder("POST /${path.trimStart('/')} HTTP/1.1\r\nHost: $host\r\n")
    if (b != null && b.isNotEmpty()) sb.append("Content-Length: ${b.size}\r\n")
    sb.append("\r\n")
    val out = ByteArrayOutputStream()
    out.write(sb.toString().toByteArray(Charsets.UTF_8))
    if (b != null) { out.write(b); out.write("\r\n".toByteArray()) }
    return out.toByteArray()
}

private val REASONS = mapOf(200 to "OK", 400 to "Bad Request", 404 to "Not Found", 501 to "Not implemented")

/** Response framing copied from SPL (handlers/h.java), including its odd Content-Length (text + 4). */
fun response(code: Int, text: String): ByteArray =
    ("HTTP/1.1 $code ${REASONS[code] ?: "Unknown"}\r\nContent-Length: ${text.length + 4}\r\n\r\n$text\r\n\r\n")
        .toByteArray(Charsets.ISO_8859_1)

/** A request or response read off the link. For a request, [path] holds the path with its query string. */
class Message(val startLine: String, val headers: Map<String, String>, val body: String) {
    val isResponse get() = startLine.startsWith("HTTP", ignoreCase = true)
    val status: Int? get() = if (isResponse) startLine.split(' ').getOrNull(1)?.toIntOrNull() else null
    val path: String get() = startLine.split(' ').getOrNull(1) ?: ""
    val route: String get() = path.substringBefore('?')
    val query: Map<String, String> get() = parseParams(path.substringAfter('?', ""))
    /** Body lines as key=value maps (the format of every SPL body except GPX). */
    val bodyRecords: List<Map<String, String>> get() = body.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { parseParams(it) }
    override fun toString() = buildString {
        append(startLine)
        headers.forEach { (k, v) -> append("\n$k: $v") }
        if (body.isNotEmpty()) append("\n\n").append(body)
    }
}

fun parseParams(s: String): Map<String, String> = s.split('&').filter { '=' in it }.associate {
    val k = it.substringBefore('='); val v = it.substringAfter('=')
    k to runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
}

/**
 * Read one message: start line, headers to the blank line, then Content-Length bytes.
 * Returns null at end of stream. Bare lines before a start line (such as "ping") are skipped.
 */
fun readMessage(input: InputStream): Message? {
    var start: String
    do { start = readLine(input) ?: return null } while (!looksLikeStart(start))
    val headers = LinkedHashMap<String, String>()
    while (true) {
        val l = readLine(input) ?: break
        if (l.isEmpty()) break
        val i = l.indexOf(':')
        if (i > 0) headers[l.substring(0, i).trim()] = l.substring(i + 1).trim()
    }
    val len = headers.entries.firstOrNull { it.key.equals("Content-Length", true) }?.value?.toIntOrNull() ?: 0
    val buf = ByteArray(len)
    var n = 0
    while (n < len) {
        val r = input.read(buf, n, len - n)
        if (r < 0) break
        n += r
    }
    return Message(start, headers, String(buf, 0, n, Charsets.UTF_8).trim())
}

private fun looksLikeStart(l: String) =
    l.startsWith("HTTP/", true) || Regex("^(GET|POST|PUT|DELETE) \\S+").containsMatchIn(l)

private fun readLine(input: InputStream): String? {
    val b = ByteArrayOutputStream()
    while (true) {
        val c = input.read()
        if (c < 0) return if (b.size() == 0) null else b.toString(Charsets.UTF_8.name())
        if (c == '\n'.code) break
        if (c != '\r'.code) b.write(c)
    }
    return b.toString(Charsets.UTF_8.name())
}

/** A place the nüvi sent us (/find-destination, /parking-spot or one entry of /save-locations). */
fun placeFromRecord(r: Map<String, String>, fallbackName: String): Place? {
    fun coord(k: String) = r[k]?.let { v -> v.toLongOrNull()?.let { fromSemi(it) } ?: v.toDoubleOrNull() }
    val lat = coord("lat") ?: return null
    val lon = coord("lon") ?: return null
    return Place(
        name = r["name"]?.takeIf { it.isNotBlank() } ?: fallbackName, lat = lat, lon = lon,
        street = r["addr"] ?: r["thoroughfare"], phone = r["phone"], category = r["cat"],
    )
}

/** What the nüvi asked or told us, and the answer we give, mirroring SPL's handlers. */
class Handled(val reply: ByteArray, val event: String, val places: List<Place> = emptyList())

fun handleDeviceRequest(m: Message): Handled = when (m.route) {
    // SPL answers "OK" when no Garmin account is stored, which is our case.
    "/auth-info" -> {
        val unit = m.query["unit-identifier"]
        if (unit == null) Handled(response(400, "Missing unit-id parameter"), "auth-info without unit id")
        else Handled(response(200, "OK"), "nüvi unit $unit said hello; services: " +
            m.bodyRecords.mapNotNull { it["category"] }.joinToString(","))
    }
    "/find-destination" -> places(m, "nüvi destination")
    "/parking-spot" -> places(m, "Parking spot")
    "/save-locations" -> places(m, "Saved place")
    else -> Handled(response(501, "Not implemented"), "unhandled ${m.route}")
}

private fun places(m: Message, label: String): Handled {
    val ps = m.bodyRecords.mapIndexedNotNull { i, r -> placeFromRecord(r, if (i == 0) label else "$label ${i + 1}") }
    return if (ps.isEmpty()) Handled(response(400, "Missing POST content"), "${m.route} without places")
    else Handled(response(200, "OK"), "${m.route}: ${ps.size} place(s)", ps)
}
