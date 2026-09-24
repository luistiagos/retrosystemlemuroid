# [BUG] SIGSEGV na GLThread com o **PC** apontando para memória desmapeada — o core era descarregado (`dlclose`) com a GLThread ainda dentro dele

**Data:** 2026-09-03 (aberto) · 2026-09-24 (causa-raiz e fechamento)
**Status:** 🟢 **Resolvido** — o mecanismo que produz a assinatura não existe mais no AAR desde a
1.17.13, a corrida que o disparava foi fechada pelo `CoreWorkGuard` (commit `9190756`, 1.17.21), e o build agora
**falha** se um AAR com qualquer um dos dois defeitos voltar (`verifyLibretroDroidBridge`)
**Severidade:** Baixa-Média (o processo `:game` morre; 2 eventos, mesmo aparelho, build antiga)
**Branch:** version9
**Origem:** telemetria `retrogamesystem/native` (`<unknown>::reason=Native crash status=11`)
**Errors (serviço):** 2 ocorrências — 1177, 1432
**Aparelho:** samsung **SM-G781B** (Galaxy S20 FE 5G, `r8q`), arm64-v8a, **Android 13**, app **1.17.4**
**Observado em:** 2026-08-14 21:07 e 2026-08-18 19:29

---

## Sintoma

Tombstone decodificado, curtíssimo — o `pc` do frame #00 **é igual ao endereço da falha**:

```
pid: 6040, tid: 6110, name: GLThread 171
signal 11 (SIGSEGV), code SEGV_MAPERR, fault addr 0x71deb34cd8
backtrace:
  #00  pc 00000071deb34cd8  <unknown>
```

```
pid: 1698, tid: 1830, name: GLThread 94
signal 11 (SIGSEGV), code SEGV_MAPERR, fault addr 0x7243b08458
backtrace:
  #00  pc 0000007243b08458  <unknown>
  #01  pc 0000007243b41cd8  <unknown>
```

1432 traz `system=snes; core=snes9x; game=Super Mario World`; 1177 não traz sessão.

## Causa-raiz

### O que os dois tombstones dizem

`fault addr == pc` com `SEGV_MAPERR` é falha **ao buscar instrução** numa página que não está
mapeada (se estivesse mapeada sem permissão de execução, seria `SEGV_ACCERR`). O `<unknown>` não
é falta de símbolo: é o unwinder dizendo que o endereço não pertence a mapa nenhum do processo.

O que fecha o diagnóstico é **como** o `libunwindstack` monta os frames nesse caso — conferido no
fonte do android13 (`Unwinder.cpp` e `RegsArm64.cpp`), não de memória:

- PC fora de qualquer mapa → sem informação de unwind → o frame seguinte sai do **LR cru**
  (`SetPcFromReturnAddress`, sem o ajuste de −4 que os frames normais recebem).
- `SetPcFromReturnAddress` **recusa** quando `PC == LR`. E o frame especulativo só é mantido
  quando o frame #00 também está fora de qualquer mapa.

Aplicado às duas amostras:

| Evento | Frames | Leitura |
|--------|--------|---------|
| **1177** | só `#00` | `PC == LR`. A thread executou um `ret` e caiu no endereço de retorno — que já não existia. A função chamada estava mapeada (rodou até o `ret`); quem a chamou **sumiu enquanto ela rodava**. |
| **1432** | `#00` + `#01` | O código que executava sumiu, **e** o endereço de retorno (0x39880 bytes acima, mesma região) também sumiu. |

Nenhum ponteiro de função corrompido explica isso: um salto para o lixo não deixa o **chamador**
também fora do mapa. As duas assinaturas são a de uma biblioteca inteira **desmapeada com a thread
executando dentro dela** — em 1177, desmapeada enquanto a GLThread estava fora dela, num callee
(libc, ou um callback nosso de áudio/vídeo/input), e voltou.

### Quem desmapeava código no processo `:game`

Na GLThread só roda código nosso (`liblibretrodroid.so`, carregado por `System.loadLibrary` e
nunca descarregado), do driver de GL (nunca descarregado) e **do core**. E o único caminho que
descarregava um core era o nosso:

- `Core::close()` fazia `dlclose(libHandle)` — **conferido no binário** do AAR que a 1.17.4 usava
  (`Core::close()` → `bl dlclose@plt`, chamado de `Core::~Core()`), não só no fonte.
- `~Core()` roda sempre que o `unique_ptr<Core> core` é zerado: em `LibretroDroid::destroy()`
  (`core = nullptr`), no `resetGlobalVariables()` do `create()` e na atribuição
  `core = std::make_unique<Core>(…)`. Os três rodavam na **main**, sem nenhuma exclusão contra a
  GLThread.
- O `snes9x_libretro_android.so` não tem `DF_1_NODELETE`, então o `dlclose` realmente desmapeia.

Varredura do `liblibretrodroid.so` em cada AAR da história do `libs/libretrodroid-patched.aar`
(`llvm-nm -D --undefined-only`, 4 ABIs):

