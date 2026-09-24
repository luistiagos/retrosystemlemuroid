# [BUG] FBNeo: SIGSEGV em `retro_run` durante gameplay / encerramento

**Detectado em:** 2026-09-22 22:55 (telemetria de produção)
**Investigação e correção:** 2026-09-23
**Revalidação:** 2026-09-24
**Status:** Resolvido e validado com sucesso em dispositivo físico arm64 (Samsung SM-A127M) com as ROMs reais afetadas (*The King of Fighters '97*, *Real Bout Fatal Fury Special* e *Marvel Super Heroes*).
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
   - Zero ocorrências de `SEGV_ACCERR` (a Variante 2 foi completamente eliminada).
   - Pausa/retomada via ciclo de vida da Activity sem falhas de concorrência.
   - Reabertura limpa subsequente sustentando 60 FPS (`VIDEOFRAMES 360`, `EMUFPS 59.96 frames/s`).

2. ***Real Bout Fatal Fury Special* (Neo Geo via FBNeo):**
   - Boot limpo com detecção do BIOS Neo Geo.
   - Gameplay contínuo por mais de 2.340 frames (`EMUFPS 60.05 frames/s`).
   - Zero ocorrências de `SEGV_MAPERR fault addr 0x234` / `YM2610TimerOver` (a Variante 1 foi completamente eliminada).
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
