---
tipo: implementacao
demandas: [2026-09-28-lowmemory-gameplay-ppsspp-foreground-service]
depende_de: [TASK-0007]
---
# lowmemory: DownloadForegroundService e Workers em foreground gravam o marcador de serviço ativo

Demanda: `docs/bugs/aberto/2026-09-28-lowmemory-gameplay-ppsspp-foreground-service/` (seção "Planejamento").
Depende de `lowmemory-fgs-leitura` (usa `TelemetryContext.markForegroundWork`/`clearForegroundWork`).

## Objetivo

Todo caminho que põe o processo principal em `FOREGROUND_SERVICE` marca a sua tag enquanto está em primeiro
plano e desmarca ao sair, para o próximo `lowmemory`/ANR do processo principal chegar com `fgs=<tag>`.

## O que mudar (abrir cada função inteira antes: retorno e caminho de erro)

- `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/DownloadForegroundService.kt`: tag
  `download`. Marcar em `onStartCommand` só depois do `ServiceCompat.startForeground` que deu certo (o ramo
  `isForegroundServiceStartNotAllowed` NÃO marca); desmarcar em `onDestroy` e onde o serviço sai do foreground
  (`stopForegroundCompat`, `onTimeout`). Atenção: este arquivo está reservado também por outras demandas desta
  execução (`2026-10-05-anr-downloadforegroundservice-...`, `2026-11-19-retomar-download-pausado-...`): faça o
  `sync` e trabalhe sobre o que elas integraram.
- `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/utils/android/WorkManagerUtils.kt`: um helper único
  para os Workers (ex.: `suspend fun <T> CoroutineWorker.inForeground(tag, info, block: suspend () -> T): T`
  ou par marcar/desmarcar) que chama `setForeground` com o `try/catch` que os workers já têm, marca só se o
  `setForeground` não lançou, e desmarca num `finally` (cobre `CancellationException`). Não pode mudar o
  comportamento atual de "continua sem notificação se `setForeground` falhar".
- Workers, cada um com sua tag: `CoreUpdateWork` (`core`), `LibraryIndexWork` (`library`), `SaveSyncWork`
  (`savesync`), `RomsDownloadWork` (`romsdownload`), `StreamingRomsWork` (`streaming`). Caminhos:
  `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/library/{CoreUpdateWork,LibraryIndexWork}.kt`,
  `.../shared/roms/{RomsDownloadWork,StreamingRomsWork}.kt`, `.../shared/savesync/SaveSyncWork.kt`.
- Nada roda no `:game`; nenhuma API acima do `minSdk` nova (`Process.myPid` é API 1).

## Teste que prova

- JVM: se o helper do `WorkManagerUtils` tiver lógica testável (marca só com sucesso, desmarca no `finally`),
  teste com lambdas falsas em `lemuroid-app/src/test/...`; senão, justificar no commit.
- E2E (aparelho do slot): Home ▸ jogo PSX/Dreamcast não baixado ▸ confirmar download; durante o download
  `adb shell run-as app.retrogamesystem.debug cat shared_prefs/<TelemetryReporter.PREFS_NAME>.xml` mostra a tag
  `download` e o pid de `adb shell pidof app.retrogamesystem.debug`; ao concluir (ou cancelar na fila) a tag
  some. Ajustes ▸ reescanear biblioteca → tag `library` durante o `LibraryIndexWork`, some ao terminar.

## Plano de teste

- Controle positivo: APK da base (`19cacda`), mesmo download: o arquivo de prefs não tem marcador.
- T3: download conclui e o "jogar agora?" aparece; pausar/retomar; reescanear biblioteca termina; nenhum crash
  ou ANR no `logcat` do PID; `ktlintCheck` sem apontamento novo nos arquivos tocados; `lintFreeBundleDebug`
  passa.

## Analise do dev (pipeline 20261119-2228, 2026-11-20)

Simbolos abertos (base `1ba98c9`, ja com TASK-0005 e TASK-0007 integradas):

- `TelemetryContext::markForegroundWork`/`clearForegroundWork` (TASK-0007) — `synchronized`, `apply()` nas
  `telemetry_prefs`, `catch Throwable`: nunca lancam. Conjunto por pid; tag nao pode ter `+`.
- `DownloadForegroundService::onStartCommand` — `ServiceCompat.startForeground` num `try`; o `catch` so engole
  `isForegroundServiceStartNotAllowed` (resto relanca). Pontos de saida do foreground: `observeQueue` (fila vazia →
  `stopForegroundCompat()` + `stopSelf()`), `onTimeout` (API 35+, `stopForegroundCompat()` + `stopSelfResult`) e
  `onDestroy`. Todos na main (o `scope` e `Dispatchers.Main`).
