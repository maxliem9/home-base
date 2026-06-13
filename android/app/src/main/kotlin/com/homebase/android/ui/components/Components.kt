package com.homebase.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homebase.android.R
import com.homebase.android.ui.theme.Hb
import com.homebase.android.ui.theme.HbType

// ---------------------------------------------------------------------------
// Shapes
// ---------------------------------------------------------------------------

val HbPill = RoundedCornerShape(percent = 50)
val HbRadius = RoundedCornerShape(11.dp)
val HbRadiusSm = RoundedCornerShape(8.dp)
val HbRadiusLg = RoundedCornerShape(16.dp)

// ---------------------------------------------------------------------------
// Icon convenience
// ---------------------------------------------------------------------------

@Composable
fun HbIcon(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 22.dp, tint: Color = Hb.ink2) {
    Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = modifier.size(size))
}

// ---------------------------------------------------------------------------
// Card
// ---------------------------------------------------------------------------

@Composable
fun HbCard(
    modifier: Modifier = Modifier,
    pad: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .shadow(1.dp, HbRadius, clip = false, ambientColor = Hb.ink, spotColor = Hb.ink)
            .clip(HbRadius)
            .background(Hb.surface)
            .border(1.dp, Hb.lineSoft, HbRadius)
            .then(if (pad) Modifier.padding(18.dp) else Modifier),
    ) { content() }
}

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

enum class HbButtonVariant { Primary, Secondary, Ghost, Soft, Danger }
enum class HbButtonSize { Md, Sm }

@Composable
fun HbButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: HbButtonVariant = HbButtonVariant.Primary,
    size: HbButtonSize = HbButtonSize.Md,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val bg: Color
    val fg: Color
    var border: BorderStroke? = null
    when (variant) {
        HbButtonVariant.Primary -> { bg = Hb.accent; fg = Hb.onAccent }
        HbButtonVariant.Secondary -> { bg = Hb.surface; fg = Hb.ink; border = BorderStroke(1.dp, Hb.line) }
        HbButtonVariant.Ghost -> { bg = Color.Transparent; fg = Hb.ink2 }
        HbButtonVariant.Soft -> { bg = Hb.accentSoft; fg = Hb.accentInk }
        HbButtonVariant.Danger -> { bg = Color.Transparent; fg = Hb.danger }
    }
    val vPad = if (size == HbButtonSize.Sm) 8.dp else 11.dp
    val hPad = if (size == HbButtonSize.Sm) 14.dp else 18.dp
    val fontSize = if (size == HbButtonSize.Sm) 13.5.sp else 14.5.sp

    Row(
        modifier
            .clip(HbPill)
            .then(if (bg != Color.Transparent) Modifier.background(bg, HbPill) else Modifier)
            .then(border?.let { Modifier.border(it, HbPill) } ?: Modifier)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = hPad, vertical = vPad),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) HbIcon(icon, size = 18.dp, tint = fg)
        Text(text, style = TextStyle(fontSize = fontSize, fontWeight = FontWeight.SemiBold), color = fg)
    }
}

// ---------------------------------------------------------------------------
// Confirm dialog
// ---------------------------------------------------------------------------

/** A pending confirmation request — `null` means no dialog is shown. */
data class HbConfirm(val message: String, val onConfirm: () -> Unit)

/** Yes/No confirmation dialog used for cross-person actions (e.g. the partner's timer). */
@Composable
fun HbConfirmDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    // Default to the localized "Yes"/"Cancel"; callers may override for a specific verb.
    confirmLabel: String = stringResource(R.string.action_yes),
    dismissLabel: String = stringResource(R.string.action_cancel),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { HbButton(confirmLabel, onConfirm, variant = HbButtonVariant.Primary, size = HbButtonSize.Sm) },
        dismissButton = { HbButton(dismissLabel, onDismiss, variant = HbButtonVariant.Secondary, size = HbButtonSize.Sm) },
        text = { Text(message, style = HbType.body, color = Hb.ink) },
        containerColor = Hb.surface,
    )
}

// ---------------------------------------------------------------------------
// Badge & priority
// ---------------------------------------------------------------------------

enum class HbTone { Neutral, Accent, Clay, Today, Soon, Over, Far }

@Composable
fun HbBadge(text: String, tone: HbTone = HbTone.Neutral, modifier: Modifier = Modifier) {
    val (bg, fg) = when (tone) {
        HbTone.Neutral -> Hb.surface2 to Hb.ink2
        HbTone.Accent, HbTone.Today -> Hb.accentSoft to Hb.accentInk
        HbTone.Clay -> Hb.claySoft to Hb.clay
        HbTone.Soon -> Hb.surface2 to Hb.ink2
        HbTone.Over -> Hb.overBg to Hb.overInk
        HbTone.Far -> Hb.surface2 to Hb.ink3
    }
    Box(
        modifier
            .clip(HbPill)
            .background(bg, HbPill)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(text, style = HbType.small.copy(fontWeight = FontWeight.SemiBold), color = fg)
    }
}

