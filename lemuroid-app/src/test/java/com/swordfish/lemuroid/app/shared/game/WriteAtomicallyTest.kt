package com.swordfish.lemuroid.app.shared.game

import com.swordfish.lemuroid.common.kotlin.readBytesUncompressed
import com.swordfish.lemuroid.common.kotlin.writeAtomically
import com.swordfish.lemuroid.common.kotlin.writeBytesAtomically
import com.swordfish.lemuroid.common.kotlin.writeBytesCompressed
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class WriteAtomicallyTest {
    @get:Rule val folder = TemporaryFolder()

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    @Test
    fun failureMidWriteKeepsThePreviousSave() {
        val save = File(folder.root, "game.srm").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        try {
            save.writeAtomically { out ->
                out.write(byteArrayOf(9))
                throw IOException("processo morto no meio da escrita")
            }
            fail("a excecao do write tem que chegar ao chamador")
        } catch (e: IOException) {
            // esperado
        }

        assertArrayEquals(byteArrayOf(1, 2, 3), save.readBytes())
        assertFalse("temporario orfao", File(folder.root, ".game.srm.tmp").exists())
    }

    @Test
    fun replacesAnExistingSave() {
        // rename(2) substitui o destino; o renameTo do JVM no Windows recusa. O app so roda em Linux.
        assumeFalse(isWindows)
        val save = File(folder.root, "game.srm").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        save.writeBytesAtomically(byteArrayOf(4, 5))

        assertArrayEquals(byteArrayOf(4, 5), save.readBytes())
        assertEquals(listOf("game.srm"), folder.root.list()!!.toList())
    }

    @Test
    fun compressedStateRoundTrips() {
        val state = File(folder.root, "game.state")
        val data = ByteArray(64 * 1024) { (it % 251).toByte() }

        state.writeBytesCompressed(data)

        assertArrayEquals(data, state.readBytesUncompressed())
        assertEquals(listOf("game.state"), folder.root.list()!!.toList())
    }
}
