package com.swordfish.lemuroid.app.mobile.shared.compose.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** `medium` is the portrait cover card (spec: 10). Landscape cards use [Dimens.cardCornerRadius] (8). */
val LemuroidShapes =
    Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(10.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

/** Buttons and chips (spec: pill). */
val PillShape = RoundedCornerShape(percent = 50)
