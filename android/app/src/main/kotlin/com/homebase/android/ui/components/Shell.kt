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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homebase.android.R
import com.homebase.android.ui.theme.Hb
import com.homebase.android.ui.theme.HbType
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Navigation routes (mirrors the drawer order in the design)
// ---------------------------------------------------------------------------

// Drawer/app-bar label resolved via stringResource at the call site (enum args can't be
// composable). labelRes points at the localized nav_* string; shortLabelRes is the compact
// label for the bottom tab bar (HB-09, #239) — only the core bar items need one, the rest
// reuse labelRes inside the "Mehr" sheet.
enum class HbRoute(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    @StringRes val shortLabelRes: Int = labelRes,
) {
    HEUTE(R.string.nav_today, HbIcons.home, R.string.nav_short_today),
    AUFGABEN(R.string.nav_tasks, HbIcons.checkCircle, R.string.nav_short_tasks),
    EINKAUF(R.string.nav_shopping, HbIcons.cart, R.string.nav_short_shopping),
    ZEIT(R.string.nav_time, HbIcons.clock, R.string.nav_short_time),
    NOTIZEN(R.string.nav_notes, HbIcons.note),
    ABWESENHEIT(R.string.nav_calendar, HbIcons.calendar),
    FAMILIENKALENDER(R.string.nav_family_calendar, HbIcons.grid),
    REZEPTE(R.string.nav_recipes, HbIcons.chef),
    WOCHENPLAN(R.string.nav_meal_plan, HbIcons.utensils),
}

