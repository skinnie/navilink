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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.navilink.core.RealNet
import app.navilink.core.parseInput
import app.navilink.nuvi.Place
import app.navilink.nuvi.buildGpi
import app.navilink.nuvi.gpiFileName
import app.navilink.nuvi.placesFromFile
import app.navilink.nuvi.placesToGpx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** App state lives here so it survives the activity being recreated. */
object State {
    val places = mutableStateListOf<Place>()
    val selected = mutableStateListOf<Int>()
    val log = mutableStateListOf<String>()
    var status by mutableStateOf("Not connected")
    var device by mutableStateOf<String?>(null) // MAC address

    fun log(s: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            log.add(0, "$t  $s")
            if (log.size > 300) log.removeRange(300, log.size)
        }
    }
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
    private var incoming by mutableStateOf<Intent?>(null)
    private var tick by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        State.device = State.device ?: prefs.getString("device", null)
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            perms.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        }
        incoming = intent
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming = intent
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

    @SuppressLint("MissingPermission")
    private fun devices(): List<BluetoothDevice> = runCatching { link.bonded() }.getOrDefault(emptyList())

    @SuppressLint("MissingPermission")
    @Composable
    private fun Screen() {
        val scope = rememberCoroutineScope()
        var input by remember { mutableStateOf("") }
        var menu by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var logOpen by remember { mutableStateOf(false) }
        var category by remember { mutableStateOf(prefs.getString("category", "NaviLink")!!) }
        /** Ticked places, or all of them when none are ticked. */
        fun chosen() = if (State.selected.isEmpty()) State.places.toList() else State.selected.sorted().mapNotNull { State.places.getOrNull(it) }
        @Suppress("UNUSED_EXPRESSION") tick
        val devs = devices()
        val dev = devs.firstOrNull { it.address == State.device } ?: devs.firstOrNull { link.looksLikeGarmin(it) }

        LaunchedEffect(incoming) { incoming?.let { handle(it) }; incoming = null }

        fun bt(label: String, block: () -> Unit) {
            if (busy) return
            busy = true
            scope.launch {
                withContext(Dispatchers.IO) { runCatching(block).onFailure { State.log("$label failed: ${it.message}") } }
                State.status = link.connectedTo?.let { "Connected to $it" } ?: "Not connected"
                if (link.listening) State.status += " · listening"
                busy = false
            }
        }

        fun send(stops: List<Place>) = bt("Send") {
            if (link.connectedTo == null) link.connect(dev ?: error("Pick the nüvi first (pair it in Android Bluetooth settings)"))
            val code = link.send(stops)
            State.log(if (code == 200) "nüvi accepted ${stops.size} place(s)." else "nüvi answered: ${code ?: "nothing"}")
        }

        @Composable
        fun Controls() = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("NaviLink", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { menu = true }) { Text(dev?.let { "${it.name ?: "?"} (${it.address})" } ?: "Pick nüvi…") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (devs.isEmpty()) DropdownMenuItem(text = { Text("No paired devices (or no Bluetooth permission)") }, onClick = { menu = false })
                    devs.forEach { d ->
                        DropdownMenuItem(text = { Text("${d.name ?: "?"}  ${d.address}") }, onClick = {
                            State.device = d.address; prefs.edit().putString("device", d.address).apply(); menu = false
                        })
                    }
                }
                Text("  ${State.status}${if (busy) " …" else ""}", style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { bt("Connect") { link.connect(dev ?: error("Pick the nüvi first")) } }) { Text("Connect") }
                OutlinedButton(onClick = { bt("Disconnect") { link.disconnect() } }) { Text("Disconnect") }
                OutlinedButton(onClick = { bt("Listen") { if (link.listening) link.stopListening() else link.startListening() } }) {
                    Text(if (link.listening) "Stop GarminLink" else "Listen (GarminLink)")
                }
                OutlinedButton(onClick = { bt("Ping") { link.ping(dev ?: error("Pick the nüvi first")) } }) { Text("Ping") }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("Google Maps link, lat,lon or address") })
                TextButton(onClick = { val t = input; input = ""; scope.launch { addText(t) } }) { Text("Add") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val d = CompletableDeferred<Uri?>().also { pendingOpen = it }
                        opener.launch(arrayOf("*/*"))
                        d.await()?.let { addFile(it) }
                    }
                }) { Text("Open GPX / GPI") }
                OutlinedButton(enabled = State.places.isNotEmpty(), onClick = {
                    scope.launch {
                        val d = CompletableDeferred<Uri?>().also { pendingCreate = it }
                        creator.launch("navilink.gpx")
                        val uri = d.await() ?: return@launch
                        withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri, "wt")?.use { it.write(placesToGpx(State.places.toList())) } }
                        State.log("Saved ${State.places.size} place(s) to ${displayName(uri)}.")
                    }
                }) { Text("Save as GPX") }
                Button(enabled = State.selected.isNotEmpty(), onClick = {
                    send(State.selected.sorted().mapNotNull { State.places.getOrNull(it) })
                }) { Text("Send ${State.selected.size} as route") }
                TextButton(onClick = { State.places.clear(); State.selected.clear() }) { Text("Clear") }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(category, { category = it; prefs.edit().putString("category", it).apply() }, Modifier.weight(1f),
                    singleLine = true, label = { Text("POI category on the nüvi") })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(enabled = State.places.isNotEmpty(), onClick = {
                    scope.launch {
                        State.log("Plug the nüvi in by USB (OTG) and pick its drive (or its Garmin folder).")
                        val d = CompletableDeferred<Uri?>().also { pendingTree = it }
                        treePicker.launch(prefs.getString("usbTree", null)?.let(Uri::parse))
                        val tree = d.await() ?: return@launch
                        prefs.edit().putString("usbTree", tree.toString()).apply()
                        val ps = chosen()
                        withContext(Dispatchers.IO) { runCatching { installGpis(this@MainActivity, tree, ps, category.ifBlank { "NaviLink" }) } }
                            .onSuccess { files -> files.forEach { State.log("Wrote $it") }; State.log("Done. Eject the nüvi before unplugging; the POIs are under Custom POIs in Where To?.") }
                            .onFailure { State.log("USB install failed: ${it.message}") }
                    }
                }) { Text(if (State.selected.isEmpty()) "Install all as POIs (USB)" else "Install ${State.selected.size} as POIs (USB)") }
                OutlinedButton(enabled = State.places.isNotEmpty(), onClick = {
                    scope.launch {
                        val cat = category.ifBlank { "NaviLink" }
                        val d = CompletableDeferred<Uri?>().also { pendingCreate = it }
                        gpiCreator.launch(gpiFileName(cat))
                        val uri = d.await() ?: return@launch
                        val ps = chosen()
                        withContext(Dispatchers.IO) { runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(buildGpi(cat, ps, displayName(uri))) } } }
                            .onSuccess { State.log("Saved ${ps.size} POI(s) as ${displayName(uri)}. Copy it to Garmin/POI on the nüvi.") }
                            .onFailure { State.log("Save failed: ${it.message}") }
                    }
                }) { Text("Save as GPI") }
            }
        }

        @Composable
        fun Places(modifier: Modifier) {
            Column(modifier) {
                Text("Places (${State.places.size})", style = MaterialTheme.typography.labelLarge)
                if (State.places.isEmpty()) Text(
                    "Share a place from Google Maps to NaviLink, type one above, or open a GPX / GPI file.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    itemsIndexed(State.places) { i, p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(i in State.selected, { on -> if (on) State.selected += i else State.selected -= i })
                            Column(Modifier.weight(1f)) {
                                Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("%.5f, %.5f".format(Locale.ROOT, p.lat, p.lon) + (p.category?.let { "  · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { send(listOf(p)) }) { Text("Send") }
                        }
                    }
                }
            }
        }

        /** The log; [folded] shows only the latest line (tap "Log" to open it). */
        @Composable
        fun Log(modifier: Modifier, foldable: Boolean) {
            Column(modifier) {
                val folded = foldable && !logOpen
                if (foldable) TextButton(onClick = { logOpen = !logOpen }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (folded) "Log ▸" else "Log ▾", style = MaterialTheme.typography.labelMedium)
                } else Text("Log", style = MaterialTheme.typography.labelMedium)
                val lines = if (folded) State.log.take(1) else State.log
                LazyColumn(Modifier.fillMaxWidth(), state = rememberLazyListState()) {
                    itemsIndexed(lines) { _, l -> Text(l, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = if (folded) 1 else Int.MAX_VALUE) }
                }
            }
        }

        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
            if (maxWidth >= 840.dp) {
                // Tablet landscape: controls and log on the left, the places on the right.
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Controls()
                        HorizontalDivider()
                        Log(Modifier.weight(1f), foldable = false)
                    }
                    VerticalDivider()
                    Places(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Controls()
                    HorizontalDivider()
                    Places(Modifier.fillMaxWidth().weight(1f))
                    HorizontalDivider()
                    Log(Modifier.fillMaxWidth().heightIn(max = 180.dp), foldable = true)
                }
            }
        }
    }
}
