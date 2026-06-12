package com.homebase.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homebase.android.ui.theme.Hb
import com.homebase.android.ui.theme.HbType

// ---------------------------------------------------------------------------
// Navigation routes (mirrors the drawer order in the design)
// ---------------------------------------------------------------------------

enum class HbRoute(val label: String, val icon: ImageVector) {
    HEUTE("Heute", HbIcons.home),
    AUFGABEN("Aufgaben", HbIcons.checkCircle),
    EINKAUF("Einkaufsliste", HbIcons.cart),
    NOTIZEN("Notizen", HbIcons.note),
    ZEIT("Zeiterfassung", HbIcons.clock),
    ABWESENHEIT("Kalender", HbIcons.calendar),
    REZEPTE("Rezepte", HbIcons.chef),
}

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

/** Click without the Material ripple — used for scrims and bare rows. */
@Composable
private fun Modifier.applyNoRipple(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = source, indication = null) { onClick() }
}

/** Draw a 1px bottom hairline in [color]. */
fun Modifier.bottomBorder(color: Color): Modifier = this.drawBehind {
    val y = size.height - 1f
    drawLine(color, Offset(0f, y), Offset(size.width, y), 1f)
}

// ---------------------------------------------------------------------------
// Icon button (44dp circular, optional accent count badge)
// ---------------------------------------------------------------------------

