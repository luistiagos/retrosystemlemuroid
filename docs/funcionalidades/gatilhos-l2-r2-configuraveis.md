# Gatilhos L2/R2 configuráveis

Faz as linhas **L2** e **R2** aparecerem em *Configurações → Controles* para qualquer controle,
e faz o binding delas valer de verdade — inclusive quando o gatilho é um **eixo analógico**,
que é o caso da maioria dos controles modernos (Xbox, DualShock, 8BitDo, ipega).

---

## Por que elas não apareciam

Não era esquecimento: `KEYCODE_BUTTON_L2` e `KEYCODE_BUTTON_R2` sempre estiveram em
`CUSTOMIZABLE_KEYS`. O filtro estava em `LemuroidInputDeviceGamePad.getCustomizableKeys()`, que
descartava toda `RetroKey` produzida por um eixo que o aparelho reportasse:

```kotlin
// antes
val keysMappedToAxis = device.getInputClass().getAxesMap()
    .filter { it.key in deviceAxis }
    .map { it.value }.toSet()
return CUSTOMIZABLE_KEYS.filter { it.keyCode !in keysMappedToAxis }
```

E o `AXES_MAP` de `InputClassGamePad` mapeia exatamente os quatro eixos de gatilho:

| Eixo | Vira |
|------|------|
| `AXIS_BRAKE` / `AXIS_LTRIGGER` | `BUTTON_L2` |
| `AXIS_THROTTLE` / `AXIS_RTRIGGER` | `BUTTON_R2` |

Logo: **controle com gatilho analógico ⇒ nenhuma linha de L2/R2 na tela.**

O motivo do filtro era honesto. `initializeVirtualGamePadMotionsFlow` convertia o eixo acima de
0.5 em `sendKeyEvent(action, button, port)` com o **keycode fixo, direto para o core**, sem
consultar o mapa de bindings. Mostrar a linha teria sido mentira: o remapeamento não teria efeito.

---

## O que mudou

Três peças, nesta ordem de dependência — a primeira é o que torna as outras duas honestas.

### 1. O caminho de eixo passa pelo mapa de bindings

`GameViewModelInput.initializeVirtualGamePadMotionsFlow` agora combina
`inputDeviceManager.getInputBindingsObservable()` e resolve o keycode pelo mesmo helper que o
caminho de teclas usa:

```kotlin
SingleAxisEvent(axis, action, resolveBoundKeyCode(deviceBindings, button), port)
```

`resolveBoundKeyCode` foi extraído da lógica que já existia inline no fluxo de teclas — binding
ausente, `KEYCODE_UNKNOWN` ou `0` significam "sem remapeamento", manda a própria tecla física.
Como o binding padrão de gamepad é identidade sobre `OUTPUT_KEYS` (`L2 → L2`, `R2 → R2`), **o
comportamento default não muda**.

Isso sozinho já corrige um bug silencioso nos controles que emitem **eixo e KeyEvent** para o
mesmo gatilho (DualShock 4, por exemplo): remapear o gatilho pela tecla funcionava, mas o eixo
continuava disparando o L2 original em paralelo, anulando o remapeamento.

### 2. A captura aceita o gatilho como tecla física

`InputBindingUpdater.handleMotionEvent` arma na passagem do limiar e grava na soltura,
espelhando o par `ACTION_DOWN`/`ACTION_UP` das teclas. Sem isso, num controle cujo gatilho é
**só eixo** (Xbox), a linha abriria o diálogo "pressione um botão" e apertar o gatilho não faria
absolutamente nada.

O limiar virou `AXIS_PRESS_THRESHOLD` (0.5f) em `inputclass/InputClass.kt`, compartilhado entre
jogo e captura — os dois precisam concordar sobre onde o gatilho "aperta".

### 3. As linhas deixam de ser escondidas

`getCustomizableKeys()` devolve `CUSTOMIZABLE_KEYS` sem filtro.

---

## Pitfall: onde capturar o motion event no diálogo do Compose

`GamePadBindingActivity` mostra um `AlertDialog` do Compose, que vive **numa janela própria**.
Evento de motion vai para a janela com foco, então `Activity.onGenericMotionEvent` **nunca é
chamado** enquanto o diálogo está na frente. O override foi mantido só para o caso de o diálogo
não estar visível; o caminho real é um `OnGenericMotionListener` no `DecorView` **da janela do
diálogo**, instalado pelo composable `GenericMotionCapture` — que precisa ficar *dentro* do
conteúdo do diálogo, porque é o `LocalView` de lá que resolve para a janela certa.

A cadeia foi conferida no fonte do framework (`sources;android-35`) e no bytecode do Compose
1.8.2, não por dedução:

1. `DecorView.dispatchGenericMotionEvent` → `cb.dispatchGenericMotionEvent` (o `Dialog`);
2. `Dialog.dispatchGenericMotionEvent` → `mWindow.superDispatchGenericMotionEvent` → volta ao
   `View.dispatchGenericMotionEvent` do `DecorView`;
3. fonte de joystick não é `SOURCE_CLASS_POINTER` ⇒ `dispatchGenericFocusedEvent` entrega ao
   filho com foco, o `AndroidComposeView`;
4. `AndroidComposeView.dispatchGenericMotionEvent` só trata `ACTION_SCROLL` (8) e rotary; para
   `ACTION_MOVE` de joystick delega a `ViewGroup` e devolve `false`;
5. de volta no `DecorView`, `dispatchGenericMotionEventInternal` chama o
   `mOnGenericMotionListener` — o nosso.

`TVGamePadBindingActivity` não tem esse problema: o `GuidedStepSupportFragment` vive na janela da
própria Activity, então o override de `onGenericMotionEvent` basta.

---

## Arquivos

| Arquivo | Mudança |
|---------|---------|
| [InputClass.kt](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/input/inputclass/InputClass.kt) | `AXIS_PRESS_THRESHOLD` compartilhado |
| [LemuroidInputDeviceGamePad.kt](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/input/lemuroiddevice/LemuroidInputDeviceGamePad.kt) | `getCustomizableKeys()` sem o filtro de eixo |
| [GameViewModelInput.kt](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/viewmodel/GameViewModelInput.kt) | `resolveBoundKeyCode` + fluxo de eixos com bindings |
| [InputBindingUpdater.kt](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/input/InputBindingUpdater.kt) | `handleMotionEvent` |
| [GamePadBindingActivity.kt](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/input/GamePadBindingActivity.kt) | `GenericMotionCapture` no conteúdo do diálogo |
| [TVGamePadBindingActivity.kt](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/input/TVGamePadBindingActivity.kt) | `onGenericMotionEvent` |

---

## Validação

- `:lemuroid-app:compileFreeDynamicDebugKotlin` passa.
- `ktlintMainSourceSetCheck` não acusa nada novo nos arquivos tocados (as violações que restam
  nesses arquivos são pré-existentes: os `Log.d("INPUT_DIAG", …)` e a ordem de imports do
  `InputBindingUpdater`).
- **Falta teste em aparelho** — não havia device no `adb` na sessão. O que conferir:
  1. as linhas L2 e R2 aparecem na lista do controle;
  2. tocar em "Botão L2" e **apertar o gatilho** fecha o diálogo e grava o binding;
  3. remapear um gatilho para outro botão surte efeito no jogo (era o caso que não funcionava);
  4. sem mexer em nada, L2/R2 continuam disparando normalmente (binding identidade).
