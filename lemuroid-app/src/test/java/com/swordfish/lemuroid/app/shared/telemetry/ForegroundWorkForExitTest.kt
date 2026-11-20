package com.swordfish.lemuroid.app.shared.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundWorkForExitTest {
    private val main = "app.retrogamesystem"
    private val game = "app.retrogamesystem:game"

    @Test
    fun mainProcessExitWithTheSamePidNamesTheWork() {
        assertEquals("fgs=download", TelemetryContext.foregroundWorkOf(main, 42, 42, setOf("download")))
    }

    @Test
    fun exitOfAnotherPidIsNotTheMarkedProcess() {
        assertEquals("", TelemetryContext.foregroundWorkOf(main, 43, 42, setOf("download")))
    }

    @Test
    fun gameProcessNeverGetsTheMarker() {
        assertEquals("", TelemetryContext.foregroundWorkOf(game, 42, 42, setOf("download")))
        assertEquals("", TelemetryContext.foregroundWorkOf(null, 42, 42, setOf("download")))
    }

    @Test
    fun severalTagsComeSortedAndStable() {
        assertEquals(
            "fgs=download+library",
            TelemetryContext.foregroundWorkOf(main, 42, 42, linkedSetOf("library", "download")),
        )
    }

    @Test
    fun emptySetOrNoRecordedPidAttachesNothing() {
        assertEquals("", TelemetryContext.foregroundWorkOf(main, 42, 42, emptySet()))
        assertEquals("", TelemetryContext.foregroundWorkOf(main, 0, 0, setOf("download")))
    }

    @Test
    fun newPidRestartsTheSet() {
        // O processo anterior morreu sem desmarcar: o "library" dele nao pode vazar para este.
        assertEquals(
            setOf("download"),
            TelemetryContext.updatedForegroundTags(10, setOf("library"), 11, "download", active = true),
        )
        assertEquals(
            emptySet<String>(),
            TelemetryContext.updatedForegroundTags(10, setOf("library"), 11, "library", active = false),
        )
    }

    @Test
    fun samePidAddsAndRemoves() {
        val both = TelemetryContext.updatedForegroundTags(11, setOf("download"), 11, "library", active = true)
        assertEquals(setOf("download", "library"), both)
        val one = TelemetryContext.updatedForegroundTags(11, both, 11, "download", active = false)
        assertEquals(setOf("library"), one)
        val none = TelemetryContext.updatedForegroundTags(11, one, 11, "library", active = false)
        assertEquals("", TelemetryContext.foregroundWorkOf(main, 11, 11, none))
    }

    @Test
    fun storedTagsRoundTrip() {
        assertEquals(setOf("download", "library"), TelemetryContext.parseForegroundTags("download+library"))
        assertEquals(emptySet<String>(), TelemetryContext.parseForegroundTags(""))
        assertEquals(emptySet<String>(), TelemetryContext.parseForegroundTags(null))
    }
}
