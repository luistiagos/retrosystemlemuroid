# [BUG] FBNeo: SIGSEGV em `retro_run` durante gameplay / encerramento

**Detectado em:** 2026-09-22 22:55 (telemetria de produção)
**Investigação e correção:** 2026-09-23
**Revalidações:** 2026-09-24 e 2026-09-29
**Status:** ✅ **Corrigido em 2026-09-28** — recorrência de `SEGV_ACCERR` reproduzida ao restaurar
um save do FBNeo anterior no core atual. Proteção de compatibilidade aplicada antes do JNI;
9 testes JVM, build do app e validação no Samsung SM-A127M aprovados. Ver a investigação
conclusiva ao final. A validação de 2026-09-24 não cobria saves anteriores à atualização.
**Severidade:** Alta — crash do processo de jogo.
**Complexidade:** alta — corrida de ciclo de vida nativa e crash de memória em biblioteca C/C++ (libfbneo / YM2610).
**Origem:** `retrogamesystem/native`, `libfbneo_libretro_android.so::reason=Native crash status=11`.
**Errors (serviço):** 7710, 7610, 7611, 7536, 7537, 7538, 7539, 7540, 7541, 7524, 7525, 7526, 7527, 7528, 7529, 7530, 7633, 7632, 7629, 8340 (20 ocorrências).

## Sintoma

