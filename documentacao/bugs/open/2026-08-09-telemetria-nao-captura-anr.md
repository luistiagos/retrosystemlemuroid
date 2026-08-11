# [BUG] Telemetria não capturou o ANR — o Lemuroid não reporta erro nenhum

**Data:** 2026-08-09
**Status:** Aberto 🔴
**Severidade:** Alta (cegueira total de produção)
**Branch:** version9

---

## Sintoma

O ANR de [2026-08-09-anr-inicializar-jogo-runongl-thread.md](2026-08-09-anr-inicializar-jogo-runongl-thread.md)
chegou por relato do cliente com print de tela. Nada apareceu no painel de erros.

## Causa-raiz

São **duas** causas independentes. Corrigir só a primeira não resolveria este caso.

### 1. Este projeto não tem telemetria — nenhuma

Varredura no repositório inteiro:

```
grep -rn "logErr|digitalstoregames|reportError|sendError|crashReport"  --include=*.kt   → 0 resultados
grep -rn "crashlytics|sentry|acra|firebase"                            --include=*.kt   → 0 resultados
```

Não existe cliente de `/logErr` do DigitalStoreGames, nem Crashlytics, nem Sentry, nem ACRA.
O tratamento de erro é **100% local**:

| Ponto | O que faz |
|---|---|
| [BaseGameActivity.setUpExceptionsHandler](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/BaseGameActivity.kt#L164-L173) | `Thread.setDefaultUncaughtExceptionHandler` → `Timber.e` + `finishAndExitProcess()` |
| [GameCrashActivity](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/gamecrash/GameCrashActivity.kt) | mostra a mensagem numa tela local |
| `Timber` | só logcat — não sai do device |

Ou seja: **nenhum erro deste app jamais chegou ao painel.** Não é uma falha específica de
ANR; é ausência de instrumentação. O agente `setup-telemetria-projeto` existe exatamente
para este caso e nunca foi rodado aqui.

### 2. ANR não é exceção — `UncaughtExceptionHandler` nunca dispara

Mesmo com a telemetria instalada da forma usual, **este erro continuaria invisível**.

Um ANR não lança `Throwable`: o processo está vivo e a main thread está parada dentro de
`CountDownLatch.await()`. Não há stack unwind, não há `uncaughtException()`, não há
`catch`. Quem mata o processo é o `ActivityManager`, de fora.

As únicas formas de um app capturar o próprio ANR:

- **`ActivityManager.getHistoricalProcessExitReasons()`** (API 30+), lido no **próximo**
  launch, filtrando `REASON_ANR`. O `ApplicationExitInfo` traz `getTraceInputStream()` com
  o dump das threads — exatamente o que provaria a main presa em `runOnGLThread`.
- **Watchdog de main thread**: thread dedicada que posta um ticket no `Looper` principal e
  verifica se foi respondido em N segundos; se não, captura `Looper.getMainLooper().thread.stackTrace`.

Nenhum dos dois existe no projeto (`grep ApplicationExitInfo|REASON_ANR|Watchdog` → 0).

### 3. Agravante — o ANR aconteceu no processo `:game`

`GameActivity`, `GameMenuActivity`, `GameService` e `TVGameActivity` rodam em
`android:process=":game"` ([AndroidManifest.xml](lemuroid-app/src/main/AndroidManifest.xml)).

Isso importa para o desenho da correção: um handler instalado só sob `isMainProcess()`
(padrão em [LemuroidApplication.onCreate](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/LemuroidApplication.kt#L55-L66),
que já bifurca `MainProcessInitializer` × `GameProcessInitializer`) **não cobriria o
processo onde o jogo — e o crash — de fato acontece**. A telemetria precisa ser instalada
nos dois processos, com o nome do processo no payload.

Detalhe adicional: `getHistoricalProcessExitReasons` é por `packageName`+`pid`, e retorna
registros de **todos** os processos do pacote — então a leitura pode ser feita no processo
main, no próximo launch, e ainda assim enxergar o ANR do `:game`. Esse é o caminho mais
barato.

## Correção (proposta — ainda não aplicada)

1. Rodar o `setup-telemetria-projeto` neste repositório: reporter para `/logErr`, kill-switch
   e pontos de captura.
2. Instalar o handler **nos dois processos** (main e `:game`), incluindo o nome do processo
   no payload.
3. Adicionar leitura de `getHistoricalProcessExitReasons()` no startup do processo main:
   varrer registros novos desde o último launch, e reportar os de `REASON_ANR`,
   `REASON_CRASH_NATIVE` e `REASON_LOW_MEMORY` com o `getTraceInputStream()` anexado.
   `REASON_CRASH_NATIVE` é especialmente relevante aqui — SIGSEGV em core libretro (ver
   histórico de Dreamcast/Flycast) também é invisível ao `UncaughtExceptionHandler`.
4. Encadear o handler existente do `BaseGameActivity` no reporter em vez de substituí-lo, e
   preservar o `finishAndExitProcess()` atual.

## Validação

Pendente. Roteiro:

- Forçar um ANR artificial (`Thread.sleep(10_000)` na main do `:game`), matar, reabrir o
  app e confirmar que o registro `REASON_ANR` chegou ao painel com o trace.
- Forçar uma exceção no `:game` e confirmar que o reporte sai com `process=":game"`.

## Lição

Duas regras que este caso deixa:

**Telemetria baseada só em `UncaughtExceptionHandler` é cega para as três falhas mais
comuns de um emulador**: ANR, crash nativo (SIGSEGV no core) e kill por LMK. Nenhuma das
três passa pelo handler de exceção da JVM. `ApplicationExitInfo` cobre as três de uma vez
e custa uma leitura no startup.

**Em app multi-processo, todo hook de erro precisa ser instalado por processo.** O padrão
`if (isMainProcess())` — legítimo para cache de imagem e WorkManager — é exatamente o
errado para captura de erro.
