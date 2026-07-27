# [BUG] `InputDevice.hasKeys()` (IPC) no caminho de motion event — um binder por evento

**Data:** 2026-07-26
**Status:** Resolvido ✅
**Severidade:** Média-Alta (custo por evento durante o jogo, em dois pontos distintos)
**Branch:** version9

---

## Sintoma

Nenhum sintoma funcional — o input continua correto. O problema é custo: durante o jogo,
com o analógico em movimento, cada motion event dispara uma ou duas chamadas IPC
(binder) para o `InputManagerService`. Motion events chegam a dezenas de vezes por
segundo por eixo em movimento.

Não foi medido em device. Foi identificado por leitura, em duas passadas de revisão
distintas — a segunda ocorrência passou batido pela primeira revisão.

## Causa-raiz

`InputDevice.hasKeys()` não lê um campo local: resolve contra os arquivos `.kl` via
`InputManagerGlobal.deviceHasKeys()`, que é uma chamada binder para o
`InputManagerService`. `InputDevice.motionRanges` também aloca uma lista a cada acesso.

Todos os outros usos de `hasKeys` no código estão na **enumeração de dispositivos**
(`getAllGamePads`, `isSupported`, `isEnabledByDefault`) — raros, disparados só em
add/change/remove de device. Estes dois estavam no caminho de evento:

### Ocorrência 1 — `hasDedicatedDpad()` (introduzida na própria correção, commit `d986589`)

Chamada por `sendSeparateMotionEvents`, ou seja, a cada motion event.

### Ocorrência 2 — `getInputClass()` (introduzida pelo patch de 2026-07-23)

Mais sutil e mais séria. `GameViewModelInput.initializeVirtualGamePadMotionsFlow` chama
`event.device.getInputClass()` a **cada** motion event. O patch de 07-23 tinha colocado um
`hasKeys(BUTTON_1..4)` dentro de `getInputClass`, e — este é o detalhe — atribuído a um
`val` **antes** do `||`:

```kotlin
val isGamepadSource = ...
val hasGenericNumberedButtons = hasKeys(...).all { it }   // avaliado SEMPRE
return isGamepadSource || hasGenericNumberedButtons        // || nao curto-circuita
```

O `||` só curto-circuita sobre expressões; com os dois lados já avaliados em `val`, o IPC
roda incondicionalmente — inclusive em controle comum, onde o `sources` já resolvia a
classificação sozinho.

## Correção

Commits `dee8db4` (ocorrência 1) e `e983c52` (ocorrência 2).

**Ocorrência 1** — resultado cacheado por `InputDevice.id` num `ConcurrentHashMap`, que é
estável enquanto o device fica conectado.

**Ocorrência 2** — a checagem numérica foi **removida** de `InputClass.getInputClass` e de
`LemuroidInputDevice.isGamepad`, em vez de apenas tornada preguiçosa, porque nos dois
pontos ela é inerte e só pode causar dano:

- **Inerte**: `getAllGamePads` filtra por `isSupported()`, que exige source de gamepad. Se
  o device tem source, `isGamepad()` já retorna true por ele; se não tem, `isSupported()`
  reprova de qualquer forma.
- **Danosa**: um device sem source de gamepad mas com `BUTTON_1..4` deixava de ser roteado
  para `LemuroidInputDeviceKeyboard` — que poderia suportá-lo — e passava a engolir teclas
  em `sendKeyEvent` sem ter porta atribuída.

A evidência por `BUTTON_1..4` continua onde de fato serve:
`LemuroidInputDeviceGamePad.hasGamepadEvidence()` e `isEnabledByDefault()`, ambos
consultados apenas na enumeração de dispositivos.

Efeito colateral: `InputClass.kt`, `LemuroidInputDevice.kt` e `InputClassGamePad.kt`
voltaram a ser **logicamente idênticos ao upstream** (só ganharam comentários), verificado
por diff ignorando comentários e linhas em branco.

## Validação

```powershell
./gradlew.bat :lemuroid-app:compileFreeBundleDebugKotlin   # BUILD SUCCESSFUL
```

Sem medição de performance em device — o diagnóstico é estático.

## Lição

**`InputDevice.hasKeys()` é IPC. Nunca chamar no caminho de key/motion event** — só na
enumeração de dispositivos, ou com o resultado cacheado por `InputDevice.id`. O mesmo vale
para `motionRanges`, que aloca.

`getInputClass()` em particular roda por evento e por isso deve continuar decidindo
**apenas por `sources`**, que é campo local barato. Foi o que o plano do `b424cda` já
mandava ("NÃO mexer em `getInputClass()` nem em `LemuroidInputDevice.isGamepad()`") e o
patch de 07-23 desrespeitou.

Armadilha de Kotlin que vale registrar: **`a || b` não economiza nada se `b` já foi
avaliado em um `val` acima.** Para preservar o curto-circuito, o lado caro precisa ser uma
expressão ou uma chamada de função no próprio `||`.
