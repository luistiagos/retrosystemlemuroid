package com.swordfish.lemuroid.app.utils.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/**
 * O marcador de trabalho em primeiro plano dos Workers
 * ([[2026-09-28-lowmemory-gameplay-ppsspp-foreground-service]]): marca só com a promoção que deu
 * certo, desmarca em qualquer saída do bloco, e não muda o que a falha do `setForeground` faz.
 */
class RunMarkedInForegroundTest {
    private val events = mutableListOf<String>()
    private val counter = ForegroundTagCounter()

    private suspend fun <T> runMarked(
        tag: String = "core",
        promote: suspend () -> Unit = { events += "promote" },
        onPromotionFailed: (Exception) -> Unit = { events += "failed:${it.message}" },
        block: suspend () -> T,
    ): T =
        runMarkedInForeground(
            tag = tag,
            counter = counter,
            promote = promote,
            mark = { events += "mark:$it" },
            clear = { events += "clear:$it" },
            onPromotionFailed = onPromotionFailed,
            block = block,
        )

    @Test
    fun promotionOkMarksBeforeBlockAndClearsAfter() {
        val result =
            runBlocking {
                runMarked {
                    events += "block"
                    42
                }
            }

        assertEquals(42, result)
        assertEquals(listOf("promote", "mark:core", "block", "clear:core"), events)
    }

    @Test
    fun swallowedPromotionFailureRunsBlockWithoutMarker() {
        val result =
            runBlocking {
                runMarked(promote = { throw IllegalStateException("fgs") }) {
                    events += "block"
                    "ok"
                }
            }

        assertEquals("ok", result)
        assertEquals(listOf("failed:fgs", "block"), events)
    }

    @Test
    fun rethrownPromotionFailureSkipsBlockAndMarker() {
        val failure = IllegalStateException("fgs")
        try {
            runBlocking {
                runMarked(
                    promote = { throw failure },
                    onPromotionFailed = { throw it },
                ) { events += "block" }
            }
            fail("a falha do setForeground tinha de propagar")
        } catch (e: IllegalStateException) {
            assertSame(failure, e)
        }

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun failingBlockStillClears() {
        try {
            runBlocking { runMarked { throw java.io.IOException("disk") } }
            fail("a falha do bloco tinha de propagar")
        } catch (e: java.io.IOException) {
            assertEquals("disk", e.message)
        }

        assertEquals(listOf("promote", "mark:core", "clear:core"), events)
    }

    @Test
    fun cancelledBlockStillClears() {
        try {
            runBlocking { runMarked { throw CancellationException("stopped") } }
            fail("o cancelamento tinha de propagar")
        } catch (e: CancellationException) {
            assertEquals("stopped", e.message)
        }

        assertEquals(listOf("promote", "mark:core", "clear:core"), events)
    }

    @Test
    fun sameTagHeldTwiceMarksOnceAndClearsOnLastRelease() {
        runBlocking {
            runMarked {
                events += "outer"
                runMarked { events += "inner" }
                events += "outer-after-inner"
            }
        }

        assertEquals(
            listOf("promote", "mark:core", "outer", "promote", "inner", "outer-after-inner", "clear:core"),
            events,
        )
    }

    @Test
    fun differentTagsAreIndependent() {
        runBlocking {
            runMarked(tag = "core") {
                runMarked(tag = "library") { events += "both" }
            }
        }

        assertEquals(
            listOf("promote", "mark:core", "promote", "mark:library", "both", "clear:library", "clear:core"),
            events,
        )
    }
}
