package com.swordfish.lemuroid.app.mobile.shared.compose.ui

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp

/**
 * Columns of the cover grids (catalog, Saved games): as many columns of at least [minSize] as fit,
 * never fewer than [MIN_COLUMNS] (the portrait spec): a plain `GridCells.Adaptive` drops to 2 on
 * narrow phones. [minSize] is `LocalDimens.gridCoverMinWidth`.
 */
class CoverGridCells(private val minSize: Dp) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(
        availableSize: Int,
        spacing: Int,
    ): List<Int> {
        val count = maxOf(MIN_COLUMNS, (availableSize + spacing) / (minSize.roundToPx() + spacing))
        val cellsSpace = (availableSize - spacing * (count - 1)).coerceAtLeast(0)
        val cellSize = cellsSpace / count
        val remainder = cellsSpace % count
        return List(count) { cellSize + if (it < remainder) 1 else 0 }
    }

    override fun equals(other: Any?): Boolean = other is CoverGridCells && other.minSize == minSize

    override fun hashCode(): Int = minSize.hashCode()
}

private const val MIN_COLUMNS = 3
