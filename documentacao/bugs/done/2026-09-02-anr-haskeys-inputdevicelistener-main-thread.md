# [BUG] ANR em TV box — `InputDeviceListener` reenumera os controles na main thread e cada `hasKeys()` é um binder IPC

**Data:** 2026-09-02
**Status:** 🟢 Resolvido — enumeração e callbacks movidos para thread dedicada; `hasKeys()` cacheado
**Severidade:** Alta (ANR no processo `:game`; o aparelho afetado é justamente TV box/Chromecast)
**Branch:** version9
**Origem:** telemetria `retrogamesystem/anr` (`libc.so::syscall+28`)
**Errors (serviço):** 7 ocorrências — 3172, 3048, 3036, 3028, 3026, 3024, 2954
**Aparelho:** Google Chromecast (`sabrina`, Google TV), **abi=armeabi-v7a**, Android 14, app 1.17.10/1.17.11
**Janela observada:** 2026-08-29 00:40 → 2026-08-30 11:54
**Relacionado:** [2026-07-26-haskeys-ipc-caminho-motion-event.md](2026-07-26-haskeys-ipc-caminho-motion-event.md) — mesma primitiva, **outro call-site**

---

## Sintoma

`ANR: Input dispatching timed out (Application does not have a focused window)` no processo
`app.retrogamesystem:game`. O dump de threads mostra a **main thread bloqueada dentro de um
binder transact**:

```
"main" prio=5 tid=1 Native
  native: #01 libart.so (art::ConditionVariable::WaitHoldingLocks+86)
  native: #02 libart.so (artJniMethodEnd+296)
  at android.os.BinderProxy.transactNative(Native method)
  at android.os.BinderProxy.transact(BinderProxy.java:584)
  at android.hardware.input.IInputManager$Stub$Proxy.hasKeys(IInputManager.java:1510)
  at android.hardware.input.InputManagerGlobal.deviceHasKeys(InputManagerGlobal.java:1250)
  at android.view.InputDevice.hasKeys(InputDevice.java:983)
  at com.swordfish.lemuroid.app.shared.input.f.e(SourceFile:59)          <- LemuroidInputDeviceGamePad
  at m4.c.a(SourceFile:25)
  at com.swordfish.lemuroid.app.shared.input.d.q(SourceFile:67)          <- InputDeviceManager.getAllGamePads
  at com.swordfish.lemuroid.app.shared.input.d$n.onInputDeviceChanged(SourceFile:5)
  at android.hardware.input.InputManagerGlobal$InputDeviceListenerDelegate.handleMessage(…:309)
  at android.os.Looper.loop(Looper.java:294)
```

Não é um dump ambíguo: a main thread está *dentro* do `hasKeys`, chamada a partir do
`onInputDeviceChanged`.

## Causa-raiz

