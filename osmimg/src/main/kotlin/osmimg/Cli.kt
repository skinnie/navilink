package osmimg

import java.io.File
import kotlin.system.exitProcess

/** A Garmin POI type with its Find menu category, from resources/osmimg/poi-categories.txt. */
data class PoiCategory(val type: Int, val category: String, val subcategory: String)

fun poiCategories(): List<PoiCategory> {
    val s = PoiCategory::class.java.getResourceAsStream("/osmimg/poi-categories.txt") ?: error("poi-categories.txt missing")
    return s.bufferedReader(Charsets.UTF_8).readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { val f = it.split('\t'); PoiCategory(Integer.decode(f[0]), f[1], f[2]) }
}

private const val USAGE = """osmimg: OpenStreetMap -> Garmin .img maps (splitter + mkgmap, downloaded on first use)

  osmimg regions [PARENT]        list regions (no PARENT = continents), e.g. osmimg regions europe
  osmimg build REGION [options]  download, split and build, e.g. osmimg build portugal --max 2G
  osmimg device DRIVE [CARD]     what maps are on a Garmin (and its memory card) and what is wrong with them
  osmimg updates DRIVE           what Garmin offers the device (firmware, voices, vehicles...); reads only
  osmimg img FILE...             tiles, area and flags of .img files
  osmimg categories              print the POI type -> category table (tab separated)
  osmimg premade SOURCE [TEXT]   ready-made maps: bbbike, freizeitkarte or opentopomap, e.g. osmimg premade opentopomap portugal
  osmimg premade-get SOURCE ID [--out DIR]   download one (ID from the list) and unzip its .img files

build options:
  --out DIR          where the .img files go (default: <cache>/out/REGION)
  --name NAME        file name stem (default OSM_<Region>)
  --routing car|bike|none   bike: car-only units route like a bicycle (default car)
  --style clean|default|DIR clean POIs (default), mkgmap's default style, or your own style folder
  --labels ascii|latin1|mixed   capitals only / capitals with accents / normal text (default mixed)
  --transit          bus and tram stops as POIs
  --no-index         no address/POI search index (smaller)
  --max SIZE         split into files of at most SIZE (e.g. 2G, 700M, bytes)
  --family-id N      Garmin family ID of the first file (default 6324)
  --heap MB          Java heap for splitter and mkgmap (default 2048)

Cache: ${'$'}OSMIMG_CACHE or ~/.cache/osmimg (tools, downloads, tiles), shared by every program using osmimg."""

fun main(args: Array<String>) {
    try {
        run(args.toList())
    } catch (e: CliError) {
        System.err.println("osmimg: ${e.message}")
        exitProcess(2)
    } catch (e: Exception) {
        System.err.println("osmimg: ${e.message ?: e}")
        exitProcess(1)
    }
}

private class CliError(msg: String) : Exception(msg)

/** Parses "2G", "700M", "500k" or plain bytes. */
fun parseSize(s: String): Long {
    val m = Regex("(?i)^(\\d+(?:\\.\\d+)?)\\s*([kmg]?)i?b?$").find(s.trim()) ?: throw CliError("bad size: $s")
    val n = m.groupValues[1].toDouble()
    val mult = when (m.groupValues[2].lowercase()) { "k" -> 1L shl 10; "m" -> 1L shl 20; "g" -> 1L shl 30; else -> 1L }
    // "2G" means the largest file a 2 GB device takes: 2 GiB - 1.
    val bytes = (n * mult).toLong()
    return if (mult > 1 && bytes == (n.toLong() * mult)) bytes - 1 else bytes
}

