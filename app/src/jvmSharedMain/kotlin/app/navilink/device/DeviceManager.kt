package app.navilink.device

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

/** What the platform offers the device page. */
interface DeviceHost {
    /** The Garmin's drives that are there now: built-in memory and/or memory card. */
    fun drives(): Map<Storage, DriveFs>
    /** GarminDevice.xml of the built-in memory, or null (a card on its own, an older unit). */
    fun deviceXml(): DeviceXml?
    /** Where copies of removed maps and undo records go, on this computer or phone. */
    val backupDir: File
    /** One 256-pixel OpenStreetMap tile for the coverage map, or null when there's none (offline). */
    suspend fun mapTile(z: Int, x: Int, y: Int): androidx.compose.ui.graphics.ImageBitmap? = null
    /** True where the user chooses the drives here (Android's folder picker); on a computer they come from the device bar. */
    val canPick: Boolean get() = false
    /** Asks the user for the Garmin's drive, or its memory card when [card]. */
    suspend fun pick(card: Boolean) {}
    /** Asks the user for files to put on the device; null when they backed out. */
    suspend fun pickFiles(): List<AddSource>? = null
    /** Where device backups go. */
    val deviceBackups: File get() = File(backupDir, "devices")
    /** Where downloads from Garmin are kept, so a second device needs no new download. */
    val updatesCache: File get() = File(backupDir.parentFile ?: backupDir, "garmin-updates")
    /** Asks Garmin's update services; tests put a fake in. */
    val updateHttp: UpdateHttp get() = realUpdateHttp
    /** Downloads a file from Garmin (url, to, progress); tests put a fake in. */
    val fetch: (String, File, (Long, Long) -> Unit) -> Unit get() = ::download
    /** "this computer" or "this phone", for messages. */
    val here: String get() = "this computer"
}

/**
 * One OpenStreetMap tile as PNG bytes, kept in [dir] for 30 days as the tile policy asks, or null offline.
 * The app is named in the User-Agent, also as the policy asks.
 */
fun cachedOsmTile(net: app.navilink.core.Net, dir: File, z: Int, x: Int, y: Int): ByteArray? {
    val f = File(dir, "$z/$x/$y.png")
    if (!f.isFile || System.currentTimeMillis() - f.lastModified() > 30L * 86_400_000) runCatching {
        val r = net.get("https://tile.openstreetmap.org/$z/$x/$y.png", mapOf("User-Agent" to app.navilink.core.USER_AGENT), timeoutMs = 10_000)
        if (r.code == 200 && r.body.isNotEmpty()) { f.parentFile.mkdirs(); f.writeBytes(r.body) }
    }
    return f.takeIf { it.isFile }?.readBytes()
}

/** A change that can be undone in this session, with the words for the undo note. */
data class DoneEdit(val text: String, val undo: Undo?)

/**
 * The "On the device" page: what map files are on the Garmin and its card, what's wrong with them,
 * and the changes a user may make (names, move between memories, remove).
 */