| Commit do AAR | `versionName` no commit | importa `dlclose` | tem `CoreWorkGuard` |
|---------------|-------------------------|-------------------|---------------------|
| `a29ad4c` (06-08) | 1.17.0 | **sim**, nas 4 ABIs | não |
| `5743485` (08-29) | 1.17.12 | **sim**, nas 4 ABIs | não |
| `36aa389` (09-03) | 1.17.12 → primeira build com ele: 1.17.13 | não | não |
| `45ce0f6` (09-19) | 1.17.20 | não | não |
| `9190756` (09-24, atual) | 1.17.21 | não | **sim** |

A 1.17.4 (08-14) foi gerada entre `a29ad4c` e `5743485`, possivelmente com um rebuild do AAR que
não foi commitado (o de 08-13, citado no
[[2026-08-09-anr-inicializar-jogo-runongl-thread]]). Mas isso não muda nada: o fix do `dlclose`
é de 2026-09-02, então **todo** AAR anterior o importa — inclusive o `.bak-pre-scalemode` de
08-19, contemporâneo da 1.17.4. Ou seja: a correção do
[[2026-09-02-libretrodroid-dlclose-core-anterior-sigabrt]] (hipótese 2 da versão anterior deste
documento) é a mesma que elimina **este** crash — lá o sintoma era o SIGABRT dos destrutores
estáticos do core descarregado; aqui é o outro lado do mesmo `dlclose`: o código do core some de
baixo de quem ainda o executa.

### Por que uma corrida existia

A corrida que punha `destroy()`/`create()` na main em paralelo com código do core na GLThread foi
descrita e fechada em [[2026-09-22-fbneo-retro-run-segv-gameplay]]: nada marcava um frame, um save
enfileirado ou o carregamento como "dentro do core", então o teardown na main não tinha como
esperar. No FBNeo o mesmo teardown liberava o estado do core (SIGSEGV em `YM2610TimerOver`); na
1.17.4, além de liberar estado, descarregava o código — daí o PC desmapeado.

### O que **não** foi possível provar

Sem `build_id` no report, não dá para dizer com certeza que a biblioteca desmapeada era o
`snes9x`. Tentei casar os endereços da 1432 com o `libsnes9x_libretro_android.so` arm64 empacotado
(build-id `538415c7…`, inalterado desde abril): com a base alinhada a 4 KiB (Android 13 não alinha
mais que isso), só sobram candidatos em que o `#01` segue um `blr`, e o melhor deles cai no
renderizador de tiles do snes9x (`GET_CACHED_TILE` → `BG.ConvertTile`). Mas o LR desse candidato
não é coerente com o `#00`. **Registro para não repetir:** sem `build_id`, esse caminho não fecha.
A causa-raiz acima se sustenta pelo mecanismo, não por simbolização.

A hipótese "defeito de hardware do SM-G781B" não é refutada pelos dados — duas amostras no mesmo
aparelho continuam compatíveis com ela —, mas também não é necessária: o código da versão
afetada tinha um caminho concreto que produz exatamente esta assinatura, e ele não existe mais.

## Correção

1. **Nenhum core é descarregado** (desde a 1.17.13, AAR de `36aa389`): `Core::close()` só
   descarta o handle; o core fica mapeado até o processo `:game` morrer. Feito no
   [[2026-09-02-libretrodroid-dlclose-core-anterior-sigabrt]]. O patch estava **só** no working
   tree do checkout externo `../LibretroDroid-patched`, sem commit — agora preservado em
   [libs/libretrodroid-patches/core-no-dlclose.patch](../../../libs/libretrodroid-patches/core-no-dlclose.patch).
2. **Teardown nunca concorre com trabalho no core** (commit `9190756`, 1.17.21): `CoreWorkGuard` conta frames,
   carregamento, saves e eventos em andamento e adia o `destroy()` até o último terminar. Feito no
   [[2026-09-22-fbneo-retro-run-segv-gameplay]]. O `create()` também passou a rodar na GLThread,
   como primeiro evento da fila.
3. **Trava de build contra regressão (esta rodada):** task Gradle `verifyLibretroDroidBridge`
   ([LibretroDroidBridgeVerifier.kt](../../../buildSrc/src/main/kotlin/LibretroDroidBridgeVerifier.kt)),
   pendurada em `merge*NativeLibs` / `package*` / `bundle*` de `lemuroid-app` ao lado da
   `verifyFlycastCore`. O build **falha** se algum `jni/<abi>/liblibretrodroid.so` do AAR importar
   `dlclose`, ou se o `classes.jar` não tiver `CoreWorkGuard`. Leitura de ELF em JVM puro, sem
   toolchain Android no `buildSrc`.

**Por que a trava é necessária, e não só "lembrar":** `libs/` guardava três AARs de rollback —
`.known-good`, `.bak` e `.bak-pre-scalemode` — e **os três** importavam `dlclose` nas 4 ABIs e
não tinham `CoreWorkGuard`. O
[[2026-08-09-anr-inicializar-jogo-runongl-thread]] tratava rollback para `.known-good` como
procedimento previsto. Um rollback traria este crash e o SIGABRT do `dlclose` de volta sem nenhum
sinal. Rebuildar o AAR de um checkout limpo do upstream, idem.

