# Fix: controles wireless genéricos não funcionam em TV Box (MXQ 4K)

> **Plano pronto para execução por um modelo menor.** Siga as edições na ordem, sem
> improvisar além do descrito. Cada edição tem o código ANTES e DEPOIS.

## Contexto

Cliente usa Lemuroid numa TV Box **MXQ 4K** com dois controles wireless genéricos
(clones de PS2 com dongle USB 2.4G, botão MODE, marcação "S2-G"). Os controles
**não funcionam** no app.

Já existe um fix anterior (documentado em `docs/bugs/done/correcoes-2026-04-18.md`)
que fez todas as camadas aceitarem `SOURCE_JOYSTICK` além de `SOURCE_GAMEPAD`.
Esse fix está aplicado e **não** deve ser refeito. O problema atual é outra camada.

## Diagnóstico (causa raiz)

`InputDevice.hasKeys(...)` depende dos arquivos `.kl` (key layout) da ROM Android.
Em TV boxes baratas (MXQ 4K = Android 6/7 AOSP de fabricante), o `.kl` genérico é
incompleto ou ausente, e `hasKeys(KEYCODE_BUTTON_A/B/X/Y)` retorna **false mesmo
quando o controle envia esses keycodes de verdade**.

O código atual confia cegamente no `hasKeys` em 3 lugares, e há um 4º problema de
roteamento de evento. Qualquer um deles sozinho mata o controle:

| # | Arquivo | Problema |
|---|---------|----------|
| 1 | `LemuroidInputDeviceGamePad.isSupported()` | Exige `hasKeys(A,B,X,Y)` todos true. Se falha, o device não entra em `getAllGamePads()` → nunca recebe porta → `port == null` em `GameViewModelInput.initializeGamePadKeysFlow` → **todos os eventos são descartados no jogo** (nos menus o Android nativo ainda navega, por isso o cliente vê o controle "funcionando no menu e morto no jogo"). |
| 2 | `LemuroidInputDeviceGamePad.isEnabledByDefault()` | Exige também `hasKeys(BUTTON_START)`. Mesmo se registrado, o controle fica **desabilitado por padrão** nas configurações — cliente não sabe que precisa habilitar. |
| 3 | `LemuroidInputDeviceGamePad.getDefaultBindingForKey()` | Toda tecla com `hasKeys == false` recebe binding padrão `KEYCODE_UNKNOWN`. Ou seja: mesmo com o device registrado e o evento chegando, o botão é traduzido para UNKNOWN e **morre antes de chegar ao core**. |
| 4 | `BaseGameActivity.dispatchKeyEvent()` | Só intercepta eventos cujo `event.source` tem bit GAMEPAD/JOYSTICK. Clones enviam o D-pad (e às vezes SELECT/START) pela interface de **teclado** do mesmo HID composto (`event.source == SOURCE_KEYBOARD`), então o evento cai na árvore de views (Compose captura para navegação de foco) em vez de ir para o emulador. |

Proteções existentes que DEVEM ser preservadas (não remover):
- `BLACKLISTED_DEVICES` em `InputDeviceManager` (`virtual-search`, `sunxi-ir-uinput`).
- `gamepadPriority()` em `InputDeviceManager` — garante que controle real (com eixos)
  ocupa a porta 0 e controle-lixo/controle remoto fica nas portas altas.
- O swap A↔B / X↔Y do `defaultOverride` em `getDefaultBindings()`.

---

## Edição 1 — `isSupported()` não pode depender só de `hasKeys`

**Arquivo:** `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/input/lemuroiddevice/LemuroidInputDeviceGamePad.kt`

Adicionar o import no topo (junto aos outros imports):

```kotlin
import android.view.MotionEvent
```

**ANTES** (método atual):

```kotlin
    override fun isSupported(): Boolean {
        val isGamepadSource =
            (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (device.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        return sequenceOf(
            isGamepadSource,
            device.supportsAllKeys(MINIMAL_SUPPORTED_KEYS),
            device.isVirtual.not(),
        ).all { it }
    }
```

**DEPOIS:**

```kotlin
    override fun isSupported(): Boolean {
        val isGamepadSource =
            (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (device.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        return sequenceOf(
            isGamepadSource,
            hasGamepadEvidence(),
            device.isVirtual.not(),
        ).all { it }
    }

    // hasKeys() depende dos arquivos .kl da ROM; em TV boxes baratas ele retorna
    // false para botões que o controle realmente envia. Aceitamos qualquer
    // evidência de gamepad em vez de exigir hasKeys(A,B,X,Y) completo.
    private fun hasGamepadEvidence(): Boolean {
        if (device.supportsAllKeys(MINIMAL_SUPPORTED_KEYS)) return true

        val anyButton =
            device.hasKeys(
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_START,
                KeyEvent.KEYCODE_BUTTON_SELECT,
                KeyEvent.KEYCODE_BUTTON_L1,
                KeyEvent.KEYCODE_BUTTON_R1,
            ).any { it }
        if (anyButton) return true

        return hasJoystickAxes()
    }

    // Eixos consultados especificamente na classe SOURCE_JOYSTICK para não
    // confundir com eixos de mouse/touch de air-mouses e controles remotos.
    private fun hasJoystickAxes(): Boolean {
        return sequenceOf(
            MotionEvent.AXIS_X,
            MotionEvent.AXIS_HAT_X,
        ).any { axis -> device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) != null }
    }
```

