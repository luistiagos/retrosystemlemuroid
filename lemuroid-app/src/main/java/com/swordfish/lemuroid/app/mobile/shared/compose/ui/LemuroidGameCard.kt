package com.swordfish.lemuroid.app.mobile.shared.compose.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.effects.bounceClick  
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun LemuroidGameCard(
    modifier: Modifier = Modifier,
    game: Game,
    onClick: () -> Unit = { },
    onLongClick: () -> Unit = { },
) {
    ElevatedCard(
        modifier = modifier
            .bounceClick(onClick = onClick)
            .clip(RoundedCornerShape(16.dp)),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            LemuroidGameImage(game = game)
            LemuroidGameTexts(game = game)
        }
    }
}
