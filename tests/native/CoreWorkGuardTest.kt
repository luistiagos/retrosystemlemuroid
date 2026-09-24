package com.swordfish.libretrodroid

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class CoreWorkGuardTest {
    private fun readyGuard(destroy: () -> Unit): CoreWorkGuard = CoreWorkGuard(destroy).also {
        assertTrue(it.begin(requireCreated = false))
        it.markCreated()
        it.end()
    }

    @Test fun destroyDuringBlockedFrameReturnsWithoutFreeingCore() {
        val destroyedOn = AtomicReference<Thread?>()
        val guard = readyGuard { destroyedOn.set(Thread.currentThread()) }
        val enteredFrame = CountDownLatch(1)
        val releaseFrame = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val glThread = thread(name = "GLThread regression") {
            try {
                assertTrue(guard.begin())
                try {
                    enteredFrame.countDown()
                    assertTrue(releaseFrame.await(5, TimeUnit.SECONDS))
                    // Simulate retro_run still using YM2610 after onPause timed out.
                    assertNull(destroyedOn.get())
                } finally {
                    guard.end()
                }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        try {
            assertTrue(enteredFrame.await(5, TimeUnit.SECONDS))
            guard.requestDestroy()
            assertNull(destroyedOn.get())
            assertFalse(guard.isReady)
            assertFalse(guard.begin()) // queued save/input/next frame must not enter JNI
            guard.requestDestroy()
        } finally {
            releaseFrame.countDown()
            glThread.join(5000)
        }
        assertFalse(glThread.isAlive)
        failure.get()?.let { throw it }
        assertSame(glThread, destroyedOn.get())
    }

    @Test fun destroyWaitsForAllOverlappingCalls() {
        val calls = AtomicInteger()
        val guard = readyGuard { calls.incrementAndGet() }
        assertTrue(guard.begin()) // frame / save
        assertTrue(guard.begin()) // UI reads core options concurrently
        guard.requestDestroy()
        guard.end()
        assertEquals(0, calls.get())
        guard.end()
        assertEquals(1, calls.get())
        assertFalse(guard.begin())
    }

    @Test fun destroyDuringCreateIsDeferredUntilCreationReturns() {
        val calls = AtomicInteger()
        val guard = CoreWorkGuard { calls.incrementAndGet() }
        assertFalse(guard.begin())
        assertTrue(guard.begin(requireCreated = false))
        guard.requestDestroy()
        guard.markCreated()
        assertEquals(0, calls.get())
        assertFalse(guard.isReady)
        guard.end()
        assertEquals(1, calls.get())
    }

    @Test fun destroyBeforeCreatePreventsLateInitialization() {
        val calls = AtomicInteger()
        val guard = CoreWorkGuard { calls.incrementAndGet() }
        guard.requestDestroy()
        assertFalse(guard.begin(requireCreated = false))
        assertEquals(0, calls.get())
    }

    @Test fun failedNativeCallStillAllowsDeferredCleanup() {
        val calls = AtomicInteger()
        val guard = readyGuard { calls.incrementAndGet() }
        try {
            assertTrue(guard.begin())
            try {
                guard.requestDestroy()
                throw IllegalStateException("JNI failure")
            } finally {
                guard.end()
            }
        } catch (expected: IllegalStateException) {
            assertEquals("JNI failure", expected.message)
        }
        assertEquals(1, calls.get())
    }

    @Test fun concurrentDestroyRequestsClaimCleanupExactlyOnce() {
        val calls = AtomicInteger()
        val guard = readyGuard { calls.incrementAndGet() }
        val start = CountDownLatch(1)
        val workers = List(16) {
            thread {
                start.await()
                guard.requestDestroy()
            }
        }
        start.countDown()
        workers.forEach { it.join(5000); assertFalse(it.isAlive) }
        assertEquals(1, calls.get())
        assertFalse(guard.begin())
    }

    @Test fun teardownRejectsReentrantWorkAndDuplicateDestruction() {
        val calls = AtomicInteger()
        lateinit var guard: CoreWorkGuard
        guard = readyGuard {
            calls.incrementAndGet()
            assertFalse(guard.begin())
            guard.requestDestroy()
        }
        guard.requestDestroy()
        assertEquals(1, calls.get())
    }
}
