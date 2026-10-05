# [BUG] Jogo com analógico morre no Android ≤ 7.1 — `kotlinx.datetime.Instant` do padkit exige `java.time` (API 26)

- **Data:** 2026-10-05
- **Status:** Aberto (achado de passagem; não investigado a fundo)
- **Severidade:** Alta — público-alvo (TV box 7.1, `minSdk 21`)
- **Branch:** version9

## Sintoma

No AVD `lemu_api25_2gb` (Android 7.1, x86_64), build `freeBundleDebug` 1.17.23, abrir *Lumines* (PSP):
o core carrega (`[BOOT] PPSSPP …`) e ~1 s depois o processo `:game` cai na tela de erro de app.
Duas tentativas, mesmo resultado:

```
W GameLaunchTaskHandler: Non-emulator failure in game process: Failed resolution of: Ljava/time/Instant;
E BaseGameActivity: java.lang.NoClassDefFoundError: Failed resolution of: Ljava/time/Instant;
    at kotlinx.datetime.Instant.<clinit>(Instant.kt:95)
    at gg.padkit.handlers.AnalogPointerHandler$Data.<init>(AnalogPointerHandler.kt:35)
    at gg.padkit.controls.ControlAnalogKt.ControlAnalog_3zYC3QE$lambda$3$lambda$2(ControlAnalog.kt:70)
    at androidx.compose.runtime.DisposableEffectImpl.onRemembered(Effects.kt:83)
```

## Causa provável (a confirmar)

`io.github.swordfish90:padkit-android:1.0.0-beta1` (via `lemuroid-touchinput`, `api(deps.libs.padkit)`)
usa `kotlinx.datetime.Instant` (`kotlinx-datetime-jvm 0.6.2`), que no JVM delega a `java.time.Instant`
— API 26. O projeto **não** tem `coreLibraryDesugaring` (`grep -rn desugar` nos `.kts` = nada).
A construção acontece no `DisposableEffect` do `ControlAnalog`, ou seja, ao **montar o pad** — qualquer
sistema cujo layout tenha analógico (PSP, N64, PSX com analógico, …) cai no boot.

## O que falta

1. Confirmar em release (R8) num Android 5–7 — o mesmo vale para a família do pitfall 12.
2. Ver na telemetria se há `NoClassDefFoundError … java/time` (talvez mascarado como "erro de app").
3. Correção candidata: `isCoreLibraryDesugaringEnabled = true` + `coreLibraryDesugaring(desugar_jdk_libs)`
   nos módulos Android (cobre `java.time`), ou trocar o relógio do padkit por `SystemClock`.

Encontrado durante a validação do core PPSSPP (`2026-09-18-ppsspp-retro-run-terminate-abort.md`).
