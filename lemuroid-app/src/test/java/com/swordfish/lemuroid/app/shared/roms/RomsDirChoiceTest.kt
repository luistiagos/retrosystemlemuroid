package com.swordfish.lemuroid.app.shared.roms

import com.swordfish.lemuroid.lib.storage.RomsDirChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RomsDirChoiceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun volume(name: String): File = tmp.newFolder(name, "Android", "data", "pkg", "files")

    private fun choose(
        stored: File?,
        volumes: List<File>,
        free: Map<File, Long>,
        saf: Boolean = false,
    ) = RomsDirChoice.choose(stored, volumes, saf, { free[it] ?: 0L }, File(tmp.root, "fallback"))

    @Test
    fun savedChoiceSurvivesFreeSpaceInversion() {
        val primary = volume("primary")
        val sd = volume("sd")
        val first = choose(null, listOf(primary, sd), mapOf(primary to 1L, sd to 9L))
        assertEquals(File(sd, "roms"), first.dir)
        assertTrue(first.persist)

        // The bug: next launch, primary has more free space and the dir jumped back.
        val second = choose(first.dir, listOf(primary, sd), mapOf(primary to 9L, sd to 1L))
        assertEquals(first.dir, second.dir)
        assertFalse(second.persist)
    }

    @Test
    fun missingSavedVolumeFallsBackToPrimaryWithoutOverwritingTheChoice() {
        val primary = volume("primary")
        val sd = volume("sd")
        val saved = File(sd, "roms")

        val unplugged = choose(saved, listOf(primary), mapOf(primary to 9L))
        assertEquals(File(primary, "roms"), unplugged.dir)
        assertFalse(unplugged.persist)

        val back = choose(saved, listOf(primary, sd), mapOf(primary to 9L, sd to 1L))
        assertEquals(saved, back.dir)
    }

    @Test
    fun upgradeKeepsTheVolumeThatAlreadyHoldsTheLibrary() {
        val primary = volume("primary")
        val sd = volume("sd")
        File(primary, "roms").mkdirs()

        val result = choose(null, listOf(primary, sd), mapOf(primary to 1L, sd to 9L))
        assertEquals(File(primary, "roms"), result.dir)
        assertTrue(result.persist)
    }

    @Test
    fun safSelectionKeepsPrimaryOnFirstDecision() {
        val primary = volume("primary")
        val sd = volume("sd")
        val result = choose(null, listOf(primary, sd), mapOf(primary to 1L, sd to 9L), saf = true)
        assertEquals(File(primary, "roms"), result.dir)
    }

    @Test
    fun noVolumeUsesFallbackAndPersistsNothing() {
        val result = choose(null, emptyList(), emptyMap())
        assertEquals(File(tmp.root, "fallback"), result.dir)
        assertFalse(result.persist)
    }

    @Test
    fun managedRootIsFoundOnAnyVolume() {
        val marker = RomsDirChoice.managedRomsMarker("pkg")
        assertEquals(
            "file:///storage/1A2B-3C4D/Android/data/pkg/files/roms",
            RomsDirChoice.managedRomsRoot("file:///storage/1A2B-3C4D/Android/data/pkg/files/roms/nes/a.nes", marker),
        )
        assertEquals(
            "/storage/emulated/0/Android/data/pkg/files/roms",
            RomsDirChoice.managedRomsRoot("/storage/emulated/0/Android/data/pkg/files/roms/psx/g/g.cue", marker),
        )
        assertNull(RomsDirChoice.managedRomsRoot("file:///storage/emulated/0/Download/a.nes", marker))
        assertNull(RomsDirChoice.managedRomsRoot("file:///storage/x/Android/data/other/files/roms/a.nes", marker))
    }
}
