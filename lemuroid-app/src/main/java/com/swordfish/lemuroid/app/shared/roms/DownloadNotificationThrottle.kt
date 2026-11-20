package com.swordfish.lemuroid.app.shared.roms

/**
 * O que a notificação da fila de download mostra
 * (`NotificationsManager.romQueueNotification`): título do item em [SaveQueueState.SAVING],
 * percentual inteiro dele e quantos itens esperam em [SaveQueueState.QUEUED]. Duas emissões de
 * [SaveQueueManager.entries] com o mesmo estado desenham a mesma notificação.
 */
data class DownloadNotificationState(
    val title: String?,
    val percent: Int,
    val queuedCount: Int,
) {
    companion object {
        fun from(entries: List<SaveQueueEntry>): DownloadNotificationState {
            val saving = entries.firstOrNull { it.state == SaveQueueState.SAVING }
            return DownloadNotificationState(
                title = saving?.title,
                percent = saving?.let { (it.progress * 100).toInt() } ?: 0,
                queuedCount = entries.count { it.state == SaveQueueState.QUEUED },
            )
        }
    }
}

/**
 * Decide quando o `DownloadForegroundService` republica a notificação de progresso.
 *
 * [SaveQueueManager.entries] emite a cada `read()` do corpo HTTP, e cada publicação são Binder
 * síncronos para o `system_server` — que ainda descarta o que passar de 5 por segundo por pacote.
 * Troca de jogo ou da fila publica na hora; só o percentual, no máximo uma vez por
 * [PROGRESS_INTERVAL_MS] (demanda 2026-10-05-anr-downloadforegroundservice-createnotificationchannel-main-thread).
 *
 * O último percentual de uma janela pode ficar sem publicar até o próximo tick: durante o
 * download ele vem em milissegundos, e o fim do item muda o título/a fila ou esvazia a fila.
 */
object DownloadNotificationThrottle {
    const val PROGRESS_INTERVAL_MS = 1_000L

    fun shouldPublish(
        last: DownloadNotificationState?,
        next: DownloadNotificationState,
        lastPublishedAtMs: Long,
        nowMs: Long,
    ): Boolean {
        if (last == null) return true
        if (next == last) return false
        if (next.title != last.title || next.queuedCount != last.queuedCount) return true
        return nowMs - lastPublishedAtMs >= PROGRESS_INTERVAL_MS
    }
}
