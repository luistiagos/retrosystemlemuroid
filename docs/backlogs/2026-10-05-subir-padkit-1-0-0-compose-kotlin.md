# [BACKLOG] Subir o padkit de 1.0.0-beta1 para 1.0.0 — exige subir Compose e Kotlin juntos

**Data:** 2026-10-05
**Origem:** hipótese descartada de
`documentacao/bugs/done/2026-10-05-padkit-kotlinx-datetime-java-time-android-7.md`

## Situação

O `lemuroid-touchinput` usa `io.github.swordfish90:padkit:1.0.0-beta1`. No beta1, o duplo toque do
analógico (`AnalogPointerHandler`) mede o intervalo com `kotlinx.datetime.Clock.System.now()`, que no
JVM é `java.time` (API 26). O crash em Android 5–7 foi corrigido no app com *core library desugaring*,
não no padkit — o beta1 continua usando **relógio de parede** (`System.currentTimeMillis` via
`j$.time`), que pode saltar com um ajuste de NTP no meio de um toque.

O upstream já corrigiu: na **1.0.0** (Maven Central; há também `1.1.0-alpha1`),
`AnalogPointerHandler.Data.lastDownEvent` é um `kotlin.time.TimeMark` de
`TimeSource.Monotonic.markNow()` e o POM não depende mais de `kotlinx-datetime`.

## Por que não foi feito junto com o bug

O POM da `padkit-android:1.0.0` puxa:

| dependência | versão no padkit 1.0.0 | versão no projeto hoje |
|---|---|---|
| `org.jetbrains.compose.runtime:runtime` / `foundation` / `material3` | 1.8.2 | Compose do BOM `2024.02.02` (Jetpack 1.6.x) |
| `kotlin-stdlib` | 2.1.21 | 2.0.21 — e o `resolutionStrategy` do `build.gradle.kts` raiz força **todo** `org.jetbrains.kotlin` para `deps.versions.kotlin` |
| `material-icons-extended` | 1.7.3 | do BOM |
| `kotlinx-collections-immutable-jvm` | 0.3.7 | 0.3.8 |

Ou seja: subir o padkit sobe o Compose do app inteiro (mobile + TV) de 1.6 para 1.8, e o stdlib que o
padkit espera (2.1) seria rebaixado à força para 2.0.21 — chamada a API só do 2.1 viraria
`NoSuchMethodError` em runtime. Além disso, a API pública do padkit pode ter mudado entre beta1 e 1.0.0,
e o `lemuroid-touchinput` tem ~45 layouts que dependem dela.

## O que fazer quando for a hora

1. Subir Kotlin (`deps.versions.kotlin` ≥ 2.1.21) e o BOM do Compose para uma versão com Compose 1.8,
   validando o app inteiro (mobile e TV) — esse é o trabalho de verdade.
2. Só então `deps.versions.padkit = "1.0.0"` e compilar o `lemuroid-touchinput`; corrigir o que a API
   nova quebrar.
3. Validar o duplo toque no analógico (L3/R3) num sistema com analógico (N64/PSP) — no AVD
   `lemu_api25_2gb` e num aparelho real.
4. O desugaring do `:lemuroid-app` **fica**: cobre `java.time` de qualquer outra dependência
   (kotlinx-coroutines-time, okhttp, androidx.work…), não só do padkit.

> ⚠️ Subir para a 1.0.0 **não** resolve o crash do Android 5.0–5.1: o `AndroidHapticGenerator.buildVibrator`
> da 1.0.0 ainda chama `getSystemService(Vibrator::class.java)` (API 23) abaixo da API 31. O crash foi
> corrigido no build, sem fork (`PadkitGetSystemServiceCompat`, no fim do `lemuroid-app/build.gradle.kts`;
> ver `documentacao/bugs/done/2026-10-05-padkit-haptics-getsystemservice-android-5.md`), e a instrumentação
> vale para a 1.0.0 também: **mantê-la** ao subir. Ela não mexe no relógio de parede do analógico — esse
> continua sendo o motivo deste backlog.
