package osmimg

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

/*
 * Software from Garmin for a mass-storage device: firmware, voices, vehicles, language files, fonts, the
 * time-zone and base maps. Asked of WebUpdater's service first (part numbers only, no unit ID); only when
 * that has nothing for the device, of Garmin Express's service, with a made-up unit ID.
 * Licensed maps and paid safety cameras are never asked for or offered.
 */

enum class UpdateKind(val label: String) {
    FIRMWARE("Device software"),
    VOICE("Voices"),
    VOICE_COMMAND("Voice command"),
    VEHICLE("Vehicles"),
    LANGUAGE("Languages"),
    FONT("Fonts"),
    MAP("Time zones and base map"),
    OTHER("Help and other files"),
}

/**
 * One piece of software Garmin offers for the device. Versions are ×100 (3.60 = 360).
 * [installedVersion] is what the device has (null: not on it; 0: on it, version unknown).
 */
data class GarminUpdate(
    val partNumber: String,
    val name: String,
    val kind: UpdateKind,
    val version: Int,
    val path: String,
    val fileName: String,
    val url: String,
    val md5: String?,
    val bytes: Long?,
    val notes: String?,
    val installedVersion: Int?,
) {
    val target: String get() = if (path.isEmpty()) fileName else "$path/$fileName"
    val installed: Boolean get() = installedVersion != null
    /** Newer than the one on the device. */
    val isUpdate: Boolean get() = installedVersion != null && installedVersion > 0 && version > installedVersion
    val versionText: String get() = versionText(version)
}

fun versionText(v: Int) = "%d.%02d".format(v / 100, v % 100)

data class UpdateCheck(val items: List<GarminUpdate>, val fromExpress: Boolean, val licence: String?)

/** Posts [body] to [url] and returns the answer; tests put a fake in. */
fun interface UpdateHttp {
    fun post(url: String, contentType: String, body: ByteArray): String
}

val realUpdateHttp = UpdateHttp { url, type, body ->
    val c = URI(url).toURL().openConnection() as HttpURLConnection
    c.requestMethod = "POST"; c.doOutput = true
    c.connectTimeout = 20_000; c.readTimeout = 60_000
    c.setRequestProperty("Content-Type", type)
    c.setRequestProperty("Accept", if (type.contains("json")) "application/json" else "*/*")
    c.setRequestProperty("User-Agent", "NaviLink")
    c.outputStream.use { it.write(body) }
    val code = c.responseCode
    val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
    check(code == 200) { "Garmin's update service answered $code" }
    text
}

const val WEBUPDATER_URL = "https://www.garmin.com/support/WUSoftwareUpdate.jsp"
const val EXPRESS_UPDATES_URL = "https://omt.garmin.com/api/device-software/v1/updates"

/** Files that are licensed maps or paid content: never asked for, never offered. */
fun isNotOffered(fileName: String, partNumber: String): Boolean {
    val f = fileName.lowercase()
    return f.startsWith("gmapprom") || f.startsWith("gmapsupp") || f.startsWith("gmap3d") || f.startsWith("gmapdem") ||
        f.contains("cyclops") || f.endsWith(".gma") || partNumber.contains("XXXX")
}

fun updateKind(fileName: String, path: String, name: String = ""): UpdateKind {
    val f = fileName.lowercase()
    return when {
        name.contains("font", true) -> UpdateKind.FONT
        f.endsWith(".gcd") || path.contains("RemoteSW", true) || path.contains("ExtData", true) -> UpdateKind.FIRMWARE
        f.endsWith(".vpm") -> UpdateKind.VOICE
        f.endsWith(".srd") || f.endsWith(".asr") -> UpdateKind.VOICE_COMMAND
        f.endsWith(".srf") -> UpdateKind.VEHICLE
        f.endsWith(".gtt") -> UpdateKind.LANGUAGE
        f.endsWith(".img") -> UpdateKind.MAP
        else -> UpdateKind.OTHER
    }
}