- Workers, caminho de erro do `setForeground` HOJE:
  - `CoreUpdateWork::doWork`, `LibraryIndexWork::doWork`, `SaveSyncWork::displayNotification`:
    `try { setForeground } catch (e: Exception) { Timber.w }` → seguem sem notificacao.
  - `RomsDownloadWork::doWork`, `StreamingRomsWork::doWork`: `setForeground` DENTRO do `try` geral → falha cai no
    `catch Throwable` → `Result.failure` (ou `CancellationException` se o job ja nao esta ativo). **Nao** seguem sem
    notificacao. O helper tem de preservar os dois comportamentos (tarefa diz: nao mudar comportamento) →
    parametro `onPromotionFailed: (Exception) -> Unit` (engole e loga, ou relanca).
  - `SaveSyncWork`: o `setForeground` esta em `displayNotification()`, separado do `sync`: o bloco marcado passa a
    envolver `displayNotification + sync`.
- Processo: `LemuroidApplication.workManagerConfiguration` nao define `defaultProcessName`; no bytecode do
  `work-runtime-2.9.0` (`ProcessUtils.isDefaultProcess`) o default e `applicationInfo.processName` = processo
  principal. `SystemForegroundService` e `DownloadForegroundService` sem `android:process`. `CoreUpdateBroadcastReceiver`
  sem `android:process`. Logo todo marcador e gravado pelo pid do principal (o que `foregroundWorkOf` espera).
- `CoroutineWorker.setForeground(ForegroundInfo)` e `public final suspend` (javap) → chamavel de extensao.

Decisoes:

- **Contagem por tag no helper dos Workers** (`ForegroundTagCounter`, em memoria, por processo): `CoreUpdateWork`
  urgente (`CORE_UPDATE_URGENT_WORK_ID`, REPLACE) e o de fundo (`CORE_UPDATE_WORK_ID`) rodam ao mesmo tempo com a
  mesma tag `core`; sem contagem, o que termina primeiro apagaria o marcador do outro. Marcar/desmarcar acontece
  dentro do lock da contagem (senao `release`→`acquire` concorrentes podiam terminar com o marcador apagado e o
  trabalho rodando). Processo morto zera a contagem junto com o pid — nada fica preso.
- Tags num lugar so (`ForegroundWorkTag` em `WorkManagerUtils.kt`): `download`, `core`, `library`, `savesync`,
  `romsdownload`, `streaming`.
- Servico: marca so depois do `startForeground` que deu certo; desmarca dentro de `stopForegroundCompat()` (cobre fila
  vazia e `onTimeout`) e em `onDestroy`. O ramo `isForegroundServiceStartNotAllowed` nao marca nem desmarca (se uma
  promocao anterior valeu, o servico segue em foreground ate o `onDestroy`).
- Custo na main do servico: um `apply()` de prefs pequenas no inicio e no fim do download (o `waitToFinish` do
  `ActivityThread` apos `onStartCommand`/`onDestroy` espera essa escrita). Mesmo padrao do `setGameSession`; mover
  para outra thread nao evita a espera (o `apply` cai antes do `waitToFinish` do mesmo jeito). Aceito.

Hipoteses descartadas:

- Worker rodando no `:game` gravaria pid errado e zeraria o conjunto do principal — descartada pelo bytecode acima.
- Um helper que sempre engole a falha do `setForeground` — mudaria `RomsDownloadWork`/`StreamingRomsWork` de
  "falha" para "segue sem notificacao"; fora do escopo.

Teste: `lemuroid-app/src/test/java/com/swordfish/lemuroid/app/utils/android/RunMarkedInForegroundTest.kt` sobre a
funcao pura `runMarkedInForeground` (lambdas falsas): sucesso marca antes e desmarca depois; falha engolida nao
marca e roda o bloco; falha relancada nao roda o bloco; bloco que lanca (inclusive `CancellationException`)
desmarca; dois donos da mesma tag marcam uma vez e desmarcam so no ultimo.

## Correcao (pipeline 20261119-2228, rodada 1)

Commit `68eff36`.

- `WorkManagerUtils.kt`:
  - `ForegroundWorkTag` — `download`, `core`, `library`, `savesync`, `romsdownload`, `streaming` (nenhuma com `+`).
  - `CoroutineWorker::runInForeground(tag, foregroundInfo, onPromotionFailed, block)` → `runMarkedInForeground`
    (internal, sem `Context`): `setForeground`; se lancou `Exception`, `onPromotionFailed(e)` decide (logar e rodar o
    bloco sem marcador, ou relancar sem rodar o bloco); se deu certo, `ForegroundTagCounter.acquire` (marca no 0→1),
    bloco, `release` no `finally` (desmarca no 1→0; cobre excecao e `CancellationException`).
  - `ForegroundTagCounter` — contagem por tag, `@Synchronized`, mark/clear dentro do lock.
