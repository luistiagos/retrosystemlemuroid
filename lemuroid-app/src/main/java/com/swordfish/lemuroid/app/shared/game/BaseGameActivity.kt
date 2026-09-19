package com.swordfish.lemuroid.app.shared.game

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.game.GameActivity
import com.swordfish.lemuroid.app.mobile.feature.settings.SettingsManager
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.AppTheme
import com.swordfish.lemuroid.app.shared.GameMenuContract
import com.swordfish.lemuroid.app.shared.ImmersiveActivity
import com.swordfish.lemuroid.app.shared.coreoptions.CoreOption
import com.swordfish.lemuroid.app.shared.coreoptions.LemuroidCoreOption
import com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelSideEffects
import com.swordfish.lemuroid.app.shared.input.InputDeviceManager
import com.swordfish.lemuroid.app.shared.rumble.RumbleManager
import com.swordfish.lemuroid.app.shared.settings.ControllerConfigsManager
import com.swordfish.lemuroid.app.shared.telemetry.TelemetryContext
import com.swordfish.lemuroid.app.shared.telemetry.TelemetryReporter
import com.swordfish.lemuroid.app.tv.game.TVGameActivity
import com.swordfish.lemuroid.common.animationDuration
import com.swordfish.lemuroid.common.coroutines.launchOnState
import com.swordfish.lemuroid.common.displayToast
import com.swordfish.lemuroid.common.dump
import com.swordfish.lemuroid.common.kotlin.serializable
import com.swordfish.lemuroid.lib.core.CoreVariablesManager
import com.swordfish.lemuroid.lib.game.GameLoader
import com.swordfish.lemuroid.lib.library.ExposedSetting
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.saves.SavesManager
import com.swordfish.lemuroid.lib.saves.StatesManager
import com.swordfish.lemuroid.lib.saves.StatesPreviewManager
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.touchinput.radial.sensors.TiltConfiguration
import dagger.Lazy
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import kotlin.system.exitProcess

@OptIn(DelicateCoroutinesApi::class)
abstract class BaseGameActivity : ImmersiveActivity() {
    protected lateinit var game: Game
    private lateinit var system: GameSystem
    protected lateinit var systemCoreConfig: SystemCoreConfig

    @Inject
    lateinit var settingsManager: SettingsManager

    @Inject
    lateinit var statesManager: StatesManager

    @Inject
    lateinit var statesPreviewManager: StatesPreviewManager

    @Inject
    lateinit var legacySavesManager: SavesManager

    @Inject
    lateinit var coreVariablesManager: CoreVariablesManager

    @Inject
    lateinit var inputDeviceManager: InputDeviceManager

    @Inject
    lateinit var gameLoader: GameLoader

    @Inject
    lateinit var controllerConfigsManager: ControllerConfigsManager

    @Inject
    lateinit var rumbleManager: RumbleManager

    @Inject
    lateinit var sharedPreferences: Lazy<SharedPreferences>

    protected lateinit var baseGameScreenViewModel: BaseGameScreenViewModel

    private val startGameTime = System.currentTimeMillis()
    private var fdsCurrentSideIndex = 0
    private var fdsDiskInserted = true

    /**
     * `final` de proposito: o `finish(); return` abaixo so sai *deste* metodo. Uma subclasse que
     * sobrescrevesse `onCreate` seguiria rodando depois do `super.onCreate()` com `game` e
     * `systemCoreConfig` sem valor — era assim que o `GameActivity` crashava no Pre-Launch Report,
     * que lanca as activities sem extras. Codigo de subclasse vai em [onGameCreated].
     */
    final override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUpExceptionsHandler()

        game = intent.getSerializableExtra(EXTRA_GAME) as? Game ?: run { finish(); return }
        systemCoreConfig = intent.getSerializableExtra(EXTRA_SYSTEM_CORE_CONFIG) as? SystemCoreConfig ?: run { finish(); return }
        system = GameSystem.findByIdOrNull(game.systemId) ?: run { finish(); return }

        // Breadcrumb for the crash reporter. A SIGSEGV inside a libretro core is only recovered on
        // the next launch, when this process no longer exists to say what it was running — so the
        // system/core/game is persisted here, and the report names the culprit core instead of
        // just "native crash".
        TelemetryContext.setGameSession(
            applicationContext,
            systemId = game.systemId,
            coreName = systemCoreConfig.coreID.coreName,
            gameTitle = game.title,
        )