@Composable
fun HbIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Hb.ink2,
    iconSize: androidx.compose.ui.unit.Dp = 24.dp,
    badge: String? = null,
) {
    Box(
        modifier
            .size(44.dp)
            .clip(HbPill)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        HbIcon(icon, size = iconSize, tint = tint)
        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 7.dp, end = 7.dp)
                    .clip(HbPill)
                    .background(Hb.accent, HbPill)
                    .heightIn(min = 16.dp)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(badge, style = HbType.mono.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = Hb.onAccent)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// App bar
// ---------------------------------------------------------------------------

@Composable
fun HbAppBar(
    title: String,
    onLeft: () -> Unit,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    leftIcon: ImageVector = HbIcons.menu,
    bordered: Boolean = false,
    titleSm: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(if (bordered) Hb.surface else Hb.paper)
            .then(if (bordered) Modifier.bottomBorder(Hb.lineSoft) else Modifier)
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HbIconButton(leftIcon, onLeft)
        Column(Modifier.weight(1f)) {
            if (eyebrow != null) {
                Text(eyebrow.uppercase(), style = HbType.eyebrow, color = Hb.ink3)
            }
            Text(
                title,
                style = if (titleSm) HbType.appBarTitleSm else HbType.appBarTitle,
                color = Hb.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        actions()
    }
}

// ---------------------------------------------------------------------------
// FAB (extended pill, or round when no label)
// ---------------------------------------------------------------------------

@Composable
fun HbFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector = HbIcons.plus,
) {
    Row(
        modifier
            .padding(end = 18.dp, bottom = 22.dp)
            .navigationBarsPadding()
            .shadow(10.dp, RoundedCornerShape(19.dp), clip = false, ambientColor = Hb.accent, spotColor = Hb.accent)
            .heightIn(min = 58.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(Hb.accent)
            .clickable { onClick() }
            .then(if (label != null) Modifier.padding(horizontal = 20.dp) else Modifier.width(58.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
    ) {
        HbIcon(icon, size = 24.dp, tint = Hb.onAccent)
        if (label != null) {
            Text(label, style = HbType.body.copy(fontWeight = FontWeight.SemiBold), color = Hb.onAccent)
        }
    }
}

// ---------------------------------------------------------------------------
// Screen scaffold: app bar + vertically scrolling content + FAB + overlays.
// Content blocks should apply their own horizontal padding (18dp); full-bleed
// rows (tab strips, chip rows) span edge-to-edge with internal padding.
// ---------------------------------------------------------------------------

val ScreenPad = 18.dp

@Composable
fun HbScreenScaffold(
    appBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    scrollable: Boolean = true,
    fab: (@Composable () -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(Hb.paper).statusBarsPadding()) {
        appBar()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .then(if (scrollable) Modifier.verticalScroll(scrollState) else Modifier),
            ) {
                Spacer(Modifier.size(4.dp))
                content()
                Spacer(Modifier.size(110.dp))
            }
            if (fab != null) {
                Box(Modifier.align(Alignment.BottomEnd)) { fab() }
            }
            overlay()
        }
    }
}

// ---------------------------------------------------------------------------
// Bottom sheet (custom overlay: scrim + bottom-anchored rounded surface)
// ---------------------------------------------------------------------------

@Composable
fun HbBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    showClose: Boolean = true,
    full: Boolean = false,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Hb.scrim)
                .applyNoRipple(onDismiss),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .then(if (full) Modifier.fillMaxHeight(0.96f) else Modifier.heightIn(max = 640.dp))
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(Hb.surface)
                .applyNoRipple { /* swallow taps so they don't reach the scrim */ },
        ) {
            // grip
            Box(
                Modifier
                    .padding(top = 11.dp, bottom = 4.dp)
                    .align(Alignment.CenterHorizontally)
                    .size(width = 38.dp, height = 4.dp)
                    .clip(HbPill)
                    .background(Hb.line),
            )
            if (title != null) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = HbType.sheetTitle, color = Hb.ink, modifier = Modifier.weight(1f))
                    if (showClose) HbIconButton(HbIcons.x, onDismiss, iconSize = 22.dp)
                }
            }
            Column(
                Modifier
                    .then(if (full) Modifier.weight(1f, fill = false) else Modifier)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                content()
            }
            if (footer != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 14.dp)
                        .navigationBarsPadding(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = footer,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Toast (dark pill, above the FAB)
// ---------------------------------------------------------------------------

@Composable
fun BoxScope.HbToast(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = HbIcons.checkCircle,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(start = 18.dp, end = 18.dp, bottom = 90.dp)
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(14.dp), clip = false)
            .clip(RoundedCornerShape(14.dp))
            .background(Hb.toastBg)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        if (icon != null) HbIcon(icon, size = 18.dp, tint = Hb.accentSoft2)
        Text(
            message,
            style = HbType.meta.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = Hb.toastInk,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            Text(
                actionLabel,
                style = HbType.meta.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                color = Hb.accentSoft2,
                modifier = Modifier.clickable { onAction() },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Navigation drawer content (300dp surface). The caller supplies the scrim,
// slide animation, and full-screen overlay placement.
// ---------------------------------------------------------------------------

@Composable
fun HbDrawerContent(
    active: HbRoute,
    householdName: String,
    currentUser: String?,
    onSelect: (HbRoute) -> Unit,
    modifier: Modifier = Modifier,
    badges: Map<HbRoute, Int> = emptyMap(),
    dots: Set<HbRoute> = emptySet(),
    onOpenSettings: () -> Unit = {},
) {
    Column(
        modifier
            .width(300.dp)
            .fillMaxHeight()
            .background(Hb.surface)
            .bottomBorderRight(Hb.lineSoft)
            .statusBarsPadding()
            .padding(start = 14.dp, end = 14.dp, top = 22.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        // Brand
        Row(
            Modifier.padding(start = 10.dp, end = 10.dp, bottom = 20.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .shadow(1.dp, RoundedCornerShape(12.dp), clip = false)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Hb.accent),
                contentAlignment = Alignment.Center,
            ) { HbIcon(HbIcons.home, size = 22.dp, tint = Hb.onAccent) }
            Column {
                Text("HomeBase", style = HbType.appBarTitle.copy(fontSize = 22.sp), color = Hb.ink)
                Text(
                    householdName.uppercase(),
                    style = HbType.small.copy(fontSize = 11.sp, letterSpacing = HbType.eyebrow.letterSpacing),
                    color = Hb.ink3,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        // Nav list
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            HbRoute.entries.forEach { route ->
                val isActive = route == active
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(HbRadiusSm)
                        .background(if (isActive) Hb.accentSoft else Color.Transparent, HbRadiusSm)
                        .clickable { onSelect(route) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    HbIcon(route.icon, size = 21.dp, tint = if (isActive) Hb.accent else Hb.ink2)
                    Text(
                        route.label,
                        style = HbType.rowTitle.copy(
                            fontSize = 15.5.sp,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = if (isActive) Hb.accentInk else Hb.ink2,
                        modifier = Modifier.weight(1f),
                    )
                    val badge = badges[route]?.takeIf { it > 0 }
                    if (badge != null) {
                        Box(
                            Modifier
                                .heightIn(min = 20.dp)
                                .clip(HbPill)
                                .background(if (isActive) Hb.accent else Hb.surface3, HbPill)
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                badge.toString(),
                                style = HbType.small.copy(fontWeight = FontWeight.SemiBold),
                                color = if (isActive) Hb.onAccent else Hb.ink2,
                            )
                        }
                    } else if (route in dots) {
                        Box(Modifier.size(7.dp).clip(HbPill).background(Hb.clay))
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        // Footer
        Row(
            Modifier
                .fillMaxWidth()
                .bottomBorderTop(Hb.lineSoft)
                .padding(top = 14.dp, start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            HbAvatar(currentUser, size = 36.dp)
            Column(Modifier.weight(1f)) {
                Text(displayName(currentUser), style = HbType.label.copy(fontSize = 14.5.sp), color = Hb.ink)
                Text("Echtzeit-Sync aktiv", style = HbType.small, color = Hb.ink3)
            }
            // Account-corner gear → central settings (#101). Web has it in the topbar; on a phone
            // the drawer foot next to the user chip is the natural spot.
            HbIconButton(HbIcons.settings, onOpenSettings, tint = Hb.ink3, iconSize = 20.dp)
            Box(Modifier.size(8.dp).clip(HbPill).background(Hb.accent))
        }
    }
}

/**
 * Capitalised display name derived from a username (#155) — no hard-coded roster,
 * so HomeBase works for any household. Blank/unknown falls back to a neutral
 * placeholder rather than the seeded "Max". Conceptually identical to web's
 * userMeta().name (which keeps the raw username as its own fallback).
 */
fun displayName(username: String?): String =
    username?.trim()?.replaceFirstChar { it.uppercase() }?.takeIf { it.isNotBlank() } ?: "?"

private fun Modifier.bottomBorderRight(color: Color): Modifier = drawBehind {
    drawLine(color, Offset(size.width - 1f, 0f), Offset(size.width - 1f, size.height), 1f)
}

private fun Modifier.bottomBorderTop(color: Color): Modifier = drawBehind {
    drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), 1f)
}
