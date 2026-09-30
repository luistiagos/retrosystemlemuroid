# [BUG] Investigações de baixa confiança — triagem 2026-09-28 → FBNeo inicializado duas vezes no mesmo processo `:game`

**Data:** 2026-09-28 (triagem) · 2026-09-29/30 (investigação e correção)
**Status:** ✅ **Resolvido** — item 1 corrigido e validado (causa-raiz: segunda sessão de core no
mesmo processo `:game`; a mesma causa fecha [[2026-09-18-fbneo-retro-init-allocator-crash]]).
Item 3 encerrado sem ação (conclusão abaixo). Itens 2 e 4 seguem sem evidência suficiente e
foram para [[2026-09-30-picodrive-e-anr-home-aguardando-trace]], com o que se apurou.
**Severidade:** Alta para o item 1 (crash do processo de jogo, em loop a cada relançamento do FBNeo
num `:game` reaproveitado).
**Branch:** version9
**Origem:** telemetria `retrogamesystem/native`, `retrogamesystem/anr`

O doc original agrupava quatro achados de uma ocorrência cada, com "Ação: nenhuma" em todos. O
item 1 tinha no tombstone um sinal que a triagem não usou — `name: GLThread 2` — e ele levou à
causa-raiz de dois bugs abertos.

---

## 1. FBNeo: `strcmp(NULL)` em `retro_load_game` — ✅ corrigido

**Error:** 8780 — Xiaomi 24095PCADG (POCO), Android 16, `app=1.17.22`, 2026-09-27 13:51:29.

```
pid: 5737, tid: 6701, name: GLThread 2
signal 11 (SIGSEGV), code SEGV_MAPERR, fault addr 0x0
  #00  libc.so (__strcmp_aarch64+36)
  #01  libfbneo_libretro_android.so (retro_load_game+324)
  #02  liblibretrodroid.so (LibretroDroid::loadGameFromPath+124)
  ...  GLRetroView.loadGameFromPath ← initializeCore ← Renderer.onSurfaceCreated
```

### O sinal: `GLThread 2`

O `GLSurfaceView` patchado nomeia a thread com um contador **estático**
(`super("GLThread " + sThreadIndex.incrementAndGet())`,
`LibretroDroid-patched/.../GLSurfaceView.java:596`). "GLThread 2" = o processo já tinha criado outra
`GLRetroView`. Nos tombstones de FBNeo em gameplay disponíveis (8625, 8774, 8806 e as duas
variantes de [[2026-09-22-fbneo-retro-run-segv-gameplay]]) a thread é `GLThread 1`; nos dois de
**inicialização** que temos localmente (8780 aqui, e 8539 do `retro_init`) é `GLThread 2`.

### Causa-raiz: o FBNeo não suporta `retro_init` depois de `retro_deinit` na mesma imagem

