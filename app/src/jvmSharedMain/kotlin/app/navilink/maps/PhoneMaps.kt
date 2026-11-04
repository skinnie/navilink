package app.navilink.maps

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import osmimg.*
import java.io.File
import java.io.InputStream

/*
 * The phone's Maps screen: put a ready-made map (BBBike, Freizeitkarte, OpenTopoMap) or an .img the
 * user already has on the nüvi over a USB OTG cable. Building maps needs a PC (mkgmap), so it isn't here.
 * Platform code (pickers, the drive) comes in through [MapsHost], so all of this runs on the desktop
 * JVM too, where it is tested and rendered.
 */

/** A map file to copy: a download kept in [local], or a file the user picked (read through [open]). */
class MapFile(val name: String, val bytes: Long, val info: ImgInfo?, val local: File? = null, val open: () -> InputStream)

/** What the platform does for the Maps screen. */
interface MapsHost {
    /** Downloads and the site lists. */
    val cache: File
    val backupDir: File
    /** Where the backups are, in the words the user finds them by. */
    val backupPlace: String
    /** The drive picked before for the nüvi ([card] false) or its memory card, if it is plugged in. */
    fun remembered(card: Boolean): DriveFs?
    /** Asks the user to pick the drive, and remembers it. Null when they cancel. */
    suspend fun pickDrive(card: Boolean): DriveFs?
    fun forget(card: Boolean)
    /** Asks the user for an .img file. */
    suspend fun pickImg(): MapFile?
}

/** Map files ready to install, and which selection they belong to ([key]). */
data class PhoneReady(val files: List<MapFile>, val name: String, val title: String, val families: List<Int>, val key: String)

fun size(bytes: Long?): String = when {
    bytes == null -> "?"
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / 1048576.0)
    else -> "%.0f KB".format(bytes / 1024.0)
}

class PhoneMaps(private val scope: CoroutineScope, private val host: MapsHost, private val log: (String) -> Unit = {}) {
    var busy by mutableStateOf<String?>(null); private set
    /** Download progress 0..1, or null when unknown or idle. */
    var progress by mutableStateOf<Float?>(null); private set
    /** The latest thing that happened, in a line. */
    var status by mutableStateOf<String?>(null); private set
    /** The last job that failed, until the next one starts. */
    var error by mutableStateOf<String?>(null); private set
    @Volatile private var stopAsked = false

    // The nüvi
    var drive by mutableStateOf<DriveFs?>(null); private set
    var card by mutableStateOf<DriveFs?>(null); private set
    var xml by mutableStateOf<DeviceXml?>(null); private set
    var storage by mutableStateOf(mapOf<Storage, StorageState>()); private set
    var installed by mutableStateOf(listOf<InstalledMap>()); private set
    val profile: DeviceProfile get() = Profiles.forDevice(xml)
    val deviceName: String get() = xml?.model?.ifEmpty { null } ?: "Garmin"
    val drives: Map<Storage, DriveFs> get() = buildMap {
        drive?.let { put(Storage.INTERNAL, it) }
        card?.let { put(Storage.SD, it) }
    }

    /** 0 = download a map, 1 = a file on the phone. */
    var tab by mutableStateOf(0)
    var source by mutableStateOf(PremadeSource.OPENTOPOMAP); private set
    var premade by mutableStateOf(listOf<PremadeMap>()); private set
    var search by mutableStateOf("")
    var picked by mutableStateOf<PremadeMap?>(null); private set
    var pickedBytes by mutableStateOf<Long?>(null); private set
    var own by mutableStateOf<MapFile?>(null); private set

    var ready by mutableStateOf<PhoneReady?>(null); private set
    /** The last install that worked, shown until something else happens. */
    var done by mutableStateOf<String?>(null); private set

    val selectionKey: String? get() = if (tab == 0) picked?.let { "premade:${it.source}:${it.id}" } else own?.let { "own:${it.name}:${it.bytes}" }
    val readyHere: Boolean get() = ready != null && ready?.key == selectionKey

    val shown: List<PremadeMap> get() {
        val q = search.trim()
        return if (q.isEmpty()) premade else premade.filter { listOf(it.name, it.group, it.variant).any { s -> s.contains(q, true) } }
    }

    private fun say(s: String) {
        log(s)
        scope.launch(Dispatchers.Main) { status = s }
    }

