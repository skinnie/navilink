package osmimg

/*
 * The message a Garmin shows while it starts: Garmin/startup.txt, which the device writes itself
 * (eTrex 10/20/30, GPSMAP, Oregon, Edge...). Its template, as found on an eTrex 10 and an Edge 1040:
 * a UTF-8 BOM, CRLF lines, comments, "<display = N>" (seconds the message stays at least), and the message
 * after the last comment. Only devices that have the file are offered the editor; none is created.
 */

const val STARTUP_FILE = "Garmin/startup.txt"
/** Garmin's limit for the message. */
const val STARTUP_MAX = 256

data class StartupText(val seconds: Int, val message: String)

private val DISPLAY = Regex("<\\s*display\\s*=\\s*(\\d+)\\s*>", RegexOption.IGNORE_CASE)

private fun text(bytes: ByteArray): String {
    val s = String(bytes, Charsets.UTF_8)
    return s.removePrefix("﻿")
}

/** Reads the device's startup.txt. */
fun readStartupText(bytes: ByteArray): StartupText {
    val s = text(bytes)
    val seconds = DISPLAY.find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val end = s.lastIndexOf("-->").let { if (it < 0) DISPLAY.find(s)?.range?.last?.plus(1) ?: 0 else it + 3 }
    val msg = s.substring(end).replace("\r\n", "\n").trim('\n', '\r').trimEnd()
    return StartupText(seconds, msg)
}

/** Why [message] can't go in, or null. */
fun startupTextProblem(message: String, seconds: Int): String? = when {
    message.length > STARTUP_MAX -> "The message is ${message.length} characters; Garmin takes up to $STARTUP_MAX."
    seconds !in 0..60 -> "Show it for 0 to 60 seconds."
    message.any { it.isISOControl() && it != '\n' } -> "The message has characters that can't be shown."
    else -> null
}

/**
 * The device's file with [t] in it: Garmin's own comments are kept as they are, the display line
 * gets the new seconds, and the message follows the last comment, with the file's BOM and line ends.
 */
fun writeStartupText(original: ByteArray, t: StartupText): ByteArray {
    startupTextProblem(t.message, t.seconds)?.let { error(it) }
    val bom = original.size >= 3 && original[0] == 0xEF.toByte() && original[1] == 0xBB.toByte() && original[2] == 0xBF.toByte()
    val s = text(original)
    val nl = if (s.contains("\r\n") || s.isEmpty()) "\r\n" else "\n"
    val head0 = s.lastIndexOf("-->").let { if (it < 0) "" else s.substring(0, it + 3) }
    val head = when {
        head0.isEmpty() -> "<display = ${t.seconds}>"
        DISPLAY.containsMatchIn(head0) -> DISPLAY.replace(head0, "<display = ${t.seconds}>")
        else -> "$head0$nl<display = ${t.seconds}>"
    }
    val body = t.message.replace("\r\n", "\n").trim('\n').lines().joinToString(nl)
    val out = if (body.isEmpty()) head else head + nl + body
    return (if (bom) "﻿" else "").toByteArray(Charsets.UTF_8) + out.toByteArray(Charsets.UTF_8)
}
