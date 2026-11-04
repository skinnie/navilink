package osmimg

/** How a built map reaches the device. */
enum class Transport {
    /** The device and/or its SD card show up as drives: copy the file. */
    DRIVE,

    /**
     * GPSMAP 276C/278/478-style data cards, written by the unit itself over Garmin USB with
     * `WebUpdater.exe /upload_file rgn=10 path=<img> -autorun usb /i`. The whole card is replaced.
     */
    CARD_UPLOAD,
}

/** Which storage of the device the map goes on. */
enum class Storage { INTERNAL, SD }

/** What a map slot is normally used for, which decides whether we may fill or replace it. */
enum class SlotRole {
    /** Any `*.img` name in the folder; several maps possible. */
    ANY_NAME,

    /** `gmapsupp.img`, `gmapsup1.img`...: the slots meant for extra maps. */
    SUPPLEMENTAL,

    /** `gmapprom1.img`, `gmapprom2.img`, `gmapoem.img`: secondary factory slots, usable for extra maps. */
    SECONDARY,

    /** `gmapprom.img`: the factory map. Used only when empty, or replaced on purpose after a backup. */
    FACTORY,
}

/**
 * A place a device reads a map from: [folder] relative to the drive root, and a fixed [baseName]
 * (without `.img`) or null for any name.
 */
data class MapSlot(val folder: String, val baseName: String?, val role: SlotRole, val note: String? = null) {
    fun path(wantedName: String): String {
        val file = baseName?.let { "$it.img" } ?: safeImgName(wantedName)
        return if (folder.isEmpty()) file else "$folder/$file"
    }
}

/**
 * What we know about putting map `.img` files on one kind of device.
 * Slot lists are in order of preference. [source] says where the rules come from,
 * so a forum-only rule is not mistaken for a tested one.
 */
data class DeviceProfile(
    val id: String,
    val name: String,
    val transport: Transport,
    val internal: List<MapSlot>,
    val sd: List<MapSlot>,
    /** Names never to write: base map, time zones, 3D buildings, elevation, geocoding. Lower case. */
    val systemNames: Set<String> = SYSTEM_IMG_NAMES,
    /** Largest single file the device reads, or null when only the card size limits it. */
    val maxFileBytes: Long? = FAT32_MAX_FILE,
    /** Most map sets the device loads in all (internal + SD), if known. */
    val maxMapSets: Int? = null,
    val source: String,
) {
    fun slots(storage: Storage) = if (storage == Storage.INTERNAL) internal else sd
}

const val GMAPSUPP = "gmapsupp.img"

/** FAT32's limit for one file: 4 GiB - 1. */
const val FAT32_MAX_FILE = 4L * 1024 * 1024 * 1024 - 1

/** Garmin FAQ: these nüvis do not see any file of 2 GB or more. */
const val NUVI_2GB_MAX_FILE = 2L * 1024 * 1024 * 1024 - 1

/** File names Garmin units use for maps that must stay as they are. */
val SYSTEM_IMG_NAMES: Set<String> = setOf(
    "gmapbmap.img", "gmaptz.img", "gmap3d.img", "gmap3d1.img", "gmapdem.img", "gmapdem1.img", "gmapgc.img",
)

/** Garmin FAQ "files larger than 2GB": nüvi 350, 360, 370, 600, 650, 660, 670, 680, 5000 (and BMW 350/360). */
private val NUVI_2GB_MODELS = setOf(350, 360, 370, 600, 650, 660, 670, 680, 5000)

/** The role of a fixed map file name, or null for a system map we must not touch. */
fun roleOf(baseName: String): SlotRole? {
    val b = baseName.lowercase()
    return when {
        b == "gmapsupp" || Regex("gmapsup\\d").matches(b) -> SlotRole.SUPPLEMENTAL
        Regex("gmapprom\\d").matches(b) || b == "gmapoem" -> SlotRole.SECONDARY
        b == "gmapprom" -> SlotRole.FACTORY
        else -> null
    }
}

private val OEM_NOTE = "the device always shows this map; it can't be hidden"
private val FACTORY_NOTE = "usually the factory map; used only when empty"

private fun slotOrder(s: MapSlot) = s.role.ordinal * 100 + when (s.baseName?.lowercase()) {
    null, "gmapsupp" -> 0
    "gmapoem" -> 50
    else -> s.baseName.filter { it.isDigit() }.toIntOrNull() ?: 1
}

object Profiles {
    private val GARMIN_GMAPSUPP = MapSlot("Garmin", "gmapsupp", SlotRole.SUPPLEMENTAL)

    /** GPSMAP 276C / 278 / 478: maps live on Garmin's own data card, which the unit writes. */
    val DATA_CARD = DeviceProfile(
        id = "gpsmap-datacard",
        name = "GPSMAP 276C / 278 / 478 (data card)",
        transport = Transport.CARD_UPLOAD,
        internal = emptyList(),
        sd = listOf(MapSlot("", "gmapsupp", SlotRole.SUPPLEMENTAL, "the whole data card")),
        maxFileBytes = null,
        maxMapSets = 1,
        source = "WebUpdater rgn=10 card upload (forum trick, used on 276C); the card holds one gmapsupp.img",
    )