/** "006-B1304-00" as the model's own firmware. */
private fun DeviceXml.firmwareEntry(): UpdateFileEntry? {
    if (partNumber.isEmpty()) return null
    updateFiles.firstOrNull { it.partNumber == partNumber }?.let { return it }
    val v = softwareVersion.toIntOrNull() ?: 0
    return UpdateFileEntry(partNumber, v / 100, v % 100, "Garmin", "GUPDATE.GCD")
}

/** What the device has of [e]: firmware by its version number, other files when the file is there. */
private fun installedOf(e: UpdateFileEntry, xml: DeviceXml, fs: DriveFs?): Int? {
    if (e.partNumber == xml.partNumber) return xml.softwareVersion.toIntOrNull() ?: e.version
    if (updateKind(e.fileName, e.path) == UpdateKind.FIRMWARE) return e.version.takeIf { it > 0 }
    val there = fs?.length(fs.spelling(if (e.path.isEmpty()) e.fileName else "${e.path}/${e.fileName}")) != null
    return if (there) e.version else null
}

private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** The request WebUpdater sends: part numbers and versions only. */
fun webUpdaterRequest(entries: List<UpdateFileEntry>): String = buildString {
    append("""<?xml version="1.0" encoding="UTF-8"?><Requests xmlns="http://www.garmin.com/xmlschemas/UnitSoftwareUpdate/v3">""")
    for (e in entries) {
        append("<Request><PartNumber>${xmlEscape(e.partNumber)}</PartNumber><TransferType>USB</TransferType><Region><RegionId>14</RegionId>")
        append("<Version><VersionMajor>${e.major}</VersionMajor><VersionMinor>${e.minor}</VersionMinor><BuildType>Release</BuildType></Version></Region></Request>")
    }
    append("</Requests>")
}

private fun org.w3c.dom.Element.kids(name: String): List<org.w3c.dom.Element> {
    val out = mutableListOf<org.w3c.dom.Element>()
    var n = firstChild
    while (n != null) { if (n is org.w3c.dom.Element && (n.localName ?: n.nodeName) == name) out += n; n = n.nextSibling }
    return out
}
private fun org.w3c.dom.Element.kid(name: String) = kids(name).firstOrNull()
private fun org.w3c.dom.Element.txt(name: String) = kid(name)?.textContent?.trim()?.ifEmpty { null }

/** Garmin's notes come URL-encoded HTML; plain lines for people. */
fun plainNotes(encoded: String?): String? = encoded?.let {
    runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
        .replace(Regex("<li>"), "\n• ").replace(Regex("<br\\s*/?>|</p>|<strong>"), "\n").replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").lines().map { l -> l.trim() }.filter { l -> l.isNotEmpty() }.joinToString("\n").ifEmpty { null }
}

