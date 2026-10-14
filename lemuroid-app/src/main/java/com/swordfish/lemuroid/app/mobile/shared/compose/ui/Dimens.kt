package com.swordfish.lemuroid.app.mobile.shared.compose.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Sizes of the redesign. Three sets:
 * - [Portrait]: direction A as drawn for the phone held upright.
 * - [LandscapeCompact]: phone held sideways (window height < 480 dp): the TV layout, shrunk.
 * - [LandscapeLarge]: TV box running the mobile UI (height >= 480 dp): the "A · TV" boards.
 *
 * Landscape = window wider than tall, the same test `MobileGameScreen` uses.
 */
@Immutable
data class Dimens(
    val isLandscape: Boolean,
    /** Side margin of the content (measured from the rail, when landscape). */
    val screenMargin: Dp,
    val cardSpacing: Dp,
    val cardCornerRadius: Dp,
    /** Cover of the Home rows (3:4). Portrait shows the title below it; landscape, cover only. */
    val rowCoverWidth: Dp,
    val rowCoverHeight: Dp,
    /** System art in the landscape Home carousel (277:192). Portrait has no carousel. */
    val systemCardWidth: Dp,
    val systemCardHeight: Dp,
    /**
     * Narrowest cover of the catalog grid; the column count is how many fit, but never fewer
     * than 3 (see `GamesScreen`).
     */
    val gridCoverMinWidth: Dp,
    val navRailWidth: Dp,
    val focusBorderWidth: Dp,
    val focusBorderGap: Dp,
    val focusScale: Float,
) {
    companion object {
        /** Window height that separates a sideways phone from a TV box. */
        val LANDSCAPE_LARGE_MIN_HEIGHT = 480.dp

        val Portrait =
            Dimens(
                isLandscape = false,
                screenMargin = 16.dp,
                cardSpacing = 12.dp,
                cardCornerRadius = 10.dp,
                rowCoverWidth = 104.dp,
                rowCoverHeight = 139.dp,
                systemCardWidth = 140.dp,
                systemCardHeight = 97.dp,
                // 3 columns from 360 to ~450 dp of width.
                gridCoverMinWidth = 96.dp,
                navRailWidth = 80.dp,
                focusBorderWidth = 3.dp,
                focusBorderGap = 3.dp,
                focusScale = 1.04f,
            )

        val LandscapeCompact =
            Portrait.copy(
                isLandscape = true,
                screenMargin = 24.dp,
                cardCornerRadius = 8.dp,
                rowCoverWidth = 72.dp,
                rowCoverHeight = 96.dp,
                systemCardWidth = 101.dp,
                systemCardHeight = 70.dp,
                // 7 columns on a ~730 dp wide phone (the SNES board of the spec).
                gridCoverMinWidth = 84.dp,
                // Rows have no title under the cover and only ~360 dp of height: the outline marks
                // focus, a scale would be clipped by the row.
                focusScale = 1f,
            )

        val LandscapeLarge =
            LandscapeCompact.copy(
                rowCoverWidth = 150.dp,
                rowCoverHeight = 200.dp,
                systemCardWidth = 208.dp,
                systemCardHeight = 144.dp,
                gridCoverMinWidth = 120.dp,
            )

        fun of(
            widthDp: Int,
            heightDp: Int,
        ): Dimens =
            when {
                widthDp <= heightDp -> Portrait
                heightDp.dp < LANDSCAPE_LARGE_MIN_HEIGHT -> LandscapeCompact
                else -> LandscapeLarge
            }
    }
}

@Composable
fun rememberDimens(): Dimens {
    val configuration = LocalConfiguration.current
    return remember(configuration.screenWidthDp, configuration.screenHeightDp) {
        Dimens.of(configuration.screenWidthDp, configuration.screenHeightDp)
    }
}

val LocalDimens = staticCompositionLocalOf { Dimens.Portrait }