    /** Runs [block] off the main thread, one job at a time; a failure is shown, not thrown. */
    private fun job(what: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = what
        error = null
        stopAsked = false
        scope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: Exception) {
                val msg = if (stopAsked) "Stopped." else "$what failed: ${e.message ?: e}"
                error = msg.takeIf { !stopAsked }
                say(msg)
            }
            finally { busy = null; progress = null }
        }
    }

    fun stop() { stopAsked = true }

    /** First open: the drives picked before, and the last site's list. */
    fun start() {
        if (drive == null) drive = host.remembered(false)
        if (card == null) card = host.remembered(true)
        rescan()
        if (premade.isEmpty()) chooseSource(source)
    }

    // ---- the nüvi ----

    fun pickDrive(card: Boolean) = scope.launch {
        val fs = host.pickDrive(card) ?: return@launch
        if (card) this@PhoneMaps.card = fs else drive = fs
        done = null
        rescan()
    }

    fun removeCard() { host.forget(true); card = null; rescan() }

    /** Reads what is on the drives again. A drive that is gone is dropped (and picked again next time). */
    fun rescan() = scope.launch {
        val d = drive
        val c = card
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val x = d?.deviceXml()
                val p = Profiles.forDevice(x)
                val roots = buildMap { d?.let { put(Storage.INTERNAL, it) }; c?.let { put(Storage.SD, it) } }
                Triple(x, roots.mapValues { (st, fs) -> storageState(fs, p, st) }, installedMaps(roots, p))
            }
        }
        r.onSuccess { (x, st, inst) -> xml = x; storage = st; installed = inst }
        r.onFailure {
            say("The ${deviceName}'s drive isn't there any more. Plug it in and pick it again.")
            drive = null; card = null; xml = null; storage = emptyMap(); installed = emptyList()
        }
    }

    // ---- choosing a map ----

    fun chooseSource(s: PremadeSource) {
        if (busy != null) return
        source = s
        picked = null
        premade = emptyList()
        job("Loading ${s.title}") {
            val list = listPremade(s, host.cache)
            withContext(Dispatchers.Main) { if (source == s) premade = list }
        }
    }

    fun pickPremade(m: PremadeMap?) {
        picked = m
        done = null
        pickedBytes = m?.bytes
        if (m != null && m.bytes == null) scope.launch {
            val b = withContext(Dispatchers.IO) { remoteSize(m.url) }
            if (picked == m) pickedBytes = b
        }
    }

    fun pickOwn() = scope.launch {
        val f = host.pickImg() ?: return@launch
        done = null
        if (f.info == null) {
            error = "${f.name} isn't a Garmin map. Choose an .img file made for Garmin units."
            return@launch
        }
        error = null
        own = f
        ready = PhoneReady(listOf(f), f.name.substringBeforeLast('.'), f.info.description.ifEmpty { f.name },
            f.info.families.map { it.familyId }, "own:${f.name}:${f.bytes}")
    }

    fun download() {
        val m = picked ?: return
        job("Downloading ${m.name}") {
            // The zip and the map out of it are on the phone at once for a moment.
            pickedBytes?.let { zip ->
                val need = zip + (m.estimatedImgBytes ?: zip * 2) + (50L shl 20)
                val free = host.cache.apply { mkdirs() }.usableSpace
                check(free >= need) { "not enough room on the phone: this needs ${size(need)} and ${size(free)} is free" }
            }
            val files = fetchPremade(m, host.cache, ::say, progress = { done, total ->
                if (total > 0) scope.launch(Dispatchers.Main) { progress = done.toFloat() / total }
            }, cancelled = { stopAsked })
            val maps = files.map { f -> MapFile(f.name, f.length(), readImgInfo(f), f) { f.inputStream() } }
            val r = PhoneReady(maps, m.fileStem, "${m.source.title} ${m.name}, ${m.variant}",
                maps.flatMap { it.info?.families.orEmpty().map { f -> f.familyId } }.distinct(), "premade:${m.source}:${m.id}")
            withContext(Dispatchers.Main) { ready = r }
        }
    }

    // ---- putting it on ----

    fun plan(r: PhoneReady): SplitPlan = planSplit(profile, r.files.map { it.bytes }, storage, r.name)

    /** Maps already there that one map could replace instead (backed up first). */
    fun swaps(r: PhoneReady, planOk: Boolean): List<Pair<Storage, SlotChoice>> =
        if (r.files.size != 1) emptyList()
        else storage.flatMap { (st, s) -> planInstall(profile, st, r.name, r.files.first().bytes, s.existing).replaceable.map { st to it } }
            .filter { (_, c) -> c.slot.role != SlotRole.FACTORY || !planOk }

    fun clashes(r: PhoneReady, replacing: Collection<String> = emptyList()) = familyClashes(installed, r.families, replacing)

    fun install(r: PhoneReady, where: List<Pair<Int, Pair<Storage, String>>>) {
        job("Installing") {
            val copies = where.map { (part, sp) -> r.files[part - 1].let { f -> MapCopy(f.name, f.bytes, sp.first, sp.second, f.open) } }
            val backups = installMaps(copies, drives, host.backupDir, ::say, cancelled = { stopAsked })
            // Downloads take room on the phone; the site still has them.
            val premadeDir = File(host.cache, "premade").canonicalPath
            r.files.mapNotNull { it.local?.parentFile }.distinct().filter { it.canonicalPath.startsWith(premadeDir) }
                .forEach { it.deleteRecursively() }
            withContext(Dispatchers.Main) {
                ready = null
                own = null
                done = "Installed. Eject the $deviceName before you unplug it: pull down the notifications and tap Eject." +
                    if (backups.isNotEmpty()) " The old map is saved in ${host.backupPlace}." else ""
            }
            withContext(Dispatchers.Main) { rescan() }
        }
    }

    fun installPlan(r: PhoneReady, p: SplitPlan) = install(r, p.placements.map { it.part to (it.storage to it.path) })
    fun replace(r: PhoneReady, st: Storage, path: String) = install(r, listOf(1 to (st to path)))
}
