# [BUG] Low-memory kills em primeiro plano — PPSSPP (mesmo aparelho, 2x) + serviço de foreground sem sessão de jogo

**Data:** 2026-09-28
**Status:** 🟡 Corrigido no código (salvar ao sair da tela + escrita atômica + memória na
telemetria), validado no emulador; falta 1 conferência de log (ver "Validação"). Continuação do "próximo passo" deixado pendente em
[[2026-09-02-telemetria-lowmemory-e-eco-de-crash]] ("Reabrir a análise dos low-memory de
primeiro plano como bug próprio de consumo de memória").
**Severidade:** Média (mata a sessão sem aviso ao usuário; não é crash visível, é o processo
sumindo)
- **Complexidade:** media — Gerenciamento de memória e retenção em background de workers de download/sync.
**Branch:** version9
**Origem:** telemetria `retrogamesystem/lowmemory` — todos já filtrados por
`isLowMemoryWorthReporting` (`importance <= IMPORTANCE_VISIBLE`), ou seja, **não** são os
1.297 kills de processo em cache já descartados naquele bug; são o resíduo que o filtro
deliberadamente deixa passar.

---

## Sintoma

Sete `Killed by low memory` com `importance` 100 (`IMPORTANCE_FOREGROUND`) ou 125
(`IMPORTANCE_FOREGROUND_SERVICE`) — o processo morreu com algo visível ou um serviço de
primeiro plano rodando, não em cache. Três padrões distintos, sem stack (a API do Android não
fornece uma para `REASON_LOW_MEMORY`, só o contexto):

### A. PPSSPP, mesmo aparelho, duas sessões

| Error | Quando | Device | Jogo | `importance` |
|---|---|---|---|---|
| 8652 | 2026-09-25 18:20 | Samsung SM-A055M | FIFA 06 | 125 (`:game`) |
| 8681 | 2026-09-26 09:35 | Samsung SM-A055M | FIFA 10 - World Class Soccer | 125 (`:game`) |

Mesmo device, duas sessões ~15h de intervalo, dois jogos diferentes do mesmo core. Sinal mais
forte deste grupo: não é um pico isolado, é o mesmo aparelho repetindo.

### B. Serviço de foreground morto sem sessão de jogo

| Error | Quando | Device | `importance` |
|---|---|---|---|
| 8568 | 2026-09-25 10:02 | Samsung SM-A055M (mesmo device do grupo A) | 125, processo **principal**, não `:game` |
| 8736 | 2026-09-27 00:43 | motorola moto g35 5G | 125, processo principal |
| 8737 | 2026-09-27 02:08 | motorola moto g35 5G (mesmo device, ~1h25 depois) | 125, processo principal |
| 9166 | 2026-09-29 22:45 | motorola moto g35 5G (mesmo device, 3ª vez) | 125, processo principal (`app=1.17.22`) |
| 9178 | 2026-09-30 04:45 | samsung SM-A105M | 125, processo principal (`app=1.17.22`) |

Sem `system=`/`core=`/`game=` no breadcrumb — não havia jogo rodando. `importance=125`
(`FOREGROUND_SERVICE`) no processo principal sem jogo bate com um **worker do WorkManager em
foreground** (download de ROM, sincronização de biblioteca — os mesmos que motivaram
[[2026-09-18-anr-workmanager-short-service-timeout]]), não com o emulador. Padrão diferente do
grupo A: aqui é o download/sync que fica pesado o bastante para o LMK matar, não o core.

### C. SNES/snes9x, ocorrência isolada

| Error | Quando | Device | Jogo | `importance` |
|---|---|---|---|---|
| 8542 | 2026-09-25 06:27 | motorola moto g15 | Donkey Kong Country I | 100 (`:game`) |

snes9x é um core leve — não se encaixa na leitura "core pesado" do grupo A. Ponto único, sem
padrão; listado para não perder o sinal caso se repita no mesmo device ou no mesmo jogo.

### D. N64/mupen64plus, TV box, versão antiga

| Error | Quando | Device | `importance` |
|---|---|---|---|
| 8755 | 2026-09-27 10:27 | TCL Smart TV (armeabi-v7a, Android 11) | 100, `:game`, `app=1.17.9` |

Único ponto de dado, versão bem mais antiga que os demais (1.17.9 vs. 1.17.22) — TV box de
recursos limitados por natureza (categoria já coberta por `HeavySystemFilter` para outros
sistemas, mas N64 só é bloqueado no tier `ULTRA_WEAK`, ≤1 GB).

## Causa-raiz

**Não determinada — não há stack para `REASON_LOW_MEMORY`.** O que dá para afirmar:

- O grupo A (PPSSPP) é o mais acionável: dois eventos, mesmo aparelho, mesmo core, ambos
  `:game` em primeiro plano. Consistente com o padrão já registrado no doc de origem ("3ds/citra
  e gc/dolphin dominam a lista") — PPSSPP entra para a mesma categoria de cores pesados que
  pressionam memória o bastante para o LMK agir mesmo com o app em primeiro plano.
- O grupo B não é sobre emulação — é sobre o worker de download/sync ocupando memória (ou
  concorrendo com outros apps) o suficiente para o processo principal ser reclamado enquanto um
  `ForegroundInfo` está ativo. Vale checar se há relação com o mesmo timeout de
  `SHORT_SERVICE`/`DATA_SYNC` desta triagem, mas por causas diferentes (um é timeout de 3 min,
  este é memória).
- Sem o RAM total dos três aparelhos (não disponível via telemetria), não dá para dizer se
  algum deveria ter sido coberto pelo `HeavySystemFilter` e não foi — isso exigiria consultar a
  especificação de cada modelo, fora do escopo desta triagem.

## Análise (2026-09-30)

### O que `importance=125` no `:game` significa

`125` é `IMPORTANCE_FOREGROUND_SERVICE`. Com a `GameActivity` na tela o processo é `TOP` →
`importance=100` (é o que os grupos C e D mostram). `125` no `:game` só acontece com a activity
**fora da tela** — Home, troca de app ou tela bloqueada — e o `GameService` segurando o processo.
Portanto o grupo A **não** é "o PPSSPP estourou a memória jogando": é o jogador que saiu do jogo
sem fechar, abriu outra coisa, e o LMK recolheu o maior processo perceptível — o emulador de PSP.
Isso o app não impede (e não deve: é o sistema funcionando). O que o app decide é **o que se perde**
quando acontece.

### O que se perdia — o defeito de fato

Símbolos abertos:

- `GameViewModelSaves::saveOnExit` — grava SRAM e depois autosave. **Único** caminho que persiste a
  sessão; em erro nunca lança (devolve `false`).
- `BaseGameScreenViewModel::requestFinish` — único chamador de `saveOnExit` (saída pelo menu).
  O ViewModel é `DefaultLifecycleObserver` registrado em `BaseGameActivity.onCreate`
  (`lifecycle.addObserver(baseGameScreenViewModel)`), mas **não implementava `onStop`**.
- `GLRetroView.RenderLifecycleObserver` (LibretroDroid-patched) — no `ON_PAUSE` enfileira
  `LibretroDroid.pause()` e pausa o `GLSurfaceView`. `GLSurfaceView.GLThread.guardedRun` (cópia
  patchada, linha ~662) drena `mEventQueue` **antes** de checar pausa, e `preserveEGLContextOnPause
  = true` → `runOnGLThread` (serializeSRAM/serializeState) funciona com o jogo pausado. É o mesmo
  estado em que o `saveOnExit` já roda hoje (o `GameMenuActivity` fica por cima).
- Tema `LemuroidMaterialTheme.Menu` e `LemuroidLeanbackPreferencesTheme` têm
  `windowIsTranslucent=true` → abrir o menu do jogo só **pausa** a `GameActivity`, nunca a para.
  `ON_STOP` = o jogo saiu da tela de verdade.
- Todo encerramento do `BaseGameActivity` passa por `finish()` (`finishAndExitProcess`,
  `restartInFreshProcess`) → no `onStop` desses casos `isFinishing == true`.

Conclusão: ir para segundo plano **não gravava nada**. Um kill do LMK nesse estado (grupo A)
perdia tudo desde a última saída pelo menu — inclusive saves feitos dentro do jogo (SRAM), que só
iam para o disco em `saveOnExit`.

Agravante encontrado ao abrir a escrita:

- `SavesManager::setSaveRAM` → `File.writeBytes` (trunca e escreve) e
  `StatesManager::writeStateToDisk` → `FileKt::writeBytesCompressed` (idem). Kill no meio deixa
  `.srm` truncado — e `getSaveRAM` aceita qualquer arquivo com `length() > 0`, entregando SRAM
  parcial ao core. O autosave truncado é descartado sem crash (`readBytesUncompressed` lança
  `EOFException`, `getSaveState` faz `getOrNull`), mas o anterior já foi sobrescrito. Gravar
  justamente na entrada em segundo plano — quando a pressão de memória sobe — aumenta a exposição.
- `SavesCoherencyEngine::shouldDiscardAutoSaveState` — descarta o autosave se a SRAM for >30 s mais
  nova. Ordem SRAM → autosave (a mesma do `saveOnExit`) mantém o autosave válido.

### Hipóteses descartadas

- **ROM carregada em memória pelo app** — não: `GameLoader.load` passa caminho/VFS
  (`getGameFiles(..., useVFS)`), não bytes.
- **Config pesada do PPSSPP por padrão** — não: `GameSystem` PSP só define
  `ppsspp_frame_duplication`; resolução interna e texture scaling ficam no default do core (1x/off).
- **`File.calculateMd5` lendo arquivo inteiro** — só chamado pelo save sync, sobre saves (pequenos).
- **Download de ROM bufferizado em memória (grupo B)** — `RomOnDemandManager` escreve em arquivo;
  os `response.body?.string()` do módulo são respostas de lookup (URL/JSON), não ROMs.
- **Serviço de foreground vazando sem trabalho (grupo B)** — `DownloadForegroundService` para
  sozinho quando não há item `QUEUED`/`SAVING`; a fila é persistida (`save_queue`) e volta a
  `QUEUED` no próximo start. Kill aqui interrompe o download, não perde dado.

### O que a telemetria não conta (e deveria)

`CrashTelemetry.reportOneExit` manda `process/pid/importance/when/device`, mas não o tamanho do
processo morto nem a RAM do aparelho — justamente o que decide se "fomos nós que pesamos" ou "o
aparelho estava sem memória". `ApplicationExitInfo.getPss()/getRss()` (API 30, mesma do scan) dão o
último PSS/RSS conhecido, e `ActivityManager.MemoryInfo.totalMem` dá a RAM total.

## Correção — tasks

1. **Persistir a sessão ao sair da tela** — `BaseGameScreenViewModel.onStop`: se a activity não
   está `isFinishing`, dispara `GameViewModelSaves.saveOnBackground` (SRAM → autosave, nunca
   lança). `saveOnExit` e `saveOnBackground` serializados por `Mutex` para não escreverem os mesmos
   arquivos ao mesmo tempo (Home durante a gravação de saída).
2. **Escrita atômica** — `FileKt`: `File.writeAtomically` (temp no mesmo diretório + `renameTo`),
   usado por `writeBytesCompressed`, `SavesManager.setSaveRAM` e o metadata do `StatesManager`.
   Teste: `WriteAtomicallyTest` (conteúdo substituído; falha no meio preserva o arquivo antigo e
   não deixa temporário).
3. **Telemetria de low-memory** — `CrashTelemetry.reportOneExit` acrescenta `pss`, `rss` e `ram`
   ao contexto dos exits `REASON_LOW_MEMORY`. Teste: formatação em `CrashTelemetryMemoryTest`.

## Validação (2026-09-30)

- `./gradlew :lemuroid-app:testFreeBundleDebugUnitTest --tests "*WriteAtomicallyTest"
  --tests "*CrashTelemetryMemoryTest" --tests "*GameSessionForExitTest"`: 10 testes, 0 falhas,
  1 pulado (`replacesAnExistingSave` — `renameTo` do JVM no Windows não sobrescreve; coberto no
  emulador abaixo).
- `ktlintCheck` em `lemuroid-app`, `retrograde-util`, `retrograde-app-shared`: limpo, baseline
  intocado (a assinatura de `reportOneExit` foi corrigida, não congelada).
- AVD `lemu_api25_2gb`, APK `-PdevAbi=x86_64`, `kof97` (FBNeo), saves em `/sdcard/Android/data/…`:
  1. 3 créditos inseridos → Home → logcat `Stored sram file with size: 0` +
     `Stored autosave file with size: 415155`; `kof97.zip.state` passou de `03:20` para `18:48`,
     sem `.tmp` na pasta (rename sobre arquivo existente funciona no sdcardfs).
  2. `run-as … kill -9 <pid do :game>` (simula o LMK) → reabrir o jogo pela Home → volta com
     **`CREDITS 03`**: a sessão foi restaurada do autosave gravado no `ON_STOP`.
  3. **Pendente:** conferir no logcat que a saída por Back/menu (`requestFinish`) grava uma vez só
     (o `onStop` com `isFinishing` não deve gravar de novo). A saída aconteceu; a leitura do log
     não, porque o ambiente da sessão perdeu a capacidade de abrir processos. Comando:
     `adb -s emulator-5554 logcat -d | grep GameViewModelSaves | tail` — esperado **um** par
     `Stored sram`/`Stored autosave` após o último Home.

## Próximos passos

- [ ] Conferir o item 3 da validação; se ok, mover este doc para `bugs/done`.
- [ ] Grupos C/D (kill **com o jogo na tela**, importance 100) não são cobertos pelo `ON_STOP`:
      avaliar flush periódico de SRAM só quando o conteúdo mudar (protegeria também contra
      SIGSEGV de core). Cuidado com `SavesCoherencyEngine`: SRAM >30 s mais nova descarta o
      autosave — gravar só quando muda preserva a semântica.
- [ ] Com `pss`/`rss`/`ram` chegando nos próximos `lowmemory`, reavaliar se algum processo nosso
      é desproporcional à RAM do aparelho (entrada para `HeavySystemFilter`).
- [ ] Grupo A: se mais eventos de PPSSPP com `importance` baixo aparecerem, considerar se
      PPSSPP deveria entrar no `HeavySystemFilter` em tiers mais baixos (hoje só PSP inteiro
      está coberto pelo filtro em `WEAK`/`ULTRA_WEAK`; confirmar se o SM-A055M cai em algum
      tier ou se está passando sem filtro por ter RAM acima do corte).
- [ ] Grupo B: correlacionar com o timeout de `SHORT_SERVICE` desta mesma triagem
      ([[2026-09-18-anr-workmanager-short-service-timeout]]) — mesmo componente
      (`SystemForegroundService`), falhas diferentes (timeout vs. memória); podem ser sintomas
      do mesmo download pesado.
- [ ] Sem stack disponível pela API, não há mais profundidade possível só com telemetria —
      qualquer avanço real precisa de reprodução em device com profiler de memória.
