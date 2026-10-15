package com.swordfish.lemuroid.app.mobile.shared.compose.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Bottom sheet of the redesign (context menu, variants, save queue): surface colour, 16 dp top
 * corners and a muted drag handle. Behaviour is the plain material3 [ModalBottomSheet].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LemuroidBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = SheetShape,
        containerColor = LemuroidColors.Surface,
        contentColor = LemuroidColors.Text,
        tonalElevation = 0.dp,
        dragHandle = { BottomSheetDefaults.DragHandle(color = LemuroidColors.TextSupport.copy(alpha = 0.4f)) },
        content = content,
    )
}

/**
 * Title (and optional support line) at the top of a [LemuroidBottomSheet], with a divider below.
 * [action] sits at the end of the title line.
 */
@Composable
fun BottomSheetHeader(
    title: String,
    subtitle: String? = null,
    action: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = SheetMargin, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = LocalLemuroidTypography.current.section,
                color = LemuroidColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
            )
            action()
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = LocalLemuroidTypography.current.support,
                color = LemuroidColors.TextSupport,
                modifier = Modifier.padding(start = SheetMargin, end = SheetMargin, bottom = 8.dp),
            )
        }
        HorizontalDivider(color = LemuroidColors.Border)
    }
}

/** Horizontal margin of the sheet content. */
val SheetMargin = 16.dp

private val SheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
