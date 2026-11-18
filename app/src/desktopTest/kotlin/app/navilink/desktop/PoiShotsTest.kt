package app.navilink.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import app.navilink.core.HttpResponse
import app.navilink.core.Net
import app.navilink.nuvi.Place
import app.navilink.nuvi.buildGpi
import app.navilink.pois.*
import app.navilink.ui.AppShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import osmimg.DriveFs
import osmimg.FileDriveFs
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/** Renders the POIs page off-screen (phone and tablet): `-Pnl.shots=<folder>`. No display, no network. */
class PoiShotsTest {
    @Test fun screenshots() {
        val out = File(System.getProperty("nl.shots") ?: return).apply { mkdirs() }
        val root = Files.createTempDirectory("poishots").toFile().apply { File(this, "Garmin/POI").mkdirs(); File(this, "Map").mkdirs() }
        File(root, "Garmin/POI/iOverlander WW 27.20.gpi").writeBytes(buildGpi("Fuel Station", listOf(Place("Galp", 39.7436, -8.8071)), "iOverlander_2027_20"))
        File(root, "Map/D4935350A.img").writeBytes(ByteArray(0x200).also {
            "DSKIMG".toByteArray().copyInto(it, 0x10); "CN Europe NTU 2027.3".toByteArray().copyInto(it, 0x49); "0 Foursquare".toByteArray().copyInto(it, 0x65)
        })
        val net = object : Net {
            override fun get(url: String, headers: Map<String, String>, followRedirects: Boolean, timeoutMs: Int) = HttpResponse(200, emptyMap(),
                """{"elements":[${(0 until 40).joinToString(",") { """{"type":"node","lat":${39.0 + it * 0.01},"lon":-8.8,"tags":{"name":"P$it"}}""" }}]}""".toByteArray())
        }
        var drives = listOf<Pair<String, DriveFs>>()
        val m = PoiPacks(CoroutineScope(SupervisorJob() + Dispatchers.Swing), object : PoiHost {
            override fun drives() = drives
            override val canPick = true
            override suspend fun pickDrive() = true
            override fun deviceName() = "nüvi 3490"
        }, net)
        fun render(scene: ImageComposeScene, name: String) = runBlocking {
            repeat(6) { delay(150); androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); scene.render(it * 100_000_000L) }
            File(out, "pois-$name.png").writeBytes(scene.render(1_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        val content = @androidx.compose.runtime.Composable {
            app.navilink.ui.NaviTheme(false) { AppShell(2, {}, Modifier.fillMaxSize()) { mod -> PoiPacksScreen(m, mod) } }
        }
        val phone = ImageComposeScene(412 * 2, 892 * 2, Density(2f)) { content() }
        val tablet = ImageComposeScene(1280, 800, Density(1f)) { content() }
        render(phone, "01-phone-no-drive")
        drives = listOf("built-in memory" to FileDriveFs(root, "NUVI"))
        runBlocking { m.scanNow() }
        render(phone, "02-phone-found"); render(tablet, "02-tablet-found")
        m.run(m.chosen)
        render(phone, "03-phone-done"); render(tablet, "03-tablet-done")
        phone.close(); tablet.close()
    }
}
