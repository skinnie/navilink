package app.navilink.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.navilink.core.RealNet
import app.navilink.core.USER_AGENT
import app.navilink.device.DeviceHost
import app.navilink.device.DeviceManager
import app.navilink.device.DeviceScreen
import app.navilink.ui.navi
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import osmimg.*
import java.io.File
import kotlin.test.Test

/**
 * Renders the "On the device" page off-screen: `-Pnl.shots=<folder> -Pnl.devdir=<folder with int/ and sd/>`.
 * With `-Pnl.tiles=<folder>` the coverage map gets OpenStreetMap tiles (fetched once, cached there).
 */
class DeviceShotsTest {
    @Test fun screenshots() {
        val out = File(System.getProperty("nl.shots") ?: return).apply { mkdirs() }
        val dev = File(System.getProperty("nl.devdir") ?: return)
        val tiles = System.getProperty("nl.tiles")?.let(::File)
        var phoneMode = false   // the phone: drives chosen with the picker, none chosen in the first shot
        var noDrives = false
        val host = object : DeviceHost {
            override val canPick get() = phoneMode
            override fun drives() = if (noDrives) emptyMap() else buildMap<Storage, DriveFs> {
                put(Storage.INTERNAL, FileDriveFs(File(dev, "int"), "GARMIN"))
                File(dev, "sd").takeIf { it.isDirectory }?.let { put(Storage.SD, FileDriveFs(it, "SD CARD")) }
            }
            override fun deviceXml() = FileDriveFs(File(dev, "int")).deviceXml()
            override val backupDir = File(out, "backup")
            override val deviceBackups = File(out, "backup/devices")
            override suspend fun pickFiles() = listOf("Lisbon trails.gpx" to 48_000L, "radars.gpi" to 120_000L, "Sintra.jnx" to 42_000_000L, "notes.txt" to 10L)
                .map { (n, b) -> AddSource(n, b) { ByteArray(0).inputStream() } }
            override suspend fun mapTile(z: Int, x: Int, y: Int): ImageBitmap? = withContext(Dispatchers.IO) {
                val t = tiles ?: return@withContext null
                val f = File(t, "$z/$x/$y.png")
                if (!f.isFile) runCatching {
                    val r = RealNet().get("https://tile.openstreetmap.org/$z/$x/$y.png", mapOf("User-Agent" to USER_AGENT))
                    if (r.code == 200) { f.parentFile.mkdirs(); f.writeBytes(r.body) }
                }
                runCatching { org.jetbrains.skia.Image.makeFromEncoded(f.readBytes()).toComposeImageBitmap() }.getOrNull()
            }
        }
        val m = DeviceManager(CoroutineScope(SupervisorJob() + Dispatchers.Swing), host)
        fun render(scene: ImageComposeScene, name: String) = runBlocking {
            repeat(12) { delay(250); androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); scene.render(it * 100_000_000L) }
            File(out, "device-$name.png").writeBytes(scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        val content = @androidx.compose.runtime.Composable {
            app.navilink.ui.NaviTheme(false) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(navi.background)) {
                    DeviceScreen(m, host, Modifier.fillMaxSize().padding(24.dp))
                }
            }
        }
        val desk = ImageComposeScene(1280, 900, Density(1f)) { content() }
        val phone = ImageComposeScene(412 * 2, 892 * 2, Density(2f)) {
            app.navilink.ui.NaviTheme(false) { app.navilink.ui.AppShell(3, {}) { mod -> DeviceScreen(m, host, mod) } }
        }
        runBlocking { m.scanNow() }
        render(desk, "01-desktop")
        val pick = m.contents!!.maps.first { it.readByDevice != false && it.kind == FileKind.MAP && it.details?.tiles?.isNotEmpty() == true }
        m.select(pick)
        render(desk, "02-desktop-selected")
        runBlocking(Dispatchers.Swing) { m.addFiles().join() }
        runBlocking { while (m.addPlans == null) delay(20) }
        render(desk, "06-add-files"); m.cancelAdd()
        runBlocking(Dispatchers.Swing) { m.openBackup().join() }
        render(desk, "07-backup"); m.closeBackup()
        runBlocking(Dispatchers.Swing) { m.checkUpdates().join() }
        m.select(null)
        val tall = ImageComposeScene(1280, 1800, Density(1f)) { content() }
        render(tall, "08-updates"); tall.close()
        phoneMode = true; noDrives = true; m.select(null); runBlocking { m.scanNow() }
        render(phone, "03-phone-choose")
        noDrives = false; runBlocking { m.scanNow() }
        render(phone, "04-phone-list")
        m.select(m.contents!!.files.first { it.path == pick.path && it.storage == pick.storage })
        render(phone, "05-phone-selected")
        desk.close(); phone.close()
    }
}