- `CoreUpdateWork::doWork` → `runInForeground(CORE, ..., loga)` em volta do novo `downloadCores()` (o mesmo
  `try/catch` de antes, devolve `hadFailure`). `LibraryIndexWork::doWork` → `LIBRARY`, em volta do
  `withContext(IO) { runCatching { indexLibrary() } }`. `SaveSyncWork::doWork` → `SAVE_SYNC`, em volta do novo
  `syncSaves()`; `displayNotification()` saiu (foi para dentro do `doWork`). Os tres seguem sem notificacao quando o
  `setForeground` falha, com a mesma mensagem de log.
- `RomsDownloadWork::doWork` (`ROMS_DOWNLOAD`) e `StreamingRomsWork::doWork` (`STREAMING`): `onPromotionFailed =
  { throw it }` — a falha do `setForeground` continua indo para o `catch` geral (`Result.failure`), como antes.
- `DownloadForegroundService`: `onStartCommand` marca `DOWNLOAD` logo depois do `ServiceCompat.startForeground` que
  voltou (o ramo `isForegroundServiceStartNotAllowed` nao marca); `stopForegroundCompat()` desmarca (fila vazia e
  `onTimeout`); `onDestroy` desmarca de novo (idempotente).
- ktlint: as linhas andaram e o passivo de `CoreUpdateWork`, `RomsDownloadWork`, `StreamingRomsWork` reapareceu
  (baseline casa por linha). Corrigido (2 imports nao usados, `if` multilinha, linha em branco no inicio da classe,
  chaves no `if`, trailing comma, assinatura de `enqueue`) e as 3 entradas sairam de
  `lemuroid-app/config/ktlint/baseline.xml` (claim). `gradlew :lemuroid-app:ktlintCheck`: `ktlintMainSourceSetCheck` e
  `ktlintTestSourceSetCheck` verdes; so o `ktlintKotlinScriptCheck` falha, no `lemuroid-app/build.gradle.kts`
  (vermelho conhecido da base, manual secao Dev).
- Teste novo: `lemuroid-app/src/test/java/com/swordfish/lemuroid/app/utils/android/RunMarkedInForegroundTest.kt`
  (7 casos; claim).
- `lintFreeBundleDebug` nao rodado: a mudanca nao chama API de framework nova (so `TelemetryContext`, ja integrado, e
  `setForeground` do WorkManager que ja era chamado).

Ajustes ao Plano de teste:

- As chaves sao `telemetry_fgs_pid` (int) e `telemetry_fgs_tags` (string) em `shared_prefs/telemetry_prefs.xml`.
  "A tag some" = `telemetry_fgs_tags` fica vazio (`<string name="telemetry_fgs_tags"></string>`); o pid fica.
- O download de jogo pela Home passa pelo `DownloadForegroundService` (tag `download`). Na base (`1ba98c9`, ja com a
  TASK-0007) as chaves `telemetry_fgs_*` nao aparecem: e o controle positivo.
- Reescanear biblioteca: `LibraryIndexWork` → `library`; pode durar pouco, entao ler o arquivo logo apos o toque (ou
  em laco curto de `run-as cat`).

## Estado

- Feito (commit `68eff36` + este): codigo, teste e ktlint. `build --bug TASK-0008 --emulador` → BUILD SUCCESSFUL
  (4m53s); testes 123 / 0 falhas / 1 pulado (linha de base 54/0/1 sem falha nova); `RunMarkedInForegroundTest` 7/7.
  APKs arm64-v8a, armeabi-v7a e x86_64 na lane.
- Falta: teste E2E (sessao de teste): download pela Home com `telemetry_fgs_tags=download` durante e vazio depois;
  reescanear biblioteca com `library`; controle positivo na base.
- Proximo passo: `enqueue-test --bug TASK-0008`.

## Teste

### Teste rodada 1 — aprovado (pipeline 20261119-2228, 2026-11-20)

Ambiente: `emulator-5554` = `lemu_3ds_api34` (Android 14, x86_64), escolhido pelo `aparelho-instalar` (o moto g86 nao
estava no adb). `sync`: base `origin/version10` = `e3e2d95` (so docs sobre `1ba98c9`, com TASK-0005/0007 integradas);
branch `085f74c`. Evidencia em `../../evidencias/TASK-0008/` (APKs fora do git por `*.apk`).

