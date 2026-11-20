package com.swordfish.lemuroid.lib.core

import android.content.SharedPreferences
import androidx.core.content.edit
import com.fredporciuncula.flow.preferences.FlowSharedPreferences
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.migration.DesmumeMigrationHandler
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class CoresSelection(
    private val sharedPreferencesFactory: Lazy<SharedPreferences>,
    private val desmumeMigrationHandler: DesmumeMigrationHandler,
) {
    private val sharedPreferences by lazy { sharedPreferencesFactory.get() }

    private val flowSharedPreferences by lazy { FlowSharedPreferences(sharedPreferences) }

    data class SelectedCore(
        val system: GameSystem,
        val coreConfig: SystemCoreConfig,
    )

    fun getSelectedCores(): Flow<List<SelectedCore>> {
        val configurableSystems =
            GameSystem.all()
                .filter { it.systemCoreConfigs.size > 1 }

        val configurationFlows =
            configurableSystems.map { system ->
                getSelectedCoreConfigForSystem(system)
                    .map { SelectedCore(system, it) }
            }

        return combine(configurationFlows) { it.toList() }
    }

    suspend fun updateCoreConfigForSystem(
        system: GameSystem,
        coreID: CoreID,
    ) = withContext(Dispatchers.IO) {
        sharedPreferences.edit()
            .putString(computeSystemPreferenceKey(system.id), coreID.coreName)
            .commit()
    }

    suspend fun getCoreConfigForSystem(system: GameSystem): SystemCoreConfig {
        return getSelectedCoreConfigForSystem(system).first()
    }

    /**
     * Resolves the core to launch [game] with: a per-game override saved for [game.downloadKey]
     * takes precedence over the system-wide default so switching a problematic game to an
     * alternative core (e.g. Azahar -> legacy Citra) does not alter other games of the same system.
     */
    suspend fun getCoreConfigForGame(
        system: GameSystem,
        game: Game,
    ): SystemCoreConfig {
        if (system.systemCoreConfigs.size <= 1) {
            return system.systemCoreConfigs.first()
        }
        val gameOverrideName =
            withContext(Dispatchers.IO) {
                sharedPreferences.getString(computeGamePreferenceKey(game.downloadKey), null)
            }
        if (gameOverrideName != null) {
            system.systemCoreConfigs.firstOrNull { it.coreID.coreName == gameOverrideName }
                ?.let { return it }
        }
        return getCoreConfigForSystem(system)
    }

    suspend fun updateCoreConfigForGame(
        game: Game,
        coreID: CoreID,
    ) = withContext(Dispatchers.IO) {
        sharedPreferences.edit()
            .putString(computeGamePreferenceKey(game.downloadKey), coreID.coreName)
            .commit()
    }

    suspend fun clearCoreConfigForGame(game: Game) =
        withContext(Dispatchers.IO) {
            sharedPreferences.edit()
                .remove(computeGamePreferenceKey(game.downloadKey))
                .commit()
        }

    suspend fun isFallbackPromptDismissed(
        game: Game,
        coreName: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            sharedPreferences.getBoolean(computeFallbackDismissedKey(game.downloadKey, coreName), false)
        }

    suspend fun dismissFallbackPrompt(
        game: Game,
        coreName: String,
    ) = withContext(Dispatchers.IO) {
        sharedPreferences.edit()
            .putBoolean(computeFallbackDismissedKey(game.downloadKey, coreName), true)
            .commit()
    }

    suspend fun clearFallbackPromptDismissed(
        game: Game,
        coreName: String,
    ) = withContext(Dispatchers.IO) {
        sharedPreferences.edit()
            .remove(computeFallbackDismissedKey(game.downloadKey, coreName))
            .commit()
    }

    suspend fun clearAllFallbackPromptsDismissed(game: Game) {
        val system = GameSystem.findByIdOrNull(game.systemId) ?: return
        withContext(Dispatchers.IO) {
            val editor = sharedPreferences.edit()
            system.systemCoreConfigs.forEach { config ->
                editor.remove(computeFallbackDismissedKey(game.downloadKey, config.coreID.coreName))
            }
            editor.commit()
        }
    }

    private fun getSelectedCoreConfigForSystem(system: GameSystem): Flow<SystemCoreConfig> {
        return getSelectedCoreNameForSystem(system)
            .map { coreName ->
                system.systemCoreConfigs.firstOrNull { it.coreID.coreName == coreName }
                    ?: system.systemCoreConfigs.first()
            }
    }

    private fun getSelectedCoreNameForSystem(system: GameSystem): Flow<String> {
        return flow {
            val preferenceKey = computeSystemPreferenceKey(system.id)
            val currentValue = flowSharedPreferences.sharedPreferences.getString(preferenceKey, null)
            var defaultValue = currentValue

            if (currentValue == null) {
                defaultValue = getDefaultCoreForSystem(system)
                flowSharedPreferences.sharedPreferences.edit(true) {
                    putString(preferenceKey, defaultValue)
                }
            }

            val preference =
                flowSharedPreferences.getString(
                    preferenceKey,
                    defaultValue,
                )
            emitAll(preference.asFlow())
        }.flowOn(Dispatchers.IO)
    }

    // TODO Also get rid of this when desmume is gone
    private fun getDefaultCoreForSystem(system: GameSystem): String {
        if (system.id == SystemID.NDS) {
            return if (desmumeMigrationHandler.hasPendingDesmumeSaves()) {
                CoreID.DESMUME.coreName
            } else {
                CoreID.MELONDS.coreName
            }
        }
        return system.systemCoreConfigs.firstOrNull()?.coreID?.coreName
            ?: throw IllegalStateException("No cores configured for system: ${system.id}")
    }

    companion object {
        private const val CORE_SELECTION_BINDING_PREFERENCE_BASE_KEY = "pref_key_core_selection"
        private const val GAME_CORE_SELECTION_PREFERENCE_BASE_KEY = "pref_key_game_core_selection"
        private const val CORE_FALLBACK_DISMISSED_BASE_KEY = "pref_key_core_fallback_dismissed"

        fun computeSystemPreferenceKey(systemID: SystemID) =
            "${CORE_SELECTION_BINDING_PREFERENCE_BASE_KEY}_${systemID.dbname}"

        fun computeGamePreferenceKey(gameKey: String) = "${GAME_CORE_SELECTION_PREFERENCE_BASE_KEY}_$gameKey"

        fun computeFallbackDismissedKey(
            gameKey: String,
            coreName: String,
        ) = "${CORE_FALLBACK_DISMISSED_BASE_KEY}_${coreName}_$gameKey"
    }
}
