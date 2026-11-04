package app.navilink.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.navilink.maps.MapFile
import app.navilink.maps.MapsHost
import app.navilink.maps.PhoneMaps
import app.navilink.maps.PhoneMapsScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import osmimg.*
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The phone's Maps screen against fake drives (folders), as Android would see a nüvi over OTG.
 * Always checks the install; with -Pnl.shots=<folder> it also saves pictures of each state.
 */
class PhoneMapsTest {
    private val xml = """<?xml version="1.0" encoding="UTF-8"?><Device xmlns="http://www.garmin.com/xmlschemas/GarminDevice/v2">""" +
        """<Model><PartNumber>006-B0000-00</PartNumber><SoftwareVersion>460</SoftwareVersion><Description>nüvi 760</Description></Model><MassStorageMode>""" +
        """<DataType><Name>UserDataSync</Name><File><Specification><Identifier>IMG</Identifier></Specification><Location><Path>Garmin</Path><BaseName>gmapsupp</BaseName><FileExtension>img</FileExtension></Location></File></DataType>""" +
        """</MassStorageMode></Device>"""

    private class FakeHost(dir: File) : MapsHost {
        override val cache = File(dir, "cache")
        override val backupDir = File(dir, "backup")
        override val backupPlace = "the backup folder"
        var drive: DriveFs? = null
        var card: DriveFs? = null
        var file: MapFile? = null
        override fun remembered(card: Boolean) = if (card) this.card else drive
        override suspend fun pickDrive(card: Boolean) = if (card) this.card else drive
        override fun forget(card: Boolean) { if (card) this.card = null else drive = null }
        override suspend fun pickImg() = file
    }

    /** A small but real Garmin map: the Andorra build if there is one, else a minimal img with one map set. */
    private fun sampleImg(dir: File): File {
        File(defaultCache(), "out/andorra/OSM_Andorra.img").takeIf { it.isFile }?.let { return it }
        return File(dir, "sample.img").apply { writeBytes(syntheticImg(6324, "Sample map")) }
    }

    @Test fun phoneScreen() {
        val shots = System.getProperty("nl.shots")?.let { File(it).apply { mkdirs() } }
        val dir = Files.createTempDirectory("phone").toFile()
        val nuvi = File(dir, "NUVI").apply { File(this, "Garmin").mkdirs(); File(this, "Garmin/GarminDevice.xml").writeText(xml) }
        val sd = File(dir, "CARD").apply { File(this, "Garmin").mkdirs() }
        val img = sampleImg(dir)
        val host = FakeHost(dir)
        // Use the real site list when the desktop has it cached (no network in the test otherwise).
        File(defaultCache(), "premade/opentopomap.html").takeIf { it.isFile }?.copyTo(File(host.cache, "premade/opentopomap.html").apply { parentFile.mkdirs() })
            ?.setLastModified(System.currentTimeMillis())
        val m = PhoneMaps(CoroutineScope(SupervisorJob() + Dispatchers.Swing), host)

        var phone = ImageComposeScene(412 * 2, 892 * 2, Density(2f)) { Screen(m) }
        var t = 0L
        fun settle() = runBlocking { delay(500); repeat(100) { if (m.busy == null) return@runBlocking; delay(100) }; delay(300) }
        fun shot(name: String, scene: ImageComposeScene = phone) = runBlocking {
            // A real window pushes state changes made off the UI thread to the screen; the off-screen scene needs a nudge.
            repeat(4) { delay(100); androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); scene.render(t); t += 100_000_000 }
            val png = scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes
            shots?.let { File(it, "phone-$name.png").writeBytes(png) }
        }

        shot("01-start"); settle()
        shot("02-no-drive")
        host.drive = FileDriveFs(nuvi, "NUVI"); host.card = FileDriveFs(sd, "CARD")
        m.pickDrive(false); settle(); m.pickDrive(true); settle()
        assertEquals("nüvi 760", m.deviceName)
        runBlocking { repeat(150) { if (m.premade.isNotEmpty() && m.busy == null) return@runBlocking; delay(100) } }
        shot("03-drive-list")
        m.premade.firstOrNull { it.id == "otm-andorra" }?.let { m.pickPremade(it) }; settle()
        shot("04-premade-picked")

        // A file on the phone: goes on the card, the built-in memory keeps its map.
        m.tab = 1
        host.file = MapFile(img.name, img.length(), readImgInfo(img)) { img.inputStream() }
        m.pickOwn(); settle()
        shot("05-own-fits")
        dark = true; shot("05-own-fits-dark"); dark = false
        val r = m.ready!!
        val plan = m.plan(r)
        assertTrue(plan.ok, plan.problems.toString())
        m.installPlan(r, plan); settle()
        shot("06-installed")
        assertEquals(null, m.error)
        val placed = plan.placements.single()
        val dest = File(if (placed.storage == Storage.SD) sd else nuvi, FileDriveFs(if (placed.storage == Storage.SD) sd else nuvi).spelling(placed.path))
        assertEquals(img.length(), dest.length())
        assertTrue(File(dest.path + ".tmp").exists().not())

