package com.swordfish.lemuroid.lib.migration

import java.io.File

/**
 * Ensures a valid Nintendo 3DS Mii database (`CFL_DB.dat`) with a default Personal Mii exists in
 * the 3DS core's Shared ExtData (`00048000/F000000B`).
 *
 * Games such as *Super Mario Maker for Nintendo 3DS* read `/CFL_DB.dat` directly from Shared
 * ExtData `0xF000000B` and block on `"No Mii was found. Press the HOME Button to return to the
 * HOME Menu."` when no Mii exists — an unrecoverable dead end in libretro cores that have no HOME
 * Menu or Mii Maker applet preinstalled.
 */
object ThreeDsMiiProvisioner {
    private const val SHARED_EXTDATA_DIR_PATH =
        "nand/data/00000000000000000000000000000000/extdata/00048000/F000000B"
    private const val SHARED_EXTDATA_RELATIVE_PATH =
        "$SHARED_EXTDATA_DIR_PATH/user/CFL_DB.dat"
    private const val EXTDATA_METADATA_SIZE = 16

    const val CFL_DB_SIZE = 0x4BD20
    const val CFOG_MAGIC = "CFOG"
    const val CFHE_MAGIC = "CFHE"
    const val CFRA_MAGIC = "CFRA"

    const val MIIMAKER_SLOTS_OFFSET = 0x0008
    const val MIIMAKER_SLOT_COUNT = 100
    const val MII_DATA_SIZE = 0x5C
    const val MII_ID_OFFSET_IN_SLOT = 0x0C

    const val CFHE_OFFSET = 0x23F8
    const val CFHE_NODE_OFFSET = 0x2400
    const val CFHE_NODE_COUNT = 3000
    const val CFHE_NODE_SIZE = 0x0E

    const val CFOG_CHECKSUM_OFFSET = 0xC81E
    const val CFRA_OFFSET = 0xC820
    const val CFRA_CHECKSUM_OFFSET = 0xE4BE

    private const val CRC16_POLY = 0x1021

    // Based on Azahar's open-source Standard Mii (`MiiSelector::GetStandardMiiResult`, 0x5C bytes)
    // placed at slot 0 (page 0, slot 0), marked copyable and named "Player".
    private val DEFAULT_MII_BYTES =
        byteArrayOf(
            0x03.toByte(), 0x01.toByte(), 0x00.toByte(), 0x30.toByte(),
            0xD2.toByte(), 0x85.toByte(), 0xB6.toByte(), 0xB3.toByte(),
            0x00.toByte(), 0xC8.toByte(), 0x85.toByte(), 0x0A.toByte(),
            0x98.toByte(), 0x39.toByte(), 0x1E.toByte(), 0xE4.toByte(),
            0x40.toByte(), 0xF4.toByte(), 0x07.toByte(), 0xB7.toByte(),
            0x37.toByte(), 0x10.toByte(), 0x00.toByte(), 0x00.toByte(),
            0xA6.toByte(), 0x00.toByte(),
            // Name: "Player" (UTF-16LE, 10 chars max, null-terminated)
            0x50.toByte(), 0x00.toByte(), 0x6C.toByte(), 0x00.toByte(),
            0x61.toByte(), 0x00.toByte(), 0x79.toByte(), 0x00.toByte(),
            0x65.toByte(), 0x00.toByte(), 0x72.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            // Facial geometry & proportions
            0x40.toByte(), 0x40.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x21.toByte(), 0x01.toByte(), 0x02.toByte(), 0x68.toByte(),
            0x44.toByte(), 0x18.toByte(), 0x26.toByte(), 0x34.toByte(),
            0x46.toByte(), 0x14.toByte(), 0x81.toByte(), 0x12.toByte(),
            0x17.toByte(), 0x68.toByte(), 0x0D.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x29.toByte(), 0x00.toByte(), 0x52.toByte(),
            0x48.toByte(), 0x50.toByte(),
            // Author: "System" (UTF-16LE, 10 chars max, null-terminated)
            0x53.toByte(), 0x00.toByte(), 0x79.toByte(), 0x00.toByte(),
            0x73.toByte(), 0x00.toByte(), 0x74.toByte(), 0x00.toByte(),
            0x65.toByte(), 0x00.toByte(), 0x6D.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        )

    fun cflDatabaseFile(coreUserDir: File): File = File(coreUserDir, SHARED_EXTDATA_RELATIVE_PATH)

    /**
     * Creates a default `CFL_DB.dat` if none exists (or if the existing one is an empty/zero-Mii
     * `CFOG` database). Existing databases that already contain at least one Mii are never modified.
     */
    fun ensureDefaultMiiDatabase(coreUserDir: File): Boolean {
        ensureSharedExtDataSkeleton(coreUserDir)
        val target = cflDatabaseFile(coreUserDir)
        if (target.isFile && target.length() > 0L) {
            val existing = target.readBytes()
            if (existing.size < CFOG_CHECKSUM_OFFSET + 2 || !hasCfogMagic(existing) || hasAnyMii(existing)) {
                return false
            }
            // Empty CFOG database (all 100 slots have mii_id == 0): populate slot 0 and re-sign.
            DEFAULT_MII_BYTES.copyInto(existing, destinationOffset = MIIMAKER_SLOTS_OFFSET)
            writeBigEndianU16(existing, CFOG_CHECKSUM_OFFSET, crc16Xmodem(existing, 0, CFOG_CHECKSUM_OFFSET))
            writeAtomic(target, existing)
            return true
        }

        writeAtomic(target, buildDefaultCflDatabase())
        return true
    }

