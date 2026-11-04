package osmimg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceProfileTest {
    // Cut down from real GarminDevice.xml files (eTrex 10 fw 2.30, Edge 830 fw 9.75); unit IDs removed.
    // The nüvi ones are made up in the same shape.
    private fun img(type: String, base: String?, path: String = "Garmin") =
        """<DataType><Name>$type</Name><File><Specification><Identifier>IMG</Identifier></Specification>""" +
            """<Location><Path>$path</Path>${base?.let { "<BaseName>$it</BaseName>" } ?: ""}<FileExtension>img</FileExtension></Location>""" +
            """<TransferDirection>InputOutput</TransferDirection></File></DataType>"""

    private fun device(model: String, part: String, vararg types: String) = (
        """<?xml version="1.0" encoding="UTF-8"?><Device xmlns="http://www.garmin.com/xmlschemas/GarminDevice/v2" """ +
            """xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.garmin.com/xmlschemas/GarminDevice/v2 http://www.garmin.com/xmlschemas/GarminDevicev2.xsd">""" +
            """<Model><PartNumber>$part</PartNumber><SoftwareVersion>230</SoftwareVersion><Description>$model</Description></Model>""" +
            """<MassStorageMode><DataType><Name>GPSData</Name><File><Specification><Identifier>http://www.topografix.com/GPX/1/1</Identifier></Specification>""" +
            """<Location><Path>Garmin/gpx</Path><FileExtension>GPX</FileExtension></Location><TransferDirection>InputToUnit</TransferDirection></File></DataType>""" +
            types.joinToString("") +
            """<DataType><Name>CustomPOI</Name><File><Specification><Identifier>GPI0.0</Identifier></Specification>""" +
            """<Location><Path>Garmin/POI</Path><FileExtension>gpi</FileExtension></Location><TransferDirection>InputToUnit</TransferDirection></File></DataType>""" +
            """<UpdateFile><PartNumber>006-F0071-00</PartNumber><Path>Garmin</Path><FileName>gmapbmap.img</FileName></UpdateFile>""" +
            """</MassStorageMode></Device>"""
        ).toByteArray()

    private val etrex10 = device("eTrex 10", "006-B1304-00",
        img("NMaps", null), img("BaseMaps", "gmapbmap"), img("PreProgrammedMaps", "gmapprom"),
        img("SupplementalMaps", "gmapsupp"), img("TimeZoneMaps", "gmaptz"))

    private val edge830 = device("Edge 830", "006-B3122-00",
        img("NMaps", null), img("PreProgrammedMaps2", "gmapprom2"), img("BaseMaps", "gmapbmap"),
        img("SupplementalMaps", "gmapsupp"), img("PreProgrammedDEMMaps", "gmapdem"), img("PreProgrammedGeocodeMaps", "gmapgc"))

    private val nuvi760 = device("nüvi 760", "006-B0000-00", img("BaseMaps", "gmapbmap"), img("PreProgrammedMaps", "gmapprom"),
        img("SupplementalMaps", "gmapsupp"))
    private val nuvi360 = device("BMW nüvi 360", "006-B0000-01", img("SupplementalMaps", "gmapsupp"))
    private val nuvi2595 = device("nüvi 2595", "006-B0000-02", img("NMaps", null, "Map"), img("BaseMaps", "gmapbmap", ".System"),
        img("PreProgrammedMaps", "gmapprom", ".System"), img("SupplementalMaps", "gmapsupp"), img("SupplementalMaps1", "gmapsup1"))

    private fun paths(slots: List<MapSlot>) = slots.map { it.path("X") }

    @Test fun readsModelAndMapSlots() {
        val x = readGarminDeviceXml(etrex10)
        assertEquals("eTrex 10", x.model)
        assertEquals("006-B1304-00", x.partNumber)
        assertTrue(x.anyImgName)
        assertEquals("Garmin/POI", x.poiPath)
        // The UpdateFile gmapbmap.img entry is not a map slot.
        assertEquals(5, x.imgSlots.size)
    }

    @Test fun roles() {
        assertEquals(SlotRole.SUPPLEMENTAL, roleOf("GMAPSUPP"))
        assertEquals(SlotRole.SUPPLEMENTAL, roleOf("gmapsup1"))
        assertEquals(SlotRole.SECONDARY, roleOf("gmapprom1"))
        assertEquals(SlotRole.SECONDARY, roleOf("gmapoem"))
        assertEquals(SlotRole.FACTORY, roleOf("gmapprom"))
        assertNull(roleOf("gmapbmap"))
        assertNull(roleOf("gmaptz"))
    }

    @Test fun newerUnitPrefersCustomName() {
        val p = Profiles.forDevice(readGarminDeviceXml(edge830))
        assertEquals(listOf("Garmin/X.img", "Garmin/gmapsupp.img", "Garmin/gmapprom2.img"), paths(p.internal))
        assertEquals("Garmin/OSM_Portugal.img", planInstall(p, Storage.INTERNAL, "OSM Portugal", 100_000_000, emptyList()).target?.path)
        // A custom name that would hit a fixed slot or a system map is moved aside.
        assertEquals("Garmin/gmapbmap_osm.img", planInstall(p, Storage.INTERNAL, "gmapbmap", 1, emptyList()).target?.path)
        assertEquals("Garmin/GMAPSUPP_osm.img", planInstall(p, Storage.INTERNAL, "GMAPSUPP.IMG", 1, emptyList()).target?.path)
    }

    @Test fun mapFolderOnInternalAndSd() {
        val p = Profiles.forDevice(readGarminDeviceXml(nuvi2595))
        assertEquals(listOf("Map/X.img", "Garmin/gmapsupp.img", "Garmin/gmapsup1.img", ".System/gmapprom.img"), paths(p.internal))
        assertEquals(listOf("Map/X.img", "Garmin/gmapsupp.img", "Garmin/gmapsup1.img"), paths(p.sd))
        assertEquals("Map/Portugal.img", planInstall(p, Storage.SD, "Portugal", 1, emptyList()).target?.path)
    }

    @Test fun olderNuviFillsFreeSlotsInOrder() {
        val p = Profiles.forDevice(readGarminDeviceXml(nuvi760))
        assertEquals(listOf("Garmin/gmapsupp.img", "Garmin/gmapprom1.img", "Garmin/gmapoem.img", "Garmin/gmapprom.img"), paths(p.internal))
        assertEquals(listOf("Garmin/gmapsupp.img", "Garmin/gmapsup1.img"), paths(p.sd))

        val factoryOnly = listOf("Garmin/gmapbmap.img", "Garmin/gmapprom.img")
        assertEquals("Garmin/gmapsupp.img", planInstall(p, Storage.INTERNAL, "x", 1, factoryOnly).target?.path)
        // gmapsupp taken: the next slot is gmapprom1, never the factory gmapprom.
        val two = factoryOnly + "Garmin/GMAPSUPP.IMG"
        assertEquals("Garmin/gmapprom1.img", planInstall(p, Storage.INTERNAL, "x", 1, two).target?.path)
        // SD: gmapsupp taken, gmapsup1 next.
        assertEquals("Garmin/gmapsup1.img", planInstall(p, Storage.SD, "x", 1, listOf("Garmin/gmapsupp.img")).target?.path)
        // A unit with no factory map can use gmapprom, with a note.
        val empty = listOf("Garmin/gmapbmap.img", "Garmin/gmapsupp.img", "Garmin/gmapprom1.img", "Garmin/gmapoem.img")
        val plan = planInstall(p, Storage.INTERNAL, "x", 1, empty)
        assertEquals("Garmin/gmapprom.img", plan.target?.path)
        assertTrue(plan.warnings.any { "factory" in it })
        assertEquals(9, Profiles.forDevice(readGarminDeviceXml(nuvi760).copy(model = "nüvi 765")).maxMapSets)
    }

    @Test fun allSlotsTakenOffersReplacements() {
        val p = Profiles.forDevice(readGarminDeviceXml(nuvi760))
        val full = listOf("Garmin/gmapbmap.img", "Garmin/gmapsupp.img", "Garmin/gmapprom1.img", "Garmin/gmapoem.img", "Garmin/gmapprom.img")
        val plan = planInstall(p, Storage.INTERNAL, "x", 1, full)
        assertNull(plan.target)
        assertTrue(plan.problems.single().contains("no room"))
        assertEquals(listOf("Garmin/gmapsupp.img", "Garmin/gmapprom1.img", "Garmin/gmapoem.img", "Garmin/gmapprom.img"),
            plan.replaceable.map { it.path })
    }

    @Test fun twoGigabyteNuvis() {
        assertEquals(360, Profiles.nuviNumber("BMW nüvi 360"))
        assertEquals(3490, Profiles.nuviNumber("nuvi 3490"))
        assertNull(Profiles.nuviNumber("eTrex 10"))
        val p = Profiles.forDevice(readGarminDeviceXml(nuvi360))
        assertEquals(NUVI_2GB_MAX_FILE, p.maxFileBytes)
        assertNull(planInstall(p, Storage.SD, "x", 2L * 1024 * 1024 * 1024, emptyList()).target)
        assertEquals("Garmin/gmapsupp.img", planInstall(p, Storage.SD, "x", NUVI_2GB_MAX_FILE, emptyList()).target?.path)
        assertEquals(FAT32_MAX_FILE, Profiles.forDevice(readGarminDeviceXml(etrex10)).maxFileBytes)
    }

    @Test fun dataCardAndUnknownDrive() {
        val card = planInstall(Profiles.DATA_CARD, Storage.SD, "OSM Portugal", 400_000_000, listOf("anything"))
        assertEquals("gmapsupp.img", card.target?.path)
        assertEquals(Transport.CARD_UPLOAD, Profiles.DATA_CARD.transport)
        assertTrue(card.warnings.any { "replaces everything" in it })
        assertTrue(planInstall(Profiles.DATA_CARD, Storage.INTERNAL, "x", 1, emptyList()).problems.isNotEmpty())
        val sd = Profiles.forDevice(null)
        assertEquals("Garmin/gmapsupp.img", planInstall(sd, Storage.SD, "OSM Portugal", 1, emptyList()).target?.path)
    }

    @Test fun safeNames() {
        assertEquals("Mapa_de_Leiria_2026.img", safeImgName("Mapa de Leiria (2026)"))
        assertEquals("osm_map.img", safeImgName("ÓÓÓ"))
        assertEquals(32 + 4, safeImgName("x".repeat(80)).length)
    }
}

