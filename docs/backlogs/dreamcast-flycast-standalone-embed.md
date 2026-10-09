# Backlog: Dreamcast funcional via motor Flycast standalone (embutido)

> **⚠️ OBSOLETO (2026-07-07):** o Dreamcast passou a funcionar pelo caminho
> libretro normal. A causa-raiz de toda a cadeia de crashes abaixo era o `.so`
> do buildbot sem `libandroid.so` no DT_NEEDED (→ `/dev/ashmem` EACCES com
> targetSdk 35 → fastmem off → fallback do dynarec quebrado). Fix: patch de ELF
> via `patch_flycast_libandroid.py`. Detalhes em
> `docs/bugs/done/2026-07-07-dreamcast-crash-boot-ashmem-libandroid.md`.
> Este plano de embutir o standalone NÃO é mais necessário.

**Status:** obsoleto (superado pelo fix do core libretro)
**Sistema:** Dreamcast (funcionando desde 2026-07-07)
**Objetivo:** fazer o Dreamcast rodar de fato no Lemuroid embutindo o motor **standalone** do Flycast (`flyinghead/flycast`) como módulo/processo separado, no lugar de tentar consertar o caminho libretro.

---

## 1. Contexto / por que chegamos aqui

O Dreamcast (core **Flycast** via LibretroDroid) inicia e crasha. Foi feita uma investigação longa, com **logcat nativo + símbolos** (o core foi recompilado do fonte com símbolos), que isolou uma **cadeia de bugs independentes** — cada correção real expõe a próxima camada. Isto NÃO é "um bug só".

### Fatos confirmados do device (moto g86 5G, Android 16, serial ZY32LMNN9B)
- arm64-v8a, **páginas de 4KB**, **OpenGL ES 3.2**. Tudo normal/capaz.
- BIOS real presente e correto: `files/system/dc/dc_boot.bin` (2MB) + `dc_flash.bin` (128KB), baixados via `BiosDownloader` (HuggingFace `luistiagos/bios`).
- Os cores são empacotados no APK (jniLibs), não em `files/cores`.

### A cadeia de bugs (com evidência)
1. **Crash original = fastmem/dynarec do Flycast.** Backtrace nativo: `addrspace::write32` (SEGV_ACCERR) chamado de `[anon:.bss]` (código JIT). O modelo de *fast memory* (região virtual reservada + mirrors com `mprotect` + handler de SIGSEGV próprio) **bate numa página não-gravável** sob o frontend LibretroDroid.
   - **Não há opção libretro** de CPU/dynarec/fastmem (confirmado: 84 chaves `reicast_*`, nenhuma).
   - Todos os cores do app vêm do **buildbot nightly** (`buildbot.libretro.com/nightly/android/latest`), que compila do **upstream flyinghead** (`addrspace`), não do `libretro/flycast`. É uma **regressão entre nightlies** do Flycast.
2. **Recompilando do fonte com fastmem desligado** (`settings.dynarec.disable_nvmem=1` forçado no Android, em `core/hw/mem/_vmem.cpp::_vmem_reserve`): o crash do `write32` **some** (log: `nvmem is DISABLED`). Mas surgem os próximos:
3. **Rejeição no dlopen — segmento W+X (RWE).** O linker do **NDK 29 (lld)** marca como RWE o segmento que contém `SH4_TCB[16MB]` e `ARM7_TCB[1MB]` (declarados `__attribute__((section(".text")))`). Android rejeita: *"has load segments that are both writable and executable"*. **Fix:** mover esses buffers pro `.bss` no Android (no `_WIN32`/plain branch). Verificado: segmentos viram `R E`/`RW`/`RW`, .so carrega.
4. **Crash atual = o dynarec ainda gera acessos fastmem mesmo com fastmem off.** Com `virt_ram_base=0`, o acesso fastmem falha → `signal_handler` (common.cpp) → `ngen_Rewrite` → `os_DebugBreak` (SIGTRAP). Símbolos confirmam. Ou seja, o **caminho de fallback "sem fastmem" do codebase `libretro/flycast` (mais antigo, `_vmem`) é mal-testado/inconsistente** neste cenário (codegen × mapeamento de memória discordam). Crasha ~2s após o boot (com `hle_bios=enabled` o REIOS dá boot e vai um pouco mais longe, mas crasha igual).