---

## Edição 2 — `isEnabledByDefault()` aceita controle com eixos reais

**Mesmo arquivo** (`LemuroidInputDeviceGamePad.kt`).

**ANTES:**

```kotlin
    override fun isEnabledByDefault(appContext: Context): Boolean {
        return device.supportsAllKeys(MINIMAL_KEYS_DEFAULT_ENABLED)
    }
```

**DEPOIS:**

```kotlin
    override fun isEnabledByDefault(appContext: Context): Boolean {
        // hasKeys mente em muitas TV boxes. Se o dispositivo expõe eixos reais de
        // joystick (analógico ou HAT do D-pad), é um controle de verdade — habilita.
        // Controles remotos de TV não expõem eixos SOURCE_JOYSTICK, então continuam
        // desabilitados por padrão a menos que passem no teste de teclas.
        return device.supportsAllKeys(MINIMAL_KEYS_DEFAULT_ENABLED) || hasJoystickAxes()
    }
```

---

## Edição 3 — binding padrão identidade (nunca mapear para UNKNOWN)

**Mesmo arquivo** (`LemuroidInputDeviceGamePad.kt`).

Motivo: se `hasKeys` mente, o binding padrão atual vira `KEYCODE_UNKNOWN` e o botão
morre mesmo com o evento chegando. Mapear a tecla para ela mesma é estritamente
melhor: se o controle não envia a tecla, o binding fica inerte; se envia, funciona.

**ANTES:**

```kotlin
    override fun getDefaultBindings(): Map<InputKey, RetroKey> {
        val allAvailableInputs =
            InputDeviceManager.OUTPUT_KEYS
                .associate {
                    InputKey(it.keyCode) to getDefaultBindingForKey(device, it)
                }

        val defaultOverride =
            bindingsOf(
                KeyEvent.KEYCODE_BUTTON_A to KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_B to KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_X to KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_Y to KeyEvent.KEYCODE_BUTTON_X,
            )

        return allAvailableInputs + defaultOverride
    }

    private fun getDefaultBindingForKey(
        device: InputDevice,
        it: RetroKey,
    ): RetroKey {
        val defaultBinding =
            if (device.hasKeys(it.keyCode).first()) {
                RetroKey(it.keyCode)
            } else {
                RetroKey(KeyEvent.KEYCODE_UNKNOWN)
            }
        return defaultBinding
    }
```

**DEPOIS** (remover `getDefaultBindingForKey` por completo):

```kotlin
    override fun getDefaultBindings(): Map<InputKey, RetroKey> {
        // Binding identidade para todas as teclas de saída. Não consultamos
        // hasKeys(): em TV boxes baratas ele retorna false para teclas que o
        // controle envia, o que mapeava botões reais para KEYCODE_UNKNOWN.
        val allAvailableInputs =
            InputDeviceManager.OUTPUT_KEYS
                .associate { InputKey(it.keyCode) to RetroKey(it.keyCode) }

        val defaultOverride =
            bindingsOf(
                KeyEvent.KEYCODE_BUTTON_A to KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_B to KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_X to KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_Y to KeyEvent.KEYCODE_BUTTON_X,
            )

        return allAvailableInputs + defaultOverride
    }
```

> Atenção: `device` continua sendo usado por outros métodos da classe — NÃO remover
> a propriedade do construtor.

---

## Edição 4 — `dispatchKeyEvent` roteia pelo device, não só pelo source do evento

**Arquivo:** `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/BaseGameActivity.kt`

Motivo: clones compostos enviam D-pad/START pela interface de teclado do mesmo HID
(`event.source == SOURCE_KEYBOARD`), mas o `event.device.sources` contém
JOYSTICK/GAMEPAD. Checar também o device garante que o evento vá para o emulador.

**ANTES:**

```kotlin
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isGamepad = (event.source and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD ||
            (event.source and android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK
        if (isGamepad) {
```

**DEPOIS:**

