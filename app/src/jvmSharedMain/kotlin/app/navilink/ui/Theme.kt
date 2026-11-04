package app.navilink.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * NaviLink's look, shared by the phone app and the desktop window so both stay alike.
 * Same rules as the Sommet app: calm low-chroma colour with one accent (NaviLink blue), a real
 * surface hierarchy (background < card < nested row), hairline borders instead of shadows,
 * 16 dp card corners, 8/16/24 spacing, one type scale, and status shown as a dot or icon plus words.
 */

@Immutable
data class NaviTokens(
    val background: Color,
    val card: Color,
    val nested: Color,
    /** A surface lifted above nested: the chosen segment of a toggle. */
    val raised: Color,
    val border: Color,
    val borderStrong: Color,
    val primary: Color,
    val primarySoft: Color,
    /** Fill of the one filled button: a deeper accent, so in dark mode it isn't the brightest thing on screen. */
    val fill: Color,
    val text: Color,
    val muted: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
)

val LightTokens = NaviTokens(
    background = Color(0xFFECEFF2), card = Color(0xFFFFFFFF), nested = Color(0xFFF2F4F7), raised = Color(0xFFFFFFFF),
    border = Color(0xFFDCE1E6), borderStrong = Color(0xFFC6CDD5),
    primary = Color(0xFF1F5A80), primarySoft = Color(0xFFE1ECF4), fill = Color(0xFF1F5A80),
    text = Color(0xFF1A1D22), muted = Color(0xFF5B6270),
    success = Color(0xFF3E7D52), warning = Color(0xFF8F6E14), error = Color(0xFFB0473C),
)

val DarkTokens = NaviTokens(
    background = Color(0xFF0F1216), card = Color(0xFF1B1F27), nested = Color(0xFF232935), raised = Color(0xFF353C49),
    border = Color(0xFF2B313C), borderStrong = Color(0xFF3A414E),
    primary = Color(0xFF7DB3DA), primarySoft = Color(0xFF1E3446), fill = Color(0xFF2F6F9C),
    text = Color(0xFFE9EBEE), muted = Color(0xFFB4BDC9),
    success = Color(0xFF6DB283), warning = Color(0xFFD3A44E), error = Color(0xFFE07A6F),
)

object Space { val small = 8.dp; val medium = 16.dp; val large = 24.dp }
object Radius { val small = 8.dp; val card = 16.dp }

val LocalNavi = staticCompositionLocalOf { LightTokens }

/** The tokens of the theme in use. */
val navi: NaviTokens @Composable get() = LocalNavi.current

private fun scheme(t: NaviTokens, dark: Boolean) = (if (dark) darkColorScheme() else lightColorScheme()).copy(
    primary = t.primary, onPrimary = if (dark) Color(0xFF0F1216) else Color.White,
    primaryContainer = t.primarySoft, onPrimaryContainer = t.text,
    secondary = t.primary, onSecondary = if (dark) Color(0xFF0F1216) else Color.White,
    secondaryContainer = t.primarySoft, onSecondaryContainer = t.text,
    tertiary = t.primary,
    background = t.background, onBackground = t.text,
    surface = t.card, onSurface = t.text,
    surfaceVariant = t.nested, onSurfaceVariant = t.muted,
    surfaceContainerLowest = t.card, surfaceContainerLow = t.card, surfaceContainer = t.card,
    surfaceContainerHigh = t.nested, surfaceContainerHighest = t.nested,
    surfaceTint = Color.Transparent,
    outline = t.borderStrong, outlineVariant = t.border,
    error = t.error, onError = Color.White,
)

/** One type scale, 12 to 22 sp: Sommet's steps, a little larger for a phone in a car. */
private val type = Typography().let { b ->
    b.copy(
        headlineSmall = b.headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
        titleLarge = b.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
        titleMedium = b.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
        titleSmall = b.titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = b.bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp, letterSpacing = 0.sp),
        bodyMedium = b.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
        bodySmall = b.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
        labelLarge = b.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        labelMedium = b.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
    )
}

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(Radius.small),
    medium = RoundedCornerShape(Radius.small), large = RoundedCornerShape(Radius.card), extraLarge = RoundedCornerShape(Radius.card),
)

@Composable
fun NaviTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val t = if (dark) DarkTokens else LightTokens
    CompositionLocalProvider(LocalNavi provides t) {
        MaterialTheme(colorScheme = scheme(t, dark), typography = type, shapes = shapes, content = content)
    }
}

// ---- building blocks ----

/** The base surface of every content block: card colour, hairline border, no shadow. */
@Composable
fun NaviCard(modifier: Modifier = Modifier, padding: Dp = Space.medium, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(Radius.card)).background(navi.card)
            .border(1.dp, navi.border, RoundedCornerShape(Radius.card)).padding(padding),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content,
    )
}

