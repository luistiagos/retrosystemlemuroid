package com.swordfish.lemuroid.app.shared.updates

import android.content.Context
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
        data class Downloading(val progress: Float) : State()
        object Installing : State()
        data class Error(val message: String) : State()
    }

    private val manager = AppUpdateManager(context.applicationContext)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun checkOnStartup() {
        if (_state.value !is State.Idle) return
        viewModelScope.launch {
            _state.value = State.Checking
            _state.value = try {
                manager.checkForUpdate()?.let { State.UpdateAvailable(it) } ?: State.Idle
            } catch (e: Exception) {
                Timber.w(e, "Startup update check failed")
                State.Idle
            }
        }
    }

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
        viewModelScope.launch {
            _state.value = State.Downloading(0f)
            try {
                manager.downloadAndInstall(info) { progress ->
                    _state.value = State.Downloading(progress)
                }
                _state.value = State.Installing
            } catch (e: Exception) {
                _state.value = State.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun dismissUpdate() {
        _state.value = State.Idle
    }

    fun resetState() {
        _state.value = State.Idle
    }
}
