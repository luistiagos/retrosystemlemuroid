# [BUG] FBNeo: SIGSEGV em `retro_run` durante gameplay / encerramento

**Detectado em:** 2026-09-22 22:55 (telemetria de produção)
**Investigação e correção:** 2026-09-23
**Status:** corrida de ciclo de vida corrigida e testes automatizados aprovados; confirmação dos relatos em aparelho arm64 pendente.
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
compatível com as duas assinaturas. Os stacks isolados não provam que todas as 19
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
- `javap` do AAR final confirma `begin` antes de `LibretroDroid.step` e `end` nos
  caminhos normal e excepcional do renderer.
- O script de empacotamento verifica byte a byte todo o conteúdo não alterado do AAR.
- BUILD_RESULT_PENDING

### Confirmação em aparelho pendente

Não havia aparelho conectado (`adb devices -l` vazio). Os arquivos locais
`roms/fbneo/kof97.zip` e `roms/fbneo/rbffspec.zip` são placeholders de zero bytes.
Não foi executado gameplay desses títulos nesta sessão.

Para encerrar o registro: testar ambos os jogos em arm64, incluindo sessão longa,
save/load, pausa/retomada, saída durante frame lento e reabertura. Verificar também
*Marvel Super Heroes* para cobrir o caso anterior de `retro_init`. Coletar logcat
com eventos de lifecycle e monitorar as duas assinaturas após distribuir o APK.

## Lição

Um timeout de espera da UI não cancela a chamada nativa em execução. A proteção
contra ANR precisa vir acompanhada de um ciclo de vida que adie a liberação de
memória até todas as chamadas nativas terminarem. A atualização do core, sozinha,
não corrige essa corrida do frontend.
