package osmimg

/** A way to get the chosen region onto the device. */
sealed interface InstallOption {
    /** The map fits as one file. */
    data class OneFile(val plan: SplitPlan) : InstallOption

    /** Build the region in [parts] files and spread them as [plan] shows. */
    data class Divide(val parts: Int, val plan: SplitPlan) : InstallOption

    /** Pick a smaller region: one file of at most [maxBytes] fits. */
    data class SmallerZone(val maxBytes: Long) : InstallOption
}

/**
 * What to tell the user before building: a plain [summary] (room left, limits), the detailed
 * [howItWorks] (file names the device reads, for "show where maps go"), whether an SD card was
 * found, and the ways to fit the region, best first.
 */
data class Advice(
    val howItWorks: List<String>,
    val sdCardFound: Boolean,
    val options: List<InstallOption>,
    val summary: List<String> = emptyList(),
) {
    val recommended: InstallOption? get() = options.firstOrNull()
}

private fun size(bytes: Long) = if (bytes >= 1L shl 30) "%.1f GB".format(bytes / (1024.0 * 1024 * 1024)) else "%.0f MB".format(bytes / 1_048_576.0)

private fun describeSlots(slots: List<MapSlot>): String = slots.joinToString(", ") { s ->
    when (s.role) {
        SlotRole.ANY_NAME -> "${s.folder}/<any name>.img (several maps)"
        else -> s.path("") + (s.note?.let { " ($it)" } ?: "")
    }
}

private fun freeSlots(profile: DeviceProfile, st: Storage, state: StorageState): List<MapSlot> {
    val have = state.existing.map { it.lowercase().trimStart('/') }.toSet()
    return profile.slots(st).filter { it.role == SlotRole.ANY_NAME || it.path("").lowercase() !in have }
}

/**
 * Advice for putting a region of about [estimatedBytes] on a device. [state] holds the storages that
 * were found (no [Storage.SD] key = no SD card). Part sizes for a split are estimates (equal parts);
 * plan again with the real sizes after building.
 */
fun adviseInstall(profile: DeviceProfile, state: Map<Storage, StorageState>, estimatedBytes: Long, wantedName: String): Advice {
    val how = mutableListOf<String>()
    val plain = mutableListOf<String>()
    val sdFound = Storage.SD in state
    when (profile.transport) {
        Transport.CARD_UPLOAD -> {
            how += "${profile.name}: the map is written to the data card by the unit itself " +
                "(WebUpdater). The card holds one map, gmapsupp.img, and writing it replaces everything on it."
            plain += "The data card holds one map. Putting this map on it replaces the map that's there now."
        }
        Transport.DRIVE -> {
            how += "${profile.name}: maps are copied to the device's drive or its SD card."
            for (st in listOf(Storage.INTERNAL, Storage.SD)) {
                val s = state[st] ?: continue
                val slots = profile.slots(st)
                if (slots.isEmpty()) continue
                val free = freeSlots(profile, st, s)
                val label = if (st == Storage.INTERNAL) "Internal memory" else "SD card"
                val room = when {
                    free.any { it.role == SlotRole.ANY_NAME } -> "room for more maps"
                    free.isEmpty() -> "no room for another map"
                    free.size == 1 -> "room for 1 more map"
                    else -> "room for ${free.size} more maps"
                }
                plain += "${if (st == Storage.INTERNAL) "Built-in memory" else "Memory card"}: $room" +
                    (s.freeBytes?.let { ", ${size(it)} free" } ?: "") + "."
                how += "$label reads maps from: ${describeSlots(slots)}. " +
                    "Free: ${if (free.any { it.role == SlotRole.ANY_NAME }) "any number of named maps" else "${free.size} slot(s)"}" +
                    (s.freeBytes?.let { ", ${size(it)} of space" } ?: "") + "."
            }
            if (!sdFound) plain += "No memory card found. A card gives more room. If one is in the device, choose it at the top."
            if (!sdFound) how += "No SD card found. If the device has a card slot, a card gives more room" +
                (if (profile.sd.isNotEmpty()) " and its own map slots (${describeSlots(profile.sd)})" else "") + "."
        }
    }
    profile.maxFileBytes?.let { how += "Each map file can be at most ${size(it)}."; plain += "One map can be up to ${size(it)}." }
    profile.maxMapSets?.let {
        how += "Reported limit: $it maps in all, base map included."
        if (it > 1) plain += "This device shows up to $it maps, counting its built-in map."
    }

    val options = mutableListOf<InstallOption>()
    val one = planSplit(profile, listOf(estimatedBytes), state, wantedName)
    if (one.ok) {
        options += InstallOption.OneFile(one)
    } else {
        val n = partsNeeded(profile, estimatedBytes)
        if (n > 1) {
            val parts = List(n) { i -> estimatedBytes / n + if (i < estimatedBytes % n) 1 else 0 }
            val split = planSplit(profile, parts, state, wantedName)
            if (split.ok) options += InstallOption.Divide(n, split)
        }
        // The biggest single map that fits: the file limit, or the most free space on a storage with a free slot.
        val room = state.filter { (st, s) -> freeSlots(profile, st, s).isNotEmpty() }
            .map { (_, s) -> s.freeBytes ?: Long.MAX_VALUE }.maxOrNull()
        if (room != null) {
            val max = minOf(room, profile.maxFileBytes ?: Long.MAX_VALUE)
            if (max > 0) options += InstallOption.SmallerZone(max)
        }
    }
    return Advice(how, sdFound, options, plain)
}
