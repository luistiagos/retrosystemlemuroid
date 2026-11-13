package com.swordfish.lemuroid.app.shared.game.viewmodel

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.swordfish.lemuroid.BuildConfig
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.settings.SettingsManager
import com.swordfish.lemuroid.app.shared.game.GameProcessSession
import com.swordfish.lemuroid.app.shared.game.ShaderChooser
import com.swordfish.lemuroid.app.shared.rumble.RumbleManager
import com.swordfish.lemuroid.app.shared.settings.HDModeQuality
import com.swordfish.lemuroid.app.shared.settings.ScreenAspectRatio
import com.swordfish.lemuroid.common.coroutines.MutableStateProperty
import com.swordfish.lemuroid.common.coroutines.launchOnState
import com.swordfish.lemuroid.common.view.disableTouchEvents
import com.swordfish.lemuroid.lib.bios.BiosDownloader
import com.swordfish.lemuroid.lib.core.CoreDownloader
import com.swordfish.lemuroid.lib.core.CoreVariable
import com.swordfish.lemuroid.lib.core.CoreVariablesManager
import com.swordfish.lemuroid.lib.game.GameLoader
import com.swordfish.lemuroid.lib.game.GameLoaderError
import com.swordfish.lemuroid.lib.game.GameLoaderException
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.saves.SaveStateCompatibility
import com.swordfish.lemuroid.lib.storage.RomFiles
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ImmersiveMode
import com.swordfish.libretrodroid.Variable
import com.swordfish.libretrodroid.VirtualFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import kotlin.time.Duration.Companion.seconds