/** A card's heading, with an optional line under it and an action on the right. */
@Composable
fun CardTitle(title: String, description: String? = null, status: Pair<String, Tone>? = null, action: @Composable RowScope.() -> Unit = {}) {
    // Every card title row is the same height, with or without an action beside it.
    Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = navi.text)
            description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = navi.muted) }
            status?.let { (t, tone) -> StatusLine(t, tone) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = action)
    }
}

enum class Tone { OK, WARN, BAD, INFO, MUTED,
    /** Still going: a small spinner instead of a dot, so it doesn't read as a finished state. */
    WORKING }

@Composable
fun toneColor(t: Tone): Color = when (t) {
    Tone.OK -> navi.success; Tone.WARN -> navi.warning; Tone.BAD -> navi.error; Tone.INFO -> navi.primary; Tone.MUTED, Tone.WORKING -> navi.muted
}

/** A small coloured dot and words: the status line used everywhere. */
@Composable
fun StatusLine(text: String, tone: Tone = Tone.MUTED, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (tone == Tone.WORKING) CircularProgressIndicator(Modifier.padding(top = 4.dp).size(12.dp), color = navi.muted, strokeWidth = 1.5.dp, trackColor = navi.border)
        else Box(Modifier.padding(top = 6.dp).size(8.dp).background(toneColor(tone), CircleShape))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (tone == Tone.MUTED || tone == Tone.WORKING) navi.muted else navi.text)
    }
}

/** A note that needs reading before going on: tinted, with a coloured edge on the left. */
@Composable
fun Note(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val c = toneColor(tone)
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.small)).background(c.copy(alpha = 0.10f))
        .border(1.dp, c.copy(alpha = 0.35f), RoundedCornerShape(Radius.small)).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).background(c, CircleShape))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = navi.text)
    }
}

/** A short label in a soft pill: a size, a type, a state. */
@Composable
fun Pill(text: String, tone: Tone = Tone.MUTED) {
    val c = toneColor(tone)
    Text(text, style = MaterialTheme.typography.labelMedium, color = if (tone == Tone.MUTED) navi.muted else c,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (tone == Tone.MUTED) navi.nested else c.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp))
}

/** The one filled button of a screen. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = RoundedCornerShape(Radius.small),
        colors = ButtonDefaults.buttonColors(containerColor = navi.fill, contentColor = Color.White,
            disabledContainerColor = navi.border, disabledContentColor = navi.muted),
        contentPadding = PaddingValues(horizontal = Space.medium, vertical = 12.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Everything else that is a button: card fill, hairline outline, plain text (the accent is for the one main action). */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = RoundedCornerShape(Radius.small),
        border = BorderStroke(1.dp, navi.borderStrong), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = navi.card, contentColor = navi.text)) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** A text-only action, for small things beside a title or in a row. Plain text, not the accent. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    TextButton(onClick, modifier, enabled = enabled, shape = RoundedCornerShape(Radius.small),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = navi.text)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Two or three choices side by side, like Sommet's view toggles. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(Radius.small)).background(navi.nested).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(if (on) navi.raised else Color.Transparent)
                .then(if (on) Modifier.border(1.dp, navi.border, RoundedCornerShape(6.dp)) else Modifier)
                .clickable(role = Role.Tab) { onSelect(i) }.heightIn(min = 42.dp).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) navi.text else navi.muted, maxLines = 1)
            }
        }
    }
}

/** A row inside a card (a place, a map): tappable, with a hairline between rows drawn by the list. Chosen: a thin accent outline. */
@Composable
fun ListRow(onClick: (() -> Unit)?, selected: Boolean = false, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.small))
        .then(if (selected) Modifier.border(1.dp, navi.primary, RoundedCornerShape(Radius.small)) else Modifier)
        .then(if (onClick != null) Modifier.clickable(role = Role.RadioButton, onClick = onClick) else Modifier)
        .heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** A small muted label above a group of rows. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = navi.muted, modifier = modifier)
}


/** A shaded row inside a card: a title, a line under it, and an optional action on the right. */
@Composable
fun NestedRow(title: String, sub: String?, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.small)).background(navi.nested)
        .heightIn(min = 60.dp).padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = navi.text, maxLines = 2)
            sub?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = navi.muted) }
        }
        action()
    }
}

/**
 * A scrolling page with buttons under it: when the page goes on below, its bottom edge fades
 * into the background and a hairline marks where the buttons start, so nothing looks cut off.
 */
@Composable
fun ScrollPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = androidx.compose.foundation.rememberScrollState()
    val bg = navi.background
    Box(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
            Spacer(Modifier.height(4.dp))
        }
        if (scroll.canScrollForward) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(32.dp)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg))))
            HorizontalDivider(Modifier.align(Alignment.BottomCenter), color = navi.border)
        }
    }
}
