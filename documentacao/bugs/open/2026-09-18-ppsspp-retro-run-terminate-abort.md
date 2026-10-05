# [BUG] Crash nativo no core PSP (PPSSPP) — SIGABRT / `std::terminate()` dentro de `retro_run`

**Data:** 2026-09-18
**Status:** 🟢 **Corrigido no core em 2026-10-05, aguardando release** — cores `1.21.0` (`lemuroid-cores`
`55d29c2`, Lemuroid `411fe17`), core `v1.19.3-987-geab2cc85bc-lemuroid1`. Fechar (mover para `done`)
quando um release com esse core estiver em produção e a telemetria não trouxer `retro_run` +
`terminating` com `lemuroid1`. (Reaberto em 2026-10-05: a 9355 reincidiu na **1.17.23**, Android 16.)
**Causa corrigida em 2026-10-05 (ver "Verificação da reabertura" no fim):** **não há exceção C++ nenhuma.**
`retro_run+0x420` é um `bl std::terminate` gerado pelo `std::thread::operator=(thread&&)` do
`EmuThreadStart` inline — o core recria a thread de emulação com a anterior ainda viva. Tanto o
diagnóstico original (falta de try/catch no JNI) quanto o da reabertura (libc++ estático entre DSOs)
estão refutados pela desmontagem. O try/catch do `step` nunca poderia cobrir este crash.
- **Complexidade:** alta — correção dentro do core PPSSPP (rebuild do `.so` + publicação no `lemuroid-cores`).
**Severidade:** Alta (todo jogo de PSP pode abortar o processo `:game` a qualquer momento durante a partida, sem ação do usuário além de jogar)
**Branch:** version9
**Origem:** telemetria `retrogamesystem/native` (`libppsspp_libretro_android.so::reason=Native crash status=6`)
**Errors (serviço):** 6986, 6559, 6423, 6417, 6150, 8103, 7309, 9180, 9187, 9203, 9355 (11 ocorrências)
**Reincidência:** primeira vez detectado (5 ocorrências, Xiaomi/POCO rodin, arm64-v8a, Android 16, app 1.17.19)

---

## Sintoma

Durante a emulação de jogos de PSP (ex.: *LEGO Batman - The Videogame*), o processo `:game`
aborta na GLThread com `signal 6 (SIGABRT), code SI_QUEUE`, mensagem `terminating`:

```
pid: 27670, tid: 28429, name: GLThread 32421
signal 6 (SIGABRT), code SI_QUEUE
Abort message: terminating
backtrace:
  #00  pc 0000000000072f7c  /apex/com.android.runtime/lib64/bionic/libc.so (abort+156)
  #01  pc 0000000001414e80  .../libppsspp_libretro_android.so
  #02  pc 00000000014146c4  .../libppsspp_libretro_android.so
  #03  pc 0000000001414660  .../libppsspp_libretro_android.so
  #04  pc 00000000014145ec  .../libppsspp_libretro_android.so (std::terminate()+36)
  #05  pc 0000000000fc47b8  .../libppsspp_libretro_android.so (retro_run+1056)
  #06  pc 00000000000b9354  .../liblibretrodroid.so (libretrodroid::LibretroDroid::step()+112)
  #07  pc 00000000000b41bc  .../liblibretrodroid.so (Java_com_swordfish_libretrodroid_LibretroDroid_step+60)
```

## Causa-raiz

