package app.navilink.core

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class HttpResponse(val code: Int, val headers: Map<String, String>, val body: ByteArray) {
    val text get() = body.toString(Charsets.UTF_8)
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
}

/** Everything that talks to the internet goes through this, so tests can fake it. */
interface Net {
    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
        timeoutMs: Int = 15_000,
    ): HttpResponse
}

class RealNet : Net {
    override fun get(url: String, headers: Map<String, String>, followRedirects: Boolean, timeoutMs: Int): HttpResponse {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = followRedirects
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            val h = c.headerFields.filterKeys { it != null }.mapValues { it.value.lastOrNull() ?: "" }
            return HttpResponse(code, h, body)
        } finally {
            c.disconnect()
        }
    }
}

