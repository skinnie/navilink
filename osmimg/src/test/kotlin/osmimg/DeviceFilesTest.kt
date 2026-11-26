package osmimg

import java.io.File
import java.nio.file.Files
import kotlin.test.*

private fun entry(name: String, path: String, ext: String, dir: String = "InputToUnit", base: String? = null) =
    "<DataType><Name>$name</Name><File>${if (ext == "img") "<Specification><Identifier>IMG</Identifier></Specification>" else ""}<Location><Path>$path</Path>${base?.let { "<BaseName>$it</BaseName>" } ?: ""}" +
        "<FileExtension>$ext</FileExtension></Location><TransferDirection>$dir</TransferDirection></File></DataType>"

private fun deviceXml(model: String, vararg entries: String) = readGarminDeviceXml(("""<?xml version="1.0" encoding="UTF-8"?>
<Device xmlns="http://www.garmin.com/xmlschemas/GarminDevice/v2"><Model><PartNumber>006-B1-00</PartNumber><Description>$model</Description></Model>
<MassStorageMode>""" + entries.joinToString("") + "</MassStorageMode></Device>").toByteArray())

/** Like a nüvi 3790's: folders at the drive root, no BirdsEye. */
private val NUVI = deviceXml("nüvi 3790",
    entry("GPSData", "GPX", "gpx"), entry("GPSData", "GPX", "gpx", "OutputFromUnit", "Current"),
    entry("CustomPOI", "POI", "gpi"), entry("Voices", "Voice", "vpm", "InputOutput"), entry("Vehicle", "Vehicle", "srf"),
    entry("NMaps", "Map", "img", "InputOutput"), entry("ASR", ".System/ASR", "asr", "InputOutput"))

/** Like an Edge's: everything new goes to Garmin/NewFiles, photos have two folders. */
private val EDGE = deviceXml("Edge 1040",
    entry("FIT_TYPE_6", "Garmin/Courses", "FIT", "OutputFromUnit"), entry("FIT_TYPE_6", "Garmin/NewFiles", "FIT"),
    entry("GPSData", "Garmin/NewFiles", "GPX"), entry("BirdsEye", "Garmin/BirdsEye", "JNX", "InputOutput"),
    entry("IQWidgets", "GARMIN/APPS", "PRG", "InputOutput"),
    entry("GeotaggedPhotos", "Pictures", "jpg", "InputOutput"), entry("GeotaggedPhotos", "Garmin/JPEG", "jpg", "InputOutput"))

class DeviceFilesTest {
    private val tmp = Files.createTempDirectory("files").toFile()
    @AfterTest fun clean() { tmp.deleteRecursively() }

    @Test fun foldersComeFromTheDevicesOwnList() {
        assertEquals("GPX", addFolder(AddKind.GPX, NUVI))
        assertEquals("POI", addFolder(AddKind.POI, NUVI))
        assertEquals("Voice", addFolder(AddKind.VOICE, NUVI))
        assertNull(addFolder(AddKind.BIRDSEYE, NUVI), "the nüvi doesn't list BirdsEye")
        assertEquals("Garmin/NewFiles", addFolder(AddKind.FIT, EDGE), "the folder the Edge takes files in, not the one it writes")
        assertEquals("Garmin/NewFiles", addFolder(AddKind.GPX, EDGE))
        assertEquals("GARMIN/APPS", addFolder(AddKind.CONNECT_IQ, EDGE))
        assertEquals("Garmin/JPEG", addFolder(AddKind.PHOTO, EDGE))
        assertEquals("Garmin/GPX", addFolder(AddKind.GPX, null), "no GarminDevice.xml: the usual folder")
        assertEquals(AddKind.BIRDSEYE, AddKind.of("Sintra.JNX"))
        assertNull(AddKind.of("notes.txt"))
        assertEquals("a_b.gpx", safeFileName("a:b?.GPX"))
    }

    @Test fun plansEachFileAndSaysWhatStandsInTheWay() {
        val map = File(tmp, "in/lisbon.img").also { fakeImg(it, family = 4900, tiles = listOf(T("63240001"))) }
        fun src(name: String, bytes: Long = 100, d: ImgDetails? = null) = AddSource(name, bytes, d) { ByteArray(bytes.toInt()).inputStream() }
        val files = listOf(src("trip.gpx"), src("cams.gpi"), src("Sintra.jnx"), src("notes.txt"), src("lisbon.img", map.length(), readImgDetails(map)),
            src("trip.gpx"))
        val p = planAdd(files, Storage.INTERNAL, null, NUVI, Profiles.forDevice(NUVI), listOf("GPX/trip.gpx"))
        assertEquals("GPX/trip.gpx", p[0].path); assertTrue(p[0].ok && p[0].replaces)
        assertEquals("POI/cams.gpi", p[1].path); assertTrue(p[1].ok && !p[1].replaces)
        assertFalse(p[2].ok); assertTrue("doesn't list .jnx" in p[2].problems.single(), p[2].problems.toString())
        assertFalse(p[3].ok)
        assertTrue(p[4].ok, p[4].problems.toString()); assertEquals("Map/lisbon.img", p[4].path)
        assertTrue(p[5].replaces, "the second trip.gpx would replace the first")

        // The card: GPX and POIs under Garmin/, voices not at all.
        val c = planAdd(listOf(src("cams.gpi"), src("Joana.vpm")), Storage.SD, null, NUVI, Profiles.forDevice(NUVI), emptyList())
        assertEquals("Garmin/POI/cams.gpi", c[0].path); assertTrue(c[0].warnings.any { "not checked" in it })
        assertFalse(c[1].ok)
    }