/** Reads WebUpdater's answer. Returns (part number -> offer) and the licence link. */
fun parseWebUpdaterAnswer(text: String): Pair<Map<String, WebOffer>, String?> {
    val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    runCatching { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
    val root = f.newDocumentBuilder().parse(text.byteInputStream()).documentElement
    var licence: String? = null
    val out = mutableMapOf<String, WebOffer>()
    for (r in root.kids("Response")) {
        val pn = r.txt("RequestedPartNumber") ?: continue
        licence = licence ?: r.txt("LicenseLocation")
        val u = r.kid("Update") ?: continue
        val v = u.kid("Version")
        val file = u.kid("UpdateFile") ?: continue
        val url = file.txt("Location") ?: continue
        out[pn] = WebOffer(u.txt("Description") ?: pn, (v?.txt("VersionMajor")?.toIntOrNull() ?: 0) * 100 + (v?.txt("VersionMinor")?.toIntOrNull() ?: 0),
            url.replaceFirst("http://", "https://"), file.txt("MD5Sum"), file.txt("Size")?.toLongOrNull()?.let { it * 1024 },
            listOfNotNull(plainNotes(u.txt("Notes")), plainNotes(u.txt("ChangeDescription"))).joinToString("\n").ifEmpty { null })
    }
    return out to licence
}

/** One answer of WebUpdater's service. [bytes] is approximate (it gives kilobytes). */
data class WebOffer(val name: String, val version: Int, val url: String, val md5: String?, val bytes: Long?, val notes: String?)

/** Asks WebUpdater's service about everything the device lists. Nothing that identifies the unit is sent. */
fun askWebUpdater(xml: DeviceXml, fs: DriveFs?, http: UpdateHttp = realUpdateHttp): UpdateCheck {
    val entries = (listOfNotNull(xml.firmwareEntry()) + xml.updateFiles).distinctBy { it.partNumber }
        .filter { !isNotOffered(it.fileName, it.partNumber) }
    val offers = mutableMapOf<String, WebOffer>()
    var licence: String? = null
    for (chunk in entries.chunked(40)) {
        val body = "req=" + java.net.URLEncoder.encode(webUpdaterRequest(chunk), "UTF-8")
        val (o, l) = parseWebUpdaterAnswer(http.post(WEBUPDATER_URL, "application/x-www-form-urlencoded", body.toByteArray()))
        offers += o; licence = licence ?: l
    }
    val items = entries.mapNotNull { e ->
        val o = offers[e.partNumber] ?: return@mapNotNull null
        GarminUpdate(e.partNumber, o.name, updateKind(e.fileName, e.path, o.name), o.version, e.path, e.fileName, o.url, o.md5, o.bytes, o.notes,
            installedOf(e, xml, fs))
    }
    return UpdateCheck(items, false, licence)
}

/** GarminDevice.xml for Express's service: the unit ID replaced by 1 and the other per-unit numbers left out. */
fun anonymousDeviceXml(raw: String): String =
    raw.replace(Regex("<Id>\\s*\\d+\\s*</Id>"), "<Id>1</Id>").replace(Regex("<Ifix>[^<]*</Ifix>"), "")

private fun jsonString(s: String) = buildString {
    append('"')
    for (c in s) when {
        c == '"' -> append("\\\""); c == '\\' -> append("\\\\"); c == '\n' -> append("\\n"); c == '\r' -> append("\\r"); c == '\t' -> append("\\t")
        c < ' ' -> append("\\u%04x".format(c.code)); else -> append(c)
    }
    append('"')
}

private fun ver(s: String?): Int? {
    val p = s?.split('.') ?: return null
    return (p.getOrNull(0)?.toIntOrNull() ?: return null) * 100 + (p.getOrNull(1)?.take(2)?.toIntOrNull() ?: 0)
}

/** Asks Garmin Express's service; it gets the device's GarminDevice.xml with a made-up unit ID. */
fun askExpress(rawXml: String, xml: DeviceXml, fs: DriveFs?, http: UpdateHttp = realUpdateHttp): UpdateCheck {
    val body = """{"garminDeviceXml":${jsonString(anonymousDeviceXml(rawXml))},"isUserInteractive":true,"clientInfo":{}}"""
    @Suppress("UNCHECKED_CAST")
    val root = Json.parse(http.post(EXPRESS_UPDATES_URL, "application/json", body.toByteArray())) as? Map<String, Any?> ?: error("unexpected answer")
    val known = xml.updateFiles.associateBy { it.partNumber }
    @Suppress("UNCHECKED_CAST")
    val items = (root["updates"] as? List<Map<String, Any?>>).orEmpty().mapNotNull { u ->
        val d = u["deliverable"] as? Map<String, Any?> ?: return@mapNotNull null
        val inst = u["installation"] as? Map<String, Any?> ?: return@mapNotNull null
        val pn = u["partNumber"] as? String ?: return@mapNotNull null
        val fileName = inst["fileName"] as? String ?: return@mapNotNull null
        val path = (inst["path"] as? String ?: "").trim('/')
        if (isNotOffered(fileName, pn) || inst["isDelta"] == true) return@mapNotNull null
        val name = u["productName"] as? String ?: pn
        val e = known[pn] ?: UpdateFileEntry(pn, 0, 0, path, fileName)
        val installed = if (u["isPrimaryFirmware"] == true) xml.softwareVersion.toIntOrNull()
            else if (inst["isProductInstalled"] == true) installedOf(e, xml, fs) ?: e.version else null
        @Suppress("UNCHECKED_CAST")
        val notes = ((u["releaseNotes"] as? List<Map<String, Any?>>)?.firstOrNull()?.get("changeLog") as? List<String>)?.joinToString("\n") { "• $it" }
        GarminUpdate(pn, name, if (u["isPrimaryFirmware"] == true) UpdateKind.FIRMWARE else updateKind(fileName, path, name),
            ver(u["version"] as? String) ?: 0, path, fileName, (d["downloadUrl"] as? String) ?: return@mapNotNull null,
            d["md5"] as? String, (d["sizeInBytes"] as? Number)?.toLong(), notes, installed)
    }
    return UpdateCheck(items, true, null)
}

/** WebUpdater's service first; Express's only when that has nothing for this device. */
fun checkGarminUpdates(rawXml: String, xml: DeviceXml, fs: DriveFs?, http: UpdateHttp = realUpdateHttp, log: (String) -> Unit = {}): UpdateCheck {
    val web = runCatching { askWebUpdater(xml, fs, http) }.onFailure { log("WebUpdater's service: ${it.message}") }.getOrNull()
    if (web != null && web.items.isNotEmpty()) return web
    log("WebUpdater's service has nothing for the ${xml.model}; asking Garmin Express's service.")
    return askExpress(rawXml, xml, fs, http)
}

/**
 * What goes on the device from a downloaded file: Garmin wraps voices, vehicles and help files in an RGN container
 * ("KpGr", then records of u32 length + type; an 'R' record is u16 region, u32 delay, u32 size, data).
 * Exactly one 'R' record is taken out; anything else is refused rather than guessed.
 */
fun unwrapRgn(data: ByteArray): ByteArray {
    if (data.size < 6 || String(data, 0, 4, Charsets.ISO_8859_1) != "KpGr") {
        check(!(data.size > 2 && data[0] == 'M'.code.toByte() && data[1] == 'Z'.code.toByte())) { "it is a Windows program, not a file for the device" }
        return data
    }
    var p = 6
    val regions = mutableListOf<ByteArray>()
    while (p + 5 <= data.size) {
        val len = le32(data, p).toLong() and 0xffffffffL
        val type = data[p + 4].toInt().toChar()
        val start = p + 5
        check(start + len <= data.size) { "the download is cut short" }
        if (type == 'R') {
            val size = le32(data, start + 6).toLong() and 0xffffffffL
            check(size + 10 <= len) { "a damaged region record" }
            regions += data.copyOfRange(start + 10, start + 10 + size.toInt())
        }
        p = start + len.toInt()
    }
    check(regions.size == 1) { "it holds ${regions.size} parts, which only Garmin's own tools put on a device" }
    return regions.single()
}

fun md5Hex(f: File): String = MessageDigest.getInstance("MD5").let { md ->
    f.inputStream().use { i -> val buf = ByteArray(1 shl 16); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
    md.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Downloads [u] into [cache] (kept, so a second device needs no new download), checks its MD5 and
 * returns the file to put on the device.
 */
fun fetchUpdate(u: GarminUpdate, cache: File, progress: (Long, Long) -> Unit = { _, _ -> },
                fetch: (String, File, (Long, Long) -> Unit) -> Unit = ::download): File {
    val dir = File(cache, "${u.partNumber}/${u.versionText}").apply { mkdirs() }
    val raw = File(dir, u.url.substringAfterLast('/').ifEmpty { "download" })
    val out = File(dir, u.fileName)
    if (out.isFile && raw.isFile && (u.md5 == null || md5Hex(raw).equals(u.md5, true))) return out
    fetch(u.url, raw, progress)
    u.md5?.let { want -> check(md5Hex(raw).equals(want, true)) { "the download of ${u.name} doesn't match Garmin's checksum" } }
    val payload = unwrapRgn(raw.readBytes())
    if (u.fileName.endsWith(".gcd", true)) check(payload.size > 8 && String(payload, 0, 6, Charsets.ISO_8859_1) == "GARMIN") {
        "the firmware file doesn't look like one"
    }
    out.writeBytes(payload)
    return out
}