O PPSSPP lança uma exceção C++ de dentro de `retro_run()` (frame #05). O `std::terminate()`
que aparece no frame #04 **não é um catch explícito do core** — é o comportamento padrão do
runtime C++ quando o `_Unwind_RaiseException` faz a busca (fase 1) por um handler subindo a
pilha de chamadas inteira e **não encontra nenhum** `try/catch` em nenhum frame entre o ponto
do `throw` e o topo da thread. Quando a busca falha, o runtime chama `std::terminate()`
imediatamente, **antes de desenrolar qualquer frame** — por isso o tombstone mostra a pilha
de chamada exata do `throw` (retro_run → …frames internos do PPSSPP… → terminate → abort), e
não uma pilha já desenrolada até `main`.

Abri [libretrodroidjni.cpp](../../../../LibretroDroid-patched/libretrodroid/src/main/cpp/libretrodroidjni.cpp)
e comparei a função `Java_..._step` com as outras ~15 funções JNI exportadas no mesmo
arquivo (`create`, `destroy`, `resume`, `pause`, `reset`, `unserializeState`, etc.): **todas**
envolvem a chamada nativa em `try { ... } catch (std::exception&) { JavaUtils::throwRetroException(...) }`,
traduzindo qualquer exceção C++ do core numa `RetroException` Java antes de retornar ao
chamador Kotlin. A função `step` — a única chamada a cada frame renderizado, ou seja, a que
mais executa código do core por segundo — era a **única sem nenhum try/catch**:

```cpp
JNIEXPORT void JNICALL Java_com_swordfish_libretrodroid_LibretroDroid_step(...) {
    LibretroDroid::getInstance().step();   // sem try/catch — qualquer exceção do core aqui
    ...                                     // propaga sem handler e vira std::terminate()
}
```

Do lado Kotlin, [GLRetroView.kt](../../../../LibretroDroid-patched/libretrodroid/src/main/java/com/swordfish/libretrodroid/GLRetroView.kt#L600)
já tinha a infraestrutura pronta para lidar com isso: `onDrawFrame` chama `LibretroDroid.step(...)`
dentro de `catchExceptions { ... }`, que captura `RetroException` e emite o código de erro em
`retroGLIssuesErrors` (sem derrubar o processo) — mas essa rede de segurança só funciona se o
lado nativo **de fato lançar** uma `RetroException` Java em vez de deixar o C++ abortar antes
de a exceção cruzar a fronteira JNI.

## Correção

Adicionado o mesmo padrão de try/catch usado em todas as outras funções JNI deste arquivo à
função `step`, com um `catch (...)` adicional como rede de segurança porque C++ permite lançar
qualquer tipo, não só `std::exception`:

```cpp
JNIEXPORT void JNICALL Java_com_swordfish_libretrodroid_LibretroDroid_step(
    JNIEnv* env,
    jclass obj,
    jobject glRetroView
) {
    try {
        LibretroDroid::getInstance().step();
    } catch (std::exception &exception) {
        LOGE("Error in step: %s", exception.what());
        JavaUtils::throwRetroException(env, ERROR_GENERIC);
        return;
    } catch (...) {
        LOGE("Error in step: unknown exception thrown by core retro_run");
        JavaUtils::throwRetroException(env, ERROR_GENERIC);
        return;
    }

    if (LibretroDroid::getInstance().requiresVideoRefresh()) {
        ...
    }
    ...
}
```

O `try/catch` precisa estar na função JNI (não bastaria só dentro de `LibretroDroid::step()`
em `libretrodroid.cpp`), porque sem handler nenhum a busca de fase 1 já teria abortado antes
de chegar lá — mas qualquer ponto da pilha entre o `throw` e o topo serve; colocá-lo na
fronteira JNI segue o padrão já estabelecido no arquivo e garante que o `env->Throw` (dentro
de `JavaUtils::throwRetroException`) tem a chance de marcar a exceção pendente antes do
retorno ao Kotlin, onde `catchExceptions` já sabe tratá-la.

## Validação

Feita no artefato (não em device — o defeito é de exceção lançada por binário de terceiro sem
fonte local, não reproduzível sob demanda):

1. `./gradlew.bat :libretrodroid:assembleRelease --console=plain` no checkout
   `C:\projects\lemuroid\LibretroDroid-patched` → **BUILD SUCCESSFUL**.
2. Extraído `libretrodroid-release.aar` e confirmado, nas 4 ABIs
   (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`), que `liblibretrodroid.so` contém as novas
   strings de log (`"Error in step: %s"` e `"Error in step: unknown exception thrown by core retro_run"`)
   — prova de que o novo código foi de fato compilado em todas elas, não só na ABI de teste.
3. `libs/libretrodroid-patched.aar` no projeto principal substituído pelo AAR novo
   (SHA-256 `B5BC06720ED48973D4AB39D7701CB246A292936B0A8948DADB230A7D73DC6FD7`).
4. `./gradlew.bat :lemuroid-app:assembleFreeBundleDebug --console=plain` → **BUILD SUCCESSFUL**,
   incluindo `mergeFreeBundleDebugNativeLibs` e `verifyFlycastCore` (gate de integridade de
   `.so` nativos, não específico deste core mas confirma que o merge de libs nativas não
   quebrou).
5. Extraído `liblibretrodroid.so` de dentro do APK final
   (`lemuroid-app-free-bundle-arm64-v8a-debug.apk`) e confirmado que carrega as mesmas duas
   strings novas — o fix chega ao artefato instalável, não só ao AAR intermediário.

## Validação em device (Motorola moto g86 5G, Android 16, arm64-v8a)

O crash original não é reproduzível sob demanda (depende de uma exceção interna do PPSSPP em
condições não determinísticas). Para validar o caminho de erro de ponta a ponta sem depender
disso, apliquei a mesma técnica já usada no pitfall 8 do CLAUDE.md (instrumentação temporária
de erro, revertida antes do build final):

1. Adicionei um throw de teste temporário em `LibretroDroid::step()`
   (`throw std::runtime_error(...)` disparado uma vez, no 120º frame), simulando exatamente o
   que o PPSSPP faz — uma exceção C++ lançada de dentro do laço que chama `core->retro_run()`.
2. Rebuild do AAR de teste, instalado via `adb install -r` no device.
3. Baixei e joguei *Final Fantasy VIII* (PSP) pela UI real do app. No frame 120 a exceção de
   teste disparou. Logcat mostrou:
   ```
   E libretrodroid: Error in step: TEMP TEST: simulated core exception in retro_run
   I AndroidRuntime: VM exiting with result code 0, cleanup skipped.
   ```
   Sem `FATAL EXCEPTION`, sem `SIGABRT`, sem `Abort message` — o processo `:game` encerrou
   com **result code 0** (saída normal), não abortado. A UI voltou para a Home mostrando
   "O arquivo ROM parece estar corrompido e foi removido. Toque no jogo novamente para
   baixá-lo de novo." — o tratamento gracioso de `GameLoaderError.Generic` via
   `retroGLIssuesErrors` → `handleRetroViewError` → `requestFailureFinish`, exatamente como
   esperado (ver `GameViewModelRetroGameView.kt:418-436`).
4. Revertida a instrumentação de teste (`git diff` conferido: zero ocorrências de "TEMP TEST"
   no `.cpp` e no `.so` compilado, nas 4 ABIs e no APK final).
5. Rebuild do AAR limpo (SHA-256 acima) e do APK, reinstalado no mesmo device.
6. **Teste de não regressão**: baixei e joguei o mesmo *Final Fantasy VIII* (PSP) de novo, já
   na build limpa — o jogo passou do boot ("システムデータチェック中です"), renderizou a
   splash da Square Enix e seguiu rodando. Logcat sem qualquer `Error in step`,
   `RetroException`, `FATAL` ou `SIGABRT`. Confirma que o novo `try/catch` não introduz
   falso-positivo em execução normal do core.

## Lição

1. **Um arquivo com 15 funções JNI seguindo o mesmo padrão de try/catch e uma exceção à regra
   é um bug esperando para acontecer** — e essa exceção era justamente a função chamada mais
   vezes por segundo (todo frame renderizado chama `step()`; as outras rodam sob demanda do
   usuário). Comparar a função suspeita com suas irmãs no mesmo arquivo revelou a lacuna sem
   precisar entender internals do PPSSPP.
2. **`std::terminate()` no meio da pilha do core não significa "bug do core sem solução"** —
   significa "nenhum handler de exceção no caminho". O tombstone mostrando o `throw` e o
   `terminate` na mesma função (sem frames de desenrolamento entre eles) é a assinatura de
   busca de handler fase-1 falha, não de um `abort()` direto do core.
3. A rede de segurança do lado Kotlin (`GLRetroView.catchExceptions`) já existia e já cobria
   este caso — o gap era puramente do lado nativo não repassar a exceção pela fronteira JNI.
   Vale conferir o par JNI/Kotlin sempre que um crash aparece "depois" de um ponto onde outras
   chamadas semelhantes já são tratadas.
4. Crash de terceiro não reproduzível sob demanda não impede validação real: a técnica do
   pitfall 6 (instrumentação temporária que simula o defeito, revertida antes do build final)
   funciona igual de bem em C++ quanto funcionou em Kotlin — o que importa é provar o caminho
   de erro ponta a ponta, não reproduzir a causa exata de terceiros.

## Recorrência (Triagem 2026-09-22)

- **Novos IDs associados:** 8103, 7309 (2 ocorrências)
- **Diagnóstico:** As ocorrências 8103 (Android 14) e 7309 (Android 14) apresentaram a exata mesma assinatura de `std::terminate()` e abort dentro de `retro_run`. Provenientes de builds anteriores à integração do AAR com o wrapper try/catch no `Java_..._step`. Fechados na telemetria.

## Reabertura em Produção (Triagem 2026-10-05)

- **Novos IDs associados:** 9180 (Android 14, 1.17.22), 9187 (Android 14, 1.17.22), 9203 (Android 14, 1.17.22), **9355** (Android 16, Motorola scout, **1.17.23**).
- **Diagnóstico e Causa da Reabertura:**
  A ocorrência 9355 comprova que o abort nativo em `retro_run` persiste na versão 1.17.23 com o mesmo frame `#04 ... std::terminate()`.
  A causa é que `libppsspp_libretro_android.so` é linkado com sua própria cópia estática do `libc++`. Em sistemas Android modernos, o mecanismo de busca de tratadores de exceção (`_Unwind_RaiseException`) não consegue cruzar a fronteira de bibliotecas compartilhadas para alcançar o `try/catch` de `Java_com_swordfish_libretrodroid_LibretroDroid_step` em `liblibretrodroid.so`. Como o `ppsspp` não encontra tratador dentro de sua própria imagem binária, o runtime C++ dispara `std::terminate()` imediatamente.
- **Ação:** O bug foi reaberto e movido para `documentacao/bugs/open/`. Todos os 4 IDs foram fechados no painel de telemetria.


## Verificação da reabertura (2026-10-05) — o crash não é exceção

### Resumo

O `std::terminate()` é chamado **diretamente** por `retro_run`, na atribuição por movimento de
`std::thread` dentro de `Libretro::EmuThreadStart()` (inline). O libc++ faz
`if (__t_ != 0) std::terminate();` quando se atribui uma thread nova a um `std::thread` que ainda
tem uma thread viva. **Nenhuma exceção é lançada**, então nenhum `try/catch` em DSO nenhum pode
interceptar. As duas causas registradas acima (falta de try/catch no `step`; busca de handler que
não cruza DSO por libc++ estático) estão erradas.

### Evidências (comandos literais)

Binário: `lemuroid-cores/bundled-cores/src/main/jniLibs/arm64-v8a/libppsspp_libretro_android.so`
(md5 `9c4f5bac00fc7fb470570895b9cf3118`, idêntico ao de `lemuroid_core_ppsspp`; Build ID
`611f775dd330b07ed77ff60cd67695b1ddfe0fa0`; PPSSPP `v1.19.3-987-geab2cc85bc`). Ferramentas do NDK
`D:/DevCaches/Android/Sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin`.

1. **É o mesmo binário do tombstone.** `llvm-nm -D --defined-only` → `retro_run` em `0xfc4398`,
   `_ZSt9terminatev` em `0x14145c8`. Tombstone: `retro_run+1056` = `0xfc47b8` e
   `std::terminate()+36` = `0x14145ec`. Batem os dois.
2. **O que está em `retro_run+0x420`.**
   `llvm-objdump -d --no-show-raw-insn --start-address=0xfc4398 --stop-address=0xfc4800`:
   ```
   fc471c: ldar w9,[x8]; cmp w9,#0x2; b.eq fc478c   ; estado == RUNNING -> pula para ThreadFrame
   fc4728: ldar w9,[x8]; mov w10,#1; stlr w10,[x8]   ; estado = START_REQUESTED
   fc4734: cmp w9,#0x4; b.eq fc478c                  ; wasPaused (PAUSED) -> não recria thread
   fc474c: ldr x8,[x8,#0x50]; blr x8                 ; ctx->ThreadStart()
   fc4758: adr x8, <EmuThreadFunc>; bl <std::thread ctor>   ; thread temporária JÁ criada e rodando
   fc4770: ldr x9,[x8,#0xcd0]                        ; handle do emuThread global
   fc4774: cbnz x9, fc47b8                           ; handle != 0 -> ...
   fc47b8: bl std::terminate                         ; <- frame #05 do tombstone
   ```
   É exatamente `EmuThreadStart()`: `wasPaused = state == PAUSED; state = START_REQUESTED;
   if (!wasPaused) { ctx->ThreadStart(); emuThread = std::thread(&EmuThreadFunc); }`.
   Enum deduzido dos `cmp`: 0 DISABLED, 1 START_REQUESTED, 2 RUNNING, 3 PAUSE_REQUESTED, 4 PAUSED.
3. **A mensagem do abort confirma.** `Abort message: terminating` (sem "due to uncaught exception
   of type …"). O handler padrão do libc++abi só imprime o `terminating` puro quando **não há
   exceção corrente**. Uncaught exception teria `__cxa_throw` na pilha e o tipo na mensagem; o
   tombstone não tem nenhum dos dois.

### Mecanismo (corrida entre GLThread e thread de emulação)

`retro_serialize` (`0xfc536c`) e `retro_unserialize` (`0xfc4bb4`) têm o mesmo padrão inline
(desmontado): se `RUNNING` → `PAUSE_REQUESTED`, espera `PAUSED` com `sleep_ms(1)`; grava/carrega;
`state = START_REQUESTED` (sem recriar a thread, pois `wasPaused`); `sleep_ms(4)`; retorna.

Quem tira o estado de `START_REQUESTED` para `RUNNING` é a **thread de emulação**, quando acorda do
`sleep_ms(1)` do laço `PAUSED`. Se ela não rodou até o próximo `retro_run` (≈4 ms + resto do frame
— aparelho carregado, thermal throttling, thread no núcleo LITTLE), o `retro_run` vê
`START_REQUESTED` ≠ `RUNNING`, chama `EmuThreadStart`, `wasPaused` é falso (1 ≠ 4), cria uma
segunda thread e a atribuição encontra `emuThread` ainda viva → `std::terminate()`.

Gatilhos no Lemuroid (todos chamam serialize/unserialize na GLThread com o render seguindo logo
depois) — `lemuroid-app/.../viewmodel/GameViewModelSaves.kt`:
- `restoreAutoSaveAsync` → `unserializeState` logo após o **primeiro frame** (boot do jogo — o
  momento de maior carga), com até 10 tentativas;
- quick save/load (atalho de controle, `GameViewModelInput.kt:384-385`) e save/load de slot;
- `saveOnBackground` (`persistSession(PHASE_BACKGROUND)`, commit `2c02594` de 2026-09-30) — **não**
  é a origem: os primeiros crashes (1.17.19, 2026-09-18) são anteriores.

Hipótese do gatilho exato por ocorrência: **não confirmada** — o report nativo só traz o tombstone.
O mecanismo, sim, está provado pelo binário.

### Hipóteses descartadas

- **Falta de try/catch em `Java_..._step`** (correção de 2026-09-18): não há `throw`. O try/catch
  continua correto para outros cores e fica, mas nunca cobriu este crash. A "validação em device"
  lançou a exceção de dentro do `liblibretrodroid.so` — provou o caminho JNI→Kotlin, não este crash.
- **libc++ estático impede a busca de handler entre DSOs** (reabertura): irrelevante, não há busca
  de handler. (E `catch (...)` casaria qualquer exceção de classe `CLNGC++` mesmo com RTTI duplicada.)
- **"8103/7309 vieram de builds anteriores ao fix"** (triagem 2026-09-22): a premissa caiu junto;
  eram o mesmo bug sem correção.

### O que deve ser feito

1. **Corrigir no fonte do core** (`libretro/libretro.cpp` do PPSSPP, base `eab2cc85bc`). Antes,
   conferir se o upstream já corrigiu `EmuThreadStart`/`EmuThreadPause` depois desse commit.
   Correção mínima:
   - `EmuThreadStart`: só criar thread se `!emuThread.joinable()`; se `joinable()` e o estado for
     `STOPPED`, dar `join()` antes de recriar. Com a thread viva, só setar `START_REQUESTED`.
   - `EmuThreadPause`: hoje retorna cedo se o estado ≠ `RUNNING` — com `START_REQUESTED` o
     serialize roda com a thread de emulação prestes a executar `EmuFrame` (corrida de dados no
     save state). Esperar `START_REQUESTED` virar `RUNNING` antes de pedir a pausa.
2. **Publicar** pelo fluxo do pitfall 15: commit no `lemuroid-cores` (os dois diretórios: 
   `bundled-cores` e `lemuroid_core_ppsspp`, 4 ABIs) → push → tag nova → bumpar
   `CoreDownloader.CORES_VERSION` → commitar o ponteiro do submódulo.
3. **Validar com reprodução determinística** (a corrida *é* reproduzível): build instrumentado do
   core com `sleep_ms(50)` no ramo `PAUSED` de `EmuThreadFunc`, abrir jogo de PSP com autosave,
   ou fazer quick save → esperado `SIGABRT terminating` em `retro_run+…` **antes** da correção e
   jogo seguindo **depois** (mesma instrumentação aplicada sobre o fonte corrigido).
4. **Mitigação só do lado do app (não recomendada como correção):** após `retro_serialize`/
   `retro_unserialize` do PPSSPP, segurar a GLThread mais alguns ms reduz a janela, mas não a
   fecha.

## Conferência do upstream (2026-10-05)

### O upstream já corrigiu — em quatro commits

Fonte: `https://raw.githubusercontent.com/hrydgard/ppsspp/{eab2cc85bc,master}/libretro/libretro.cpp`
e `https://github.com/hrydgard/ppsspp/commit/<sha>.diff`. A base `eab2cc85bc` é de 2025-11-05. Dos 40
commits que tocam `libretro/libretro.cpp` depois dela, só estes mexem no estado da thread de emulação
(achados com `grep -E "^[-+].*(joinable|START_REQUESTED|EmuThreadStart|EmuThreadState)"` nos `.diff`):

| Commit | Data | O que faz |
|--------|------|-----------|
| `72bd0fa4d3` | 2026-04-07 | `EmuThreadStart`: `if (RUNNING \|\| START_REQUESTED \|\| emuThread.joinable()) return;` — **fecha o nosso `std::terminate`** |
| `b7d4a54a45` | 2026-05-01 | o guard acima travava o serialize (thread pausada é `joinable`): vira `(joinable() && !wasPaused)` |
| `c42a3f070a` | 2026-08-04 | remove `START_REQUESTED`: `EmuThreadStart` grava `RUNNING` direto — fecha também a corrida do serialize em `START_REQUESTED` (item 1, `EmuThreadPause`) |
| `821c5b4534` | 2026-08-25 | devolve o `PAUSE_REQUESTED` (sem ele, deadlock no serialize); `retro_run` só troca buffers em `PAUSED`/`PAUSE_REQUESTED` |

`EmuThreadStart` no master:
```cpp
EmuThreadState state = emuThreadState;
bool wasPaused = state == EmuThreadState::PAUSED;
if (state == EmuThreadState::RUNNING || (emuThread.joinable() && !wasPaused)) return;
emuThreadState = EmuThreadState::RUNNING;
if (!wasPaused) { ctx->ThreadStart(); emuThread = std::thread(&EmuThreadFunc); }
```
Enum no master: 0 DISABLED, 1 RUNNING, 2 PAUSE_REQUESTED, 3 PAUSED, 4 QUIT_REQUESTED, 5 STOPPED.

### O nightly do buildbot já tem a correção

`https://buildbot.libretro.com/nightly/android/latest/<abi>/ppsspp_libretro_android.so.zip`,
`Last-Modified: 2026-10-01`, `.so` de 2026-09-28. Build ID arm64 `ffe5fae4fff2f698e4e76177d35289fed23a158a`.
md5: arm64 `dc55e0da69643611c4d5c1c7da9642bb`, v7a `192cb9a6a1872d56235d31d7da0b18a0`, x86_64
`e1aed1bcaa2d43f4fab2ddc31c0a161c`, x86 `5a7b1f353abf1ed9c22f4589f317bb72`. **Não embute string de
versão** (o atual embute `v1.19.3-987-geab2cc85bc`) — a prova é a desmontagem.

`llvm-objdump -d --no-show-raw-insn --start-address=0x5f8630 --stop-address=0x5f9014` (arm64,
`retro_run` em `0x5f8630`):
```
5f8938: ldar w9; cmp w9,#3; b.eq -> swap+return     ; PAUSED
5f8944: ldar w9; cmp w9,#2; b.ne 5f89f8             ; PAUSE_REQUESTED -> swap+return (821c5b4534)
5f89f8: ldar; cmp #1; b.eq 5f8a80                   ; != RUNNING -> EmuThreadStart inline
5f8a04: ldar; cmp #1; b.eq 5f8a80                   ;   state == RUNNING -> return
5f8a10: cmp w9,#3; b.eq 5f8a24                      ;   wasPaused pula o guard
5f8a1c: ldr x10,[x19,#0xd0]; cbnz x10, 5f8a80       ;   joinable() && !wasPaused -> return (72bd0fa4d3/b7d4a54a45)
5f8a24: mov w10,#1; stlr w10,[x8]                   ;   state = RUNNING (c42a3f070a)
5f8a60: bl <std::thread ctor>; ldr x8,[x19,#0xd0]; cbnz x8, 5f8ab4 -> terminate
```
O `bl terminate` continua no código (é o `operator=` do libc++), mas ficou inalcançável: o mesmo campo
`+0xd0` foi testado em `5f8a1c` pela mesma thread, e só ela escreve nele.

Compatibilidade do nightly conferida:
- **minSdk 21**: imports novos (`pthread_attr_*`, `setbuf`, `sleep`, `fesetround`, `truncf`,
  `mbstowcs`…) todos presentes nos stubs `sysroot/usr/lib/<triple>/21/lib{c,m,dl,log,android,EGL,GLESv2}.so`
  do NDK 29. Únicos ausentes: `ZSTD_trace_*`, que são **weak** e já eram importados pelo atual.
  `DT_NEEDED` perdeu `libz`/`libOpenSLES`; mantém `libandroid`.
- **VFS**: o master pede versão 2 (era 1); o LibretroDroid serve `SUPPORTED_VERSION = 2`
  (`vfs/vfs.h:36`) e devolve `true` sem olhar o pedido. `File::InitLibretroVFS` só usa `mkdir` do
  frontend com versão ≥ 3, e `path_vfs_init` (libretro-common) só usa `stat` com ≥ 3 — ok.
- **Core options** que o `GameSystem.kt` passa (`ppsspp_frame_duplication`, `ppsspp_auto_frameskip`,
  `ppsspp_frameskip`, `ppsspp_cpu_core` = `JIT`/`IR JIT`/`Interpreter`, `ppsspp_internal_resolution`,
  `ppsspp_texture_scaling_level`): todas as strings presentes no `.so` novo.

### Bloqueio: o nightly muda onde ficam as fontes do PSP (`flash0`)

`78ef1eae82` (2026-08-23) trocou a montagem de `flash0:`. Na base, no Android, era
`VFSFileSystem("flash0")` sobre `g_VFS.Register("", DirectoryReader(retro_base_dir))` =
`<system>/PPSSPP/flash0` — **onde o `PPSSPPAssetsManager` descompacta o `ppsspp.zip` 1.15**. No
master é `DirectoryFileSystem(g_Config.nandRootDirectory / "flash0")`, com
`nandRootDirectory = GetSysDirectory(DIRECTORY_NAND)` = `<memStickDirectory>/PSP/NAND`, e
`memStickDirectory = retro_save_dir` (diretório de saves do Lemuroid). Quem preenche o NAND é o
`AutoInstallFirmwareFromDisc()` (`ad06cbe2c4`, 2026-09-10) a partir do updater do disco — ISO
sem `PSP_GAME/../UPDATE` (dump "trimmed", muitos CSO) fica **sem fontes de sistema** (`sceFont` lê
`flash0:/font/*.pgf`). É regressão visível (texto some em jogo) que o dump atual não tem.

Além disso o salto é de 11 meses de master em desenvolvimento ativo (HLE `039ffc682d`,
`5c80533ee3`; shutdown `1c7f393e2b`, `e2ab84087e`), sem release estável no meio.

### Hipóteses descartadas nesta etapa

- **"Basta trocar pelo nightly"**: corrige o crash (provado acima), mas traz a mudança de `flash0`
  e não pode ser validado com a instrumentação do item 3 (binário do buildbot não se instrumenta).

### Decisão (dono, 2026-10-05): **B**

### Opções que estavam na mesa

- **A — nightly + adaptar o app**: trocar os 8 `.so`, e fazer o `PPSSPPAssetsManager` também
  semear `<saves>/PSP/NAND/flash0/font` (ou copiar de `<system>/PPSSPP/flash0`) quando ausente.
  Exige teste de regressão amplo de PSP (boot, fontes, saves antigos carregando).
- **B — core da base + patch mínimo**: compilar `eab2cc85bc` com `EmuThreadStart`/`EmuThreadPause`
  no formato do master (tabela acima), 4 ABIs, NDK. Mantém todo o resto idêntico ao que está em
  produção e permite a reprodução instrumentada do item 3. Contra: core compilado à mão (pitfall 6b
  — conferir que não embute caminho absoluto de fonte; usar `-ffile-prefix-map`).

## Execução (B) — 2026-10-05

### O binário de produção não é do buildbot

As strings do `.so` atual levam `/home/swordfish/workspaces/libretro/libretro-super/libretro-ppsspp/libretro/jni/../../Core/…`
(294 ocorrências) e o `.comment` é `clang 19.0.1 (13624864, r530567e)` = **NDK 27.3.13750724**. Ou
seja: build do Swordfish90 (LemuroidCores upstream) por **ndk-build** em `libretro/jni`, não pelo
template CMake do buildbot (`android-cmake.yml`). O core já embute caminho absoluto desde sempre — o
`verifyFlycastCore` só olha Flycast.

### Receita reproduzida

- Fonte: `C:/projects/lemuroid/ppsspp_src` (fora do repo), `git checkout eab2cc85bca530d47873c2015457cfa754a0115d`
  + `git submodule update --init --recursive` (todos os submódulos no commit gravado, conferido com
  `git submodule status` sem `+`/`-`).
- Patch: só `libretro/libretro.cpp::EmuThreadStart` (ver `lemuroid-cores/lemuroid_core_ppsspp/patches/`).
  `EmuThreadPause`/`EmuThreadStop` ficam como estão: com o `Start` gravando `RUNNING` direto, o estado
  transitório que fazia o `Pause` virar no-op deixa de existir.
- Versão fixada em `git-version.cpp` (`PPSSPP_GIT_VERSION_NO_UPDATE 1`):
  `v1.19.3-987-geab2cc85bc-lemuroid1` — distingue o core corrigido num tombstone/log.
- Comando (por ABI), de `libretro/jni`:
  ```
  D:/DevCaches/Android/Sdk/ndk/27.3.13750724/ndk-build.cmd -j16 APP_ABI=<abi> \
    "APP_CPPFLAGS+=-fexceptions -frtti" \
    "APP_CFLAGS+=-ffile-prefix-map=C:/projects/lemuroid/ppsspp_src/libretro/jni/../..=ppsspp"
  ```
  **Sem `-fexceptions -frtti` não compila**: `LuaContext.cpp:105: cannot use 'try' with exceptions
  disabled` (e `sceHttp.h` usa `dynamic_cast`). O `.so` de produção tem `.gcc_except_table`, então
  o build original também ligava exceções. O `Application.mk` só tem o obsoleto
  `APP_GNUSTL_CPP_FEATURES`.

### Segundo caminho para o mesmo `terminate` (achado ao portar)

`retro_serialize`/`retro_unserialize` chamados com o estado em `START_REQUESTED` (deixado pelo save
anterior): `EmuThreadPause` vira no-op (≠ `RUNNING`), o save roda **com a thread de emulação viva**, e
o `EmuThreadStart` do fim vê `wasPaused == false` e recria a thread → `terminate` dentro do próprio
`retro_serialize`. Quick save repetido rápido cai aqui. O patch fecha os dois caminhos.

### Validação em aparelho — reprodução determinística (SM-A127M, Android 13, arm64)

Instrumentação: `sleep_ms(50, "libretro-paused")` no lugar de `sleep_ms(1, …)` no ramo `PAUSED`
de `EmuThreadFunc` — a thread de emulação demora a consumir o estado deixado pelo save. Dois `.so`
arm64 com essa instrumentação, um sem e um com o patch, cada um empacotado num
`assembleFreeBundleDebug` (o core vem do `nativeLibraryDir`; `files/cores/` **não** é consultado
quando o APK o traz). Jogo: *Lumines - Puzzle Fusion (USA)* empurrado por `adb push` para
`/sdcard/Android/data/app.retrogamesystem.debug/files/roms/psp/`. Roteiro: abrir → jogar ~20 s →
voltar (grava autosave: `Stored autosave file with size: 41943040`) → reabrir (restaura o autosave
logo após o primeiro frame).

| Core | Resultado ao reabrir |
|------|----------------------|
| instrumentado **sem** patch | `Fatal signal 6 (SIGABRT)`, `Abort message: 'terminating'`, `#04 std::terminate()+36`, `#05 retro_run+1056` — **mesmo frame e mesmo offset dos tombstones de produção** |
| instrumentado **com** patch | 3 ciclos abrir/restaurar/jogar/salvar, `Fatal signal` = 0; o jogo segue do estado salvo (`TIME 1:14`, não da tela de título) |

Binário final (sem instrumentação), arm64: md5 `1526855f4da0713e0c05cde8e807d62e`; mesmos
`NEEDED`, `SONAME` (`libretro.so`) e exports `retro_*` do de produção; +1,2% de tamanho; zero
ocorrência de `C:/`, `/home/`, `/Users/`. Desmontagem do `EmuThreadStart` inline em `retro_run`
(enum da base: 1 START_REQUESTED, 2 RUNNING, 4 PAUSED): `RUNNING → return`; `≠ PAUSED &&
handle(+0xc10) ≠ 0 → return`; `stlr 2`; `PAUSED → não cria thread`.

### Fora de escopo (registrado, não feito)

- `retro_unserialize` adiado (`!gpu`) não grava `unserialize_size` — corrigido no upstream em
  `b4eb292e34`. O Lemuroid só restaura estado depois do primeiro frame (`gpu` já existe), então não
  passa por esse caminho.
- **Jogo com analógico morre no Android ≤ 7.1** (`kotlinx.datetime.Instant` do padkit → `java.time`),
  visto ao tentar validar o x86_64 no `lemu_api25_2gb` — registro próprio em
  `2026-10-05-padkit-kotlinx-datetime-java-time-android-7.md`.

### Outras ABIs

| ABI | md5 | Validação |
|-----|-----|-----------|
| arm64-v8a | `1526855f4da0713e0c05cde8e807d62e` | reprodução determinística + 2 ciclos com o binário final (SM-A127M); rebuild bit-a-bit idêntico |
| armeabi-v7a | `c828161e17301d899f008c336ebe6685` | 2 ciclos restore/save no SM-A127M com `adb install --abi armeabi-v7a` (processo 32 bits) |
| x86 | `100409e111dc4624e2b5190f7626fdfc` | só estática (NEEDED, exports, sem TEXTREL) |
| x86_64 | `862ee23295f5f6f832522c030d6dc712` | carrega e faz `retro_load_game` no AVD API 25; gameplay bloqueado pelo bug do padkit acima |

x86_64 precisou de `"APP_LDFLAGS+=-Wl,-Bsymbolic"` (o ffmpeg pré-compilado do submódulo não é PIC:
`R_X86_64_PC32 … ff_pw_8`) — o `CMakeLists.txt:2489` do upstream faz o mesmo só para Android x86_64.
O x86_64 de produção anterior era outro binário (buildbot CMake, `/builds/libretro/ppsspp`, sem
string de versão) e não exportava os `retro_vfs_*_impl`; agora as 4 ABIs saem da mesma fonte.
x86/x86_64 não estão nos splits distribuídos — só valem para AVD e o fallback de download.

### Publicação

`lemuroid-cores` `55d29c2` (push em `main`) → tag anotada `1.21.0` (push; o raw da tag já serve o
`.so` novo, `Content-Length: 22588760`) → `CoreDownloader.CORES_VERSION = "1.21.0"` → ponteiro do
submódulo, commit `411fe17` no Lemuroid (sem push). `verifyBundledCores` e `verifyCoresPublished`
passam.

### Próximo passo

1. Release com o `411fe17` (versionCode já está em 255 / 1.17.24 no working tree, não commitado).
2. Na telemetria, depois do release: `libppsspp_libretro_android.so` + `terminating` em versão
   ≥ 1.17.24 = o patch não cobriu algum caminho — comparar o frame com `retro_run+1056`.
3. Sem reincidência → mover para `done`.
