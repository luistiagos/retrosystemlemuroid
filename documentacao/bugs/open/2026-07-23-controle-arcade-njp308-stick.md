# [BUG] Controle arcade NJP308/NJP308A: stick nao responde no jogo

**Data:** 2026-07-23
**Status:** Em aberto - causa da recorrencia identificada em 2026-07-26 (fix existente nao esta neste branch)
**Severidade:** Media - controle fisico conectado pode aparecer sem porta efetiva no jogo
**Modelo afetado:** NJP308 / NJP308A "Game Arcade Controller" USB para PC/Android/PSII/PSIII
**Branch:** version9

---

## Sintoma

O controle arcade USB e reconhecido/conectado, mas o stick nao move o jogo no Lemuroid.
Em alguns aparelhos o controle pode nem aparecer como gamepad habilitado; em outros, botoes
podem chegar com nomes numericos e o direcional fica sem efeito dentro do core.

## Evidencias do manual

O manual anexado descreve o NJP308/NJP308A como controle multi-protocolo para
USB/PC, Android OTG, PSII e PSIII. Dois detalhes sao importantes:

- O controle tem dois modos de base: **digital pattern** e **simulated model**.
- Depois de conectar no Android via OTG, o manual diz que o modo pode ser alternado por
  toque curto no botao **MODE**.
- A tabela de botoes mostra diferenca entre Android e USB(PC): no USB(PC), alguns botoes
  sao expostos como numeros (`5`, `6`, `7`, `8`) em vez de nomes Android (`L2`, `R2`,
  `L1`, `R1`). Esse e o padrao de muitos controles arcade DirectInput genericos.

## Causa-raiz provavel

Ha duas causas possiveis, dependendo do ponto em que o dispositivo aparece.

### Camada 1 - Android/USB OTG

No teste ADB de 2026-07-23, o telefone `moto g86 5G` estava conectado ao PC por USB para ADB.
O `dumpsys usb` mostrou a porta em `current_mode=ufp`, `power_role=sink`,
`data_role=device`. Nesse estado o telefone e o dispositivo USB, nao o host OTG.

O `dumpsys input` e `getevent -p` nao listaram nenhum gamepad/joystick externo, apenas
input interno do telefone. Portanto, nesse setup o Lemuroid nao recebe nada: o Android
nao enumerou o arcade stick como input.

Para testar o controle de verdade, e preciso liberar a porta USB do telefone para OTG:

- usar ADB via Wi-Fi/pareamento sem fio e plugar o controle por OTG no telefone; ou
- usar um hub USB-C que permita OTG host para o controle e, se necessario, energia externa;
  ADB por USB comum deixa o telefone em modo device e impede esse teste.

### Camada 2 - Filtro/mapeamento do Lemuroid

Quando o Android enumera o controle como gamepad/joystick, o problema nao esta no envio
de movimento para o core: [GameViewModelInput.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/viewmodel/GameViewModelInput.kt)
ja trata o stick tanto como `AXIS_HAT_X/Y` quanto como `AXIS_X/Y`.

A falha esta antes, na deteccao/habilitacao do dispositivo:

1. [LemuroidInputDeviceGamePad.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/input/lemuroiddevice/LemuroidInputDeviceGamePad.kt)
   considerava gamepad suportado apenas se o Android dissesse que o dispositivo tinha
   **todos** os botoes `KEYCODE_BUTTON_A`, `B`, `X` e `Y`.
2. Controles arcade USB genericos, especialmente no modo USB(PC)/digital, frequentemente
   expoem os botoes como `KEYCODE_BUTTON_1`, `2`, `3`, `4`, etc.
3. Quando isso acontece, `InputDeviceManager.getAllGamePads()` descarta o dispositivo.
4. Sem dispositivo habilitado, `getGamePadsPortMapperObservable()` nao atribui porta.
5. Durante o jogo, os eventos de movimento podem ate chegar, mas `ports(event.device)` fica
   `null`; entao `sendStickMotions()` nunca envia DPAD/analogico para o Libretro.

Resultado: o stick parece "morto", embora o hardware esteja enviando eventos.

## Correcao aplicada no app

Arquivos alterados:

