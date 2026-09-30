package com.swordfish.lemuroid.lib.saves

import java.io.File
import java.security.MessageDigest

/** Validate before JNI: FBNeo can partially overwrite live memory even when loading returns false. */
class SaveStateCompatibility(
    private val version: Int,
    private val requiresCoreMatch: Boolean,
    private val coreSha256: String? = null,
) {
    fun metadataFor(
        state: ByteArray,
        diskIndex: Int,
    ): SaveState.Metadata {
        if (requiresCoreMatch && coreSha256.isNullOrBlank()) throw IncompatibleStateException()
        return SaveState.Metadata(
            diskIndex = diskIndex,
            version = version,
            coreSha256 = coreSha256.takeIf { requiresCoreMatch },
            stateSha256 = if (requiresCoreMatch) sha256(state) else null,
        )
    }

    fun requireCompatible(saveState: SaveState) {
        val metadata = saveState.metadata
        if (metadata.version != version) throw IncompatibleStateException()
        if (requiresCoreMatch &&
            (
                coreSha256.isNullOrBlank() || metadata.coreSha256 != coreSha256 ||
                    metadata.stateSha256 != sha256(saveState.state)
            )
        ) {
            throw IncompatibleStateException()
        }
    }

    companion object {
        /** Hash the selected .so, including downloaded cores and ABI changes, once per game load on IO. */
        fun fingerprint(core: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            core.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var count = input.read(buffer)
                while (count != -1) {
                    digest.update(buffer, 0, count)
                    count = input.read(buffer)
                }
            }
            return digest.digest().toHex()
        }

        private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).toHex()

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