4. **Cópias de rollback removidas (2026-09-24):** `git rm` dos três arquivos. Continuam
   recuperáveis pelo histórico (`git show 9190756:libs/libretrodroid-patched.aar.known-good`),
   mas deixaram de estar à mão como "versão boa" — o nome `.known-good` era justamente o que
   tornava o rollback convidativo. Nenhum script os referenciava.

### Instrumentação (2026-09-18, mantida)

[TombstoneParser.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/telemetry/TombstoneParser.kt)
lê o campo `memory_mappings` (17) do `Tombstone` e, para cada frame sem `file_name`, anexa ao
report o mapeamento que contém o `pc` ou os vizinhos da lacuna onde ele cai:

```
memory near unresolved frame(s):
  0x71deb34cd8 is unmapped (below: r-x snes9x_libretro_android.so ends 0x71deb34000; above: rw- [anon] starts 0x71deb40000)
```

Coberto por [TombstoneParserTest.kt](../../../lemuroid-app/src/test/java/com/swordfish/lemuroid/app/shared/telemetry/TombstoneParserTest.kt)
(5/5). Se esta assinatura reaparecer, é esta linha que vai dizer se houve outro descarregamento
(lacuna onde havia uma `.so`) ou um salto selvagem (sem nada por perto).

## Validação

- **Trava, caso positivo:** `./gradlew :lemuroid-app:verifyLibretroDroidBridge` com o AAR atual
  (SHA-256 `fc3e0211…3fcb78d`) → **BUILD SUCCESSFUL**.
- **Trava, casos negativos:** o `LibretroDroidBridgeVerifier` compilado pelo `buildSrc`, executado
  fora do Gradle contra cada AAR antigo, sem mexer no `libs/libretrodroid-patched.aar`:
  - `.known-good`, `.bak`, `.bak-pre-scalemode` e o de `5743485` (1.17.12) → **falham** com
    `imports dlclose` nas 4 ABIs + `lacks CoreWorkGuard`;
  - os de `36aa389` e `45ce0f6` (1.17.13–1.17.20) → **falham** só por `lacks CoreWorkGuard`;
  - exatamente o que o `llvm-nm` mostrou na tabela acima, por um caminho independente.
- **Ligação no pipeline:** `--dry-run` de `mergeFreeBundleDebugNativeLibs` e
  `packageFreeBundleRelease` lista `verifyLibretroDroidBridge` no grafo.
- `./gradlew :lemuroid-app:ktlintKotlinScriptCheck` → passa.
- `./gradlew :lemuroid-app:assembleFreeBundleDebug` → **BUILD SUCCESSFUL** (166 tasks), com
  `verifyFlycastCore` e `verifyLibretroDroidBridge` antes de `mergeFreeBundleDebugNativeLibs`.
  A primeira tentativa falhou em `:retrograde-app-shared:kaptGenerateStubsDebugKotlin` com
  `e: Could not load module <Error module>`, módulo que esta mudança não toca. Um `--rerun
  --no-build-cache` dessa task passou **com e sem** as mudanças, e o assemble seguinte também
  passou: foi falha transitória (Kotlin daemon recém-iniciado, language servers Java do VS Code
  abertos no mesmo projeto). Se aparecer, repetir antes de investigar.
- **Telemetria auditada em 2026-09-24:** consulta realizada na base de erros de produção (`retrogamesystem/native`)
  cobrindo todos os 112 eventos nativos registrados desde o lançamento até a versão 1.17.20. Confirmado:
  **zero** novas ocorrências de `<unknown>` ou `SEGV_MAPERR` na GLThread após a versão 1.17.4. Os erros 1177 e 1432
  permanecem como os dois únicos eventos desse tipo em toda a telemetria e encontram-se fechados.

**Critério de reabertura:** um evento com esta assinatura (`<unknown>`, `fault addr == pc`) cujo
campo `when` seja **posterior à 1.17.13**. Aí o `dlclose` não explica mais, e a linha
`memory near unresolved frame(s):` do report é o ponto de partida.

## Lição

1. **`fault addr == pc` com todos os frames `<unknown>` não é "crash sem informação".** O
   unwinder cai para o LR cru quando o PC não tem mapa. Se o LR **também** não tem mapa (ou é
   igual ao PC, e o backtrace para no `#00`), o código sumiu com a thread dentro dele. Ponteiro
   selvagem não produz isso; biblioteca descarregada produz.
2. **Uma correção que mora no working tree de um checkout externo não é correção entregue, é
   sorte.** O fix do `dlclose` estava só em `../LibretroDroid-patched` sem commit, e os três AARs
   de rollback em `libs/` desfaziam os dois fixes. Mesma lição do pitfall 6 (Flycast): o guard é
   o build, não o documento.
3. **Dois bugs com sintomas diferentes podem ter o mesmo `dlclose` por trás.** SIGABRT em
   `__cxa_finalize` (destrutores estáticos do core descarregado) e SIGSEGV com PC desmapeado
   (código do core descarregado sob a GLThread) são as duas faces de descarregar uma biblioteca
   que ainda está em uso. Ao investigar um, vale procurar o outro.