O LibretroDroid nunca faz `dlclose` (pitfall 13): `LibretroDroid::destroy()` chama
`retro_unload_game` + `retro_deinit`, e o `dlopen` da sessão seguinte devolve **a mesma imagem**,
com os estáticos da anterior. No FBNeo
([burn.cpp](https://github.com/libretro/FBNeo/blob/c2c52376cfb49b431200f2b2c986423abd7463e7/src/burn/burn.cpp)):

- `BurnGameListInit()` aloca `pszShortName`/`pszFullNameA`/`pszFullNameW` e **aponta
  `pDriver[i]->szShortName` para as cópias**;
- `BurnGameListExit()` (via `retro_deinit` → `BurnLibExit`) dá `free` em tudo **sem zerar os
  ponteiros** nem restaurar `pDriver[i]`;
- o `retro_init` seguinte → `BurnLibInit()` → chama `BurnLibExit()` de novo → `free` nos mesmos
  três arrays: **double free**.

Num processo novo isso é impossível: `pszShortName == NULL` e `nBurnDrvCount == 0`, os `free`
nem executam. **Todo crash dentro desses `free` exige um `retro_init` anterior no mesmo processo.**

Confirmado no binário exato do 1.17.22 (Build ID `99e0cc617988476a06d2b82e88dee3f514b7e9e8`):

| Frame de produção | Instrução |
| --- | --- |
| 8780 `retro_load_game+324` = `0x21f94e4` | `bl strcmp` dentro do `BurnDrvGetIndexByName(szRomset)` inlined: `x0 = BurnDrvGetTextA(DRV_NAME)` (`bl 0x21e7274`), `x1 = szRomset` (pilha). Fault `0x0` ⇒ **`pDriver[i]->szShortName == NULL`**, impossível num processo novo |
| 8539 `retro_init+252` = `0x21f472c` | `bl 0x21e6ecc` = `BurnLibInit` |
| 8539 `0x21e6ee8` | `bl 0x21e7060` = `BurnLibExit` (com `BurnGameListExit` inlined) |
| 8539 `0x21e710c` / 8740·8741·8804 `0x21e70f4` | `bl free` em `pszFullNameW` / `pszShortName` — sem zerar depois |
| r28c `retro_init+196` → `0x1dc4310` → `0x1dc4524`/`0x1dc4530` | mesma cadeia no binário anterior: os crashes originais de 2026-09-18 também eram isto |

Quando o alocador detecta, crasha no `retro_init` (Scudo: *"chunk header is zero … double free"*;
jemalloc: `je_large_dalloc`). Quando não detecta, os arrays liberados duas vezes voltam aliasados
nas alocações seguintes, a tabela de drivers sai corrompida e o crash vem depois — o `strcmp(NULL)`
do 8780.

### Como o processo `:game` hospeda uma segunda sessão

O processo só morre em `BaseGameActivity.finishAndExitProcess`, e mesmo ali o `exitProcess(0)` sai
`config_mediumAnimTime` (400 ms) depois do `finish()`. Dois caminhos reaproveitam o processo:

1. **Destruição da Activity fora de `finishAndExitProcess`** — tarefa removida dos recentes, por
   exemplo. A remoção destrói as Activities, mas não matou o `:game` (no AVD API 25 nem o processo
   principal morreu); o processo fica em cache, com o core carregado e já `deinit`ado, e o próximo
   jogo cai nele.
2. **Janela de 400 ms** — o resultado da `GameActivity` chega ao processo principal no `finish()`,
   e o `GameLaunchTaskHandler.tryFallbackCore` relançava na hora: a sessão nova nascia no processo
   que estava morrendo (e o `exitProcess` pendente a matava no meio da carga).

Além do double free do FBNeo, duas `GLRetroView` no mesmo processo compartilham o singleton nativo
(`LibretroDroid`, `Environment`): um `destroy()` adiado pelo `CoreWorkGuard` da sessão velha roda
sobre a nova. Cada `GLRetroView` tem o seu guard; nenhum serializa uma contra a outra.

### Correção

**Um processo `:game` hospeda uma sessão de core, nunca duas.**

- [GameProcessSession.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/GameProcessSession.kt):
  `tryClaim()` (primeira sessão do processo) e `awaitGameProcessExit()` (lado do processo
  principal: espera o `:game` sair — `runningAppProcesses`, com `killProcess` após 3 s e defesa
  para ROM de TV box que devolve `null`).
- [BaseGameActivity.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/BaseGameActivity.kt):
  no `onCreate`, antes do breadcrumb e de qualquer coisa tocar o core, `tryClaim()`. Se o processo
  já hospedou uma sessão: `setResult(RESULT_RESTART_IN_FRESH_PROCESS)` com os parâmetros do
  lançamento, `finish()` (síncrono com o system_server — `finishActivity` devolve `boolean`) e
  `Process.killProcess` (não `exitProcess`: `exit()` rodaria destrutores estáticos das bibliotecas
  com a GLThread antiga possivelmente dentro do core).
- [GameLaunchTaskHandler.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/main/GameLaunchTaskHandler.kt):
  trata `RESULT_RESTART_IN_FRESH_PROCESS` esperando o processo sumir e relançando com os mesmos
  parâmetros (limite de 2 relançamentos, depois tela de erro de app). `tryFallbackCore` também
  espera o `:game` que falhou sair antes de relançar.
- `lemuroid-app/config/ktlint/baseline.xml`: só os números de linha dos apontamentos antigos desses
  dois arquivos, deslocados pelas inserções (mesma sequência de coluna + regra, conferida por
  script; nenhum apontamento novo).

Nenhum `.so` nem AAR foi trocado. Não corrigimos o FBNeo: o core vem do buildbot e compilar à mão
já custou um incidente (pitfall 6b). O bug de upstream (`BurnGameListExit` sem zerar ponteiros)
pode ser reportado, mas a garantia de uma sessão por processo cobre qualquer core com o mesmo
defeito — e a regra de nunca `dlclose` torna isso obrigatório de qualquer forma.

### Validação

- **Reprodução nativa, sem ROM** — [test-fbneo-reinit.ps1](../../../tests/native/test-fbneo-reinit.ps1)
  + [fbneo_reinit_test.c](../../../tests/native/fbneo_reinit_test.c): `dlopen` →
  `retro_set_environment` → `retro_init` → `retro_deinit`, repetido, sem `dlclose`. AVD
  `lemu_api25_2gb` (x86_64, jemalloc), core x86_64 empacotado:
  - 1 sessão por processo: exit 0;
  - 2 sessões: o segundo `dlopen` devolve o **mesmo handle**, e `Invalid address … passed to free:
    value not allocated` → SIGABRT (exit 134), com `retro_init+0x10f` → `BurnLibInit` →
    `BurnLibExit` → `je_free` no tombstone.
- **No app, antes da correção** (build `freeBundleDebug -PdevAbi=x86_64` com os três arquivos no
  estado do `HEAD`): KOF '97 (Neo Geo, FBNeo) aberto → tarefa removida (`am stack remove`) → os
  dois processos sobrevivem, Activities destruídas → app reaberto, mesmo jogo tocado:
  `Fatal signal 6 (SIGABRT) … (GLThread 2)`, `retro_init+271` → frames do FBNeo → `je_free`. A
  assinatura do 8539.
- **No app, depois da correção**, mesmo roteiro:
  ```
  18:00:39.739 4656 W BaseGameActivity: Game process already hosted a session; restarting The King of Fighters '97 in a fresh one (0)
  18:00:39.757      I ActivityManager: Process app.retrogamesystem.debug:game (pid 4656) has died
  18:00:39.825      I ActivityManager: Start proc 4917:app.retrogamesystem.debug:game ... GameActivity
  18:00:41.148 4917 I GLRetroView: Core created on GLThread 1 in 79 ms
  18:00:44.911 4917 E libretrodroid: EMUFPS 60.55 frames/s
  ```
  ~90 ms entre a recusa e o processo novo; 60 FPS sustentados depois.
- **Aparelho físico — Samsung SM-A127M (RX8R90G1D6E), Android 13, arm64**, o mesmo modelo do 8539:
  - harness nativo arm64: 1 sessão → exit 0; 2 sessões → `SIGSEGV … null pointer dereference`
    em `je_large_dalloc+52` ← `je_free+2116` ← `0x21e70f4` ← `0x21e6ee8` ← `0x21f472c`
    (`retro_init+252`): **os mesmos offsets de libc e do FBNeo do tombstone 8539 de produção**;
  - app com a correção (`freeBundleDebug` arm64), mesmo roteiro do AVD (KOF '97 → `am stack remove`
    → os dois processos sobrevivem → reabrir → tocar no jogo):
    ```
    00:25:01.045 25140 W BaseGameActivity: Game process already hosted a session; restarting The King of Fighters '97 in a fresh one (0)
    00:25:01.117       I ActivityManager: Process app.retrogamesystem.debug:game (pid 25140) has died
    00:25:01.211       I ActivityManager: Start proc 25491:app.retrogamesystem.debug:game ... GameActivity
    00:25:06.353 25491 I GLRetroView: Core created on GLThread 1 in 137 ms
    00:25:10.746 25491 E libretrodroid: EMUFPS 60.71 frames/s
    ```
- **Fluxo normal sem regressão** (AVD e aparelho): sair pelo "voltar" encerra o `:game` pelo
  `finishAndExitProcess`; reabrir o jogo sobe processo novo em `GLThread 1`, sem recusa nem
  relançamento extra.
- `:lemuroid-app:ktlintMainSourceSetCheck`, `ktlintTestSourceSetCheck`,
  `testFreeBundleDebugUnitTest --tests "…telemetry.*"` (`GameSessionForExitTest` 5/5,
  `StorageEnvironmentFailureTest` 9/9, `TombstoneParserTest` 5/5) e `assembleFreeBundleDebug`
  (arm64/armv7 e `-PdevAbi=x86_64`): BUILD SUCCESSFUL.

Não validado: o caminho do `tryFallbackCore` (Neo Geo só tem FBNeo; nenhum sistema com fallback
foi exercitado) e o limite de 2 relançamentos (exigiria um `:game` que não morre).

### Correção lateral: o breadcrumb pertencia ao scan, não ao crash

O doc original dizia "crash ocorre antes do fim do load, quando o breadcrumb ainda não foi
gravado". Falso: o breadcrumb é gravado no `onCreate`, antes do load. O que acontece é que o
`CrashTelemetry.reportOneExit` lia o slot **na hora do scan** — próximo cold start do processo
principal, que pode vir horas depois (8541: `when=` 20:43 no relógio do aparelho, recebido às
08:45 do dia seguinte no do servidor) — e o pendurava em todos os exits do lote. Um crash saía sem breadcrumb (uma saída limpa posterior apaga o slot) ou com o jogo
de outra sessão.

`TelemetryContext.gameSessionForExit` agora só anexa o breadcrumb a exit de processo `:game` cuja
sessão começou até o `timestamp` do exit — o critério que o `CoreCrashFallback` já usava. Teste:
[GameSessionForExitTest.kt](../../../lemuroid-app/src/test/java/com/swordfish/lemuroid/app/shared/telemetry/GameSessionForExitTest.kt).
**Consequência para a triagem:** `system=/core=/game=` de relatórios anteriores a esta correção
não identifica com segurança o jogo que crashou.

## 2. Picodrive: SIGSEGV em `0x2200000` — segue aberto

Ver [[2026-09-30-picodrive-e-anr-home-aguardando-trace]]. Apurado aqui: o frame `#02 0x13f468` é
`retro_run+0x378` (gameplay, não carga); o binário baixado da tag `1.19.0` é o **mesmo blob**
(`f395e5c…`) que o app empacota hoje como `libpicodrive_libretro_android.so`; a falta de breadcrumb
não significa nada (ver correção lateral acima).

## 3. Flycast: SIGTRAP sem stack — ✅ encerrado, sem ação

**Error:** 8636 — Xiaomi 2201117PG, Android 13, `app=1.17.22`, `system=dc; core=flycast;
game=Spider-Man` (breadcrumb do slot na hora do scan — ver acima).

- **O `TombstoneParser` não falhou.** `parse` captura `Throwable` e devolve `null`, que o
  `CrashTelemetry.readTrace` transforma em *"Binary tombstone (N bytes) could not be decoded"*. O
  log `"crash"` (o `description` do `ApplicationExitInfo`) só sai com stream nulo/vazio ou exceção
  na leitura: **o sistema não tinha tombstone anexado** a esse exit.
- **SIGTRAP no Flycast não é assinatura de regressão de empacotamento.** No build libretro,
  `verify(x)` e `die()` (`core/types.h`) chamam `os_DebugBreak()` →
  `__builtin_trap()` (`shell/libretro/libretro.cpp:3687`); há 333 `verify` no core. SIGTRAP =
  asserção fatal do emulador — dependente do jogo e do estado. Os dois incidentes de binário
  (pitfall 6) têm guard de build (`verifyFlycastCore`), e um SIGTRAP isolado sem stack não os
  indica.
- **Ação:** nenhuma. A mensagem da asserção (`Verify Failed : …`) vai para o logcat via `log_cb`
  e não chega à telemetria; se a família crescer, o próximo passo é guardar a última linha de erro
  do core como breadcrumb.

## 4. ANR na `MainActivity` fora de jogo — segue aberto

Ver [[2026-09-30-picodrive-e-anr-home-aguardando-trace]]. Apurado aqui: o 8623 **tem** um frame —
`libc.so fstatat64+8` nos campos `file`/`method` — que o doc original não registrou, e o toque num
jogo faz `File(path).length()` (um `stat`) na main thread
([MainActivity.kt:310-323](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/main/MainActivity.kt#L310-L323)).

## Lição

1. **Nome da thread no tombstone é evidência.** `GLThread N` com N ≥ 2 prova uma segunda
   `GLRetroView` no processo. As triagens de 2026-09-22 e 2026-09-28 leram esses tombstones pelo
   stack e pelo offset e não compararam o nome da thread entre as famílias.
2. **"Nunca `dlclose`" (pitfall 13) tem contrapartida: nunca reusar o processo.** A regra trocou um
   crash no descarregamento por outro na reinicialização, e o segundo ficou três semanas atribuído
   a "binário velho do FBNeo" — a atualização de core de 2026-09-18 só mudou os offsets.
3. **Contexto de relatório tem que ser do momento do evento.** Breadcrumb (e `app=`) lidos no scan
   descrevem o aparelho de agora, não o crash de então.
