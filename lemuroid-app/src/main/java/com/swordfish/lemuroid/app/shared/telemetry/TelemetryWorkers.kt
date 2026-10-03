package com.swordfish.lemuroid.app.shared.telemetry

import java.util.Collections

/**
 * Os threads de envio da telemetria ainda em voo, para quem vai matar o processo poder esperar por
 * eles com prazo.
 *
 * Sem isto, um report nao-terminal (POST num thread daemon) morre junto com o `:game` quando a saida
 * do jogo chega ao `exitProcess` antes do fim do POST — e o report de `GLThreadTimeoutException`, que
 * carrega a pilha da GLThread, e justamente o que sai segundos antes da saida.
 *
 * Separado do [TelemetryReporter] para ser testavel na JVM sem rede. Nunca lanca.
 */
internal class TelemetryWorkers {
    private val inFlight = Collections.synchronizedSet(HashSet<Thread>())

    val pendingCount: Int
        get() = inFlight.size

    /** Cria e inicia um thread daemon que roda [block] e sai do conjunto ao terminar. */
    fun start(
        name: String,
        block: () -> Unit,
    ): Thread {
        val worker =
            Thread({
                try {
                    block()
                } finally {
                    inFlight.remove(Thread.currentThread())
                }
            }, name).apply { isDaemon = true }
        // Registrado antes do start(): um worker rapido sairia do conjunto antes de entrar.
        inFlight.add(worker)
        try {
            worker.start()
        } catch (e: Throwable) {
            inFlight.remove(worker)
            throw e
        }
        return worker
    }

    /**
     * Espera os workers em voo terminarem, por no maximo [timeoutMs] no **total** (nao por worker).
     * Sem nenhum em voo, volta na hora.
     */
    fun awaitAll(timeoutMs: Long) {
        try {
            val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLI
            val snapshot = synchronized(inFlight) { inFlight.toList() }
            for (worker in snapshot) {
                val remainingMs = (deadline - System.nanoTime()) / NANOS_PER_MILLI
                if (remainingMs <= 0) return
                worker.join(remainingMs)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (ignored: Throwable) {
            // telemetry never affects app behavior
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