class SplitPlanTest {
    private val gb = 1024L * 1024 * 1024
    private val nuvi660 = Profiles.forDevice(
        DeviceXml("006-B0000-03", "nüvi 660", "490",
            listOf(ImgSlot("BaseMaps", "Garmin", "gmapbmap"), ImgSlot("SupplementalMaps", "Garmin", "gmapsupp")), null))

    @Test fun bigMapOnTwoGigabyteNuviUsesSdThenInternal() {
        assertEquals(3, partsNeeded(nuvi660, 5 * gb))
        val parts = listOf(1900L * 1024 * 1024, 1900L * 1024 * 1024, 1200L * 1024 * 1024)
        val plan = planSplit(nuvi660, parts, mapOf(
            Storage.SD to StorageState(emptyList(), 8 * gb),
            Storage.INTERNAL to StorageState(listOf("Garmin/gmapbmap.img", "Garmin/gmapprom.img"), 1500L * 1024 * 1024),
        ), "Europe")
        // nüvi 660: the SD card only has gmapsupp, so parts 2 and 3 must go internal, where 1.5 GB is free:
        // part 2 (1.9 GB) doesn't fit anywhere.
        assertTrue(!plan.ok && plan.problems.single().contains("part 2"), plan.problems.toString())
    }

    @Test fun spreadsOverBothStorages() {
        val parts = listOf(1900L * 1024 * 1024, 1000L * 1024 * 1024, 300L * 1024 * 1024)
        val plan = planSplit(nuvi660, parts, mapOf(
            Storage.SD to StorageState(emptyList(), 8 * gb),
            Storage.INTERNAL to StorageState(listOf("Garmin/gmapbmap.img", "Garmin/gmapprom.img"), 2 * gb),
        ), "Europe")
        assertTrue(plan.ok, plan.problems.toString())
        assertEquals(listOf(Storage.SD to "Garmin/gmapsupp.img", Storage.INTERNAL to "Garmin/gmapsupp.img",
            Storage.INTERNAL to "Garmin/gmapprom1.img"), plan.placements.map { it.storage to it.path })
        assertTrue(plan.warnings.any { "Keep the card" in it })
    }

