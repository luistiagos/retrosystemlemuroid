package com.swordfish.libretrodroid

/**
 * Keeps native teardown out of active JNI calls, including frames and save states.
 * Requesting destruction never waits for a slow core on the Android main thread.
 */
internal class CoreWorkGuard(private val destroy: () -> Unit) {
    private val lock = Any()
    private var activeCalls = 0
    private var created = false
    private var destroyRequested = false
    private var destroyed = false

    val isReady: Boolean
        get() = synchronized(lock) { created && !destroyRequested && !destroyed }

    fun begin(requireCreated: Boolean = true): Boolean = synchronized(lock) {
        if (destroyRequested || destroyed || (requireCreated && !created)) {
            false
        } else {
            activeCalls++
            true
        }
    }

    fun markCreated() = synchronized(lock) {
        check(activeCalls > 0 && !created && !destroyed)
        created = true
    }

    fun end() {
        val destroyNow = synchronized(lock) {
            check(activeCalls > 0)
            activeCalls--
            claimDestruction()
        }
        if (destroyNow) destroy()
    }

    fun requestDestroy() {
        val destroyNow = synchronized(lock) {
            destroyRequested = true
            claimDestruction()
        }
        if (destroyNow) destroy()
    }

    /** Called with lock held; ownership is claimed before invoking native code. */
    private fun claimDestruction(): Boolean {
        if (!destroyRequested || !created || destroyed || activeCalls != 0) return false
        destroyed = true
        created = false
        return true
    }
}
