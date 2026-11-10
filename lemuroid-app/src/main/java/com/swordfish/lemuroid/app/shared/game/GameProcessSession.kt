package com.swordfish.lemuroid.app.shared.game

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

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
    const val LOCK_FILE_NAME = "game_process.lock"
    private const val POLL_INTERVAL_MS = 50L
    private const val LOCK_ACQUIRE_ATTEMPTS = 5
    private const val LOCK_RETRY_DELAY_MS = 10L

    /**
     * Folga para o `exitProcess` de `finishAndExitProcess`: 400 ms depois do `finish()`, ate 2,4 s
     * quando ha report de telemetria em envio (`BaseGameActivity.EXIT_DEADLINE_MS`).
     */
    private const val EXIT_TIMEOUT_MS = 3_000L

    /** Depois do `killProcess`, quanto esperar o sistema tirar o processo da lista. */
    private const val KILL_TIMEOUT_MS = 1_000L

    /**
     * Folga para o ActivityManagerService limpar os registros do processo morto
     * antes de um novo `startActivity` ser disparado.
     */
    private const val AMS_CLEANUP_GRACE_PERIOD_MS = 150L

    private var claimed = false
    private var coreCreated = false
    private var exiting = false

    private var heldLock: FileLock? = null
    private var heldChannel: FileChannel? = null
    private var heldRaf: RandomAccessFile? = null
    private var heldPid: Int? = null

    /**
     * Chamado na inicializacao do processo `:game` para registrar a existencia e travar o lock file.
     */
    @Synchronized
    fun onProcessStarted(context: Context) {
        acquireProcessLock(context.filesDir)
    }

    /**
     * Chamado no `:game` antes de qualquer coisa tocar o core. `true` so para a primeira sessao do
     * processo (ou para recriacao de configuracao da mesma Activity antes de qualquer `GLRetroView`
     * ter sido criada); quem recebe `false` nao pode criar core aqui.
     */
    @Synchronized
    fun tryClaim(
        context: Context? = null,
        isRecreation: Boolean = false,
    ): Boolean {
        if (exiting || coreCreated) return false
        if (claimed && !isRecreation) return false
        claimed = true
        context?.let { acquireProcessLock(it.filesDir) }
        return true
    }

    /**
     * Registra que a `GLRetroView` (e portanto o core nativo `.so`) foi instanciada neste processo.
     * A partir daqui, nenhuma nova `BaseGameActivity` (nem mesmo recriacao) pode reutilizar o processo.
     */
    @Synchronized
    fun onCoreCreated() {
        coreCreated = true
    }

    /**
     * Registra que a Activity foi destruida durante uma mudanca de configuracao antes de o core
     * nativo ter sido criado, permitindo que a instancia recriada em seguida assuma a sessao.
     */
    @Synchronized
    fun onActivityDestroyed(isChangingConfigurations: Boolean) {
        if (isChangingConfigurations && !coreCreated && !exiting) {
            claimed = false
        }
    }

    /**
     * Registra que o processo `:game` iniciou sua rotina de encerramento.
     * O `FileLock` nao e liberado em Java de proposito: o proprio kernel Linux libera o lock no
     * `do_exit()` quando a tabela de file descriptors do processo e fechada.
     */
    @Synchronized
    fun onProcessExiting() {
        exiting = true
    }

    @Synchronized
    fun acquireProcessLock(filesDir: File) {
        if (heldLock != null) return
        runCatching {
            val lockFile = File(filesDir, LOCK_FILE_NAME)
            val raf = RandomAccessFile(lockFile, "rw")
            val channel = raf.channel
            var lock: FileLock? = null
            for (attempt in 0 until LOCK_ACQUIRE_ATTEMPTS) {
                lock = runCatching { channel.tryLock() }.getOrNull()
                if (lock != null) break
                if (attempt < LOCK_ACQUIRE_ATTEMPTS - 1) {
                    Thread.sleep(LOCK_RETRY_DELAY_MS)
                }
            }
            if (lock != null) {
                heldRaf = raf
                heldChannel = channel
                heldLock = lock
                channel.truncate(0)
                val currentPid = runCatching { Process.myPid() }.getOrNull() ?: 0
                heldPid = currentPid
                channel.write(ByteBuffer.wrap("$currentPid\n".toByteArray(Charsets.UTF_8)))
                channel.force(true)
            } else {
                raf.close()
            }
        }.onFailure { e ->
            Timber.w(e, "Failed to acquire game process lock")
        }
    }

    @Synchronized
    fun releaseProcessLock() {
        try {
            heldLock?.release()
            heldChannel?.close()
            heldRaf?.close()
        } catch (e: Throwable) {
            Timber.w(e, "Failed to release game process lock")
        } finally {
            heldLock = null
            heldChannel = null
            heldRaf = null
            heldPid = null
        }
    }

    fun isGameProcessRunning(filesDir: File): Boolean {
        val lockFile = File(filesDir, LOCK_FILE_NAME)
        if (!lockFile.exists()) return false

        // If this process is the one holding the lock, it is definitely running.
        if (heldLock?.isValid == true) return true

        return runCatching {
            RandomAccessFile(lockFile, "rw").use { raf ->
                raf.channel.use { channel ->
                    val lock = channel.tryLock()
                    if (lock != null) {
                        lock.release()
                        false
                    } else {
                        true
                    }
                }
            }
        }.getOrElse {
            // If the file is locked by another process, Java NIO can throw OverlappingFileLockException,
            // and Windows/some filesystems throw IOException ("used by another process").
            true
        }
    }

    /**
     * Verifica se o processo `:game` esta atualmente rodando, testando o lock file de processo
     * e o ActivityManager como fallback.
     */
    fun isGameProcessRunning(context: Context): Boolean {
        return isGameProcessRunning(context.filesDir) || runningAppProcessPid(context) != null
    }

    fun readGameProcessPid(filesDir: File): Int? {
        if (heldPid != null) return heldPid
        val lockFile = File(filesDir, LOCK_FILE_NAME)
        if (!lockFile.exists()) return null
        return runCatching {
            RandomAccessFile(lockFile, "r").use { raf ->
                raf.readLine()?.trim()?.toIntOrNull()
            }
        }.getOrNull()
    }

    fun readGameProcessPid(context: Context): Int? = readGameProcessPid(context.filesDir)

    /**
     * Chamado no processo principal antes de relancar um jogo cuja sessao anterior acabou de
     * terminar: espera o `:game` sair, para que o relancamento ganhe um processo novo. Se ele nao
     * sair no prazo, e morto — a sessao dele ja terminou, e por isso que estamos relancando.
     *
     * Usa o lock file do kernel Linux + `Os.kill(pid, 0)` para deteccao confiavel mesmo em ROMs
     * de TV box onde `getRunningAppProcesses` devolve null ou omite processos secundarios.
     */
    suspend fun awaitGameProcessExit(
        context: Context,
        expectedPid: Int? = null,
    ) {
        withContext(Dispatchers.IO) {
            val killAt = SystemClock.elapsedRealtime() + EXIT_TIMEOUT_MS
            val giveUpAt = killAt + KILL_TIMEOUT_MS
            var killed = false
            var waited = false
            var lockedPid: Int? = null

            while (true) {
                val lockHeld = isGameProcessRunning(context.filesDir)
                if (lockHeld && lockedPid == null) {
                    lockedPid = readGameProcessPid(context.filesDir)
                }
                val amPid = runningAppProcessPid(context)
                val targetPid = expectedPid ?: lockedPid ?: amPid
                val running = lockHeld || amPid != null || (targetPid != null && isPidAlive(targetPid))

                if (!running) {
                    if (waited || expectedPid != null) {
                        delay(AMS_CLEANUP_GRACE_PERIOD_MS)
                    }
                    return@withContext
                }

                waited = true
                val now = SystemClock.elapsedRealtime()
                if (!killed && now >= killAt) {
                    if (targetPid != null) {
                        Timber.w("Game process $targetPid did not exit in time; killing it before relaunch")
                        runCatching { Process.killProcess(targetPid) }
                    }
                    killed = true
                } else if (now >= giveUpAt) {
                    Timber.w("Game process still listed after kill; relaunching anyway")
                    return@withContext
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun isPidAlive(pid: Int): Boolean {
        if (pid <= 0) return false
        val myPid = runCatching { Process.myPid() }.getOrNull() ?: 0
        if (pid == myPid && heldLock == null) return false
        return try {
            Os.kill(pid, 0)
            true
        } catch (e: Throwable) {
            false
        }
    }

    private fun runningAppProcessPid(context: Context): Int? =
        runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val gameProcessName = "${context.packageName}:game"
            manager?.runningAppProcesses
                ?.firstOrNull { it.processName == gameProcessName }
                ?.pid
        }.getOrNull()

    fun resetForTest() {
        claimed = false
        coreCreated = false
        exiting = false
        releaseProcessLock()
        heldPid = null
    }
}
