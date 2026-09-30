/*
 * FileKt.kt
 *
 * Copyright (C) 2017 Retrograde Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.swordfish.lemuroid.common.kotlin

import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipInputStream

private const val CRC32_BYTE_ARRAY_SIZE = 16 * 1024
private const val GZIP_INPUT_STREAM_BUFFER_SIZE = 8 * 1024

fun InputStream.calculateCrc32(): String =
    this.use { fileStream ->
        val buffer = ByteArray(CRC32_BYTE_ARRAY_SIZE)
        return CheckedInputStream(fileStream, CRC32()).use { crcStream ->
            while (crcStream.read(buffer) != -1) {
                // Read file in completely
            }
            crcStream.checksum.value.toStringCRC32()
        }
    }

fun File.calculateMd5(): String {
    val bytes =
        MessageDigest
            .getInstance("MD5")
            .digest(this.readBytes())
    return bytes.toHexString()
}

fun InputStream.writeToFile(file: File) {
    this.use { inputStream ->
        file.outputStream().use { outputStream ->
            inputStream.copyTo(outputStream)
        }
    }
}

fun ZipInputStream.extractEntryToFile(
    entryName: String,
    gameFile: File,
) {
    this.use { inputStream ->
        while (true) {
            val entry = inputStream.nextEntry
            if (entry.name == entryName) break
        }
        inputStream.writeToFile(gameFile)
    }
}

fun File.isZipped() = extension == "zip"

fun DocumentFile.isZipped() = type == "application/zip"

/** Returns the uncompressed input stream if gzip compressed. */
private fun File.uncompressedInputStream(): InputStream {
    val pb = PushbackInputStream(inputStream(), 2)
    val signature = ByteArray(2)
    val len = pb.read(signature)
    pb.unread(signature, 0, len)
    return if (signature[0] == 0x1f.toByte() && signature[1] == 0x8b.toByte()) {
        GZIPInputStream(pb, GZIP_INPUT_STREAM_BUFFER_SIZE)
    } else {
        pb
    }
}

/**
 * Replaces the file's content all at once: [write] fills a temporary file in the same directory,
 * which is then renamed over this one. A process killed in the middle — the low-memory killer
 * reclaiming a game in the background — leaves the previous content intact instead of a truncated
 * file. `File.writeBytes` truncates first, and a truncated `.srm` with `length() > 0` is handed to
 * the core as if it were a valid save.
 *
 * Guards against the process dying, not against power loss: there is no `fsync`. Relies on
 * `rename(2)` replacing the destination, which is POSIX behavior — on a Windows JVM `renameTo`
 * refuses to overwrite and this throws.
 */
fun File.writeAtomically(write: (OutputStream) -> Unit) {
    val temp = File(parentFile, ".$name.tmp")
    try {
        temp.outputStream().use(write)
        if (!temp.renameTo(this)) {
            throw IOException("Could not rename $temp to $this")
        }
    } catch (e: Throwable) {
        temp.delete()
        throw e
    }
}

fun File.writeBytesAtomically(array: ByteArray) = writeAtomically { it.write(array) }

/** Write bytes to file using GZIP compression. */
fun File.writeBytesCompressed(array: ByteArray) {
    writeAtomically { outputStream ->
        ByteArrayInputStream(array).use { inputStream ->
            GZIPOutputStream(outputStream).use { gzip -> inputStream.copyTo(gzip) }
        }
    }
}

/** Read bytes from file. If the file is compressed with GZIP the uncompressed data is returned.*/
fun File.readBytesUncompressed(): ByteArray =
    uncompressedInputStream().use { input ->
        val b = ByteArray(GZIP_INPUT_STREAM_BUFFER_SIZE)
        val os = ByteArrayOutputStream()
        os.use { usedOutputStream ->
            var c: Int
            while (input.read(b).also { c = it } != -1) {
                usedOutputStream.write(b, 0, c)
            }
        }
        return os.toByteArray()
    }
