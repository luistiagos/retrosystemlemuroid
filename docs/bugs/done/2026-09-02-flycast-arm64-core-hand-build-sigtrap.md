# [BUG] Dreamcast crasha com SIGTRAP na GLThread — core arm64 empacotado era um build local, não o do buildbot

**Data:** 2026-09-02
**Status:** Resolvido ✅ (correção no binário empacotado + guard de build; falta confirmação em aparelho)
**Severidade:** Alta (Dreamcast inoperante em **todo** aparelho arm64, ou seja, em todos os usuários reais)
**Branch:** version9
**Origem:** telemetria `retrogamesystem/native` — 15 eventos, errors 2969-2971, 2213-2219, 2174-2179
**Janela observada:** 2026-08-17 → 2026-09-01 (motorola moto g14, Android 14/16, app 1.17.10)

Desmembrado de [[2026-09-02-crashes-nativos-cores-citra-dolphin]], que segue aberto para os
outros dois cores.

---

## Sintoma

Jogo de Dreamcast morre com `signal 5 (SIGTRAP), code TRAP_BRKPT` na `GLThread`, sem
exceção Java (o processo `:game` some; o app "fecha sozinho"):

```
signal 5 (SIGTRAP), code TRAP_BRKPT — tid: GLThread 184
  #00  libflycast_libretro_android.so +0x1e54a0
  #01  libflycast_libretro_android.so +0x1e6124
  #02  libsigchain.so (art::SignalChain::Handler+1152)
  #04  libflycast_libretro_android.so +0x14af24
  #05  [anon:.bss]
```

