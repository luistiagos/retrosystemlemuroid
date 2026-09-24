/*
 * LibretroDroidBridgeVerifier.kt
 *
 * Build-time guard for the packaged LibretroDroid bridge (`libs/libretrodroid-patched.aar`).
 *
 * The AAR is rebuilt by hand from an external checkout (`../LibretroDroid-patched`) whose
 * fixes live as uncommitted working-tree changes, and until 2026-09-24 `libs/` kept older
 * AARs around as rollback targets (`.known-good`, `.bak`, ...). Every AAR older than the
 * current one carries at least one of two defects that only show up as a native crash on a
 * user's device:
 *
 *   1. `Core::close()` calls `dlclose` on the libretro core. Unloading a core runs its
 *      static destructors (SIGABRT in `std::thread::~thread`, see
 *      documentacao/bugs/done/2026-09-02-libretrodroid-dlclose-core-anterior-sigabrt.md)
 *      and unmaps its code while the GLThread may still be executing inside it — the
 *      SIGSEGV whose PC *is* the fault address and whose every frame is `<unknown>`, see
 *      documentacao/bugs/done/2026-09-03-investigacao-sigsegv-glthread-pc-desmapeado.md
 *
 *   2. No `CoreWorkGuard`, so `LibretroDroid.destroy()` can run on the main thread while a
 *      frame or a queued save is still inside the core on the GLThread, see
 *      documentacao/bugs/done/2026-09-22-fbneo-retro-run-segv-gameplay.md
 *
 * So the build enforces what the fixed AAR looks like:
 *
 *   • no packaged `liblibretrodroid.so` may import `dlclose`;
 *   • `classes.jar` must contain `CoreWorkGuard`.
 *
 * Pure JVM ELF reading, like FlycastCoreVerifier: no Android toolchain in buildSrc.
 */

package com.swordfish.lemuroid.builder

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

object LibretroDroidBridgeVerifier {

    private const val BRIDGE_LIBRARY = "liblibretrodroid.so"
    private const val FORBIDDEN_IMPORT = "dlclose"
    private const val GUARD_CLASS = "com/swordfish/libretrodroid/CoreWorkGuard.class"

    /**
     * Fails with [IllegalStateException] (Gradle renders it as the build failure) when the
     * bridge inside [aar] can unload a core or lacks the teardown guard.
     */
    fun verify(aar: File) {
        check(aar.isFile) { "LibretroDroid AAR not found: ${aar.path}" }

        val problems = mutableListOf<String>()
        var checked = 0

        ZipFile(aar).use { zip ->
            for (entry in zip.entries().asSequence()) {
                if (!entry.name.startsWith("jni/") || !entry.name.endsWith("/$BRIDGE_LIBRARY")) continue
                checked++

                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val imports = runCatching { readUndefinedDynamicSymbols(bytes) }
                val symbols = imports.getOrNull()
                when {
                    symbols == null ->
                        problems += "${entry.name}: could not read .dynsym " +
                            "(${imports.exceptionOrNull()?.message})"
                    FORBIDDEN_IMPORT in symbols ->
                        problems += "${entry.name}: imports $FORBIDDEN_IMPORT, so the bridge can " +
                            "unload a core under a running thread. `Core::close()` in core.cpp " +
                            "must only drop the handle (libs/libretrodroid-patches/core-no-dlclose.patch)."
                }
            }

            if (!containsGuardClass(zip)) {
                problems += "classes.jar lacks ${GUARD_CLASS.removeSuffix(".class")}, so destroy() " +
                    "can race a frame on the GLThread. Re-apply " +
                    "libs/libretrodroid-patches/core-lifecycle.patch (see its README)."
            }
        }

        check(checked > 0) {
            "LibretroDroidBridgeVerifier found no jni/*/$BRIDGE_LIBRARY in ${aar.path}"
        }
        check(problems.isEmpty()) {
            "LibretroDroid bridge verification failed for ${aar.path} - " +
                "this build would crash the :game process natively:\n" +
                problems.joinToString("\n") { "  - $it" }
        }
    }

    private fun containsGuardClass(aar: ZipFile): Boolean {
        val classesJar = aar.getEntry("classes.jar") ?: return false
        return ZipInputStream(aar.getInputStream(classesJar)).use { jar ->
            generateSequence { jar.nextEntry }.any { it.name == GUARD_CLASS }
        }
    }

    // ---------------------------------------------------------------- ELF

    /**
     * Names of the undefined (imported) symbols in `.dynsym`. Read through the section
     * headers: the bridge is a plain NDK build, and `strip` keeps `.dynsym`/`.dynstr`.
     */
    private fun readUndefinedDynamicSymbols(bytes: ByteArray): Set<String> {
        require(bytes.size > 0x40 && bytes[0] == 0x7F.toByte() &&
            String(bytes, 1, 3, Charsets.US_ASCII) == "ELF") { "not an ELF file" }

        val is64 = bytes[4].toInt() == 2
        val order = if (bytes[5].toInt() == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val elf = ByteBuffer.wrap(bytes).order(order)

        fun half(at: Long): Int = elf.getShort(at.toInt()).toInt() and 0xFFFF
        fun word(at: Long): Long = elf.getInt(at.toInt()).toLong() and 0xFFFFFFFFL
        fun addr(at: Long): Long = if (is64) elf.getLong(at.toInt()) else word(at)

        // Elf{32,64}_Ehdr: e_shoff, e_shentsize, e_shnum
        val shOff = addr(if (is64) 0x28 else 0x20)
        val shEntSize = half(if (is64) 0x3A else 0x2E)
        val shNum = half(if (is64) 0x3C else 0x30)
        require(shOff > 0 && shNum > 0) { "no section headers" }

        // Elf{32,64}_Shdr fields
        fun header(index: Long): Long = shOff + index * shEntSize
        fun type(section: Long): Long = word(section + 0x04)
        fun offset(section: Long): Long = addr(section + if (is64) 0x18 else 0x10)
        fun size(section: Long): Long = addr(section + if (is64) 0x20 else 0x14)
        fun link(section: Long): Long = word(section + if (is64) 0x28 else 0x18)
        fun entrySize(section: Long): Long = addr(section + if (is64) 0x38 else 0x24)

        val dynsym = (0L until shNum).map(::header).firstOrNull { type(it) == SHT_DYNSYM }
            ?: error("no .dynsym section")
        val strings = offset(header(link(dynsym)))
        val symbolSize = entrySize(dynsym)
        require(symbolSize > 0) { ".dynsym has sh_entsize 0" }

        fun string(at: Long): String {
            var end = at
            while (bytes[end.toInt()].toInt() != 0) end++
            return String(bytes, at.toInt(), (end - at).toInt(), Charsets.US_ASCII)
        }

        val undefined = mutableSetOf<String>()
        for (i in 0L until size(dynsym) / symbolSize) {
            // Elf{32,64}_Sym: st_name at +0 in both layouts, st_shndx at +6 / +14.
            val symbol = offset(dynsym) + i * symbolSize
            val name = word(symbol)
            val sectionIndex = half(symbol + if (is64) 6 else 14)
            if (name != 0L && sectionIndex == SHN_UNDEF) {
                undefined += string(strings + name)
            }
        }
        return undefined
    }

    private const val SHT_DYNSYM = 11L
    private const val SHN_UNDEF = 0
}
