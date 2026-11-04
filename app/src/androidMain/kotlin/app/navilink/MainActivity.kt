package app.navilink

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import app.navilink.maps.PhoneMapsScreen
import app.navilink.ui.AppShell
import app.navilink.ui.NaviTheme
import app.navilink.ui.PlacesPage
import app.navilink.ui.PlacesScreen
import app.navilink.core.RealNet
import app.navilink.core.parseInput
import app.navilink.nuvi.CABLE_FILE
import app.navilink.nuvi.Place
import app.navilink.nuvi.Route
import app.navilink.nuvi.buildGpi
import app.navilink.nuvi.gpiFileName
import app.navilink.nuvi.placesFromFile
import app.navilink.nuvi.placesToGpx
import app.navilink.nuvi.routeName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** App state lives here so it survives the activity being recreated. */
object State {
    val places = mutableStateListOf<Place>()
    val selected = mutableStateListOf<Int>()
    val log = mutableStateListOf<String>()
    var status by mutableStateOf("Not connected")
    var device by mutableStateOf<String?>(null) // MAC address
    var cable by mutableStateOf(false)            // send by cable (USB) instead of Bluetooth
    var drive by mutableStateOf<String?>(null)    // name of the nüvi's drive last picked
    var page by mutableStateOf(0)                 // 0 = places and routes, 1 = maps

    fun log(s: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            log.add(0, "$t  $s")
            if (log.size > 300) log.removeRange(300, log.size)
        }
    }
}

/** The Maps screen's state and its Android side, kept for the life of the app (downloads go on across screens). */
object MapsApp {
    // Holds the application context only; the activity is dropped in onDestroy.
    @SuppressLint("StaticFieldLeak")
    private var host: AndroidMapsHost? = null
    private var model: app.navilink.maps.PhoneMaps? = null
    fun get(a: MainActivity): app.navilink.maps.PhoneMaps {
        val h = host ?: AndroidMapsHost(a.applicationContext).also { host = it }
        h.activity = a
        return model ?: app.navilink.maps.PhoneMaps(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main), h, State::log,
        ).also { model = it }
    }
    fun detach(a: MainActivity) { if (host?.activity === a) host?.activity = null }
}

class MainActivity : ComponentActivity() {
    private val link by lazy {
        BtLink(State::log) { route, ps ->
            android.os.Handler(mainLooper).post {
                State.places.addAll(ps)
                State.log("Added ${ps.size} place(s) from the nüvi ($route).")
            }
        }
    }
    private val prefs by lazy { getSharedPreferences("navilink", Context.MODE_PRIVATE) }