**Offline:** a diff toca so arquivos reservados e implementa o Planejamento (helper `runInForeground` /
`runMarkedInForeground` / `ForegroundTagCounter` + `ForegroundWorkTag`; 5 Workers com a tag de cada um; servico marca
depois do `startForeground` que voltou e desmarca em `stopForegroundCompat`/`onDestroy`; `RomsDownloadWork`/
`StreamingRomsWork` relancam a falha do `setForeground` como antes). Testes de unidade do `build`: 123 / 0 falhas /
1 pulado (linha de base 54/0/1, sem falha nova); `RunMarkedInForegroundTest` 7/7. `gradlew :lemuroid-app:ktlintCheck`:
`ktlintMainSourceSetCheck` e `ktlintTestSourceSetCheck` verdes; so `ktlintKotlinScriptCheck` vermelho no
`lemuroid-app/build.gradle.kts` (vermelho conhecido da base, manual secao Dev).

**Binarios.** Base: `git checkout --detach e3e2d95` + `build --bug TASK-0008 --emulador` -> `base-e3e2d95.apk` (x86_64)
sha256 `682073b3…032177`, igual ao `base.apk` instalado (`lastUpdateTime 2026-11-20 14:53:16`). Fix: `build --emulador`
em `085f74c` (BUILD SUCCESSFUL) -> `fix-085f74c.apk` sha256 `2443bfbd…a0da558`, igual ao `base.apk` instalado
(`lastUpdateTime 2026-11-20 14:59:01`). `versionName 1.17.36-DEBUG` nos dois.

**Caminho (o mesmo nas duas versoes, pela UI):** Inicio -> Busca -> "tekken 3" -> *Tekken 3 Unlocked Edition Traduzido
PT-BR* (PSX, nao baixado) -> **Save** -> download correndo na fila -> `run-as ... cat shared_prefs/telemetry_prefs.xml`
a cada ~5 s.

| | Base `e3e2d95` (PID 18991) | Fix `085f74c` (PID 19559) |
|---|---|---|
| `DownloadForegroundService` (`dumpsys activity services`) | `isForeground=true` (`base-servico.txt`) | `isForeground=true` (`fix-servico.txt`) |
| `telemetry_prefs.xml` durante o download | **sem** `telemetry_fgs_*` (4 amostras, `base-prefs-durante.txt`) | `telemetry_fgs_tags=download`, `telemetry_fgs_pid=19559` = `pidof` (4 amostras, `fix-prefs-durante.txt`) |
| fila | `base-fila-baixando.png` (5%) | `fix-fila-baixando.png` |

- **T2: aprovado.** Sinal do sintoma (processo em `FOREGROUND_SERVICE` sem marcador do trabalho) presente na base,
  ausente no fix; marcador correto (`download` + pid do processo principal) presente.
- **T3 (aparelho):**
  - Cancelar ativo (`fix-t3-cancelar-ativo.txt`): antes do X `download`/`isForeground=true`; 3 s depois do X
    `telemetry_fgs_tags` vazio, servico fora.
  - Download ate o fim (`fix-t3-download-completo.txt`, `fix-t3-jogar-agora.png`): *Adventure Island II* (GB) baixou em
    ~14 s, dialogo "Download Complete ... Play now?" apareceu; marcador vazio no fim. (O download pequeno foi rapido
    demais para a amostragem pegar `download` no meio; a marcacao em si esta provada pelo Tekken.)
  - Pausar/retomar (`fix-t3-pausar-retomar.txt`): pausar -> servico sai, `telemetry_fgs_tags` vazio, item "Paused";
    retomar -> item volta a "Saving… 15%", **servico nao volta** (e o marcador continua vazio, coerente). Isso e o bug ja
    registrado `2026-11-19-retomar-download-pausado-sem-foreground-service` (resumeActive nao chama
    `DownloadForegroundService.start`), nao desta mudanca: o marcador reflete corretamente a ausencia do FGS.
  - Ajustes ▸ ROMs ▸ Rescan (`fix-t3-reescanear.txt`, laco de 0,5 s no aparelho): `telemetry_fgs_tags=library` com
    `SystemForegroundService` ativo, depois vazio quando o servico do WorkManager sai; reescaneamento terminou.
  - Testes de unidade e ktlint: acima (offline). `gradlew :lemuroid-app:lintFreeBundleDebug` na lane: BUILD SUCCESSFUL
    (7m26s), sem achado novo fora do baseline.
- **T4** (`fix-logcat-pid19559.txt`, `base-logcat-pid18991.txt`, filtrados pelo PID): nenhum `AndroidRuntime`/`FATAL`/
  `ANR`; buffer `crash` vazio. Unicos `E/`: `OpenGLRenderer: Unable to match the desired swap behavior` (tambem na
  base). `WM-SystemFgDispatcher` Started/Stopping foreground service normais.
