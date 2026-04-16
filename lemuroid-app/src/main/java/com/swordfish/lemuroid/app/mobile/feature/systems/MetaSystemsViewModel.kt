package com.swordfish.lemuroid.app.mobile.feature.systems

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.app.shared.systems.MetaSystemInfo
import com.swordfish.lemuroid.app.utils.android.isTvDevice
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.HeavySystemFilter
import com.swordfish.lemuroid.lib.library.MetaSystemID
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.metaSystemID
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class MetaSystemsViewModel(retrogradeDb: RetrogradeDatabase, appContext: Context) : ViewModel() {
    class Factory(
        val retrogradeDb: RetrogradeDatabase,
        val appContext: Context,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MetaSystemsViewModel(retrogradeDb, appContext) as T
        }
    }

    private val tvHiddenSystems = setOf(MetaSystemID.PSP, MetaSystemID.NINTENDO_3DS)

    private val deviceTier = HeavySystemFilter.deviceTier(appContext)
    private val hiddenSystems: Set<MetaSystemID> = HeavySystemFilter.excludedSystems(deviceTier)
        .mapNotNull { systemId ->
            GameSystem.findByIdOrNull(systemId.dbname)?.let { it.metaSystemID() }
        }.toSet()

    // null = loading (Room query not yet emitted), emptyList = genuinely no systems.
    // StateFlow with SharingStarted.Eagerly starts collecting immediately upon ViewModel
    // creation — before the composable subscribes — so the first emission is ready by the
    // time the first frame is rendered, eliminating the race condition that caused the
    // systems screen to briefly show empty on slow/cold devices.
    val availableMetaSystems: StateFlow<List<MetaSystemInfo>?> =
        retrogradeDb.gameDao()
            .selectSystemsWithCount()
            .map { systemCounts ->
                val hideSystems = appContext.isTvDevice()
                systemCounts.asSequence()
                    .filter { (_, count) -> count > 0 }
                    // findByIdOrNull: skips rows with system IDs not recognised by GameSystem,
                    // preventing a NoSuchElementException crash on stale or unexpected DB entries.
                    .mapNotNull { (systemId, count) ->
                        GameSystem.findByIdOrNull(systemId)?.let { it.metaSystemID() to count }
                    }
                    .filter { (metaSystemId, _) -> !hideSystems || metaSystemId !in tvHiddenSystems }
                    .filter { (metaSystemId, _) -> metaSystemId !in hiddenSystems }
                    .groupBy { (metaSystemId, _) -> metaSystemId }
                    .map { (metaSystemId, counts) -> MetaSystemInfo(metaSystemId, counts.sumOf { it.second }) }
                    .sortedBy { it.getName(appContext) }
                    .toList()
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = null,
            )
}