- [LemuroidInputDeviceGamePad.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/input/lemuroiddevice/LemuroidInputDeviceGamePad.kt)
- [GameViewModelInput.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/viewmodel/GameViewModelInput.kt)
- [LemuroidInputDevice.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/input/lemuroiddevice/LemuroidInputDevice.kt)
- [InputClass.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/input/inputclass/InputClass.kt)
- [InputClassGamePad.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/input/inputclass/InputClassGamePad.kt)

Mudancas:

- O filtro de suporte agora aceita dois conjuntos minimos:
  - gamepad Android padrao: `BUTTON_A`, `BUTTON_B`, `BUTTON_X`, `BUTTON_Y`;
  - arcade/DirectInput generico: `BUTTON_1`, `BUTTON_2`, `BUTTON_3`, `BUTTON_4`.
- Controles numericos passam a ser habilitados por padrao quando expoem `BUTTON_1..4`
  ou, em adaptadores HID simples, as teclas numericas `KEYCODE_1..4`.
- Dispositivos nao-alfabeticos que expoem teclas numericas de controle agora tambem sao
  tratados como classe de gamepad, em vez de cair no caminho de teclado completo.
- Foi adicionado mapeamento padrao para controles USB numericos:
  - `BUTTON_1 -> B`
  - `BUTTON_2 -> A`
  - `BUTTON_3 -> Y`
  - `BUTTON_4 -> X`
  - `BUTTON_5 -> L2`
  - `BUTTON_6 -> R2`
  - `BUTTON_7 -> L1`
  - `BUTTON_8 -> R1`
  - `BUTTON_9 -> Select`
  - `BUTTON_10 -> Start`
- Tambem ha fallback equivalente para teclas numericas comuns:
  - `KEYCODE_1 -> B`
  - `KEYCODE_2 -> A`
  - `KEYCODE_3 -> Y`
  - `KEYCODE_4 -> X`
  - `KEYCODE_5 -> L2`
  - `KEYCODE_6 -> R2`
  - `KEYCODE_7 -> L1`
  - `KEYCODE_8 -> R1`
  - `KEYCODE_9 -> Select`
  - `KEYCODE_0 -> Start`
- No caminho do stick, eventos de movimento com `SOURCE_DPAD` agora sao aceitos alem de
  `SOURCE_GAMEPAD`/`SOURCE_JOYSTICK`.
- Se o dispositivo nao declara `AXIS_HAT_X/Y` nem `AXIS_X/Y`, o app usa `AXIS_RX/RY` como
  fallback de direcional/analogico esquerdo. Isso cobre adaptadores arcade que anunciam o
  manche em eixos nao-padrao sem afetar controles normais que ja possuem eixos primarios.

Esse mapeamento segue o comportamento comum de controles DirectInput e a tabela do manual
para os botoes `5..8`. Se algum botao fisico ficar invertido nesse modelo, o usuario ainda
pode remapear em Configuracoes > Controles.

## Validacao da correcao

Sem o arcade stick fisico conectado nao e possivel afirmar que o hardware especifico
NJP308/NJP308A foi exercitado ponta a ponta. A validacao feita nesta entrega e de codigo,
build e cobertura dos formatos de entrada que o Android pode expor para esse tipo de
controle.

Matriz coberta pela correcao:

- Controles Android padrao com `BUTTON_A/B/X/Y`: comportamento preservado.
- Controles arcade/DirectInput com `BUTTON_1..BUTTON_4`: agora sao classificados como
  gamepad, habilitados por padrao e recebem mapeamento para os botoes Libretro.
- Adaptadores HID que usam `KEYCODE_1..KEYCODE_4` em teclado nao alfabetico: agora sao
  classificados como gamepad e recebem mapeamento equivalente.
- Stick como DPAD digital por teclas `KEYCODE_DPAD_*`: ja era aceito pela classe de
  gamepad e continua coberto.
- Stick como `AXIS_HAT_X/Y`: continua indo para DPAD.
- Stick como `AXIS_X/Y`: continua indo para analogico esquerdo.
- Stick como evento com `SOURCE_DPAD`: agora passa pelo filtro de movimento.
- Stick como `AXIS_RX/RY` em dispositivo sem `AXIS_HAT_X/Y` nem `AXIS_X/Y`: agora e enviado
  tanto para DPAD quanto para analogico esquerdo, evitando falha em cores/sistemas que
  esperam direcional digital.

