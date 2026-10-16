package com.swordfish.lemuroid.app.shared.roms

import com.swordfish.lemuroid.lib.library.ArcadeCloneParents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.FileInputStream

class ArcadeCloneParentsTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setUp() {
            // Locate arcade-clone-parents.json from assets directory
            val candidates = listOf(
                File("retrograde-app-shared/src/main/assets/arcade-clone-parents.json"),
                File("../retrograde-app-shared/src/main/assets/arcade-clone-parents.json"),
                File("C:/projects/lemuroid/Lemuroid/retrograde-app-shared/src/main/assets/arcade-clone-parents.json"),
            )
            val assetFile = candidates.firstOrNull { it.exists() }
                ?: error("arcade-clone-parents.json not found in test search paths")

            FileInputStream(assetFile).use { input ->
                ArcadeCloneParents.loadFromStream(input)
            }
        }
    }

    @Test
    fun testAppliesToSystem() {
        assertTrue(ArcadeCloneParents.appliesToSystem("mame289"))
        assertTrue(ArcadeCloneParents.appliesToSystem("mame"))
        assertTrue(ArcadeCloneParents.appliesToSystem("fbneo"))
        assertTrue(ArcadeCloneParents.appliesToSystem("arcade"))
        assertTrue(ArcadeCloneParents.appliesToSystem("neogeo"))
        assertTrue(ArcadeCloneParents.appliesToSystem("neogeo64"))
        assertTrue(ArcadeCloneParents.appliesToSystem("cps1"))
        assertTrue(ArcadeCloneParents.appliesToSystem("cps2"))
        assertTrue(ArcadeCloneParents.appliesToSystem("cps3"))
        assertTrue(ArcadeCloneParents.appliesToSystem("zinc"))

        assertFalse(ArcadeCloneParents.appliesToSystem("snes"))
        assertFalse(ArcadeCloneParents.appliesToSystem("nes"))
        assertFalse(ArcadeCloneParents.appliesToSystem("genesis"))
        assertFalse(ArcadeCloneParents.appliesToSystem("psx"))
    }

    @Test
    fun testBiosDatasetBaseUrl() {
        val url = ArcadeCloneParents.biosDatasetBaseUrl("mame289")
        assertNotNull(url)
        assertEquals("https://huggingface.co/datasets/luisluis123/mame289bios/resolve/main", url)
    }

    @Test
    fun testHasBiosInAcervo() {
        assertTrue(ArcadeCloneParents.hasBiosInAcervo("mame289"))
    }

    @Test
    fun testResolveAncestorsFor18Wheeler() {
        // 18wheelr depends on Naomi BIOS, MIE microcontroller, and JVS I/O board
        val ancestors = ArcadeCloneParents.resolveAncestors(null, "mame289", "18wheelr.zip")
        assertEquals(listOf("naomi", "mie", "jvs13551"), ancestors)

        // Works with or without .zip extension
        val ancestorsNoExt = ArcadeCloneParents.resolveAncestors(null, "mame289", "18wheelr")
        assertEquals(listOf("naomi", "mie", "jvs13551"), ancestorsNoExt)
    }

    @Test
    fun testResolveAncestorsForSfex2() {
        // sfex2 requires ZN-2 BIOS coh3002c and QSound
        val ancestors = ArcadeCloneParents.resolveAncestors(null, "mame289", "sfex2.zip")
        assertTrue("Expected coh3002c in sfex2 ancestors", ancestors.contains("coh3002c"))
        assertTrue("Expected qsound in sfex2 ancestors", ancestors.contains("qsound"))
    }

    @Test
    fun testNonArcadeSystemReturnsEmpty() {
        val ancestors = ArcadeCloneParents.resolveAncestors(null, "snes", "18wheelr.zip")
        assertTrue(ancestors.isEmpty())
    }
}