        // The same map again: it clashes with itself, and replacing is offered.
        m.pickOwn(); settle()
        shot("07-clash")
        val again = m.ready!!
        val swaps = m.swaps(again, m.plan(again).ok)
        assertTrue(swaps.isNotEmpty())
        m.replace(again, swaps.first().first, swaps.first().second.path); settle()
        assertTrue(host.backupDir.walk().any { it.isFile && it.length() == img.length() }, "no backup made")

        // Too big for a nüvi 660 (2 GB a file at most... and a 3 GB map).
        File(nuvi, "Garmin/GarminDevice.xml").writeText(xml.replace("nüvi 760", "nüvi 660"))
        host.card = null; m.removeCard(); settle()
        host.file = MapFile("Europe.img", 3L shl 30, readImgInfo(img)) { error("not copied") }
        m.pickOwn(); settle()
        shot("08-too-big")
        assertTrue(!m.plan(m.ready!!).ok)
        phone.close()

        // Tablet, landscape.
        m.tab = 0
        m.premade.firstOrNull { it.id == "otm-portugal" }?.let { m.pickPremade(it) }; settle()
        phone = ImageComposeScene(1280, 800, Density(1f)) { Screen(m) }
        shot("09-tablet", phone)
        phone.close()
    }

    private var dark by mutableStateOf(false)

    @androidx.compose.runtime.Composable
    private fun Screen(m: PhoneMaps) = app.navilink.ui.NaviTheme(dark) {
        app.navilink.ui.AppShell(1, {}) { mod -> PhoneMapsScreen(m, mod) }
    }

    /** The Places page with a few places, in both themes. */
    @Test fun placesScreen() {
        val shots = System.getProperty("nl.shots")?.let { File(it).apply { mkdirs() } } ?: return
        val page = FakePlaces()
        for (d in listOf(false, true)) {
            for ((name, w, h, dens) in listOf(Quad("phone", 412 * 2, 892 * 2, 2f), Quad("tablet", 1280, 800, 1f))) {
                val scene = ImageComposeScene(w, h, Density(dens)) {
                    app.navilink.ui.NaviTheme(d) { app.navilink.ui.AppShell(0, {}) { mod -> app.navilink.ui.PlacesScreen(page, mod) } }
                }
                runBlocking { repeat(3) { delay(50); scene.render(it * 100_000_000L) } }
                File(shots, "places-$name${if (d) "-dark" else ""}.png").writeBytes(scene.render(400_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                scene.close()
            }
        }
    }

    private data class Quad(val a: String, val b: Int, val c: Int, val d: Float)

    private class FakePlaces : app.navilink.ui.PlacesPage {
        override val places = listOf(
            app.navilink.nuvi.Place("Mercado do Bolhão", 41.14927, -8.60667),
            app.navilink.nuvi.Place("Serra da Estrela, Torre", 40.32170, -7.61290, category = "Hikes"),
            app.navilink.nuvi.Place("Cabo da Roca", 38.78035, -9.49875),
            app.navilink.nuvi.Place("Fábrica de Pastéis de Belém", 38.69756, -9.20317, category = "Food"),
        )
        override val selected = listOf(0, 2)
        override fun select(i: Int, on: Boolean) {}
        override var cable = false
        override val busy = false
        override val devices = listOf("nüvi 3790" to "00:1C:D7:00:00:01")
        override val device = devices.first()
        override fun chooseDevice(address: String) {}
        override val connected = true
        override val status = "Connected to nüvi 3790"
        override val listening = false
        override fun connect() {}
        override fun disconnect() {}
        override fun toggleListen() {}
        override fun ping() {}
        override val driveName: String? = null
        override fun pickDrive() {}
        override fun removeCableFile() {}
        override fun add(text: String) {}
        override fun openFile() {}
        override fun saveGpx() {}
        override fun clear() {}
        override fun send(stops: List<app.navilink.nuvi.Place>) {}
        override var category = "NaviLink"
        override fun installPois() {}
        override fun saveGpi() {}
        override val log = listOf("10:42:07  nüvi accepted the place.")
    }
}

/** A minimal Garmin img: header, a directory with one MPS file, and the MPS naming one map set. */
internal fun syntheticImg(familyId: Int, name: String): ByteArray {
    val b = ByteArray(0x1000)
    "DSKIMG".toByteArray().copyInto(b, 0x10)
    b[0x61] = 9; b[0x62] = 0 // 512-byte blocks
    "OSM sample".toByteArray().copyInto(b, 0x49)
    fun le16(o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
    fun le32(o: Int, v: Int) { le16(o, v and 0xFFFF); le16(o + 2, v ushr 16) }
    // "." entry spanning header + directory (to 0x800), then the MPS entry.
    b[0x400] = 1; le32(0x400 + 12, 0x800)
    b[0x600] = 1; "MAKEGMAP".toByteArray().copyInto(b, 0x601); "MPS".toByteArray().copyInto(b, 0x609)
    val rec = byteArrayOf('F'.code.toByte(), 0, 0, 1, 0, 0, 0) + name.toByteArray() + byteArrayOf(0)
    val d = rec.copyOf()
    d[1] = (rec.size - 3).toByte()
    d[5] = familyId.toByte(); d[6] = (familyId shr 8).toByte()
    le32(0x600 + 12, d.size); le16(0x600 + 0x20, 4) // block 4 = 0x800
    d.copyInto(b, 0x800)
    return b
}