Validacoes executadas:

```powershell
./gradlew.bat :lemuroid-app:compileFreeBundleDebugKotlin
git diff --check
./gradlew.bat :lemuroid-app:assembleFreeBundleDebug --max-workers=2
```

As validacoes passaram, incluindo geracao do APK `freeBundle/debug`. A validacao fisica ainda depende de conectar o controle em
modo OTG host e capturar `dumpsys input`/`getevent`, porque no teste ADB disponivel o
telefone estava em modo USB device conectado ao PC, nao em modo host OTG para o controle.

## Como consertar no aparelho sem rebuild

Se estiver testando uma versao antiga do APK, use este procedimento:

1. Conecte o controle via OTG com o Lemuroid fechado.
2. Abra o Lemuroid e entre em Configuracoes > Controles.
3. Se o controle aparecer desativado, ative manualmente.
4. Dentro de um jogo, pressione `MODE` uma vez para alternar digital/simulado.
5. Teste o stick. Se nao mover, pressione `MODE` novamente e teste de novo.
6. Se os botoes responderem errados, use o remapeamento manual.

Se o controle nao aparecer na tela de controles, a versao antiga provavelmente esta batendo
no filtro estrito `A/B/X/Y`; nesse caso precisa do patch acima ou de uma versao nova do APK.

## Como confirmar em device

Com `adb` disponivel pelo Android SDK:

```powershell
adb shell dumpsys input | Select-String -Pattern "NJP|Game|Joystick|Keyboard|BUTTON_|AXIS_" -Context 0,8
adb logcat -s INPUT_DIAG
```

Sinais esperados antes da correcao:

- O controle aparece em `dumpsys input` com `BUTTON_1..BUTTON_4` ou botoes numericos.
- Nao aparece log `registered gamepad ...` para esse dispositivo.
- Eventos de tecla/movimento podem aparecer, mas sem `port=0`.

Sinais esperados depois da correcao:

- Log parecido com `registered gamepad id=<id> name=<nome> port=0`.
- Ao mexer no stick, os eventos passam pelo mapeamento de porta.
- O jogo recebe DPAD ou analogico, dependendo do modo atual do controle e do sistema emulado.

## Por que o bug ainda recorre (analise de 2026-07-26)

A correcao descrita acima nao resolveu porque **ela foi construida sobre um baseline
que ja estava sem o fix de input anterior**. Duas descobertas, ambas verificadas no git:

### Descoberta 1 - o fix de input existe, mas em outro branch

O commit `b424cda` (2026-07-20) aplicou os 5 edits do plano
`documentacao/backlogs/fix-controles-genericos-tvbox-haskeys.md` e **so existe no branch
`version8`**. O branch atual `version9` divergiu de `version8` no commit `095f1dc`,
antes desse fix.

```
095f1dc (base comum)
  |-- version8 -> 0fbe5fc, f8ff215, 8d19200, b424cda   <- fix de input aqui
  \-- version9 -> ... 9be8ecf, 27accb3, e1cd92f, fc6e94c  <- branch de trabalho atual
```

Confirmacao: `git merge-base --is-ancestor b424cda HEAD` retorna falso.

Dois dos quatro commits do `version8` foram cherry-picked para o `version9`
(`f8ff215` -> `9be8ecf`, `8d19200` -> `27accb3`). O `b424cda` ficou para tras.
O arquivo de backlog tambem nunca existiu neste branch - `git log --diff-filter=D`
nao encontra delecao, ele simplesmente nao esta na historia do `version9`.

### Descoberta 2 - o patch de 07-23 deixou intactos os pontos que matam o direcional

Escrito sobre o codigo original do upstream, o patch reimplementou parte do diagnostico
e nao tocou nos tres pontos corrigidos pelo `b424cda`:

| # | Ponto | Estado no `version9` | Efeito |
|---|-------|----------------------|--------|
| 1 | `LemuroidInputDeviceGamePad.getDefaultBindingForKey()` | ainda presente | **causa mecanica mais provavel do stick morto** |
| 2 | `BaseGameActivity.dispatchKeyEvent()` | so checa `event.source` | direcional pela interface de teclado do HID composto e consumido pelo Compose |
| 3 | `isSupported()` / `isEnabledByDefault()` | so `hasKeys()` | perdeu o `hasJoystickAxes()`, unica evidencia que nao depende do `.kl` |