        val viewModel by viewModels<BaseGameScreenViewModel> {
            BaseGameScreenViewModel.Factory(
                applicationContext,
                game,
                settingsManager,
                inputDeviceManager,
                controllerConfigsManager,
                system,
                systemCoreConfig,
                sharedPreferences.get(),
                statesManager,
                statesPreviewManager,
                legacySavesManager,
                coreVariablesManager,
                rumbleManager,
            )
        }

        baseGameScreenViewModel = viewModel

        lifecycle.addObserver(baseGameScreenViewModel)

        setContent {
            AppTheme {
                BaseGameScreen(viewModel = baseGameScreenViewModel) {
                    GameScreen(viewModel)
                }
            }
        }

        lifecycleScope.launch {
            baseGameScreenViewModel.loadGame(
                applicationContext,
                game,
                systemCoreConfig,
                gameLoader,
                intent.getBooleanExtra(EXTRA_LOAD_SAVE, false),
            )
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    baseGameScreenViewModel.requestFinish()
                }
            },
        )

        initialiseFlows()
        onGameCreated()
    }

    /** Fim do [onCreate] — so e chamado quando a inicializacao nao abortou por falta de extras. */
    protected open fun onGameCreated() {}

    @Composable
    abstract fun GameScreen(viewModel: BaseGameScreenViewModel)

    private fun initialiseFlows() {
        launchOnState(Lifecycle.State.CREATED) {
            initializeViewModelsEffectsFlow()
        }
    }

    private fun setUpExceptionsHandler() {
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            // This replaces the process-wide handler installed in LemuroidApplication, so the
            // report has to be filed here too or every in-game Java crash goes unreported.
            // Synchronous on purpose: the paths below tear the process down immediately.
            TelemetryReporter.reportThrowable(
                component = "game",
                thread = thread,
                error = exception,
                extraContext = TelemetryContext.lastGameSession(applicationContext),
                terminal = true,
            )
            if (isEglIncompatibilityException(exception)) {
                Timber.e(exception, "EGL incompatibility detected on this device")
                performErrorFinish(getString(R.string.game_loader_error_gl_incompatible))
            } else {
                performUnexpectedErrorFinish(exception)
            }
        }
    }

    private fun isEglIncompatibilityException(exception: Throwable): Boolean {
        var current: Throwable? = exception
        while (current != null) {
            val message = current.message ?: ""
            val isEglFailure = current is IllegalArgumentException &&
                (message.contains("eglChooseConfig", ignoreCase = true) ||
                    message.contains("egl/ChooseConfig", ignoreCase = true) ||
                    (message.contains("EGL", ignoreCase = false) && message.contains("config", ignoreCase = true)))
            if (isEglFailure) return true
            current = current.cause
        }
        return false
    }

    private fun transformExposedSetting(
        exposedSetting: ExposedSetting,
        coreOptions: List<CoreOption>,
    ): LemuroidCoreOption? {
        return coreOptions
            .firstOrNull { it.variable.key == exposedSetting.key }
            ?.let { LemuroidCoreOption(exposedSetting, it) }
    }

    private suspend fun displayOptionsDialog(
        currentTiltConfiguration: TiltConfiguration,
        tiltConfigurations: List<TiltConfiguration>,
    ) {
        if (baseGameScreenViewModel.loadingState.value) {
            return
        }

        val coreOptions = getCoreOptions()

        val options =
            systemCoreConfig.exposedSettings
                .mapNotNull { transformExposedSetting(it, coreOptions) }

        val advancedOptions =
            systemCoreConfig.exposedAdvancedSettings
                .mapNotNull { transformExposedSetting(it, coreOptions) }

        val retroGameView = baseGameScreenViewModel.retroGameView.retroGameView
        val (availableDisks, retroCurrentDisk) = readDiskState(retroGameView)
        val fdsSideCount = baseGameScreenViewModel.retroGameView.fdsSideCount ?: 0
        val menuDisks = if (game.systemId == SystemID.FDS.dbname) {
            maxOf(availableDisks, fdsSideCount, 2)
        } else {
            availableDisks
        }
        val currentDisk = if (game.systemId == SystemID.FDS.dbname) {
            fdsCurrentSideIndex
        } else {
            retroCurrentDisk
        }.coerceIn(0, maxOf(menuDisks - 1, 0))

        val intent =
            Intent(this, getDialogClass()).apply {
                this.putExtra(GameMenuContract.EXTRA_CORE_OPTIONS, options.toTypedArray())
                this.putExtra(GameMenuContract.EXTRA_ADVANCED_CORE_OPTIONS, advancedOptions.toTypedArray())
                this.putExtra(
                    GameMenuContract.EXTRA_CURRENT_DISK,
                    currentDisk,
                )
                this.putExtra(
                    GameMenuContract.EXTRA_DISKS,
                    menuDisks,
                )
                this.putExtra(GameMenuContract.EXTRA_GAME, game)
                this.putExtra(GameMenuContract.EXTRA_SYSTEM_CORE_CONFIG, systemCoreConfig)
                this.putExtra(
                    GameMenuContract.EXTRA_AUDIO_ENABLED,
                    baseGameScreenViewModel.retroGameView.retroGameView?.audioEnabled,
                )
                this.putExtra(GameMenuContract.EXTRA_FAST_FORWARD_SUPPORTED, system.fastForwardSupport)
                this.putExtra(
                    GameMenuContract.EXTRA_FAST_FORWARD,
                    (baseGameScreenViewModel.retroGameView.retroGameView?.frameSpeed ?: 1) > 1,
                )
                this.putExtra(GameMenuContract.EXTRA_CURRENT_TILT_CONFIG, currentTiltConfiguration)
                // TODO PADS... Make sure to avoid passing this if a physical pad is connected.
                this.putExtra(GameMenuContract.EXTRA_TILT_ALL_CONFIGS, tiltConfigurations.toTypedArray())
            }
        startActivityForResult(intent, DIALOG_REQUEST)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /**
     * Disco atual e total de discos, ou `0 to 0` quando a GLThread nao responde.
     *
     * `getAvailableDisks`/`getCurrentDisk` passam por `runOnGLThread`: bloqueiam o chamador ate a
     * GLThread drenar a fila e, se ela estiver presa dentro de um callback do renderer, desistem
     * com [GLRetroView.GLThreadTimeoutException] depois de 30 s. Deixar essa excecao subir daqui
     * derrubava a sessao inteira: ela escapa do `collect` de `initializeViewModelsEffectsFlow`,
     * chega ao `UncaughtExceptionHandler` e o usuario ve a tela de crash — em vez do menu que
     * pediu, que e justamente por onde ele sairia do jogo travado. Reproduzido em device com
     * GameCube/Dolphin em 2026-09-03.
     *
     * Por isso a sonda barata antes: se a GLThread nao drena a fila em [GL_PROBE_TIMEOUT_MS], o
     * menu abre na hora sem a linha de discos, em vez de fazer o usuario esperar 30 s por um
     * resultado que ja se sabe que nao vem.
     */
    private suspend fun readDiskState(retroGameView: GLRetroView?): Pair<Int, Int> {
        if (retroGameView == null) return 0 to 0
        return withContext(Dispatchers.IO) {
            try {
                if (!glThreadResponds(retroGameView)) {
                    Timber.w("GL thread is not draining its queue; opening menu without disk info")
                    return@withContext 0 to 0
                }
                retroGameView.getAvailableDisks() to retroGameView.getCurrentDisk()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.e(e, "Failed to read disk state; opening menu without disk info")
                TelemetryReporter.reportThrowable(
                    component = "game",
                    thread = Thread.currentThread(),
                    error = e,
                    extraContext = "phase=open-menu; call=getAvailableDisks; " +
                        "system=${system.id.dbname}; game=${game.title}",
                    terminal = false,
                )
                0 to 0
            }
        }
    }

    /** Enfileira um no-op e espera pouco: distingue GLThread lenta de GLThread parada. */
    private fun glThreadResponds(view: GLRetroView): Boolean =
        try {
            val latch = java.util.concurrent.CountDownLatch(1)
            view.queueEvent { latch.countDown() }
            latch.await(GL_PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Throwable) {
            false
        }

    protected abstract fun getDialogClass(): Class<out Activity>

    private fun getCoreOptions(): List<CoreOption> {
        return baseGameScreenViewModel.retroGameView.retroGameView?.getVariables()
            ?.mapNotNull {
                val coreOptionResult =
                    runCatching {
                        CoreOption.fromLibretroDroidVariable(it)
                    }
                coreOptionResult.getOrNull()
            } ?: listOf()
    }

    private suspend fun initializeViewModelsEffectsFlow() {
        baseGameScreenViewModel.getSideEffects()
            .collect {
                when (it) {
                    is GameViewModelSideEffects.UiEffect.ShowMenu ->
                        displayOptionsDialog(
                            it.currentTiltConfiguration,
                            it.tiltConfigurations,
                        )
                    is GameViewModelSideEffects.UiEffect.ShowToast -> displayToast(it.message)
                    is GameViewModelSideEffects.UiEffect.SuccessfulFinish ->
                        performSuccessfulActivityFinish(it.savesFailed)
                    is GameViewModelSideEffects.UiEffect.FailureFinish -> performErrorFinish(it.message, it.isRomLoadFailure)
                    is GameViewModelSideEffects.UiEffect.SaveQuickSave -> performSaveQuickSave()
                    is GameViewModelSideEffects.UiEffect.LoadQuickSave -> performLoadQuickSave()
                    is GameViewModelSideEffects.UiEffect.ToggleFastForward -> performToggleFastForward()
                }
            }
    }

    private suspend fun performSaveQuickSave() {
        baseGameScreenViewModel.saveQuickSave()
    }

    private suspend fun performLoadQuickSave() {
        baseGameScreenViewModel.loadQuickSave()
    }

    private fun performToggleFastForward() {
        baseGameScreenViewModel.toggleFastForward()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val sourceIsGamepad = (event.source and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD ||
            (event.source and android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK
        // Clones enviam D-pad/START pela interface de teclado do mesmo HID composto:
        // o source do EVENTO é KEYBOARD, mas o DEVICE tem source de joystick/gamepad.
        val deviceIsGamepad = event.device?.let {
            (it.sources and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD ||
                (it.sources and android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK
        } ?: false
        if (sourceIsGamepad || deviceIsGamepad) {
            val handled = when (event.action) {
                KeyEvent.ACTION_DOWN -> onKeyDown(event.keyCode, event)
                KeyEvent.ACTION_UP -> onKeyUp(event.keyCode, event)
                else -> false
            }
            if (handled) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val handled = baseGameScreenViewModel.sendMotionEvent(event)
        if (handled) {
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        val handled = baseGameScreenViewModel.sendKeyEvent(keyCode, event)
        if (handled) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        val handled = baseGameScreenViewModel.sendKeyEvent(keyCode, event)
        if (handled) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * [savesFailed] viaja no resultado porque o aviso nao pode ser dado daqui: este metodo termina
     * em `finishAndExitProcess()`, e um toast enfileirado no processo `:game` morre junto com ele.
     * Quem mostra e o processo principal, no `GameLaunchTaskHandler`.
     */
    private fun performSuccessfulActivityFinish(savesFailed: Boolean = false) {
        // Clean exit — drop the breadcrumb so an unrelated crash later isn't blamed on this game.
        TelemetryContext.clearGameSession(applicationContext)
        val resultIntent =
            Intent().apply {
                putExtra(PLAY_GAME_RESULT_SESSION_DURATION, System.currentTimeMillis() - startGameTime)
                putExtra(PLAY_GAME_RESULT_GAME, intent.getSerializableExtra(EXTRA_GAME))
                putExtra(PLAY_GAME_RESULT_LEANBACK, intent.getBooleanExtra(EXTRA_LEANBACK, false))
                putExtra(PLAY_GAME_RESULT_SAVES_FAILED, savesFailed)
            }

        setResult(Activity.RESULT_OK, resultIntent)
        finishAndExitProcess()
    }

    /**
     * Um crash de core é SIGSEGV: não desenrola pela JVM, mata o processo direto e só é recuperado
     * na sessão seguinte via `ApplicationExitInfo`. Ou seja, o que chega ao
     * `UncaughtExceptionHandler` é, quase sempre, bug de app — não do núcleo. Só tratamos como falha
     * de emulação o que tem frame do LibretroDroid ou o que é falta de memória, porque só nesses
     * casos trocar de core (e culpar o núcleo na tela de erro) faz algum sentido.
     */
    /** A excecao e suas causas, limitada em profundidade para nao girar num ciclo de `cause`. */
    private fun causeChain(exception: Throwable): Sequence<Throwable> =
        generateSequence(exception) { it.cause }.take(MAX_CAUSE_DEPTH)

    /**
     * A GLThread parou de drenar a fila e uma chamada nossa estourou o prazo de 30 s.
     *
     * Nao e crash de core (o core nao morreu, parou de responder) nem bug de app: e um prazo que
     * o proprio `runOnGLThread` cria. Merece as duas coisas que nenhuma das outras duas categorias
     * daria certo: **nao** trocar de nucleo (repetiria a espera inteira) e **nao** mandar limpar
     * dados ou resetar de fabrica — nada disso alcanca a GLThread.
     *
     * Ver `documentacao/bugs/open/2026-08-09-anr-inicializar-jogo-runongl-thread.md`.
     */
    private fun isCoreStall(exception: Throwable): Boolean =
        causeChain(exception).any { it is GLRetroView.GLThreadTimeoutException }

    private fun isEmulatorFailure(exception: Throwable): Boolean {
        for (current in causeChain(exception)) {
            if (current is OutOfMemoryError) return true
            // Antes do teste por pacote de proposito: a GLThreadTimeoutException e lancada de
            // dentro de GLRetroView, entao casaria LIBRETRODROID_PACKAGE e seria tratada como
            // crash de core — com fallback de nucleo e conselho de formatacao de fabrica.
            if (current is GLRetroView.GLThreadTimeoutException) return false
            if (current.stackTrace.any { it.className.startsWith(LIBRETRODROID_PACKAGE) }) return true
        }
        return false
    }

    private fun performUnexpectedErrorFinish(exception: Throwable) {
        Timber.e(exception, "Handling java exception in BaseGameActivity")
        val triedCores = buildUpdatedTriedCores()
        val resultIntent =
            Intent().apply {
                putExtra(PLAY_GAME_RESULT_ERROR, exception.message ?: exception.javaClass.name)
                putExtra(PLAY_GAME_RESULT_IS_EMULATOR_FAILURE, isEmulatorFailure(exception))
                putExtra(PLAY_GAME_RESULT_IS_CORE_STALL, isCoreStall(exception))
                putExtra(PLAY_GAME_RESULT_GAME, intent.getSerializableExtra(EXTRA_GAME))
                if (::systemCoreConfig.isInitialized) {
                    putExtra(PLAY_GAME_RESULT_CORE_ID, systemCoreConfig.coreID.coreName)
                }
                putExtra(PLAY_GAME_RESULT_LEANBACK, intent.getBooleanExtra(EXTRA_LEANBACK, false))
                putStringArrayListExtra(PLAY_GAME_RESULT_TRIED_CORES, triedCores)
            }

        setResult(RESULT_UNEXPECTED_ERROR, resultIntent)
        finishAndExitProcess()
    }

    private fun performErrorFinish(message: String, isRomLoadFailure: Boolean = false) {
        val triedCores = buildUpdatedTriedCores()
        val resultIntent =
            Intent().apply {
                putExtra(PLAY_GAME_RESULT_ERROR, message)
                putExtra(PLAY_GAME_RESULT_GAME, intent.getSerializableExtra(EXTRA_GAME))
                putExtra(PLAY_GAME_RESULT_IS_ROM_LOAD_FAILURE, isRomLoadFailure)
                putExtra(PLAY_GAME_RESULT_CORE_ID, systemCoreConfig.coreID.coreName)
                putExtra(PLAY_GAME_RESULT_LEANBACK, intent.getBooleanExtra(EXTRA_LEANBACK, false))
                putStringArrayListExtra(PLAY_GAME_RESULT_TRIED_CORES, triedCores)
            }

        setResult(RESULT_ERROR, resultIntent)
        finishAndExitProcess()
    }

    private fun buildUpdatedTriedCores(): ArrayList<String> {
        val previous = intent.getStringArrayListExtra(EXTRA_TRIED_CORES) ?: arrayListOf()
        return ArrayList(previous).also {
            if (::systemCoreConfig.isInitialized) it.add(systemCoreConfig.coreID.coreName)
        }
    }

    private fun finishAndExitProcess() {
        onFinishTriggered()
        val duration = animationDuration().toLong()
        GlobalScope.launch {
            delay(duration)
            exitProcess(0)
        }
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    open fun onFinishTriggered() {}

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DIALOG_REQUEST) {
            Timber.i("Game menu dialog response: ${data?.extras.dump()}")
            if (data?.getBooleanExtra(GameMenuContract.RESULT_RESET, false) == true) {
                lifecycleScope.launch {
                    baseGameScreenViewModel.reset()
                }
            }
            if (data?.hasExtra(GameMenuContract.RESULT_SAVE) == true) {
                lifecycleScope.launch {
                    baseGameScreenViewModel.saveSlot(data.getIntExtra(GameMenuContract.RESULT_SAVE, 0))
                }
            }
            if (data?.hasExtra(GameMenuContract.RESULT_LOAD) == true) {
                lifecycleScope.launch {
                    baseGameScreenViewModel.loadSlot(data.getIntExtra(GameMenuContract.RESULT_LOAD, 0))
                }
            }
            if (data?.getBooleanExtra(GameMenuContract.RESULT_QUIT, false) == true) {
                baseGameScreenViewModel.requestFinish()
            }
            if (data?.hasExtra(GameMenuContract.RESULT_CHANGE_DISK) == true) {
                val index = data.getIntExtra(GameMenuContract.RESULT_CHANGE_DISK, 0)
                if (game.systemId == SystemID.FDS.dbname) {
                    lifecycleScope.launch {
                        changeFdsSide(index)
                    }
                } else {
                    // changeDisk e runOnGLThread — nunca da main thread.
                    val retroGameView = baseGameScreenViewModel.retroGameView.retroGameView
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { retroGameView?.changeDisk(index) }
                    }
                }
            }
            if (data?.hasExtra(GameMenuContract.RESULT_ENABLE_AUDIO) == true) {
                baseGameScreenViewModel.retroGameView.retroGameView?.apply {
                    this.audioEnabled =
                        data.getBooleanExtra(
                            GameMenuContract.RESULT_ENABLE_AUDIO,
                            true,
                        )
                }
            }
            if (data?.hasExtra(GameMenuContract.RESULT_ENABLE_FAST_FORWARD) == true) {
                baseGameScreenViewModel.retroGameView.retroGameView?.apply {
                    val fastForwardEnabled =
                        data.getBooleanExtra(
                            GameMenuContract.RESULT_ENABLE_FAST_FORWARD,
                            false,
                        )
                    this.frameSpeed = if (fastForwardEnabled) 2 else 1
                }
            }
            if (data?.getBooleanExtra(GameMenuContract.RESULT_EDIT_TOUCH_CONTROLS, false) == true) {
                baseGameScreenViewModel.showEditControls(true)
            }
            if (data?.hasExtra(GameMenuContract.RESULT_CHANGE_TILT_CONFIG) == true) {
                val tiltConfig = data.serializable<TiltConfiguration>(GameMenuContract.RESULT_CHANGE_TILT_CONFIG)
                    ?: return
                baseGameScreenViewModel.changeTiltConfiguration(tiltConfig)
            }
        }
    }

    private suspend fun changeFdsSide(index: Int) {
        val retroGameView = baseGameScreenViewModel.retroGameView.retroGameView ?: return
        val sideCount = maxOf(baseGameScreenViewModel.retroGameView.fdsSideCount ?: 0, 2)
        val targetSide = index.coerceIn(0, sideCount - 1)

        Timber.i(
            "FDS side change request: target=$targetSide current=$fdsCurrentSideIndex sides=$sideCount inserted=$fdsDiskInserted",
        )

        if (fdsDiskInserted) {
            pulseRetroButton(KeyEvent.KEYCODE_BUTTON_R1)
            fdsDiskInserted = false
        }

        val steps = (targetSide - fdsCurrentSideIndex + sideCount) % sideCount
        repeat(steps) {
            pulseRetroButton(KeyEvent.KEYCODE_BUTTON_L1)
            fdsCurrentSideIndex = (fdsCurrentSideIndex + 1) % sideCount
        }

        pulseRetroButton(KeyEvent.KEYCODE_BUTTON_R1)
        fdsDiskInserted = true
    }

    private suspend fun pulseRetroButton(keyCode: Int) {
        val retroGameView = baseGameScreenViewModel.retroGameView.retroGameView ?: return
        retroGameView.sendKeyEvent(KeyEvent.ACTION_DOWN, keyCode, FDS_CONTROL_PORT)
        delay(FDS_BUTTON_PULSE_MS)
        retroGameView.sendKeyEvent(KeyEvent.ACTION_UP, keyCode, FDS_CONTROL_PORT)
        delay(FDS_BUTTON_GAP_MS)
    }

    companion object {
        const val DIALOG_REQUEST = 100

        /** Curto de proposito: so distingue "GLThread ocupada" de "GLThread parada". */
        private const val GL_PROBE_TIMEOUT_MS = 2_000L

        private const val FDS_CONTROL_PORT = 0
        private const val FDS_BUTTON_PULSE_MS = 80L
        private const val FDS_BUTTON_GAP_MS = 120L

        private const val EXTRA_GAME = "GAME"
        private const val EXTRA_LOAD_SAVE = "LOAD_SAVE"
        private const val EXTRA_LEANBACK = "LEANBACK"
        private const val EXTRA_SYSTEM_CORE_CONFIG = "EXTRA_SYSTEM_CORE_CONFIG"
        private const val EXTRA_TRIED_CORES = "EXTRA_TRIED_CORES"

        const val PLAY_GAME_RESULT_TRIED_CORES = "PLAY_GAME_RESULT_TRIED_CORES"

        const val REQUEST_PLAY_GAME = 1001
        const val PLAY_GAME_RESULT_SESSION_DURATION = "PLAY_GAME_RESULT_SESSION_DURATION"
        const val PLAY_GAME_RESULT_SAVES_FAILED = "PLAY_GAME_RESULT_SAVES_FAILED"
        const val PLAY_GAME_RESULT_GAME = "PLAY_GAME_RESULT_GAME"
        const val PLAY_GAME_RESULT_LEANBACK = "PLAY_GAME_RESULT_LEANBACK"
        const val PLAY_GAME_RESULT_ERROR = "PLAY_GAME_RESULT_ERROR"
        const val PLAY_GAME_RESULT_IS_ROM_LOAD_FAILURE = "PLAY_GAME_RESULT_IS_ROM_LOAD_FAILURE"
        const val PLAY_GAME_RESULT_IS_EMULATOR_FAILURE = "PLAY_GAME_RESULT_IS_EMULATOR_FAILURE"
        const val PLAY_GAME_RESULT_IS_CORE_STALL = "PLAY_GAME_RESULT_IS_CORE_STALL"
        const val PLAY_GAME_RESULT_CORE_ID = "PLAY_GAME_RESULT_CORE_ID"

        private const val LIBRETRODROID_PACKAGE = "com.swordfish.libretrodroid"
        private const val MAX_CAUSE_DEPTH = 10

        const val RESULT_ERROR = Activity.RESULT_FIRST_USER + 2
        const val RESULT_UNEXPECTED_ERROR = Activity.RESULT_FIRST_USER + 3

        fun launchGame(
            activity: Activity,
            systemCoreConfig: SystemCoreConfig,
            game: Game,
            loadSave: Boolean,
            useLeanback: Boolean,
            triedCores: ArrayList<String> = arrayListOf(),
        ) {
            val gameActivity =
                if (useLeanback) {
                    TVGameActivity::class.java
                } else {
                    GameActivity::class.java
                }
            activity.startActivityForResult(
                Intent(activity, gameActivity).apply {
                    putExtra(EXTRA_GAME, game)
                    putExtra(EXTRA_LOAD_SAVE, loadSave)
                    putExtra(EXTRA_LEANBACK, useLeanback)
                    putExtra(EXTRA_SYSTEM_CORE_CONFIG, systemCoreConfig)
                    putStringArrayListExtra(EXTRA_TRIED_CORES, triedCores)
                },
                REQUEST_PLAY_GAME,
            )
            activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }
}
