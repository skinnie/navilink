package app.navilink.pois

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.navilink.core.Net
import app.navilink.core.Point
import app.navilink.core.RealNet
import app.navilink.nuvi.Place
import app.navilink.nuvi.buildGpi
import app.navilink.nuvi.gpiFileName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import osmimg.DriveFs
import osmimg.deviceXml

/** What the platform offers: the Garmin's drives, and on a phone a way to pick one. */
interface PoiHost {
    /** The Garmin's drives that are there now: its built-in memory first, then its memory card. */
    fun drives(): List<Pair<String, DriveFs>>
    /** Whether the user picks the drive by hand (phone); on a PC drives are found on their own. */
    val canPick: Boolean
    /** Asks the user for the drive; false when they cancel. */
    suspend fun pickDrive(): Boolean
    /** "nüvi 760", for buttons and messages. */
    fun deviceName(): String
}

/** One kind's outcome, for the results list. */
data class PoiResult(val kind: PoiKind, val written: Int, val alreadyThere: Int, val path: String?)

/**
 * POI packs for the Garmin: downloads the chosen kinds for some countries, leaves out what the Garmin already has,
 * and writes one .gpi per kind to Garmin/POI. Places already there are matched by kind and distance (150 m), using
 * every readable .gpi on the device (iOverlander, the user's own, ours from earlier); a map add-on that can't be read
 * place by place (City Navigator's Foursquare layer) instead leaves out its source as a whole, unless the user says so.
 */
/** The source of one of our own categories ("OSM Fuel"), or null for anyone else's. */
internal fun sourceOf(category: String): PoiKind.Source? =
    (OsmPoiType.entries + FsqPoiType.entries).firstOrNull { it.category.equals(category, true) }?.source

