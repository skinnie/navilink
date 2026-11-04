package osmimg

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import java.util.zip.ZipInputStream

/** A site with ready-made Garmin maps from OpenStreetMap data. */
enum class PremadeSource(val title: String, val site: String, val listUrl: String, val about: String) {
    BBBIKE("BBBike", "https://download.bbbike.org/osm/bbbike/", "https://download.bbbike.org/osm/bbbike/",
        "About 240 cities and their surroundings, updated weekly. Onroad (car/bike), Ontrail (hiking, MTB), " +
            "OpenTopoMap look, or mkgmap's default style. Every BBBike map uses the same map ID, so keep one at a time."),
    FREIZEITKARTE("Freizeitkarte", "https://www.freizeitkarte-osm.de/garmin/en/", "https://download.freizeitkarte-osm.de/garmin/latest/",
        "Topographic maps of European countries and regions, with contour lines and routing. Several languages."),
    OPENTOPOMAP("OpenTopoMap", "https://garmin.opentopomap.org/", "https://garmin.opentopomap.org/",
        "Topographic maps of every country, with a separate contour line map. Licence CC-BY-NC-SA 4.0: not for resale."),
}

/**
 * One ready-made map. [bytes] is the download (zip) size if the site lists it; the map on the device
 * is a bit bigger (see [estimatedImgBytes]). [fileStem] is the name it gets where the device takes any name.
 */
data class PremadeMap(
    val source: PremadeSource,
    val id: String,
    val name: String,
    val group: String,
    val variant: String,
    val url: String,
    val bytes: Long?,
    val updated: String?,
    val fileStem: String,
) {
    /** Zips of these maps shrink them by about a fifth (measured on Andorra and Lisbon: 1.2-1.3x). */
    val estimatedImgBytes: Long? get() = bytes?.let { (it * 1.35).toLong() }
}

/** "9.8M", "1.7G", "512K" or "4.2 MB" -> bytes. */
internal fun parseListedSize(s: String): Long? {
    val m = Regex("""([\d.]+)\s*([KMG])i?B?""", RegexOption.IGNORE_CASE).find(s.trim()) ?: return null
    val n = m.groupValues[1].toDoubleOrNull() ?: return null
    val mult = when (m.groupValues[2].uppercase()) { "K" -> 1L shl 10; "M" -> 1L shl 20; else -> 1L shl 30 }
    return (n * mult).toLong()
}

// Built from the two-letter list: Locale.IsoCountryCode needs Android 12.
private val COUNTRY_BY_ISO3: Map<String, String> by lazy {
    Locale.getISOCountries().mapNotNull { c ->
        val l = Locale("", c)
        runCatching { l.isO3Country }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { it to l.getDisplayCountry(Locale.ENGLISH) }
    }.toMap()
}

private val FZK_NAMES = mapOf(
    "ALPS_EAST" to "Alps (east)", "ALPS_WEST" to "Alps (west)", "AZORES" to "Azores", "BALEARICS" to "Balearic Islands",
    "BALKAN" to "Balkans", "BEL_NLD_LUX" to "Belgium, Netherlands, Luxembourg", "CANARY_ISLANDS" to "Canary Islands",
    "CORSICA" to "Corsica", "MADEIRA" to "Madeira", "SARDINIA" to "Sardinia", "SICILY" to "Sicily", "BRITISH_ISLES" to "British Isles",
    "SCANDINAVIA" to "Scandinavia", "BALTIC" to "Baltic states", "DACH" to "Germany, Austria, Switzerland",
)

private val FZK_LANGS = mapOf("en" to "English", "de" to "German", "fr" to "French", "nl" to "Dutch", "it" to "Italian",
    "pt" to "Portuguese", "es" to "Spanish", "pl" to "Polish", "ru" to "Russian")