### Conclusão do diagnóstico
- O problema **não é** "o core não roda neste device". O **Flycast standalone roda Dreamcast no Android** (app da Play Store). O problema é a **camada libretro + LibretroDroid** (interação do fastmem/contexto GL com um frontend pré-compilado sem fonte).
- Consertar o caminho libretro vira *whack-a-mole* no codebase errado (o antigo `libretro/flycast`), ou exigiria mexer no **LibretroDroid** (AAR pré-compilado, **sem fonte** no projeto).

---

## 2. Por que a Opção A (embutir o standalone) e por que deve funcionar

`flyinghead/flycast` é **ao mesmo tempo** o app standalone **e** a fonte do core libretro — eles **compartilham o mesmo `core/`**; o que muda é o **frontend** (`shell/`).

| | Core libretro (atual) | Standalone Android |
|---|---|---|
| Emulador | `core/` | `core/` (**o mesmo**) |
| Frontend | `core/libretro/libretro.cpp` | `shell/android-studio/` |
| Execução | LibretroDroid é dono do GL; chama `retro_run` quadro-a-quadro; core desenha num **FBO** | Recebe uma **Surface Android** (`rendinitNative(Surface,w,h)`), cria o **próprio EGL**, roda o **próprio loop** numa thread nativa |
| Entrada/Áudio/Menu | LibretroDroid + overlay Lemuroid | Próprios (`android_input.cpp`, áudio interno, GUI imgui) |
| Inicia jogo | `dlopen` + `retro_load_game` | `Intent ACTION_VIEW` + `setGameUri(path)` + `resume()` |

O standalone configura memória (fastmem) e GL **do jeito do Flycast**, no próprio processo/Activity → **sem a interação com o LibretroDroid** que causa os crashes. Por isso deve funcionar.

**Não existe "trocar só o runner do .so libretro":** os dois modelos de integração são incompatíveis. Acoplar = **embutir um segundo motor de emulação** só pro Dreamcast.

---

## 3. Plano por fases (Opção A)

### Fase 0 — Arquitetura (~0,5d)
Flycast vira módulo Android separado (`:flycast-engine`) em **processo isolado** (`android:process=":flycast"`), acionado por **Intent**. Isola GPLv2 (Flycast) da GPLv3 (Lemuroid) por comunicação via Intent (sem linkar), espelhando como o app já trata cores como `.so` separados.

### Fase 1 — Vendoring + build nativo (~1,5–2d, maior risco)
- Copiar do upstream: `core/`, `core/deps/`, `shell/android-studio/flycast/src/main/jni/` (`Android.cpp`, `android_input.cpp`…) e o módulo Java `com.flycast.emulator` (`NativeGLActivity`, `BaseGLActivity`, `Emulator`, `emu/JNIdc`, `InputDeviceManager`, `config/`, `periph/`, `AndroidStorage`, `FileBrowser`).
- Build via **CMake** (`externalNativeBuild` + `CMakeLists.txt` — diferente do `jni/Android.mk` do libretro).
- Reaproveitar os ajustes de NDK 29 já descobertos se aparecerem (PAGE_SIZE etc.; o standalone provavelmente já builda limpo por ser mantido).
- ABIs: `arm64-v8a` primeiro.
- Saída: módulo que gera `libflycast.so` + classes Java.

### Fase 2 — Activity de emulação DC (~0,5–1d)
Reusar a `NativeGLActivity` do Flycast quase como está (cria `SurfaceView`, chama `rendinitNative`, `setGameUri`, `resume/pause/stop`, input/áudio próprios). Declarar no manifest com `android:process=":flycast"` + `intent-filter` ACTION_VIEW.

### Fase 3 — Bifurcação do launch (~1d)
- Ponto exato: `GameLauncher.launchGameAsync` → linha do `BaseGameActivity.launchGame(...)` (`lemuroid-app/.../app/shared/game/GameLauncher.kt`).
- `if (system.id == SystemID.DREAMCAST)` → `Intent(ACTION_VIEW, Uri(game.fileUri))` pra `NativeGLActivity` (processo `:flycast`) + extras (dirs BIOS/saves/states) + `startActivity`; senão segue o fluxo atual.
- Retorno/estatística: `GameLaunchTaskHandler.handleGameStart` já roda antes; ligar o handle de resultado p/ última-jogada.
- ⚠️ Mesmo APK (mesmo em processo separado): `file://` entre activities é ok. Se virar app separado (Opção B), usar `content://`.