    fun buildDefaultCflDatabase(): ByteArray {
        val db = ByteArray(CFL_DB_SIZE)

        // CFOG section header + version 0x00000100 (little-endian)
        writeAscii(db, 0x0000, CFOG_MAGIC)
        db[0x0004] = 0x00
        db[0x0005] = 0x01
        db[0x0006] = 0x00
        db[0x0007] = 0x00

        // Slot 0: default Personal Mii
        DEFAULT_MII_BYTES.copyInto(db, destinationOffset = MIIMAKER_SLOTS_OFFSET)

        // CFHE section: empty linked list (head = 0xFFFF, tail = 0xFFFF) + 3000 invalid nodes
        writeAscii(db, CFHE_OFFSET, CFHE_MAGIC)
        db[CFHE_OFFSET + 4] = 0xFF.toByte()
        db[CFHE_OFFSET + 5] = 0xFF.toByte()
        db[CFHE_OFFSET + 6] = 0xFF.toByte()
        db[CFHE_OFFSET + 7] = 0xFF.toByte()
        for (i in 0 until CFHE_NODE_COUNT) {
            val nodeOffset = CFHE_NODE_OFFSET + i * CFHE_NODE_SIZE
            // prev = 0x7FFF, next = 0x7FFF (little-endian)
            db[nodeOffset + 10] = 0xFF.toByte()
            db[nodeOffset + 11] = 0x7F.toByte()
            db[nodeOffset + 12] = 0xFF.toByte()
            db[nodeOffset + 13] = 0x7F.toByte()
        }

        // First CRC-16/XMODEM covering [0x0000, 0xC81E), stored big-endian at 0xC81E
        writeBigEndianU16(db, CFOG_CHECKSUM_OFFSET, crc16Xmodem(db, 0, CFOG_CHECKSUM_OFFSET))

        // CFRA section header + empty invitations count + CRC-16/XMODEM at 0xE4BE
        writeAscii(db, CFRA_OFFSET, CFRA_MAGIC)
        writeBigEndianU16(
            db,
            CFRA_CHECKSUM_OFFSET,
            crc16Xmodem(db, CFRA_OFFSET, CFRA_CHECKSUM_OFFSET),
        )

        return db
    }

    private fun ensureSharedExtDataSkeleton(coreUserDir: File) {
        val extDataDir = File(coreUserDir, SHARED_EXTDATA_DIR_PATH)
        val bossDir = File(extDataDir, "boss")
        if (!bossDir.exists()) {
            bossDir.mkdirs()
        }
        val metadataFile = File(extDataDir, "metadata")
        if (!metadataFile.exists() || metadataFile.length() == 0L) {
            writeAtomic(metadataFile, ByteArray(EXTDATA_METADATA_SIZE))
        }
    }

    fun crc16Xmodem(
        data: ByteArray,
        fromIndex: Int,
        toIndex: Int,
    ): Int {
        var crc = 0
        for (i in fromIndex until toIndex) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            for (bit in 0 until 8) {
                crc =
                    if ((crc and 0x8000) != 0) {
                        ((crc shl 1) xor CRC16_POLY) and 0xFFFF
                    } else {
                        (crc shl 1) and 0xFFFF
                    }
            }
        }
        return crc and 0xFFFF
    }

    private fun hasCfogMagic(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'C'.code.toByte() &&
            bytes[1] == 'F'.code.toByte() &&
            bytes[2] == 'O'.code.toByte() &&
            bytes[3] == 'G'.code.toByte()

    private fun hasAnyMii(bytes: ByteArray): Boolean {
        for (slot in 0 until MIIMAKER_SLOT_COUNT) {
            val slotOffset = MIIMAKER_SLOTS_OFFSET + slot * MII_DATA_SIZE
            val idOffset = slotOffset + MII_ID_OFFSET_IN_SLOT
            if (bytes[idOffset] != 0.toByte() ||
                bytes[idOffset + 1] != 0.toByte() ||
                bytes[idOffset + 2] != 0.toByte() ||
                bytes[idOffset + 3] != 0.toByte()
            ) {
                return true
            }
        }
        return false
    }

    private fun writeAscii(
        dest: ByteArray,
        offset: Int,
        ascii: String,
    ) {
        for (i in ascii.indices) {
            dest[offset + i] = ascii[i].code.toByte()
        }
    }

    private fun writeBigEndianU16(
        dest: ByteArray,
        offset: Int,
        value: Int,
    ) {
        dest[offset] = ((value ushr 8) and 0xFF).toByte()
        dest[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeAtomic(
        target: File,
        bytes: ByteArray,
    ) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IllegalStateException("could not create $parent")
        }
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
    }
}
