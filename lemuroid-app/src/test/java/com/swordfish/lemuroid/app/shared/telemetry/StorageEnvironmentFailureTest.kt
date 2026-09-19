package com.swordfish.lemuroid.app.shared.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class StorageEnvironmentFailureTest {

    @Test
    fun testNullThrowableReturnsFalse() {
        assertFalse(CrashTelemetry.isStorageEnvironmentFailure(null))
    }

    @Test
    fun testUnrelatedExceptionReturnsFalse() {
        assertFalse(CrashTelemetry.isStorageEnvironmentFailure(NullPointerException("null pointer")))
        assertFalse(CrashTelemetry.isStorageEnvironmentFailure(IllegalArgumentException("bad arg")))
        assertFalse(CrashTelemetry.isStorageEnvironmentFailure(IllegalStateException("normal state error")))
    }

    @Test
    fun testSqliteFullExceptionMessage() {
        val error = Exception("android.database.sqlite.SQLiteFullException: database or disk is full (code 13 SQLITE_FULL)")
        assertTrue(CrashTelemetry.isStorageEnvironmentFailure(error))
    }

    @Test
    fun testSqliteCantOpenDatabaseMessage() {
        val error = Exception("SQLiteCantOpenDatabaseException: Cannot open database '/mnt/expand/uuid/workdb' Directory doesn't exist")
        assertTrue(CrashTelemetry.isStorageEnvironmentFailure(error))
    }

    @Test
    fun testWorkManagerForceStopBadStateMessage() {
        val wmError = IllegalStateException(
            "The file system on the device is in a bad state. WorkManager cannot access the app's internal data store.",
            IOException("Directory doesn't exist"),
        )
        assertTrue(CrashTelemetry.isStorageEnvironmentFailure(wmError))
    }

    @Test
    fun testEnospcAndNoSpaceLeftMessages() {
        val enospc = IOException("write failed: ENOSPC (No space left on device)")
        assertTrue(CrashTelemetry.isStorageEnvironmentFailure(enospc))

        val wrapped = RuntimeException("Failed to write transaction", enospc)
        assertTrue(CrashTelemetry.isStorageEnvironmentFailure(wrapped))
    }

    @Test
    fun testNestedCauseChain() {
        val rootCause = IOException("database or disk is full (code 13 SQLITE_FULL)")
        val middle = RuntimeException("Room transaction failed", rootCause)
        val outer = IllegalStateException("Coroutine died", middle)
        assertTrue(CrashTelemetry.isStorageEnvironmentFailure(outer))
    }

    @Test
    fun testSuppressedExceptions() {
        val outer = RuntimeException("Top level coroutine exception")
        val suppressed = IOException("database or disk is full")
        outer.addSuppressed(suppressed)
        assertTrue(CrashTelemetry.isStorageEnvironmentFailure(outer))
    }

    // Custom exception subclasses simulating Android SQLite exceptions
    private class MockSQLiteFullException(message: String) : RuntimeException(message)
    private class MockSQLiteCantOpenDatabaseException(message: String) : RuntimeException(message)
    private class MockSQLiteDiskIOException(message: String) : RuntimeException(message)

    @Test
    fun testSqliteExceptionClassNames() {
        assertTrue(CrashTelemetry.isStorageEnvironmentFailure(MockSQLiteFullException("code 13")))
        assertTrue(CrashTelemetry.isStorageEnvironmentFailure(MockSQLiteCantOpenDatabaseException("unable to open")))
        assertTrue(CrashTelemetry.isStorageEnvironmentFailure(MockSQLiteDiskIOException("disk error")))
    }
}
