package com.swordfish.lemuroid.app.shared.game

import com.swordfish.libretrodroid.GLRetroView

/**
 * Onde a GLThread estava quando deixou de responder.
 *
 * A [GLRetroView.GLThreadTimeoutException] traz so a pilha de quem esperava (`serializeSRAM`,
 * `getAvailableDisks`), que e sempre a mesma e nao aponta causa. O que decide o dono da travada e
 * o topo da pilha da propria GLThread: `LibretroDroid.step` (core), `eglSwapBuffers` (driver),
 * carga da ROM ainda em curso, ou `Object.wait` ociosa (bug no `GLSurfaceView` patchado) — ou a
 * ausencia dela, quando a thread ja encerrou e os eventos enfileirados nunca vao rodar.
 *
 * Ver `docs/bugs/open/2026-08-09-anr-inicializar-jogo-runongl-thread.md`.
 */
object GLThreadDump {
    // Nome dado pelo GLSurfaceView patchado ("GLThread <n>"); e o mesmo prefixo com que o
    // runOnGLThread reconhece a propria thread.
    private const val GL_THREAD_PREFIX = "GLThread"
    private const val MAX_FRAMES = 48

    /** Dump para anexar ao report, ou `null` quando o erro nao e um timeout da GLThread. */
    fun forFailure(error: Throwable): String? = if (error is GLRetroView.GLThreadTimeoutException) capture() else null

    fun capture(): String =
        try {
            val glThreads =
                Thread.getAllStackTraces()
                    .filterKeys { it.name.startsWith(GL_THREAD_PREFIX) }

            if (glThreads.isEmpty()) {
                "no live $GL_THREAD_PREFIX in process: queued GL events will never run"
            } else {
                buildString {
                    glThreads.forEach { (thread, frames) ->
                        append(thread.name).append(" state=").append(thread.state).append('\n')
                        frames.take(MAX_FRAMES).forEach { append("\tat ").append(it).append('\n') }
                        if (frames.size > MAX_FRAMES) append("\t...\n")
                    }
                }
            }
        } catch (e: Throwable) {
            "GL thread dump failed: $e"
        }
}
