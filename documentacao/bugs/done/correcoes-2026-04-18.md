# Correções — 18 de Abril de 2026

Horário: **18/04/2026**  
Build final: **BUILD SUCCESSFUL** — APK instalado no dispositivo `ZY32LMNN9B`.

---

## Correção 1 — Joystick USB não funcionava em jogos no TV Box

### Arquivos modificados
| Arquivo | Papel |
|---------|-------|
| `lemuroid-app/.../shared/input/lemuroiddevice/LemuroidInputDevice.kt` | Detecção e criação do device Lemuroid |
| `lemuroid-app/.../shared/input/lemuroiddevice/LemuroidInputDeviceGamePad.kt` | Implementação do GamePad |
| `lemuroid-app/.../shared/input/InputClass.kt` | Mapeamento de source → classe de input |
| `lemuroid-app/.../shared/input/InputClassGamePad.kt` | Teclas aceitas pelo GamePad |
| `lemuroid-app/.../shared/game/GameViewModelInput.kt` | Processamento de MotionEvents durante o jogo |

### Severidade
**ALTO** — controles USB em TV Box eram completamente ignorados durante o gameplay

### Sintoma
O joystick funcionava nos menus e na UI, mas ao iniciar um jogo os botões e analógicos não respondiam. Reportado em TV Boxes com controles USB genéricos (ex.: controles sem fio com receptor USB).

### Causa raiz
Controles USB genéricos em TV Boxes reportam `InputDevice.SOURCE_JOYSTICK` (sem `SOURCE_GAMEPAD`). O código possuía **5 camadas de filtragem** que aceitavam exclusivamente `SOURCE_GAMEPAD`, descartando silenciosamente qualquer dispositivo com apenas `SOURCE_JOYSTICK`:

1. `getLemuroidInputDevice()` — retornava `null` para dispositivos sem `SOURCE_GAMEPAD`
2. `LemuroidInputDeviceGamePad.isSupported()` — rejeitava devices sem `SOURCE_GAMEPAD`
3. `InputClass.getInputClass()` — não mapeava `SOURCE_JOYSTICK` para `InputClassGamePad`
4. `InputClassGamePad` — não incluía `KEYCODE_DPAD_*` (TV Box manda DPAD como KeyEvent, não MotionEvent)
5. `sendStickMotions()` — usava `when (event.source)` com match exato em vez de check bitwise

### Correções

**1. `LemuroidInputDevice.kt`** — Adicionada função `isGamepad()` que aceita `SOURCE_GAMEPAD` ou `SOURCE_JOYSTICK`; `getLemuroidInputDevice()` alterado para usá-la:

```kotlin
// ANTES — só aceita SOURCE_GAMEPAD:
if ((sources and SOURCE_GAMEPAD) == SOURCE_GAMEPAD) {
    return LemuroidInputDeviceGamePad(context, descriptor)
}

// DEPOIS — aceita gamepad OU joystick puro:
private fun InputDevice.isGamepad(): Boolean =
    (sources and SOURCE_GAMEPAD) == SOURCE_GAMEPAD ||
    (sources and SOURCE_JOYSTICK) == SOURCE_JOYSTICK

if (isGamepad()) {
    return LemuroidInputDeviceGamePad(context, descriptor)
}
```

---

**2. `LemuroidInputDeviceGamePad.kt`** — `isSupported()` agora aceita ambos os sources:

```kotlin
// ANTES:
override fun isSupported(): Boolean =
    (sources and SOURCE_GAMEPAD) == SOURCE_GAMEPAD

// DEPOIS:
override fun isSupported(): Boolean =
    (sources and SOURCE_GAMEPAD) == SOURCE_GAMEPAD ||
    (sources and SOURCE_JOYSTICK) == SOURCE_JOYSTICK
```

---

**3. `InputClass.kt`** — `getInputClass()` passou a mapear `SOURCE_JOYSTICK` → `InputClassGamePad`, inserido antes do bloco de teclado:

```kotlin
// ADICIONADO antes do check de teclado:
(sources and SOURCE_JOYSTICK) == SOURCE_JOYSTICK -> InputClassGamePad(context)
```

---

**4. `InputClassGamePad.kt`** — Adicionados os 4 DPAD keycodes ao conjunto `INPUT_KEYS`. TV Boxes com D-Pad físico enviam eventos como `KeyEvent`, não `MotionEvent`:

```kotlin
// ADICIONADOS:
KeyEvent.KEYCODE_DPAD_UP,
KeyEvent.KEYCODE_DPAD_DOWN,
KeyEvent.KEYCODE_DPAD_LEFT,
KeyEvent.KEYCODE_DPAD_RIGHT,
```

---

**5. `GameViewModelInput.kt`** — `sendStickMotions()` mudou de match exato para verificação bitwise, cobrindo devices cujo `event.source` é uma combinação de flags:

```kotlin
// ANTES — match exato; bitmask combinado passava silencioso:
when (event.source) {
    InputDevice.SOURCE_JOYSTICK -> { /* processa eixos */ }
}

// DEPOIS — verificação bitwise; qualquer source contendo joystick ou gamepad é aceito:
val isJoystickSource = (event.source and SOURCE_JOYSTICK) == SOURCE_JOYSTICK ||
    (event.source and SOURCE_GAMEPAD) == SOURCE_GAMEPAD
if (isJoystickSource) {
    /* processa eixos */
}
```

### Impacto
Controles USB genéricos em TV Boxes (que reportam `SOURCE_JOYSTICK`) agora são reconhecidos corretamente como gamepads em todas as camadas de processamento de input, tanto para botões (KeyEvent) quanto para analógicos/DPAD (MotionEvent).

---

## Status do Build

```
BUILD SUCCESSFUL
APK: lemuroid-app-free-bundle-release.apk
Dispositivo: ZY32LMNN9B
Install: Success
```
