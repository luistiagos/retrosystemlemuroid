package com.swordfish.lemuroid.app.utils.android

import android.app.Notification
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import com.swordfish.lemuroid.app.shared.telemetry.TelemetryContext

fun createSyncForegroundInfo(
    notificationId: Int,
    notification: Notification,
): ForegroundInfo {
    // DATA_SYNC has no OS-imposed timeout and remains valid through Android 15 (API 35).
    // SHORT_SERVICE (API 34+) is killed by the system after a hard 3-minute cap — it must
    // never back an indeterminate-duration job like a ROMs download or a library sync
    // (RomsDownloadWork, StreamingRomsWork, LibraryIndexWork, CoreUpdateWork, SaveSyncWork all
    // call into this). If a future Android version drops DATA_SYNC support, callers should
    // guard their own setForeground() call rather than have this function silently swap in a
    // type with a timeout unfit for the job.
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else -> ForegroundInfo(notificationId, notification)
    }
}

/**
 * Tags do marcador de trabalho em primeiro plano ([TelemetryContext.markForegroundWork]), uma por
 * caminho que poe o processo principal em `FOREGROUND_SERVICE`. Chegam no `lowmemory`/ANR do
 * processo principal como `fgs=<tag>` ([[2026-09-28-lowmemory-gameplay-ppsspp-foreground-service]]).
 * Nenhuma pode conter `+`, o separador do conjunto.
 */
object ForegroundWorkTag {
    const val DOWNLOAD = "download"
    const val CORE = "core"
    const val LIBRARY = "library"
    const val SAVE_SYNC = "savesync"
    const val ROMS_DOWNLOAD = "romsdownload"
    const val STREAMING = "streaming"
}

/**
 * Poe o Worker em primeiro plano ([CoroutineWorker.setForeground]) e, so se deu certo, marca [tag]
 * no breadcrumb de telemetria enquanto [block] roda; desmarca ao fim, inclusive por exceção ou
 * cancelamento ([[2026-09-28-lowmemory-gameplay-ppsspp-foreground-service]]).
 *
 * [onPromotionFailed] decide o que a falha do `setForeground` faz, como antes desta função: logar
 * e seguir sem notificação (o bloco roda sem marcador), ou relançar (o bloco não roda).
 */
suspend fun <T> CoroutineWorker.runInForeground(
    tag: String,
    foregroundInfo: ForegroundInfo,
    onPromotionFailed: (Exception) -> Unit,
    block: suspend () -> T,
): T =
    runMarkedInForeground(
        tag = tag,
        counter = workerForegroundTags,
        promote = { setForeground(foregroundInfo) },
        mark = { TelemetryContext.markForegroundWork(applicationContext, it) },
        clear = { TelemetryContext.clearForegroundWork(applicationContext, it) },
        onPromotionFailed = onPromotionFailed,
        block = block,
    )

/** Uma contagem por processo: é o processo (o pid) que o marcador descreve. */
private val workerForegroundTags = ForegroundTagCounter()

/** Lógica de [runInForeground], sem `Worker` nem `Context`. */
internal suspend fun <T> runMarkedInForeground(
    tag: String,
    counter: ForegroundTagCounter,
    promote: suspend () -> Unit,
    mark: (String) -> Unit,
    clear: (String) -> Unit,
    onPromotionFailed: (Exception) -> Unit,
    block: suspend () -> T,
): T {
    val promoted =
        try {
            promote()
            true
        } catch (e: Exception) {
            onPromotionFailed(e)
            false
        }
    if (!promoted) return block()

    counter.acquire(tag, mark)
    try {
        return block()
    } finally {
        counter.release(tag, clear)
    }
}

/**
 * Quantos trabalhos em primeiro plano deste processo seguram cada tag. Dois Workers com a mesma
 * tag rodam juntos (o `CoreUpdateWork` urgente e o de fundo têm ids de trabalho distintos): sem a
 * contagem, o primeiro a terminar apagaria o marcador do outro. A marcação e a desmarcação
 * acontecem dentro do lock, para um `release` e um `acquire` concorrentes não terminarem com o
 * marcador apagado e o trabalho rodando.
 */
internal class ForegroundTagCounter {
    private val counts = HashMap<String, Int>()

    /** Registra mais um dono de [tag]; chama [mark] quando ele é o primeiro. */
    @Synchronized
    fun acquire(
        tag: String,
        mark: (String) -> Unit,
    ) {
        val count = (counts[tag] ?: 0) + 1
        counts[tag] = count
        if (count == 1) mark(tag)
    }

    /** Tira um dono de [tag]; chama [clear] quando era o último. */
    @Synchronized
    fun release(
        tag: String,
        clear: (String) -> Unit,
    ) {
        val count = (counts[tag] ?: 0) - 1
        if (count > 0) {
            counts[tag] = count
        } else {
            counts.remove(tag)
            clear(tag)
        }
    }
}
