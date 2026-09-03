/*
 * FlycastCoreVerifier.kt
 *
 * Build-time guard for the packaged Flycast (Dreamcast) core.
 *
 * Two defects already shipped from this exact spot, both invisible until a device
 * crashed in native code:
 *
 *   1. 2026-07-07 — the buildbot `.so` does not link `libandroid.so`, so the weak
 *      `ASharedMemory_create` never resolves, vmem falls back to `open("/dev/ashmem")`
 *      (EACCES with targetSdk >= 29), fastmem turns off and the dynarec fallback path
 *      crashes. Fix: `patch_flycast_libandroid.py` adds `libandroid.so` to DT_NEEDED.
 *      See documentacao/bugs/done/2026-07-07-dreamcast-crash-boot-ashmem-libandroid.md
 *
 *   2. 2026-09-02 — a hand-built core (from a local `flycast_src` checkout of the old
 *      `libretro/flycast` fork) was dropped into `jniLibs/arm64-v8a` while the other
 *      three ABIs kept the correct buildbot binary. It was unpatched too, so every
 *      arm64 device — i.e. every real user — hit `os_DebugBreak` (SIGTRAP) in the
 *      GLThread. See documentacao/bugs/done/2026-09-02-crashes-nativos-cores-citra-flycast-dolphin.md
 *
 * Both are packaging mistakes that a human step ("remember to run the script") failed
 * to prevent twice, so the build enforces them instead:
 *
 *   • every packaged Flycast `.so` must list `libandroid.so` in DT_NEEDED;
 *   • no packaged Flycast `.so` may embed an absolute local source path — buildbot
 *     binaries embed none, a locally compiled one embeds its build directory.
 *
 * Pure JVM ELF reading: buildSrc must not depend on the Android toolchain, and adding
 * `lief`/binutils to the build machine would be another human step to forget.
 */

package com.swordfish.lemuroid.builder

import java.io.File

object FlycastCoreVerifier {

    private const val REQUIRED_LIBRARY = "libandroid.so"

    /** C/C++ source extensions that mark an embedded `__FILE__` from a local build. */
    private val SOURCE_EXTENSIONS = listOf(".cpp", ".cc", ".cxx", ".hpp", ".c", ".h")

    /**
     * Fails with [IllegalStateException] (Gradle renders it as the build failure) when any
     * of [coreFiles] is missing [REQUIRED_LIBRARY] in DT_NEEDED or looks locally compiled.
     * Files that do not exist are skipped: a flavor may legitimately not ship every ABI.
     */
    fun verify(coreFiles: List<File>) {
        val problems = mutableListOf<String>()
        var checked = 0

        for (file in coreFiles) {
            if (!file.isFile) continue
            checked++
            val bytes = file.readBytes()

            val needed = runCatching { readNeededLibraries(bytes) }
            val libraries = needed.getOrNull()
            when {
                libraries == null ->
                    problems += "${file.path}: could not read DT_NEEDED " +
                        "(${needed.exceptionOrNull()?.message})"
                REQUIRED_LIBRARY !in libraries ->
                    problems += "${file.path}: DT_NEEDED lacks $REQUIRED_LIBRARY (has $libraries). " +
                        "Run `python patch_flycast_libandroid.py` before building."
            }

            val localPath = findEmbeddedSourcePath(bytes)
            if (localPath != null) {
                problems += "${file.path}: embeds the local build path \"$localPath\", so this " +
                    "is a hand-compiled core, not the buildbot one. Restore the buildbot " +
                    "binary and re-run `python patch_flycast_libandroid.py`."
            }
        }

        check(checked > 0) {
            "FlycastCoreVerifier found no Flycast core to verify. Expected at least one of:\n" +
                coreFiles.joinToString("\n") { "  ${it.path}" }
        }
        check(problems.isEmpty()) {
            "Flycast core verification failed - this build would crash Dreamcast on device:\n" +
                problems.joinToString("\n") { "  - $it" }
        }
    }

    // ---------------------------------------------------------------- ELF

