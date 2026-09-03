package com.swordfish.lemuroid.app.shared.game.viewmodel

import android.content.Context
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.settings.SettingsManager
import com.swordfish.lemuroid.app.shared.telemetry.TelemetryReporter
import com.swordfish.lemuroid.common.graphics.GraphicsUtils
import com.swordfish.lemuroid.common.graphics.takeScreenshot
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.saves.IncompatibleStateException
import com.swordfish.lemuroid.lib.saves.SaveState
import com.swordfish.lemuroid.lib.saves.SavesManager
import com.swordfish.lemuroid.lib.saves.StatesManager
import com.swordfish.lemuroid.lib.saves.StatesPreviewManager
import com.swordfish.libretrodroid.GLRetroView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class GameViewModelSaves(
    private val appContext: Context,
    private val system: GameSystem,
    private val game: Game,
    private val systemCoreConfig: SystemCoreConfig,
    private val retroGameView: GameViewModelRetroGameView,
    private val settingsManager: SettingsManager,
    private val savesManager: SavesManager,
    private val statesManager: StatesManager,
    private val statesPreviewManager: StatesPreviewManager,
    private val sideEffects: GameViewModelSideEffects,
) {
    private var currentQuickSave: SaveState? = null

    suspend fun saveSlot(index: Int) {
        getCurrentSaveState()?.let {
            statesManager.setSlotSave(game, it, systemCoreConfig.coreID, index)
            runCatching {
                takeScreenshotPreview(index)
            }
        }
    }

    suspend fun loadSlot(index: Int) {
        try {
            statesManager.getSlotSave(game, systemCoreConfig.coreID, index)?.let {
                val loaded = loadSaveState(it)

                if (!loaded) {
                    sideEffects.showToast(appContext.getString(R.string.game_toast_load_state_failed))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            val errorMessageId =
                when (e) {
                    is IncompatibleStateException -> R.string.error_message_incompatible_state
                    else -> R.string.game_toast_load_state_failed
                }
            sideEffects.showToast(appContext.getString(errorMessageId))
        }
    }

    /**
     * Grava o que precisa sobreviver a saida do jogo, na ordem do que o jogador sentiria mais
     * falta: a SRAM (o progresso que o proprio cartucho guardaria) antes do autosave (conveniencia
     * que o app refaz jogando de novo).
     *
     * **Nunca lanca.** `serializeSRAM`/`serializeState` passam por `runOnGLThread`, que desiste com
     * [GLRetroView.GLThreadTimeoutException] depois de 30 s — e em producao isso acontece de
     * verdade ao sair de uma sessao de Dolphin. Deixar a excecao subir do `viewModelScope.launch`
     * levava o `UncaughtExceptionHandler` a matar o processo `:game` e mostrar a tela de crash em
     * vez de simplesmente sair do jogo.
     *
     * @return false quando algo nao pode ser gravado, para o chamador avisar o usuario.
     */
    suspend fun saveOnExit(game: Game): Boolean {
        val view = retroGameView.retroGameView ?: return true

        val sramSaved = trySaveSRAM(view, game)

        // Um timeout ja custou 30 s de espera. So vale tentar o autosave se a GLThread voltou a
        // responder — senao o usuario paga outro timeout inteiro so para sair do jogo.
        if (!sramSaved && !glThreadResponds(view)) {
            Timber.w("Skipping autosave on exit: GL thread is not draining its event queue")
            return false
        }

        val autoSaved = trySaveAutoSave(game)
        return sramSaved && autoSaved
    }

    private suspend fun trySaveSRAM(
        view: GLRetroView,
        game: Game,
    ): Boolean {
        repeat(GL_SAVE_ATTEMPTS) { attempt ->
            try {
                val sramState = withContext(Dispatchers.IO) { view.serializeSRAM() }
                savesManager.setSaveRAM(game, sramState)
                Timber.i("Stored sram file with size: ${sramState.size}")
                return true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: GLRetroView.GLThreadTimeoutException) {
                Timber.w(e, "SRAM save timed out (attempt ${attempt + 1}/$GL_SAVE_ATTEMPTS)")
                // So repete se a GLThread voltou a drenar a fila; senao seriam mais 30 s parados.
                if (attempt == GL_SAVE_ATTEMPTS - 1 || !glThreadResponds(view)) {
                    reportExitSaveFailure("serializeSRAM", e)
                    return false
                }
            } catch (e: Throwable) {
                Timber.e(e, "Error while saving sram")
                reportExitSaveFailure("serializeSRAM", e)
                return false
            }
        }
        return false
    }

    private suspend fun trySaveAutoSave(game: Game): Boolean =
        try {
            saveAutoSave(game)
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.e(e, "Error while saving autosave on exit")
            reportExitSaveFailure("serializeState", e)
            false
        }

    /**
     * Sonda barata: enfileira um no-op e espera pouco. Se a GLThread esta drenando a fila, volta na
     * hora; se esta presa dentro de um callback do renderer, custa [GL_PROBE_TIMEOUT_MS] em vez dos
     * 30 s que uma chamada de verdade custaria antes de estourar.
     */
    private suspend fun glThreadResponds(view: GLRetroView): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val latch = CountDownLatch(1)
                view.queueEvent { latch.countDown() }
                latch.await(GL_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: Throwable) {
                false
            }
        }

    private fun reportExitSaveFailure(
        call: String,
        error: Throwable,
    ) {
        TelemetryReporter.reportThrowable(
            component = "game",
            thread = Thread.currentThread(),
            error = error,
            extraContext = "phase=exit-save; call=$call; system=${system.id.dbname}; game=${game.title}",
            terminal = false,
        )
    }

    suspend fun saveAutoSave(game: Game) {
        if (!isAutoSaveEnabled()) return
        val state = getCurrentSaveState()

        if (state != null) {
            statesManager.setAutoSave(game, systemCoreConfig.coreID, state)
            Timber.i("Stored autosave file with size: ${state?.state?.size}")
        }
    }

    // On some cores unserialize fails with no reason. So we need to try multiple times.
    suspend fun restoreAutoSaveAsync(saveState: SaveState) {
        // PPSSPP and Mupen64 initialize some state while rendering the first frame, so we have to wait before restoring
        // the autosave. O unserialize precisa rodar na GLThread — quem garante isso e o
        // runOnGLThread dentro do GLRetroView, nao o dispatcher deste chamador. Por isso
        // loadSaveState roda em Dispatchers.IO: o chamador nunca pode ser a main thread,
        // que ficaria presa no latch sem timeout do runOnGLThread.
        if (!isAutoSaveEnabled()) return

        try {
            retroGameView.waitGLEvent<GLRetroView.GLRetroEvents.FrameRendered>()
            restoreQuickSave(saveState)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.e(e, "Error while loading auto-save")
        }
    }

    // serializeState/getCurrentDisk passam por runOnGLThread, que bloqueia o chamador ate a
    // GLThread drenar a fila. Nunca chamar da main thread — dai o withContext(IO).
    private suspend fun getCurrentSaveState(): SaveState? {
        val retroGameView = retroGameView.retroGameView ?: return null
        return withContext(Dispatchers.IO) {
            val currentDisk =
                if (system.hasMultiDiskSupport) {
                    retroGameView.getCurrentDisk()
                } else {
                    0
                }
            SaveState(
                retroGameView.serializeState(),
                SaveState.Metadata(currentDisk, systemCoreConfig.statesVersion),
            )
        }
    }

    private suspend fun isAutoSaveEnabled(): Boolean {
        return systemCoreConfig.statesSupported && settingsManager.autoSave()
    }

    private suspend fun takeScreenshotPreview(index: Int) {
        val sizeInDp = StatesPreviewManager.PREVIEW_SIZE_DP
        val previewSize = GraphicsUtils.convertDpToPixel(sizeInDp, appContext).roundToInt()
        val preview = retroGameView.retroGameView?.takeScreenshot(previewSize, 3)
        if (preview != null) {
            statesPreviewManager.setPreviewForSlot(game, preview, systemCoreConfig.coreID, index)
        }
    }

    // Now that we wait for the first rendered frame this is probably no longer needed, but we'll keep it just to be sure
    private suspend fun restoreQuickSave(saveState: SaveState) {
        var times = 10

        while (!loadSaveState(saveState) && times > 0) {
            delay(200)
            times--
        }
    }

    // Idem getCurrentSaveState: getAvailableDisks/getCurrentDisk/changeDisk/unserializeState
    // sao todos runOnGLThread e bloqueiam quem chama.
    private suspend fun loadSaveState(saveState: SaveState): Boolean {
        val retroGameView = retroGameView.retroGameView ?: return false

        if (systemCoreConfig.statesVersion != saveState.metadata.version) {
            throw IncompatibleStateException()
        }

        return withContext(Dispatchers.IO) {
            if (system.hasMultiDiskSupport &&
                retroGameView.getAvailableDisks() > 1 &&
                retroGameView.getCurrentDisk() != saveState.metadata.diskIndex
            ) {
                retroGameView.changeDisk(saveState.metadata.diskIndex)
            }

            retroGameView.unserializeState(saveState.state)
        }
    }

    suspend fun saveQuickSave() {
        currentQuickSave = getCurrentSaveState()
        sideEffects.showToast(appContext.getString(R.string.game_toast_quick_save_saved))
    }

    suspend fun loadQuickSave() {
        val saveToLoad = currentQuickSave
        if (saveToLoad == null) {
            sideEffects.showToast(appContext.getString(R.string.game_toast_load_state_failed))
            return
        }
        val loaded = loadSaveState(saveToLoad)
        if (loaded) {
            sideEffects.showToast(appContext.getString(R.string.game_toast_quick_save_loaded))
        } else {
            sideEffects.showToast(appContext.getString(R.string.game_toast_load_state_failed))
        }
    }

    companion object {
        /** Tentativas de gravar a SRAM na saida. A segunda so acontece se a GLThread respondeu. */
        private const val GL_SAVE_ATTEMPTS = 2

        /** Quanto esperar a GLThread responder um no-op antes de considera-la presa. */
        private const val GL_PROBE_TIMEOUT_MS = 2_000L
    }
}
