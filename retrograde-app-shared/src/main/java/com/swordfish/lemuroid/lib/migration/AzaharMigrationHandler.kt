package com.swordfish.lemuroid.lib.migration

import android.content.SharedPreferences
import com.swordfish.lemuroid.lib.core.CoreVariablesManager
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import dagger.Lazy
import timber.log.Timber
import java.io.File

/**
 * Carries a 3DS player's progress and options from the old core (citra_libretro) to Azahar.
 *
 * Both ship as `CoreID.CITRA` / `libcitra_libretro_android.so`, but they disagree on two things the
 * user cannot see:
 *
 * 1. **Where saves live.** citra_libretro uses `<saves>/Citra/` as its user dir; Azahar uses
 *    `<saves>/Azahar/` (`ParseStorageOptions`, `citra_use_libretro_save_path = LibRetro Default`).
 *    The tree inside is the same (`sdmc/`, `nand/`, `sysdata/`, `cheats/`), so a copy is enough.
 *    Without it every save and extdata is still on disk but in a folder nobody reads.
 * 2. **Option keys and values.** Renamed keys would make the stored preference invisible to
 *    [CoreVariablesManager] — including the `hw shaders = off` that `CoreCrashFallback` writes after
 *    a crash. Layout and resolution keep their key but the core now declares short values
 *    (`side_by_side`, `2`), and the game menu only lists stored values the core declares.
 *
 * Runs on every 3DS load, before the core variables are read. Every step is idempotent and cheap
 * once done. Never throws: a failed migration must not keep the game from opening.
 *
 * See `docs/bugs/open/2026-11-07-3ds-sem-tela-unica-controles-em-coluna-e-imagem-deformada.md`,
 * section "T7: análise de implementação".
 */
class AzaharMigrationHandler(
    private val directoriesManager: DirectoriesManager,
    private val sharedPreferences: Lazy<SharedPreferences>,
) {
    fun migrate() {
        try {
            migratePreferences(sharedPreferences.get())
        } catch (e: Throwable) {
            Timber.e(e, "Azahar migration: preferences failed")
        }
        try {
            migrateUserDirectory(directoriesManager.getSavesDirectory())
        } catch (e: Throwable) {
            Timber.e(e, "Azahar migration: user directory failed")
        }
    }

    companion object {
        private const val SYSTEM_3DS = "3ds"

        private const val OLD_USER_DIR = "Citra"
        private const val NEW_USER_DIR = "Azahar"
        private const val STAGING_SUFFIX = ".migrating"

        // Compiled-shader cache of the old core: useless to Azahar and the bulk of the folder.
        private const val SKIPPED_SUBDIR = "shaders"

        private val RENAMED_KEYS =
            mapOf(
                "citra_use_hw_shaders" to "citra_use_hw_shader",
                "citra_use_acc_mul" to "citra_shaders_accurate_mul",
            )

        private const val LAYOUT_KEY = "citra_layout_option"
        private const val RESOLUTION_KEY = "citra_resolution_factor"

        // Old core's labels -> the values Azahar declares (GetLayoutOption accepts both).
        private val LAYOUT_VALUES =
            mapOf(
                "Default Top-Bottom Screen" to "default",
                "Single Screen Only" to "single_screen",
                "Large Screen, Small Screen" to "large_screen",
                "Side by Side" to "side_by_side",
            )

        // The old core went up to 10x; Azahar 2126.2 declares 1..18 (read from its option list on a
        // device, not from its source, whose table stops at 3).
        private const val MAX_RESOLUTION_FACTOR = 10

        private fun prefKey(key: String) = CoreVariablesManager.computeSharedPreferenceKey(key, SYSTEM_3DS)

        fun migratePreferences(preferences: SharedPreferences) {
            val all = preferences.all
            val editor = preferences.edit()
            var changed = false

            for ((oldKey, newKey) in RENAMED_KEYS) {
                val oldPref = prefKey(oldKey)
                val value = all[oldPref] ?: continue
                if (!all.containsKey(prefKey(newKey))) {
                    when (value) {
                        is Boolean -> editor.putBoolean(prefKey(newKey), value)
                        is String -> editor.putString(prefKey(newKey), value)
                    }
                }
                editor.remove(oldPref)
                changed = true
            }

            (all[prefKey(LAYOUT_KEY)] as? String)?.let { stored ->
                LAYOUT_VALUES[stored]?.let { mapped ->
                    editor.putString(prefKey(LAYOUT_KEY), mapped)
                    changed = true
                }
            }

            (all[prefKey(RESOLUTION_KEY)] as? String)?.let { stored ->
                val mapped = migrateResolution(stored)
                if (mapped != stored) {
                    editor.putString(prefKey(RESOLUTION_KEY), mapped)
                    changed = true
                }
            }

            if (changed) {
                // Synchronous: the core variables are read right after this returns.
                editor.commit()
                Timber.i("Azahar migration: 3DS preferences migrated")
            }
        }

        /** `"1x (Native)"`, `"2x"`, `"10x"` -> `"1"`, `"2"`, `"10"`. Unreadable -> `"1"`. */
        fun migrateResolution(stored: String): String {
            val factor = stored.takeWhile { it.isDigit() }.toIntOrNull() ?: 1
            return factor.coerceIn(1, MAX_RESOLUTION_FACTOR).toString()
        }

        fun migrateUserDirectory(savesDirectory: File) {
            val target = File(savesDirectory, NEW_USER_DIR)
            if (target.exists()) return

            val source = File(savesDirectory, OLD_USER_DIR)
            if (!source.isDirectory) return

            // A copy interrupted on an earlier launch: start over rather than trust it.
            val staging = File(savesDirectory, NEW_USER_DIR + STAGING_SUFFIX)
            if (staging.exists()) staging.deleteRecursively()

            copyTree(source, staging, skipped = setOf(SKIPPED_SUBDIR))

            if (!staging.renameTo(target)) {
                staging.deleteRecursively()
                throw IllegalStateException("could not rename $staging to $target")
            }
            // The old folder stays: it is the backup, and what a rollback to the old core reads.
            Timber.i("Azahar migration: copied $source to $target")
        }

        private fun copyTree(
            source: File,
            destination: File,
            skipped: Set<String> = emptySet(),
        ) {
            if (!destination.mkdirs() && !destination.isDirectory) {
                throw IllegalStateException("could not create $destination")
            }
            val children = source.listFiles() ?: throw IllegalStateException("could not list $source")
            for (child in children) {
                val target = File(destination, child.name)
                when {
                    child.name in skipped -> continue
                    child.isDirectory -> copyTree(child, target)
                    else -> child.copyTo(target, overwrite = true)
                }
            }
        }
    }
}