A leitura do stack: os quadros **abaixo** do `SignalChain::Handler` são o contexto
interrompido — `[anon:.bss]` é o código gerado pelo dynarec. Ou seja, o código JIT
faltou memória, o sinal foi entregue, o handler próprio do Flycast rodou (#01, #00) e
**abortou de propósito** em vez de recuperar. É exatamente a assinatura
`signal_handler` → `ngen_Rewrite` → `os_DebugBreak` já descrita na investigação de junho
(`docs/backlogs/dreamcast-flycast-standalone-embed.md`, item 4).

## Causa-raiz

O `.so` de Flycast empacotado para **arm64-v8a** não era o core do buildbot libretro: era
o **build local** feito em junho a partir de `E:/projects/lemuroid/flycast_src` — o fork
antigo `libretro/flycast`, compilado com fastmem desligado, que a própria investigação de
junho já tinha identificado como o caminho quebrado.

Evidências no binário empacotado (md5 `ebb7891487f8ddf0ed589ea25be50d4f`):

| Sinal | Core empacotado (arm64) | Core correto (buildbot) |
|---|---|---|
| Tamanho | 6,58 MB | 23,26 MB |
| `SONAME` | `libretro.so` | `flycast_libretro.so` |
| `DT_NEEDED` | `libGLESv2, liblog, libc, libm, libdl` — **sem `libandroid.so`** | `libandroid, libm, libdl, libc` |
| Fonte embutida | `E:/projects/lemuroid/flycast_src/.../core/hw/mem/_vmem.cpp` | nenhuma (path relativo) |
| Codebase | `_vmem.cpp` + `vmem32.cpp` (fork antigo) | `addrspace::` (upstream flyinghead) |

Duas falhas somadas, e a segunda é a repetição literal do
[[2026-07-07-dreamcast-crash-boot-ashmem-libandroid]]:

1. **Binário errado.** Trocado apenas em `arm64-v8a`; `armeabi-v7a`, `x86` e `x86_64`
   continuaram com o core do buildbot correto (md5 idênticos aos do backup
   `_flycast_backup_20260531_131454/`). Por isso o defeito não aparecia em nenhum
   emulador x86 nem em TV box armv7 — só nos aparelhos reais.
2. **Sem o patch de `libandroid.so` no `DT_NEEDED`.** Mesmo que o build local fosse bom,
   `ASharedMemory_create` (símbolo weak) ficaria nulo → `open("/dev/ashmem")` → `EACCES`
   com `targetSdk 35` → fastmem off → caminho de fallback do dynarec, que é onde o
   `os_DebugBreak` mora.

O mais provável é que a troca tenha sido uma tentativa de mitigar
[[2026-07-08-dreamcast-subset-jogos-travam-9fps]] (cujo "próximo passo" era justamente
"testar um build de core diferente, reaplicando o patch `libandroid.so`") — o build
diferente foi para o lugar, o patch não.

## Correção

1. **Restaurado o core do buildbot em `arm64-v8a`**, nos dois módulos que empacotam o
   Flycast (`lemuroid_core_flycast` e `bundled-cores`), a partir de
   `_flycast_backup_20260531_131454/` — o binário já patchado que estava em uso quando o
   Dreamcast foi validado em 07/07. As 8 combinações (2 módulos × 4 ABIs) agora batem
   entre si e listam `libandroid.so` no `DT_NEEDED`.
   O binário ruim ficou preservado como
   `_flycast_backup_20260531_131454/BADBUILD_localsrc_arm64-v8a.so`.

2. **Guard de build** — [FlycastCoreVerifier.kt](../../../buildSrc/src/main/kotlin/FlycastCoreVerifier.kt)
   + task `verifyFlycastCore` em [lemuroid-app/build.gradle.kts](../../../lemuroid-app/build.gradle.kts),
   pendurada em `merge*NativeLibs`, `package*` e `bundle*`. Para cada `.so` de Flycast
   empacotado, o build **falha** se:
   - `libandroid.so` não estiver no `DT_NEEDED`; ou
   - o binário embutir um caminho de fonte absoluto (`X:/.../foo.cpp`) — os binários do
     buildbot não embutem nenhum, então um hit significa core compilado à mão.

   Leitura de ELF em Kotlin puro dentro do `buildSrc` (sem `lief`, sem binutils): exigir
   ferramenta extra na máquina de build seria mais um passo humano para esquecer.

3. Pitfall 6 do [CLAUDE.md](../../../CLAUDE.md) atualizado com o segundo modo de falha e
   com o guard.

## Validação

Feita no artefato, não em aparelho (o defeito é de empacotamento e é verificável no
binário):

- `DT_NEEDED` das 8 combinações módulo × ABI contém `libandroid.so`; `SONAME` uniforme
  `flycast_libretro.so`; arquiteturas ELF corretas (aarch64 / arm / x86 / x86_64).
- Varredura dos 102 arquivos `.so` arm64 empacotados (51 em `bundled-cores` + 51 nos módulos por core) procurando caminho de fonte
  absoluto: o Flycast era o **único** build local (citra, dolphin e os demais são
  binários de buildbot legítimos).
- `./gradlew :lemuroid-app:verifyFlycastCore` → BUILD SUCCESSFUL com o core restaurado.
- Reinserindo o binário ruim, a mesma task falha apontando as duas causas:
  ```
  - ...\arm64-v8a\libflycast_libretro_android.so: DT_NEEDED lacks libandroid.so
    (has [libGLESv2.so, liblog.so, libc.so, libm.so, libdl.so]).
    Run `python patch_flycast_libandroid.py` before building.
  - ...\arm64-v8a\libflycast_libretro_android.so: embeds the local build path
    "E:/projects/lemuroid/flycast_src/jni/../core/hw/holly/sb_mem.cpp", so this is a
    hand-compiled core, not the buildbot one.
  ```

**Falta:** rodar um jogo de Dreamcast num aparelho arm64 e confirmar no logcat
`[VMEM] Info: nvmem is enabled` com `BASE` ≠ 0 (mesmo critério do 07/07). Enquanto isso
não for feito, o fechamento é por evidência de binário.

> ⚠️ Os `.so` de Flycast **não estão versionados** no submodule `lemuroid-cores`
> (`git status` os mostra como `??`). A correção vale para builds feitos nesta máquina;
> o caminho de download do `CoreUpdaterImpl` (GitHub `luistiagos/libretrocores`) precisa
> receber o binário corrigido separadamente.

## Lição

1. **Empacotamento de core é código, e código sem teste regride.** O 07/07 terminou com
   uma instrução para um humano ("rodar o script antes de buildar"). Ela falhou na
   primeira oportunidade e ninguém percebeu por seis semanas, porque o resultado só
   aparece como sinal nativo no aparelho de outra pessoa. Instrução em documento não é
   guard; guard é o que quebra o build.
2. **Divergência entre ABIs é um cheiro forte.** Três ABIs iguais ao backup e uma
   diferente foi o que fechou o diagnóstico em minutos. Vale comparar ABIs entre si antes
   de olhar o conteúdo.
3. **Um experimento abandonado precisa ser desfeito, não deixado no lugar.** O build de
   `flycast_src` era legítimo como experimento de junho; virou defeito de produção ao
   ficar em `jniLibs/`.
4. `SIGTRAP`/`TRAP_BRKPT` **nunca** é corrupção de memória — é assert/`__builtin_trap`
   compilado no core. Quando aparece junto de `libsigchain` e `[anon:.bss]`, é o handler
   de sinal do próprio emulador desistindo de um fault do JIT.