    /** A drive with no GarminDevice.xml: an SD card in a reader, or an older unit like the GPSMAP 60CSx. */
    val UNKNOWN_DRIVE = DeviceProfile(
        id = "unknown-drive",
        name = "Garmin drive or SD card (model unknown)",
        transport = Transport.DRIVE,
        internal = listOf(GARMIN_GMAPSUPP),
        sd = listOf(GARMIN_GMAPSUPP),
        source = "safe default: Garmin/gmapsupp.img, which every Garmin that reads maps from a card loads",
    )

    /** Model number from a description like "nüvi 760", "BMW nüvi 360" or "nuvi 3490", or null. */
    fun nuviNumber(model: String): Int? {
        val m = Regex("""n[uü]vi\s*(\d{2,4})""", RegexOption.IGNORE_CASE).find(model) ?: return null
        return m.groupValues[1].toInt()
    }

    /** Profile for a device, from the GarminDevice.xml on its internal drive if it has one. */
    fun forDevice(xml: DeviceXml?): DeviceProfile {
        if (xml == null) return UNKNOWN_DRIVE
        val nuvi = nuviNumber(xml.model)
        val sources = mutableListOf("GarminDevice.xml (${xml.model.ifEmpty { xml.partNumber }})")

        val internal = mutableListOf<MapSlot>()
        for (s in xml.imgSlots) {
            if (s.baseName == null) { internal += MapSlot(s.path, null, SlotRole.ANY_NAME); continue }
            val role = roleOf(s.baseName) ?: continue
            internal += MapSlot(s.path, s.baseName.lowercase(), role, when (role) {
                SlotRole.FACTORY -> FACTORY_NOTE
                else -> if (s.baseName.lowercase() == "gmapoem") OEM_NOTE else null
            })
        }
        // The SD card has no GarminDevice.xml: it takes the same any-name folders and gmapsupp/gmapsupN in Garmin/.
        val sd = mutableListOf<MapSlot>()
        internal.filter { it.role == SlotRole.ANY_NAME }.forEach { sd += it }
        internal.filter { it.role == SlotRole.SUPPLEMENTAL }.forEach { sd += it.copy(folder = "Garmin") }
        if (sd.none { it.baseName == "gmapsupp" }) sd += GARMIN_GMAPSUPP

        val legacyNuvi = nuvi != null && nuvi < 1000
        if (legacyNuvi) {
            // Forum reports (nüvi 200, 255, 660): internal Garmin/ reads gmapsupp, gmapprom, gmapprom1 and gmapoem,
            // even where GarminDevice.xml lists fewer.
            for ((b, role) in listOf("gmapsupp" to SlotRole.SUPPLEMENTAL, "gmapprom1" to SlotRole.SECONDARY,
                "gmapoem" to SlotRole.SECONDARY, "gmapprom" to SlotRole.FACTORY)) {
                if (internal.none { it.baseName == b }) internal += MapSlot("Garmin", b, role,
                    when (b) { "gmapoem" -> OEM_NOTE; "gmapprom" -> FACTORY_NOTE; else -> null })
            }
            sources += "forum reports for older nüvis: internal gmapsupp/gmapprom1/gmapoem/gmapprom"
            if (nuvi in 200..299 || nuvi in 700..799 || nuvi == 465) {
                if (sd.none { it.baseName == "gmapsup1" }) sd += MapSlot("Garmin", "gmapsup1", SlotRole.SUPPLEMENTAL,
                    "second card map; works on European and US units")
                sources += "forum: SD Garmin/gmapsup1.img on nüvi 2xx/7xx/465 (US/EU firmware)"
            }
        }

        var maxFile = FAT32_MAX_FILE
        if (nuvi in NUVI_2GB_MODELS) {
            maxFile = NUVI_2GB_MAX_FILE
            sources += "Garmin FAQ: no files of 2 GB or more"
        }
        var maxSets: Int? = null
        if (nuvi != null && nuvi in 700..799) {
            // Forum guide written for the 7x0/7x5: 6 (7x0) or 9 (7x5) map sets in all, one of them on the SD card.
            maxSets = if (nuvi % 10 == 5) 9 else 6
            sources += "nüvi 7x0/7x5 guide: $maxSets map sets in all, one on the SD card"
        }
        return DeviceProfile(
            id = "garmin-" + xml.partNumber.ifEmpty { "unknown" }.lowercase(),
            name = xml.model.ifEmpty { "Garmin ${xml.partNumber}" },
            transport = Transport.DRIVE,
            internal = internal.sortedBy(::slotOrder).distinctBy { it.folder.lowercase() to it.baseName },
            sd = sd.sortedBy(::slotOrder).distinctBy { it.folder.lowercase() to it.baseName },
            systemNames = SYSTEM_IMG_NAMES + xml.systemImgNames.filter { roleOf(it.removeSuffix(".img")) == null },
            maxFileBytes = maxFile,
            maxMapSets = maxSets,
            source = sources.joinToString("; "),
        )
    }
}
