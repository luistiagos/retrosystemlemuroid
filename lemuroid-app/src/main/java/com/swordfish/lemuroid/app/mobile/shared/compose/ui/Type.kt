package com.swordfish.lemuroid.app.mobile.shared.compose.ui

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.swordfish.lemuroid.R

/**
 * Outfit (SIL OFL 1.1, https://github.com/Outfitio/Outfit-Fonts), static files. The variable
 * file is not used: `Font(resId, weight)` cannot pick a weight axis below Android 8.
 */
val OutfitFontFamily =
    FontFamily(
        Font(R.font.outfit_regular, FontWeight.Normal),
        Font(R.font.outfit_medium, FontWeight.Medium),
        Font(R.font.outfit_bold, FontWeight.Bold),
    )

private val defaults = Typography()

/**
 * Material slots keep their default sizes (they already match the spec: screen title 22,
 * hero 24, section 16, support 11); only the family changes and titles become bold.
 */
val LemuroidMaterialTypography =
    Typography(
        displayLarge = defaults.displayLarge.copy(fontFamily = OutfitFontFamily),
        displayMedium = defaults.displayMedium.copy(fontFamily = OutfitFontFamily),
        displaySmall = defaults.displaySmall.copy(fontFamily = OutfitFontFamily),
        headlineLarge = defaults.headlineLarge.copy(fontFamily = OutfitFontFamily, fontWeight = FontWeight.Bold),
        headlineMedium = defaults.headlineMedium.copy(fontFamily = OutfitFontFamily, fontWeight = FontWeight.Bold),
        headlineSmall = defaults.headlineSmall.copy(fontFamily = OutfitFontFamily, fontWeight = FontWeight.Bold),
        titleLarge = defaults.titleLarge.copy(fontFamily = OutfitFontFamily, fontWeight = FontWeight.Bold),
        titleMedium = defaults.titleMedium.copy(fontFamily = OutfitFontFamily, fontWeight = FontWeight.Bold),
        titleSmall = defaults.titleSmall.copy(fontFamily = OutfitFontFamily),
        bodyLarge = defaults.bodyLarge.copy(fontFamily = OutfitFontFamily),
        bodyMedium = defaults.bodyMedium.copy(fontFamily = OutfitFontFamily),
        bodySmall = defaults.bodySmall.copy(fontFamily = OutfitFontFamily),
        labelLarge = defaults.labelLarge.copy(fontFamily = OutfitFontFamily),
        labelMedium = defaults.labelMedium.copy(fontFamily = OutfitFontFamily),
        labelSmall = defaults.labelSmall.copy(fontFamily = OutfitFontFamily),
    )

/** Named styles of the redesign that have no matching Material slot. */
@Immutable
data class LemuroidTypography(
    val screenTitle: TextStyle,
    val heroTitle: TextStyle,
    val section: TextStyle,
    val cardTitle: TextStyle,
    val support: TextStyle,
) {
    companion object {
        val Default =
            LemuroidTypography(
                screenTitle = LemuroidMaterialTypography.titleLarge,
                heroTitle = LemuroidMaterialTypography.headlineSmall,
                section = LemuroidMaterialTypography.titleMedium,
                cardTitle =
                    TextStyle(
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    ),
                support =
                    TextStyle(
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    ),
            )
    }
}

val LocalLemuroidTypography = staticCompositionLocalOf { LemuroidTypography.Default }