/** Readable name for a Freizeitkarte code: "AUT+" -> "Austria (and border area)". */
internal fun fzkName(code: String): String {
    FZK_NAMES[code]?.let { return it }
    // A country code, "+" for the border area, then maybe a part: AUT+, ESP_CANARIAS, NOR+NORTH.
    val m = Regex("""([A-Z]{3})(\+?)[_+]?(.*)""").matchEntire(code)
    val country = m?.groupValues?.get(1)?.let { COUNTRY_BY_ISO3[it] }
    if (m == null || country == null) return code.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    val part = m.groupValues[3].replace('_', ' ').lowercase()
    return country + (if (part.isNotEmpty()) " ($part)" else "") + if (m.groupValues[2] == "+") " and border area" else ""
}

/** Parses Freizeitkarte's download folder listing (Apache index): `<code>_<lang>_gmapsupp.img.zip`. */
internal fun parseFreizeitkarte(html: String, base: String = PremadeSource.FREIZEITKARTE.listUrl): List<PremadeMap> {
    val row = Regex("""<a href="(([A-Z0-9_+]+)_([a-z]{2})_gmapsupp\.img\.zip)">.*?</a></td><td[^>]*>([\d-]+)[^<]*</td><td[^>]*>\s*([\d.]+[KMG])""")
    return row.findAll(html).map { m ->
        val (file, code, lang, date, size) = m.destructured
        val name = fzkName(code)
        PremadeMap(PremadeSource.FREIZEITKARTE, "$code-$lang", name, FZK_LANGS[lang] ?: lang, "topo",
            base + file.replace("+", "%2B"), parseListedSize(size), date, safeImgName("FZK_${code.replace("+", "plus")}_$lang").removeSuffix(".img"))
    }.toList()
}

/** Parses garmin.opentopomap.org: one table row per country, with the map and the contour line map. */
internal fun parseOpenTopoMap(html: String, base: String = PremadeSource.OPENTOPOMAP.listUrl): List<PremadeMap> {
    val row = Regex("""<tr class='country' id='([^']+)' continent='([^']+)'[^>]*><td>([^<]+)</td>(.*?)</tr>""")
    val link = Regex("""<a href="([^"]+\.zip)">([^<]+)</a>\s*\(([^)]+)\)""")
    val date = Regex("""<td>(\d{4}-\d{2}-\d{2})""")
    return row.findAll(html).flatMap { m ->
        val (id, continent, name, cells) = m.destructured
        val updated = date.find(cells)?.groupValues?.get(1)
        link.findAll(cells).mapNotNull { l ->
            val (href, label, size) = l.destructured
            val variant = when {
                label.contains("Basecamp", true) -> return@mapNotNull null // for the PC program, not a device
                label.contains("contour", true) -> "contour lines (add to the map)"
                else -> "topo"
            }
            val stem = href.substringAfterLast('/').removeSuffix(".zip")
            PremadeMap(PremadeSource.OPENTOPOMAP, stem, name.trim(), continent.replaceFirstChar { it.uppercase() }, variant,
                URI(base).resolve(href).toString(), parseListedSize(size), updated, safeImgName(stem).removeSuffix(".img"))
        }
    }.toList()
}

/** BBBike's styles, as named in its file names. */
private val BBBIKE_STYLES = listOf(
    "garmin-onroad-latin1" to "onroad (latin1)",
    "garmin-ontrail-latin1" to "ontrail (latin1)",
    "garmin-opentopo-latin1" to "OpenTopoMap look (latin1)",
    "garmin-osm" to "mkgmap default (UTF-8, newer units only)",
)

