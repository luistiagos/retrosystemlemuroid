package com.swordfish.lemuroid.app.shared.game

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Um processo `:game` hospeda **uma** sessao de core, nunca duas.
 *
 * O LibretroDroid nao descarrega o `.so` do core (pitfall 13 do `CLAUDE.md`): o `destroy()` chama
 * `retro_deinit`, e o `dlopen` da sessao seguinte no mesmo processo devolve a mesma imagem, com os
 * estaticos da anterior. O FBNeo nao sobrevive a isso: `BurnGameListExit` da `free` nas listas de
 * nomes de driver sem zerar os ponteiros, e o `retro_init` seguinte libera tudo de novo — double
 * free dentro do `retro_init`, ou heap corrompida ate um `strcmp(NULL)` no `retro_load_game`. E o
 * singleton nativo e um so: um `destroy()` adiado da sessao velha roda por cima da nova.
 *
 * O processo morre em `BaseGameActivity.finishAndExitProcess`, mas nao na hora: o `exitProcess`
 * sai `config_mediumAnimTime` (400 ms) depois do `finish()` — ate 2,4 s com telemetria em envio —,
 * e e nessa janela que o fallback de core relanca o jogo. E toda destruicao da Activity que nao passa por la (tarefa removida dos
 * recentes, recriacao) deixa o processo vivo em cache, pronto para o proximo jogo reaproveitar.
 *
 * Sinal na telemetria: o tombstone traz `name: GLThread 2` (ou maior). O contador de `GLThread` e
 * estatico, entao so passa de 1 quando o processo ja criou outra `GLRetroView`.
 *
 * Ver `docs/bugs/done/2026-09-28-investigacoes-baixa-confianca-triagem.md`.
 */
object GameProcessSession {
    private const val POLL_INTERVAL_MS = 50L

    /**
     * Folga para o `exitProcess` de `finishAndExitProcess`: 400 ms depois do `finish()`, ate 2,4 s
     * quando ha report de telemetria em envio (`BaseGameActivity.EXIT_DEADLINE_MS`).
     */
    private const val EXIT_TIMEOUT_MS = 3_000L

    /** Depois do `killProcess`, quanto esperar o sistema tirar o processo da lista. */
    private const val KILL_TIMEOUT_MS = 1_000L

    private var claimed = false

    /**
     * Chamado no `:game` antes de qualquer coisa tocar o core. `true` so para a primeira sessao do
     * processo; quem recebe `false` nao pode criar core aqui.
     */
    @Synchronized
    fun tryClaim(): Boolean {
        if (claimed) return false
        claimed = true
        return true
    }

    /**
     * Chamado no processo principal antes de relancar um jogo cuja sessao anterior acabou de
     * terminar: espera o `:game` sair, para que o relancamento ganhe um processo novo. Se ele nao
     * sair no prazo, e morto — a sessao dele ja terminou, e por isso que estamos relancando.
     *
     * Sem como listar os processos (ROMs de TV box devolvem `null` em `getRunningAppProcesses`),
     * segue sem esperar: no pior caso o relancamento cai no processo velho, que recusa a sessao
     * por [tryClaim] e devolve [BaseGameActivity.RESULT_RESTART_IN_FRESH_PROCESS].
     */
    suspend fun awaitGameProcessExit(context: Context) {
        withContext(Dispatchers.IO) {
            val killAt = SystemClock.elapsedRealtime() + EXIT_TIMEOUT_MS
            val giveUpAt = killAt + KILL_TIMEOUT_MS
            var killed = false
            while (true) {
                val pid = gameProcessPid(context) ?: return@withContext
                val now = SystemClock.elapsedRealtime()
                if (!killed && now >= killAt) {
                    Timber.w("Game process $pid did not exit in time; killing it before relaunch")
                    Process.killProcess(pid)
                    killed = true
                } else if (now >= giveUpAt) {
                    Timber.w("Game process $pid still listed after kill; relaunching anyway")
                    return@withContext
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun gameProcessPid(context: Context): Int? =
        runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val gameProcessName = "${context.packageName}:game"
            manager?.runningAppProcesses
                ?.firstOrNull { it.processName == gameProcessName }
                ?.pid
        }.getOrNull()
}
