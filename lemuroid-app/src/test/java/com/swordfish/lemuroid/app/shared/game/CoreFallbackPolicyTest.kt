package com.swordfish.lemuroid.app.shared.game

import android.content.SharedPreferences
import com.swordfish.lemuroid.app.shared.game.CoreFallbackPolicy.FallbackPromptReason
import com.swordfish.lemuroid.lib.core.CoresSelection
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.migration.DesmumeMigrationHandler
import dagger.Lazy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreFallbackPolicyTest {
    private val system3ds = GameSystem.findById(SystemID.NINTENDO_3DS.dbname)
    private val systemNds = GameSystem.findById(SystemID.NDS.dbname)
    private val systemGc = GameSystem.findById(SystemID.GAMECUBE.dbname)
    private val systemSnes = GameSystem.findById(SystemID.SNES.dbname)

    @Test
    fun findNextFallbackCoreOn3dsAlternatesBetweenAzaharAndCitraLegacy() {
        assertEquals(
            CoreID.CITRA_LEGACY,
            CoreFallbackPolicy.findNextFallbackCore(system3ds, listOf("citra"), allowDeprecated = false)?.coreID,
        )
        assertEquals(
            CoreID.CITRA_LEGACY,
            CoreFallbackPolicy.findNextFallbackCore(system3ds, listOf("citra"), allowDeprecated = true)?.coreID,
        )
        assertEquals(
            CoreID.CITRA,
            CoreFallbackPolicy.findNextFallbackCore(system3ds, listOf("citra_legacy"), allowDeprecated = false)?.coreID,
        )
        assertNull(
            CoreFallbackPolicy.findNextFallbackCore(
                system3ds,
                listOf("citra", "citra_legacy"),
                allowDeprecated = true,
            ),
        )
    }

    @Test
    fun findNextFallbackCoreRespectsDeprecatedFlagOnNds() {
        assertNull(
            CoreFallbackPolicy.findNextFallbackCore(systemNds, listOf("melonds"), allowDeprecated = false),
        )
        assertEquals(
            CoreID.DESMUME,
            CoreFallbackPolicy.findNextFallbackCore(systemNds, listOf("melonds"), allowDeprecated = true)?.coreID,
        )
        assertEquals(
            CoreID.MELONDS,
            CoreFallbackPolicy.findNextFallbackCore(systemNds, listOf("desmume"), allowDeprecated = false)?.coreID,
        )
    }

    @Test
    fun findNextFallbackCoreReturnsNullForSingleCoreSystems() {
        assertNull(CoreFallbackPolicy.findNextFallbackCore(systemGc, listOf("dolphin"), allowDeprecated = true))
        assertNull(CoreFallbackPolicy.findNextFallbackCore(systemSnes, listOf("snes9x"), allowDeprecated = true))
    }

    @Test
    fun evaluateExitPromptHandlesStalledAndNoFrameExits() {
        assertEquals(
            FallbackPromptReason.CORE_STALLED_ON_EXIT,
            CoreFallbackPolicy.evaluateExitPrompt(
                savesFailed = true,
                firstFrameRendered = true,
                sessionDurationMs = 200_000L,
                isPrimaryCore = false,
                promptAlreadyDismissed = true,
            ),
        )
        assertEquals(
            FallbackPromptReason.NO_FRAME_RENDERED,
            CoreFallbackPolicy.evaluateExitPrompt(
                savesFailed = false,
                firstFrameRendered = false,
                sessionDurationMs = CoreFallbackPolicy.MIN_NO_FRAME_DURATION_MS,
                isPrimaryCore = false,
                promptAlreadyDismissed = true,
            ),
        )
        assertNull(
            CoreFallbackPolicy.evaluateExitPrompt(
                savesFailed = false,
                firstFrameRendered = false,
                sessionDurationMs = 2_000L,
                isPrimaryCore = true,
                promptAlreadyDismissed = false,
            ),
        )
    }

    @Test
    fun evaluateExitPromptHandlesShortSessionAndRespectsDismissalAndSecondaryCore() {
        assertEquals(
            FallbackPromptReason.SHORT_SESSION,
            CoreFallbackPolicy.evaluateExitPrompt(
                savesFailed = false,
                firstFrameRendered = true,
                sessionDurationMs = 25_000L,
                isPrimaryCore = true,
                promptAlreadyDismissed = false,
            ),
        )
        assertNull(
            CoreFallbackPolicy.evaluateExitPrompt(
                savesFailed = false,
                firstFrameRendered = true,
                sessionDurationMs = 7_999L,
                isPrimaryCore = true,
                promptAlreadyDismissed = false,
            ),
        )
        assertNull(
            CoreFallbackPolicy.evaluateExitPrompt(
                savesFailed = false,
                firstFrameRendered = true,
                sessionDurationMs = 120_001L,
                isPrimaryCore = true,
                promptAlreadyDismissed = false,
            ),
        )
        assertNull(
            CoreFallbackPolicy.evaluateExitPrompt(
                savesFailed = false,
                firstFrameRendered = true,
                sessionDurationMs = 25_000L,
                isPrimaryCore = true,
                promptAlreadyDismissed = true,
            ),
        )
        assertNull(
            CoreFallbackPolicy.evaluateExitPrompt(
                savesFailed = false,
                firstFrameRendered = true,
                sessionDurationMs = 25_000L,
                isPrimaryCore = false,
                promptAlreadyDismissed = false,
            ),
        )
    }

    @Test
    fun shouldPersistFallbackCoreRequiresMultipleTriedCoresAndCleanFirstFrame() {
        assertTrue(
            CoreFallbackPolicy.shouldPersistFallbackCore(
                triedCores = listOf("citra", "citra_legacy"),
                coreName = "citra_legacy",
                firstFrameRendered = true,
                savesFailed = false,
            ),
        )
        assertFalse(
            CoreFallbackPolicy.shouldPersistFallbackCore(
                triedCores = listOf("citra"),
                coreName = "citra",
                firstFrameRendered = true,
                savesFailed = false,
            ),
        )
        assertFalse(
            CoreFallbackPolicy.shouldPersistFallbackCore(
                triedCores = listOf("citra", "citra_legacy"),
                coreName = "citra_legacy",
                firstFrameRendered = false,
                savesFailed = false,
            ),
        )
        assertFalse(
            CoreFallbackPolicy.shouldPersistFallbackCore(
                triedCores = listOf("citra", "citra_legacy"),
                coreName = "citra_legacy",
                firstFrameRendered = true,
                savesFailed = true,
            ),
        )
    }

    @Test
    fun perGameOverrideIsScopedToSpecificGameAndLeavesOther3dsGamesOnAzahar() =
        runBlocking {
            val prefs = FakePreferences()
            val coresSelection = CoresSelection(Lazy { prefs }, stubDesmumeMigrationHandler())
            val ssf4 = dummy3dsGame(id = 1, fileName = "Super Street Fighter IV - 3D Edition.3ds")
            val mk7 = dummy3dsGame(id = 2, fileName = "Mario Kart 7.3ds")

            assertEquals(CoreID.CITRA, coresSelection.getCoreConfigForGame(system3ds, ssf4).coreID)
            assertEquals(CoreID.CITRA, coresSelection.getCoreConfigForGame(system3ds, mk7).coreID)

            coresSelection.updateCoreConfigForGame(ssf4, CoreID.CITRA_LEGACY)

            assertEquals(CoreID.CITRA_LEGACY, coresSelection.getCoreConfigForGame(system3ds, ssf4).coreID)
            assertEquals(CoreID.CITRA, coresSelection.getCoreConfigForGame(system3ds, mk7).coreID)

            coresSelection.clearCoreConfigForGame(ssf4)
            assertEquals(CoreID.CITRA, coresSelection.getCoreConfigForGame(system3ds, ssf4).coreID)
        }

    @Test
    fun fallbackPromptDismissalIsPerGameAndCoreAndClearsOnSwitch() =
        runBlocking {
            val prefs = FakePreferences()
            val coresSelection = CoresSelection(Lazy { prefs }, stubDesmumeMigrationHandler())
            val ssf4 = dummy3dsGame(id = 1, fileName = "Super Street Fighter IV - 3D Edition.3ds")
            val mk7 = dummy3dsGame(id = 2, fileName = "Mario Kart 7.3ds")

            assertFalse(coresSelection.isFallbackPromptDismissed(ssf4, CoreID.CITRA.coreName))
            coresSelection.dismissFallbackPrompt(ssf4, CoreID.CITRA.coreName)

            assertTrue(coresSelection.isFallbackPromptDismissed(ssf4, CoreID.CITRA.coreName))
            assertFalse(coresSelection.isFallbackPromptDismissed(mk7, CoreID.CITRA.coreName))

            coresSelection.clearAllFallbackPromptsDismissed(ssf4)
            assertFalse(coresSelection.isFallbackPromptDismissed(ssf4, CoreID.CITRA.coreName))
        }

    private fun dummy3dsGame(
        id: Int,
        fileName: String,
    ) = Game(
        id = id,
        fileName = fileName,
        fileUri = "file:///roms/3ds/$fileName",
        title = fileName.removeSuffix(".3ds"),
        systemId = SystemID.NINTENDO_3DS.dbname,
        developer = null,
        coverFrontUrl = null,
        lastIndexedAt = 0L,
    )

    private fun stubDesmumeMigrationHandler(): DesmumeMigrationHandler {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateInstance.invoke(unsafe, DesmumeMigrationHandler::class.java) as DesmumeMigrationHandler
    }

    private class FakePreferences(
        val values: MutableMap<String, Any?> = mutableMapOf(),
    ) : SharedPreferences {
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): MutableMap<String, *> = HashMap(values)

        override fun getString(
            key: String?,
            defValue: String?,
        ) = values[key] as? String ?: defValue

        override fun getStringSet(
            key: String?,
            defValues: MutableSet<String>?,
        ) = defValues

        override fun getInt(
            key: String?,
            defValue: Int,
        ) = values[key] as? Int ?: defValue

        override fun getLong(
            key: String?,
            defValue: Long,
        ) = values[key] as? Long ?: defValue

        override fun getFloat(
            key: String?,
            defValue: Float,
        ) = values[key] as? Float ?: defValue

        override fun getBoolean(
            key: String?,
            defValue: Boolean,
        ) = values[key] as? Boolean ?: defValue

        override fun contains(key: String?) = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {
            listener?.let { listeners.add(it) }
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {
            listener?.let { listeners.remove(it) }
        }

        private inner class FakeEditor : SharedPreferences.Editor {
            private val puts = mutableMapOf<String, Any?>()
            private val removes = mutableSetOf<String>()

            override fun putString(
                key: String,
                value: String?,
            ) = apply { puts[key] = value }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?,
            ) = apply { puts[key] = values }

            override fun putInt(
                key: String,
                value: Int,
            ) = apply { puts[key] = value }

            override fun putLong(
                key: String,
                value: Long,
            ) = apply { puts[key] = value }

            override fun putFloat(
                key: String,
                value: Float,
            ) = apply { puts[key] = value }

            override fun putBoolean(
                key: String,
                value: Boolean,
            ) = apply { puts[key] = value }

            override fun remove(key: String) = apply { removes += key }

            override fun clear() = apply { removes += values.keys }

            override fun commit(): Boolean {
                val changedKeys = (removes + puts.keys).distinct()
                removes.forEach { values.remove(it) }
                values.putAll(puts)
                changedKeys.forEach { key ->
                    listeners.forEach { it.onSharedPreferenceChanged(this@FakePreferences, key) }
                }
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