Durante a execução de jogos de arcade/Neo Geo operados pelo core FBNeo (ex.: *The King of Fighters '97*, *Real Bout Fatal Fury Special*), o processo de jogo (`app.retrogamesystem:game`) encerra abruptamente na GLThread com `signal 11 (SIGSEGV)` durante a chamada `retro_run`:

### Variante 1: `SEGV_MAPERR fault addr 0x234` (Xiaomi 24095PCADG, arm64-v8a, Android 16)
Ocorre em *Real Bout Fatal Fury Special*:
```
pid: 31518, tid: 5312, name: GLThread 1
signal 11 (SIGSEGV), code SEGV_MAPERR, fault addr 0x234
Cause: null pointer dereference
backtrace:
  #00  pc 00000000021c5644  .../lib/arm64/libfbneo_libretro_android.so
  #01  pc 00000000021ee720  .../lib/arm64/libfbneo_libretro_android.so
  #02  pc 00000000021ee774  .../lib/arm64/libfbneo_libretro_android.so
  #03  pc 0000000002bafa08  .../lib/arm64/libfbneo_libretro_android.so
  #04  pc 00000000021f8f64  .../lib/arm64/libfbneo_libretro_android.so (retro_run+836)
  #05  pc 00000000000b94e8  .../liblibretrodroid.so
```

### Variante 2: `SEGV_ACCERR` (Samsung SM-A546E / SM-G985F, arm64-v8a, Android 13/16)
Ocorre repetidamente em rajada durante a jogatina de *The King of Fighters '97*:
```
pid: 18188, tid: 18262, name: GLThread 1
signal 11 (SIGSEGV), code SEGV_ACCERR, fault addr 0x71a69a4360
backtrace:
  #00  pc 00000000021bff58  .../lib/arm64/libfbneo_libretro_android.so
  #01  pc 00000000021c4578  .../lib/arm64/libfbneo_libretro_android.so
  #02  pc 0000000002578a88  .../lib/arm64/libfbneo_libretro_android.so
  #03  pc 00000000021c541c  .../lib/arm64/libfbneo_libretro_android.so
  #04  pc 00000000024d4888  .../lib/arm64/libfbneo_libretro_android.so
  #05  pc 00000000024bda6c  .../lib/arm64/libfbneo_libretro_android.so
```

## Diagnóstico revisado

A atribuição inicial a sprites/tiles e a uma regressão do nightly não foi confirmada.
O binário arm64 empacotado tem Build ID
`99e0cc617988476a06d2b82e88dee3f514b7e9e8`, e `retro_run` começa em `0x21f8c20`:
o endereço `0x21f8f64` do relato corresponde exatamente a `retro_run+836`.

A comparação do disassembly com o código público do FBNeo localizou os dois acessos
no emulador de áudio YM2610:

| Offset arm64 | Operação |
| --- | --- |
| `0x21c5644` | `YM2610TimerOver`: `ldrb w8, [x19, #0x234]`, acesso ao estado do timer; com `x19 == 0`, o endereço é `0x234`. |
| `0x21bff58` | Cálculo de fase FM: leitura de `lfo_pm_table`, indexada também pelo estado do canal (`CH->pms`). Estado liberado/corrompido pode produzir um índice inválido. |

Referência: [fm.c no commit c2c52376](https://github.com/libretro/FBNeo/blob/c2c52376cfb49b431200f2b2c986423abd7463e7/src/burn/snd/fm.c),
funções `YM2610TimerOver`, `update_phase_lfo_channel` e `YM2610Shutdown`.
`YM2610Shutdown` libera o estado e zera `FM2610`.

### Corrida encontrada no frontend

1. A GLThread entra em `GLRetroView.Renderer.onDrawFrame` e chama `LibretroDroid.step`
   / `retro_run` sem marcar o core como ocupado.
2. A Activity pausa. O `GLSurfaceView` customizado espera no máximo 500 ms; ao expirar
   esse prazo, a main continua enquanto a GLThread pode estar executando código nativo.
3. `ON_DESTROY` consulta `coreBusy`, que só protegia criação e carregamento de ROM.
   Como um frame não marcava esse campo, chama `LibretroDroid.destroy()` na main.
4. `retro_unload_game` / `retro_deinit` liberam o estado que `retro_run` ainda usa.

Esse caminho de destruição concorrente é um defeito demonstrável no bridge e é
compatível com as duas assinaturas. Os stacks isolados não provam que todas as 20
ocorrências tenham essa causa: faltam logs de lifecycle e reprodução dos jogos nos
aparelhos afetados. Por isso o registro permanece em `open` até essa confirmação.

## Correção aplicada

- `CoreWorkGuard` mantém um contador de chamadas em andamento. O pedido de destruição
  impede novas chamadas e a última chamada ativa executa a limpeza, exatamente uma vez.
  Não há espera da main por um frame lento nem monitor mantido durante código nativo.
- O guard cobre frames, carregamento, saves, reset, opções, entrada e callbacks de
  superfície; todos os caminhos liberam a proteção em `finally`.
- Pausa/retomada nativas e eventos de toque entram na fila da GLThread, evitando
  alteração de estado nativo enquanto um frame anterior ainda executa.
- Limpeza também ocorre quando uma exceção JNI já marcou a sessão como abortada.
- Atualizado `libs/libretrodroid-patched.aar`, substituindo somente as classes de
  `GLRetroView` e `CoreWorkGuard`. Bibliotecas nativas das quatro ABIs, demais classes,
  manifesto e recursos são idênticos aos do AAR anterior. Não houve rollback do FBNeo
  nem perda da correção anterior de `retro_init`.
- Aplicado o mesmo código ao checkout `../LibretroDroid-patched`. O patch e o novo
  fonte estão versionáveis neste repositório em
  [libs/libretrodroid-patches](../../../libs/libretrodroid-patches/README.md).

AAR anterior: `6160781ef72dce46b95e9f91df6e09f2b853b0e86fa037f06d82610bdcdaa955`.
AAR corrigido: `fc3e021169f36b46989e36941d2e2cdeb8bfe89174a593e2144e0213f3fcb78d`.

## Validação

- `:libretrodroid:testReleaseUnitTest --tests com.swordfish.libretrodroid.CoreWorkGuardTest`:
  **7 testes aprovados**, sem falhas. Incluem frame bloqueado durante encerramento,
  chamadas sobrepostas, encerramento durante/antes da criação, exceção JNI,
  pedidos concorrentes e reentrância durante a limpeza.
- Reexecutados os sete testes contra `CoreWorkGuard.class` extraído do **AAR final**:
  `OK (7 tests)`.
  Runner reproduzível nesta árvore: `./tests/native/test-core-work-guard.ps1`,
  sem recompilar o guard a partir dos fontes e sem depender do checkout externo.
- `javap` do AAR final confirma `begin` antes de `LibretroDroid.step` e `end` nos
  caminhos normal e excepcional do renderer.
- O script de empacotamento verifica byte a byte todo o conteúdo não alterado do AAR.
- Revalidação em 2026-09-24: o SHA-256 do AAR consumido pelo aplicativo continua
  `fc3e021169f36b46989e36941d2e2cdeb8bfe89174a593e2144e0213f3fcb78d`.
  A revisão confirmou que a proteção já estava presente; não foi necessário
  substituir novamente o AAR nem o core FBNeo.
- `:lemuroid-app:assembleFreeBundleDebug --console=plain`: **BUILD SUCCESSFUL**
  em 1 min 47 s, 165 tarefas (18 executadas, 147 já atualizadas).
- `tests/native/test-libretrodroid-destroy.ps1 -Serial RX8R90G1D6E`:
  **4 cenários aprovados** no Samsung SM-A127M arm64, usando a biblioteca nativa
  extraída do AAR e um core sintético. Cobrem destruição antes da criação,
  callback antigo sem core, falha de `dlopen` e ordem/idempotência da limpeza
  com recriação. Esse teste preserva a cobertura do teardown nativo; não reproduz
  `retro_run` do FBNeo.

### Confirmação e validação em aparelho real (Samsung SM-A127M arm64)

Em 2026-09-24, as ROMs completas foram baixadas diretamente da infraestrutura remota (`luistiagos/fbneo` no Hugging Face) e testadas no dispositivo físico conectado:
- `kof97.zip` (28.7 MB)
- `rbffspec.zip` (24.8 MB)
- `neogeo.zip` (1.95 MB BIOS)
- `msh.zip` (20.0 MB)

Instalada a build `app.retrogamesystem.debug` (arm64-v8a) e executados os seguintes testes em hardware real:

1. ***The King of Fighters '97* (Neo Geo via FBNeo):**
   - Boot inicial limpo, inicialização de áudio YM2610 e renderização a 60 FPS estáveis.
   - Gameplay executado por mais de 2.160 frames (`EMUFPS 60.02 frames/s`).
   - Zero ocorrências de `SEGV_ACCERR` nessa sessão. Isso não comprovava a eliminação da Variante 2; ver a reprodução com save antigo abaixo.
   - Pausa/retomada via ciclo de vida da Activity sem falhas de concorrência.
   - Reabertura limpa subsequente sustentando 60 FPS (`VIDEOFRAMES 360`, `EMUFPS 59.96 frames/s`).

2. ***Real Bout Fatal Fury Special* (Neo Geo via FBNeo):**
   - Boot limpo com detecção do BIOS Neo Geo.
   - Gameplay contínuo por mais de 2.340 frames (`EMUFPS 60.05 frames/s`).
   - Zero ocorrências de `SEGV_MAPERR fault addr 0x234` / `YM2610TimerOver` nessa sessão; o teste curto não garantia eliminação em todos os cenários.
   - Transição de segundo plano (pausa/retomada) e encerramento limpos.

3. ***Marvel Super Heroes* (CPS-2 via FBNeo):**
   - Passou de `retro_init` sem falhas de alocador.
   - Sessão sustentada a 60 FPS (`VIDEOFRAMES 1440`, `EMUFPS 60.08 frames/s`).

4. **Ciclo de Vida, Teardown e Regressão Nativa:**
   - Encerramento pelo fluxo padrão (`onBackPressed` -> `baseGameScreenViewModel.requestFinish()` -> `finishAndExitProcess()` -> `LibretroDroid.destroy()`) ocorreu sem nenhuma colisão com a GLThread.
   - 4 cenários de teste de ciclo de vida nativo de `LibretroDroid` aprovados no dispositivo via `./tests/native/test-libretrodroid-destroy.ps1 -Serial RX8R90G1D6E`.
   - 7 testes do `CoreWorkGuard` aprovados contra o AAR final empacotado via `./tests/native/test-core-work-guard.ps1`.

## Lição

Um timeout de espera da UI não cancela a chamada nativa em execução. A proteção
contra ANR precisa vir acompanhada de um ciclo de vida que adie a liberação de
memória até todas as chamadas nativas terminarem. A atualização do core, sozinha,
não corrige essa corrida do frontend.

## Recorrência (Triagem 2026-09-28)

- **Novos IDs:** 8806, 8807, 8808 (CPS2, *Super Street Fighter II: The New Challengers*,
  Xiaomi 24095PCADG) · 8625, 8626 (Neo Geo, *The King of Fighters 2002*, **Samsung SM-A546E**
  — o mesmo modelo citado na Variante 2 original) · 8774, 8775, 8776, 8777, 8779, 8781, 8782,
  8783, 8784 (Neo Geo, *Real Bout Fatal Fury Special*, Xiaomi 24095PCADG). 14 IDs, todos
  `SEGV_ACCERR`, todos `app=1.17.22`.
- **Janela:** 2026-09-25 19:05 → 2026-09-27 15:44 (campo `when=` do `ApplicationExitInfo`,
  não o horário de recebimento do relatório).

### Comparação de stack com a Variante 2 original

```
Novo (8806/8774/8808, idêntico nos três):
  #00  pc 00000000021bff58
  #01  pc 00000000021c4550          (8625/8626: 21c4578 — bate exato com a Variante 2)
  #02  pc 0000000002578a88
  #03  pc 00000000021c5640
  #04  pc 00000000021ee720
  #05  pc 00000000021ee774
  #06  pc 0000000002bafa08
  #07  pc 00000000021f8f64  (retro_run+836)

Variante 2 original (doc acima):
  #00  pc 00000000021bff58   ← idêntico ao novo #00
  #01  pc 00000000021c4578   ← idêntico ao novo #01 (nos IDs 8625/8626)
  #02  pc 0000000002578a88   ← idêntico ao novo #02
  #03  pc 00000000021c541c   ← diverge do novo #03 (21c5640)
  #04  pc 00000000024d4888
  #05  pc 00000000024bda6c

Variante 1 original (SEGV_MAPERR fault 0x234):
  #00  pc 00000000021c5644   ← quase idêntico ao novo #03 (21c5640, ±4 bytes)
  #01  pc 00000000021ee720   ← idêntico ao novo #04
  #02  pc 00000000021ee774   ← idêntico ao novo #05
  #03  pc 0000000002bafa08   ← idêntico ao novo #06
  #04  pc 00000000021f8f64  (retro_run+836)  ← idêntico ao novo #07
```

Os três primeiros frames do stack novo batem exatos com a Variante 2; os quatro últimos batem
exatos (ou a 4 bytes) com a Variante 1. Como as duas variantes antigas foram capturadas com
profundidade de unwind menor (6 frames) e a nova com 8, a leitura mais simples é que é **a
mesma cadeia de chamada**, e as duas variantes antigas eram cortes parciais dela — não duas
causas independentes, e não uma terceira causa nova.

### Por que isto não fecha como "cliente desatualizado" sem ressalva

Diferente dos outros casos desta triagem (GameService/dataSync, Home LazyGrid, WorkManager
SHORT_SERVICE), aqui o campo `app=1.17.22` **não pode ser tomado como prova** de que o
dispositivo já tinha o fix no momento do crash:

- O fix (`CoreWorkGuard`) está nas **classes Kotlin do AAR** (classes `GLRetroView`,
  `CoreWorkGuard`), não no `.so` do FBNeo. `GLRetroView.step()` (Kotlin) hoje chama
  `CoreWorkGuard.begin()`/`end()` **ao redor** da chamada JNI — o `Java_..._step` nativo e o
  `LibretroDroid::step()` C++ em si não mudam de bytes com esse fix. Ou seja, **o offset do
  `liblibretrodroid.so` sozinho não diferencia build com guard de build sem guard** — e de
  fato o frame `libretrodroid::LibretroDroid::step()+112` nos tombstones novos é byte-idêntico
  ao dos tombstones antigos (ambos `0xb94e8`/`0xb94e8` na amostra desta triagem).
- Para crashes nativos, `CrashTelemetry.reportOneExit` grava `app=` a partir de
  `TelemetryReporter.deviceContext()` →
  [TelemetryReporter.kt:229](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/telemetry/TelemetryReporter.kt#L229)
  (`safeVersion()`), que lê a versão **instalada no momento do scan** (próxima abertura do
  app), não a versão que rodava quando o processo `:game` morreu. Um device que crashou numa
  build antiga e atualizou antes de reabrir reportaria `app=1.17.22` mesmo sem o fix.

### Diagnóstico

**Inconclusivo, registrado como aberto por segurança.** Duas leituras possíveis, nenhuma
descartável com os dados disponíveis:

1. **O fix realmente não cobre este caminho.** A validação em dispositivo do fix original foi
   uma sessão curta (~2.000–2.300 frames, ~35–40 s) num único aparelho (SM-A127M), sem stress
   de pausa/retomada real repetida. Se o gatilho verdadeiro não é a corrida `destroy()` ×
   `retro_run` (a causa-raiz **nunca foi confirmada no código do FBNeo**, só inferida por
   comparação de disassembly com o fonte público — ver "Diagnóstico revisado" acima, que já
   dizia "os stacks isolados não provam... faltam logs de lifecycle"), o `CoreWorkGuard`
   protege uma corrida que não é a que está acontecendo em produção.
2. **É eco de builds anteriores a `9742176`** (commit do `CoreWorkGuard`, 2026-09-24 15:15,
   presente a partir de `1.17.21`/`1.17.22`), e o `app=1.17.22` reflete só a versão atual do
   device, não a versão de quando o processo `:game` crashou.

A leitura (1) é a mais preocupante e a que motiva reabrir em vez de anotar como recorrência
simples: o cluster do *Real Bout Fatal Fury Special* (9 IDs, 6 eventos reais em ~3 min, ver
[[2026-09-28-fbneo-crashes-baixa-confianca-triagem]] para o detalhe do `retro_load_game` que
saiu deste grupo) é um **crash loop no mesmo aparelho**, incompatível com "resquício de build
antiga" — um device que já crashou e reabriu 6 vezes em 3 minutos quase certamente não trocou
de versão do app no meio disso.

## Próximos passos sugeridos na triagem (superados pela reprodução abaixo)

- [ ] Confirmar em telemetria futura se `app=1.17.22` recém-instalado (sem histórico de
      crash anterior a este) ainda produz o mesmo `SEGV_ACCERR` — isso eliminaria a hipótese
      (2).
- [ ] Repetir a validação em device com sessão **longa** (dezenas de minutos, múltiplos
      pause/resume reais, não só uma sessão contínua de 40 s) no Neo Geo/CPS2, idealmente no
      mesmo modelo SM-A546E.
- [ ] Se a hipótese (1) se confirmar, a causa-raiz volta a ser desconhecida — o
      `CoreWorkGuard` continua correto (é uma corrida real, só talvez não a única), mas não é
      suficiente sozinho.

## Investigação conclusiva e correção da recorrência — 2026-09-28

### Causa reproduzida: save state de outro binário do FBNeo

O core foi atualizado, mas todos os sistemas que usam `CoreID.FBNEO` continuavam com
`statesVersion = 0`. A única checagem de `GameViewModelSaves.loadSaveState` comparava esse
inteiro com o metadata; metadata ausente também virava versão 0. Assim, autosaves e slots
anteriores à atualização passavam pela validação e chegavam a `retro_unserialize`.

O `StateReadAcb` do FBNeo copia cada área diretamente para a memória viva. Quando o layout
mudou e faltam bytes para as áreas seguintes, retorna falha **depois de alterar as anteriores**.
`retro_unserialize == false` não desfaz essas escritas. O frontend ainda repetia a tentativa
no autosave, deixando frames executarem entre tentativas. Já o primeiro frame após a primeira
restauração inválida pode acessar o índice corrompido de `lfo_pm_table` no YM2610.

Reprodução determinística no Samsung SM-A127M, Android 13, arm64, em processo isolado e
**uma única thread**, sem GL, pausa, `CoreWorkGuard` ou destruição concorrente:

1. Extrair do histórico `lemuroid-cores` o FBNeo `b31f28b` (r28c); carregar `rbffspec.zip`,
   executar 300 frames e gravar um estado.
2. Em outro processo, carregar o FBNeo atual com a mesma ROM e executar 300 frames.
3. Restaurar o estado anterior e chamar `retro_run`.

| Operação | Resultado |
| --- | --- |
| `retro_serialize_size` no core anterior | 414.123 bytes |
| `retro_serialize_size` no core atual | 415.155 bytes |
| Restaurar os 414.123 bytes no core atual | `retro_unserialize = false` |
| Primeiro `retro_run` subsequente | SIGSEGV / `SEGV_ACCERR` |
| Estado criado e restaurado no core atual | `retro_unserialize = true`, execução normal |

Stack da primeira reprodução:

```text
#00 0x21bff58
#01 0x21c4578
#02 0x2578a88
#03 0x21c5640
#04 0x21ee720
#05 0x21ee774
#06 0x2bafa08
#07 0x21f8f64 (retro_run+836)
```

São os **oito offsets exatos** do ID 8625. Os tombstones de 8625, 8774 e 8806 foram
consultados novamente no serviço com autorização do usuário. 8774/8806 diferem em `#01`
(`0x21c4550`), como já registrado. Não é necessário supor atualização no intervalo entre
crashes: a restauração automática do mesmo snapshot incompatível explica o crash loop.
Não temos os saves dos usuários para provar individualmente cada ocorrência. Tampouco essa
reprodução fecha o incidente separado em `retro_init` ou o `strcmp` de `retro_load_game`.

Identificação dos artefatos arm64 usados:

- Anterior: SHA-256 `88fd9f4aa9d8f235b58e64e78947ede0965c1e7ff85caf03dbcfcfe914b2e583`.
- Atual: SHA-256 `67289389f5a8870cd41704924001428e13885176d54cab7953d4d165d3fc84df`.
- AAR permanece `fc3e021169f36b46989e36941d2e2cdeb8bfe89174a593e2144e0213f3fcb78d`.

### Correção

- `GameLoader` calcula SHA-256 do **arquivo de core selecionado**, uma vez por carga, em IO.
  A identificação acompanha o jogo até `GameViewModelSaves`; cobre core empacotado, baixado,
  troca de arquitetura e todos os subsistemas que compartilham `CoreID.FBNEO`.
- `SaveState.Metadata` passa a registrar `coreSha256` e `stateSha256` nos novos saves FBNeo.
- `SaveStateCompatibility` exige versão, core e integridade compatíveis **antes** de qualquer
  mutação nativa. Não depende só do tamanho (layouts diferentes podem ter o mesmo tamanho).
  Também recusa metadata ausente, truncamento e combinação de estado com sidecar incorreto.
- A mesma checagem cobre autosave, slots e quick save. Autosave incompatível é ignorado com
  aviso e o jogo inicia normalmente, interrompendo o ciclo de crashes. A exceção interrompe
  as tentativas de restauração antes da primeira chamada JNI.
- SRAM e o caminho de saves internos ao jogo não foram alterados. Os demais cores mantêm
  a regra anterior de `statesVersion`. Não houve substituição de `.so` nem de AAR.

**Efeito da migração:** snapshots FBNeo legados sem fingerprint são recusados mesmo que
algum tenha sido criado com o core atual, pois não há como provar sua compatibilidade. Os
slots não são apagados; o autosave é substituído normalmente quando uma nova sessão é salva.
Não se deve preencher fingerprints artificialmente em saves antigos: isso contornaria a
proteção e reintroduziria o crash.

### Validação da correção

- `:lemuroid-app:testFreeBundleDebugUnitTest --tests com.swordfish.lemuroid.app.shared.game.SaveStateCompatibilityTest`:
  **9 testes**, zero falhas. Cobrem metadata legado, mudança de core/ABI com tamanho igual,
  persistência JSON, checksum, truncamento, identificação ausente, versão e outros cores.
- `:lemuroid-app:assembleFreeBundleDebug`: **BUILD SUCCESSFUL** (junto aos testes: 4m46s,
  175 tarefas). APK arm64 instalado no Samsung SM-A127M de teste.
- [Runner nativo](../../../tests/native/test-fbneo-state-compatibility.ps1): seis cenários
  positivos, gravação/restauração em processos novos para `rbffspec`, `kof97` e `msh`.
  Cada cenário executou 300 frames iniciais e 3.600 posteriores. Todos terminaram normalmente.
  O modo opcional `-ReproduceLegacyCrash` foi executado para `rbffspec` e reproduziu a falha
  após `unserialize=0`, com o processo de teste encerrando por SIGSEGV (exit 139).
- **UI real do app:** instalado o snapshot r28c como autosave `rbffspec.zip.state` com
  metadata `{}`. Ao abrir o jogo, log `Skipping incompatible auto-save before calling the
  native core`; execução continuou por mais de 3.900 frames, com três ciclos de
  desligar/ligar a tela (pausa/retomada) e saída normal. Fora das pausas, ~60 FPS.
- Na saída o app gravou um estado de 415.155 bytes com os dois hashes. Ambos foram conferidos
  independentemente contra o payload descomprimido e o `.so`. Reabertura com esse novo
  autosave estável por mais de 8.400 frames, sem rejeição por incompatibilidade.
- Evidências locais: `tmp/fbneo-recurrence/` (tombstones, reprodução, log do build, logs do
  app e estados de teste) e `tmp/fbneo-state-test/` (runner nativo). ROMs e bibliotecas
  históricas não foram adicionadas ao repositório.

Para repetir os testes nativos, fornecer ROMs locais e o BIOS correspondente:

```powershell
./tests/native/test-fbneo-state-compatibility.ps1 -RomDirectory ./tmp/test_roms -Serial RX8R90G1D6E
# Reprodução negativa opcional; OldCore é o binário anterior extraído do Git como bytes:
./tests/native/test-fbneo-state-compatibility.ps1 -RomDirectory ./tmp/test_roms -Serial RX8R90G1D6E -Games rbffspec -OldCore ./tmp/fbneo-recurrence/fbneo-r28c.so -ReproduceLegacyCrash
```

A correção anterior tratava uma corrida real, mas iniciar uma ROM limpa por 40 segundos
não exercitava a migração dos saves já existentes. A regressão agora tem um gatilho
reproduzível antes da correção e uma checagem verificável antes de alcançar o core.

### Revalidação final — 2026-09-29

Rodada conjunta concluída com **BUILD SUCCESSFUL em 15m38s**, após a revisão final:

- `:retrograde-app-shared:ktlintMainSourceSetCheck`;
- `:lemuroid-app:ktlintMainSourceSetCheck` e `:lemuroid-app:ktlintTestSourceSetCheck`;
- `:lemuroid-app:testFreeBundleDebugUnitTest`, filtrado para `SaveStateCompatibilityTest`:
  **9 testes, zero falhas, erros ou testes ignorados**;
- `:lemuroid-app:assembleFreeBundleDebug`.

Log local: `tmp/fbneo-recurrence/final-validation.log`. A validação no aparelho e a reprodução
nativa descritas acima cobrem a mesma correção funcional; a revisão final ajustou formatação.
