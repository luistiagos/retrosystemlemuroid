package com.swordfish.lemuroid.app.shared.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TelemetryWorkersTest {
    @Test
    fun awaitReturnsAtOnceWithNothingPending() {
        val workers = TelemetryWorkers()

        val elapsed = elapsedMs { workers.awaitAll(2_000) }

        assertTrue("took $elapsed ms", elapsed < 100)
    }

    @Test
    fun awaitGivesUpAtTheDeadlineWhenAWorkerIsStuck() {
        val workers = TelemetryWorkers()
        val release = CountDownLatch(1)
        // Dois presos: o prazo e do total, nao de cada join.
        repeat(2) { workers.start("stuck-$it") { release.await() } }

        try {
            val elapsed = elapsedMs { workers.awaitAll(300) }

            assertTrue("took $elapsed ms", elapsed in 250..1_000)
            assertEquals(2, workers.pendingCount)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun awaitWaitsForAWorkerThatFinishesInTime() {
        val workers = TelemetryWorkers()
        val sent = CountDownLatch(1)
        workers.start("slow-send") {
            Thread.sleep(200)
            sent.countDown()
        }

        workers.awaitAll(5_000)

        assertTrue(sent.await(0, TimeUnit.MILLISECONDS))
        assertEquals(0, workers.pendingCount)
    }

    @Test
    fun aFailingWorkerLeavesTheSetAndNeverReachesTheCaller() {
        val workers = TelemetryWorkers()
        val go = CountDownLatch(1)
        val worker =
            workers.start("failing") {
                go.await()
                throw IllegalStateException("network down")
            }
        worker.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> }
        go.countDown()

        worker.join(5_000)
        workers.awaitAll(1_000)

        assertEquals(0, workers.pendingCount)
    }

    @Test
    fun awaitKeepsTheInterruptFlagAndDoesNotThrow() {
        val workers = TelemetryWorkers()
        val release = CountDownLatch(1)
        workers.start("stuck") { release.await() }

        try {
            Thread.currentThread().interrupt()
            workers.awaitAll(2_000)

            assertTrue(Thread.interrupted())
        } finally {
            release.countDown()
        }
    }

    private fun elapsedMs(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }
}