// HB-09 (#239) — the mobile bottom tab bar shows these core areas (in this order) plus a
// "Mehr" button; everything else moves into the "Mehr" sheet so 8 areas never overflow on a
// narrow phone. Mirrors web's CORE_TABS (App.tsx): Heute · Aufgaben · Einkauf · Zeit; after
// web #270 "Zeit" sits in the bar and "Kalender" (Abwesenheit) lives under "Mehr". The drawer
// is unaffected — it still lists every route.
val CORE_ROUTES: List<HbRoute> = listOf(HbRoute.HEUTE, HbRoute.AUFGABEN, HbRoute.EINKAUF, HbRoute.ZEIT)
val MORE_ROUTES: List<HbRoute> = HbRoute.entries.filterNot { it in CORE_ROUTES }

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
    contentDescription: String? = null,
) {
    Box(
        modifier
            .size(44.dp)
            .clip(HbPill)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        HbIcon(icon, size = iconSize, tint = tint, contentDescription = contentDescription)
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
    leftContentDescription: String? = null,
    bordered: Boolean = false,
    titleSm: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    // a11y (#499): the left button is either the nav-drawer toggle (menu) or a back chevron;
    // derive a sensible TalkBack label from the icon unless the caller overrides it.
    val leftCd = leftContentDescription
        ?: if (leftIcon == HbIcons.menu) stringResource(R.string.cd_open_menu)
        else stringResource(R.string.cd_back)
    Row(
        modifier
            .fillMaxWidth()
            .background(if (bordered) Hb.surface else Hb.paper)
            .then(if (bordered) Modifier.bottomBorder(Hb.lineSoft) else Modifier)
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HbIconButton(leftIcon, onLeft, contentDescription = leftCd)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HbScreenScaffold(
    appBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    scrollable: Boolean = true,
    fab: (@Composable () -> Unit)? = null,
    // Pull-to-refresh (#269): when set, the scrolling area gets a Material3 pull-to-refresh gesture.
    // The lambda suspends until the refetch completes; the indicator spins for that duration. Null =
    // no gesture (screens without a refreshable list).
    onRefresh: (suspend () -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(Hb.paper).statusBarsPadding()) {
        appBar()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // The scrolling content, shared by both the plain and the pull-to-refresh paths.
            val scrollingContent: @Composable () -> Unit = {
                Column(
                    Modifier
                        .fillMaxSize()
                        .then(if (scrollable) Modifier.verticalScroll(scrollState) else Modifier),
                ) {
                    Spacer(Modifier.size(4.dp))
                    content()
                    Spacer(Modifier.size(110.dp))
                }
            }
            if (onRefresh != null) {
                val scope = rememberCoroutineScope()
                var refreshing by remember { mutableStateOf(false) }
                val refreshState = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = {
                        // Guard against a second pull while one is in flight; reset in finally so a
                        // failed refetch never wedges the gesture shut.
                        if (!refreshing) {
                            refreshing = true
                            scope.launch {
                                try { onRefresh() } finally { refreshing = false }
                            }
                        }
                    },
                    state = refreshState,
                    indicator = {
                        // Themed indicator (accent on the surface), pinned to the top-centre.
                        PullToRefreshDefaults.Indicator(
                            state = refreshState,
                            isRefreshing = refreshing,
                            modifier = Modifier.align(Alignment.TopCenter),
                            containerColor = Hb.surface,
                            color = Hb.accent,
                        )
                    },
                ) {
                    scrollingContent()
                }
            } else {
                scrollingContent()
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
                    if (showClose) HbIconButton(HbIcons.x, onDismiss, iconSize = 22.dp, contentDescription = stringResource(R.string.cd_close))
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
                        // a11y (#298): mark the current destination as selected + give nav rows
                        // the tab role, mirroring web's aria-current="page" on the drawer links.
                        .semantics {
                            selected = isActive
                            role = Role.Tab
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    HbIcon(route.icon, size = 21.dp, tint = if (isActive) Hb.accent else Hb.ink2)
                    Text(
                        stringResource(route.labelRes),
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
                Text(stringResource(R.string.drawer_sync_active), style = HbType.small, color = Hb.ink3)
            }
            // Account-corner gear → central settings (#101). Web has it in the topbar; on a phone
            // the drawer foot next to the user chip is the natural spot.
            HbIconButton(HbIcons.settings, onOpenSettings, tint = Hb.ink3, iconSize = 20.dp, contentDescription = stringResource(R.string.cd_settings))
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

// ---------------------------------------------------------------------------
// Bottom navigation bar (HB-09, #239) — mirrors web's mobile tab bar (App.tsx
// `.hb-tabbar`): the core areas + a "Mehr" entry that opens an overflow sheet.
// The "Mehr" entry highlights while one of the overflow areas is active, so the
// current area stays recognizable even when it lives behind "Mehr".
// ---------------------------------------------------------------------------

@Composable
fun HbBottomNav(
    active: HbRoute,
    onSelect: (HbRoute) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    badges: Map<HbRoute, Int> = emptyMap(),
    dots: Set<HbRoute> = emptySet(),
    moreActive: Boolean = active in MORE_ROUTES,
) {
    // Landmark for the bottom tab bar, mirroring web's `<nav aria-label>` (#239); read out by
    // TalkBack as the navigation region. Resolved here as a @Composable call, then set in the
    // non-composable semantics lambda.
    val navLabel = stringResource(R.string.nav_main)
    // onClick label for the "Mehr" tab — TalkBack announces "double tap to <label>", conveying it
    // opens a sheet rather than navigating (Compose has no aria-haspopup; resolved here as a
    // @Composable call, then applied in the non-composable semantics lambda). a11y (#298).
    val moreOpensLabel = stringResource(R.string.nav_more_opens_sheet)
    Row(
        modifier
            .fillMaxWidth()
            .background(Hb.surface)
            .bottomBorderTop(Hb.lineSoft)
            .navigationBarsPadding()
            .heightIn(min = 58.dp)
            .semantics { contentDescription = navLabel },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CORE_ROUTES.forEach { route ->
            HbBottomNavItem(
                icon = route.icon,
                label = stringResource(route.shortLabelRes),
                active = route == active,
                badge = badges[route]?.takeIf { it > 0 },
                dot = route in dots,
                onClick = { onSelect(route) },
                modifier = Modifier.weight(1f),
            )
        }
        // The "Mehr" overflow entry — highlighted while a hidden area is active. It opens a sheet
        // rather than selecting a tab, so it gets the "opens sheet" semantics (Button role + click
        // label) instead of selected/Role.Tab. a11y (#298).
        HbBottomNavItem(
            icon = HbIcons.more,
            label = stringResource(R.string.nav_more),
            active = moreActive,
            badge = null,
            dot = false,
            onClick = onMore,
            modifier = Modifier.weight(1f),
            onClickLabel = moreOpensLabel,
        )
    }
}

@Composable
private fun HbBottomNavItem(
    icon: ImageVector,
    label: String,
    active: Boolean,
    badge: Int?,
    dot: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // a11y (#298): set for the overflow ("Mehr") entry — its onClick gets this label so TalkBack
    // announces it opens a sheet rather than navigating. Null = a plain nav tab (selected/Role.Tab).
    onClickLabel: String? = null,
) {
    Column(
        modifier
            // NB: intentionally NOT fillMaxHeight() — the bar is the Column's non-weighted child, so
            // the outer Column measures it FIRST with the full screen height as its max. A
            // fillMaxHeight() item would expand to that full height, making the whole bar fill the
            // screen and collapsing the weighted content area above it to zero (the "centred bar,
            // blank screen" bug). Wrapping content keeps the bar at heightIn(min=58dp); the Row's
            // verticalAlignment = CenterVertically already centres the icon+label.
            .applyNoRipple(onClick)
            // a11y (#298): nav tabs expose selected-state + the tab role (web: aria-current="page");
            // the overflow entry instead advertises that it opens a sheet via the onClick label
            // (web: aria-haspopup="dialog"), keeping the default Button role.
            .semantics {
                if (onClickLabel != null) {
                    onClick(label = onClickLabel, action = null)
                } else {
                    selected = active
                    role = Role.Tab
                }
            }
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Box(contentAlignment = Alignment.Center) {
            HbIcon(icon, size = 23.dp, tint = if (active) Hb.accent else Hb.ink2)
            if (badge != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 11.dp, y = (-6).dp)
                        .heightIn(min = 16.dp)
                        .clip(HbPill)
                        .background(Hb.accent, HbPill)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        badge.toString(),
                        style = HbType.mono.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        color = Hb.onAccent,
                    )
                }
            } else if (dot) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-3).dp)
                        .size(7.dp)
                        .clip(HbPill)
                        .background(Hb.clay),
                )
            }
        }
        Text(
            label,
            style = HbType.small.copy(
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = if (active) Hb.accentInk else Hb.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// "Mehr" overflow sheet (HB-09, #239) — lists the areas that don't fit in the
// bottom bar; selecting one navigates and dismisses. Mirrors web's `.hb-morenav`.
// The active overflow area is highlighted (accent), matching the drawer rows.
// ---------------------------------------------------------------------------

@Composable
fun HbMoreSheet(
    active: HbRoute,
    onSelect: (HbRoute) -> Unit,
    onDismiss: () -> Unit,
    badges: Map<HbRoute, Int> = emptyMap(),
    dots: Set<HbRoute> = emptySet(),
) {
    HbBottomSheet(onDismiss = onDismiss, title = stringResource(R.string.nav_more)) {
        MORE_ROUTES.forEach { route ->
            val isActive = route == active
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(HbRadiusSm)
                    .background(if (isActive) Hb.accentSoft else Color.Transparent, HbRadiusSm)
                    .clickable { onSelect(route) }
                    // a11y (#298): same selected + tab-role semantics as the drawer/bottom-nav rows
                    // (web: aria-current="page" on the overflow links).
                    .semantics {
                        selected = isActive
                        role = Role.Tab
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                HbIcon(route.icon, size = 21.dp, tint = if (isActive) Hb.accent else Hb.ink2)
                Text(
                    stringResource(route.labelRes),
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
}