    private var pendingOpen: CompletableDeferred<Uri?>? = null
    private var pendingCreate: CompletableDeferred<Uri?>? = null
    private val opener = registerForActivityResult(ActivityResultContracts.OpenDocument()) { pendingOpen?.complete(it) }
    private val creator = registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { pendingCreate?.complete(it) }
    private val gpiCreator = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { pendingCreate?.complete(it) }
    private var pendingTree: CompletableDeferred<Uri?>? = null
    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { pendingTree?.complete(it) }
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    /** Shares and opened files, handled in order (several can arrive before the screen is up). */
    private val incoming = Channel<Intent>(Channel.UNLIMITED)
    private var tick by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        State.device = State.device ?: prefs.getString("device", null)
        State.cable = prefs.getBoolean("cable", false)
        State.drive = State.drive ?: prefs.getString("usbName", null)
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            perms.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        }
        incoming.trySend(intent)
        category = prefs.getString("category", "NaviLink")!!
        setContent { NaviTheme { Root() } }
    }

    override fun onDestroy() {
        MapsApp.detach(this)
        super.onDestroy()
    }

    /** Android's folder picker; the answer is kept across restarts. Null when cancelled. */
    suspend fun pickTree(initial: Uri?): Uri? {
        val d = CompletableDeferred<Uri?>().also { pendingTree = it }
        treePicker.launch(initial)
        val tree = d.await() ?: return null
        runCatching {
            contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        return tree
    }

    suspend fun pickFile(types: Array<String>): Uri? {
        val d = CompletableDeferred<Uri?>().also { pendingOpen = it }
        opener.launch(types)
        return d.await()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming.trySend(intent)
    }

    private fun refresh() { tick++ }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"

    private suspend fun addText(text: String) {
        State.log("Reading: ${text.take(120)}")
        val r = withContext(Dispatchers.IO) { runCatching { parseInput(text, RealNet()) } }
        r.onFailure { State.log("Could not read that: ${it.message}") }
        r.onSuccess { res ->
            res.warnings.forEach { State.log("Note: $it") }
            res.points.forEach { State.places += Place(it.name, it.lat, it.lon) }
            State.log("Added ${res.points.size} place(s).")
        }
    }

    private suspend fun addFile(uri: Uri) {
        val name = displayName(uri)
        val r = withContext(Dispatchers.IO) {
            runCatching { placesFromFile(name, contentResolver.openInputStream(uri)!!.use { it.readBytes() }) }
        }
        r.onFailure { State.log("Could not read $name: ${it.message}") }
        r.onSuccess { State.places += it; State.log("Added ${it.size} place(s) from $name.") }
    }

    /** Text, geo: links and files shared or opened from other apps. */
    private suspend fun handle(i: Intent) {
        val text = i.getStringExtra(Intent.EXTRA_TEXT)
        @Suppress("DEPRECATION")
        val stream = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        when {
            i.action == Intent.ACTION_SEND && text != null -> addText(text)
            i.action == Intent.ACTION_SEND && stream != null -> addFile(stream)
            i.action == Intent.ACTION_VIEW && i.data?.scheme == "geo" -> addText(geoToText(i.data!!))
            i.action == Intent.ACTION_VIEW && i.data != null -> addFile(i.data!!)
        }
    }

    /** geo:lat,lon?q=lat,lon(Label) or geo:0,0?q=address -> something parseInput understands. */
    private fun geoToText(u: Uri): String {
        val q = u.getQueryParameter("q")
        val ll = u.schemeSpecificPart.substringBefore('?')
        return when {
            q != null && Regex("""^\s*-?\d+(\.\d+)?\s*,\s*-?\d+(\.\d+)?""").containsMatchIn(q) -> q.substringBefore('(').trim()
            q != null -> q
            else -> ll
        }
    }

    /** The nüvi's drive: the one picked before if it's plugged in, otherwise ask (and remember the answer). */
    private suspend fun drive(ask: Boolean = false): Uri? {
        val saved = prefs.getString("usbTree", null)?.let(Uri::parse)
        if (!ask && saved != null && withContext(Dispatchers.IO) { driveReady(this@MainActivity, saved) }) return saved
        State.log("Plug the nüvi in with a USB OTG cable, wait for Android to show it, then pick its drive.")
        val tree = pickTree(saved) ?: return null
        val name = withContext(Dispatchers.IO) { driveName(this@MainActivity, tree) }
        prefs.edit().putString("usbTree", tree.toString()).putString("usbName", name).apply()
        State.drive = name
        return tree
    }

    @SuppressLint("MissingPermission")
    private fun devices(): List<BluetoothDevice> = runCatching { link.bonded() }.getOrDefault(emptyList())

    /** Long work, one job at a time; may open pickers, so it starts on the main thread. */
    private var busy by mutableStateOf(false)
    private var category by mutableStateOf("NaviLink")

    private fun job(label: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            runCatching { block() }.onFailure { State.log("$label failed: ${it.message}") }
            State.status = link.connectedTo?.let { "Connected to $it" } ?: "Not connected"
            if (link.listening) State.status += " · listening"
            busy = false
        }
    }

    private fun bt(label: String, block: () -> Unit) = job(label) { withContext(Dispatchers.IO) { block() } }

    /** Ticked places, or all of them when none are ticked. */
    private fun chosen() = if (State.selected.isEmpty()) State.places.toList() else State.selected.sorted().mapNotNull { State.places.getOrNull(it) }

    @SuppressLint("MissingPermission")
    private fun currentDevice(): BluetoothDevice? {
        val devs = devices()
        return devs.firstOrNull { it.address == State.device } ?: devs.firstOrNull { link.looksLikeGarmin(it) }
    }

    /** The Places page on Android: Bluetooth through [link], the cable through the picked drive. */
    private val places = object : PlacesPage {
        override val places get() = State.places
        override val selected get() = State.selected
        override fun select(i: Int, on: Boolean) { if (on) State.selected += i else State.selected -= i }
        override var cable: Boolean
            get() = State.cable
            set(v) { State.cable = v; prefs.edit().putBoolean("cable", v).apply() }
        override val busy get() = this@MainActivity.busy
        @get:SuppressLint("MissingPermission")
        override val devices get() = run { @Suppress("UNUSED_EXPRESSION") tick; devices().map { (it.name ?: "?") to it.address } }
        @get:SuppressLint("MissingPermission")
        override val device get() = run { @Suppress("UNUSED_EXPRESSION") tick; currentDevice()?.let { (it.name ?: "?") to it.address } }
        override fun chooseDevice(address: String) { State.device = address; prefs.edit().putString("device", address).apply(); refresh() }
        override val connected get() = State.status.startsWith("Connected")
        override val status get() = State.status
        override val listening get() = State.status.contains("listening")
        override fun connect() = bt("Connect") { link.connect(currentDevice() ?: error("Pick the nüvi first")) }
        override fun disconnect() = bt("Disconnect") { link.disconnect() }
        override fun toggleListen() = bt("Listen") { if (link.listening) link.stopListening() else link.startListening() }
        override fun ping() = bt("Ping") { link.ping(currentDevice() ?: error("Pick the nüvi first")) }
        override val driveName get() = State.drive
        override fun pickDrive() = job("Pick drive") { drive(ask = true) }
        override fun removeCableFile() = job("Remove") {
            val tree = drive() ?: return@job
            val gone = withContext(Dispatchers.IO) { removeCableFile(this@MainActivity, tree) }
            State.log(if (gone) "Removed Garmin/GPX/$CABLE_FILE." else "There's no $CABLE_FILE on the nüvi.")
        }
        override fun add(text: String) { lifecycleScope.launch { addText(text) } }
        override fun openFile() {
            lifecycleScope.launch { pickFile(arrayOf("*/*"))?.let { addFile(it) } }
        }
        override fun saveGpx() {
            lifecycleScope.launch {
                val d = CompletableDeferred<Uri?>().also { pendingCreate = it }
                creator.launch("navilink.gpx")
                val uri = d.await() ?: return@launch
                withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri, "wt")?.use { it.write(placesToGpx(State.places.toList())) } }
                State.log("Saved ${State.places.size} place(s) to ${displayName(uri)}.")
            }
        }
        override fun clear() { State.places.clear(); State.selected.clear() }
        override fun send(stops: List<Place>) = when {
            !State.cable -> bt("Send") {
                if (link.connectedTo == null) link.connect(currentDevice() ?: error("Pick the nüvi first (pair it in Android Bluetooth settings)"))
                val code = link.send(stops)
                State.log(if (code == 200) "nüvi accepted ${if (stops.size == 1) "the place" else "${stops.size} places"}." else "nüvi answered: ${code ?: "nothing"}")
            }
            stops.size == 1 -> cableSend(stops, null)
            else -> cableSend(emptyList(), Route(routeName(stops), stops))
        }
        override var category: String
            get() = this@MainActivity.category
            set(v) { this@MainActivity.category = v; prefs.edit().putString("category", v).apply() }
        override fun installPois() = job("USB install") {
            val tree = drive() ?: return@job
            val ps = chosen()
            val files = withContext(Dispatchers.IO) { installGpis(this@MainActivity, tree, ps, category.ifBlank { "NaviLink" }) }
            files.forEach { State.log("Wrote $it") }
            State.log("Done. Eject the nüvi before unplugging; the POIs are under Custom POIs in Where To?.")
        }
        override fun saveGpi() {
            lifecycleScope.launch {
                val cat = category.ifBlank { "NaviLink" }
                val d = CompletableDeferred<Uri?>().also { pendingCreate = it }
                gpiCreator.launch(gpiFileName(cat))
                val uri = d.await() ?: return@launch
                val ps = chosen()
                withContext(Dispatchers.IO) { runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(buildGpi(cat, ps, displayName(uri))) } } }
                    .onSuccess { State.log("Saved ${ps.size} POI(s) as ${displayName(uri)}. Copy it to Garmin/POI on the nüvi.") }
                    .onFailure { State.log("Save failed: ${it.message}") }
            }
        }
        override val log get() = State.log
    }

    private fun cableSend(places: List<Place>, route: Route?) = job("Send by cable") {
        val tree = drive() ?: return@job
        val f = withContext(Dispatchers.IO) { sendByCable(this@MainActivity, tree, places, route) }
        val what = route?.let { "route \"${it.name}\" (${it.stops.size} stops)" } ?: places.singleOrNull()?.name ?: "${places.size} places"
        State.log("Wrote $what to Garmin/GPX/$CABLE_FILE (now ${f.places.size} place(s), ${f.routes.size} route(s)).")
        State.log("Eject the nüvi and unplug it. When it starts, places go to Saved and routes to Trip Planner.")
    }

    /** Places / Maps, in the shared NaviLink look. */
    @Composable
    private fun Root() {
        // Shares and opened files go to the places page, whichever page is up.
        LaunchedEffect(Unit) {
            for (i in incoming) {
                if (i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_VIEW) State.page = 0
                handle(i)
            }
        }
        AppShell(State.page, { State.page = it }, Modifier.safeDrawingPadding()) { mod ->
            if (State.page == 1) PhoneMapsScreen(MapsApp.get(this@MainActivity), mod)
            else PlacesScreen(places, mod)
        }
    }
}