/** Priority pill: colored dot + localized label. */
@Composable
fun HbPriority(priority: String?, modifier: Modifier = Modifier) {
    if (priority == null) return
    val (color, label) = when (priority.uppercase()) {
        "HIGH" -> Hb.prioHigh to stringResource(R.string.priority_high)
        "MEDIUM" -> Hb.prioMedium to stringResource(R.string.priority_medium)
        "LOW" -> Hb.prioLow to stringResource(R.string.priority_low)
        else -> return
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(HbPill).background(color))
        Text(label, style = HbType.meta.copy(fontWeight = FontWeight.SemiBold), color = Hb.ink2)
    }
}

// ---------------------------------------------------------------------------
// Checkbox
// ---------------------------------------------------------------------------

@Composable
fun HbCheck(checked: Boolean, onCheckedChange: () -> Unit, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val shape = RoundedCornerShape(if (size <= 20.dp) 6.dp else 7.dp)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(if (checked) Hb.accent else Hb.surface, shape)
            .border(2.dp, if (checked) Hb.accent else Hb.line, shape)
            .clickable { onCheckedChange() },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) HbIcon(HbIcons.check, size = size * 0.62f, tint = Hb.onAccent)
    }
}

// ---------------------------------------------------------------------------
// Avatar
// ---------------------------------------------------------------------------

/**
 * Per-user avatar-hue overrides (Teil von #100): username → chosen hue (0..359), loaded once
 * from the household-visible roster (GET /users avatarHue) and provided app-wide in MainActivity.
 * [HbAvatar] reads it so a colour a member picked on web shows up at EVERY Android avatar site
 * without threading the hue through each call. Empty default = everyone "automatic" (derived).
 * Display-only here; the Android picker is deferred to the settings mirror (#101).
 */
val LocalAvatarHues = compositionLocalOf { emptyMap<String, Int>() }

@Composable
fun HbAvatar(userId: String?, modifier: Modifier = Modifier, size: Dp = 26.dp) {
    if (userId == null) {
        Box(
            modifier.size(size).clip(HbPill).background(Hb.surface2, HbPill)
                .border(1.dp, Hb.line, HbPill),
        )
        return
    }
    // A stored override (from the shared roster) wins over the derived username-hash hue.
    val override = LocalAvatarHues.current[userId]
    Box(modifier.size(size).clip(HbPill).background(Hb.userColor(userId, override)), contentAlignment = Alignment.Center) {
        Text(
            Hb.userInitial(userId),
            style = TextStyle(fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold),
            color = Color.White,
        )
    }
}

// ---------------------------------------------------------------------------
// Section label / divider
// ---------------------------------------------------------------------------

@Composable
fun HbSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = HbType.sectionLabel,
        color = Hb.ink3,
        modifier = modifier.padding(start = 2.dp, bottom = 11.dp),
    )
}

@Composable
fun HbDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Box(Modifier.fillMaxWidth().size(1.dp).background(Hb.lineSoft))
    }
}

// ---------------------------------------------------------------------------
// Inputs
// ---------------------------------------------------------------------------

@Composable
fun HbTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    mono: Boolean = false,
    password: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val style = (if (mono) HbType.mono.copy(fontSize = 13.5.sp) else HbType.body).copy(color = Hb.ink)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .clip(HbRadiusSm)
            .background(Hb.surface, HbRadiusSm)
            .border(if (focused) 1.5.dp else 1.dp, if (focused) Hb.accent else Hb.line, HbRadiusSm)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        textStyle = style,
        singleLine = singleLine,
        minLines = minLines,
        // A password field uses the password IME (no suggestion strip / autocorrect caching).
        keyboardOptions = if (password) {
            keyboardOptions.copy(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password)
        } else {
            keyboardOptions
        },
        visualTransformation = if (password) {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        cursorBrush = SolidColor(Hb.accent),
        interactionSource = interaction,
        decorationBox = { inner ->
            if (value.isEmpty()) Text(placeholder, style = style.copy(color = Hb.ink3))
            inner()
        },
    )
}

/** [TextFieldValue] overload — same look as above, but exposes the caret/selection so
 *  callers can insert text at the cursor (e.g. the notes editor's image insert). No
 *  password / keyboard-options variant — it's for plain multi-line body text only. */
@Composable
fun HbTextField(
    value: androidx.compose.ui.text.input.TextFieldValue,
    onValueChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    mono: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val style = (if (mono) HbType.mono.copy(fontSize = 13.5.sp) else HbType.body).copy(color = Hb.ink)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .clip(HbRadiusSm)
            .background(Hb.surface, HbRadiusSm)
            .border(if (focused) 1.5.dp else 1.dp, if (focused) Hb.accent else Hb.line, HbRadiusSm)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        textStyle = style,
        singleLine = singleLine,
        minLines = minLines,
        cursorBrush = SolidColor(Hb.accent),
        interactionSource = interaction,
        decorationBox = { inner ->
            if (value.text.isEmpty()) Text(placeholder, style = style.copy(color = Hb.ink3))
            inner()
        },
    )
}

@Composable
fun HbField(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, style = HbType.label, color = Hb.ink2)
        content()
    }
}