### Fase 4 — Diretórios BIOS/saves/states/VMU (~1–1,5d)
- Flycast usa "home dir" próprio (`data/dc/dc_boot.bin`, `data/dc/dc_flash.bin`). O Lemuroid já tem BIOS em `DirectoriesManager.getSystemDirectory()/dc/` (via `BiosManager`/`BiosDownloader`).
- Passar em `JNIdc.initEnvironment(filesDir, homeDir, locale)` um `homeDir` apontando pros BIOS já baixados; decidir local de saves/states (na v1, aceitar que o Flycast gerencie os próprios).

### Fase 5 — Controles (~0,5d v1)
- **v1:** usar os controles do **próprio Flycast** (touch overlay + gamepad via `InputDeviceManager`). Zero trabalho de input; UX diferente do resto do app.
- v2 (opcional, +2d): rotear pad/overlay do Lemuroid → `InputDeviceManager` JNI.

### Fase 6 — Save state / menu / ciclo de vida (~0,5–1d)
- v1: usar a GUI do Flycast (imgui: `guiOpenSettings`) p/ save/load/opções. Integrar com o menu do Lemuroid fica pra v2.
- Rotação/voltar/salvar-ao-sair: a Activity do Flycast já trata.

### Fase 7 — Licença/empacotamento (~0,5d)
Módulo/processo separado, incluir licença GPLv2 + créditos, sem linkar no app principal.

---

## 4. Resumo

| | |
|---|---|
| **Esforço total v1** | **~6–8 dias** focados |
| **Risco** | Médio (maior na Fase 1: build nativo) |
| **Resultado provável** | Dreamcast **funcionando** (motor comprovado da Play Store) |
| **Custo de UX** | Menu/controles do Flycast no DC (não os do Lemuroid) na v1 |
| **Licença** | OK se processo/módulo separado (GPLv2 isolada da GPLv3) |

## 5. 🔑 Prova de conceito barata (FAZER ANTES de investir os dias, ~30 min)
Instalar o **app Flycast standalone** (Play Store/APK) no device e abrir o `GTA2 (USA).chd` nele (ACTION_VIEW). Se rodar liso (esperado), **confirma empiricamente** que o motor roda neste aparelho e que a Opção A vale o investimento. Se NÃO rodar, reavaliar **antes** de gastar os dias (significaria que o problema não é o LibretroDroid, e a Opção A não ajudaria).

---

## 6. Estado do working tree quando isto foi escrito (limpar ao retomar)
Ficaram mudanças experimentais (Dreamcast NÃO funciona com elas — decidir reverter):
- `retrograde-app-shared/.../GameSystem.kt`: bloco DREAMCAST com `reicast_hle_bios` alterado p/ `"enabled"` (experimento).
- `lemuroid-cores/lemuroid_core_flycast/.../arm64-v8a/` e `bundled-cores/.../arm64-v8a/`: `libflycast_libretro_android.so` **substituído pelo build patchado** (carrega mas crasha). Backup dos originais em `_flycast_backup_20260531_131454/`.
- Clone do fonte com patches em `e:/projects/lemuroid/flycast_src` (libretro/flycast + patches: disable_nvmem, PAGE_SIZE, legacy-C, SHORT_COMMANDS, TCB→.bss). **Não é o codebase recomendado** (é o antigo); a Opção A usa o **upstream flyinghead**.
- Para um app estável agora (sem DC funcional): reverter o `.so` do Flycast pro original do backup e o `hle_bios`, OU esconder o Dreamcast da lista. Os outros ~35 sistemas não são afetados (o `.so` do DC só carrega ao abrir um jogo DC).

## 7. Referências
- Upstream (motor standalone): https://github.com/flyinghead/flycast — `shell/android-studio/`, `core/`, `core/libretro/`.
- JNI do standalone: `JNIdc.java` (`initEnvironment`, `setGameUri`, `rendinitNative(Surface,w,h)`, `resume/pause/stop`, `guiOpenSettings`).
- Buildbot (core libretro atual): `https://buildbot.libretro.com/nightly/android/latest/<abi>/flycast_libretro_android.so.zip`.
