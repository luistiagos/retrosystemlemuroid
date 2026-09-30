package com.swordfish.lemuroid.app.shared.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class CrashTelemetryMemoryTest {
    @Test
    fun reportsProcessSizeAgainstDeviceRam() {
        assertEquals(
            "pss=412MB; rss=530MB; ram=3712MB",
            CrashTelemetry.memoryContext(pssKb = 412L * 1024, rssKb = 530L * 1024, totalMemBytes = 3712L * 1024 * 1024),
        )
    }

    @Test
    fun unknownValuesAreLeftOutInsteadOfReportedAsZero() {
        // 0 e "o sistema nao sabia", nao "o processo nao ocupava nada".
        assertEquals("ram=2048MB", CrashTelemetry.memoryContext(0, 0, 2048L * 1024 * 1024))
        assertEquals("", CrashTelemetry.memoryContext(0, 0, 0))
    }
}
