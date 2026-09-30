package com.swordfish.lemuroid.app.shared.game

import com.swordfish.lemuroid.lib.saves.IncompatibleStateException
import com.swordfish.lemuroid.lib.saves.SaveState
import com.swordfish.lemuroid.lib.saves.SaveStateCompatibility
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class SaveStateCompatibilityTest {
    private val state = byteArrayOf(1, 2, 3, 4)
    private val current = SaveStateCompatibility(0, true, "current-arm64-core")

    @Test
    fun legacyMetadataCannotReachFbneo() {
        val metadata = Json.decodeFromString(SaveState.Metadata.serializer(), "{}")
        assertThrows(IncompatibleStateException::class.java) {
            current.requireCompatible(SaveState(state, metadata))
        }
    }

    @Test
    fun coreUpdateOrAbiChangeRejectsEvenAnEqualSizedState() {
        val oldCore = SaveStateCompatibility(0, true, "previous-core-or-abi")
        val saved = SaveState(state, oldCore.metadataFor(state, 0))
        assertThrows(IncompatibleStateException::class.java) { current.requireCompatible(saved) }
    }

    @Test
    fun currentStateSurvivesMetadataRoundTrip() {
        val metadata = current.metadataFor(state, 2)
        val json = Json.encodeToString(SaveState.Metadata.serializer(), metadata)
        val decoded = Json.decodeFromString(SaveState.Metadata.serializer(), json)
        assertEquals(2, decoded.diskIndex)
        current.requireCompatible(SaveState(state, decoded))
    }

    @Test
    fun corruptionTruncationOrMismatchedSidecarIsRejected() {
        val metadata = current.metadataFor(state, 0)
        for (bytes in listOf(byteArrayOf(1, 2, 3, 5), state.copyOf(3), byteArrayOf())) {
            assertThrows(IncompatibleStateException::class.java) {
                current.requireCompatible(SaveState(bytes, metadata))
            }
        }
    }

    @Test
    fun missingChecksumCannotReachFbneo() {
        val metadata = current.metadataFor(state, 0).copy(stateSha256 = null)
        assertThrows(IncompatibleStateException::class.java) {
            current.requireCompatible(SaveState(state, metadata))
        }
    }

    @Test
    fun unknownRunningCoreFailsClosedForBothSaveAndLoad() {
        val unknown = SaveStateCompatibility(0, true)
        assertThrows(IncompatibleStateException::class.java) { unknown.metadataFor(state, 0) }
        assertThrows(IncompatibleStateException::class.java) {
            unknown.requireCompatible(SaveState(state, SaveState.Metadata()))
        }
    }

    @Test
    fun explicitStateVersionStillApplies() {
        val saved = SaveState(state, current.metadataFor(state, 0))
        assertThrows(IncompatibleStateException::class.java) {
            SaveStateCompatibility(1, true, "current-arm64-core").requireCompatible(saved)
        }
    }

    @Test
    fun otherCoresRetainLegacyCompatibilityAndVersionChecks() {
        val other = SaveStateCompatibility(2, false)
        other.requireCompatible(SaveState(state, SaveState.Metadata(version = 2)))
        assertEquals(SaveState.Metadata(1, 2), other.metadataFor(state, 1))
        assertThrows(IncompatibleStateException::class.java) {
            other.requireCompatible(SaveState(state, SaveState.Metadata(version = 1)))
        }
    }

    @Test
    fun fingerprintTracksBytesInsteadOfPathSizeOrTimestamp() {
        val first = File.createTempFile("fbneo-first", ".so")
        val copy = File.createTempFile("fbneo-copy", ".so")
        try {
            first.writeBytes(byteArrayOf(97, 98, 99))
            copy.writeBytes(first.readBytes())
            val hash = SaveStateCompatibility.fingerprint(first)
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash)
            assertEquals(hash, SaveStateCompatibility.fingerprint(copy))
            val timestamp = first.lastModified()
            first.writeBytes(byteArrayOf(97, 98, 100))
            first.setLastModified(timestamp)
            assertNotEquals(hash, SaveStateCompatibility.fingerprint(first))
        } finally {
            first.delete()
            copy.delete()
        }
    }
}
