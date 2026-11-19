package app.navilink.live

import app.navilink.nuvi.Message
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLSocketFactory

/** An HTTP response as received: [raw] is forwarded to the nüvi unchanged; [body] is decoded for inspection. */
class HttpReply(val status: Int, val headers: List<Pair<String, String>>, val raw: ByteArray, val body: ByteArray) {
    fun header(n: String) = headers.firstOrNull { it.first.equals(n, true) }?.second
    /** The body with gzip undone, for reading only. */
    val plainBody: ByteArray get() =
        if (header("Content-Encoding")?.contains("gzip", true) == true) runCatching { GZIPInputStream(body.inputStream()).readBytes() }.getOrDefault(body) else body

    companion object {
        /** Read one response: Content-Length, chunked, or until the server closes. Keeps the exact bytes. */
        fun read(input: InputStream): HttpReply {
            val raw = ByteArrayOutputStream()
            fun line(): String? {
                val b = ByteArrayOutputStream()
                while (true) {
                    val c = input.read()
                    if (c < 0) return if (b.size() == 0) null else b.toString(Charsets.ISO_8859_1.name())
                    raw.write(c)
                    if (c == '\n'.code) break
                    if (c != '\r'.code) b.write(c)
                }
                return b.toString(Charsets.ISO_8859_1.name())
            }
            fun exact(n: Int, into: ByteArrayOutputStream) {
                val buf = ByteArray(8192); var left = n
                while (left > 0) {
                    val r = input.read(buf, 0, minOf(buf.size, left))
                    if (r < 0) error("connection closed in the body")
                    raw.write(buf, 0, r); into.write(buf, 0, r); left -= r
                }
            }
            val start = line() ?: error("no answer")
            val status = start.split(' ').getOrNull(1)?.toIntOrNull() ?: error("bad status line: $start")
            val headers = ArrayList<Pair<String, String>>()
            while (true) {
                val l = line() ?: break
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) headers += l.substring(0, i).trim() to l.substring(i + 1).trim()
            }
            val body = ByteArrayOutputStream()
            val te = headers.firstOrNull { it.first.equals("Transfer-Encoding", true) }?.second
            val cl = headers.firstOrNull { it.first.equals("Content-Length", true) }?.second?.toIntOrNull()
            when {
                status in 100..199 || status == 204 || status == 304 -> {}
                te?.contains("chunked", true) == true -> while (true) {
                    val size = (line() ?: error("closed in chunk header")).substringBefore(';').trim().toInt(16)
                    if (size == 0) { while (true) { val t = line() ?: break; if (t.isEmpty()) break }; break }
                    exact(size, body)
                    line()
                }
                cl != null -> exact(cl, body)
                else -> { val buf = ByteArray(8192); while (true) { val r = input.read(buf); if (r < 0) break; raw.write(buf, 0, r); body.write(buf, 0, r) } }
            }
            return HttpReply(status, headers, raw.toByteArray(), body.toByteArray())
        }

        /** A reply of our own, framed like Garmin's server. */
        fun of(status: Int, reason: String, body: ByteArray, contentType: String = "application/octet-stream"): HttpReply {
            val head = "HTTP/1.1 $status $reason\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\n\r\n"
            return HttpReply(status, listOf("Content-Type" to contentType, "Content-Length" to body.size.toString()),
                head.toByteArray(Charsets.ISO_8859_1) + body, body)
        }

        /** SPL's own error answers (api/btlink/request/c.java). */
        fun noData() = text("HTTP/1.1 598 No Mobile Data Connection\r\nContent Type: text/plain\r\n\r\nError connecting to the server: No mobile data connection\r\n", 598)
        fun serverError(e: Throwable) = text("HTTP/1.1 500 Internal Server Error\r\nContent Type: text/plain\r\n\r\nError connecting to the server:\r\n$e\r\n\r\n", 500)
        private fun text(s: String, code: Int) = s.toByteArray(Charsets.ISO_8859_1).let { HttpReply(code, emptyList(), it, ByteArray(0)) }
    }
}

/** Where relayed requests go. Faked in tests. */
fun interface Upstream {
    fun exchange(host: String, port: Int, tls: Boolean, request: ByteArray): HttpReply
}

/** Plain sockets, like SPL: the nüvi's request bytes go out unchanged; TLS for *.garmin.com on 443. */
class SocketUpstream(private val timeoutMs: Int = 20_000) : Upstream {
    override fun exchange(host: String, port: Int, tls: Boolean, request: ByteArray): HttpReply {
        val s: Socket = if (tls) SSLSocketFactory.getDefault().createSocket() else Socket()
        s.use {
            it.connect(InetSocketAddress(host, port), timeoutMs)
            it.soTimeout = timeoutMs
            it.getOutputStream().apply { write(request); flush() }
            return HttpReply.read(it.getInputStream().buffered())
        }
    }
}

/** Host and port a relayed request goes to, the way SPL picks them (443 + TLS for garmin.com hosts). */
fun relayTarget(m: Message): Triple<String, Int, Boolean> {
    var host = m.host
    var port = 80
    val i = host.indexOf(':')
    if (i > 0) { port = host.substring(i + 1).toIntOrNull() ?: 80; host = host.substring(0, i) }
    if (host.endsWith("garmin.com", true)) port = 443
    return Triple(host, port, port == 443)
}