/** Quick-add pill bar: optional leading glyph + inline field + round accent submit button. */
@Composable
fun HbQuickAdd(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leading: ImageVector? = null,
    submitIcon: ImageVector = HbIcons.plus,
) {
    Row(
        modifier
            .fillMaxWidth()
            .shadow(1.dp, HbPill, clip = false)
            .clip(HbPill)
            .background(Hb.surface, HbPill)
            .border(1.dp, Hb.line, HbPill)
            .padding(start = 16.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (leading != null) HbIcon(leading, size = 20.dp, tint = Hb.ink3)
        val interaction = remember { MutableInteractionSource() }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            textStyle = HbType.body.copy(color = Hb.ink),
            singleLine = true,
            cursorBrush = SolidColor(Hb.accent),
            interactionSource = interaction,
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, style = HbType.body.copy(color = Hb.ink3))
                inner()
            },
        )
        Box(
            Modifier.size(40.dp).clip(HbPill).background(Hb.accent).clickable { onSubmit() },
            contentAlignment = Alignment.Center,
        ) { HbIcon(submitIcon, size = 20.dp, tint = Hb.onAccent) }
    }
}

// ---------------------------------------------------------------------------
// Pick chips & tag chips
// ---------------------------------------------------------------------------

@Composable
fun HbPick(
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .clip(HbPill)
            .background(if (active) Hb.accentSoft else Hb.surface, HbPill)
            .then(if (active) Modifier else Modifier.border(1.dp, Hb.line, HbPill))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun HbPickText(text: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    HbPick(active, onClick, modifier) {
        Text(text, style = HbType.label.copy(fontSize = 13.5.sp), color = if (active) Hb.accentInk else Hb.ink2)
    }
}

@Composable
fun HbTagChip(
    text: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    static: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val bg = when { active -> Hb.accent; static -> Hb.surface2; else -> Hb.surface }
    val fg = when { active -> Hb.onAccent; else -> Hb.ink2 }
    Box(
        modifier
            .clip(HbPill)
            .background(bg, HbPill)
            .then(if (active || static) Modifier else Modifier.border(1.dp, Hb.line, HbPill))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 13.dp, vertical = 6.dp),
    ) {
        Text(text, style = HbType.meta.copy(fontWeight = FontWeight.SemiBold), color = fg)
    }
}

// ---------------------------------------------------------------------------
// Segmented control
// ---------------------------------------------------------------------------

@Composable
fun HbSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    leadingIcons: List<ImageVector?> = emptyList(),
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(HbPill)
            .background(Hb.surface2, HbPill)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val active = i == selectedIndex
            Row(
                Modifier
                    .weight(1f)
                    .clip(HbPill)
                    .then(if (active) Modifier.shadow(1.dp, HbPill, clip = false).background(Hb.surface, HbPill) else Modifier)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leadingIcons.getOrNull(i)?.let { HbIcon(it, size = 17.dp, tint = if (active) Hb.ink else Hb.ink2) }
                Text(
                    label,
                    style = HbType.meta.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    color = if (active) Hb.ink else Hb.ink2,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// List rows
// ---------------------------------------------------------------------------

/** Generic list row: 13dp vertical padding + bottom hairline (optional). */
@Composable
fun HbRow(
    modifier: Modifier = Modifier,
    divider: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(horizontal = 2.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        if (divider) Box(Modifier.fillMaxWidth().size(1.dp).background(Hb.lineSoft))
    }
}

/** Small 3px separator dot used inside meta rows. */
@Composable
fun HbDotSep(modifier: Modifier = Modifier) {
    Box(modifier.size(3.dp).clip(HbPill).background(Hb.ink3.copy(alpha = 0.5f)))
}

// ---------------------------------------------------------------------------
// Empty state
// ---------------------------------------------------------------------------

@Composable
fun HbEmpty(icon: ImageVector, title: String, hint: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 54.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(60.dp).clip(HbRadiusLg).background(Hb.surface2, HbRadiusLg),
            contentAlignment = Alignment.Center,
        ) { HbIcon(icon, size = 26.dp, tint = Hb.ink3) }
        Text(
            title,
            style = HbType.cardTitle,
            color = Hb.ink2,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            hint,
            style = HbType.meta.copy(fontSize = 14.sp, lineHeight = 21.sp),
            color = Hb.ink3,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Card header (title + optional trailing link)
// ---------------------------------------------------------------------------

@Composable
fun HbCardHead(title: String, modifier: Modifier = Modifier, linkText: String? = null, onLink: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(bottom = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = HbType.cardTitle, color = Hb.ink)
        if (linkText != null) {
            Row(
                Modifier.clip(HbRadiusSm).then(if (onLink != null) Modifier.clickable { onLink() } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(linkText, style = HbType.meta.copy(fontWeight = FontWeight.SemiBold), color = Hb.ink3)
                HbIcon(HbIcons.chevronRight, size = 15.dp, tint = Hb.ink3)
            }
        }
    }
}

/** Truncating single-line text helper. */
@Composable
fun HbEllipsisText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    Text(text, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}
