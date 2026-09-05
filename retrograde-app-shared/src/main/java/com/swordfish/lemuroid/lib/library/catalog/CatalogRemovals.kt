package com.swordfish.lemuroid.lib.library.catalog

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Games the user explicitly removed from the catalog ("excluir do catálogo").
 *
 * Deleting the `games` row alone is not enough: [ManifestQuickLoader] re-inserts every
 * manifest entry whenever it runs a full pass (app update, MANIFEST_SCHEMA_VERSION bump),
 * so the game would silently come back. Each removal is recorded here as
 * `"systemId/fileName"` — the same shape as [com.swordfish.lemuroid.lib.library.db.entity.Game.downloadKey]
 * — and the loader skips those keys.
 *
 * Cleared in bulk by the "restaurar catálogo" action in settings, which then forces a full
 * manifest reload so everything is inserted again.
 */
object CatalogRemovals {

    private const val PREFS_NAME = "catalog_removals_prefs"
    private const val KEY_REMOVED = "removed_keys"

    private val _removed = MutableStateFlow<Set<String>>(emptySet())

    /** Observable removal set. Empty until the first call that touches SharedPreferences. */
    val removed: StateFlow<Set<String>> = _removed

    @Volatile
    private var loaded = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        // getStringSet's returned instance must never be mutated or stored — copy it.
        _removed.value = prefs(context).getStringSet(KEY_REMOVED, null)?.toHashSet() ?: emptySet()
        loaded = true
    }

    fun key(systemId: String, fileName: String): String = "$systemId/$fileName"

    fun all(context: Context): Set<String> {
        ensureLoaded(context)
        return _removed.value
    }

    /** Same as [removed], but guarantees the set was read from disk first. */
    fun observe(context: Context): StateFlow<Set<String>> {
        ensureLoaded(context)
        return removed
    }

    @Synchronized
    fun add(context: Context, keys: Collection<String>) {
        if (keys.isEmpty()) return
        ensureLoaded(context)
        val updated = _removed.value + keys
        if (updated.size == _removed.value.size) return
        prefs(context).edit().putStringSet(KEY_REMOVED, HashSet(updated)).apply()
        _removed.value = updated
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_REMOVED).apply()
        _removed.value = emptySet()
        loaded = true
    }
}