    @Test fun warnsWhenTheSameMapIsAlreadyThere() {
        val dev = File(tmp, "dev").also { File(it, "Garmin").mkdirs() }
        File(dev, "Garmin/GarminDevice.xml").writeText("""<?xml version="1.0"?><Device xmlns="http://www.garmin.com/xmlschemas/GarminDevice/v2"><Model><Description>eTrex 10</Description></Model><MassStorageMode>${entry("NMaps", "Garmin", "img", "InputOutput")}</MassStorageMode></Device>""")
        fakeImg(File(dev, "Garmin/old.img"), family = 4900, tiles = listOf(T("63240001")))
        val incoming = File(tmp, "new.img").also { fakeImg(it, family = 4900, desc = "Other", tiles = listOf(T("63240002"))) }
        val xml = FileDriveFs(dev).deviceXml()!!
        val c = scanDevice(mapOf(Storage.INTERNAL to FileDriveFs(dev)), Profiles.forDevice(xml), xml.model, xml.rasterPaths)
        val p = planAdd(listOf(AddSource("new.img", incoming.length(), readImgDetails(incoming)) { incoming.inputStream() }),
            Storage.INTERNAL, c, xml, Profiles.forDevice(xml), c.files.map { it.path })
        assertTrue(p.single().ok)
        assertTrue(p.single().warnings.any { "same map ID (4900)" in it }, p.single().warnings.toString())
    }

    @Test fun backsUpAndPutsBack() {
        val int = File(tmp, "int"); val sd = File(tmp, "sd")
        File(int, "Garmin").mkdirs()
        File(int, "Garmin/GarminDevice.xml").writeText("""<?xml version="1.0"?><Device xmlns="http://www.garmin.com/xmlschemas/GarminDevice/v2"><Model><PartNumber>006-B1304-00</PartNumber><Description>eTrex 10</Description></Model><MassStorageMode>${entry("NMaps", "Garmin", "img", "InputOutput")}${entry("BaseMaps", "Garmin", "img", "InputOutput", "gmapbmap")}</MassStorageMode></Device>""")
        fakeImg(File(int, "Garmin/gmapbmap.img"), family = 1, tiles = listOf(T("00000001")))
        fakeImg(File(sd, "Garmin/lisbon.img"), family = 4900, tiles = listOf(T("63240001")))
        File(int, "Garmin/GPX").mkdirs(); File(int, "Garmin/GPX/trip.gpx").writeText("<gpx>one</gpx>")
        File(int, "Garmin/GPX/half.gpx.tmp").writeText("x")
        File(int, "System Volume Information").mkdirs(); File(int, "System Volume Information/IndexerVolumeGuid").writeText("pc")
        val drives = mapOf<Storage, DriveFs>(Storage.INTERNAL to FileDriveFs(int), Storage.SD to FileDriveFs(sd))
        val xml = drives.getValue(Storage.INTERNAL).deviceXml()!!
        val c = scanDevice(drives, Profiles.forDevice(xml), xml.model, xml.rasterPaths)

        val files = backupFiles(drives, c)
        assertEquals(setOf("Garmin/GarminDevice.xml", "Garmin/gmapbmap.img", "Garmin/GPX/trip.gpx", "Garmin/lisbon.img"), files.map { it.path }.toSet())
        assertEquals(BackupPart.DEVICE_MAPS, files.single { it.path.endsWith("gmapbmap.img") }.part)
        assertEquals(BackupPart.YOUR_MAPS, files.single { it.path.endsWith("lisbon.img") }.part)
        assertEquals(BackupPart.OTHER, files.single { it.path.endsWith("trip.gpx") }.part)

        val root = File(tmp, "backups")
        backupDevice(drives, files, root, xml, {})
        val b = listBackups(root).single()
        assertEquals("eTrex 10", b.model); assertEquals(4, b.files.size)

        // Lose the card's map, change the track: the map comes back, the track only when asked.
        File(sd, "Garmin/lisbon.img").delete()
        File(int, "Garmin/GPX/trip.gpx").writeText("<gpx>two</gpx>")
        val plan = planRestore(b, drives, BackupPart.entries.toSet())
        assertEquals(RestoreAction.COPY, plan.single { it.file.path.endsWith("lisbon.img") }.action)
        assertEquals(RestoreAction.CHANGED, plan.single { it.file.path.endsWith("trip.gpx") }.action)
        assertEquals(RestoreAction.SAME, plan.single { it.file.path.endsWith("gmapbmap.img") }.action)
        assertEquals(1, restoreBackup(b, plan, false, drives, File(tmp, "safety"), {}))
        assertContentEquals(b.file(b.files.single { it.path.endsWith("lisbon.img") }).readBytes(), File(sd, "Garmin/lisbon.img").readBytes())
        assertEquals("<gpx>two</gpx>", File(int, "Garmin/GPX/trip.gpx").readText())
        assertEquals(1, restoreBackup(b, planRestore(b, drives, setOf(BackupPart.OTHER)), true, drives, File(tmp, "safety"), {}))
        assertEquals("<gpx>one</gpx>", File(int, "Garmin/GPX/trip.gpx").readText())
        assertTrue(File(tmp, "safety").walk().any { it.name == "trip.gpx" && it.readText() == "<gpx>two</gpx>" }, "the device's version is kept")

        // A stopped backup leaves no list, so it isn't offered.
        runCatching { backupDevice(drives, files, root, xml, {}, cancelled = { true }) }
        assertEquals(1, listBackups(root).size)
    }
}
