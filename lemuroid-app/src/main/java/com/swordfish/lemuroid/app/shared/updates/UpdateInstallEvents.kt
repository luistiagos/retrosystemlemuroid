package com.swordfish.lemuroid.app.shared.updates

import android.content.pm.PackageInstaller
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class UpdateInstallResult {
    abstract val sessionId: Int

    data class Success(override val sessionId: Int) : UpdateInstallResult()

    data class Aborted(override val sessionId: Int) : UpdateInstallResult()

    data class Failed(
        override val sessionId: Int,
        val status: Int,
        val message: String,
    ) : UpdateInstallResult()
}

internal enum class InstallFailureKind {
    STORAGE,
    CONFLICT,
    INCOMPATIBLE,
    INVALID,
    BLOCKED,
    GENERIC,
}

internal fun classifyInstallFailure(status: Int): InstallFailureKind =
    when (status) {
        PackageInstaller.STATUS_FAILURE_STORAGE -> InstallFailureKind.STORAGE
        PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallFailureKind.CONFLICT
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> InstallFailureKind.INCOMPATIBLE
        PackageInstaller.STATUS_FAILURE_INVALID -> InstallFailureKind.INVALID
        PackageInstaller.STATUS_FAILURE_BLOCKED -> InstallFailureKind.BLOCKED
        else -> InstallFailureKind.GENERIC
    }

/**
 * Canal em processo entre [UpdateInstallReceiver] (callback assíncrono do PackageInstaller)
 * e a UI de atualização (mobile ou TV).
 */
object UpdateInstallEvents {
    private val _results = MutableSharedFlow<UpdateInstallResult>(extraBufferCapacity = 4)
    val results: SharedFlow<UpdateInstallResult> = _results.asSharedFlow()

    fun hasObservers(): Boolean = _results.subscriptionCount.value > 0

    fun emit(result: UpdateInstallResult) {
        _results.tryEmit(result)
    }
}
