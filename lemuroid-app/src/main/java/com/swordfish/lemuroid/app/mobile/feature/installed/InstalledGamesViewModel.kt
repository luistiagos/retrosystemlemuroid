package com.swordfish.lemuroid.app.mobile.feature.installed

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.app.shared.search.SystemSearchResolver
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class InstalledGamesViewModel(
    private val retrogradeDb: RetrogradeDatabase,
    private val directoriesManager: DirectoriesManager,
    private val storageAvailabilityMonitor: StorageAvailabilityMonitor,
    private val systemSearchResolver: SystemSearchResolver,
) : ViewModel() {

    class Factory(
        private val retrogradeDb: RetrogradeDatabase,
        private val directoriesManager: DirectoriesManager,
        private val storageAvailabilityMonitor: StorageAvailabilityMonitor,
        private val systemSearchResolver: SystemSearchResolver,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return InstalledGamesViewModel(
                retrogradeDb,
                directoriesManager,
                storageAvailabilityMonitor,
                systemSearchResolver,
            ) as T
        }
    }

    private val romsDirPrefix: String by lazy {
        runCatching {
            directoriesManager.getInternalRomsDirectory().toUri().toString().trimEnd('/')
        }.getOrDefault("")
    }

    private val managedMarker: String by lazy {
        directoriesManager.getManagedRomsMarker()
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            reconcileManagedRoms()
        }
    }

    private suspend fun reconcileManagedRoms() {
        val romsDir = runCatching { directoriesManager.getInternalRomsDirectory() }.getOrNull() ?: return
        if (!romsDir.exists() || !romsDir.isDirectory) return

        val systemDirs = romsDir.listFiles()?.filter { it.isDirectory } ?: return
        val discovered = mutableListOf<com.swordfish.lemuroid.lib.library.db.entity.DownloadedRom>()

        for (dir in systemDirs) {
            val systemId = dir.name
            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (file.isFile && file.length() > 0) {
                    val name = file.name
                    if (!name.endsWith(".download") && !name.endsWith(".part") && !name.endsWith(".tmp")) {
                        discovered.add(
                            com.swordfish.lemuroid.lib.library.db.entity.DownloadedRom(
                                systemId = systemId,
                                fileName = name,
                                fileSize = file.length(),
                                downloadedAt = 0L,
                            )
                        )
                    }
                }
            }
        }

        if (discovered.isNotEmpty()) {
            retrogradeDb.downloadedRomDao().insertIgnore(discovered)
        }
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    private val rawGroupsFlow = _searchQuery
        .debounce(150)
        .flatMapLatest { query ->
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                retrogradeDb.gameDao().observeInstalledGameGroups(romsDirPrefix, managedMarker)
            } else {
                val matchedSystemIds = systemSearchResolver.resolveSystemIds(trimmed).ifEmpty { listOf("__none__") }
                retrogradeDb.gameDao().searchInstalledGameGroups(
                    query = trimmed,
                    matchedSystemIds = matchedSystemIds,
                    romsDirPrefix = romsDirPrefix,
                    managedMarker = managedMarker,
                )
            }
        }

    val installedGames: StateFlow<List<InstalledGameGroupUiModel>> =
        combine(rawGroupsFlow, storageAvailabilityMonitor.mediaStateChanges) { groups, _ ->
            groups.map { group ->
                InstalledGameGroupUiModel(
                    group = group,
                    isAvailable = storageAvailabilityMonitor.isGameAvailable(group.game),
                )
            }
        }
            .flowOn(Dispatchers.IO)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList(),
            )

    suspend fun getInstalledVariants(systemId: String, title: String): List<Game> = withContext(Dispatchers.IO) {
        retrogradeDb.gameDao().getInstalledVariantsForGroup(
            systemId = systemId,
            title = title,
            romsDirPrefix = romsDirPrefix,
            managedMarker = managedMarker,
        )
    }
}
