package osmimg

import java.io.File
import java.nio.file.Files
import kotlin.test.*

private val XML = """<?xml version="1.0" encoding="UTF-8"?><Device xmlns="http://www.garmin.com/xmlschemas/GarminDevice/v2">
<Model><PartNumber>006-B1304-00</PartNumber><SoftwareVersion>230</SoftwareVersion><Description>eTrex 10</Description></Model><Id>3999999999</Id>
<MassStorageMode>
<UpdateFile><PartNumber>006-D3564-06</PartNumber><Version><Major>2</Major><Minor>20</Minor></Version><Path>Garmin/Text</Path><FileName>Portuguese.gtt</FileName></UpdateFile>
<UpdateFile><PartNumber>006-D3564-07</PartNumber><Version><Major>0</Major><Minor>0</Minor></Version><Path>Garmin/Text</Path><FileName>Danish.gtt</FileName></UpdateFile>
<UpdateFile><PartNumber>006-D0323-11</PartNumber><Version><Major>0</Major><Minor>0</Minor></Version><Path>Garmin</Path><FileName>gmapprom.img</FileName></UpdateFile>
<UpdateFile><PartNumber>006-B1304-00</PartNumber><Version><Major>2</Major><Minor>30</Minor></Version><Path>Garmin</Path><FileName>GUPDATE.GCD</FileName></UpdateFile>
</MassStorageMode><Extensions><DeviceExtension><Ifix>123456789</Ifix></DeviceExtension></Extensions></Device>"""

private fun offer(pn: String, desc: String, maj: Int, min: String, url: String) =
    "<Response><RequestedPartNumber>$pn</RequestedPartNumber><LicenseLocation>http://www8.garmin.com/gcdfiles/license.txt</LicenseLocation>" +
        "<Update><Description>$desc</Description><Version><VersionMajor>$maj</VersionMajor><VersionMinor>$min</VersionMinor></Version>" +
        "<Notes>%3Cli%3ECharge+the+batteries.%3C%2Fli%3E</Notes><UpdateFile><Location>$url</Location><Size>4</Size><MD5Sum>abc</MD5Sum></UpdateFile></Update></Response>"

class GarminUpdatesTest {
    private val tmp = Files.createTempDirectory("upd").toFile()
    @AfterTest fun clean() { tmp.deleteRecursively() }

    private fun device(): FileDriveFs {
        File(tmp, "dev/Garmin/Text").mkdirs()
        File(tmp, "dev/Garmin/GarminDevice.xml").writeText(XML)
        File(tmp, "dev/Garmin/Text/Portuguese.gtt").writeText("<gtt/>")
        return FileDriveFs(File(tmp, "dev"))
    }

    @Test fun asksWebUpdaterWithPartNumbersOnlyAndNeverForLicensedMaps() {
        val fs = device()
        val xml = fs.deviceXml()!!
        val sent = mutableListOf<String>()
        val http = UpdateHttp { url, _, body ->
            sent += java.net.URLDecoder.decode(String(body), "UTF-8"); assertEquals(WEBUPDATER_URL, url)
            "<Responses xmlns=\"http://www.garmin.com/xmlschemas/UnitSoftwareUpdate/v3\">" +
                offer("006-B1304-00", "eTrex 10 (Webupdater)", 3, "60", "http://download.garmin.com/software/eTrex10_Webupdater__360.gcd") +
                offer("006-D3564-06", "Text, Portuguese, eTrex 10", 2, "40", "http://download.garmin.com/software/Text_Portuguese_eTrex10_240.exe") +
                offer("006-D3564-07", "Text, Danish, eTrex 10", 2, "40", "http://download.garmin.com/software/Text_Danish_eTrex10_240.gtt") + "</Responses>"
        }
        val c = checkGarminUpdates(XML, xml, fs, http)
        assertFalse(c.fromExpress)
        val body = sent.single()
        assertFalse("3999999999" in body || "123456789" in body, "no unit ID or other per-unit number is sent")
        assertFalse("006-D0323-11" in body, "the licensed preprogrammed map isn't asked for")
        val fw = c.items.single { it.kind == UpdateKind.FIRMWARE }
        assertTrue(fw.isUpdate); assertEquals(230, fw.installedVersion); assertEquals("Garmin/GUPDATE.GCD", fw.target)
        assertTrue(fw.url.startsWith("https://")); assertEquals("• Charge the batteries.", fw.notes)
        val pt = c.items.single { it.fileName == "Portuguese.gtt" }
        assertTrue(pt.isUpdate && pt.kind == UpdateKind.LANGUAGE)
        val da = c.items.single { it.fileName == "Danish.gtt" }
        assertFalse(da.installed)
    }

