# [BUG] Crash nativo no core PSP (PPSSPP) — SIGABRT / `std::terminate()` dentro de `retro_run`

**Data:** 2026-09-18
**Status:** Resolvido ✅ (validado em device real, incluindo simulação controlada do crash)
**Severidade:** Alta (todo jogo de PSP pode abortar o processo `:game` a qualquer momento durante a partida, sem ação do usuário além de jogar)
**Branch:** version9
**Origem:** telemetria `retrogamesystem/native` (`libppsspp_libretro_android.so::reason=Native crash status=6`)
**Errors (serviço):** 6986, 6559, 6423, 6417, 6150 (5 ocorrências)
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
