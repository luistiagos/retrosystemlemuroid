/*
 * BundledCoresVerifier.kt
 *
 * Build-time guard for the libretro cores packaged from the `lemuroid-cores` submodule.
 *
 * Two defects shipped from here without any build, test or device run noticing:
 *
 *   1. 17 cores were packaged as `<name>_libretro_android.so`, without the `lib` prefix.
 *      The package installer only extracts `lib*.so` from `lib/<abi>/` into
 *      `nativeLibraryDir` (NativeLibrariesIterator in NativeLibraryHelper.cpp; since
 *      Android 13 the filter is skipped for *debuggable* APKs, so every debug build hid
 *      it). In the release APK those cores were never extracted, `GameLoader.findLibrary`
 *      missed them and every user downloaded them again — or, offline, could not play.
 *      See docs/bugs/done/2026-09-25-cores-sem-prefixo-lib-nao-extraidos-no-release.md
 *
 *   2. The submodule pointer moved two months back, the newer cores stayed on disk as
 *      untracked files and the APK was built for weeks from binaries no commit held,
 *      on top of a local commit that was never pushed.
 *      See docs/bugs/done/2026-09-25-lemuroid-cores-submodulo-divergente-commit-nao-publicado.md
 *
 * So the build enforces:
 *
 *   • [verifyNames] (every variant): every core `.so` is named `lib*.so`, and the file
 *     names in `CoreID.kt` are exactly the ones in `bundled-cores` — renaming on one side
 *     only would silently fall back to downloading the core;
 *   • [verifyVersioned] (release variants): nothing packaged from `bundled-cores/jniLibs`
 *     is outside git, the submodule HEAD is on a remote branch, and the cores tag the app
 *     downloads from (`CoreDownloader.CORES_VERSION`) holds every `CoreID` file.
 */

package com.swordfish.lemuroid.builder

import java.io.File
import java.util.concurrent.TimeUnit

object BundledCoresVerifier {

    /** Every real device is arm64 (or armv7); the bundle must be complete there. */
    private const val REFERENCE_ABI = "arm64-v8a"

    private const val BUNDLED_JNI_LIBS = "bundled-cores/src/main/jniLibs"

    /** `NAME("coreName", "Display Name", ... "libfoo_libretro_android.so")` in CoreID.kt. */
    private val CORE_ENTRY =
        Regex("""\b[A-Z0-9_]+\(\s*"([^"]+)"\s*,\s*"[^"]*"\s*,(?:\s*//[^\n]*)*\s*"([^"]+\.so)"""")

    private val CORES_VERSION = Regex("""const\s+val\s+CORES_VERSION\s*=\s*"([^"]+)"""")

    /**
     * Fails when a packaged core is not named `lib*.so` or when [coreIdSource] and the
     * `bundled-cores` directory disagree on the file names. [extraJniLibsDirs] are other
     * `jniLibs` folders that end up in the APK (the app module's own), name-checked only.
     */
    fun verifyNames(coresRepo: File, coreIdSource: File, extraJniLibsDirs: List<File> = emptyList()) {
        val problems = mutableListOf<String>()

        val coresJniLibsDirs = coresRepo.listFiles().orEmpty()
            .filter { it.name == "bundled-cores" || it.name.startsWith("lemuroid_core_") }
            .map { File(it, "src/main/jniLibs") }
            .filter { it.isDirectory }
        check(coresJniLibsDirs.isNotEmpty()) {
            "BundledCoresVerifier found no jniLibs under ${coresRepo.path}. " +
                "Run `git submodule update --init lemuroid-cores`."
        }

        for (dir in coresJniLibsDirs + extraJniLibsDirs.filter { it.isDirectory }) {
            dir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".so") && !it.name.startsWith("lib") }
                .forEach { problems += "${it.invariantSeparatorsPath}: not named lib*.so" }
        }

        val coreFiles = readCoreEntries(coreIdSource).map { it.second }
        coreFiles.filterNot { it.startsWith("lib") }
            .forEach { problems += "${coreIdSource.name}: \"$it\" is not named lib*.so" }

        val bundled = File(coresRepo, BUNDLED_JNI_LIBS)
        val abiDirs = bundled.listFiles().orEmpty().filter { it.isDirectory }
        for (abiDir in abiDirs) {
            val present = abiDir.listFiles().orEmpty().filter { it.name.endsWith(".so") }.map { it.name }.toSet()
            (present - coreFiles.toSet()).forEach {
                problems += "$BUNDLED_JNI_LIBS/${abiDir.name}/$it: no CoreID uses this file name"
            }
            if (abiDir.name == REFERENCE_ABI) {
                (coreFiles.toSet() - present).forEach {
                    problems += "$BUNDLED_JNI_LIBS/$REFERENCE_ABI: missing $it, named in ${coreIdSource.name}"
                }
            }
        }
        check(abiDirs.any { it.name == REFERENCE_ABI }) { "$BUNDLED_JNI_LIBS/$REFERENCE_ABI not found" }

        check(problems.isEmpty()) {
            "Bundled cores verification failed. The installer only extracts lib*.so, so a core " +
                "named otherwise is never found on a release install and gets downloaded again " +
                "(or cannot load offline):\n" + problems.joinToString("\n") { "  - $it" }
        }
    }

