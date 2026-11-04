package osmimg

/** One place the map could be written: [path] on the drive, and whether a file is already there. */
data class SlotChoice(val path: String, val slot: MapSlot, val replaces: Boolean)

/**
 * Where a built map would go on a device, and what stands in the way.
 * [target] is the slot to use (null when none is free or the map can't go on at all);
 * [replaceable] lists the taken slots the user could choose to overwrite instead.
 */
data class InstallPlan(
    val target: SlotChoice?,
    val replaceable: List<SlotChoice>,
    val problems: List<String>,
    val warnings: List<String>,
) {
    val ok: Boolean get() = target != null && problems.isEmpty()
}

/** A file name the device will accept: letters, digits, `_` and `-`, at most 32 characters, ending in .img. */
fun safeImgName(wanted: String): String {
    val base = wanted.replace(Regex("\\.img$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').take(32)
    return (base.ifEmpty { "osm_map" }) + ".img"
}

private fun mb(bytes: Long) = if (bytes >= 1L shl 30) "%.1f GB".format(bytes / 1073741824.0) else "%.0f MB".format(bytes / 1_048_576.0)

/** Plain names for where a map goes, for messages people read. */
fun storageName(st: Storage) = if (st == Storage.INTERNAL) "built-in memory" else "memory card"

/**
 * Plans putting a map of [sizeBytes] on [storage] of a device with [profile].
 * [existing] are the paths of files already on that storage, relative to its root (any case),
 * e.g. `Garmin/gmapsupp.img`. [wantedName] is used where the device takes any name.
 */
fun planInstall(
    profile: DeviceProfile,
    storage: Storage,
    wantedName: String,
    sizeBytes: Long,
    existing: Collection<String>,
): InstallPlan {
    val problems = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val have = existing.map { it.lowercase().trimStart('/') }.toSet()

    profile.maxFileBytes?.let { max ->
        if (sizeBytes > max) problems += "This map is ${mb(sizeBytes)} and the ${profile.name} takes up to ${mb(max)} per map. " +
            "Split it into ${partsNeeded(profile, sizeBytes)} maps, or choose a smaller region."
    }
    if (profile.transport == Transport.CARD_UPLOAD) warnings += "Putting this map on the data card replaces everything on it."

    val slots = profile.slots(storage)
    if (slots.isEmpty()) problems += "The ${profile.name} can't take maps on its ${storageName(storage)}."

    val choices = slots.map { s ->
        var path = s.path(wantedName)
        if (s.role == SlotRole.ANY_NAME) {
            // A custom name must not land on a fixed slot or system name in the same folder.
            val file = path.substringAfterLast('/').lowercase()
            if (file in profile.systemNames || roleOf(file.removeSuffix(".img")) != null) {
                path = s.path(wantedName.replace(Regex("\\.img$", RegexOption.IGNORE_CASE), "") + "_osm")
            }
        }
        SlotChoice(path, s, path.lowercase() in have)
    }
    // Card uploads always overwrite; otherwise take the first free slot, never a taken factory map.
    val target = if (profile.transport == Transport.CARD_UPLOAD) choices.firstOrNull()
    else choices.firstOrNull { !it.replaces }
    val replaceable = choices.filter { it.replaces && it != target }

    target?.slot?.note?.let { warnings += "${target.path}: $it." }
    if (target == null && slots.isNotEmpty()) {
        problems += "The ${storageName(storage)} has no room for another map. " +
            "Replace one (it is backed up first), or use the other storage."
    }
    profile.maxMapSets?.let { max ->
        // A forum figure that counts the base map too, and we only see this storage: warn, don't block.
        val imgs = have.count { it.endsWith(".img") }
        if (profile.transport == Transport.DRIVE && target != null && !target.replaces && imgs + 1 > max) {
            warnings += "The ${profile.name} may show only $max maps, counting its built-in map, and this would be number ${imgs + 1}. " +
                "To be safe, replace a map you don't use."
        }
    }
    return InstallPlan(if (problems.isEmpty()) target else null, replaceable, problems, warnings)
}

/** What is on one storage of the device now: file paths (relative to its root) and free space, if known. */
data class StorageState(val existing: Collection<String>, val freeBytes: Long? = null)

/** Where one part of a split map goes. */
data class PartPlacement(val part: Int, val storage: Storage, val path: String, val sizeBytes: Long, val note: String?)

data class SplitPlan(val placements: List<PartPlacement>, val problems: List<String>, val warnings: List<String>) {
    val ok: Boolean get() = problems.isEmpty()
}

/** How many parts a map of [totalBytes] needs so each fits the device's file limit (with 5% room for uneven tiles). */
fun partsNeeded(profile: DeviceProfile, totalBytes: Long): Int {
    val max = profile.maxFileBytes ?: return 1
    val usable = (max * 0.95).toLong()
    return ((totalBytes + usable - 1) / usable).toInt().coerceAtLeast(1)
}

/**
 * Spreads the parts of a split map ([partSizes], in order) over the free map slots of both storages.
 * Storages are tried in [order]: the SD card first by default, since internal memory is usually
 * small and a bad file on a card is fixed by taking the card out. Slots already used are never
 * overwritten here; the single-file [planInstall] offers replacing.
 * Each part should be built with its own family/product ID so older units don't see the parts as one
 * map set split across files.
 */
fun planSplit(
    profile: DeviceProfile,
    partSizes: List<Long>,
    state: Map<Storage, StorageState>,
    wantedName: String,
    order: List<Storage> = listOf(Storage.SD, Storage.INTERNAL),
): SplitPlan {
    val problems = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val max = profile.maxFileBytes
    partSizes.forEachIndexed { i, s ->
        if (max != null && s > max) problems += if (partSizes.size == 1) "This map is ${mb(s)} and the ${profile.name} takes up to ${mb(max)} per map."
            else "Part ${i + 1} is still too big (${mb(s)}; the limit is ${mb(max)}). Split it into more parts."
    }

    // Free slots across storages, in order; any-name slots can take every part, fixed slots one each.
    data class Free(val storage: Storage, val slot: MapSlot)
    val free = mutableListOf<Free>()
    for (st in order) {
        val s = state[st] ?: continue
        val have = s.existing.map { it.lowercase().trimStart('/') }.toSet()
        for (slot in profile.slots(st)) {
            if (slot.role == SlotRole.ANY_NAME || slot.path("").lowercase() !in have) free += Free(st, slot)
        }
    }
    val left = order.associateWith { state[it]?.freeBytes }.toMutableMap()
    val usedPaths = mutableSetOf<Pair<Storage, String>>()
    val placements = mutableListOf<PartPlacement>()
    partSizes.forEachIndexed { i, size ->
        val n = i + 1
        val base = wantedName.replace(Regex("\\.img$", RegexOption.IGNORE_CASE), "") + if (partSizes.size > 1) "_$n" else ""
        val taken = { st: Storage, path: String ->
            (st to path.lowercase()) in usedPaths || state[st]?.existing?.any { it.lowercase().trimStart('/') == path.lowercase() } == true
        }
        // An any-name slot gets a fresh name if this one is taken: Europe_1.img -> Europe_1_new.img.
        fun pathFor(f: Free): String {
            if (f.slot.role != SlotRole.ANY_NAME) return f.slot.path(base)
            var k = 1
            var p = f.slot.path(base)
            while (taken(f.storage, p)) p = f.slot.path(base + "_new" + if (k++ > 1) "$k" else "")
            return p
        }
        val pick = free.firstOrNull { f ->
            val room = left[f.storage]
            (room == null || room >= size) && !taken(f.storage, pathFor(f))
        }
        if (pick == null) {
            problems += if (partSizes.size == 1) "No room for this map (${mb(size)}). Replace a map, or choose a smaller region."
                else "No room for part $n (${mb(size)}). Replace a map, or choose a smaller region."
            return@forEachIndexed
        }
        val path = pathFor(pick)
        usedPaths += pick.storage to path.lowercase()
        if (pick.slot.role != SlotRole.ANY_NAME) free.remove(pick)
        left[pick.storage]?.let { left[pick.storage] = it - size }
        placements += PartPlacement(n, pick.storage, path, size, pick.slot.note)
    }
    if (placements.any { it.storage == Storage.INTERNAL } && placements.any { it.storage == Storage.SD }) {
        warnings += "Part of this map is on the memory card. Keep the card in the device."
    }
    profile.maxMapSets?.let { m ->
        val total = state.values.sumOf { s -> s.existing.count { it.lowercase().endsWith(".img") } } + placements.size
        if (total > m) warnings += "The ${profile.name} may show only $m maps, counting its built-in map, and this would make $total. " +
            "To be safe, replace a map you don't use."
    }
    return SplitPlan(if (problems.isEmpty()) placements else emptyList(), problems, warnings)
}
