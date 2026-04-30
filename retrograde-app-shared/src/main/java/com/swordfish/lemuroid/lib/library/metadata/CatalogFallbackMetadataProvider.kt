package com.swordfish.lemuroid.lib.library.metadata

import com.swordfish.lemuroid.lib.library.catalog.CatalogCoverProvider
import com.swordfish.lemuroid.lib.storage.StorageFile

/**
 * A decorator [GameMetadataProvider] that delegates to the original provider
 * and, when the original returns metadata without a thumbnail, tries to
 * augment it with a cover URL from the embedded catalog_manifest.txt.
 */
class CatalogFallbackMetadataProvider(
    private val original: GameMetadataProvider,
    private val catalog: CatalogCoverProvider,
) : GameMetadataProvider {

    override suspend fun retrieveMetadata(storageFile: StorageFile): GameMetadata? {
        val metadata = original.retrieveMetadata(storageFile)
            ?: return null

        // If the original already found a thumbnail, nothing to do.
        if (!metadata.thumbnail.isNullOrBlank()) return metadata

        // If the original found the system but no thumbnail, try the catalog.
        val system = metadata.system ?: return metadata
        val fallbackUrl = catalog.getCoverUrl(system, storageFile.name)
            ?: return metadata

        return metadata.copy(thumbnail = fallbackUrl)
    }
}
