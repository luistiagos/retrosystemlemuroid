# [BUG] Crash ao reiniciar GameService pelo SO — `NullPointerException: Parameter specified as non-null is null: method GameService.onStartCommand, parameter intent`

**Data:** 2026-09-18
**Status:** Resolvido — intent nulo tratado no callback.
**Severidade:** Alta (crash do processo de jogo).
**Branch:** version9

- **Detectado em:** 2026-09-12 10:14 (telemetria de produção)
- **Origem:** telemetria `retrogamesystem/game` (`ActivityThread.java::android.app.ActivityThread.handleServiceArgs`)
- **Errors (serviço):** 5943 (1 ocorrência)
- **Classe:** crash
- **Reincidência:** primeira vez detectado (Samsung SM-A556E, Android 14/15, app 1.17.19)

---

## Sintoma

O sistema Android tenta reiniciar ou entregar argumentos ao `GameService` e o app sofre um crash com a seguinte exceção:

```
java.lang.RuntimeException: Unable to start service com.swordfish.lemuroid.app.mobile.feature.game.GameService@52b275d with null
	at android.app.ActivityThread.handleServiceArgs(ActivityThread.java:6469)
	at android.app.ActivityThread.-$$Nest$mhandleServiceArgs(ActivityThread.java:0)
	at android.app.ActivityThread$H.handleMessage(ActivityThread.java:3111)
	at android.os.Handler.dispatchMessage(Handler.java:132)
	at android.os.Looper.loopOnce(Looper.java:288)
	at android.os.Looper.loop(Looper.java:392)
	at android.app.ActivityThread.main(ActivityThread.java:10346)
Caused by: java.lang.NullPointerException: Parameter specified as non-null is null: method com.swordfish.lemuroid.app.mobile.feature.game.GameService.onStartCommand, parameter intent
	at com.swordfish.lemuroid.app.mobile.feature.game.GameService.onStartCommand(GameService.kt:43)
	at android.app.ActivityThread.handleServiceArgs(ActivityThread.java:6453)
```

## Causa raiz

Confirmada no código-fonte em [GameService.kt:42-46](file:///c:/projects/lemuroid/Lemuroid/lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/game/GameService.kt#L42-L46):

```kotlin
override fun onStartCommand(
    intent: Intent,
    flags: Int,
    startId: Int,
): Int {
```

Na API do Android (`android.app.Service`), a assinatura oficial de `onStartCommand` é:
`public int onStartCommand(Intent intent, int flags, int startId)`
A documentação oficial do Android adverte expressamente:
> *"The Intent supplied to `startService(Intent)`, as given. This may be null if the service is being restarted after its process has gone away, and it had previously returned anything except START_STICKY_COMPATIBILITY."*

Ao declarar o parâmetro como não-anulável (`intent: Intent`), o compilador do Kotlin insere uma checagem intrínseca de nulidade (`Intrinsics.checkNotNullParameter(intent, "intent")`). Quando o sistema Android invoca o callback passando `null`, o Kotlin rejeita com `NullPointerException`.

## Como reproduzir

1. Iniciar o jogo (que ativa o `GameService`).
2. Simular reinício do serviço pelo SO via adb ou kill de processo onde o sistema redispacha a chamada com intent nulo.
3. Crash imediato de NPE no `onStartCommand`.

## Correção aplicada

Em [GameService.kt:42-46](file:///c:/projects/lemuroid/Lemuroid/lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/game/GameService.kt#L42-L46):

```kotlin
override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
): Int {
    if (intent == null) {
        stopSelf(startId)
        return START_NOT_STICKY
    }
    val game =
        kotlin.runCatching {
            intent.extras?.getSerializable(EXTRA_GAME) as Game?
        }.getOrNull()

    displayNotification(game)
    return START_NOT_STICKY
}
```

## Validação

- Revisão do diff: intent nulo chama `stopSelf(startId)` (respeitando solicitações mais recentes) e retorna antes de acessar extras ou criar notificações. O fluxo com intent válido permanece igual.
- `./gradlew.bat :lemuroid-app:compileFreeBundleDebugKotlin --console=plain`: BUILD SUCCESSFUL (40 s). Aviso de API obsoleta em `getSerializable`, já existente no fluxo original.
- `git diff --check` no arquivo alterado: sem erros de whitespace.
- Validação em aparelho (`moto g86 5G`, device `ZY32LMNN9B`): `:lemuroid-app:installFreeBundleDebug` instalou `lemuroid-app-free-bundle-arm64-v8a-debug.apk` com sucesso.
- `GameService` iniciado no aparelho pelo UID do app debug com `adb shell run-as app.retrogamesystem.debug am start-foreground-service --user 0 -n app.retrogamesystem.debug/com.swordfish.lemuroid.app.mobile.feature.game.GameService`; o processo `app.retrogamesystem.debug:game` permaneceu vivo (`pid 8480`), `dumpsys activity services` mostrou `startCommandResult=2` (`START_NOT_STICKY`) e o logcat não registrou `AndroidRuntime`/`FATAL EXCEPTION`.
- Limitação: ADB não consegue enviar literalmente `Intent == null` via `am start-foreground-service`; o teste em aparelho cobre instalação e execução do callback sem extras, enquanto a prevenção específica do null foi validada pela assinatura `Intent?` e pelo guard clause antes de acessar `intent.extras`.

## Lição

Callbacks de plataforma que aceitam null devem declarar a nulabilidade em Kotlin; um try/catch no corpo não intercepta a checagem de parâmetro gerada pelo compilador.
