package app.navilink.live

import app.navilink.nuvi.Message
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

/**
 * Keeps every Bluetooth exchange with the nüvi in text files, so a session with the real unit can be studied later.
 * Text messages are written as they are; binary bodies as base64. Holds the nüvi's unit id and session keys:
 * the files stay on the phone unless the user shares them.
 */
class Capture(private val dir: File, private val maxBytes: Long = 8L shl 20) {
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
    private var file: File? = null

    @Synchronized fun exchange(link: String, m: Message, replyRaw: ByteArray, note: String) {
        val f = current()
        f.appendText(buildString {
            append("=== ${SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date())} $link $note\n")
            append(">>> request\n").append(show(m.raw)).append('\n')
            append("<<< reply\n").append(show(replyRaw)).append("\n\n")
        })
    }

    @Synchronized fun note(s: String) { current().appendText("--- ${SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date())} $s\n") }

    fun files(): List<File> = dir.listFiles { f -> f.name.startsWith("link-") }?.sortedByDescending { it.name }.orEmpty()

    private fun current(): File {
        val f = file
        if (f != null && f.length() < maxBytes) return f
        dir.mkdirs()
        files().drop(9).forEach { it.delete() }           // keep the last 10 files
        return File(dir, "link-${stamp.format(Date())}.txt").also { file = it }
    }

    companion object {
        /** Head as text; the body as text when it is printable, else base64. */
        fun show(raw: ByteArray): String {
            val split = indexOf(raw, "\r\n\r\n".toByteArray())
            val headEnd = if (split < 0) raw.size else split + 4
            val head = String(raw, 0, headEnd, Charsets.ISO_8859_1).trimEnd()
            val body = raw.copyOfRange(headEnd, raw.size)
            if (body.isEmpty()) return head
            val printable = body.all { val c = it.toInt() and 0xff; c == 9 || c == 10 || c == 13 || c >= 32 }
            return head + "\n\n" + if (printable) String(body, Charsets.UTF_8) else "[base64 ${body.size} bytes]\n" + Base64.getMimeEncoder().encodeToString(body)
        }

        private fun indexOf(a: ByteArray, p: ByteArray): Int {
            outer@ for (i in 0..a.size - p.size) { for (j in p.indices) if (a[i + j] != p[j]) continue@outer; return i }
            return -1
        }
    }
}
