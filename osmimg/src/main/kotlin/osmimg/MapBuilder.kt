package osmimg

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipInputStream

/**
 * Routing in the built map. [BIKE] also suits units that only route by car (276C, nüvi): their
 * car mode then follows roads a bicycle may use and prefers cycleways and quiet streets.
 */
enum class Routing { CAR, BIKE, NONE }

/** Which rules turn OSM data into map objects. */
enum class MapStyle {
    /** The clean style shipped here: POIs in the Garmin Find categories, no clutter; roads from mkgmap's default. */
    CLEAN,
    /** mkgmap's built-in default style, as it comes. */
    MKGMAP_DEFAULT,
}

/** How labels are stored. Older units can't show lower case or accents. */
enum class Labels(val codePage: Int?, val lowerCase: Boolean) {
    /** Capitals without accents: every unit, the 276C and older included. */
    ASCII(null, false),
    /** Capitals with accents (ã, ç, é): code page 1252. */
    LATIN1(1252, false),
    /** Normal text with accents: newer units (nüvi, eTrex, Edge, Oregon...). */
    LATIN1_MIXED_CASE(1252, true),
}

/** What to build. */
data class BuildOptions(
    /** File name stem for the parts: "OSM_Portugal" gives OSM_Portugal.img or OSM_Portugal_1.img... */
    val name: String,
    /** Shown on the device and in BaseCamp. */
    val description: String = name.replace('_', ' '),
    /** Garmin family ID of the first part; part n gets familyId + n - 1. Keep away from Garmin's own (< 1000 are theirs). */
    val familyId: Int = 6324,
    val routing: Routing = Routing.CAR,
    val style: MapStyle = MapStyle.CLEAN,
    val labels: Labels = Labels.LATIN1_MIXED_CASE,
    /** Bus and tram stops as POIs (clean style only; there are a lot of them). */
    val transitStops: Boolean = false,
    /** Address and POI search on the device. */
    val index: Boolean = true,
    /** Your own mkgmap style directory; overrides [style]. */
    val styleDir: File? = null,
    /** Largest file allowed per part (from the device profile); null = one file. */
    val maxPartBytes: Long? = null,
    /** Java heap for splitter and mkgmap. */
    val heapMb: Int = 2048,
    /** splitter tile size; smaller tiles make finer parts but more files to process. */
    val maxNodesPerTile: Int = 1_600_000,
) {
    init {
        require(familyId in 1..0xFFFF) { "family ID must be 1-65535" }
    }

    /** The style options passed to mkgmap (--style-option), empty for none. */
    val styleOptions: List<String> get() = buildList {
        if (routing == Routing.BIKE) add("bike")
        if (transitStops) add("transit")
    }
}

data class BuildResult(val parts: List<File>, val tiles: Int)

/**
 * The cache shared by every program that builds maps with this module (NaviLink, bbnav, the
 * command line): tools, downloads, split tiles and built maps. ~/.cache/osmimg, or $OSMIMG_CACHE.
 */
fun defaultCache(): File = System.getenv("OSMIMG_CACHE")?.let(::File)
    ?: File(System.getProperty("user.home"), ".cache/osmimg")

/** Where splitter and mkgmap live; [ensure] downloads them from mkgmap.org.uk the first time. */
class MapTools(val dir: File) {
    val mkgmap get() = File(dir, "mkgmap-$MKGMAP_VERSION/mkgmap.jar")
    val splitter get() = File(dir, "splitter-$SPLITTER_VERSION/splitter.jar")

    fun ensure(log: (String) -> Unit) {
        for ((jar, name) in listOf(mkgmap to "mkgmap-$MKGMAP_VERSION", splitter to "splitter-$SPLITTER_VERSION")) {
            if (jar.isFile) continue
            log("Downloading $name (one time) ...")
            dir.mkdirs()
            val zip = File(dir, "$name.zip")
            download("https://www.mkgmap.org.uk/download/$name.zip", zip)
            unzip(zip, dir)
            check(jar.isFile) { "$name: ${jar.name} not found in the download" }
        }
    }

    companion object {
        const val MKGMAP_VERSION = "r4924"
        const val SPLITTER_VERSION = "r654"
    }
}

/** Downloads [url] to [to] through a .part file, so a broken download is never mistaken for a finished one. */
fun download(url: String, to: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
    val part = File(to.path + ".part")
    var u = URI(url).toURL()
    var c: HttpURLConnection
    var hops = 0
    while (true) {
        c = u.openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", "osmimg")
        c.connectTimeout = 30_000
        c.readTimeout = 60_000
        if (c.responseCode in 300..399 && hops++ < 5) { u = URI(url).resolve(c.getHeaderField("Location")).toURL(); continue }
        break
    }
    check(c.responseCode == 200) { "download failed: HTTP ${c.responseCode} for $url" }
    val total = c.contentLengthLong
    c.inputStream.use { inp ->
        part.outputStream().use { out ->
            val buf = ByteArray(1 shl 16)
            var done = 0L
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                onProgress(done, total)
            }
        }
    }
    if (total > 0) check(part.length() == total) { "download incomplete: ${part.length()} of $total bytes" }
    check(part.renameTo(to) || (to.delete() && part.renameTo(to))) { "could not save $to" }
}

