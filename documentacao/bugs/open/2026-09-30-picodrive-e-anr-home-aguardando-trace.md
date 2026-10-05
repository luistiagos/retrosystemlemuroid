# [BUG] Picodrive SIGSEGV em `0x2200000` e ANR na home com `fstatat64`

**Data:** 2026-09-30
**Status:** 🟡 **Reaberto em 2026-10-05 (Item 1 reincidiu com o exato mesmo PC `0x136f74`)**.
Item 2 (ANR): segue resolvido (fix no código de stat fora da main thread).
Item 1 (Picodrive): reaberto devido ao novo crash com o mesmo endereço em Android 16 (ID 9209).
- **Complexidade:** alta — Análise de disassembly do binário Picodrive / investigação upstream.
**Severidade:** Alta (crash durante gameplay em core Mega Drive / Genesis)
**Branch:** version9
**Origem:** separado de [[2026-09-28-investigacoes-baixa-confianca-triagem]] (itens 2 e 4), cujo
item 1 foi resolvido.

> ⚠️ `system=/core=/game=` nesses relatórios **não** identifica a sessão que morreu: até a correção
> de 2026-09-30 o breadcrumb era lido na hora do scan, não do crash (ver o doc de origem).

---

## 1. Picodrive: SIGSEGV `SEGV_MAPERR` / `SEGV_ACCERR` em `0x136f74`

**Errors:**
- **8541:** Skyth S11, Android 13, `app=1.17.22`, `when=` 2026-09-24 20:43:25.
- **9209:** Samsung Galaxy S23 Ultra (SM-S918B), Android 16 (sdk 36), `app=1.17.22`, `when=` 2026-09-30 15:57:26, `pid: 525, tid: 630, name: GLThread 1`.

```
signal 11 (SIGSEGV), code SEGV_ACCERR, fault addr 0x732f7dc000
backtrace:
  #00  pc 0000000000136f74  .../lib/arm64/picodrive_libretro_android.so
  #01  pc 000000000006f21c  .../lib/arm64/picodrive_libretro_android.so
  #02  pc 000000000013f468  .../lib/arm64/picodrive_libretro_android.so (retro_run+888)
  #03  pc 00000000000b94e8  .../liblibretrodroid.so (libretrodroid::LibretroDroid::step()+112)
  #04  pc 00000000000b425c  .../liblibretrodroid.so (Java_com_swordfish_libretrodroid_LibretroDroid_step+60)
```

Apurado:

- `#02` é `retro_run+888` / `retro_run+0x378`: a falha é em gameplay, não na carga. `#00`/`#01` são funções internas
  sem símbolo (o `.so` é stripped; só 46 símbolos dinâmicos definidos).
- O caminho `files/cores/1.19.0/` era o core baixado pelo `CoreDownloader` porque, até o 1.17.22, o
  `.so` empacotado não tinha o prefixo `lib` e o instalador não o extraía (pitfall 14). **O binário
  é o mesmo** que o app empacota hoje: blob `f395e5c168c742c3e691210d1af6e5eb032ae192` em
  `1.19.0:…/picodrive_libretro_android.so` e em `1.20.0:…/libpicodrive_libretro_android.so`.
- **Reabertura confirmada (2026-10-05):** O erro **9209** em um Samsung Galaxy S23 Ultra (Android 16) repetiu o exato mesmo `#00 pc 0x136f74` e `#01 pc 0x06f21c` em `GLThread 1`. Trata-se de defeito determinístico em instruções internas do Picodrive ao rodar em `retro_run`.
- **Próximos passos:** Baixar o código fonte do Picodrive correspondente à tag e realizar o disassembly via `llvm-objdump -d --start-address=0x136f00` no `libpicodrive_libretro_android.so` para identificar a instrução com falha de acesso à memória. O erro 9209 foi fechado no painel da telemetria (rastreabilidade viva neste doc).

## 2. ANR na `MainActivity`, fora de jogo