    /**
     * Returns the DT_NEEDED entries of an ELF shared object, reading the dynamic section
     * through the program headers (the section headers of a lief-rewritten `.so` may be
     * inconsistent, the program headers are what the dynamic linker itself uses).
     */
    private fun readNeededLibraries(bytes: ByteArray): List<String> {
        require(bytes.size > 64 && bytes[0] == 0x7F.toByte() && bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()) { "not an ELF file" }

        val is64 = bytes[4].toInt() == 2
        val littleEndian = bytes[5].toInt() == 1
        val reader = ElfReader(bytes, is64, littleEndian)

        // e_phoff / e_phentsize / e_phnum
        val phOff = if (is64) reader.long(0x20) else reader.int(0x1C).toLong()
        val phEntSize = if (is64) reader.short(0x36) else reader.short(0x2A)
        val phNum = if (is64) reader.short(0x38) else reader.short(0x2C)

        var dynamicOffset = -1L
        var dynamicSize = 0L
        val loads = mutableListOf<Triple<Long, Long, Long>>() // fileOffset, vAddr, fileSize

        for (i in 0 until phNum) {
            val header = phOff + i * phEntSize
            val type = reader.int(header)
            val offset: Long
            val vAddr: Long
            val fileSize: Long
            if (is64) {
                offset = reader.long(header + 0x08)
                vAddr = reader.long(header + 0x10)
                fileSize = reader.long(header + 0x20)
            } else {
                offset = reader.int(header + 0x04).toLong()
                vAddr = reader.int(header + 0x08).toLong()
                fileSize = reader.int(header + 0x10).toLong()
            }
            when (type) {
                PT_LOAD -> loads += Triple(offset, vAddr, fileSize)
                PT_DYNAMIC -> {
                    dynamicOffset = offset
                    dynamicSize = fileSize
                }
            }
        }
        require(dynamicOffset >= 0) { "no PT_DYNAMIC segment" }

        fun fileOffsetOf(vAddr: Long): Long {
            val load = loads.firstOrNull { (_, base, size) -> vAddr >= base && vAddr < base + size }
                ?: error("virtual address 0x${vAddr.toString(16)} is in no PT_LOAD segment")
            return load.first + (vAddr - load.second)
        }

        val entrySize = if (is64) 16 else 8
        var stringTableAddress = -1L
        val neededOffsets = mutableListOf<Long>()

        var cursor = dynamicOffset
        while (cursor < dynamicOffset + dynamicSize) {
            val tag: Long
            val value: Long
            if (is64) {
                tag = reader.long(cursor)
                value = reader.long(cursor + 8)
            } else {
                tag = reader.int(cursor).toLong()
                value = reader.int(cursor + 4).toLong() and 0xFFFFFFFFL
            }
            if (tag == DT_NULL) break
            when (tag) {
                DT_NEEDED -> neededOffsets += value
                DT_STRTAB -> stringTableAddress = value
            }
            cursor += entrySize
        }
        require(stringTableAddress >= 0) { "no DT_STRTAB entry" }

        val stringTable = fileOffsetOf(stringTableAddress)
        return neededOffsets.map { reader.string(stringTable + it) }
    }

    private const val PT_LOAD = 1
    private const val PT_DYNAMIC = 2
    private const val DT_NULL = 0L
    private const val DT_NEEDED = 1L
    private const val DT_STRTAB = 5L

    private class ElfReader(
        private val bytes: ByteArray,
        private val is64: Boolean,
        private val littleEndian: Boolean,
    ) {
        private fun byteAt(index: Long): Int = bytes[index.toInt()].toInt() and 0xFF

        fun short(index: Long): Int = read(index, 2).toInt()

        fun int(index: Long): Int = read(index, 4).toInt()

        fun long(index: Long): Long = read(index, if (is64) 8 else 4)

        private fun read(index: Long, width: Int): Long {
            var result = 0L
            for (i in 0 until width) {
                val b = byteAt(index + i).toLong()
                result = if (littleEndian) result or (b shl (8 * i)) else (result shl 8) or b
            }
            return result
        }

        fun string(index: Long): String {
            val builder = StringBuilder()
            var i = index
            while (byteAt(i) != 0) {
                builder.append(byteAt(i).toChar())
                i++
            }
            return builder.toString()
        }
    }

    // ------------------------------------------------- local-build detection

    private const val MAX_PATH_LENGTH = 200

    /**
     * Returns the first absolute Windows/Unix-drive source path baked into the binary by a
     * local compiler (`__FILE__` in an assert), or null when there is none. Buildbot cores
     * are built with relative paths, so a hit means the `.so` was compiled on this machine.
     */
    private fun findEmbeddedSourcePath(bytes: ByteArray): String? {
        for (i in 1 until bytes.size - 1) {
            if (bytes[i] != ':'.code.toByte()) continue
            val drive = bytes[i - 1].toInt().toChar()
            if (!drive.isLetter()) continue
            val separator = bytes[i + 1].toInt().toChar()
            if (separator != '/' && separator != '\\') continue

            val builder = StringBuilder().append(drive).append(':')
            var j = i + 1
            while (j < bytes.size && builder.length < MAX_PATH_LENGTH) {
                val c = bytes[j].toInt() and 0xFF
                if (c < 0x20 || c > 0x7E || c == '"'.code) break
                builder.append(c.toChar())
                j++
            }
            val candidate = builder.toString()
            if (SOURCE_EXTENSIONS.any { candidate.endsWith(it, ignoreCase = true) }) {
                return candidate
            }
        }
        return null
    }
}