```kotlin
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val sourceIsGamepad = (event.source and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD ||
            (event.source and android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK
        // Clones enviam D-pad/START pela interface de teclado do mesmo HID composto:
        // o source do EVENTO é KEYBOARD, mas o DEVICE tem source de joystick/gamepad.
        val deviceIsGamepad = event.device?.let {
            (it.sources and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD ||
                (it.sources and android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK
        } ?: false
        if (sourceIsGamepad || deviceIsGamepad) {
```

O restante do método (`when (event.action) ...`) permanece igual.

---

## Edição 5 — log de diagnóstico para devices rejeitados

**Arquivo:** `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/input/InputDeviceManager.kt`

No método `getAllGamePads()`, logar os devices que existem mas foram filtrados,
usando a tag `INPUT_DIAG` já usada no restante do arquivo. Serve para diagnosticar
remotamente casos futuros pedindo um `adb logcat -s INPUT_DIAG` ao cliente.

**ANTES:**

```kotlin
    private fun getAllGamePads(): List<InputDevice> {
        return runCatching {
            InputDevice.getDeviceIds()
                .map { InputDevice.getDevice(it) }
                .filterNotNull()
                .filter { it.getLemuroidInputDevice().isSupported() }
                .filter { it.name !in BLACKLISTED_DEVICES }
                .sortedWith(
                    compareByDescending<InputDevice> { it.gamepadPriority() }
                        .thenBy { it.controllerNumber },
                )
        }.getOrNull() ?: listOf()
    }
```

**DEPOIS:**

```kotlin
    private fun getAllGamePads(): List<InputDevice> {
        return runCatching {
            InputDevice.getDeviceIds()
                .map { InputDevice.getDevice(it) }
                .filterNotNull()
                .filter { device ->
                    val supported = device.getLemuroidInputDevice().isSupported()
                    val blacklisted = device.name in BLACKLISTED_DEVICES
                    if (!supported || blacklisted) {
                        android.util.Log.d(
                            "INPUT_DIAG",
                            "rejected device name=${device.name} sources=${device.sources} " +
                                "isVirtual=${device.isVirtual} supported=$supported blacklisted=$blacklisted",
                        )
                    }
                    supported && !blacklisted
                }
                .sortedWith(
                    compareByDescending<InputDevice> { it.gamepadPriority() }
                        .thenBy { it.controllerNumber },
                )
        }.getOrNull() ?: listOf()
    }
```

---

## O que NÃO fazer

- NÃO mexer em `gamepadPriority()`, `BLACKLISTED_DEVICES`, `getInputClass()`,
  `LemuroidInputDevice.isGamepad()` nem em `InputClassGamePad.INPUT_KEYS` — já estão
  corretos após o fix de 2026-04-18.
- NÃO remover o swap A↔B / X↔Y do `defaultOverride`.
- NÃO tentar tratar o caso de dongle que enumera o D-pad como um **InputDevice
  separado** só-teclado (HID com 2 devices distintos). É raro, exige associação por
  vendorId/productId e mapeamento de porta compartilhada — fica como follow-up se o
  cliente reportar que botões funcionam mas D-pad não, após este fix.

## Verificação

1. **Compilar:** `./gradlew :lemuroid-app:assembleFreeDebug` (na raiz do repo, usar
   `gradlew.bat` no Windows). Deve compilar sem erros.
2. **Regressão local (qualquer Android com controle USB/Bluetooth normal):**
   controle deve continuar aparecendo em Configurações → dispositivos de entrada,
   habilitado, e funcionando em jogo (incluindo o swap A/B).
3. **No dispositivo do cliente (MXQ 4K):**
   - Instalar o APK, conectar o dongle, abrir Configurações → dispositivos de
     entrada: os dois controles devem aparecer habilitados.
   - Abrir um jogo (ex.: NES ou SNES) e testar D-pad, A/B/X/Y, START/SELECT,
     analógicos e o botão MODE (deve abrir o menu do jogo, pois `KEYCODE_BUTTON_MODE`
     com porta 0 abre o menu).
   - Se ainda falhar, pedir `adb logcat -s INPUT_DIAG` — agora o log mostra tanto os
     devices registrados quanto os rejeitados com o motivo.
4. **Orientação ao cliente (independente do código):** esses controles têm chave de
   modo no dongle/botão MODE — se o controle parear em modo "mouse/air-mouse", nada
   funciona como gamepad. Pedir para pressionar MODE (ou segurar HOME+X ao ligar,
   conforme o manual) até o controle sair do modo mouse.

## Risco

Baixo. As mudanças só **relaxam** filtros para devices que já têm source de
joystick/gamepad e não são virtuais. Controles remotos de TV continuam protegidos
por: blacklist, exigência de source joystick/gamepad, `isEnabledByDefault` que ainda
exige eixos SOURCE_JOYSTICK reais, e `gamepadPriority()` que os mantém fora da porta 0.