private fun unzip(zip: File, dest: File) {
    val root = dest.canonicalFile
    ZipInputStream(zip.inputStream()).use { z ->
        while (true) {
            val e = z.nextEntry ?: break
            val f = File(root, e.name).canonicalFile
            check(f.path.startsWith(root.path + File.separator)) { "bad path in zip: ${e.name}" }
            if (e.isDirectory) f.mkdirs() else { f.parentFile.mkdirs(); f.outputStream().use { z.copyTo(it) } }
        }
    }
}

/**
 * Splits [sizes] (in order) into consecutive groups whose sums stay under [limit]; one item over
 * the limit gets a group of its own. Consecutive splitter tiles are neighbours, so each part
 * covers one block of the region.
 */
fun groupBySize(sizes: List<Long>, limit: Long): List<IntRange> {
    val out = mutableListOf<IntRange>()
    var start = 0
    var sum = 0L
    for (i in sizes.indices) {
        if (i > start && sum + sizes[i] > limit) { out += start until i; start = i; sum = 0 }
        sum += sizes[i]
    }
    if (sizes.isNotEmpty()) out += start until sizes.size
    return out
}

/** The styles shipped with the app, unpacked to a folder mkgmap can read. */
object Styles {
    /** Files of the clean style, under resources /osmimg/style/clean/. */
    val CLEAN_FILES = listOf("version", "info", "options", "points", "lines", "polygons", "relations",
        "inc/bike-tags", "inc/bike-routing")

    /**
     * Unpacks the clean style into [root]/clean-<hash>/ and returns that folder. The hash of the
     * contents names the folder, so an app update with new rules never reuses old files.
     */
    fun clean(root: File): File {
        val files = CLEAN_FILES.associateWith { name ->
            val s = Styles::class.java.getResourceAsStream("/osmimg/style/clean/$name") ?: error("style file $name missing")
            s.use { it.readBytes() }
        }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        files.forEach { (n, b) -> md.update(n.toByteArray()); md.update(b) }
        val hash = md.digest().take(6).joinToString("") { "%02x".format(it) }
        val dir = File(root, "clean-$hash")
        if (!File(dir, "points").isFile) {
            val tmp = File(root, "clean-$hash.tmp").apply { deleteRecursively(); mkdirs() }
            files.forEach { (n, b) -> File(tmp, n).apply { parentFile.mkdirs(); writeBytes(b) } }
            dir.deleteRecursively()
            check(tmp.renameTo(dir)) { "could not unpack the style to $dir" }
        }
        return dir
    }
}

