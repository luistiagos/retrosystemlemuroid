# [BUG] Todo jogo da UI mobile morre no Android 5.0–5.1 — haptics do padkit chama `Context.getSystemService(Class)` (API 23)

**Data:** 2026-10-05
**Status:** Aberto (causa confirmada no stack e no bytecode; não corrigido)
**Severidade:** Alta para API 21–22 (`minSdk`) — nenhum jogo abre na UI mobile, com ou sem gamepad
**Branch:** version9
**Origem:** validação no `lemu_api21_1gb` de
`documentacao/bugs/done/2026-10-05-padkit-kotlinx-datetime-java-time-android-7.md`

## Sintoma

AVD `lemu_api21_1gb` (Android 5.0, x86_64), debug 1.17.24 (já com o desugaring do bug acima), *Lumines*
(PSP) por deep link: o `:game` cai na primeira composição da tela de jogo, antes do `[BOOT]` do core,
e vai para a tela de erro de app:

```
E/BaseGameActivity: java.lang.NoSuchMethodError: No virtual method getSystemService(Ljava/lang/Class;)Ljava/lang/Object;
    in class Landroid/content/Context; or its super classes
    at gg.padkit.haptics.AndroidHapticGenerator.buildVibrator(HapticGenerator.android.kt:59)
    at gg.padkit.haptics.AndroidHapticGenerator.<init>(HapticGenerator.android.kt:36)
    at gg.padkit.haptics.HapticGenerator_androidKt.rememberHapticGenerator(HapticGenerator.android.kt:92)
    at gg.padkit.PadKitKt.PadKit(PadKit.kt:86)
    at com.swordfish.lemuroid.app.mobile.feature.game.MobileGameScreenKt$MobileGameScreen$1.invoke(MobileGameScreen.kt:114)
    at androidx.compose.foundation.layout.BoxWithConstraintsKt$BoxWithConstraints…
W/GameLaunchTaskHandler: Non-emulator failure in game process: No virtual method getSystemService(…)
```

Reproduzido três vezes. **Efeito colateral:** depois do crash o processo `:game` não morre — laço de
`RuntimeException: System.exit returned normally, while it was supposed to halt JVM.` em
`BaseGameActivity$finishAndExitProcess$1.invokeSuspend(BaseGameActivity.kt:594)`, que enche o buffer do
logcat em segundos e resiste a `am force-stop` (só `run-as … kill -9` resolveu).

## Causa (confirmada)

- `padkit-android:1.0.0-beta1`, `AndroidHapticGenerator.buildVibrator`: `SDK_INT >= 31` →
  `getSystemService(VibratorManager::class.java)`; senão `getSystemService(Vibrator::class.java)` — a
  sobrecarga `getSystemService(Class)` é **API 23** e não tem guarda. Os `VibrationEffect` (API 26) são
  guardados (`SDK_INT >= 26`, senão `null`).
- `rememberHapticGenerator()` constrói o `AndroidHapticGenerator` **sempre** (não recebe o
  `HapticFeedbackType`), e o `PadKit` o chama em linha reta (`PadKitKt`, bytecode 866). Desligar a
  vibração nos ajustes não evita o crash.
- `PadKit` é a raiz da `MobileGameScreen` — a `GLRetroView` fica dentro dele. Logo: todo jogo, todo
  sistema, com ou sem gamepad, em API 21–22. API 23+ não é afetada.
- No dex do APK, `gg.padkit.haptics.AndroidHapticGenerator` é o único chamador de
  `Context.getSystemService(Class)` no caminho do `:game` (os demais são `androidx.core` `*Api23Impl`
  e `ShortcutManagerCompat`/`ShortcutsGenerator`, do processo principal).
- **Introduzido pelo `25a4b58` (2026-04-21, ~1.17.0)**, que baixou o `minSdkVersion` de 23 (valor do
  upstream e do commit raiz) para 21 sem auditar as dependências: com minSdk 23 esse código nunca rodava
  abaixo da API 23. Desde então nenhum jogo abre no Android 5.0–5.1 pela UI mobile.
- **Não é coberto pelo desugaring** (é API de framework) e **subir o padkit não resolve**: a 1.0.0 tem o
  mesmo `buildVibrator`.

## O que falta

1. Correção candidata: padkit patcheado — fork do `1.0.0-beta1` com `ContextCompat.getSystemService`
   (ou `getSystemService(Context.VIBRATOR_SERVICE)`) no `buildVibrator`, empacotado como AAR local no
   molde do `libretrodroid-patched.aar`; mandar o mesmo patch upstream. No mesmo fork, trocar o
   `kotlinx.datetime` por `TimeSource.Monotonic` (o conserto da 1.0.0) deixa o padkit sem depender do
   desugaring.
2. Investigar o laço do `exitProcess` no Android 5 (`BaseGameActivity.kt:594`): o `Runtime.exit` do
   libcore antigo parece retornar quando já há um shutdown em andamento, e o `exitProcess` do Kotlin
   transforma isso em exceção — que volta ao handler que chama o `exitProcess` de novo.
3. Telemetria: quantos usuários em API 21–22 (define a prioridade).
4. Validar no `lemu_api21_1gb`. Esse AVD não tem armazenamento externo (sem `sdcard.img`), e a pasta de
   ROMs vira `/roms`: para testar, pôr a ROM em `files/` do app via `run-as` e apontar a `fileUri` da
   linha para lá, ou lançar o AVD com `-sdcard` (ver "SD card / segundo volume" no `CLAUDE.md`).
