package osmimg

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * One kind of map file a device declares in `Garmin/GarminDevice.xml`.
 * [baseName] null means the device reads any `*.img` in [path] (the "NMaps" entry on newer units);
 * otherwise only that exact name, e.g. `gmapsupp`.
 */
data class ImgSlot(val dataType: String, val path: String, val baseName: String?)

/**
 * One file entry under MassStorageMode/DataType: [path] relative to the drive root (as the file spells it),
 * [ext] lower case, [baseName] set for a fixed file name, [direction] InputToUnit / OutputFromUnit / InputOutput.
 */
data class DataFile(val dataType: String, val path: String, val ext: String, val baseName: String?, val direction: String) {
    val toUnit: Boolean get() = direction.equals("InputToUnit", true) || direction.equals("InputOutput", true)
}

/** A piece of Garmin software the device knows (firmware, voice, vehicle...): version 0.0 = not on it yet. */
data class UpdateFileEntry(val partNumber: String, val major: Int, val minor: Int, val path: String, val fileName: String) {
    val version: Int get() = major * 100 + minor
}

/** What `GarminDevice.xml` says about a device. The unit ID is deliberately not kept. */
data class DeviceXml(
    val partNumber: String,
    val model: String,
    val softwareVersion: String,
    val imgSlots: List<ImgSlot>,
    val poiPath: String?,
    /** BirdsEye (.jnx) and custom map (.kmz) folders the device declares, as (extension, path). */
    val rasterPaths: List<Pair<String, String>> = emptyList(),
    /** Every file entry the device declares, for installing other kinds of files. */
    val dataFiles: List<DataFile> = emptyList(),
    /** The UpdateFile list: what Garmin's update service is asked about. */
    val updateFiles: List<UpdateFileEntry> = emptyList(),
) {
    /** The device loads `*.img` files with any name. */
    val anyImgName: Boolean get() = imgSlots.any { it.baseName == null }

    /** Folder the maps go in ("Garmin" on every device seen so far). */
    val mapFolder: String get() = imgSlots.firstOrNull { it.baseName == null || it.baseName == "gmapsupp" }?.path ?: "Garmin"

    /** Fixed file names the device uses for its own maps (base map, factory map, time zones, DEM...). */
    val systemImgNames: Set<String>
        get() = imgSlots.mapNotNull { it.baseName }.filter { it.lowercase() != "gmapsupp" }.map { "${it.lowercase()}.img" }.toSet()
}

private fun Element.child(name: String): Element? {
    var n = firstChild
    while (n != null) {
        if (n is Element && (n.localName ?: n.nodeName) == name) return n
        n = n.nextSibling
    }
    return null
}

private fun Element.children(name: String): List<Element> {
    val out = mutableListOf<Element>()
    var n = firstChild
    while (n != null) {
        if (n is Element && (n.localName ?: n.nodeName) == name) out += n
        n = n.nextSibling
    }
    return out
}

private fun Element.text(name: String): String? = child(name)?.textContent?.trim()?.ifEmpty { null }

/** Reads a device's `Garmin/GarminDevice.xml`. */
fun readGarminDeviceXml(data: ByteArray): DeviceXml {
    val f = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // The file names a schema URL; never fetch it.
        // Android's parser rejects this setting (and never loads external DTDs anyway).
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
    }
    val root = f.newDocumentBuilder().parse(data.inputStream()).documentElement
    val model = root.child("Model")
    val slots = mutableListOf<ImgSlot>()
    var poi: String? = null
    val raster = mutableListOf<Pair<String, String>>()
    val files = mutableListOf<DataFile>()
    root.child("MassStorageMode")?.children("DataType")?.forEach { dt ->
        val name = dt.text("Name") ?: return@forEach
        for (file in dt.children("File")) {
            val loc = file.child("Location") ?: continue
            val ext = loc.text("FileExtension")?.lowercase()
            val path = loc.text("Path") ?: ""
            if (ext != null) files += DataFile(name, path.trim('/'), ext, loc.text("BaseName"), file.text("TransferDirection") ?: "")
            if (ext == "img" && file.child("Specification")?.text("Identifier") == "IMG") {
                slots += ImgSlot(name, path, loc.text("BaseName"))
            } else if (name == "CustomPOI" && ext == "gpi") {
                poi = path
            } else if ((ext == "jnx" || ext == "kmz") && (ext to path) !in raster) {
                raster += ext to path
            }
        }
    }
    val updates = root.child("MassStorageMode")?.children("UpdateFile").orEmpty().mapNotNull { u ->
        val v = u.child("Version")
        UpdateFileEntry(u.text("PartNumber") ?: return@mapNotNull null, v?.text("Major")?.toIntOrNull() ?: 0, v?.text("Minor")?.toIntOrNull() ?: 0,
            (u.text("Path") ?: "").trim('/'), u.text("FileName") ?: return@mapNotNull null)
    }.distinct()
    return DeviceXml(
        partNumber = model?.text("PartNumber") ?: "",
        model = model?.text("Description") ?: "",
        softwareVersion = model?.text("SoftwareVersion") ?: "",
        imgSlots = slots,
        poiPath = poi,
        rasterPaths = raster,
        dataFiles = files,
        updateFiles = updates,
    )
}
