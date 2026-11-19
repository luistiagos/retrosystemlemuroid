package com.swordfish.lemuroid.app.shared.game

import android.content.SharedPreferences
import com.swordfish.lemuroid.lib.migration.AzaharMigrationHandler
import com.swordfish.lemuroid.lib.migration.ThreeDsMiiProvisioner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AzaharMigrationHandlerTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun renamedKeysKeepTheUsersChoice() {
        val prefs =
            FakePreferences(
                mutableMapOf(
                    // What CoreCrashFallback wrote after a crash with the old core.
                    "cv_3ds_citra_use_hw_shaders" to false,
                    "cv_3ds_citra_use_acc_mul" to true,
                ),
            )

        AzaharMigrationHandler.migratePreferences(prefs)

        assertEquals(false, prefs.values["cv_3ds_citra_use_hw_shader"])
        assertEquals(true, prefs.values["cv_3ds_citra_shaders_accurate_mul"])
        assertFalse(prefs.values.containsKey("cv_3ds_citra_use_hw_shaders"))
        assertFalse(prefs.values.containsKey("cv_3ds_citra_use_acc_mul"))
    }

    @Test
    fun newKeyAlreadySetWins() {
        val prefs =
            FakePreferences(
                mutableMapOf(
                    "cv_3ds_citra_use_hw_shaders" to false,
                    "cv_3ds_citra_use_hw_shader" to true,
                ),
            )

        AzaharMigrationHandler.migratePreferences(prefs)

        assertEquals(true, prefs.values["cv_3ds_citra_use_hw_shader"])
        assertFalse(prefs.values.containsKey("cv_3ds_citra_use_hw_shaders"))
    }

    @Test
    fun layoutAndResolutionBecomeTheValuesAzaharDeclares() {
        val prefs =
            FakePreferences(
                mutableMapOf(
                    "cv_3ds_citra_layout_option" to "Side by Side",
                    "cv_3ds_citra_resolution_factor" to "2x",
                    // Other systems are never touched.
                    "cv_nds_melonds_screen_layout" to "Side by Side",
                ),
            )

        AzaharMigrationHandler.migratePreferences(prefs)

        assertEquals("side_by_side", prefs.values["cv_3ds_citra_layout_option"])
        assertEquals("2", prefs.values["cv_3ds_citra_resolution_factor"])
        assertEquals("Side by Side", prefs.values["cv_nds_melonds_screen_layout"])
    }

    @Test
    fun migrationIsIdempotent() {
        val prefs = FakePreferences(mutableMapOf("cv_3ds_citra_layout_option" to "default"))

        AzaharMigrationHandler.migratePreferences(prefs)

        assertEquals(0, prefs.commits)
        assertEquals("default", prefs.values["cv_3ds_citra_layout_option"])
    }

    @Test
    fun resolutionLabels() {
        assertEquals("1", AzaharMigrationHandler.migrateResolution("1x (Native)"))
        assertEquals("3", AzaharMigrationHandler.migrateResolution("3x"))
        assertEquals("10", AzaharMigrationHandler.migrateResolution("10x"))
        assertEquals("10", AzaharMigrationHandler.migrateResolution("12x"))
        assertEquals("1", AzaharMigrationHandler.migrateResolution("disabled"))
        assertEquals("2", AzaharMigrationHandler.migrateResolution("2"))
    }

    @Test
    fun oldUserDirIsCopiedWithoutTheShaderCache() {
        val saves = folder.root
        val save = File(saves, "Citra/sdmc/Nintendo 3DS/0/0/title/00040000/00030700/data/00000001/save.bin")
        save.parentFile!!.mkdirs()
        save.writeBytes(byteArrayOf(1, 2, 3))
        writeFile(File(saves, "Citra/shaders/opengl/cache.bin"), byteArrayOf(9))

        AzaharMigrationHandler.migrateUserDirectory(saves)

        val migrated = File(saves, "Azahar/sdmc/Nintendo 3DS/0/0/title/00040000/00030700/data/00000001/save.bin")
        assertTrue(migrated.exists())
        assertEquals(listOf<Byte>(1, 2, 3), migrated.readBytes().toList())
        assertFalse(File(saves, "Azahar/shaders").exists())
        assertFalse(File(saves, "Azahar.migrating").exists())
        // The old folder stays as the backup.
        assertTrue(save.exists())
    }

    @Test
    fun existingAzaharDirIsNeverOverwritten() {
        val saves = folder.root
        writeFile(File(saves, "Citra/sysdata/a.bin"), byteArrayOf(1))
        val current = writeFile(File(saves, "Azahar/sysdata/a.bin"), byteArrayOf(2))

        AzaharMigrationHandler.migrateUserDirectory(saves)

        assertEquals(listOf<Byte>(2), current.readBytes().toList())
    }

    @Test
    fun interruptedCopyIsRedone() {
        val saves = folder.root
        writeFile(File(saves, "Citra/sysdata/a.bin"), byteArrayOf(1))
        writeFile(File(saves, "Azahar.migrating/half.bin"), byteArrayOf(7))

        AzaharMigrationHandler.migrateUserDirectory(saves)

        assertTrue(File(saves, "Azahar/sysdata/a.bin").exists())
        assertFalse(File(saves, "Azahar/half.bin").exists())
        assertFalse(File(saves, "Azahar.migrating").exists())
    }

    @Test
    fun noOldDirNothingHappens() {
        AzaharMigrationHandler.migrateUserDirectory(folder.root)

        assertFalse(File(folder.root, "Azahar").exists())
        assertNull(folder.root.listFiles()?.firstOrNull())
    }

    @Test
    fun defaultMiiDatabaseIsProvisionedWhenMissing() {
        val saves = folder.root
        AzaharMigrationHandler.ensureAzaharMiiDatabase(saves)

        val dbFile = ThreeDsMiiProvisioner.cflDatabaseFile(File(saves, "Azahar"))
        assertTrue(dbFile.isFile)
        val bytes = dbFile.readBytes()
        assertEquals(ThreeDsMiiProvisioner.CFL_DB_SIZE, bytes.size)
        assertEquals("CFOG", String(bytes, 0, 4))
        assertEquals("CFHE", String(bytes, ThreeDsMiiProvisioner.CFHE_OFFSET, 4))
        assertEquals("CFRA", String(bytes, ThreeDsMiiProvisioner.CFRA_OFFSET, 4))

        // Slot 0 has a valid non-zero Mii ID with bit 31 set (non-special Mii).
        val idOffset = ThreeDsMiiProvisioner.MIIMAKER_SLOTS_OFFSET + ThreeDsMiiProvisioner.MII_ID_OFFSET_IN_SLOT
        assertTrue((bytes[idOffset].toInt() and 0x80) != 0)

        // Checksums at 0xC81E and 0xE4BE match big-endian CRC-16/XMODEM.
        val expectedCfogCrc = ThreeDsMiiProvisioner.crc16Xmodem(bytes, 0, ThreeDsMiiProvisioner.CFOG_CHECKSUM_OFFSET)
        val actualCfogCrc =
            ((bytes[ThreeDsMiiProvisioner.CFOG_CHECKSUM_OFFSET].toInt() and 0xFF) shl 8) or
                (bytes[ThreeDsMiiProvisioner.CFOG_CHECKSUM_OFFSET + 1].toInt() and 0xFF)
        assertEquals(expectedCfogCrc, actualCfogCrc)

        val expectedCfraCrc =
            ThreeDsMiiProvisioner.crc16Xmodem(
                bytes,
                ThreeDsMiiProvisioner.CFRA_OFFSET,
                ThreeDsMiiProvisioner.CFRA_CHECKSUM_OFFSET,
            )
        val actualCfraCrc =
            ((bytes[ThreeDsMiiProvisioner.CFRA_CHECKSUM_OFFSET].toInt() and 0xFF) shl 8) or
                (bytes[ThreeDsMiiProvisioner.CFRA_CHECKSUM_OFFSET + 1].toInt() and 0xFF)
        assertEquals(expectedCfraCrc, actualCfraCrc)
    }

    @Test
    fun existingMiiDatabaseWithMiiIsPreserved() {
        val azaharDir = File(folder.root, "Azahar")
        val dbFile = ThreeDsMiiProvisioner.cflDatabaseFile(azaharDir)
        val customDb = ThreeDsMiiProvisioner.buildDefaultCflDatabase()
        // Mark slot 1 as occupied with a custom marker and verify it isn't overwritten.
        val slot1Offset = ThreeDsMiiProvisioner.MIIMAKER_SLOTS_OFFSET + ThreeDsMiiProvisioner.MII_DATA_SIZE
        customDb[slot1Offset + ThreeDsMiiProvisioner.MII_ID_OFFSET_IN_SLOT] = 0x99.toByte()
        writeFile(dbFile, customDb)

        assertFalse(ThreeDsMiiProvisioner.ensureDefaultMiiDatabase(azaharDir))
        assertEquals(0x99.toByte(), dbFile.readBytes()[slot1Offset + ThreeDsMiiProvisioner.MII_ID_OFFSET_IN_SLOT])
    }

    @Test
    fun emptyCfogDatabaseGetsDefaultMiiAndValidChecksum() {
        val azaharDir = File(folder.root, "Azahar")
        val dbFile = ThreeDsMiiProvisioner.cflDatabaseFile(azaharDir)
        val emptyDb = ThreeDsMiiProvisioner.buildDefaultCflDatabase()
        // Zero out all 100 owned Mii slots to simulate an empty Mii database.
        for (i in ThreeDsMiiProvisioner.MIIMAKER_SLOTS_OFFSET until ThreeDsMiiProvisioner.CFHE_OFFSET) {
            emptyDb[i] = 0
        }
        writeFile(dbFile, emptyDb)

        assertTrue(ThreeDsMiiProvisioner.ensureDefaultMiiDatabase(azaharDir))
        val updated = dbFile.readBytes()
        val idOffset = ThreeDsMiiProvisioner.MIIMAKER_SLOTS_OFFSET + ThreeDsMiiProvisioner.MII_ID_OFFSET_IN_SLOT
        assertTrue((updated[idOffset].toInt() and 0x80) != 0)
        val expectedCrc = ThreeDsMiiProvisioner.crc16Xmodem(updated, 0, ThreeDsMiiProvisioner.CFOG_CHECKSUM_OFFSET)
        val actualCrc =
            ((updated[ThreeDsMiiProvisioner.CFOG_CHECKSUM_OFFSET].toInt() and 0xFF) shl 8) or
                (updated[ThreeDsMiiProvisioner.CFOG_CHECKSUM_OFFSET + 1].toInt() and 0xFF)
        assertEquals(expectedCrc, actualCrc)
    }

    private fun writeFile(
        file: File,
        bytes: ByteArray,
    ): File {
        file.parentFile!!.mkdirs()
        file.writeBytes(bytes)
        return file
    }

    private class FakePreferences(val values: MutableMap<String, Any?>) : SharedPreferences {
        var commits = 0

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
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

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
                removes.forEach { values.remove(it) }
                values.putAll(puts)
                commits++
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
