# [BUG] Telemetria não capturou o ANR — o Lemuroid não reporta erro nenhum

**Data:** 2026-08-09
**Status:** Corrigido ✅ — telemetria instalada e validada em aparelho (moto g86) nos dois caminhos
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

## Correção (aplicada em 2026-08-13)

Implementada à mão neste repositório (o agente `setup-telemetria-projeto` não chegou a ser
usado). Quatro arquivos novos em `lemuroid-app/.../app/shared/telemetry/`:

| Arquivo | Papel |
|---|---|
| `TelemetryReporter.kt` | Transporte para `/logErr`, projeto `retrogamesystem/<componente>`. Dedup por sessão, kill-switch, fallback GET, e **join limitado a 2,5 s** no caminho terminal. |
| `CrashTelemetry.kt` | Instala o `UncaughtExceptionHandler` **por processo** e varre `getHistoricalProcessExitReasons()` no startup (`REASON_CRASH_NATIVE`, `REASON_ANR`, `REASON_LOW_MEMORY`, `REASON_CRASH`), com watermark para não reprocessar. |
| `TelemetryContext.kt` | Breadcrumb `system/core/game` persistido em disco no início do jogo. |
| `TombstoneParser.kt` | Decodificador do protobuf `Tombstone` (ver abaixo). |

Pontos de integração:

- `LemuroidApplication.onCreate` — handler instalado nos **dois** processos (`main` e `game`);
  a varredura de saídas roda só no main (a API é por pacote, então enxerga o `:game`).
- `BaseGameActivity.setUpExceptionsHandler` — ele **substitui** o handler do processo, então o
  reporte foi adicionado ali também; o comportamento anterior (tela de erro, `finishAndExitProcess`)
  é preservado intacto.
- `BaseGameActivity.onCreate` grava o breadcrumb; `performSuccessfulActivityFinish` o limpa.

### O breadcrumb é o que torna o reporte acionável

Um crash nativo só é recuperado no **launch seguinte**, quando o processo que sabia o que estava
rodando já morreu — e o tombstone raramente nomeia o core. Por isso `system/core/game` é gravado em
disco no início do jogo. É a diferença entre "SIGSEGV em algum lugar" e
`system=saturn; core=yabasanshiro; game=Mortal Kombat II`.

### Armadilha: em Android 12+ o tombstone é protobuf, não texto

`getTraceInputStream()` para `REASON_CRASH_NATIVE` devolve a mensagem **`Tombstone` serializada em
protobuf**. Lê-la como UTF-8 produz 100–300 KB de lixo binário por evento — foi exatamente o que
aconteceu no app irmão ARMSX2 (52 crashes ilegíveis). O primeiro teste aqui reproduziu o mesmo
defeito (registro **1129** no painel, mensagem binária).

`TombstoneParser.kt` decodifica o wire format com um leitor mínimo (sem dependência nova), extraindo
signal, abort message, causas e o backtrace da thread que crashou. Se o parse falhar, envia-se um
resumo curto — **nunca** os bytes crus. O trace de ANR continua sendo texto e segue pelo caminho
antigo, escolhido por heurística de proporção de caracteres imprimíveis.

## Validação (moto g86 5G, Android 16)

| Caminho | Como foi forçado | Resultado no painel |
|---|---|---|
| Crash nativo no `:game` | `run-as … kill -11 <pid>` com Saturn/MK II rodando | **id 1130** — `retrogamesystem/native`, `signal 11 (SIGSEGV), code SI_USER`, backtrace simbolizado, contexto com `process=…:game` e `system=saturn; core=yabasanshiro; game=Mortal Kombat II` |
| Exceção Java no `:game` | `am crash <pid>` com Tomb Raider rodando | **id 1131** — `retrogamesystem/game`, `RemoteServiceException$CrashedByAdbException`, `thread=main`, mesmo breadcrumb |
| Contrato do endpoint | POST direto do PC | HTTP 200 `ok` |

> Os registros 1129 (lixo binário, pré-decoder), 1130 e 1131 são **testes** e podem ser apagados do
> painel.

**Não validado empiricamente:** `REASON_ANR` e `REASON_LOW_MEMORY`. Ambos usam o mesmo caminho de
código já exercitado pelo crash nativo (varredura + trace de texto), mas não foram forçados.

## Lição

Três regras que este caso deixa:

**Telemetria baseada só em `UncaughtExceptionHandler` é cega para as três falhas mais comuns de um
emulador**: ANR, crash nativo (SIGSEGV no core) e kill por LMK. Nenhuma passa pelo handler da JVM.
`ApplicationExitInfo` cobre as três e custa uma leitura no startup.

**Em app multi-processo, todo hook de erro precisa ser instalado por processo** — e é preciso
conferir quem mais chama `setDefaultUncaughtExceptionHandler`, porque o último a chamar vence. Aqui
a `BaseGameActivity` sobrescrevia o handler do `Application` justamente no processo do emulador.

**Reporte de crash não pode bloquear o caminho de crash.** O POST síncrono da implementação de
referência congelaria o app por até 10 s antes da tela de erro; a versão daqui despacha numa thread
e faz `join` com teto de 2,5 s.
