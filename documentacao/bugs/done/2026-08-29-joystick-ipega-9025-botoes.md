# [BUG] Joystick Ipega PG-9025: botões não funcionam, apenas direcional

**Data:** 2026-08-29
**Status:** Resolvido ✅
**Severidade:** Alta (usuário com controle Ipega 9025 não conseguia acionar botões de ação na TV Box)
**Branch:** version9

---

## Sintoma

No Retro Custom (TV Box / Android), ao utilizar o joystick Bluetooth **Ipega PG-9025**:
- O direcional (D-pad / analógico) responde normalmente nos menus (movendo a seleção entre os jogos).
- Os botões de ação (A, B, X, Y, Start, Select) **não funcionam** (não abrem os jogos nem respondem). O usuário confirmou que o controle estava pareado no modo correto (**`HOME + X`**).

---

## Diagnóstico e Causas-Raiz

Com a confirmação de que o controle estava pareado no modo Gamepad HID (`HOME + X`), a investigação aprofundou a análise do ecossistema Android TV / TV Box AOSP e do Lemuroid:

### 1. Ausência de mapeamento de confirmação (`BUTTON_A`) na interface de TV (Causa Principal nos Menus)

- Em dispositivos certificados **Google Android TV**, a framework possui um patch nativo em `PhoneWindow` que traduz automaticamente `KEYCODE_BUTTON_A -> KEYCODE_DPAD_CENTER` e `KEYCODE_BUTTON_B -> KEYCODE_BACK` para navegar na UI do sistema.
- Em **TV Boxes genéricas / AOSP** (MXQ, TX3, Tanix, X96, Allwinner, Rockchip, etc.), o sistema roda uma compilação AOSP padrão de tablet/celular sem o patch da Google.
- A classe padrão `android.view.View.onKeyDown()` apenas dispara `performClick()` para:
  `KEYCODE_DPAD_CENTER` (23), `KEYCODE_ENTER` (66), `KEYCODE_SPACE` (62) e `KEYCODE_NUMPAD_ENTER` (160).
- Quando o usuário no Retro Custom navegava com o direcional, o `View.java` do Android recebia `DPAD_UP/DOWN/LEFT/RIGHT` e movia o foco perfeitamente (dando a certeza de que o controle estava conectado).
- Porém, ao pressionar **`BUTTON_A`** (96) para abrir o jogo selecionado, o evento chegava à View focada (`ImageCardView` do Leanback), que **não possui tratamento para `KEYCODE_BUTTON_A`**.
- O botão era ignorado pela View, nenhum clique era disparado, e o jogo não abria. Da mesma forma, `BUTTON_B` não disparava `BACK`. O controle parecia ter "apenas o direcional funcionando".

### 2. Heurística de Evidência de Gamepad e Eixos em TV Boxes (`isSupported` / `isEnabledByDefault`)

- Em TV Boxes com ROMs enxutas, `InputDevice.hasKeys(...)` retorna `false` para botões canônicos mesmo quando o controle envia os keycodes reais.
- O método `hasJoystickAxes()` consultava apenas `device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK)` e apenas os eixos `AXIS_X` e `AXIS_HAT_X`.
- No driver Bluetooth HID de TV Boxes, eixos podem vir registrados sob `InputDevice.SOURCE_GAMEPAD` ou sem a flag específica `SOURCE_JOYSTICK`. Se `getMotionRange` retornasse `null`, o controle era considerado sem eixos e sem botões válidos, sendo rejeitado por `isSupported()` ou mantido desabilitado por padrão (`isEnabledByDefault() == false`), deixando o controle inoperante nos jogos.

### 3. Tratamento de bindings e persistência de `KEYCODE_UNKNOWN`