    @Test fun tooFewSlotsOrTooBigPart() {
        val plan = planSplit(nuvi660, listOf(3 * gb), mapOf(Storage.SD to StorageState(emptyList())), "x")
        assertTrue(plan.problems.any { "takes up to" in it })
        val many = planSplit(nuvi660, List(6) { 100L }, mapOf(Storage.SD to StorageState(emptyList())), "x")
        assertTrue(!many.ok && many.problems.any { "No room for part" in it })
    }

    @Test fun anyNameDeviceNumbersTheParts() {
        val p = DeviceProfile("t", "test", Transport.DRIVE,
            internal = emptyList(), sd = listOf(MapSlot("Map", null, SlotRole.ANY_NAME)),
            maxFileBytes = 2 * gb, source = "test")
        val plan = planSplit(p, listOf(1L, 1L), mapOf(Storage.SD to StorageState(listOf("Map/Europe_1.img"))), "Europe")
        assertEquals(listOf("Map/Europe_1_new.img", "Map/Europe_2.img"), plan.placements.map { it.path })
    }
}

class AdviceTest {
    private val gb = 1024L * 1024 * 1024
    private val nuvi660 = Profiles.forDevice(
        DeviceXml("006-B0000-03", "nüvi 660", "490",
            listOf(ImgSlot("BaseMaps", "Garmin", "gmapbmap"), ImgSlot("SupplementalMaps", "Garmin", "gmapsupp")), null))
    private val internal = StorageState(listOf("Garmin/gmapbmap.img", "Garmin/gmapprom.img"), 3 * gb)

