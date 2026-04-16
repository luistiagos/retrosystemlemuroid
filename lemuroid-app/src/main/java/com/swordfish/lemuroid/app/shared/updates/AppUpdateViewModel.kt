package com.swordfish.lemuroid.app.shared.updates

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppUpdateViewModel(context: Context) : ViewModel() {

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppUpdateViewModel(context.applicationContext) as T
    }

    sealed class State {
        /** No check in progress and no dialog shown. */
        object Idle : State()
        /** Silently checking in background (no UI shown). */
        object Checking : State()
        /** A newer version was found — show the update dialog. */
        data class UpdateAvailable(val info: AppUpdateManager.UpdateInfo) : State()
        /** Manual check returned "already up-to-date" — show the toast/dialog. */
        object NoUpdate : State()
        /** APK download in progress — show progress dialog. */
        data class Downloading(val progress: Float) : State()
        /** PackageInstaller session committed — system will now show its own UI. */
        object Installing : State()
        /** Something went wrong. */
        data class Error(val message: String) : State()
    }

    private val manager = AppUpdateManager(context.applicationContext)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Called automatically when the app starts.
     * Checks silently; surfaces the dialog only if an update is found.
     * Does nothing if a check or download is already in progress.
     */
    fun checkOnStartup() {
        if (_state.value !is State.Idle) return
        viewModelScope.launch {
            _state.value = State.Checking
            val info = manager.checkForUpdate()
            _state.value = if (info != null) State.UpdateAvailable(info) else State.Idle
        }
    }

    /**
     * Called from the Settings "Check for updates" button.
     * Always shows feedback (either the update dialog or the "no update" dialog).
     */
    fun checkManually() {
        viewModelScope.launch {
            _state.value = State.Checking
            val info = manager.checkForUpdate()
            _state.value = if (info != null) State.UpdateAvailable(info) else State.NoUpdate
        }
    }

    /** User confirmed the update dialog — start the download. */
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

    /** User dismissed the update dialog (chose "Not now"). */
    fun dismissUpdate() {
        _state.value = State.Idle
    }

    /** Reset from NoUpdate / Error / Installing back to Idle. */
    fun resetState() {
        _state.value = State.Idle
    }
}
