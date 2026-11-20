---
resultado: passou
base: 1ba98c924be79a3fa41eb46bccf380465e8501d7
---
# Aceitação de 2026-10-05-anr-downloadforegroundservice-createnotificationchannel-main-thread

Execução 20261119-2228, 2026-11-20. Ramo principal com as duas tasks integradas: TASK-0005 (`4b66511`, canal criado
uma vez por processo) e TASK-0006 (`d18e538`, notificação da fila só quando muda, no máximo 1/s, montada e postada
no IO). As duas são ancestrais do HEAD testado (`git merge-base --is-ancestor`). Os arquivos de evidência estão em
`evidencias/aceitacao/`. O logcat bruto da janela está em
`<pasta do pipeline>/reports/TASK-0009-evidencias-brutas/logcat-completo.txt`.

## Ambiente e binário

- Aparelho: `emulator-5554`, o `lemu_3ds_api34` (Android 14, x86_64). Foi o único no adb e o escolhido pelo
  `aparelho-instalar`.
- Build: `build --bug TASK-0009 --emulador` no HEAD `1ba98c9`, BUILD SUCCESSFUL. Testes de unidade
  (`testFreeBundleDebugUnitTest`): 116 testes, 0 falhas/erros, 1 pulado.
- O sha256 do `base.apk` instalado é `c75a4d0d…16d046`, igual ao `lemuroid-app-free-bundle-x86_64-debug.apk` da lane.
  `versionName 1.17.36-DEBUG`, `lastUpdateTime 2026-11-20 14:34:49` (`pacote.txt`).
- Processos do meu launch: principal com PID 17805 (14:35), `:game` com PID 18585 (14:41).

## Prova do bug: T2 da demanda, pelo caminho do usuário

Caminho: Início → Busca → "metal gear solid" → *Metal Gear Solid - Special Missions* (PSX, não baixado) → **Save**
às 14:37:08. A folha da fila abriu com o download correndo. Às 14:38:04 rodei `am trace-ipc start` e esperei 30 s. Às
14:38:36 rodei `am trace-ipc stop --dump-file`. Antes e depois, os contadores do `dumpsys notification`.

| Sinal | Defeito (doc da demanda / controles positivos das tasks) | Observado no ramo principal |
|---|---|---|
| `createNotificationChannels` do app em 30 s (`ipc.txt`) | dezenas a centenas, pilha `createDownloadNotificationChannel` ← `romQueueNotification` ← `DownloadForegroundService` sobre a main (base TASK-0005: 9; base TASK-0006: 28+) | **0** |
| `enqueueNotificationWithTag` em 30 s (`ipc.txt`) | centenas, na main (base TASK-0006: +724 enqueue em ~32 s) | **14**, todos com pilha `publishQueueNotification` ← `observeQueue$1$1$1.invokeSuspend` ← `LimitedDispatcher$Worker` ← `CoroutineScheduler$Worker.run` (IO). Nenhum na main |
| `numEnqueuedByApp` / `numUpdatedByApp` no intervalo (`stats.txt`) | ~73% dos enqueues descartados pelo limite de 5/s | 7036→7051 (+15) / 2028→2043 (+15): nada descartado |
| `NotificationService: … Shedding … package=app.retrogamesystem.debug` | 46 em ~4 min (base TASK-0006) | **0** no logcat inteiro da janela (14:35–14:42) |
| `Choreographer: Skipped N frames` na main durante o download (14:37:09–14:39:08) | 110 eventos, até 492 frames (base TASK-0006) | 1 evento (41 frames às 14:37:09.5, no start do serviço) |

O comportamento certo continua presente:
- O percentual sobe: a folha marcou 22% aos ~10 s (`fila-10s.png`) e 28% aos ~20 s (`fila-20s.png`). Depois do trace, a
  notificação estava em `Downloading… 38%` (`android.progress=38`, no canal `DOWNLOAD_CHANNEL_ID`).
- A cortina mostrou a notificação de progresso com a barra (`cortina-durante.png`).
- Cancelar pelo X (`cancelar.txt`, `cortina-5s-apos-cancelar.png`): toquei às 14:39:08.266. No logcat, `cancelled` às
  08.206, `queue empty, stopping self` às 08.438 e `destroyed, WakeLock released` às 08.552. Aos +1 s, +5 s e +15 s
  não havia notificação do pacote no canal de download nem serviço. O print aos 5 s mostra a cortina sem a
  notificação: ela não volta.

O ANR em si não se provoca sob demanda, porque depende da latência de IPC do `system_server`. O doc da demanda diz que
a prova é a remoção, da main, do Binder por tick, e essa remoção está provada acima.

## Regressão direcionada (o que as tasks tocaram)

- **Download pequeno até o fim** (`t3-download-pequeno.txt`): *Contra - The Alien Wars* (GB, 128 KB). Save às 14:40:11,
  `saved successfully` às 14:40:15.890, `stopping self` às 15.893 e `destroyed` às 16.462. Depois disso: 0 notificação
  de download e 0 serviço. O app mostrou o diálogo "Download Complete … Play now?".
- **Canal padrão no processo `:game`** (`t3-jogo-rodando.txt`, `t3-jogo-rodando.png`): Play no diálogo abriu o jogo (PID
  18585, tela da Konami renderizando). A notificação id=3 no `DEFAULT_CHANNEL_ID` dizia "Contra - The Alien Wars is
  running / Make sure to save your progress". A saída pelo Voltar terminou com `VM exiting with result code 0`.
- **Canal padrão no processo principal, depois do canal de download criado** (`t3-indexando.txt`): Ajustes → Rescan às
  14:41:47. O `LibraryIndexWork` entrou em foreground (`WM-SystemFgDispatcher: Started foreground service` às 47.422,
  com a notificação do `NotificationsManager` no canal padrão) e terminou `SUCCESS` às 47.839. Não houve `No Channel
  found`, `Bad notification` nem exceção. A notificação não chegou à cortina porque o trabalho durou 0,4 s, dentro do
  adiamento de exibição de FGS do Android 12+ (a observação está na TASK-0006). A versão visível dela já foi provada no
  T3 da TASK-0005, com scan mais longo.
- **Testes de unidade:** 116 / 0 / 1 pulado. A linha de base do manual é 54/0/1; a diferença vem dos testes novos das
  tasks, sem falha.

## Logs (T4)

`logcat-pid17805-18585.txt` está filtrado pelos dois PIDs do meu launch, sem as linhas de varredura do
`LemuroidLibrary`. Não há `FATAL`, `AndroidRuntime` de crash, `am_anr` nem `am_crash` (buffer de eventos vazio para
isso). Os únicos `E` são `OpenGLRenderer: Unable to match the desired swap behavior` (também aparece na base, nas
tasks) e os contadores `VIDEOFRAMES`/`EMUFPS` do libretrodroid, que são informativos e logados em nível E. Não há
erro vindo de `DownloadForegroundService` nem de `NotificationsManager`.

## Não testado

- Aparelho físico: o moto g86 não estava no adb.
- Android < 8: abaixo da API 26 não há canal, e a guarda liga a flag direto.
- Controle positivo na base pré-tasks: não refeito nesta Aceitação. Ele foi feito em cada task (TASK-0005: 9 → 0
  `createNotificationChannels`; TASK-0006: +724 → +25 enqueue, 46 → 0 `Shedding`), com o mesmo caminho e o mesmo
  aparelho.
