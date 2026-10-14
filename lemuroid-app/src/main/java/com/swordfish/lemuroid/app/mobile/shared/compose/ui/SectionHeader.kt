package com.swordfish.lemuroid.app.mobile.shared.compose.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Title of a Home row ("Jogos salvos", "Recentes", "Descubra"), with an optional action on the
 * right (e.g. a shortcut to the full tab). Horizontal padding follows [Dimens.screenMargin].
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val dimens = LocalDimens.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = dimens.screenMargin, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = LocalLemuroidTypography.current.section,
            color = LemuroidColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(
                    text = actionLabel,
                    style = LocalLemuroidTypography.current.support,
                    color = LemuroidColors.Accent,
                )
            }
        }
    }
}