@OptIn(FlowPreview::class)
class GameViewModelRetroGameView(
    private val appContext: Context,
    private val system: GameSystem,
    private val systemCoreConfig: SystemCoreConfig,
    private val settingsManager: SettingsManager,
    private val coreVariablesManager: CoreVariablesManager,
    private val sideEffects: GameViewModelSideEffects,
    private val rumbleManager: RumbleManager,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {
    sealed interface GameState {
        data object Uninitialized : GameState

        data class Loading(val message: String) : GameState

        data class Loaded(
            val gameData: GameLoader.GameData,
            val retroViewData: GLRetroViewData,
        ) : GameState

        data object Ready : GameState
    }

    private val gameState: MutableStateFlow<GameState> = MutableStateFlow(GameState.Uninitialized)

    private val retroGameViewFlow = MutableStateFlow<GLRetroView?>(null)
    var retroGameView: GLRetroView? by MutableStateProperty(retroGameViewFlow)
    lateinit var saveStateCompatibility: SaveStateCompatibility
        private set
    var fdsSideCount: Int? = null
        private set

    // Layout de tela unica do core (SystemCoreConfig.singleScreenLayout). Muda com o jogo aberto
    // quando o usuario troca o layout pelo menu: a volta do menu relê as variaveis no RESUMED.
    private val singleScreenLayout = MutableStateFlow(false)

    // Telas trocadas pelo botao do pad (SystemCoreConfig.screenSwap). Nao e persistido: cada jogo
    // abre com a tela de cima, como o L3 do core. A volta do menu nao desfaz a troca: a variavel
    // nao esta nas options do updateCoreVariables, e o LibretroDroid grava variavel por chave.
    private val screenSwapped = MutableStateFlow(false)

    // Uma instancia so: a tela coleta com collectAsState, e um combine novo a cada chamada
    // reiniciaria a coleta em toda recomposicao.
    private val singleTopScreen: Flow<Boolean> =
        combine(singleScreenLayout, screenSwapped) { single, swapped -> isSingleTopScreen(single, swapped) }

    // A proporcao escolhida em Ajustes, para voltar a ela quando a tela unica deixa de esticar.
    // Ja filtrada por effectiveScreenAspectRatio: no 3DS e sempre AUTO.
    private var userTargetAspectRatio = ScreenAspectRatio.AUTO.targetAspectRatio

    fun getGameState(): Flow<GameState> {
        return gameState.debounce(200)
    }

    /**
     * Tela unica mostrando a tela de cima: so nela os controles vao por cima do jogo e a imagem
     * estica em paisagem. Com as telas trocadas, a tela visivel e a de toque, e valem as colunas
     * e a proporcao do core, como em Superior — inferior.
     */
    fun isSingleScreenLayout(): Flow<Boolean> = singleTopScreen

    /** Se o botao de troca de telas do pad e tratado aqui ([SystemCoreConfig.screenSwap]). */
    fun handlesScreenSwap(): Boolean = systemCoreConfig.screenSwap != null

    /**
     * Inverte as telas: grava a variavel do core, que aplica no quadro seguinte, pelo mesmo caminho
     * da troca de layout pelo menu. Sem a GLRetroView ainda, ignora sem inverter, para o estado do
     * app nao divergir do core. T3 de
     * docs/bugs/retest/2026-11-07-3ds-sem-tela-unica-controles-em-coluna-e-imagem-deformada.md
     */
    fun toggleScreenSwap() {
        val screenSwap = systemCoreConfig.screenSwap ?: return
        val view = retroGameView ?: return
        val swapped = !screenSwapped.value
        val variable = screenSwap.variable(swapped)
        Timber.i("Updating core variable: ${variable.key} ${variable.value}")
        view.updateVariables(Variable(variable.key, variable.value))
        screenSwapped.value = swapped
    }

    /**
     * Estica a imagem ate preencher a area de jogo, ou devolve a proporcao de Ajustes. Quem decide
     * e a tela do celular, que conhece a orientacao: em retrato a area e alta e esticar achataria
     * a imagem (ver docs/bugs/retest/2026-11-07-3ds-sem-tela-unica-controles-em-coluna-e-imagem-deformada.md).
     */
    suspend fun setStretchToFill(fill: Boolean) {
        val view = retroGameViewFlow()
        view.targetAspectRatio = if (fill) GLRetroView.TARGET_ASPECT_FILL else userTargetAspectRatio
    }

    suspend fun initialize(
        applicationContext: Context,
        game: Game,
        systemCoreConfig: SystemCoreConfig,
        gameLoader: GameLoader,
        requestLoadSave: Boolean,
    ) {
        val currentState = gameState.value
        if (currentState != GameState.Uninitialized) return
        fdsSideCount = null

        val autoSaveEnabled = settingsManager.autoSave()
        val filter = settingsManager.screenFilter()
        val hdMode = settingsManager.hdMode()
        val hdModeQuality = settingsManager.hdModeQuality()
        val lowLatencyAudio = settingsManager.lowLatencyAudio()
        val enableRumble = settingsManager.enableRumble()
        val directLoad = settingsManager.allowDirectGameLoad()
        val enableImmersiveMode = settingsManager.enableImmersiveMode()
        val screenAspectRatio = effectiveScreenAspectRatio(systemCoreConfig, settingsManager.screenAspectRatio())
        userTargetAspectRatio = screenAspectRatio.targetAspectRatio

        val hasMicrophonePermission =
            ContextCompat.checkSelfPermission(
                applicationContext,
                android.Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

        val enableMicrophone = systemCoreConfig.supportsMicrophone && hasMicrophonePermission

        var coreDownloadRetried = false
        var biosDownloadRetried = false
        try {
            while (true) {
                var shouldRetry = false
                gameLoader.load(
                    applicationContext,
                    game,
                    requestLoadSave && autoSaveEnabled,
                    systemCoreConfig,
                    directLoad,
                )
                    .catch { e ->
                        if (e is CancellationException) throw e
                        if (
                            e is GameLoaderException &&
                            e.error is GameLoaderError.LoadCore &&
                            !coreDownloadRetried
                        ) {
                            coreDownloadRetried = true
                            val msg =
                                appContext.getString(
                                    com.swordfish.lemuroid.ext.R.string.game_loading_download_core,
                                )
                            gameState.value = GameState.Loading(msg)
                            try {
                                CoreDownloader.downloadCore(applicationContext, systemCoreConfig.coreID)
                                shouldRetry = true
                            } catch (downloadEx: CancellationException) {
                                throw downloadEx
                            } catch (downloadEx: Exception) {
                                Timber.e(downloadEx, "Direct core download failed: ${downloadEx.message}")
                                sideEffects.requestFailureFinish(getErrorMessage(GameLoaderError.LoadCore))
                                shouldRetry = false
                            }
                        } else if (
                            e is GameLoaderException &&
                            e.error is GameLoaderError.MissingBiosFiles &&
                            !biosDownloadRetried
                        ) {
                            biosDownloadRetried = true
                            val msg =
                                appContext.getString(
                                    com.swordfish.lemuroid.ext.R.string.game_loading_download_bios,
                                )
                            gameState.value = GameState.Loading(msg)
                            val biosError = e.error as GameLoaderError.MissingBiosFiles
                            try {
                                BiosDownloader.downloadMissing(applicationContext, biosError.missingFiles)
                                shouldRetry = true
                            } catch (downloadEx: CancellationException) {
                                throw downloadEx
                            } catch (downloadEx: Exception) {
                                Timber.e(downloadEx, "BIOS download failed: ${downloadEx.message}")
                                sideEffects.requestFailureFinish(getErrorMessage(e.error))
                                shouldRetry = false
                            }
                        } else {
                            val message = if (e is GameLoaderException) getErrorMessage(e.error) else ""
                            sideEffects.requestFailureFinish(message)
                        }
                    }
                    // flowOn cobre tudo que esta ACIMA dele — precisa ficar depois do catch para
                    // que o corpo do catch (CoreDownloader/BiosDownloader, que fazem mkdirs,
                    // isElfCompatible e md5 do arquivo inteiro) tambem saia da main thread.
                    // O collect abaixo continua na main, que e onde a UI precisa dele.
                    .flowOn(Dispatchers.IO)
                    .debounce(50)
                    .collect { loadingState ->
                        gameState.value =
                            if (loadingState is GameLoader.LoadingState.Ready) {
                                Timber.i("Setting state to loaded")
                                singleScreenLayout.value =
                                    systemCoreConfig.isSingleScreenLayout(
                                        loadingState.gameData.coreVariables.asIterable(),
                                    )
                                val retroViewData =
                                    buildRetroViewData(
                                        applicationContext,
                                        systemCoreConfig,
                                        loadingState.gameData,
                                        hdMode,
                                        hdModeQuality,
                                        filter,
                                        lowLatencyAudio,
                                        enableRumble,
                                        enableMicrophone,
                                        enableImmersiveMode,
                                        screenAspectRatio,
                                    )
                                GameState.Loaded(
                                    gameData = loadingState.gameData,
                                    retroViewData = retroViewData,
                                )
                            } else {
                                GameState.Loading(getLoadingMessage(loadingState))
                            }
                    }
                if (!shouldRetry) break
            }
        } catch (e: CancellationException) {
            if (gameState.value is GameState.Loading) {
                gameState.value = GameState.Uninitialized
            }
            throw e
        }
    }

    fun createRetroView(
        context: Context,
        lifecycle: LifecycleOwner,
    ): Pair<GameLoader.GameData, GLRetroView> {
        val currentState = gameState.value
        if (currentState !is GameState.Loaded) throw IllegalStateException("Game is not loaded.")

        GameProcessSession.onCoreCreated()
        saveStateCompatibility = currentState.gameData.saveStateCompatibility

        val result =
            GLRetroView(context, currentState.retroViewData)
                .apply {
                    isFocusable = false
                    isFocusableInTouchMode = false
                }

        if (!system.hasTouchScreen) {
            result.disableTouchEvents()
        }

        lifecycle.lifecycle.addObserver(result)

        if (BuildConfig.DEBUG) {
            runCatching {
                printRetroVariables(result)
            }
        }

        retroGameViewFlow.value = result
        gameState.value = GameState.Ready

        return currentState.gameData to result
    }

    suspend fun retroGameViewFlow() =
        retroGameViewFlow
            .filterNotNull()
            .first()

    suspend fun waitRetroGameViewInitialized() {
        retroGameViewFlow()
    }

    suspend inline fun <reified T> waitGLEvent() {
        val retroView = retroGameViewFlow()
        retroView.getGLRetroEvents()
            .filterIsInstance<T>()
            .first()
    }

    private fun buildRetroViewData(
        appContext: Context,
        systemCoreConfig: SystemCoreConfig,
        gameData: GameLoader.GameData,
        hdMode: Boolean,
        hdModeQuality: HDModeQuality,
        screenFilter: String,
        lowLatencyAudio: Boolean,
        requestRumble: Boolean,
        requestMicrophone: Boolean,
        enableImmersiveMode: Boolean,
        screenAspectRatio: ScreenAspectRatio,
    ): GLRetroViewData {
        return GLRetroViewData(appContext).apply {
            coreFilePath = gameData.coreLibrary

            when (val gameFiles = gameData.gameFiles) {
                is RomFiles.Standard -> {
                    val gameFile =
                        gameFiles.files.firstOrNull()
                            ?: throw GameLoaderException(GameLoaderError.LoadGame)
                    gameFilePath = gameFile.absolutePath
                    fdsSideCount =
                        if (gameData.game.systemId == SystemID.FDS.dbname) {
                            countFdsSides(gameFile)
                        } else {
                            null
                        }
                }

                is RomFiles.Virtual -> {
                    gameVirtualFiles = gameFiles.files.map { VirtualFile(it.filePath, it.fd) }
                }
            }

            systemDirectory = gameData.systemDirectory.absolutePath
            savesDirectory = gameData.savesDirectory.absolutePath
            variables = gameData.coreVariables.map { Variable(it.key, it.value) }.toTypedArray()
            saveRAMState = gameData.saveRAMData
            shader =
                ShaderChooser.getShaderForSystem(
                    appContext,
                    hdMode,
                    hdModeQuality,
                    screenFilter,
                    GameSystem.findByIdOrNull(gameData.game.systemId) ?: GameSystem.findById("snes"),
                )
            preferLowLatencyAudio = lowLatencyAudio
            rumbleEventsEnabled = requestRumble
            skipDuplicateFrames = systemCoreConfig.skipDuplicateFrames
            enableMicrophone = requestMicrophone
            immersiveMode = buildImmersiveModeConfiguration(enableImmersiveMode)
            targetAspectRatio = screenAspectRatio.targetAspectRatio
        }
    }

    private fun countFdsSides(file: File): Int? {
        if (!file.isFile) return null

        val length = file.length()
        if (length < FDS_BYTES_PER_SIDE) return null

        val header = ByteArray(FDS_HEADER_SIZE)
        val bytesRead = file.inputStream().use { it.read(header) }
        val hasHeader =
            bytesRead == FDS_HEADER_SIZE &&
                header[0] == 'F'.code.toByte() &&
                header[1] == 'D'.code.toByte() &&
                header[2] == 'S'.code.toByte() &&
                header[3] == 0x1a.toByte()

        val sides =
            if (hasHeader) {
                val headerSides = header[4].toInt() and 0xff
                if (headerSides > 0) headerSides else ((length - FDS_HEADER_SIZE) / FDS_BYTES_PER_SIDE).toInt()
            } else {
                (length / FDS_BYTES_PER_SIDE).toInt()
            }

        return sides.coerceIn(1, FDS_MAX_SIDES)
    }

    private fun buildImmersiveModeConfiguration(enableImmersiveMode: Boolean): ImmersiveMode? {
        return if (enableImmersiveMode) {
            ImmersiveMode(blendFactor = 0.05f)
        } else {
            null
        }
    }

    private fun getLoadingMessage(loadingState: GameLoader.LoadingState): String {
        return when (loadingState) {
            is GameLoader.LoadingState.LoadingCore -> {
                appContext.getString(com.swordfish.lemuroid.ext.R.string.game_loading_download_core)
            }

            is GameLoader.LoadingState.LoadingGame -> {
                appContext.getString(R.string.game_loading_preparing_game)
            }

            else -> ""
        }
    }

    private fun printRetroVariables(retroGameView: GLRetroView) {
        scope.launch {
            // Espera o primeiro frame: o core e criado na GLThread (dlopen do .so, que em core
            // grande passa de 1 s) e getVariables devolve vazio ate ele existir.
            waitGLEvent<GLRetroView.GLRetroEvents.FrameRendered>()
            // Some cores do not immediately call SET_VARIABLES so we might need to wait a little bit
            delay(1.seconds)
            retroGameView.getVariables().forEach {
                Timber.i("Libretro variable: $it")
            }
        }
    }

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)

        owner.launchOnState(Lifecycle.State.STARTED) {
            initializeRetroGameViewErrorsFlow()
        }

        owner.launchOnState(Lifecycle.State.RESUMED) {
            initializeCoreVariablesFlow()
        }

        owner.launchOnState(Lifecycle.State.RESUMED) {
            initializeRumbleFlow()
        }
    }

    private suspend fun initializeCoreVariablesFlow() {
        try {
            waitRetroGameViewInitialized()
            val options = coreVariablesManager.getOptionsForCore(system.id, systemCoreConfig)
            updateCoreVariables(options)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    private suspend fun initializeRumbleFlow() {
        val retroGameView = retroGameViewFlow()
        val rumbleEvents = retroGameView.getRumbleEvents()
        rumbleManager.collectAndProcessRumbleEvents(systemCoreConfig, rumbleEvents)
    }

    private suspend fun initializeRetroGameViewErrorsFlow() {
        retroGameViewFlow().getGLRetroErrors()
            .catch { Timber.e(it, "Exception in GLRetroErrors. Ironic.") }
            .collect { handleRetroViewError(it) }
    }

    private fun updateCoreVariables(options: List<CoreVariable>) {
        val updatedVariables =
            options.map { Variable(it.key, it.value) }
                .toTypedArray()

        updatedVariables.forEach {
            Timber.i("Updating core variable: ${it.key} ${it.value}")
        }

        retroGameView?.updateVariables(*updatedVariables)
        singleScreenLayout.value = systemCoreConfig.isSingleScreenLayout(options)
    }

    private fun handleRetroViewError(errorCode: Int) {
        Timber.e("Error in GLRetroView $errorCode")
        val gameLoaderError =
            when (errorCode) {
                GLRetroView.ERROR_GL_NOT_COMPATIBLE -> GameLoaderError.GLIncompatible
                GLRetroView.ERROR_LOAD_GAME -> GameLoaderError.LoadGame
                GLRetroView.ERROR_LOAD_LIBRARY -> {
                    // O arquivo do core existe mas o linker falhou ao carregá-lo.
                    // Deleta o arquivo para forçar novo download na próxima tentativa.
                    deleteCachedCoreFile(systemCoreConfig.coreID)
                    GameLoaderError.LoadCore
                }
                GLRetroView.ERROR_SERIALIZATION -> GameLoaderError.Saves
                else -> GameLoaderError.Generic
            }

        val isRomLoadFailure =
            gameLoaderError is GameLoaderError.LoadGame ||
                gameLoaderError is GameLoaderError.Generic
        sideEffects.requestFailureFinish(getErrorMessage(gameLoaderError), isRomLoadFailure)
    }

    /** Remove o arquivo do core dos diretórios de cache para forçar novo download. */
    private fun deleteCachedCoreFile(coreID: CoreID) {
        runCatching {
            val coresDir = File(appContext.filesDir, "cores")
            coresDir.walkBottomUp()
                .filter { it.name == coreID.libretroFileName }
                .forEach {
                    Timber.w("Deleting corrupted core file: $it")
                    it.delete()
                }
        }.onFailure {
            Timber.e(it, "Failed to delete corrupted core file")
        }
    }

    private fun getErrorMessage(gameError: GameLoaderError): String {
        val message =
            when (gameError) {
                is GameLoaderError.GLIncompatible -> {
                    appContext.getString(R.string.game_loader_error_gl_incompatible)
                }
                is GameLoaderError.Generic -> {
                    appContext.getString(R.string.game_loader_error_generic)
                }
                is GameLoaderError.LoadCore -> {
                    appContext.getString(com.swordfish.lemuroid.ext.R.string.game_loader_error_load_core)
                }
                is GameLoaderError.LoadGame -> {
                    appContext.getString(R.string.game_loader_error_load_game)
                }
                is GameLoaderError.Saves -> {
                    appContext.getString(R.string.game_loader_error_save)
                }
                is GameLoaderError.UnsupportedArchitecture -> {
                    appContext.getString(R.string.game_loader_error_unsupported_architecture)
                }
                is GameLoaderError.MissingBiosFiles -> {
                    appContext.getString(R.string.game_loader_error_missing_bios, gameError.missingFiles)
                }
            }

        return message
    }

    companion object {
        private const val FDS_BYTES_PER_SIDE = 65_500L
        private const val FDS_HEADER_SIZE = 16
        private const val FDS_MAX_SIDES = 8

        /**
         * A proporcao de Ajustes que vale para este sistema. No 3DS ela e ignorada: cada tela tem
         * proporcao fixa, e qualquer alvo diferente do core a deforma (T6 de
         * docs/bugs/retest/2026-11-07-3ds-sem-tela-unica-controles-em-coluna-e-imagem-deformada.md).
         */
        internal fun effectiveScreenAspectRatio(
            systemCoreConfig: SystemCoreConfig,
            userChoice: ScreenAspectRatio,
        ): ScreenAspectRatio {
            return if (systemCoreConfig.ignoresScreenAspectRatio) ScreenAspectRatio.AUTO else userChoice
        }

        /**
         * Tela unica com a tela de cima visivel. Trocada para a de baixo (a de toque), ela nao
         * pode ficar sob os controles: o padkit nao consome o toque, e cada botao apertado seria
         * tambem um toque na tela do 3DS (T3 do bug doc acima).
         */
        internal fun isSingleTopScreen(
            singleScreenLayout: Boolean,
            screenSwapped: Boolean,
        ): Boolean = singleScreenLayout && !screenSwapped
    }
}
