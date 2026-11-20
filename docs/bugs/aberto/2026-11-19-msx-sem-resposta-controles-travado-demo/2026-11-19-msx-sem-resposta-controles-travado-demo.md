# [BUG] MSX / MSX2 (fMSX): jogos abrem na demo sem responder a nenhum comando (Porta 0 em `RETRO_DEVICE_NONE`)

**Data:** 2026-11-19
**Severidade:** alta — todo o sistema MSX e MSX2 fica injogável sem resposta na Porta 0 (toque e controle físico)
**Complexidade:** media — mapeamento de controlador/descriptor no `ControllerConfigs.kt` + layout touch dedicado no `lemuroid-touchinput`
**Branch:** version10
**Origem:** triagem de suporte WhatsApp (`+55 13 99612-3908`, jogos *Eggerland*, *King's Valley* e *Castle Excellent* no MSX)

---

## Sintoma

Cliente relatou no suporte via WhatsApp que jogos de MSX (como *Eggerland*, e por consequência *King's Valley* e *Castle Excellent*) abrem e ficam travados na tela de demo/título sem responder a nenhum comando — diferente da versão de NES (*Adventures of Lolo*), que responde normalmente.

---

## Evidencia

1. **Catálogo (`lemuroid-app/src/main/assets/catalog_manifest.txt`):**
   - `msx/Castle Excellent (Japan) (Alt 1).rom` (linha 32161)
   - `msx/Meikyuu Shinwa (Japan) (Alt 1).rom` (*Eggerland 2*, linha 32275) e `msx/Eggerland Mystery (Japan).rom` (linha 32277)
   - `msx/King's Valley (Japan, Europe) (Alt 1).rom` (linha 32473) e `msx/King's Valley II (Japan, Europe).rom` (linha 32481)
2. **BIOS (`bios/` vs `BiosManager.kt:255-289`):** todas as ROMs exigidas (`MSX.ROM`, `MSX2.ROM`, `MSX2EXT.ROM`, `MSX2P.ROM`, `MSX2PEXT.ROM`, `MSXDOS2.ROM`) conferem com os MD5s esperados, explicando por que os jogos carregam e exibem a demo.
3. **Configuração do sistema (`GameSystem.kt:1998-2049` e `ControllerConfigs.kt:393-404`):**
   - `SystemID.MSX` e `SystemID.MSX2` usam `CoreID.FMSX` (`libfmsx_libretro_android.so`) com `ControllerConfigs.MSX` nas portas `0` e `1`.
   - `ControllerConfigs.MSX` tinha `libretroId = null` e `libretroDescriptor = null`, e reutilizava `TouchControllerID.SMS` (`SMS.kt:22-64`, apenas D-pad, `1`, `2`, `Start` e `Menu`).
4. **Fluxo no frontend (`GameViewModelInput.kt:257-279` e `LibretroDroid` `libretrodroid.cpp:148-150`):**
   - `findControllerId` retorna `null` quando `libretroId` e `libretroDescriptor` são `null`, logo `setControllerType(0, ...)` nunca é chamado e o core nunca recebe `retro_set_controller_port_device(0, ...)`.
5. **Binário do core (`lemuroid-cores/bundled-cores/src/main/jniLibs/arm64-v8a/libfmsx_libretro_android.so`):**
   - Em `.data.rel.ro` (`0x33168..0x331e0`), o core registra para a Porta 0: `0x101` (`"Joystick + Emulated Keyboard"`), `0x201` (`"Emulated Keyboard"`), `0x301` (`"Custom Keyboard"`), `0x3` (`"Keyboard"`) e `0x1` (`"Joystick"`); e para a Porta 1: `0x1` (`"Joystick"`).
   - A variável de dispositivo da Porta 0 (`0x3ac88`) fica na `.bss` (inicializada em `0` = `RETRO_DEVICE_NONE`) e só é escrita por `retro_set_controller_port_device` (`0x134a4`, quando `port == 0`).
   - Em `retro_run` (`0x13748..0x137e0`), quando `[0x3ac88] == 0`, o core salta direto para `0x13a10` e ignora toda entrada da Porta 0.
   - Os descritores de `0x101` (`"Joystick + Emulated Keyboard"`, tabela `0x354e8` e `0x331f0..0x33378`) mapeiam D-Pad (Stick), `A` (Fire A), `B` (Fire B), `Y` (`Space`), `X` (`F3`), `START` (`F1`), `SELECT` (`F2`), `L` (`F4`), `R` (`F5`), `L2` (`Graph`), `R2` (`Ctrl`), `L3` (`Enter`) e `R3` (`Escape`).

---

## Causa-raiz

`ControllerConfigs.MSX` não definia `libretroDescriptor` nem `libretroId`, fazendo com que `GameViewModelInput.updateControllers` nunca chamasse `setControllerType(0, ...)`. Sem essa chamada, `libfmsx_libretro_android.so` mantinha a variável do dispositivo da Porta 0 (`0x3ac88`) no valor inicial `0` (`RETRO_DEVICE_NONE`) da `.bss`, descartando 100% dos comandos da Porta 0 em `retro_run`. Adicionalmente, o layout touch reutilizado (`TouchControllerID.SMS`) não expunha `Space` (`Y`), `Enter` (`L3`) nem `Select`/`F2`–`F5`, necessários em vários jogos de MSX.

---

## Hipoteses descartadas

- **BIOS ausente ou MD5 divergente:** descartada — os arquivos em `bios/` batem 100% com `BiosManager.kt` e os jogos carregam normalmente até a tela de demo.
- **ROMs ausentes no catálogo:** descartada — *Castle Excellent*, *Eggerland Mystery*, *Eggerland 2* e *King's Valley I/II* estão presentes em `catalog_manifest.txt`.

---

## O que fazer

1. Definir `libretroDescriptor = "Joystick + Emulated Keyboard"` em `ControllerConfigs.MSX` para forçar `setControllerType(0, 0x101)` na inicialização do core fMSX.
2. Criar o layout touch `TouchControllerID.MSX` (`MSX.kt`) expondo D-Pad, Fire `A`/`B`, `SPC` (`Y` = Space), `F3` (`X`), `Start` (`F1`), `Select` (`F2`), `F4` (`L1`), `F5` (`R1`) e `ENT` (`THUMBL` = Enter).
3. Adicionar teste unitário cobrindo a configuração de controle de `SystemID.MSX` e `SystemID.MSX2`.

---

## Correcao

1. **`retrograde-app-shared/.../ControllerConfigs.kt` (`ControllerConfigs.MSX`):**
   - Definido `libretroDescriptor = "Joystick + Emulated Keyboard"` (id `0x101` no `fmsx`), forçando `GameViewModelInput.updateControllers` a chamar `setControllerType(0, 0x101)` e sair de `RETRO_DEVICE_NONE`.
   - Atualizado `touchControllerID` de `TouchControllerID.SMS` para `TouchControllerID.MSX`.
2. **`lemuroid-touchinput/.../layouts/MSX.kt` e `TouchControllerID.kt`:**
   - Criado o layout dedicado `MSXLeft` / `MSXRight` (`TouchControllerID.MSX`) com D-Pad, `A` (Fire A), `B` (Fire B), `SPC` (`Y` → Space), `F3` (`X`), `Start` (`F1`), `Select` (`F2`), `F4` (`L1`), `F5` (`R1`) e `ENT` (`THUMBL` → Enter).
3. **`lemuroid-app/src/test/.../MsxControllerConfigTest.kt`:**
   - Adicionado teste de regressão validando `libretroDescriptor = "Joystick + Emulated Keyboard"` e `TouchControllerID.MSX` para `SystemID.MSX` e `SystemID.MSX2`.

---

## Validacao

- **Lint (`ktlintCheck`):** `:lemuroid-touchinput:ktlintCheck`, `:retrograde-app-shared:ktlintCheck` e `:lemuroid-app:ktlintCheck` concluídos com sucesso (exit code 0).
- **Testes unitários (`:lemuroid-app:testFreeBundleDebugUnitTest`):** 17 suites, 102 testes executados, 0 falhas, 0 erros (1 teste pré-existente ignorado em `WriteAtomicallyTest`), incluindo `MsxControllerConfigTest` (1/1 passou).
- **E2E no aparelho/emulador:** pendente (nenhum dispositivo conectado em `adb devices` no momento da execução). Testar *Eggerland Mystery* / *Meikyuu Shinwa*, *King's Valley* e *Castle Excellent* no catálogo `msx` confirmando saída da tela de título/demo via toque (`SPC` / `Start` / `A` / `B` / `ENT`) e controle físico.

---

## Licao

Cores libretro que mantêm a porta 0 em `RETRO_DEVICE_NONE` até receberem `retro_set_controller_port_device` exigem `libretroDescriptor` explícito em `ControllerConfigs` (mesma lição do Amiga/PUAE, 3DO/Opera e PC-FX). Para computadores como o MSX, o descritor `"Joystick + Emulated Keyboard"` combinado a um layout touch com `Space`, `Enter` e teclas `F1`–`F5` evita telas de título e menus travados.