/** Parses BBBike's city folder listing. Sizes are not listed there; [remoteSize] fetches one when needed. */
internal fun parseBbbike(html: String, base: String = PremadeSource.BBBIKE.listUrl): List<PremadeMap> {
    val city = Regex("""href="([A-Z][A-Za-z0-9_-]*)/"""")
    return city.findAll(html).map { it.groupValues[1] }.distinct().flatMap { c ->
        val name = c.replace(Regex("([a-z])([A-Z])"), "$1 $2")
        BBBIKE_STYLES.map { (style, label) ->
            PremadeMap(PremadeSource.BBBIKE, "$c-$style", name, "Cities", label, "$base$c/$c.osm.$style.zip", null, null,
                safeImgName("BBBike_${c}_${style.removePrefix("garmin-").substringBefore('-')}").removeSuffix(".img"))
        }
    }.toList()
}

fun parsePremade(source: PremadeSource, html: String): List<PremadeMap> = when (source) {
    PremadeSource.BBBIKE -> parseBbbike(html)
    PremadeSource.FREIZEITKARTE -> parseFreizeitkarte(html)
    PremadeSource.OPENTOPOMAP -> parseOpenTopoMap(html)
}

private fun open(url: String, method: String = "GET"): HttpURLConnection {
    var u = URI(url).toURL()
    var hops = 0
    while (true) {
        val c = u.openConnection() as HttpURLConnection
        c.requestMethod = method
        c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", "osmimg")
        c.connectTimeout = 30_000
        c.readTimeout = 60_000
        if (c.responseCode in 300..399 && hops++ < 5) { u = u.toURI().resolve(c.getHeaderField("Location")).toURL(); continue }
        return c
    }
}

/** Download size of [url] from the server, or null if it doesn't say. */
fun remoteSize(url: String): Long? = runCatching {
    val c = open(url, "HEAD")
    if (c.responseCode == 200) c.contentLengthLong.takeIf { it > 0 } else null
}.getOrNull()

/**
 * The maps a [source] offers. The listing is kept in [cache] for a day, so browsing doesn't hit
 * the site every time; with no network the last listing is used, however old.
 */
fun listPremade(source: PremadeSource, cache: File, maxAgeHours: Int = 24): List<PremadeMap> {
    val f = File(cache, "premade/${source.name.lowercase()}.html")
    val fresh = f.isFile && System.currentTimeMillis() - f.lastModified() < maxAgeHours * 3_600_000L
    if (!fresh) {
        runCatching {
            val c = open(source.listUrl)
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            val text = c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            check(parsePremade(source, text).isNotEmpty()) { "the page has changed: no maps found" }
            f.parentFile.mkdirs(); f.writeText(text)
        }.onFailure { if (!f.isFile) throw IllegalStateException("${source.title}: can't load the list (${it.message})", it) }
    }
    return parsePremade(source, f.readText())
}

/**
 * Downloads a ready-made map and returns its .img file(s), kept in [cache] so a second install
 * needs no download. Only `.img` files are taken from the zip, by their bare names.
 * [progress] gets bytes done and the total (-1 if unknown); [cancelled] stops the download when true.
 */
fun fetchPremade(map: PremadeMap, cache: File, log: (String) -> Unit,
                 progress: (Long, Long) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false }): List<File> {
    val dir = File(cache, "premade/${map.source.name.lowercase()}/${map.id}")
    val ok = File(dir, "download.ok")
    val zip = File(dir, "map.zip")
    if (!ok.isFile) {
        dir.deleteRecursively(); dir.mkdirs()
        log("Downloading ${map.source.title} ${map.name} (${map.variant}) ...")
        var shown = -1L
        try {
            download(map.url, zip) { done, total ->
                if (cancelled()) throw InterruptedException("stopped")
                progress(done, total)
                val pct = if (total > 0) done * 100 / total else -1
                if (pct / 10 != shown / 10) { shown = pct; if (pct >= 0) log("  $pct%") }
            }
        } catch (e: InterruptedException) { dir.deleteRecursively(); throw e }
        val imgs = mutableListOf<File>()
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val bare = e.name.substringAfterLast('/')
                if (e.isDirectory || !bare.endsWith(".img", true)) continue
                val out = File(dir, bare)
                out.outputStream().use { z.copyTo(it) }
                imgs += out
            }
        }
        zip.delete()
        check(imgs.isNotEmpty()) { "no .img in the download" }
        ok.writeText(map.url)
    }
    val imgs = dir.listFiles { f -> f.name.endsWith(".img", true) }!!.sortedBy { it.name }
    imgs.forEach { log("Ready: ${it.name}, ${it.length() / 1_048_576} MB") }
    return imgs
}
