# [BUG] Fallback de DPAD sequestrava o analógico esquerdo em 9 sistemas

**Data:** 2026-07-26
**Status:** Resolvido ✅
**Severidade:** Média (regressão em controle comum, restrita aos sistemas com analógico)
**Branch:** version9

---

## Sintoma

Em N64, PSX (DualShock), PSP, NDS, DOS, 3DS, Dreamcast, GameCube e Amiga, mover o
analógico esquerdo aciona **também** o D-pad digital. O efeito prático é entrada dupla:
cursor de menu andando dois passos, ou o direcional digital atropelando a leitura
analógica em jogo.

Atinge controle analógico comum, não o stick arcade — justamente o oposto do que a
mudança pretendia.

Regressão introduzida e corrigida dentro da mesma sessão (commits `d986589` →
`dee8db4`); **nunca chegou a um release**.

## Causa-raiz

Ao corrigir o caso do stick arcade digital (que reporta o manche em `AXIS_X/Y` e não tem
canal próprio de D-pad), o guard escrito checava apenas a ausência de **eixos HAT**:

```kotlin
val hasHatAxes = event.device?.hasHatAxes() != false
val dpadXAxis = if (hasHatAxes) MotionEvent.AXIS_HAT_X else MotionEvent.AXIS_X
```

A premissa estava incompleta. Um gamepad analógico perfeitamente normal que reporta o
D-pad como **teclas** `KEYCODE_DPAD_*` — em vez de eixos HAT — também não tem eixos HAT.
Para ele o guard dava falso positivo e passava a rotear `AXIS_X/Y` (o analógico esquerdo)
para `MOTION_SOURCE_DPAD`.

O agravante é *onde* isso acontece. `sendSeparateMotionEvents` só roda quando
`ControllerConfig.mergeDPADAndLeftStickEvents == false`, que é o default e vale para 9
configs — exatamente as dos sistemas com analógico real, onde a separação entre D-pad e
analógico é o propósito da flag:

```
N64, PSX_DUALSHOCK, PSP, DESMUME, DOS_AUTO, NINTENDO_3DS, DREAMCAST, GAMECUBE, AMIGA
```

Ou seja: a mudança quebrava a separação precisamente nos sistemas que existem para
mantê-la.

## Correção

Commit `dee8db4`. O fallback passou a exigir ausência de **todo** canal de D-pad — sem
eixos HAT **e** sem teclas `DPAD_*`:

```kotlin
private fun InputDevice.hasDedicatedDpad(): Boolean {
    val axes = motionRanges.map { it.axis }.toSet()
    val hasHatAxes = MotionEvent.AXIS_HAT_X in axes && MotionEvent.AXIS_HAT_Y in axes
    val hasDpadKeys = hasKeys(DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT).any { it }
    return hasHatAxes || hasDpadKeys
}
```

A assimetria do `hasKeys` foi escolhida de propósito, e os dois modos de erro caem no lado
seguro:

- `hasKeys` mentindo **false** → cai no fallback → é o comportamento desejado para stick
  arcade.
- `hasKeys` mentindo **true** → pula o fallback → preserva o comportamento antigo.

O resultado é cacheado por `InputDevice.id` (ver
`2026-07-26-haskeys-ipc-caminho-motion-event.md`).

Na mesma correção foi removido o fallback `AXIS_RX/RY` do patch de 2026-07-23, que era
**código morto**: `hasPrimaryDirectionAxes()` retornava `true` para qualquer device com
`AXIS_X` e `AXIS_Y` — praticamente todo joystick — então `retrieveFallbackLeftCoordinates`
e `sendFallbackLeftStickMotion` nunca executavam.

## Validação

```powershell
./gradlew.bat :lemuroid-app:compileFreeBundleDebugKotlin   # BUILD SUCCESSFUL
```

Sem validação em device. Nem o caso que quebrava (gamepad analógico com D-pad por teclas
em PSX DualShock) nem o caso que a mudança quer resolver (stick arcade digital) foram
exercitados em hardware.

## Lição

**"Não tem eixos HAT" não é sinônimo de "não tem D-pad".** D-pad chega ao Android por dois
canais independentes — `AXIS_HAT_X/Y` ou teclas `KEYCODE_DPAD_*` — e qualquer heurística
sobre ausência de direcional precisa checar os dois.

Mais geral: ao adicionar um fallback para hardware exótico, o teste que importa não é "isto
resolve o caso raro?", e sim **"o que isto faz com o hardware comum?"**. Aqui o caso raro
(stick arcade) nunca foi testado, mas o caso comum teria regredido em 9 sistemas.

E ao mexer em `sendSeparateMotionEvents`, lembrar que ele só roda com
`mergeDPADAndLeftStickEvents = false` — **ao testar input, sempre exercitar um sistema de
cada lado dessa flag** (ex.: arcade e PSX DualShock), porque são caminhos de código
diferentes.
