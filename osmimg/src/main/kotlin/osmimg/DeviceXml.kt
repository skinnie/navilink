package osmimg

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * One kind of map file a device declares in `Garmin/GarminDevice.xml`.
 * [baseName] null means the device reads any `*.img` in [path] (the "NMaps" entry on newer units);
 * otherwise only that exact name, e.g. `gmapsupp`.
 */
data class ImgSlot(val dataType: String, val path: String, val baseName: String?)

/** What `GarminDevice.xml` says about a device. The unit ID is deliberately not kept. */
data class DeviceXml(
    val partNumber: String,
    val model: String,
    val softwareVersion: String,
    val imgSlots: List<ImgSlot>,
    val poiPath: String?,
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
    root.child("MassStorageMode")?.children("DataType")?.forEach { dt ->
        val name = dt.text("Name") ?: return@forEach
        for (file in dt.children("File")) {
            val loc = file.child("Location") ?: continue
            val ext = loc.text("FileExtension")?.lowercase()
            val path = loc.text("Path") ?: ""
            if (ext == "img" && file.child("Specification")?.text("Identifier") == "IMG") {
                slots += ImgSlot(name, path, loc.text("BaseName"))
            } else if (name == "CustomPOI" && ext == "gpi") {
                poi = path
            }
        }
    }
    return DeviceXml(
        partNumber = model?.text("PartNumber") ?: "",
        model = model?.text("Description") ?: "",
        softwareVersion = model?.text("SoftwareVersion") ?: "",
        imgSlots = slots,
        poiPath = poi,
    )
}