[InputDeviceManager.kt:209-229](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/input/InputDeviceManager.kt#L209-L229):

```kotlin
fun getGamePadsObservable(): Flow<List<InputDevice>> {
    val result = MutableStateFlow(getAllGamePads())
    val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int)   { result.value = getAllGamePads() }
        override fun onInputDeviceChanged(deviceId: Int) { result.value = getAllGamePads() }
        override fun onInputDeviceRemoved(deviceId: Int) { result.value = getAllGamePads() }
    }
    return result
        .onSubscription { inputManager.registerInputDeviceListener(listener, null) }  // <- Handler null
        .onCompletion   { inputManager.unregisterInputDeviceListener(listener) }
}
```

`registerInputDeviceListener(listener, null)` — o segundo parâmetro é o `Handler` de entrega.
**`null` significa "main thread"**. Logo cada `onInputDevice*` roda na main e dispara
`getAllGamePads()`, que filtra por `LemuroidInputDeviceGamePad.isSupported()` →
`hasGamepadEvidence()`
([LemuroidInputDeviceGamePad.kt:98-115](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/input/lemuroiddevice/LemuroidInputDeviceGamePad.kt#L98-L115)),
que faz `supportsAllKeys(...)` **e** um `hasKeys(...)` com ~12 keycodes.

`InputDevice.hasKeys()` não lê campo local: resolve contra os `.kl` da ROM via binder para o
`InputManagerService`. Custo = 1 IPC × (nº de dispositivos) × (nº de chamadas por dispositivo),
tudo na main. Num Chromecast armeabi-v7a — CPU fraca, e onde o serviço de input já está sob
carga porque *acabou* de mudar o conjunto de devices — isso passa dos 5 s e vira ANR.

### Por que o fix de 2026-07-26 não pegou este caso

[2026-07-26-haskeys-ipc-caminho-motion-event.md](../done/2026-07-26-haskeys-ipc-caminho-motion-event.md)
tratou os dois `hasKeys` do **caminho de motion event** e registrou explicitamente:

> "Todos os outros usos de `hasKeys` no código estão na **enumeração de dispositivos**
> (`getAllGamePads`, `isSupported`, `isEnabledByDefault`) — raros, disparados só em
> add/change/remove de device."

A premissa "raros ⇒ inofensivos" é o que a telemetria derruba: raros sim, mas **na main
thread**, e o custo por disparo é alto o bastante para o ANR sozinho. O disparo também não é
tão raro quanto parece — `onInputDeviceChanged` pipoca quando um controle Bluetooth reconecta
ou quando o Google TV renegocia o controle remoto.

## Como reproduzir

Chromecast/Google TV com um gamepad Bluetooth. Entrar num jogo e forçar `onInputDeviceChanged`
em rajada — desligar/ligar o controle, ou conectar um segundo device de input enquanto a
partida está carregando (a main já está ocupada com o `dlopen` do core, ver
[[2026-08-09-anr-inicializar-jogo-runongl-thread]]).

## Correção

1. `getGamePadsObservable()` agora usa `callbackFlow` e registra o listener com um `Handler`
   ligado a uma `HandlerThread` própria. O registro e a enumeração inicial também sobem por
   `Dispatchers.IO`, eliminando o IPC da main inclusive no primeiro `collect`.
2. Os eventos de add/change/remove invalidam o cache do device e reagendam uma única
   enumeração após 150 ms. Eventos em rajada são coalescidos pelo mesmo `Runnable`.
3. `LemuroidInputDeviceGamePad` consulta, em um único `hasKeys()`, a união das teclas usadas
   pelas heurísticas e guarda o resultado em `ConcurrentHashMap`, indexado por ID e validado
   também pelo descriptor. Add/change/remove invalidam o ID antes da próxima enumeração.
4. `isSupported()` passou a rejeitar source incompatível ou device virtual antes de consultar
   as teclas, evitando IPC desnecessário.

## Validação

- [x] `:lemuroid-app:compileFreeBundleDebugKotlin` — build concluído com sucesso.
- [x] Revisão estática confirma que `registerInputDeviceListener` não recebe mais `null` e que
      a enumeração de gamepads só é agendada na thread `lemuroid-input-devices`.
- [x] O cache é invalidado nos três callbacks e não reaproveita entrada se o Android reutilizar
      o mesmo ID para outro descriptor.
- [ ] Validação manual no Chromecast com pareamento/despareamento repetido requer o aparelho.

O `ktlintMainSourceSetCheck` também foi executado, mas o módulo já contém violações de estilo
anteriores e fora deste patch. Nenhuma violação reportada cai nas linhas alteradas por esta
correção.

## Lição

Callbacks de framework com `Handler = null` não são seguros quando o callback chama APIs que
podem atravessar Binder. Enumerações de input devem ter executor explícito, cache por ciclo de
vida do device e coalescência de mudanças, mesmo quando os eventos parecem raros.

## Acompanhamento

- Revisar separadamente os demais ANRs de `GameActivity` (errors 3813, 2602, 2490, 2486, 2153,
  1817, 1715, 1214, 1190, 1251): parte pode ter esta assinatura e parte corresponde ao
  `runOnGLThread` de [2026-08-09-anr-inicializar-jogo-runongl-thread.md](../open/2026-08-09-anr-inicializar-jogo-runongl-thread.md).
