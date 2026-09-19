package com.swordfish.lemuroid.app.shared.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Builds synthetic `Tombstone` protobuf payloads by hand (field numbers match
 * `system/core/debuggerd/proto/tombstone.proto`, AOSP) to exercise [TombstoneParser] without a
 * device. Field numbers are duplicated here rather than reused from the parser because the
 * parser's constants are private — this also means a mismatch between the two would surface as a
 * failing test, not a silently wrong field number.
 */
private class ProtoWriter {
    private val out = ByteArrayOutputStream()

    private fun rawVarint(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7F.inv().toLong() == 0L) {
                out.write(v.toInt())
                return
            } else {
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
        }
    }

    private fun tag(field: Int, wireType: Int) = rawVarint(((field.toLong()) shl 3) or wireType.toLong())

    fun varint(field: Int, value: Long) {
        tag(field, 0)
        rawVarint(value)
    }

    fun bool(field: Int, value: Boolean) = varint(field, if (value) 1L else 0L)

    fun string(field: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        tag(field, 2)
        rawVarint(bytes.size.toLong())
        out.write(bytes)
    }

    fun message(field: Int, bytes: ByteArray) {
        tag(field, 2)
        rawVarint(bytes.size.toLong())
        out.write(bytes)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

private fun proto(block: ProtoWriter.() -> Unit): ByteArray = ProtoWriter().apply(block).toByteArray()

private fun frame(
    relPc: Long,
    pc: Long,
    fileName: String,
    functionName: String = "",
    functionOffset: Long = 0,
): ByteArray =
    proto {
        varint(1, relPc)
        varint(2, pc)
        if (functionName.isNotEmpty()) string(4, functionName)
        if (functionOffset != 0L) varint(5, functionOffset)
        if (fileName.isNotEmpty()) string(6, fileName)
    }

private fun thread(
    id: Int,
    name: String,
    frames: List<ByteArray>,
): ByteArray =
    proto {
        varint(1, id.toLong())
        string(2, name)
        frames.forEach { message(4, it) }
    }

/** `threads` is a proto map<int32, Thread>: each entry is key=1 (id) + value=2 (Thread). */
private fun threadMapEntry(
    id: Int,
    threadBytes: ByteArray,
): ByteArray =
    proto {
        varint(1, id.toLong())
        message(2, threadBytes)
    }

private fun mapping(
    begin: Long,
    end: Long,
    read: Boolean,
    write: Boolean,
    execute: Boolean,
    name: String,
): ByteArray =
    proto {
        varint(1, begin)
        varint(2, end)
        bool(4, read)
        bool(5, write)
        bool(6, execute)
        if (name.isNotEmpty()) string(7, name)
    }

private fun signal(
    number: Long,
    name: String,
    codeName: String,
    faultAddress: Long,
): ByteArray =
    proto {
        varint(1, number)
        string(2, name)
        string(4, codeName)
        bool(8, true)
        varint(9, faultAddress)
    }

class TombstoneParserTest {

    @Test
    fun testResolvedFrameHasNoMemoryMapSection() {
        val tid = 200
        val frames = listOf(frame(relPc = 0x1234, pc = 0x7100001234, fileName = "/data/app/foo/lib/arm64/snes9x_libretro_android.so"))
        val bytes =
            proto {
                varint(5, 100) // pid
                varint(6, tid.toLong())
                message(10, signal(11, "SIGSEGV", "SEGV_MAPERR", 0x7100001234))
                message(16, threadMapEntry(tid, thread(tid, "GLThread", frames)))
                message(17, mapping(0x7100000000, 0x7100002000, read = true, write = false, execute = true, name = "snes9x_libretro_android.so"))
            }

        val result = TombstoneParser.parse(bytes)

        assertNotNull(result)
        assertTrue(result!!.text.contains("backtrace:"))
        assertFalse("a resolved frame must not trigger the memory-map section", result.text.contains("memory near unresolved"))
        assertTrue(result.culpritLibrary == "snes9x_libretro_android.so")
    }

    @Test
    fun testUnresolvedFrameInsideAMappingIsDescribed() {
        val tid = 200
        val addr = 0x71deb10000L // inside [0x71deb00000, 0x71deb34000)
        val frames = listOf(frame(relPc = addr, pc = addr, fileName = ""))
        val bytes =
            proto {
                varint(5, 100)
                varint(6, tid.toLong())
                message(16, threadMapEntry(tid, thread(tid, "GLThread 171", frames)))
                message(17, mapping(0x71deb00000, 0x71deb34000, read = true, write = false, execute = true, name = "libfoo.so"))
            }

        val result = TombstoneParser.parse(bytes)

        assertNotNull(result)
        val text = result!!.text
        assertTrue(text.contains("memory near unresolved frame(s):"))
        assertTrue("expected the enclosing mapping to be named", text.contains("libfoo.so"))
        assertTrue("expected the enclosing mapping's permissions", text.contains("r-x"))
        assertTrue("expected the fault address", text.contains("0x71deb10000"))
    }

    @Test
    fun testUnresolvedFrameInGapBracketsTheNeighboringMappings() {
        val tid = 200
        // Right past the end of mapping A, before mapping B starts.
        val addr = 0x71deb34cd8L
        val frames = listOf(frame(relPc = addr, pc = addr, fileName = ""))
        val bytes =
            proto {
                varint(5, 100)
                varint(6, tid.toLong())
                message(10, signal(11, "SIGSEGV", "SEGV_MAPERR", addr))
                message(16, threadMapEntry(tid, thread(tid, "GLThread 171", frames)))
                message(17, mapping(0x71deb00000, 0x71deb34000, read = true, write = false, execute = true, name = "snes9x_libretro_android.so"))
                message(17, mapping(0x71deb40000, 0x71deb50000, read = true, write = true, execute = false, name = ""))
            }

        val result = TombstoneParser.parse(bytes)

        assertNotNull(result)
        val text = result!!.text
        assertTrue(text.contains("memory near unresolved frame(s):"))
        assertTrue("expected 'unmapped' since the address is in the gap", text.contains("is unmapped"))
        assertTrue("expected the library ending right before the gap", text.contains("snes9x_libretro_android.so"))
        assertTrue("expected the anonymous mapping starting after the gap", text.contains("[anon]"))
    }

    @Test
    fun testUnresolvedFrameWithoutAnyMappingsOmitsSection() {
        val tid = 200
        val frames = listOf(frame(relPc = 0x71deb34cd8, pc = 0x71deb34cd8, fileName = ""))
        val bytes =
            proto {
                varint(5, 100)
                varint(6, tid.toLong())
                message(16, threadMapEntry(tid, thread(tid, "GLThread 171", frames)))
            }

        val result = TombstoneParser.parse(bytes)

        assertNotNull(result)
        assertFalse(result!!.text.contains("memory near unresolved"))
    }

    @Test
    fun testMalformedInputReturnsNull() {
        val result = TombstoneParser.parse(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        assertNull(result)
    }
}