class PoiPacks(
    private val scope: CoroutineScope, private val host: PoiHost,
    private val net: Net = RealNet(), private val fsqIndex: () -> FsqIndex = { FsqIndex.bundled() },
    private val log: (String) -> Unit = {},
) {
    var countries by mutableStateOf("PT")
    val osm = mutableStateListOf(OsmPoiType.FUEL, OsmPoiType.LPG, OsmPoiType.MOTORHOME, OsmPoiType.CAMPSITE, OsmPoiType.DRINKING_WATER)
    val fsq = mutableStateListOf<FsqPoiType>()
    /** Add Foursquare places even though the Garmin has a Foursquare map add-on. */
    var fsqAnyway by mutableStateOf(false)

    var drives by mutableStateOf(listOf<String>()); private set
    var found by mutableStateOf(listOf<FoundPoiSet>()); private set
    var scanned by mutableStateOf(false); private set

    var busy by mutableStateOf<String?>(null); private set
    var progress by mutableStateOf<Float?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var results by mutableStateOf(listOf<PoiResult>()); private set

    val deviceName get() = host.deviceName()
    val canPick get() = host.canPick
    val foursquareMap: FoundPoiSet? get() = found.firstOrNull { it.isImg && it.publisher == PoiPublisher.FOURSQUARE }
    val fsqLeftOut get() = foursquareMap != null && !fsqAnyway
    /** The kinds that will be written, OpenStreetMap first. */
    val chosen: List<PoiKind> get() = osm.sortedBy { it.ordinal } + (if (fsqLeftOut) emptyList() else fsq.sortedBy { it.ordinal })
    val countriesError get() = countriesProblem(countries)

    fun toggle(k: PoiKind) {
        results = emptyList()
        when (k) {
            is OsmPoiType -> if (k in osm) osm -= k else osm += k
            is FsqPoiType -> if (k in fsq) fsq -= k else fsq += k
        }
    }

    /** Looks at what is on the Garmin. */
    fun scan() = scope.launch { scanNow() }

    internal suspend fun scanNow() {
        val r = withContext(Dispatchers.IO) {
            runCatching { host.drives().let { d -> d.map { it.first } to d.flatMap { (label, fs) -> findPoiSets(fs, label) } } }
        }
        r.onSuccess { (d, f) -> drives = d; found = f }.onFailure { log("Couldn't look at the Garmin: ${it.message}") }
        scanned = true
    }

    fun pick() = scope.launch { if (host.pickDrive()) scan() }

    fun install() {
        if (busy != null) return
        error = null; results = emptyList()
        val kinds = chosen
        busy = "Starting"
        scope.launch {
            try { withContext(Dispatchers.IO) { run(kinds) } }
            catch (e: Exception) { error = e.message ?: e.toString(); log("POIs failed: $error") }
            finally { busy = null; progress = null; scan() }
        }
    }

    internal fun run(kinds: List<PoiKind>) {
        val cc = parseCountries(countries)
        val (label, fs) = host.drives().firstOrNull() ?: error("The Garmin isn't there. Plug it in and try again.")
        require(kinds.isNotEmpty()) { "Choose at least one kind of POI." }

        // 1. Download.
        val got = LinkedHashMap<PoiKind, List<Point>>()
        val osmKinds = kinds.filterIsInstance<OsmPoiType>()
        osmKinds.forEachIndexed { i, k ->
            busy = "Downloading ${k.label.lowercase()} from OpenStreetMap"; progress = i.toFloat() / (osmKinds.size + 1)
            got[k] = fetchOsmPois(k, cc, net)
            log("${k.category}: ${got[k]!!.size} places from OpenStreetMap")
        }
        val fsqKinds = kinds.filterIsInstance<FsqPoiType>().toSet()
        if (fsqKinds.isNotEmpty()) {
            busy = "Downloading from Foursquare"
            fetchFoursquare(fsqIndex(), cc, fsqKinds, FSQ_DEFAULT_SINCE, net) { d, n -> progress = if (n == 0) null else d.toFloat() / n }
                .forEach { (k, pts) -> got[k] = pts; log("${k.category}: ${pts.size} places from Foursquare") }
        }

        // 2. What the Garmin already has, by category name. Our own files from before are replaced, so they don't count.
        busy = "Checking what the $deviceName already has"; progress = null
        val targets = kinds.associateWith { "Garmin/POI/" + gpiFileName(it.category) }
        val replaced = targets.values.map { it.lowercase() }.toSet()
        val existing = mutableListOf<Pair<String, NearIndex>>()
        val all = host.drives().toMap()
        for (set in found.filter { it.readable && it.path.lowercase() !in replaced }) {
            val dfs = all[set.drive] ?: continue
            runCatching { dfs.openRead(set.path).use { gpiPlacesByCategory(it) } }
                .onSuccess { cats -> cats.forEach { (name, pts) -> existing += name to NearIndex().apply { pts.forEach { (a, b) -> add(a, b) } } } }
                .onFailure { log("Couldn't read ${set.path}: ${it.message}") }
        }

        // 3. Leave out what's already there, then write.
        val out = mutableListOf<PoiResult>()
        for ((i, k) in kinds.withIndex()) {
            val pts = got[k].orEmpty()
            busy = "Writing ${k.category}"; progress = i.toFloat() / kinds.size
            val same = sameKindWords(k)
            // Kinds from one source don't knock each other out (a campsite next to a motorhome area stays).
            val near = existing.filter { same.containsMatchIn(it.first) && sourceOf(it.first) != k.source }.map { it.second }
            val seen = HashSet<String>()
            val kept = pts.filter { p ->
                seen.add("${Math.rint(p.lat * 1e5)},${Math.rint(p.lon * 1e5)}") && near.none { it.near(p.lat, p.lon) }
            }
            val path = targets.getValue(k)
            if (kept.isEmpty()) {
                // Nothing new: an old file of ours stays as it was rather than being emptied.
                out += PoiResult(k, 0, pts.size, null); continue
            }
            val data = buildGpi(k.category, kept.map { Place(it.name, it.lat, it.lon) }, path.substringAfterLast('/'))
            fs.write(path, data.inputStream())
            log("Wrote $path on the $label: ${kept.size} places, ${pts.size - kept.size} left out")
            out += PoiResult(k, kept.size, pts.size - kept.size, path)
            existing += k.category to NearIndex().apply { kept.forEach { add(it.lat, it.lon) } }
        }
        results = out
        busy = null; progress = null
    }
}

/** The phone's way: the drives picked on the Maps page (the nüvi and its card), picked through Android's folder picker. */
fun app.navilink.maps.MapsHost.asPoiHost(): PoiHost = object : PoiHost {
    override fun drives() = listOfNotNull(remembered(false)?.let { "built-in memory" to it }, remembered(true)?.let { "memory card" to it })
        .filter { (_, fs) -> runCatching { fs.list("") != null }.getOrDefault(false) }
    override val canPick get() = true
    override suspend fun pickDrive() = this@asPoiHost.pickDrive(false) != null
    override fun deviceName() = runCatching { remembered(false)?.deviceXml()?.model }.getOrNull()?.ifEmpty { null } ?: "Garmin"
}
