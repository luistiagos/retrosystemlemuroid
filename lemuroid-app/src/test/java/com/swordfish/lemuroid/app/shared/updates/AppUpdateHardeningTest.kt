package com.swordfish.lemuroid.app.shared.updates

import android.content.pm.PackageInstaller
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateHardeningTest {
    @Test
    fun requiredBytesForUpdateCoversDownloadAndInstallerStaging() {
        val apkSize = 163_527_974L
        assertEquals(apkSize * 2L, AppUpdateManager.requiredBytesForUpdate(apkSize, 0L))
    }

    @Test
    fun requiredBytesForUpdateSubtractsValidPartialDownload() {
        val apkSize = 200_000_000L
        val partial = 50_000_000L
        assertEquals(350_000_000L, AppUpdateManager.requiredBytesForUpdate(apkSize, partial))
    }

    @Test
    fun requiredBytesForUpdateHandlesUnknownAndOversizedPartialSafely() {
        assertEquals(0L, AppUpdateManager.requiredBytesForUpdate(0L, 10_000L))
        assertEquals(0L, AppUpdateManager.requiredBytesForUpdate(-5L, 0L))
        val apkSize = 100_000_000L
        assertEquals(apkSize, AppUpdateManager.requiredBytesForUpdate(apkSize, apkSize + 5_000L))
        assertEquals(apkSize * 2L, AppUpdateManager.requiredBytesForUpdate(apkSize, -100L))
    }

    @Test
    fun megabyteRoundingHelpersBehaveConsistently() {
        val oneMb = 1024L * 1024L
        assertEquals(0L, AppUpdateManager.bytesToMegabytesCeil(0L))
        assertEquals(1L, AppUpdateManager.bytesToMegabytesCeil(1L))
        assertEquals(1L, AppUpdateManager.bytesToMegabytesCeil(oneMb))
        assertEquals(2L, AppUpdateManager.bytesToMegabytesCeil(oneMb + 1L))

        assertEquals(0L, AppUpdateManager.bytesToMegabytesFloor(0L))
        assertEquals(0L, AppUpdateManager.bytesToMegabytesFloor(oneMb - 1L))
        assertEquals(1L, AppUpdateManager.bytesToMegabytesFloor(oneMb))
    }

    @Test
    fun classifyInstallFailureMapsAllKnownPackageInstallerErrors() {
        assertEquals(InstallFailureKind.STORAGE, classifyInstallFailure(PackageInstaller.STATUS_FAILURE_STORAGE))
        assertEquals(InstallFailureKind.CONFLICT, classifyInstallFailure(PackageInstaller.STATUS_FAILURE_CONFLICT))
        assertEquals(
            InstallFailureKind.INCOMPATIBLE,
            classifyInstallFailure(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE),
        )
        assertEquals(InstallFailureKind.INVALID, classifyInstallFailure(PackageInstaller.STATUS_FAILURE_INVALID))
        assertEquals(InstallFailureKind.BLOCKED, classifyInstallFailure(PackageInstaller.STATUS_FAILURE_BLOCKED))
        assertEquals(InstallFailureKind.GENERIC, classifyInstallFailure(PackageInstaller.STATUS_FAILURE))
        assertEquals(InstallFailureKind.GENERIC, classifyInstallFailure(999))
    }

    @Test
    fun updateInstallEventsDeliversFailuresToActiveObservers() =
        runBlocking {
            assertFalse(UpdateInstallEvents.hasObservers())
            val received =
                async(start = CoroutineStart.UNDISPATCHED) {
                    UpdateInstallEvents.results.first()
                }
            assertTrue(UpdateInstallEvents.hasObservers())

            val expected =
                UpdateInstallResult.Failed(
                    sessionId = 42,
                    status = PackageInstaller.STATUS_FAILURE_STORAGE,
                    message = "Espaco insuficiente",
                )
            UpdateInstallEvents.emit(expected)

            assertEquals(expected, received.await())
            assertFalse(UpdateInstallEvents.hasObservers())
        }
}