    @Test fun fitsAsOneFile() {
        val a = adviseInstall(nuvi660, mapOf(Storage.INTERNAL to internal,
            Storage.SD to StorageState(emptyList(), 4 * gb)), 300L * 1024 * 1024, "Portugal")
        val r = a.recommended as InstallOption.OneFile
        assertEquals(Storage.SD to "Garmin/gmapsupp.img", r.plan.placements.single().let { it.storage to it.path })
        assertTrue(a.sdCardFound)
        assertTrue(a.howItWorks.any { it.startsWith("SD card reads maps from: Garmin/gmapsupp.img") }, a.howItWorks.toString())
        assertTrue(a.howItWorks.any { "at most 2.0 GB" in it })
    }

    @Test fun tooBigOffersDivideThenSmallerZone() {
        val a = adviseInstall(nuvi660, mapOf(Storage.INTERNAL to internal,
            Storage.SD to StorageState(emptyList(), 4 * gb)), 3 * gb, "Iberia")
        assertEquals(2, a.options.size)
        val d = a.options[0] as InstallOption.Divide
        assertEquals(2, d.parts)
        assertEquals(listOf(Storage.SD, Storage.INTERNAL), d.plan.placements.map { it.storage })
        val z = a.options[1] as InstallOption.SmallerZone
        assertEquals(NUVI_2GB_MAX_FILE, z.maxBytes)
    }

    @Test fun noSdCardIsSaid() {
        val a = adviseInstall(nuvi660, mapOf(Storage.INTERNAL to internal), 100L * 1024 * 1024, "Leiria")
        assertTrue(!a.sdCardFound)
        assertTrue(a.howItWorks.any { it.startsWith("No SD card found") })
        assertEquals("Garmin/gmapsupp.img", (a.recommended as InstallOption.OneFile).plan.placements.single().path)
    }

    @Test fun dataCardExplainsReplacement() {
        val a = adviseInstall(Profiles.DATA_CARD,
            mapOf(Storage.SD to StorageState(emptyList(), 512L * 1024 * 1024)), 700L * 1024 * 1024, "Portugal")
        assertTrue(a.howItWorks.first().contains("replaces everything"))
        assertEquals(512L * 1024 * 1024, (a.recommended as InstallOption.SmallerZone).maxBytes)
    }
}
