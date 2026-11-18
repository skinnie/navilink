@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package app.navilink.pois

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.navilink.maps.size
import app.navilink.ui.*

/** The Garmin, and the POI sets already on it. */
@Composable
private fun GarminCard(m: PoiPacks) {
    NaviCard(Modifier.fillMaxWidth()) {
        if (m.drives.isEmpty()) {
            CardTitle(m.deviceName, if (m.canPick) "Plug it into this phone with a USB OTG cable. When Android shows it, pick its drive."
                else "Plug it in with a USB cable, or put its memory card in a card reader.")
            if (m.canPick) SecondaryButton("Pick the ${m.deviceName}'s drive", { m.pick() }, Modifier.fillMaxWidth())
            else SecondaryButton("Look again", { m.scan() }, Modifier.fillMaxWidth())
            return@NaviCard
        }
        CardTitle(m.deviceName) { if (m.canPick) TextAction("Change", { m.pick() }, enabled = m.busy == null) }
        if (!m.scanned) { StatusLine("Looking at what's on it…", Tone.WORKING); return@NaviCard }
        if (m.found.isEmpty()) StatusLine("No POIs on it yet.")
        m.found.forEach { f ->
            NestedRow(f.title, listOfNotNull(f.publisher?.title, size(f.bytes), f.path).joinToString("  ·  "))
        }
        if (m.found.any { it.publisher == PoiPublisher.IOVERLANDER && it.readable }) {
            Note("iOverlander is on it. New places within 150 m of an iOverlander place of the same kind are left out.", Tone.INFO)
        }
        if (m.found.any { it.readable && it.publisher != PoiPublisher.IOVERLANDER }) {
            Note("Places already in its other POI files are left out the same way.", Tone.INFO)
        }
        m.foursquareMap?.let { f ->
            Note(if (m.fsqAnyway) "${f.title} already has Foursquare places. You chose to add ours too, so some will show twice."
                else "${f.title} already has Foursquare places, so Foursquare is left out here.", Tone.WARN)
            TextAction(if (m.fsqAnyway) "Leave Foursquare out" else "Add Foursquare anyway", { m.fsqAnyway = !m.fsqAnyway })
        }
    }
}

@Composable
private fun AreaCard(m: PoiPacks) {
    NaviCard(Modifier.fillMaxWidth()) {
        CardTitle("Countries", "2-letter codes, like PT, ES.")
        OutlinedTextField(m.countries, { m.countries = it }, Modifier.fillMaxWidth(), singleLine = true,
            isError = m.countriesError != null, shape = RoundedCornerShape(Radius.small),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = navi.border, focusedBorderColor = navi.primary,
                unfocusedContainerColor = navi.nested, focusedContainerColor = navi.card))
        m.countriesError?.let { StatusLine(it, Tone.BAD) }
    }
}

@Composable
private fun KindsCard(m: PoiPacks, title: String, credit: String, kinds: List<PoiKind>, on: (PoiKind) -> Boolean, enabled: Boolean) {
    NaviCard(Modifier.fillMaxWidth()) {
        CardTitle(title, credit)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.small), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            kinds.forEach { k ->
                FilterChip(on(k), { m.toggle(k) }, { Text(k.label) }, enabled = enabled && m.busy == null,
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = navi.primarySoft, selectedLabelColor = navi.primary,
                        containerColor = navi.nested, labelColor = navi.text),
                    border = FilterChipDefaults.filterChipBorder(enabled = enabled, selected = on(k), borderColor = navi.border,
                        selectedBorderColor = navi.primary))
            }
        }
    }
}

@Composable
private fun ResultsCard(m: PoiPacks) {
    if (m.results.isEmpty()) return
    NaviCard(Modifier.fillMaxWidth()) {
        val n = m.results.count { it.written > 0 }
        CardTitle("Done", status = "$n ${if (n == 1) "file" else "files"} on the ${m.deviceName}" to Tone.OK)
        m.results.forEach { r ->
            NestedRow(r.kind.category, if (r.written == 0) "Nothing new: all ${r.alreadyThere} were already there"
                else "${r.written} places" + if (r.alreadyThere > 0) ", ${r.alreadyThere} already there left out" else "")
        }
        Text("Eject the ${m.deviceName} before unplugging it. The POIs are under Custom POIs in Where To?",
            style = MaterialTheme.typography.bodySmall, color = navi.muted)
    }
}

@Composable
private fun ActionBar(m: PoiPacks, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.small)) {
        m.error?.let { Note(it, Tone.BAD) }
        val b = m.busy
        if (b != null) {
            NaviCard(Modifier.fillMaxWidth(), padding = 12.dp) {
                Text("$b…", style = MaterialTheme.typography.bodyMedium, color = navi.text)
                val pr = m.progress
                if (pr != null) LinearProgressIndicator(progress = { pr }, modifier = Modifier.fillMaxWidth(), trackColor = navi.nested, drawStopIndicator = {})
                else LinearProgressIndicator(Modifier.fillMaxWidth(), trackColor = navi.nested)
            }
            return@Column
        }
        val n = m.chosen.size
        PrimaryButton(if (n == 0) "Choose what to add" else "Put $n ${if (n == 1) "kind" else "kinds"} of POIs on the ${m.deviceName}",
            { m.install() }, Modifier.fillMaxWidth(), enabled = n > 0 && m.drives.isNotEmpty() && m.countriesError == null)
    }
}

/** The POIs page: what's on the Garmin, which countries, which kinds, then one button. */
@Composable
fun PoiPacksScreen(m: PoiPacks, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { m.scan() }
    BoxWithConstraints(modifier) {
        val osm = @Composable {
            KindsCard(m, "OpenStreetMap", PoiKind.Source.OSM.credit, OsmPoiType.entries, { it in m.osm }, enabled = true)
        }
        val fsq = @Composable {
            KindsCard(m, "Foursquare", PoiKind.Source.FOURSQUARE.credit + ". Places confirmed since $FSQ_DEFAULT_SINCE.",
                FsqPoiType.entries, { it in m.fsq && !m.fsqLeftOut }, enabled = !m.fsqLeftOut)
        }
        if (maxWidth >= 720.dp) Row(horizontalArrangement = Arrangement.spacedBy(Space.medium)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GarminCard(m); AreaCard(m); ResultsCard(m)
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { osm(); fsq() }
                ActionBar(m)
            }
        } else Column {
            ScrollPage(Modifier.weight(1f)) { GarminCard(m); AreaCard(m); osm(); fsq(); ResultsCard(m) }
            ActionBar(m, Modifier.padding(top = 12.dp))
        }
    }
}
