package com.swordfish.lemuroid.app.shared.roms

import com.swordfish.lemuroid.app.shared.roms.DownloadNotificationThrottle.shouldPublish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNotificationThrottleTest {
    private val downloading = DownloadNotificationState(title = "X", percent = 10, queuedCount = 0)

    private fun entry(
        fileName: String,
        state: SaveQueueState,
        progress: Float = 0f,
    ) = SaveQueueEntry(
        fileName = fileName,
        gameId = 1,
        title = fileName,
        coverUrl = null,
        fileUri = "file:///roms/$fileName",
        systemId = "psx",
        state = state,
        progress = progress,
    )

    @Test
    fun firstPublicationAlwaysGoesOut() {
        assertTrue(shouldPublish(null, downloading, lastPublishedAtMs = 0L, nowMs = 0L))
    }

    @Test
    fun sameStateIsNotRepublished() {
        assertFalse(shouldPublish(downloading, downloading.copy(), lastPublishedAtMs = 0L, nowMs = 60_000L))
    }

    @Test
    fun percentOnlyWaitsForTheInterval() {
        val next = downloading.copy(percent = 11)
        assertFalse(shouldPublish(downloading, next, lastPublishedAtMs = 5_000L, nowMs = 5_200L))
        assertTrue(shouldPublish(downloading, next, lastPublishedAtMs = 5_000L, nowMs = 6_000L))
    }

    @Test
    fun titleChangeIsImmediate() {
        val next = downloading.copy(title = "Y", percent = 0)
        assertTrue(shouldPublish(downloading, next, lastPublishedAtMs = 5_000L, nowMs = 5_050L))
    }

    @Test
    fun queueChangeIsImmediate() {
        val next = downloading.copy(queuedCount = 1)
        assertTrue(shouldPublish(downloading, next, lastPublishedAtMs = 5_000L, nowMs = 5_050L))
    }

    @Test
    fun stateComesFromTheSavingEntryAndTheQueuedCount() {
        val entries =
            listOf(
                entry("A", SaveQueueState.QUEUED),
                entry("X", SaveQueueState.SAVING, progress = 0.423f),
                entry("B", SaveQueueState.QUEUED),
                entry("C", SaveQueueState.ERROR),
            )
        assertEquals(DownloadNotificationState("X", 42, 2), DownloadNotificationState.from(entries))

        val paused = listOf(entry("P", SaveQueueState.PAUSED), entry("A", SaveQueueState.QUEUED))
        val waiting = DownloadNotificationState.from(paused)
        assertNull(waiting.title)
        assertEquals(0, waiting.percent)
        assertEquals(1, waiting.queuedCount)
    }

    /** O defeito: uma publicação por `read()` do download. 1.000 ticks em 10 s (um a cada 10 ms). */
    @Test
    fun thousandProgressTicksInTenSecondsPublishAboutOncePerSecond() {
        var last: DownloadNotificationState? = null
        var lastPublishedAtMs = 0L
        val publishedPercents = mutableListOf<Int>()
        for (tick in 0 until 1_000) {
            val nowMs = tick * 10L
            val saving = entry("X", SaveQueueState.SAVING, progress = tick / 1_000f)
            val state = DownloadNotificationState.from(listOf(saving))
            if (shouldPublish(last, state, lastPublishedAtMs, nowMs)) {
                last = state
                lastPublishedAtMs = nowMs
                publishedPercents += state.percent
            }
        }
        assertTrue("publicações: ${publishedPercents.size}", publishedPercents.size in 10..11)
        // A notificação continua andando: cada publicação mostra um percentual maior.
        assertEquals(publishedPercents.sorted().distinct(), publishedPercents)
        assertTrue("último: ${publishedPercents.last()}", publishedPercents.last() >= 89)
    }
}