    @Test fun asksExpressOnlyWhenWebUpdaterHasNothingAndHidesTheUnitId() {
        val fs = device()
        val xml = fs.deviceXml()!!
        var express: String? = null
        val http = UpdateHttp { url, _, body ->
            if (url == WEBUPDATER_URL) "<Responses xmlns=\"http://www.garmin.com/xmlschemas/UnitSoftwareUpdate/v3\"></Responses>"
            else {
                express = String(body)
                """{"updates":[{"partNumber":"006-D1074-00","productName":"Time Zone Map","version":"43.00","isPrimaryFirmware":false,
                "releaseNotes":[{"changeLog":["Updated Morocco."]}],"deliverable":{"downloadUrl":"https://download.garmin.com/gsup/x/D107400AZ.img","md5":"d8","sizeInBytes":582656},
                "installation":{"isProductInstalled":true,"path":"Garmin","fileName":"gmaptz.img","isDelta":false}},
                {"partNumber":"006-D0323-11","productName":"City Navigator","version":"2027.10","deliverable":{"downloadUrl":"https://x/y"},
                "installation":{"isProductInstalled":true,"path":"Garmin","fileName":"gmapprom.img"}}]}"""
            }
        }
        val c = checkGarminUpdates(XML, xml, fs, http)
        assertTrue(c.fromExpress)
        assertTrue(express!!.contains("<Id>1</Id>")); assertFalse("3999999999" in express!! || "123456789" in express!!)
        assertEquals(listOf("gmaptz.img"), c.items.map { it.fileName }, "the licensed map is never offered")
        assertEquals(4300, c.items.single().version); assertEquals(UpdateKind.MAP, c.items.single().kind)
    }

    @Test fun unwrapsGarminsRgnContainer() {
        val payload = "GARMIN BITMAP vehicle".toByteArray()
        fun rec(type: Char, data: ByteArray) = java.nio.ByteBuffer.allocate(5 + data.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(data.size).put(type.code.toByte()).put(data).array()
        val r = java.nio.ByteBuffer.allocate(10 + payload.size).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(78).putInt(0).putInt(payload.size).put(payload).array()
        val rgn = "KpGr".toByteArray() + byteArrayOf(100, 0) + rec('D', byteArrayOf(100, 0)) + rec('A', "SQA".toByteArray()) + rec('R', r)
        assertContentEquals(payload, unwrapRgn(rgn))
        assertFailsWith<IllegalStateException> { unwrapRgn(rgn + rec('R', r)) }
        assertContentEquals("<gtt/>".toByteArray(), unwrapRgn("<gtt/>".toByteArray()), "plain files pass through")
        assertFailsWith<IllegalStateException> { unwrapRgn("MZ\u0090\u0000".toByteArray()) }
    }

    @Test fun fetchChecksTheChecksumAndKeepsTheDownload() {
        val u = GarminUpdate("006-D0578-00", "Vehicle", UpdateKind.VEHICLE, 220, "Vehicle", "car.srf", "https://x/Vehicle_220.exe",
            "5d41402abc4b2a76b9719d911017c592", null, null, null)
        var calls = 0
        val fetch = { _: String, to: File, _: (Long, Long) -> Unit -> calls++; to.writeText("hello") }
        val f = fetchUpdate(u, tmp, fetch = fetch)
        assertEquals("hello", f.readText()); assertEquals("car.srf", f.name)
        fetchUpdate(u, tmp, fetch = fetch); assertEquals(1, calls, "the second time comes from the cache")
        assertFailsWith<IllegalStateException> { fetchUpdate(u.copy(md5 = "00", version = 230), tmp, fetch = fetch) }
    }
}