**Errors:** 8621, 8623 — Samsung SM-A166M, Android 16, `app=1.17.22`, 2026-09-25 18:55:26 e
19:02:01, `Input dispatching timed out … Waited 10000ms for MotionEvent`, processo principal.

Apurado:

- O doc de origem registrou só o trace do 8621 (cabeçalho + `wchan` por thread, sem stack). O
  **8623 tem frame**: `file=libc.so`, `method=fstatat64+8` — o `CrashTelemetry` preenche esses
  campos com o primeiro `#NN pc` que encontra no texto do ANR, que normalmente é da thread `main`.
- Candidato no código: o toque num jogo faz `File(path).length()` — um `stat` — **na main thread**,
  dentro do handler de clique
  ([MainActivity.kt:310-323](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/main/MainActivity.kt#L310-L323));
  o mesmo `isGamePlaceholder` roda na composição do menu de contexto (`isGameDownloaded`, linha
  613). Um `stat` só leva 10 s com o armazenamento travado (FUSE/MediaProvider ocupado, I/O
  saturado), mas "10 s esperando `MotionEvent`" + `fstatat64` combina com exatamente esse caminho.

**Não corrigido de propósito:** sem o trace não há como saber se o `fstatat64` era da `main`, nem
de qual chamador.

**Próximo passo:** `triagem.py logs 8623`. Se a `main` estiver em
`UnixFileSystem.getLength` ← `MainActivity…isGamePlaceholder`, mover a checagem para
`Dispatchers.IO` no clique (e tirar da composição do menu de contexto).

### Análise de 2026-09-30 (sessão 2)

O trace continuou fora de alcance: `triagem.py logs 8541 8621 8623` sai com
`ERRO: nem JWT_SECRET_KEY nem TRIAGEM_TOKEN estao no ambiente`. Decidido corrigir o candidato do
item 2 mesmo assim — `stat` síncrono na main thread é defeito com ou sem este ANR, e o próprio
`GameLauncher` já faz a mesma checagem em `Dispatchers.IO`. **Isso não confirma a causa do ANR**:
se o trace do 8623 mostrar outro chamador, o bug reabre com esse chamador.

Símbolos abertos:

- `MainActivity.kt::isGamePlaceholder` (lambda local em `setContent`) — `Uri.parse` +
  `File(path).length() == 0L`. `File.length()` é `UnixFileSystem.getLength` → `stat()` →
  `fstatat64` no bionic, casando com o frame do 8623 (`File.exists()` seria `faccessat`, não casa).
  Chamado na main em 5 lugares: `onGameClick` (toque em qualquer jogo, todas as telas), composição
  do `MainGameContextActions` (`isGameDownloaded`, reavaliado a cada recomposição com o menu
  aberto), `onGamePlay`/`onGameRestart` do menu de contexto e `onVariantSelected` do
  `GameVariantsModal`.
- `DirectoriesManager::cachedRoms` → `SmartStoragePicker.getBestRomsDirectory` — escolhe o volume
  com mais espaço livre, ou seja, em aparelho com cartão SD as ROMs ficam no SD, que no Android 11+
  é servido por FUSE (MediaProvider). É ali que um `stat` pode esperar segundos.
- `GameInteractor::onGamePlay` (mobile) — construído **sem** `onPlaceholderGame`
  (`MainActivity.kt:996`), então no mobile não faz `stat`; o `isGamePlaceholder` privado dele só roda
  na TV.
- `GameLauncher::launchGameAsync` — `prepareGameForLaunch`, `isGameFileAvailable` (`exists()`) e
  `handleGameStart` já em `Dispatchers.IO`. Precedente do padrão aplicado aqui.

Hipótese descartada:

- **Item 1 sem fonte:** só existe o binário em `LibretroCores/lemuroid_core_picodrive`; não há
  checkout do Picodrive em disco para localizar `0x136f74`. Segue aguardando o trace.

Tasks:

1. `MainActivity`: `isGamePlaceholder` vira `suspend` em `Dispatchers.IO`; os quatro handlers
   (clique, play/restart do menu, variante) passam a resolver dentro de `lifecycleScope.launch`.
2. `MainActivity`: `isGameDownloaded` do menu de contexto sai da composição para um estado
   chaveado em (jogo selecionado, `downloadedGameKeys`); valor inicial = só a checagem em memória
   (`downloadedGameKeys`), o `stat` complementa em IO. (Planejado com `produceState`; trocado por
   `remember` chaveado — ver "Tropeço" abaixo.)
3. Fora do escopo, registrado aqui: a TV tem a mesma família — `GameInteractor.onGamePlay` (via
   `onPlaceholderGame`) e `GameContextMenuListener` (`isRomDownloaded`) fazem `stat` na main. Nenhum
   ANR de TV reportado; não mexido nesta correção.

Validação: `:lemuroid-app:compileFreeBundleDebugKotlin` + `ktlintCheck`; no aparelho, tocar em jogo
baixado (abre), em placeholder (diálogo de download), em jogo com variantes (modal), e abrir o menu
de contexto de um baixado (mostra "Excluir ROM") e de um placeholder (não mostra).

### Correção (item 2)

[MainActivity.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/main/MainActivity.kt):

- `isGamePlaceholder` saiu da lambda em `setContent` para `private suspend fun` da Activity, com o
  corpo em `withContext(Dispatchers.IO)`. Não existe mais versão síncrona para ninguém chamar da
  main por engano.
- `onGameClick`: o desvio para variantes continua síncrono (só lê `titlesWithVariants` em memória);
  o resto roda em `lifecycleScope.launch`. Idem para "Continuar"/"Reiniciar" do menu de contexto e
  para `onVariantSelected`. Activity destruída no meio do `stat` = coroutine cancelada, nada é
  lançado.
- `isGameDownloaded` do menu: `remember(jogo, downloadedGameKeys) { mutableStateOf(<só memória>) }`
  + `LaunchedEffect` que complementa com o `stat` em IO quando a memória diz "não baixado".

**Tropeço na implementação (registrado para não repetir):** a primeira versão usou
`produceState(initialValue, jogo, downloadedGameKeys)`. No aparelho, o placeholder mostrava
"Excluir ROM baixada". O `produceState` guarda o estado num `remember` **sem chave** — as chaves só
relançam o producer. Conferido no bytecode do compose-runtime 1.8.2 (`javap -c` em
`SnapshotStateKt__ProduceStateKt`, overload de 2 chaves: `rememberedValue` → `mutableStateOf` sem
nenhum `Composer.changed` antes). O valor ficava `true` desde a composição sem jogo selecionado, e o
producer (que só fazia o `stat` quando `!value`) nunca agia. Para estado que deve **reiniciar** com a
chave, `remember(chave) { mutableStateOf(...) }`.

A TV (task 3) segue com `stat` na main; não mexido.

**Baseline do ktlint:** o bloco do `MainActivity.kt` em `lemuroid-app/config/ktlint/baseline.xml`
foi renumerado à mão — o baseline é indexado por linha e as 15 entradas do passivo só deslocaram. A
entrada `612:36` (`multiline-expression-wrapping`) sumiu porque era o `isGameDownloaded = … ?.let`
removido. Nenhum apontamento novo congelado.

### Validação (item 2)

- `./gradlew :lemuroid-app:ktlintCheck :lemuroid-app:assembleFreeBundleDebug` — sem falha.
- SM-A127M (`RX8R90G1D6E`), APK `arm64-v8a` debug:
  - toque em jogo baixado (KOF '97, Recentes) → `START … GameActivity`, `Displayed +3s716ms`;
  - menu de contexto do baixado → "Continuar" abre o jogo; "Excluir ROM baixada" presente;
  - toque em placeholder (Plants Eat My Zombies, GB, pela busca) → diálogo "Save ROM?";
  - menu de contexto do placeholder → sem "Excluir ROM baixada"; e o baixado aberto **logo depois**
    volta a mostrar (valor não herda do jogo anterior);
  - Tetris (FBNeo, 4 variantes) → modal; variante `atetris` não baixada → diálogo "Save ROM?".
- **Não reproduzido:** o ANR em si exige FUSE travado. A confirmação da causa vem do trace (abaixo).

### Trace (sessão 3) — causa confirmada

`triagem.py logs 8541 8621 8623` (segredo injetado só no processo, conforme a skill). **8623**,
thread `main` (`sysTid=22446`, `state=S`):

```
native: #00 pc 0010e108  libc.so (fstatat64+8)
native: #01 pc 0002aee0  libjavacore.so (doStat+292)
at libcore.io.Linux.stat(Native method)
…
at java.io.UnixFileSystem.getLength(UnixFileSystem.java:354)
at java.io.File.length(File.java:984)
at com.swordfish.lemuroid.app.mobile.feature.main.MainActivity$a.L(SourceFile:37)
at com.swordfish.lemuroid.app.mobile.feature.main.MainActivity$a.t(SourceFile:1)
at com.swordfish.lemuroid.app.mobile.feature.main.c.invoke(SourceFile:1)
at com.swordfish.lemuroid.app.mobile.feature.main.MainActivity$a.c0(SourceFile:10)
at com.swordfish.lemuroid.app.mobile.feature.main.z.invoke(SourceFile:1)
…
at androidx.compose.foundation.f$f.a(SourceFile:15)          ← clickable
…
at androidx.compose.ui.platform.AndroidComposeView.dispatchTouchEvent(SourceFile:64)
…
at android.app.Dialog.dispatchTouchEvent(Dialog.java:1406)
```

- É exatamente o `File.length()` da lambda `isGamePlaceholder` dentro do `setContent` (classe
  ofuscada `MainActivity$a`), chamado de um `clickable`.
- O toque entrou por uma janela de **`Dialog`**: o clique foi dentro de um `ModalBottomSheet` —
  "Continuar"/"Reiniciar" do `MainGameContextActions` ou a escolha no `GameVariantsModal`. Os dois
  caminhos estão na correção acima.
- **8621:** sem stack (`tombstoned reported failure`), só `Waiting Channels`: a `main`
  (`sysTid=14610` = pid) em **`lookup_slow`** — resolução de caminho no kernel, o mesmo `stat`
  preso. Compatível.
- `CriticalEventLog` dos dois relatórios mostra **três** ANRs idênticos no mesmo SM-A166M em ~20 min
  (pids 14610, 21435, 22446): o armazenamento daquele aparelho travava de forma recorrente, e cada
  toque num jogo pelo menu virava ANR.

Fechamento na telemetria: `triagem.py close 8541 8621 8623` → `affected=0` (já estavam fechados pela
sessão de origem); `verify` → `ainda abertos: 0`.

---

## Lição

1. **`stat` na main thread não é barato neste app.** As ROMs moram onde o `SmartStoragePicker`
   achar mais espaço — em aparelho com SD, atrás de FUSE. Um `File.length()`/`exists()` num handler
   de clique pode ficar preso o tempo que o MediaProvider quiser. Toda checagem de arquivo de ROM vai
   para `Dispatchers.IO`, como no `GameLauncher`. A TV ainda tem a mesma família (task 3 acima).
2. **`produceState` com chaves não reinicia o valor** — só relança o producer. Estado que tem que
   voltar ao inicial quando a chave muda é `remember(chave) { mutableStateOf(...) }`.
3. **ANR sem stack não é beco sem saída:** o `Waiting Channels` (`lookup_slow` na `main`) já
   apontava para I/O de caminho, e o `CriticalEventLog` mostra se é reincidente no aparelho.
4. **Frame único no cabeçalho do ANR (`file=`/`method=` do `CrashTelemetry`) é pista, não prova** —
   aqui bateu, mas só o trace completo mostrou o chamador e a janela (`Dialog`) de onde veio o toque.
