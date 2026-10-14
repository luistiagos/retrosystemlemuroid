package com.swordfish.lemuroid.app.mobile.shared.compose.ui

import androidx.compose.ui.graphics.Color

/**
 * Redesign tokens (direction A, "vitrine"). Source of truth: section "Especificação visual" of
 * docs/backlogs/2026-10-13-redesenho-visual-inspirado-pegasus.md. The dark Material scheme below
 * is built from these; new components read them directly.
 */
object LemuroidColors {
    val Background = Color(0xFF0E0F13)
    val Surface = Color(0xFF1A1C22)
    val Bars = Color(0xFF14161B)
    val Border = Color(0xFF23262E)

    val Text = Color(0xFFF2F0EA)
    val TextSupport = Color(0xFFA4A7B0)

    /** Support text drawn over the ambient backdrop (cover art behind a gradient). */
    val TextSupportOnBackdrop = Color(0xFFC9CBD2)

    val Accent = Color(0xFFF2B544)
    val OnAccent = Color(0xFF1A1300)

    /** Selected item pill: accent at 16% over the bars. */
    val AccentPill = Color(0x29F2B544)

    /** AccentPill already composited over [Bars], for slots that need an opaque colour. */
    val AccentPillOpaque = Color(0xFF382F22)

    /** Error badge background. Not readable as text colour on [Background] (2.9:1). */
    val ErrorBadge = Color(0xFFB3261E)
}

val md_theme_light_primary = Color(0xFF007A40)
val md_theme_light_onPrimary = Color(0xFFFFFFFF)
val md_theme_light_primaryContainer = Color(0xFF9EFFCA)
val md_theme_light_onPrimaryContainer = Color(0xFF002107)
val md_theme_light_secondary = Color(0xFF516350)
val md_theme_light_onSecondary = Color(0xFFFFFFFF)
val md_theme_light_secondaryContainer = Color(0xFFD4E8D0)
val md_theme_light_onSecondaryContainer = Color(0xFF101F10)
val md_theme_light_tertiary = Color(0xFF39656C)
val md_theme_light_onTertiary = Color(0xFFFFFFFF)
val md_theme_light_tertiaryContainer = Color(0xFFBCEBF2)
val md_theme_light_onTertiaryContainer = Color(0xFF001F24)
val md_theme_light_error = Color(0xFFBA1A1A)
val md_theme_light_errorContainer = Color(0xFFFFDAD6)
val md_theme_light_onError = Color(0xFFFFFFFF)
val md_theme_light_onErrorContainer = Color(0xFF410002)
val md_theme_light_background = Color(0xFFFAFAFA)
val md_theme_light_onBackground = Color(0xFF09090B)
val md_theme_light_surface = Color(0xFFFAFAFA)
val md_theme_light_onSurface = Color(0xFF09090B)
val md_theme_light_surfaceVariant = Color(0xFFDEE5D9)
val md_theme_light_onSurfaceVariant = Color(0xFF424940)
val md_theme_light_outline = Color(0xFF72796F)
val md_theme_light_inverseOnSurface = Color(0xFFF0F1EB)
val md_theme_light_inverseSurface = Color(0xFF2F312D)
val md_theme_light_inversePrimary = Color(0xFF00FF7A)
val md_theme_light_shadow = Color(0xFF000000)
val md_theme_light_surfaceTint = Color(0xFF007A40)
val md_theme_light_outlineVariant = Color(0xFFC2C9BD)
val md_theme_light_scrim = Color(0xFF000000)

val md_theme_dark_primary = LemuroidColors.Accent
val md_theme_dark_onPrimary = LemuroidColors.OnAccent
val md_theme_dark_primaryContainer = LemuroidColors.AccentPillOpaque
val md_theme_dark_onPrimaryContainer = LemuroidColors.Accent
val md_theme_dark_secondary = LemuroidColors.Accent
val md_theme_dark_onSecondary = LemuroidColors.OnAccent
val md_theme_dark_secondaryContainer = LemuroidColors.AccentPillOpaque
val md_theme_dark_onSecondaryContainer = LemuroidColors.Accent
val md_theme_dark_tertiary = LemuroidColors.TextSupportOnBackdrop
val md_theme_dark_onTertiary = LemuroidColors.Background
val md_theme_dark_tertiaryContainer = LemuroidColors.Surface
val md_theme_dark_onTertiaryContainer = LemuroidColors.Text

// `error` is used as a text colour in several Settings screens, so it keeps the readable M3 dark
// red; the spec's #B3261E goes to `errorContainer` (badge background).
val md_theme_dark_error = Color(0xFFF2B8B5)
val md_theme_dark_onError = Color(0xFF601410)
val md_theme_dark_errorContainer = LemuroidColors.ErrorBadge
val md_theme_dark_onErrorContainer = Color(0xFFFFFFFF)
val md_theme_dark_background = LemuroidColors.Background
val md_theme_dark_onBackground = LemuroidColors.Text
val md_theme_dark_surface = LemuroidColors.Bars
val md_theme_dark_onSurface = LemuroidColors.Text
val md_theme_dark_surfaceVariant = LemuroidColors.Surface
val md_theme_dark_onSurfaceVariant = LemuroidColors.TextSupport
val md_theme_dark_outline = Color(0xFF6B6F79)
val md_theme_dark_inverseOnSurface = LemuroidColors.Background
val md_theme_dark_inverseSurface = LemuroidColors.Text
val md_theme_dark_inversePrimary = Color(0xFF7A5A12)
val md_theme_dark_shadow = Color(0xFF000000)

// Neutral tint: an accent tint would turn every elevated surface amber.
val md_theme_dark_surfaceTint = LemuroidColors.TextSupport
val md_theme_dark_outlineVariant = LemuroidColors.Border
val md_theme_dark_scrim = Color(0xFF000000)
val md_theme_dark_surfaceContainerLowest = LemuroidColors.Background
val md_theme_dark_surfaceContainerLow = LemuroidColors.Bars
val md_theme_dark_surfaceContainer = LemuroidColors.Bars
val md_theme_dark_surfaceContainerHigh = LemuroidColors.Surface
val md_theme_dark_surfaceContainerHighest = LemuroidColors.Surface
val md_theme_dark_surfaceBright = LemuroidColors.Surface
val md_theme_dark_surfaceDim = LemuroidColors.Background

val seed = LemuroidColors.Accent
