package com.swordfish.lemuroid.app.shared.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSessionForExitTest {
    private val game = "app.retrogamesystem:game"
    private val main = "app.retrogamesystem"

    @Test
    fun sessionStartedBeforeTheGameExitBelongsToIt() {
        assertTrue(TelemetryContext.isSessionOfExit(game, exitTimestamp = 2_000, sessionStartedAt = 1_000))
    }

    @Test
    fun exitAtTheSameMillisecondAsTheSessionStillBelongsToIt() {
        assertTrue(TelemetryContext.isSessionOfExit(game, exitTimestamp = 1_000, sessionStartedAt = 1_000))
    }

    @Test
    fun sessionStartedAfterTheExitIsALaterGame() {
        // O scan roda no proximo cold start: o slot ja pode ser de uma sessao posterior ao crash.
        assertFalse(TelemetryContext.isSessionOfExit(game, exitTimestamp = 1_000, sessionStartedAt = 2_000))
    }

    @Test
    fun noRecordedSessionAttachesNothing() {
        assertFalse(TelemetryContext.isSessionOfExit(game, exitTimestamp = 1_000, sessionStartedAt = 0))
    }

    @Test
    fun mainProcessExitNeverGetsAGameSession() {
        // ANR na home com um breadcrumb antigo no slot nao e crash de jogo.
        assertFalse(TelemetryContext.isSessionOfExit(main, exitTimestamp = 2_000, sessionStartedAt = 1_000))
        assertFalse(TelemetryContext.isSessionOfExit(null, exitTimestamp = 2_000, sessionStartedAt = 1_000))
    }
}
