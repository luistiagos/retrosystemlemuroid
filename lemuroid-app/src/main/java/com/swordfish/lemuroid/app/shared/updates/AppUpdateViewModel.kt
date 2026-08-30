package com.swordfish.lemuroid.app.shared.updates

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class AppUpdateViewModel(context: Context) : ViewModel() {

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppUpdateViewModel(context.applicationContext) as T
    }

    sealed class State {
        object Idle : State()
        object Checking : State()
        data class UpdateAvailable(val info: AppUpdateManager.UpdateInfo) : State()
        object NoUpdate : State()

        /**
         * Android 8+ recusa a instalacao sem "instalar apps desconhecidos".
         * Perguntamos antes de baixar 100 MB que o sistema jogaria fora.
         */
        data class PermissionRequired(val info: AppUpdateManager.UpdateInfo) : State()
        data class Downloading(val progress: Float) : State()
        object Installing : State()
        data class Error(val message: String) : State()
    }

    private val manager = AppUpdateManager(context.applicationContext)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Checagem ao abrir o app: silenciosa, no maximo 1x a cada 12h, e respeita
     * a versao que o usuario ja dispensou. Falha de rede nao pode virar popup
     * de erro toda vez que o app abre.
     */
    fun checkOnStartup() {
        if (_state.value !is State.Idle) return
        if (!manager.shouldCheckNow()) return
        viewModelScope.launch {
            _state.value = State.Checking
            _state.value = try {
                manager.checkForUpdate()
                    ?.takeIf { !manager.isVersionSkipped(it.versionCode) }
                    ?.let { State.UpdateAvailable(it) }
                    ?: State.Idle
            } catch (e: Exception) {
                Timber.w(e, "Startup update check failed")
                State.Idle
            }
        }
    }

    /** Botao "Verificar atualizacoes": ignora o throttle e a versao dispensada. */
    fun checkManually() {
        if (_state.value is State.Downloading || _state.value is State.Installing) return
        viewModelScope.launch {
            _state.value = State.Checking
            _state.value = try {
                manager.checkForUpdate()?.let { State.UpdateAvailable(it) } ?: State.NoUpdate
            } catch (e: Exception) {
                State.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun startUpdate(info: AppUpdateManager.UpdateInfo) {
        if (!manager.canInstallPackages()) {
            _state.value = State.PermissionRequired(info)
            return
        }
        viewModelScope.launch {
            _state.value = State.Downloading(0f)
            try {
                manager.downloadAndInstall(info) { progress ->
                    _state.value = State.Downloading(progress)
                }
                _state.value = State.Installing
            } catch (e: Exception) {
                Timber.w(e, "Update download failed")
                _state.value = State.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun buildAllowInstallIntent(): Intent = manager.buildAllowInstallIntent()

    /** "Agora nao": nao insiste nesta versao ate sair uma mais nova. */
    fun dismissUpdate() {
        (_state.value as? State.UpdateAvailable)?.let { manager.skipVersion(it.info.versionCode) }
        _state.value = State.Idle
    }

    fun resetState() {
        _state.value = State.Idle
    }
}
