package com.swordfish.lemuroid.app.shared

import com.swordfish.lemuroid.app.shared.search.SystemSearchResolver
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemID
import org.junit.Assert.assertEquals
import org.junit.Test

// [[2026-10-25-busca-gamecube-traz-neo-geo-pocket-color]]
class SystemSearchResolverTest {
    private val gc = GameSystem.findById(SystemID.GAMECUBE.dbname)
    private val ngc = GameSystem.findById(SystemID.NGC.dbname)

    private val strings =
        mapOf(
            gc.titleResId to "Nintendo GameCube",
            gc.shortTitleResId to "GC",
            ngc.titleResId to "Neo Geo Pocket Color",
            ngc.shortTitleResId to "NGC",
        )

    private val resolver = SystemSearchResolver { id -> strings[id] ?: throw IllegalArgumentException("id $id") }

    private fun resolve(query: String) = resolver.resolveSystemIds(query).toSet()

    @Test
    fun gamecubeResolvesToGameCube() {
        assertEquals(setOf("gc"), resolve("gamecube"))
        assertEquals(setOf("gc"), resolve("game cube"))
        assertEquals(setOf("gc"), resolve("nintendo gamecube"))
    }

    @Test
    fun abbreviationsKeepTheirSystems() {
        assertEquals(setOf("ngc"), resolve("ngc"))
        assertEquals(setOf("gc"), resolve("gc"))
    }
}
