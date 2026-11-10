package com.swordfish.lemuroid.app.shared.game

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameProcessSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        GameProcessSession.resetForTest()
    }

    @After
    fun tearDown() {
        GameProcessSession.resetForTest()
    }

    @Test
    fun tryClaimAllowsOnlyOneSessionPerProcess() {
        assertTrue("First claim must succeed", GameProcessSession.tryClaim())
        assertFalse("Second claim in the same process must be rejected", GameProcessSession.tryClaim())
    }

    @Test
    fun recreationBeforeCoreCreatedIsAllowed() {
        assertTrue("Initial claim must succeed", GameProcessSession.tryClaim(isRecreation = false))
        GameProcessSession.onActivityDestroyed(isChangingConfigurations = true)
        assertTrue(
            "Activity recreation before GLRetroView creation must succeed",
            GameProcessSession.tryClaim(isRecreation = true),
        )
    }

    @Test
    fun recreationAfterCoreCreatedIsRejected() {
        assertTrue("Initial claim must succeed", GameProcessSession.tryClaim(isRecreation = false))
        GameProcessSession.onCoreCreated()
        GameProcessSession.onActivityDestroyed(isChangingConfigurations = true)
        assertFalse(
            "Activity recreation after GLRetroView creation must be rejected",
            GameProcessSession.tryClaim(isRecreation = true),
        )
        assertFalse(
            "Fresh claim after GLRetroView creation must be rejected",
            GameProcessSession.tryClaim(isRecreation = false),
        )
    }

    @Test
    fun processExitingBlocksAnyFurtherClaims() {
        assertTrue("Initial claim must succeed", GameProcessSession.tryClaim(isRecreation = false))
        GameProcessSession.onProcessExiting()
        GameProcessSession.onActivityDestroyed(isChangingConfigurations = true)
        assertFalse(
            "Recreation while process is exiting must be rejected",
            GameProcessSession.tryClaim(isRecreation = true),
        )
    }

    @Test
    fun lockFileLifecycleAcrossProcessSimulation() {
        val filesDir = tmp.newFolder("files")
        assertFalse(
            "Lock must not be reported running when file does not exist",
            GameProcessSession.isGameProcessRunning(filesDir),
        )

        GameProcessSession.acquireProcessLock(filesDir)
        assertTrue(
            "Process lock must be reported running while held",
            GameProcessSession.isGameProcessRunning(filesDir),
        )

        val lockFile = File(filesDir, GameProcessSession.LOCK_FILE_NAME)
        assertTrue("Lock file must exist", lockFile.exists())

        val pid = GameProcessSession.readGameProcessPid(filesDir)
        assertNotNull("PID must be present in lock file", pid)

        GameProcessSession.releaseProcessLock()
        assertFalse("Lock must be reported free after release", GameProcessSession.isGameProcessRunning(filesDir))
    }

    @Test
    fun emptyOrStaleLockFileWithoutHoldReturnsNotRunning() {
        val filesDir = tmp.newFolder("stale_files")
        val lockFile = File(filesDir, GameProcessSession.LOCK_FILE_NAME)
        lockFile.writeText("99999\n")

        // No process actually holds an OS file lock on this file
        assertFalse(
            "Stale lock file from a dead process must not block",
            GameProcessSession.isGameProcessRunning(filesDir),
        )
        assertEquals(99999, GameProcessSession.readGameProcessPid(filesDir))
    }
}
