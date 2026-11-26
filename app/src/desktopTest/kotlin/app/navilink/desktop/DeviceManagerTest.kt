package app.navilink.desktop

import app.navilink.device.DeviceHost
import app.navilink.device.DeviceManager
import kotlinx.coroutines.*
import osmimg.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** The device page's model on a copy of a test device: `-Pnl.devdir=<folder with int/ and sd/>`. */
class DeviceManagerTest {
    @Test fun editUndoRenameMoveRemove() = runBlocking {
        val src = File(System.getProperty("nl.devdir") ?: return@runBlocking)
        val dev = Files.createTempDirectory("devmgr").toFile()
        // Symlinked big files (a BirdsEye image) are left out of the copy.
        src.walk().filter { !Files.isSymbolicLink(it.toPath()) }.forEach { f ->
            val t = File(dev, f.relativeTo(src).path)
            if (f.isDirectory) t.mkdirs() else f.copyTo(t, true)
        }
        val host = object : DeviceHost {
            override fun drives() = mapOf<Storage, DriveFs>(Storage.INTERNAL to FileDriveFs(File(dev, "int")), Storage.SD to FileDriveFs(File(dev, "sd")))
            override fun deviceXml() = FileDriveFs(File(dev, "int")).deviceXml()
            override val backupDir = File(dev, "backup")
            override val updatesCache = File(dev, "updates-cache")
            override val updateHttp = UpdateHttp { url, _, _ ->
                check(url == WEBUPDATER_URL)
                "<Responses xmlns=\"http://www.garmin.com/xmlschemas/UnitSoftwareUpdate/v3\"><Response><RequestedPartNumber>006-B1304-00</RequestedPartNumber>" +
                    "<Update><Description>eTrex 10 (Webupdater)</Description><Version><VersionMajor>3</VersionMajor><VersionMinor>60</VersionMinor></Version>" +
                    "<UpdateFile><Location>http://download.garmin.com/software/eTrex10_Webupdater__360.gcd</Location></UpdateFile></Update></Response>" +
                    "<Response><RequestedPartNumber>006-D3564-06</RequestedPartNumber><Update><Description>Text, Portuguese, eTrex 10</Description>" +
                    "<Version><VersionMajor>2</VersionMajor><VersionMinor>40</VersionMinor></Version>" +
                    "<UpdateFile><Location>http://download.garmin.com/software/Text_Portuguese_eTrex10_240.exe</Location></UpdateFile></Update></Response></Responses>"
            }
            override val fetch: (String, File, (Long, Long) -> Unit) -> Unit = { url, to, _ ->
                to.writeBytes(if (url.endsWith(".gcd")) "GARMINd\u0000firmware".toByteArray() else "<gtt/>".toByteArray())
            }
            override suspend fun pickFiles() = listOf(File(dev, "in/trip.gpx"), File(dev, "in/Sintra.jnx"), File(dev, "in/notes.txt")).map { f ->
                AddSource(f.name, f.length()) { f.inputStream() }
            }
        }
        File(dev, "in").mkdirs()
        File(dev, "in/trip.gpx").writeText("<gpx/>"); File(dev, "in/Sintra.jnx").writeBytes(ByteArray(1000)); File(dev, "in/notes.txt").writeText("x")
        val m = DeviceManager(this, host)
        suspend fun idle() { m.scanNow(); while (m.busy != null) delay(20); m.scanNow() }
        m.scanNow()
        assertEquals("eTrex 10", m.model)
        val f = m.contents!!.files.single { it.path == "Garmin/lisbon.img" }
        m.select(f)
        val before = File(dev, "int/Garmin/lisbon.img").readBytes()

        m.setDescription(f, "Lisboa trilhos"); idle()
        assertNull(m.error, m.error)
        assertEquals("Lisboa trilhos", m.selected!!.details!!.description)
        m.undo(); idle()
        assertContentEquals(before, File(dev, "int/Garmin/lisbon.img").readBytes())

        m.renameFile(m.selected!!, "Lisboa.img"); idle()
        assertEquals("Garmin/Lisboa.img", m.selected!!.path)
        m.move(m.selected!!, Storage.SD); idle()
        assertNull(m.error, m.error)
        assertTrue(File(dev, "sd/Garmin/Lisboa.img").isFile && !File(dev, "int/Garmin/Lisboa.img").exists())
        assertEquals(Storage.SD, m.selected!!.storage)
        m.remove(m.selected!!, keepCopy = true); idle()
        assertFalse(File(dev, "sd/Garmin/Lisboa.img").exists())
        assertTrue(File(dev, "backup/removed").walk().any { it.name == "Lisboa.img" && it.readBytes().contentEquals(before) })
        assertNull(m.selected)

        // Other files: the GPX goes where the eTrex 10 reads GPX, the eTrex 10 lists no BirdsEye, a .txt isn't a Garmin file.
        m.addFiles().join()
        while (m.addPlans == null) delay(20)
        val plans = m.addPlans!!
        assertEquals("Garmin/gpx/trip.gpx", plans.single { it.source.name == "trip.gpx" }.path)
        assertFalse(plans.single { it.source.name == "Sintra.jnx" }.ok)
        assertFalse(plans.single { it.source.name == "notes.txt" }.ok)
        m.confirmAdd(); idle()
        assertNull(m.error, m.error)
        assertEquals("<gpx/>", File(dev, "int/Garmin/gpx/trip.gpx").readText())

        // Back up (maps and the rest), lose the track, put it back.
        m.openBackup().join()
        assertTrue(m.backupList!!.any { it.path == "Garmin/gpx/trip.gpx" })
        m.backUp(setOf(BackupPart.YOUR_MAPS, BackupPart.OTHER)); idle()
        assertNull(m.error, m.error)
        File(dev, "int/Garmin/gpx/trip.gpx").delete()
        m.openRestore().join()
        val b = m.backups!!.single()
        m.chooseRestore(b, BackupPart.entries.toSet()).join()
        assertEquals(1, m.restorePlan!!.count { it.action == RestoreAction.COPY })
        m.restore(false); idle()
        assertEquals("<gpx/>", File(dev, "int/Garmin/gpx/trip.gpx").readText())

        // Updates from Garmin: firmware is offered but not ticked; ticked, it goes to Garmin/GUPDATE.GCD.
        m.checkUpdates().join()
        assertNull(m.error, m.error)
        val fw = m.updates!!.items.single { it.kind == UpdateKind.FIRMWARE }
        assertTrue(fw.isUpdate && fw.partNumber !in m.chosenUpdates)
        val pt = m.updates!!.items.single { it.fileName == "Portuguese.gtt" }
        assertFalse(pt.installed); assertFalse(pt.partNumber in m.chosenUpdates, "things not on the device aren't ticked")
        m.toggleUpdate(fw); m.toggleUpdate(pt)
        m.installUpdates(); idle()
        assertNull(m.error, m.error)
        assertTrue(File(dev, "int/Garmin/GUPDATE.GCD").readText().startsWith("GARMINd"))
        assertEquals("<gtt/>", File(dev, "int/Garmin/Text/Portuguese.gtt").readText())
        assertTrue(fw.partNumber in m.copiedUpdates)

        // The startup message: Garmin's template on the eTrex 10, a message written after its comments.
        assertEquals(StartupText(0, ""), m.startup)
        m.changeStartup(StartupText(5, "If found: +351 900 000 000")); idle()
        assertNull(m.error, m.error)
        assertEquals(StartupText(5, "If found: +351 900 000 000"), m.startup)
        assertTrue(File(dev, "int/Garmin/startup.txt").readText().contains("<!-- Type your message on the next line -->\r\nIf found"))
        dev.deleteRecursively()
    }
}