private fun run(args: List<String>) {
    val cmd = args.firstOrNull() ?: run { println(USAGE); return }
    val cache = defaultCache()
    when (cmd) {
        "-h", "--help", "help" -> println(USAGE)
        "device" -> {
            val dev = File(args.getOrNull(1) ?: throw CliError("device needs a drive or folder"))
            val drives = buildMap<Storage, DriveFs> {
                put(Storage.INTERNAL, FileDriveFs(dev))
                args.getOrNull(2)?.let { put(Storage.SD, FileDriveFs(File(it))) }
            }
            val xml = FileDriveFs(dev).deviceXml()
            val c = scanDevice(drives, Profiles.forDevice(xml), xml?.model?.ifEmpty { null }, xml?.rasterPaths.orEmpty())
            println("Device: ${xml?.model ?: "model unknown (no Garmin/GarminDevice.xml)"}")
            for (s in c.storages) println("${storageName(s.storage)}: ${s.name}, maps ${s.mapBytes / 1048576} MB, free ${s.freeBytes?.let { it / 1048576 }} MB")
            for (f in c.files) {
                val d = f.details
                println("%-8s %-40s %8d MB  %s".format(f.kind, "${f.storage.name.lowercase()}:${f.path}", f.bytes / 1048576, f.title))
                if (d != null) println("         families ${d.families.joinToString { "${it.familyId}/${it.productId} \"${it.name}\"" }}; ${d.tiles.size} tiles" +
                    (if (d.routable) ", routable" else "") + (if (d.hasSearch) ", search" else "") + (if (d.elevation) ", elevation" else "") +
                    (if (d.hasTyp) ", TYP" else "") + (if (d.locked) ", locked (${d.unlockCodes} unlock code(s) inside)" else "") +
                    (d.bounds?.let { ", area N%.3f E%.3f S%.3f W%.3f".format(it.north, it.east, it.south, it.west) } ?: ""))
                when (f.readByDevice) { false -> println("         NOT READ: ${f.note}"); null -> println("         maybe read: ${f.note ?: ""}"); else -> {} }
            }
            println()
            for (x in c.findings) println("[${x.level}] ${x.title}\n        ${x.detail}")
        }
        "updates" -> {
            // osmimg updates DRIVE: what Garmin offers for the device on DRIVE (reads only, writes nothing).
            val dev = FileDriveFs(File(args.getOrNull(1) ?: throw CliError("updates needs the device's drive or folder")))
            val raw = dev.readBytes("Garmin/GarminDevice.xml") ?: throw CliError("no Garmin/GarminDevice.xml there")
            val xml = readGarminDeviceXml(raw)
            val c = checkGarminUpdates(String(raw, Charsets.UTF_8), xml, dev, log = { System.err.println(it) })
            println("${xml.model}: ${c.items.size} items from ${if (c.fromExpress) "Garmin Express's service" else "WebUpdater's service"}")
            for ((k, list) in c.items.groupBy { it.kind }.toSortedMap()) {
                println("  ${k.label}:")
                for (u in list.sortedBy { it.name }) println("    %-45s %6s %-14s -> %s".format(u.name.take(45), u.versionText,
                    when { u.isUpdate -> "UPDATE from ${versionText(u.installedVersion!!)}"; u.installed -> "on it"; else -> "not on it" }, u.target))
            }
        }
        "img" -> for (a in args.drop(1)) {
            val d = readImgDetails(File(a))
            if (d == null) { println("$a: not a Garmin map"); continue }
            println("$a: \"${d.description}\" block ${d.blockSize}, ${d.tiles.size} tiles, families ${d.families.joinToString { "${it.familyId}/${it.productId} \"${it.name}\"" }}" +
                (if (d.locked) ", locked" else "") + (if (d.routable) ", routable" else "") + (d.damaged?.let { ", DAMAGED: $it" } ?: ""))
        }
        "categories" -> poiCategories().forEach { println("0x%04x\t%s\t%s".format(it.type, it.category, it.subcategory)) }
        "regions" -> {
            val all = bundledRegions()
            val parent = args.getOrElse(1) { "" }
            if (parent.isNotEmpty() && all.none { it.id == parent }) throw CliError("no region $parent")
            all.childrenOf(parent).forEach { r ->
                val kids = all.count { it.parent == r.id }
                println("%-32s %8s  %s%s".format(r.id, if (r.bytes > 0) "${r.bytes / 1_048_576} MB" else "", r.name,
                    if (kids > 0) "  ($kids inside)" else ""))
            }
        }
        "build" -> {
            val id = args.getOrNull(1)?.takeIf { !it.startsWith("--") } ?: throw CliError("build needs a region id (see: osmimg regions)")
            val region = bundledRegions().firstOrNull { it.id == id } ?: throw CliError("no region $id (see: osmimg regions)")
            val o = args.drop(2).iterator()
            var opts = BuildOptions("OSM_" + region.name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_'))
            var out = File(cache, "out/$id")
            fun value(flag: String) = if (o.hasNext()) o.next() else throw CliError("$flag needs a value")
            while (o.hasNext()) {
                when (val f = o.next()) {
                    "--out" -> out = File(value(f))
                    "--name" -> value(f).let { opts = opts.copy(name = it, description = it.replace('_', ' ')) }
                    "--routing" -> opts = opts.copy(routing = when (value(f)) {
                        "car" -> Routing.CAR; "bike" -> Routing.BIKE; "none" -> Routing.NONE
                        else -> throw CliError("--routing car|bike|none") })
                    "--style" -> when (val v = value(f)) {
                        "clean" -> opts = opts.copy(style = MapStyle.CLEAN)
                        "default" -> opts = opts.copy(style = MapStyle.MKGMAP_DEFAULT)
                        else -> File(v).let { if (!it.isDirectory) throw CliError("no style folder $v"); opts = opts.copy(styleDir = it.absoluteFile) }
                    }
                    "--labels" -> opts = opts.copy(labels = when (value(f)) {
                        "ascii" -> Labels.ASCII; "latin1" -> Labels.LATIN1; "mixed" -> Labels.LATIN1_MIXED_CASE
                        else -> throw CliError("--labels ascii|latin1|mixed") })
                    "--transit" -> opts = opts.copy(transitStops = true)
                    "--no-index" -> opts = opts.copy(index = false)
                    "--max" -> opts = opts.copy(maxPartBytes = parseSize(value(f)))
                    "--family-id" -> opts = opts.copy(familyId = value(f).toIntOrNull() ?: throw CliError("--family-id needs a number"))
                    "--heap" -> opts = opts.copy(heapMb = value(f).toIntOrNull() ?: throw CliError("--heap needs a number"))
                    else -> throw CliError("unknown option $f")
                }
            }
            val res = MapBuilder(MapTools(File(cache, "tools")), cache, ::println).build(region, opts, out)
            println("Done: ${res.parts.size} file(s) in ${out.absolutePath}")
        }
        "premade", "premade-get" -> {
            val src = args.getOrNull(1)?.let { a -> PremadeSource.entries.firstOrNull { it.name.equals(a, true) } }
                ?: throw CliError("$cmd needs a source: " + PremadeSource.entries.joinToString("|") { it.name.lowercase() })
            val maps = listPremade(src, cache)
            if (cmd == "premade") {
                val text = args.getOrElse(2) { "" }
                maps.filter { text.isEmpty() || it.name.contains(text, true) || it.id.contains(text, true) }.forEach {
                    println("%-40s %8s  %s, %s (%s)".format(it.id, it.bytes?.let { b -> "${b / 1_048_576} MB" } ?: "", it.name, it.variant, it.group))
                }
            } else {
                val id = args.getOrNull(2) ?: throw CliError("premade-get needs an ID (see: osmimg premade ${src.name.lowercase()})")
                val map = maps.firstOrNull { it.id == id } ?: throw CliError("no map $id in ${src.title}")
                val imgs = fetchPremade(map, cache, ::println)
                val out = args.indexOf("--out").takeIf { it > 0 }?.let { File(args.getOrNull(it + 1) ?: throw CliError("--out needs a value")) }
                if (out != null) {
                    out.mkdirs()
                    imgs.forEachIndexed { i, f ->
                        val name = safeImgName(map.fileStem + if (imgs.size > 1) "_${i + 1}" else "")
                        f.copyTo(File(out, name), overwrite = true); println("Wrote ${File(out, name).path}")
                    }
                }
            }
        }
        else -> throw CliError("unknown command $cmd\n\n$USAGE")
    }
}