    /**
     * Fails when the release would ship cores that no published commit holds, or would
     * download cores from a tag that lacks them.
     */
    fun verifyVersioned(coresRepo: File, coreIdSource: File, coreDownloaderSource: File) {
        val problems = mutableListOf<String>()

        val dirty = git(coresRepo, "status", "--porcelain", "--ignored", "--", BUNDLED_JNI_LIBS)
        if (dirty.isNotBlank()) {
            problems += "files under $BUNDLED_JNI_LIBS are untracked, modified or ignored, so " +
                "no commit reproduces this APK:\n" + dirty.lines().filter { it.isNotBlank() }
                .joinToString("\n") { "      $it" }
        }

        val head = git(coresRepo, "rev-parse", "HEAD").trim()
        val remoteBranches = git(coresRepo, "branch", "-r", "--contains", "HEAD").trim()
        if (remoteBranches.isEmpty()) {
            problems += "lemuroid-cores HEAD ${head.take(7)} is on no remote branch (as of the " +
                "last fetch). Push it before building a release."
        }

        val coresVersion = CORES_VERSION.find(coreDownloaderSource.readText())?.groupValues?.get(1)
        if (coresVersion == null) {
            problems += "could not read CORES_VERSION from ${coreDownloaderSource.name}"
        } else {
            val tagCommit = runCatching { git(coresRepo, "rev-parse", "--verify", "$coresVersion^{commit}").trim() }
            if (tagCommit.isFailure) {
                problems += "cores tag $coresVersion (CoreDownloader.CORES_VERSION) does not exist in " +
                    "lemuroid-cores. Create and push it, or fetch tags."
            } else {
                val missing = readCoreEntries(coreIdSource).map { (coreName, file) ->
                    "lemuroid_core_$coreName/src/main/jniLibs/$REFERENCE_ABI/$file"
                }.filter { path ->
                    runCatching { git(coresRepo, "cat-file", "-e", "$coresVersion:$path") }.isFailure
                }
                missing.forEach {
                    problems += "cores tag $coresVersion lacks $it, so downloading that core returns 404"
                }
            }
        }

        check(problems.isEmpty()) {
            "Cores versioning check failed for a release build:\n" +
                problems.joinToString("\n") { "  - $it" }
        }
    }

    private fun readCoreEntries(coreIdSource: File): List<Pair<String, String>> {
        val entries = CORE_ENTRY.findAll(coreIdSource.readText())
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
        check(entries.isNotEmpty()) {
            "BundledCoresVerifier read no core entry from ${coreIdSource.path}; update CORE_ENTRY " +
                "if CoreID's constructor changed."
        }
        return entries
    }

    private fun git(repo: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repo.absolutePath) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "git ${args.joinToString(" ")} timed out" }
        check(process.exitValue() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output
    }
}