class DeviceManager(private val scope: CoroutineScope, private val host: DeviceHost, private val log: (String) -> Unit = {}) {
    var contents by mutableStateOf<DeviceContents?>(null); private set
    var model by mutableStateOf<String?>(null); private set
    var storages by mutableStateOf(listOf<Storage>()); private set
    var selected by mutableStateOf<DeviceFile?>(null); private set
    var busy by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null)
    /** The last change, shown with an Undo button until the next one. */
    var lastEdit by mutableStateOf<DoneEdit?>(null); private set
    val undos = mutableStateListOf<Undo>()
    /** What the running job is doing now ("Backing up 3 of 120..."). */
    var progress by mutableStateOf<String?>(null); private set
    @Volatile private var stopAsked = false

    /** Files picked to put on the device, and where each would go on [addStorage]. */
    var adding by mutableStateOf<List<AddSource>?>(null); private set
    var addStorage by mutableStateOf(Storage.INTERNAL); private set
    var addPlans by mutableStateOf<List<AddPlan>?>(null); private set

    /** The backup dialog: every file on the device, sorted into parts. */
    var backupList by mutableStateOf<List<BackupFile>?>(null); private set
    /** The restore dialog: the backups there are, the one chosen, and what putting it back would do. */
    var backups by mutableStateOf<List<DeviceBackup>?>(null); private set
    var restoreFrom by mutableStateOf<DeviceBackup?>(null); private set
    var restorePlan by mutableStateOf<List<RestoreItem>?>(null); private set
    val here: String get() = host.here

    /** The startup message, or null when the device has no Garmin/startup.txt (it doesn't show one). */
    var startup by mutableStateOf<StartupText?>(null); private set

    /** What Garmin offers the device, after "Check"; [chosenUpdates] are the part numbers ticked. */
    var updates by mutableStateOf<UpdateCheck?>(null); private set
    var checkingUpdates by mutableStateOf(false); private set
    val chosenUpdates = mutableStateListOf<String>()
    /** Part numbers copied in this session: the device takes them in when it next starts. */
    val copiedUpdates = mutableStateListOf<String>()
    val backupsPlace: File get() = host.deviceBackups

    val deviceName: String get() = model ?: if (storages == listOf(Storage.SD)) "memory card" else "Garmin"

    fun select(f: DeviceFile?) { selected = f }

    val canPick: Boolean get() = host.canPick

    /** Lets the user choose the drive or the card, then looks at the device again. */
    fun pick(card: Boolean) = scope.launch { host.pick(card); scanNow() }

    fun scan() = scope.launch { scanNow() }

    internal suspend fun scanNow() {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val drives = host.drives()
                val xml = host.deviceXml()
                val m = xml?.model?.ifEmpty { null }
                val st = runCatching { drives[Storage.INTERNAL]?.let { fs -> fs.readBytes(fs.spelling(STARTUP_FILE))?.let(::readStartupText) } }.getOrNull()
                startupFound = st
                Triple(drives.keys.sorted(), m, if (drives.isEmpty()) null
                    else scanDevice(drives, if (xml != null) Profiles.forDevice(xml) else Profiles.UNKNOWN_DRIVE, m, xml?.rasterPaths.orEmpty()))
            }
        }
        r.onSuccess { (st, m, c) ->
            startup = startupFound
            // The selection as it is now: a rename or move may have finished while this scan ran.
            val keep = selected?.let { it.storage to it.path.lowercase() }
            storages = st; model = m; contents = c
            selected = keep?.let { (s, p) -> c?.files?.firstOrNull { it.storage == s && it.path.lowercase() == p } }
        }.onFailure { error = "Couldn't look at the device: ${it.message}"; log(error!!) }
    }

    @Volatile private var startupFound: StartupText? = null

    /** Writes a new startup message; the old file is copied to this computer first. */
    fun changeStartup(t: StartupText) = job("Changing the startup message") {
        val fs = fs(Storage.INTERNAL)
        val path = fs.spelling(STARTUP_FILE)
        val old = fs.readBytes(path) ?: error("The device has no $STARTUP_FILE any more.")
        val new = writeStartupText(old, t)
        installMaps(listOf(MapCopy("startup.txt", new.size.toLong(), Storage.INTERNAL, path) { new.inputStream() }),
            host.drives(), File(host.backupDir, "replaced"), {})
        lastEdit = DoneEdit(if (t.message.isEmpty()) "The startup message is cleared." else
            "The startup message is changed. The ${deviceName} shows it after it has been switched off and on once.", null)
        "Startup message on the $deviceName changed (${t.message.length} characters, ${t.seconds} s)"
    }

    /** Runs a change off the window thread, one at a time, then looks at the device again. */
    private fun job(what: String, block: suspend () -> String?) {
        if (busy != null) return
        busy = what; error = null; stopAsked = false
        scope.launch {
            try {
                val done = withContext(Dispatchers.IO) { block() }
                if (done != null) log(done)
            } catch (e: Exception) {
                error = if (stopAsked) "Stopped. Nothing half copied was left behind." else e.message ?: e.toString()
                log("$what failed: $error")
            } finally {
                busy = null; progress = null
                scanNow()
            }
        }
    }

    /** Asks the running copy to stop after the file it is on. */
    fun stop() { stopAsked = true }
    private val say: (String) -> Unit = { progress = it; log(it) }
    private val stopped: () -> Boolean = { stopAsked }

    private fun fs(st: Storage) = host.drives()[st] ?: error("The ${storageName(st)} isn't there any more. Plug the device in again.")

    fun setDescription(f: DeviceFile, text: String) = job("Renaming the map") {
        val u = editMap(fs(f.storage), f.storage, f.path, host.backupDir, "map name") { descriptionPatches(it, text.trim()) }
        undos += u; lastEdit = DoneEdit("The map is now called \"${text.trim()}\".", u)
        "Map name of ${f.path} changed to \"${text.trim()}\" (undo record ${u.record.path})"
    }

    fun setMapSetName(f: DeviceFile, family: MapFamily, text: String) = job("Renaming the map set") {
        val u = editMap(fs(f.storage), f.storage, f.path, host.backupDir, "map set name") { mapSetNamePatches(it, family.familyId, text.trim()) }
        undos += u; lastEdit = DoneEdit("The map set is now called \"${text.trim()}\".", u)
        "Map set ${family.familyId} in ${f.path} renamed to \"${text.trim()}\" (undo record ${u.record.path})"
    }

    fun renameFile(f: DeviceFile, newName: String) = job("Renaming the file") {
        renameMapFile(fs(f.storage), f.path, newName.trim())
        val newPath = f.path.substringBeforeLast('/', "").let { if (it.isEmpty()) newName.trim() else "$it/${newName.trim()}" }
        selected = f.copy(path = newPath)
        lastEdit = DoneEdit("Renamed ${f.name} to ${newName.trim()}.", null)
        "Renamed ${f.path} to ${newName.trim()}"
    }

    /** Where [f] would go on [to]: the same folder and name, or Garmin/ when that folder isn't one the device reads there. */
    fun moveTarget(f: DeviceFile, to: Storage): String {
        val folder = f.path.substringBeforeLast('/', "")
        val ok = folder.equals("Garmin", true) || folder.equals("Map", true)
        return (if (ok) folder else "Garmin") + "/" + f.name
    }

    fun move(f: DeviceFile, to: Storage) = job("Moving the map to the ${storageName(to)}") {
        val target = moveTarget(f, to)
        moveMap(fs(f.storage), f.path, fs(to), target, log)
        selected = f.copy(storage = to, path = target)
        lastEdit = DoneEdit("Moved ${f.name} to the ${storageName(to)}.", null)
        null
    }

    fun remove(f: DeviceFile, keepCopy: Boolean) = job("Removing the map") {
        val copy = removeMap(fs(f.storage), f.storage, f.path, if (keepCopy) host.backupDir else null, log)
        selected = null
        lastEdit = DoneEdit(if (copy != null) "Removed ${f.name}. A copy is in ${copy.parent}." else "Removed ${f.name}.", null)
        null
    }

    fun undo() {
        val u = lastEdit?.undo ?: return
        job("Undoing") {
            undoEdit(fs(u.storage), u)
            undos.remove(u); lastEdit = null
            "Undid the ${u.label} change on ${u.path}"
        }
    }

    fun dismissEdit() { lastEdit = null }

    // ---- other files ----

    /** Lets the user pick files, then shows where each would go. */
    fun addFiles() = scope.launch {
        val src = host.pickFiles()?.takeIf { it.isNotEmpty() } ?: return@launch
        adding = src
        addStorage = if (Storage.INTERNAL in storages) Storage.INTERNAL else storages.firstOrNull() ?: Storage.INTERNAL
        replan()
    }

    fun addTo(st: Storage) { addStorage = st; scope.launch { replan() } }

    private suspend fun replan() {
        val src = adding ?: return
        val st = addStorage
        addPlans = null
        addPlans = withContext(Dispatchers.IO) {
            runCatching {
                val xml = host.deviceXml()
                val profile = if (xml != null) Profiles.forDevice(xml) else Profiles.UNKNOWN_DRIVE
                val fs = host.drives()[st]
                // Plan once to learn the folders, then again knowing what is in them.
                val first = planAdd(src, st, contents, xml, profile, emptyList())
                val existing = first.mapNotNull { it.path?.substringBeforeLast('/', "") }.distinct().flatMap { dir ->
                    val real = fs?.spelling(dir) ?: return@flatMap emptyList()
                    fs.list(real).orEmpty().filter { !it.isDir }.map { if (dir.isEmpty()) it.name else "$dir/${it.name}" }
                }
                planAdd(src, st, contents, xml, profile, existing)
            }.getOrElse { e -> error = "Couldn't plan the copy: ${e.message}"; emptyList() }
        }
    }

    fun cancelAdd() { adding = null; addPlans = null }

    fun confirmAdd() {
        val plans = addPlans ?: return
        val copies = addCopies(plans)
        if (copies.isEmpty()) return
        cancelAdd()
        job("Copying files") {
            installMaps(copies, host.drives(), File(host.backupDir, "replaced"), say, stopped)
            lastEdit = DoneEdit(if (copies.size == 1) "Copied ${copies[0].name} to the ${storageName(copies[0].storage)}." else
                "Copied ${copies.size} files to the ${storageName(copies[0].storage)}.", null)
            null
        }
    }

    // ---- updates from Garmin ----

    /** Asks Garmin what there is for the device: WebUpdater's service first, Express's when that has nothing. */
    fun checkUpdates() = scope.launch {
        if (checkingUpdates) return@launch
        checkingUpdates = true; error = null
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val fs = host.drives()[Storage.INTERNAL] ?: error("Updates need the device's built-in memory.")
                val raw = fs.readBytes("Garmin/GarminDevice.xml") ?: error("The device has no Garmin/GarminDevice.xml, so Garmin can't be asked about it.")
                checkGarminUpdates(String(raw, Charsets.UTF_8), readGarminDeviceXml(raw), fs, host.updateHttp, log)
            }
        }
        r.onSuccess { c ->
            updates = c
            chosenUpdates.clear()
            // Newer versions of what is on it are ticked; device software only when the user ticks it.
            chosenUpdates += c.items.filter { it.isUpdate && it.kind != UpdateKind.FIRMWARE }.map { it.partNumber }
        }.onFailure { error = "Couldn't ask Garmin: ${it.message}"; log(error!!) }
        checkingUpdates = false
    }

    fun toggleUpdate(u: GarminUpdate) { if (u.partNumber in chosenUpdates) chosenUpdates.remove(u.partNumber) else chosenUpdates += u.partNumber }

    fun installUpdates() {
        val list = updates?.items.orEmpty().filter { it.partNumber in chosenUpdates }
        if (list.isEmpty()) return
        job("Getting software from Garmin") {
            val files = list.mapIndexed { i, u ->
                check(!stopAsked) { "stopped" }
                say("Downloading ${u.name} (${i + 1} of ${list.size})")
                u to fetchUpdate(u, host.updatesCache, { done, total ->
                    if (total > 0) progress = "Downloading ${u.name} (${i + 1} of ${list.size}), ${done * 100 / total}%"
                }, host.fetch)
            }
            installMaps(files.map { (u, f) -> MapCopy(u.name, f.length(), Storage.INTERNAL, u.target) { f.inputStream() } },
                host.drives(), File(host.backupDir, "replaced"), say, stopped)
            copiedUpdates += list.map { it.partNumber }
            chosenUpdates.removeAll(list.map { it.partNumber })
            val fw = list.any { it.kind == UpdateKind.FIRMWARE }
            lastEdit = DoneEdit("Copied ${list.size} ${if (list.size == 1) "file" else "files"} from Garmin." +
                if (fw) " The device installs its new software the next time it starts: eject it, unplug it and switch it on." else "", null)
            null
        }
    }

    // ---- backup and restore ----

    fun openBackup() = scope.launch {
        backupList = withContext(Dispatchers.IO) { runCatching { backupFiles(host.drives(), contents) }.getOrElse { error = it.message; null } }
    }

    fun closeBackup() { backupList = null }

    fun backUp(parts: Set<BackupPart>) {
        val files = backupList?.filter { it.part in parts } ?: return
        closeBackup()
        job("Backing up the device") {
            val b = backupDevice(host.drives(), files, host.deviceBackups, host.deviceXml(), say, stopped)
            lastEdit = DoneEdit("Backed up ${b.files.size} files (${app.navilink.maps.size(b.bytes)}) to ${b.dir.path}.", null)
            null
        }
    }

    fun openRestore() = scope.launch {
        backups = withContext(Dispatchers.IO) { listBackups(host.deviceBackups) }
        restoreFrom = null; restorePlan = null
    }

    fun chooseRestore(b: DeviceBackup?, parts: Set<BackupPart>) = scope.launch {
        restoreFrom = b; restorePlan = null
        if (b == null) return@launch
        restorePlan = withContext(Dispatchers.IO) { runCatching { planRestore(b, host.drives(), parts) }.getOrElse { error = it.message; null } }
    }

    fun closeRestore() { backups = null; restoreFrom = null; restorePlan = null }

    fun restore(replaceChanged: Boolean) {
        val b = restoreFrom ?: return
        val plan = restorePlan ?: return
        closeRestore()
        job("Putting the backup back") {
            val n = restoreBackup(b, plan, replaceChanged, host.drives(), File(host.backupDir, "replaced"), say, stopped)
            lastEdit = DoneEdit(if (n == 0) "The device already had every file of that backup." else "Put back $n ${if (n == 1) "file" else "files"} from the backup.", null)
            null
        }
    }
}
