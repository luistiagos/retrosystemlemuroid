package com.swordfish.lemuroid.app.mobile.feature.installed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidGameImage
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidGameTexts
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.effects.bounceClick
import com.swordfish.lemuroid.lib.library.GameSystem

@Composable
fun LemuroidInstalledGameCard(
    modifier: Modifier = Modifier,
    item: InstalledGameGroupUiModel,
    onClick: () -> Unit = { },
    onLongClick: () -> Unit = { },
) {
    val game = item.group.game
    val context = LocalContext.current
    val systemName = remember(game.systemId) {
        val res = GameSystem.findByIdOrNull(game.systemId)?.shortTitleResId
        if (res != null) context.getString(res) else game.systemId
    }

    val cardAlpha = if (item.isAvailable) 1f else 0.6f

    ElevatedCard(
        modifier = modifier
            .alpha(cardAlpha)
            .bounceClick(onClick = onClick, onLongClick = onLongClick)
            .clip(RoundedCornerShape(16.dp)),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
        )
    ) {
        Box {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                LemuroidGameImage(game = game)
                LemuroidGameTexts(game = game)
            }

            // Chip do Sistema no topo esquerdo
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .align(Alignment.TopStart),
            ) {
                Text(
                    text = systemName,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    maxLines = 1,
                )
            }

            // Badges no topo direito (Versões ou Indisponibilidade)
            Row(
                modifier = Modifier
                    .padding(6.dp)
                    .align(Alignment.TopEnd),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!item.isAvailable) {
                    Box(
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(22.dp)
                            .background(
                                color = MaterialTheme.colorScheme.error,
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = stringResource(R.string.installed_media_unavailable),
                            tint = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }

                if (item.group.installedVariantsCount > 1) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Layers,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(11.dp),
                            )
                            Text(
                                text = " ${item.group.installedVariantsCount}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