/** Builds Garmin .img maps from OpenStreetMap extracts with splitter and mkgmap. */
class MapBuilder(
    private val tools: MapTools,
    private val cache: File,
    private val log: (String) -> Unit,
    private val java: String = File(System.getProperty("java.home"), "bin/java").path,
) {
    /** The region's .osm.pbf, downloaded again when older than [maxAgeDays]. */
    fun pbfFor(region: Region, maxAgeDays: Int = 14): File {
        val f = File(cache, "osm/${region.id.substringAfterLast('/')}-latest.osm.pbf")
        val fresh = f.isFile && System.currentTimeMillis() - f.lastModified() < maxAgeDays * 86_400_000L
        if (!fresh) {
            log("Downloading OpenStreetMap data for ${region.name} ...")
            f.parentFile.mkdirs()
            var shown = -1L
            download(region.pbfUrl, f) { done, total ->
                val pct = if (total > 0) done * 100 / total else -1
                if (pct / 10 != shown / 10) { shown = pct; if (pct >= 0) log("  $pct%") }
            }
        }
        return f
    }

    fun build(region: Region, opts: BuildOptions, outDir: File): BuildResult {
        cancelled = false
        tools.ensure(log)
        val pbf = pbfFor(region)
        val work = File(cache, "work/${region.id.substringAfterLast('/')}").apply { mkdirs() }
        val tilesDir = File(work, "tiles")
        // Splitting is the slow step (15 min for Portugal on a busy 4-core PC): keep the tiles
        // while the extract, the tile size and the IDs stay the same.
        val stamp = File(tilesDir, "split.ok")
        val key = "${pbf.length()} ${pbf.lastModified()} ${opts.maxNodesPerTile} ${opts.familyId}"
        if (stamp.isFile && stamp.readText() == key) {
            log("Reusing the tiles from the last build of ${region.name}")
        } else {
            tilesDir.deleteRecursively(); tilesDir.mkdirs()
            log("Splitting ${region.name} into tiles (the slowest step) ...")
            run(listOf(java, "-Xmx${opts.heapMb}m", "-jar", tools.splitter.path,
                "--mapid=${opts.familyId}0001", "--max-nodes=${opts.maxNodesPerTile}",
                "--output-dir=${tilesDir.path}", pbf.path), tilesDir)
            stamp.writeText(key)
        }
        val tiles = tilesDir.listFiles { f -> f.name.endsWith(".osm.pbf") }!!.sortedBy { it.name }
        check(tiles.isNotEmpty()) { "splitter made no tiles" }
        log("${tiles.size} tiles")

        // Group tiles by their pbf size, scaled to the expected img size, leaving 10% room.
        val limit = opts.maxPartBytes
        var groups = if (limit == null) listOf(tiles.indices)
        else groupBySize(tiles.map { (it.length() * IMG_PER_PBF).toLong() }, (limit * 0.9).toLong())

        outDir.mkdirs()
        val parts = mutableListOf<File>()
        var queue = ArrayDeque(groups)
        while (queue.isNotEmpty()) {
            val g = queue.removeFirst()
            val n = parts.size + 1
            val img = buildPart(tiles.slice(g), opts, n, File(work, "part$n"))
            if (limit != null && img.length() > limit && g.count() > 1) {
                // Bigger than the device reads: halve the group and build both halves.
                log("Part $n came out ${img.length() / 1_048_576} MB, over the limit: splitting it in two")
                val mid = g.first + g.count() / 2
                queue.addFirst(mid..g.last); queue.addFirst(g.first until mid)
                continue
            }
            check(limit == null || img.length() <= limit) { "one tile alone is bigger than the device's file limit" }
            parts += img
        }
        // Name the parts and move them out of the work folder.
        val named = parts.mapIndexed { i, f ->
            val dest = File(outDir, safeImgName(if (parts.size == 1) opts.name else "${opts.name}_${i + 1}"))
            f.copyTo(dest, overwrite = true); dest
        }
        named.forEach { log("Built ${it.name}: ${it.length() / 1_048_576} MB") }
        return BuildResult(named, tiles.size)
    }

    /** One mkgmap run: compiles [tiles] and combines them into one gmapsupp-style img with its own family ID. */
    private fun buildPart(tiles: List<File>, opts: BuildOptions, n: Int, dir: File): File {
        dir.deleteRecursively(); dir.mkdirs()
        val fid = opts.familyId + n - 1
        val args = mutableListOf(java, "-Xmx${opts.heapMb}m", "-jar", tools.mkgmap.path,
            "--output-dir=${dir.path}", "--gmapsupp",
            "--family-id=$fid", "--product-id=1",
            "--family-name=${opts.description}", "--series-name=${opts.description}",
            "--description=${opts.description}" + if (n > 1 || opts.maxPartBytes != null) " $n" else "",
            "--overview-mapname=${fid}0000",
            "--draw-priority=25", "--add-pois-to-areas", "--link-pois-to-ways",
            // Sea from the extract's coastline; extend-sea-sectors closes it at the extract's edge.
            "--generate-sea=multipolygon,extend-sea-sectors",
            // A broken tile is reported instead of stopping the whole build.
            "--keep-going",
        )
        if (opts.routing != Routing.NONE) args += "--route"
        if (opts.routing == Routing.BIKE) args += "--make-opposite-cycleways"
        if (opts.index) args += "--index"
        opts.labels.codePage?.let { args += "--code-page=$it" }
        if (opts.labels.lowerCase) args += "--lower-case"
        val styleDir = opts.styleDir ?: if (opts.style == MapStyle.CLEAN) Styles.clean(File(cache, "style")) else null
        styleDir?.let { args += "--style-file=${it.path}" }
        if (opts.style == MapStyle.CLEAN && opts.styleDir == null) {
            // The same POI drawn twice (a shop as a point and as a building) shows once.
            args += "--nearby-poi-rules=*/named:50,*/unnamed:30"
        }
        if (opts.styleOptions.isNotEmpty()) args += "--style-option=" + opts.styleOptions.joinToString(";")
        args += tiles.map { it.path }
        log("Building part $n (${tiles.size} tiles, family ID $fid) ...")
        run(args, dir)
        val img = File(dir, "gmapsupp.img")
        check(img.isFile) { "mkgmap did not make gmapsupp.img for part $n" }
        return img
    }

    @Volatile private var current: Process? = null
    @Volatile private var cancelled = false

    /** Stops a running [build] from another thread; the build then fails with "cancelled". */
    fun cancel() {
        cancelled = true
        current?.destroyForcibly()
    }

    private fun run(cmd: List<String>, dir: File) {
        check(!cancelled) { "cancelled" }
        val p = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start()
        current = p
        val tail = ArrayDeque<String>()
        p.inputStream.bufferedReader().forEachLine { line ->
            tail.addLast(line); if (tail.size > 20) tail.removeFirst()
            if (line.contains("SEVERE") || line.contains("Exception") || line.startsWith("Elapsed time") || line.contains("Number of MapFailedExceptions")) log("  " + line.take(160))
        }
        val code = p.waitFor()
        current = null
        check(!cancelled) { "cancelled" }
        check(code == 0) { "${File(cmd[3]).name} failed (exit $code):\n" + tail.joinToString("\n") }
    }
}
