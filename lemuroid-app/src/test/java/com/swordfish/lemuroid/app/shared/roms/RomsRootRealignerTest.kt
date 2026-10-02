package com.swordfish.lemuroid.app.shared.roms

import com.swordfish.lemuroid.lib.library.catalog.RomsRootRealigner
import com.swordfish.lemuroid.lib.library.db.entity.Game
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RomsRootRealignerTest {
    private val sd = "file:///storage/SD/Android/data/pkg/files/roms"
    private val primary = "file:///storage/emulated/0/Android/data/pkg/files/roms"

    private fun game(
        id: Int,
        root: String,
        name: String,
        favorite: Boolean = false,
        played: Long? = null,
    ) = Game(
        id = id,
        fileName = name,
        fileUri = "$root/nes/$name",
        title = name,
        systemId = "nes",
        developer = null,
        coverFrontUrl = null,
        lastIndexedAt = 0L,
        lastPlayedAt = played,
        isFavorite = favorite,
    )

    private fun plan(
        foreign: List<Game>,
        current: List<Game>,
        onDisk: Set<String> = emptySet(),
    ) = RomsRootRealigner.plan(foreign, listOf(sd), current, primary) { it in onDisk }

    @Test
    fun placeholderOnOldVolumeIsRepointed() {
        val p = plan(listOf(game(1, sd, "a.nes")), emptyList())
        assertEquals(listOf(1 to "$primary/nes/a.nes"), p.repoint)
        assertTrue(p.delete.isEmpty())
    }

    @Test
    fun downloadStillOnOldVolumeStaysPut() {
        val row = game(1, sd, "a.nes")
        assertTrue(plan(listOf(row), emptyList(), setOf(row.fileUri)).isEmpty)
    }

    @Test
    fun duplicatePlaceholderIsDeletedAndStateMerged() {
        val old = game(1, sd, "a.nes", favorite = true, played = 50L)
        val dup = game(2, primary, "a.nes", played = 10L)
        val p = plan(listOf(old), listOf(dup))

        assertEquals(listOf(old), p.delete)
        assertTrue(p.repoint.isEmpty())
        val survivor = p.update.single()
        assertEquals(2, survivor.id)
        assertTrue(survivor.isFavorite)
        assertEquals(50L, survivor.lastPlayedAt)
    }

    @Test
    fun downloadOnOldVolumeWinsOverFreshPlaceholderDuplicate() {
        val old = game(1, sd, "a.nes")
        val dup = game(2, primary, "a.nes", favorite = true)
        val p = plan(listOf(old), listOf(dup), setOf(old.fileUri))

        assertEquals(listOf(dup), p.delete)
        val survivor = p.update.single()
        assertEquals(1, survivor.id)
        assertEquals(old.fileUri, survivor.fileUri)
        assertTrue(survivor.isFavorite)
    }

    @Test
    fun downloadedOnBothVolumesKeepsBoth() {
        val old = game(1, sd, "a.nes")
        val dup = game(2, primary, "a.nes")
        assertTrue(plan(listOf(old), listOf(dup), setOf(old.fileUri, dup.fileUri)).isEmpty)
    }

    @Test
    fun sameGameOnTwoOldVolumesEndsAsOneRow() {
        val usb = "file:///storage/USB/Android/data/pkg/files/roms"
        val a = game(1, sd, "a.nes")
        val b = game(2, usb, "a.nes", favorite = true)
        val p = RomsRootRealigner.plan(listOf(a, b), listOf(sd, usb), emptyList(), primary) { false }

        assertEquals(listOf(1 to "$primary/nes/a.nes"), p.repoint)
        assertEquals(listOf(b), p.delete)
        assertEquals(1, p.update.single().id)
        assertTrue(p.update.single().isFavorite)
    }

    @Test
    fun siblingDirWithSamePrefixIsNotTreatedAsTheRoot() {
        // "roms2" starts with "roms" but is not under it.
        val row = game(1, "${sd}2", "a.nes")
        assertTrue(plan(listOf(row), emptyList()).isEmpty)
    }
}
