package com.swordfish.lemuroid.app.shared.startup

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File

/**
 * Points the native `TMPDIR` at an app-private directory before any Libretro core is loaded.
 *
 * Bionic's `tmpfile()` creates its file in `$TMPDIR` and, when the variable is unset, falls back to
 * `/data/local/tmp` — which is `shell:shell drwxrwx--x`, so an app uid gets `EACCES` and `tmpfile()`
 * returns `NULL`. Android never exports `TMPDIR` to app processes (verified on the live process
 * environment), so `tmpfile()` fails for every core, on every device.
 *
 * Cores do not check that return value. yabasanshiro hands the `NULL` straight to `fwrite` inside
 * `retro_serialize`, and `_FORTIFY_SOURCE` turns that into `abort()` — `FORTIFY: fwrite: null FILE*`,
 * SIGABRT on the GLThread, which never unwinds through the JVM (see pitfall 8 in CLAUDE.md). Saving
 * *and* loading Saturn states hit it. `ppsspp`, `atari800`, `hatari` and `fake08` import `tmpfile`
 * too and are exposed to the same failure on whichever paths use it.
 *
 * Modern bionic has no second fallback: if `$TMPDIR` does not exist, `tmpfile()` fails with `ENOENT`.
 * The directory must therefore be created before the variable is exported.
 */
object NativeTempDir {
    private const val TAG = "NativeTempDir"
    private const val DIR_NAME = "tmp"

    /**
     * Must run before the first core is loaded, and while the process is still single-threaded:
     * `setenv` is not safe against a concurrent `getenv` from another thread, and SQLite and the
     * cores themselves read the environment natively.
     *
     * Logs through [Log] rather than Timber on purpose — this runs before `DebugInitializer` plants
     * a tree, and in release no tree is ever planted, so a Timber call here would be discarded
     * exactly when the diagnostic is needed.
     */
    fun install(context: Context) {
        try {
            val tempDir = File(context.filesDir, DIR_NAME)
            if (!tempDir.isDirectory && !tempDir.mkdirs()) {
                Log.w(TAG, "Could not create ${tempDir.absolutePath}, leaving TMPDIR unset")
                return
            }
            deleteLeftovers(tempDir)
            Os.setenv("TMPDIR", tempDir.absolutePath, true)
            Log.i(TAG, "Native TMPDIR set to ${tempDir.absolutePath}")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to set native TMPDIR", e)
        }
    }

    /**
     * `tmpfile()` unlinks immediately, but a core using `mkstemp` leaves its file behind when the
     * process is killed mid-game — and nothing under `filesDir` is ever reclaimed by the system.
     * No core is loaded yet at this point, so emptying the directory cannot race one.
     */
    private fun deleteLeftovers(tempDir: File) {
        tempDir.listFiles()?.forEach { it.deleteRecursively() }
    }
}
