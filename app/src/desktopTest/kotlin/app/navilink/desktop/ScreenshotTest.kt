package app.navilink.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
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

/**
 * Renders the Maps window off-screen in its main states, for design review:
 * `./gradlew :app:desktopTest --tests '*ScreenshotTest*' -Pnl.shots=<folder>`. No display needed.
 * Uses fake device folders; the OpenTopoMap list comes from the cache or the network.
 */
class ScreenshotTest {
    private val nuviXml = """<?xml version="1.0" encoding="UTF-8"?><Device xmlns="http://www.garmin.com/xmlschemas/GarminDevice/v2">""" +
        """<Model><PartNumber>006-B0000-00</PartNumber><SoftwareVersion>460</SoftwareVersion><Description>nüvi 760</Description></Model><MassStorageMode>""" +
        """<DataType><Name>UserDataSync</Name><File><Specification><Identifier>IMG</Identifier></Specification><Location><Path>Garmin</Path><BaseName>gmapsupp</BaseName><FileExtension>img</FileExtension></Location></File></DataType>""" +
        """<DataType><Name>BaseMap</Name><File><Specification><Identifier>IMG</Identifier></Specification><Location><Path>Garmin</Path><BaseName>gmapbmap</BaseName><FileExtension>img</FileExtension></Location></File></DataType>""" +
        """</MassStorageMode></Device>"""

    @Test fun screenshots() {
        val out = File(System.getProperty("nl.shots") ?: return).apply { mkdirs() }
        val dir = Files.createTempDirectory("shots").toFile()
        val nuvi = File(dir, "NUVI760").apply { File(this, "Garmin").mkdirs(); File(this, "Garmin/GarminDevice.xml").writeText(nuviXml) }
        File(nuvi, "Garmin/gmapsupp.img").writeBytes(ByteArray(6_000_000))
        File(nuvi, "Garmin/gmapbmap.img").writeBytes(ByteArray(100_000))
        val sd = File(dir, "SDCARD").apply { File(this, "Garmin").mkdirs() }
        val built = File(defaultCache(), "out/andorra/OSM_Andorra.img").takeIf { it.isFile }
            ?: File(dir, "OSM_Andorra.img").apply { writeBytes(ByteArray(1_560_576)) }

        val nuvi660 = File(dir, "NUVI660").apply {
            File(this, "Garmin").mkdirs(); File(this, "Garmin/GarminDevice.xml").writeText(nuviXml.replace("nüvi 760", "nüvi 660"))
        }
        val m = MapsModel(CoroutineScope(SupervisorJob() + Dispatchers.Swing))
        val scene = ImageComposeScene(1280, 800, Density(1f)) { NaviTheme { MapsWindow(m) } }
        var t = 0L
        fun shot(name: String) = runBlocking {
            repeat(4) { delay(150); scene.render(t); t += 100_000_000 }
            File(out, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        fun settle() = runBlocking { delay(800) }
        fun idle() = runBlocking { repeat(150) { if (m.busy == null) return@runBlocking; delay(200) } }
        val regions = bundledRegions()

        shot("01-start-no-device")
        m.addDrive(nuvi, asSd = false); settle()
        m.addDrive(sd, asSd = true); settle()
        m.parent = "europe"
        m.pick(regions.first { it.id == "andorra" }); settle()
        shot("02-region-fits")
        m.showReady(Ready(listOf(built), "OSM_Andorra", "Andorra, built today", listOf(6324), "build:andorra"))
        shot("03-ready-to-install")
        m.install(m.plan(m.ready!!).placements.map { Copy(built, it.storage, it.path) }); settle(); idle(); settle()
        shot("04-installed")
        check(File(sd, "Garmin/gmapsupp.img").length() == built.length()) { "install did not copy the map" }
        m.addDrive(nuvi660, asSd = false); settle(); m.chooseSd(null); settle()
        m.pick(regions.first { it.id == "france" }); settle()
        shot("05-too-big-split")
        m.addDrive(nuvi, asSd = false); settle(); m.chooseSd(regions.let { m.drives.first { it.root == sd } }); settle()
        m.tab = 1
        m.chooseSource(PremadeSource.OPENTOPOMAP)
        runBlocking { repeat(100) { if (m.busy == null && m.premade.isNotEmpty()) return@runBlocking; delay(200) } }
        m.premadeSearch = "andorra"
        m.premade.firstOrNull { it.id == "otm-andorra" }?.let(m::pickPremade); settle()
        shot("06-download-picked")
        m.tab = 0
        m.chooseDevice(DeviceChoice.DataCard); settle()
        m.pick(regions.first { it.id == "andorra" }); settle()
        m.showReady(Ready(listOf(built), "OSM_Andorra", "Andorra, built today", listOf(6324), "build:andorra"))
        shot("07-datacard-ready")
        m.chooseDevice(DeviceChoice.Folder); settle()
        shot("08-folder-ready")
        scene.close()
    }
}