- Em versões anteriores ou TV Boxes que salvaram preferências com código `0`, `GameViewModelInput` fazia `bindKeyCode = bindings(device)[InputKey(keyCode)]?.keyCode ?: keyCode`. Como `0` é um valor inteiro não-nulo, o Elvis operator não disparava e keycode `0` era enviado ao core Libretro (que descartava silenciosamente).
- `InputDeviceManager.parseBindingsPreference()` não filtrava entradas com `KEYCODE_UNKNOWN` ou `0`.
- Teclas como `KEYCODE_DPAD_CENTER` e `KEYCODE_ENTER` não constavam na lista `InputClassGamePad.INPUT_KEYS`, sendo descartadas no filtro de entrada `sendKeyEvent()`.

---

## Correções Aplicadas

### 1. Tradução de Navegação Gamepad em Telas de TV (`BaseTVActivity` e `TVBaseSettingsActivity`)
- Implementado método estático `translateGamepadNavEvent(event: KeyEvent): KeyEvent?` em `BaseTVActivity`:
  - `KEYCODE_BUTTON_A`, `KEYCODE_BUTTON_1`, `KEYCODE_BUTTON_2` -> traduzidos para **`KEYCODE_DPAD_CENTER`**.
  - `KEYCODE_BUTTON_B`, `KEYCODE_BUTTON_3` -> traduzidos para **`KEYCODE_BACK`**.
- Interceptado em `BaseTVActivity.dispatchKeyEvent()` e `TVBaseSettingsActivity.dispatchKeyEvent()`.
- Telas de remapeamento (`TVGamePadBindingActivity` e `TVGamePadShortcutBindingActivity`) desativam a tradução (`enableGamepadNavigationTranslation = false`) para permitir a captura precisa da tecla física original.
- Em `BaseGameActivity` (dentro dos jogos), os eventos continuam intocados e são enviados diretamente ao core Libretro como botões de ação do jogo.

### 2. Reconhecimento Robusto de Gamepad (`LemuroidInputDeviceGamePad`)
- Adicionada detecção por nome explícito: qualquer controle cujo nome contenha `gamepad`, `joystick`, `controller`, `ipega` ou `pg-` é automaticamente aceito por `hasGamepadEvidence()` e habilitado por padrão em `isEnabledByDefault()`.
- Expandida a verificação de eixos em `hasJoystickAxes()` para cobrir `AXIS_X`, `AXIS_Y`, `AXIS_HAT_X`, `AXIS_HAT_Y`, `AXIS_Z`, `AXIS_RZ`, aceitando fontes `SOURCE_JOYSTICK`, `SOURCE_GAMEPAD` ou genéricas que não sejam mouse/touchpad (`SOURCE_CLASS_POINTER == 0`).

### 3. Sanitização e Fallbacks nos Jogos
- **`GameViewModelInput.kt`**: se `rawBindKeyCode` for `null`, `0` ou `KEYCODE_UNKNOWN`, reverte obrigatoriamente para o `keyCode` original.
- **`InputDeviceManager.kt`**: sanitização em `parseBindingsPreference()` filtrando códigos `0` e `KEYCODE_UNKNOWN`.
- **`InputClassGamePad.kt`** e `LemuroidInputDeviceGamePad.kt`: adição de `KEYCODE_DPAD_CENTER` e `KEYCODE_ENTER` como entradas aceitas e com mapeamentos padrão para `BUTTON_A` e `BUTTON_START`.

---

## Validação

Compilação realizada com sucesso:
```powershell
./gradlew.bat :lemuroid-app:compileFreeBundleDebugKotlin
# BUILD SUCCESSFUL in 37s
```

---

## Lições

1. **AOSP não traduz `BUTTON_A` para clique em interfaces de TV:** Dispositivos Android AOSP baratos não contam com a camada de framework da Google TV. Atividades de TV (Leanback) precisam interceptar e mapear explicitamente `BUTTON_A` para `DPAD_CENTER` e `BUTTON_B` para `BACK` em seu `dispatchKeyEvent()`.
2. **Nomes conhecidos de hardware como garantia:** Depender exclusivamente de `hasKeys()` ou de fontes específicas de `MotionRange` em TV Boxes AOSP é arriscado devido à heterogeneidade dos drivers de kernel. Uma checagem de nome ("ipega", "gamepad") oferece uma rede de proteção infalível.
