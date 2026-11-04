package app.navilink.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import osmimg.*
import java.io.File

/** Where the map goes. */
sealed interface DeviceChoice {
    /** A Garmin (or a drive holding its maps) mounted on this PC. */
    data class OnDrive(val drive: Drive) : DeviceChoice
    /** GPSMAP 276C / 278 / 478: the unit writes its data card itself, through WebUpdater. */
    data object DataCard : DeviceChoice
    /** No device: the map is saved to a folder. */
    data object Folder : DeviceChoice
}

/**
 * Map files ready to go on the device: built here or downloaded. [name] is used where the device
 * takes any name; [key] says which selection it came from (see [MapsModel.selectionKey]).
 */
data class Ready(val files: List<File>, val name: String, val title: String, val families: List<Int>, val key: String = "")

/**
 * Rough build time from the download size: Portugal (427 MB) took about 30 minutes on a 4-core PC,
 * half of it splitting, which is kept for later builds.
 */
fun buildMinutes(r: Region): Int = maxOf(2, (r.bytes / (14L shl 20)).toInt())

/** "About 2 minutes", "About 30 minutes", "About 6 hours". */
fun buildTime(r: Region): String {
    val n = buildMinutes(r)
    return when {
        n <= 3 -> "About 2 minutes."
        n < 90 -> "About $n minutes. Later builds of this region are faster."
        else -> "About ${(n + 30) / 60} hours. Later builds of this region are faster."
    }
}

fun size(bytes: Long?): String = when {
    bytes == null -> "?"
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / 1048576.0)
    else -> "%.0f KB".format(bytes / 1024.0)
}

/** Everything the Maps window shows, and what its buttons do. Long work runs on [Dispatchers.IO]. */
class MapsModel(private val scope: CoroutineScope) {
    val cache: File = defaultCache()
    val log = mutableStateListOf<String>()
    var busy by mutableStateOf<String?>(null); private set

    // Device
    var drives by mutableStateOf(listOf<Drive>()); private set
    /** Drives or folders picked by hand (not found on their own), kept across "look again". */
    private val manual = (System.getenv("NAVILINK_DRIVES") ?: "").split(File.pathSeparator)
        .filter { it.isNotBlank() }.map(::File).toMutableList()
    var device by mutableStateOf<DeviceChoice>(DeviceChoice.Folder)
    var sd by mutableStateOf<Drive?>(null)
    var storage by mutableStateOf(mapOf<Storage, StorageState>()); private set
    var installed by mutableStateOf(listOf<InstalledMap>()); private set
    /** For "save to a folder": the largest file to make, or null for one file. */
    var folderLimit by mutableStateOf<Long?>(null)

    val profile: DeviceProfile get() = when (val d = device) {
        is DeviceChoice.OnDrive -> Profiles.forDevice(d.drive.xml)
        DeviceChoice.DataCard -> Profiles.DATA_CARD
        DeviceChoice.Folder -> Profiles.UNKNOWN_DRIVE
    }

    val roots: Map<Storage, File> get() = buildMap {
        (device as? DeviceChoice.OnDrive)?.let { put(Storage.INTERNAL, it.drive.root) }
        sd?.let { put(Storage.SD, it.root) }
    }

    /** 0 = build, 1 = download. */
    var tab by mutableStateOf(0)

    // Build tab
    val regions: List<Region> = bundledRegions()
    var parent by mutableStateOf("")
    var search by mutableStateOf("")
    var region by mutableStateOf<Region?>(null); private set
    var opts by mutableStateOf(BuildOptions("OSM_map"))
    var choice by mutableStateOf(0)
    private var labelsTouched = false
    private var builder: MapBuilder? = null

    // Download tab
    var source by mutableStateOf(PremadeSource.OPENTOPOMAP); private set
    var premade by mutableStateOf(listOf<PremadeMap>()); private set
    var premadeSearch by mutableStateOf("")
    var picked by mutableStateOf<PremadeMap?>(null); private set
    var pickedBytes by mutableStateOf<Long?>(null); private set

    // Ready to install
    var ready by mutableStateOf<Ready?>(null); private set
    /** The last install that worked, shown until something else happens. */
    var done by mutableStateOf<String?>(null)
    var showLog by mutableStateOf(false)