**Detalhe do ponto 1.** `InputDeviceManager.OUTPUT_KEYS` inclui
`KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT`. Se `device.hasKeys(KEYCODE_DPAD_UP)` retorna `false`
(caso tipico de encoder arcade generico sem `.kl` proprio), o binding padrao vira
`RetroKey(KEYCODE_UNKNOWN)`. Depois, em `GameViewModelInput.initializeGamePadKeysFlow`:

```kotlin
val bindKeyCode = bindings(device)[InputKey(keyCode)]?.keyCode ?: keyCode
```

O `?: keyCode` **nao salva**, porque o mapa *contem* a chave - com valor
`KEYCODE_UNKNOWN` (0). O evento chega, tem porta atribuida, e e enviado ao core como
keycode 0. Morre em silencio. Isso explica o sintoma "stick morto mesmo com o controle
aparecendo habilitado".

### Descoberta 3 - problema independente, nao coberto por nenhum dos dois fixes

`ControllerConfig.mergeDPADAndLeftStickEvents` e `false` por default e 9 configs nao o
ativam: **N64, PSX_DUALSHOCK, PSP, DESMUME, DOS_AUTO, NINTENDO_3DS, DREAMCAST,
GAMECUBE, AMIGA**.

Nesses sistemas, `sendSeparateMotionEvents` manda `AXIS_X/Y` apenas para
`MOTION_SOURCE_ANALOG_LEFT`, e o DPAD recebe `AXIS_HAT_X/Y` (= 0 se o device nao tem
hat). Um stick arcade digital reportando em `AXIS_X/Y` fica sem direcional nesses
sistemas especificos. Em arcade/NES/SNES (`merge = true`) isso nao ocorre - o que
explica por que o sintoma parece intermitente conforme o jogo testado.

### Codigo morto introduzido pelo patch de 07-23

`hasPrimaryDirectionAxes()` retorna `true` se o device tem `AXIS_X` **e** `AXIS_Y`,
o que praticamente todo joystick tem. Logo `retrieveFallbackLeftCoordinates()` e
`sendFallbackLeftStickMotion()` nunca disparam na pratica.

### Estado do patch de 07-23

As 5 alteracoes estao **apenas no working tree**, sem commit. Qualquer build feito a
partir de um checkout limpo nao contem o patch.

## Plano de correcao proposto

1. `git cherry-pick b424cda` no `version9` - traz os 5 edits ja validados.
2. Resolver o conflito em `LemuroidInputDeviceGamePad.kt` mantendo o **binding
   identidade** do `version8` e sobrepondo apenas os overrides numericos
   `BUTTON_1..10` do patch de 07-23 (sao aditivos, nao conflitam).
3. Remover `retrieveFallbackLeftCoordinates` / `sendFallbackLeftStickMotion` (codigo
   morto) e, no lugar, tratar o caso `merge = false` + device sem `AXIS_HAT`.
4. Commitar antes de gerar APK de teste.
5. Restaurar `documentacao/backlogs/fix-controles-genericos-tvbox-haskeys.md` no
   `version9` (vem junto no cherry-pick) para nao perder o plano de novo.

## Licao

Fix de input validado em um branch de release nao acompanha o proximo branch
automaticamente. Ao cherry-pickar commits entre branches de versao, conferir a lista
completa com `git log --oneline versaoNova..versaoAntiga` em vez de escolher commits
individualmente - foi exatamente assim que o `b424cda` se perdeu enquanto os dois
commits de build pipeline vizinhos foram trazidos.

## Observacao sobre o botao MODE

No Lemuroid, `KEYCODE_BUTTON_MODE` tambem e usado como atalho de menu quando vem do controle.
No NJP308/NJP308A, o mesmo botao alterna o modo interno do hardware. Isso pode confundir o
teste: pressionar `MODE` pode abrir o menu do Lemuroid e tambem trocar o modo do controle.
Para diagnostico, feche o menu e teste o stick apos cada toque curto no `MODE`.
