package com.swordfish.lemuroid.app.shared.game

import com.swordfish.libretrodroid.GLRetroView
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GLThreadDumpTest {
    @Test
    fun dumpShowsWhereTheStuckGlThreadIs() {
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val glThread =
            Thread({ stuckInsideCore(parked, release) }, "GLThread 4242").apply {
                isDaemon = true
                start()
            }

        try {
            assertTrue(parked.await(5, TimeUnit.SECONDS))
            val dump = GLThreadDump.forFailure(GLRetroView.GLThreadTimeoutException("timeout"))!!

            assertTrue(dump, dump.contains("GLThread 4242 state="))
            assertTrue(dump, dump.contains("stuckInsideCore"))
        } finally {
            release.countDown()
            glThread.join(5_000)
        }
    }

    @Test
    fun dumpSaysSoWhenNoGlThreadIsAlive() {
        val dump = GLThreadDump.capture()

        assertTrue(dump, dump.startsWith("no live GLThread"))
    }

    @Test
    fun otherFailuresCarryNoDump() {
        assertNull(GLThreadDump.forFailure(IllegalStateException("not a GL timeout")))
        assertNull(GLThreadDump.forFailure(GLRetroView.GameNotLoadedException("still loading")))
    }

    private fun stuckInsideCore(
        parked: CountDownLatch,
        release: CountDownLatch,
    ) {
        parked.countDown()
        release.await()
    }
}