    /** What is selected on the current tab, to match against [Ready.key]. */
    val selectionKey: String? get() = if (tab == 0) region?.let { "build:${it.id}" } else picked?.let { "premade:${it.id}" }

    /** The ready map belongs to what is selected now. */
    val readyHere: Boolean get() = ready != null && ready?.key == selectionKey

    /** Short name for messages and buttons: "nüvi 760", "276C", or null with no device. */
    val deviceName: String? get() = when (val d = device) {
        is DeviceChoice.OnDrive -> d.drive.xml?.model?.ifEmpty { null } ?: "Garmin"
        DeviceChoice.DataCard -> "276C"
        DeviceChoice.Folder -> null
    }

    /** Label style that suits the chosen device: older units can't show lower case or accents. */
    val recommendedLabels: Labels get() = if (device == DeviceChoice.DataCard) Labels.ASCII else Labels.LATIN1_MIXED_CASE

    fun say(s: String) { scope.launch(Dispatchers.Main) { log += s; if (log.size > 2000) log.removeRange(0, 500) } }

    /** Runs [block] off the window thread, one job at a time, and logs its failure. */
    private fun job(what: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = what
        scope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: Exception) { say("$what failed: ${e.message ?: e}") }
            finally { busy = null }
        }
    }

    // ---- device ----

    /** Looks for drives again; keeps the choice if the drive is still there, picks a Garmin when one appears. */
    fun refreshDrives(quiet: Boolean = false) = scope.launch {
        val found = withContext(Dispatchers.IO) {
            val auto = findDrives()
            auto + manual.filter { f -> f.isDirectory && auto.none { it.root == f } }.map(::readDrive)
        }
        if (quiet && found.map { it.root } == drives.map { it.root }) return@launch
        drives = found
        val d = device
        if (d is DeviceChoice.OnDrive && found.none { it.root == d.drive.root }) device = DeviceChoice.Folder
        if (device == DeviceChoice.Folder) found.firstOrNull { it.isGarmin }?.let { chooseDevice(DeviceChoice.OnDrive(it)); return@launch }
        if (sd != null && found.none { it.root == sd!!.root }) sd = null
        rescan()
    }

    fun chooseDevice(c: DeviceChoice) {
        device = c
        if (c is DeviceChoice.OnDrive && sd?.root == c.drive.root) sd = null
        if (c is DeviceChoice.OnDrive && sd == null) {
            // An SD card usually shows up as a second drive with a Garmin folder and no GarminDevice.xml.
            sd = drives.firstOrNull { it.root != c.drive.root && !it.isGarmin && it.hasGarminFolder }
        }
        if (c == DeviceChoice.DataCard) sd = null
        if (!labelsTouched) opts = opts.copy(labels = recommendedLabels)
        done = null
        rescan()
    }

    fun chooseSd(d: Drive?) { sd = d; rescan() }

    /** A drive or folder the search didn't find: an unusual mount, or a copy of the device's files. */
    fun addDrive(root: File, asSd: Boolean) = scope.launch {
        if (root !in manual) manual += root
        val d = withContext(Dispatchers.IO) { readDrive(root) }
        drives = drives.filter { it.root != root } + d
        if (asSd) chooseSd(d) else chooseDevice(DeviceChoice.OnDrive(d))
    }

    /** Reads what's on the chosen drives: map files, free space and the map IDs in use. */
    fun rescan() = scope.launch {
        val p = profile
        val r = roots
        val (st, inst) = withContext(Dispatchers.IO) {
            val st = buildMap {
                r.forEach { (s, root) -> put(s, storageState(root, p, s)) }
                if (device == DeviceChoice.DataCard) put(Storage.SD, StorageState(emptyList()))
            }
            st to installedMaps(r, p)
        }
        storage = st
        installed = inst
        choice = 0
    }

    // ---- build ----

    fun open(r: Region) {
        if (regions.any { it.parent == r.id }) parent = r.id
        pick(r)
    }

    fun pick(r: Region) {
        region = r
        done = null
        choice = 0
        opts = opts.copy(name = "OSM_" + r.name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_'))
        opts = opts.copy(description = opts.name.replace('_', ' '))
    }

    fun setLabels(l: Labels) { labelsTouched = true; opts = opts.copy(labels = l) }

    fun advice(estimate: Long, name: String): Advice? =
        if (device == DeviceChoice.Folder) null else adviseInstall(profile, storage, estimate, name)

    fun usedFamilies(): Set<Int> = installed.flatMap { m -> m.info?.families.orEmpty().map { it.familyId } }.toSet()

    fun build() {
        val r = region ?: return
        val adv = advice(estimateImgBytes(r), opts.name)
        val opt = adv?.options?.getOrNull(choice)
        val parts = (opt as? InstallOption.Divide)?.parts ?: 1
        val fid = freeFamilyId(usedFamilies(), parts, opts.familyId)
        val max = when (device) {
            DeviceChoice.Folder -> folderLimit
            DeviceChoice.DataCard -> null
            is DeviceChoice.OnDrive -> profile.maxFileBytes
        }
        val o = opts.copy(familyId = fid, maxPartBytes = max)
        val out = File(cache, "out/${r.id}" + if (o.routing == Routing.BIKE) "-bike" else "")
        job("Building ${r.name}") {
            if (fid != opts.familyId) say("Map ID $fid: ${opts.familyId} is already used by a map on the device.")
            val b = MapBuilder(MapTools(File(cache, "tools")), cache, ::say)
            builder = b
            val t0 = System.currentTimeMillis()
            val res = try { b.build(r, o, out) } finally { builder = null }
            say("Built in ${(System.currentTimeMillis() - t0) / 60_000} min: ${res.parts.joinToString { it.name }}")
            setReady(Ready(res.parts, o.name, "${r.name}, built today", (fid until fid + res.parts.size).toList(), "build:${r.id}"))
        }
    }

    fun stop() { builder?.cancel() }

    // ---- download ----

    fun chooseSource(s: PremadeSource) {
        source = s
        picked = null
        premade = emptyList()
        job("Loading ${s.title}") {
            val list = listPremade(s, cache)
            withContext(Dispatchers.Main) { if (source == s) premade = list }
            say("${s.title}: ${list.size} maps")
        }
    }

    fun pickPremade(m: PremadeMap) {
        picked = m
        done = null
        pickedBytes = m.bytes
        if (m.bytes == null) scope.launch {
            val b = withContext(Dispatchers.IO) { remoteSize(m.url) }
            if (picked == m) pickedBytes = b
        }
    }

    fun download() {
        val m = picked ?: return
        job("Downloading ${m.name}") {
            val files = fetchPremade(m, cache, ::say)
            val fams = files.flatMap { f -> readImgInfo(f)?.families.orEmpty().map { it.familyId } }.distinct()
            setReady(Ready(files, m.fileStem, "${m.source.title} ${m.name}, ${m.variant}", fams, "premade:${m.id}"))
        }
    }

    // ---- install ----

    private suspend fun setReady(r: Ready) = withContext(Dispatchers.Main) { ready = r }

    fun clearReady() { ready = null }

    /** Shows map files as ready to install, as after a build or download. */
    fun showReady(r: Ready) { ready = r }

    fun plan(r: Ready): SplitPlan = planSplit(profile, r.files.map { it.length() }, storage, r.name)

    fun install(copies: List<Copy>) {
        job("Installing") {
            val backups = installMaps(copies, roots, File(cache, "backup"), ::say)
            if (backups.isNotEmpty()) say("Backups of the replaced maps: ${backups.joinToString { it.path }}")
            withContext(Dispatchers.Main) {
                ready = null // installed: don't offer the same map again
                done = "Installed. Eject the ${deviceName ?: "device"} in your file manager before you unplug it." +
                    if (backups.isNotEmpty()) " The old map is saved in ${backups.first().parentFile.parentFile.path}." else ""
            }
            withContext(Dispatchers.Main) { rescan() }
        }
    }

    fun saveTo(dir: File, r: Ready) {
        job("Saving") {
            r.files.forEachIndexed { i, f ->
                val dest = File(dir, safeImgName(r.name + if (r.files.size > 1) "_${i + 1}" else ""))
                f.copyTo(dest, overwrite = true)
                say("Saved ${dest.path} (${size(dest.length())})")
            }
            withContext(Dispatchers.Main) { done = "Saved in ${dir.path}." }
        }
    }
}
