# [BUG] Jogo com analógico morre no Android ≤ 7.1 — `kotlinx.datetime.Instant` do padkit exige `java.time` (API 26)

**Data:** 2026-10-05
**Status:** ✅ Resolvido em 2026-10-05 — *core library desugaring* no `:lemuroid-app`
**Severidade:** Alta — público-alvo (TV box 7.1, `minSdk 21`); todo jogo de 8 sistemas
**Branch:** version9
**Origem:** achado durante a validação do core PPSSPP (`2026-09-18-ppsspp-retro-run-terminate-abort.md`)

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

## Causa (confirmada)

O padkit `1.0.0-beta1` (pad touch, via `lemuroid-touchinput`) mede o duplo toque do analógico — que vira
L3/R3 — com `kotlinx.datetime`, e o `kotlinx-datetime-jvm` delega a `java.time`, que só existe a partir
da API 26. O estado do analógico nasce num `DisposableEffect` com `lastDownEvent = Instant.DISTANT_PAST`,
então o `Instant.<clinit>` roda ao **montar** o analógico, sem toque nenhum. O projeto não tinha *core
library desugaring*, nada no build avisou (o lint não lê bytecode de dependência; o AAR não declara
`coreLibraryDesugaringEnabled`) e o R8 não reescreve a chamada: em Android 5.0–7.1, todo jogo de N64,
PSP, DOS, 3DS, Dreamcast, GameCube e Amiga (e PSX com DualShock) caía ~1 s depois do boot — sempre que o
pad touch está na tela, isto é, sem gamepad conectado. O release caía igual.

## Análise (2026-10-05, antes da correção)

### Símbolos abertos

- **`padkit-android-1.0.0-beta1`** (`lib-release.aar` no cache do Gradle; `javap -c`). Só três classes
  referenciam `kotlinx.datetime`: `gg.padkit.handlers.AnalogPointerHandler`,
  `AnalogPointerHandler$Data` e `gg.padkit.controls.ControlAnalogKt`.
  - `AnalogPointerHandler$Data` — construtor default faz `lastDownEvent = Instant.DISTANT_PAST`, o que
    dispara `kotlinx.datetime.Instant.<clinit>`.
  - `AnalogPointerHandler.handle` (bytecode 509–555) — no primeiro dedo:
    `Clock.System.now() - lastDownEvent < Constants.DOUBLE_TAP_INTERVAL` → `pressed = true`. É o duplo
    toque no analógico que vira L3/R3 (`analogPressId`).
  - `ControlAnalog.kt:70` — o `Data` nasce num `DisposableEffect`: o crash é ao **montar** o analógico,
    não ao tocar nele.
  - `META-INF/com/android/build/gradle/aar-metadata.properties` não declara
    `coreLibraryDesugaringEnabled` — por isso o `checkAarMetadata` não exige nada e o build passa calado.
- **`kotlinx-datetime-jvm-0.6.2`** — `Instant.<clinit>` chama `java.time.Instant.ofEpochSecond(JJ)`
  (`DISTANT_PAST`/`DISTANT_FUTURE`) e lê `java.time.Instant.MIN/MAX`; `Clock.System.now()` delega a
  `java.time.Clock.systemUTC().instant()`. `java.time` é API 26.
- **`lemuroid-touchinput/.../layouts/shared/Controls.kt`** — `SecondaryAnalogLeft/Right` são os únicos
  chamadores de `LemuroidControlAnalog` → `ControlAnalog`. Usados pelos layouts `Amiga`, `DOS`,
  `Dreamcast`, `GameCube`, `N64`, `Nintendo3DS`, `PSP` e `PSXDualShock`.
- **`ControllerConfigs` / `GameSystem`** — N64, PSP, DOS (`DOS_AUTO`), 3DS, Dreamcast, GameCube e Amiga
  (4 entradas) têm só essa config: **todo** jogo desses sistemas cai. PSX: default é `PSX_STANDARD`
  (digital); só cai quem escolheu DualShock.
- **`MobileGameScreen` / `GameViewModelTouchControls.isTouchControllerVisible`** — o pad só é composto
  quando `getEnabledInputDevices().isEmpty()`. Com gamepad conectado o analógico nunca monta e o bug
  não aparece (TV box com controle escapa; celular, tablet e box usada por toque, não).
- **Código do projeto** — nenhum uso direto de `java.time`, `kotlinx.datetime`, `java.util.stream`,
  `Optional` ou `java.nio.file` em `lemuroid-app`, `retrograde-*`, `lemuroid-touchinput`,
  `lemuroid-metadata-libretro-db` (`grep`). Todo `java.time` do APK vem de biblioteca.
- **Build** — o `build.gradle.kts` raiz aplica `compileOptions` (só `source/targetCompatibility = 17`)
  a todo módulo Android em `subprojects { afterEvaluate }`. Ninguém liga
  `isCoreLibraryDesugaringEnabled`. O `android.enableD8.desugaring=true` do `gradle.properties` é o
  desugaring de **linguagem** (lambdas), não o de biblioteca. AGP efetivo é **8.7.1**, não o 8.4.0 do
  `deps.kt` (o `com.android.test` 8.7.1 do bloco `plugins` raiz vence; `app-metadata.properties` do
  build confirma), Gradle 8.10.2.
- **Lint** — `lemuroid-app/lint-baseline.xml` só tem `src/` do app e `build.gradle.kts`: o lint não lê o
  bytecode das dependências. O `NewApi`, que pegou o pitfall 12, nunca viu o padkit.

### Comandos que provaram algo

- **Reprodução (debug 1.17.24, `lemu_api25_2gb`)** — game id do Lumines via
  `adb shell run-as app.retrogamesystem.debug sqlite3 databases/retrograde "SELECT id … WHERE systemId='psp' AND fileName LIKE 'Lumines%'"`
  (= 40854) e
  `adb shell am start -a android.intent.action.VIEW -d "retrogamesystem://app.retrogamesystem.debug/play-game/id/40854"`.
  `[BOOT] PPSSPP v1.19.3…` às 14:56:26.673; `NoClassDefFoundError` às 14:56:28.007 com o stack acima
  (+ `ControlAnalogKt$$ExternalSyntheticLambda0` → `RememberEventDispatcher`). Tela: "The app hit an
  internal error…", `text2` = `Failed resolution of: Ljava/time/Instant;`.
- **Release (R8), confirmação estática** — `dexdump -d` nos dex do
  `lemuroid-app-free-bundle-arm64-v8a-release.apk` de 2026-09-30: `invoke-static
  Ljava/time/Instant;.ofEpochSecond(JJ)` e `Ljava/time/Clock;.systemUTC()` continuam lá (kotlinx-datetime
  ofuscado como `Z6/*`). O R8 não resolve — o release cai igual. A prova empírica em release fica para a
  validação da correção.
- **Auditoria do dex do mesmo release** (script que cruza os `type/field/method_ids` do dex com
  `platforms/android-35/data/api-versions.xml`, subindo superclasses/interfaces): **80** APIs
  `java.*`/`javax.*` acima da API 21 em código que o R8 manteve — `java.time` (kotlinx-datetime e
  `Duration`), `java.nio.file` (okio), `javax.net.ssl`/`java.security.cert` (Conscrypt, okhttp),
  `java.util.function`/`Optional`/`CompletableFuture`/`Map.getOrDefault` (API 24), `java.lang.invoke`
  (Retrofit). A primeira versão do script dava 107: tomava o primeiro caminho de herança em vez do menor
  (`Method.getName` saía API 26 porque o `api-versions.xml` o move para `Executable`). Triagem do que
  sobra depois da correção: ver Validação.

### Hipóteses descartadas

- **"O R8 resolve no release"** — não: o R8 só faz *outlining* de API (pitfall 12), não reescreve
  `java.time`. O dex do release tem as mesmas invocações.
- **Subir o padkit** — a 1.0.0 trocou `kotlinx.datetime` por `kotlin.time.TimeSource.Monotonic`
  (`Data.lastDownEvent: TimeMark`), que é o conserto certo; mas puxa Compose Multiplatform 1.8.2 (o app
  inteiro sairia do Compose 1.6 do BOM 2024.02.02 para 1.8) e `kotlin-stdlib` 2.1.21, que o
  `resolutionStrategy` raiz rebaixaria para 2.0.21 (risco de `NoSuchMethodError`), além de possível
  quebra de API nos ~45 layouts do `lemuroid-touchinput`. Desproporcional para um bugfix → backlog.
- **Trocar o relógio do padkit por `SystemClock`** (candidata original deste doc) — exigiria fork + AAR
  local do padkit (o mesmo custo de manutenção do LibretroDroid patcheado) para resolver um chamador só.
  O desugaring cobre qualquer biblioteca que use `java.time`.
- **Ligar o desugaring em todos os módulos (bloco `subprojects` raiz)** — desnecessário: quem produz o
  dex do APK é o `:lemuroid-app`, e ele desugariza tudo o que empacota, inclusive os módulos do projeto.
  A doc oficial só pede o flag em módulo de biblioteca para os testes instrumentados dele ou lint isolado
  — nenhum dos dois existe aqui. E o flag exige a dependência `coreLibraryDesugaring` em cada módulo que
  o liga.
- **`android.enableD8.desugaring=true` já cobriria** — é o desugaring de linguagem.

### Plano (executado)

1. `buildSrc/src/main/java/deps.kt`: `deps.libs.desugarJdkLibs =
   "com.android.tools:desugar_jdk_libs:2.1.5"` (a mais recente no Google Maven; exige AGP ≥ 8.0).
2. `lemuroid-app/build.gradle.kts`: bloco novo **no fim do arquivo** —
   `android { compileOptions { isCoreLibraryDesugaringEnabled = true } }` +
   `dependencies { coreLibraryDesugaring(deps.libs.desugarJdkLibs) }`, com o porquê. No fim porque o
   baseline do ktlint casa por número de linha e tem entradas até a 490 desse arquivo.
3. Provas: dex, AVD 7.1 (debug e release), celular Android 13, AVD 5.0, gates, auditoria do dex.
4. Documentar: pitfall 16 no `CLAUDE.md`; backlogs do verificador de dex e do padkit 1.0.0.

## Correção

- [deps.kt](../../../buildSrc/src/main/java/deps.kt): `desugarJdkLibs` = `desugar_jdk_libs:2.1.5`.
- [lemuroid-app/build.gradle.kts](../../../lemuroid-app/build.gradle.kts) (fim do arquivo):
  `isCoreLibraryDesugaringEnabled = true` + `coreLibraryDesugaring(deps.libs.desugarJdkLibs)`. O D8/R8
  reescreve `java.time` (e `java.util.stream`, `Optional`, defaults de `Map`…) de **todas** as
  dependências para `j$.*`, e o L8 empacota a biblioteca. Vale em todo aparelho, sem depender do
  `SDK_INT`.
- [audit_dex_api_level.py](../../../audit_dex_api_level.py) (raiz): auditoria do dex por classe
  chamadora contra o `api-versions.xml` — o que achou este bug em segundos e o que ainda falta como gate.
- `CLAUDE.md`: pitfall 16 + convenção "dependência nova ou atualizada: auditar o dex do release".

## Validação

- **Build:** `./gradlew :lemuroid-app:assembleFreeBundleDebug -PdevAbi=x86_64` → `BUILD SUCCESSFUL in
  8m 37s`, com `l8DexDesugarLibFreeBundleDebug`. APK debug x86_64: 148.021.645 → 149.179.866 bytes
  (a `j$` vai sem shrink no debug).
- **Dex (debug):** `kotlinx.datetime.Instant.<clinit>` agora faz
  `invoke-static Lj$/time/Instant;.ofEpochSecond(JJ)`; `Clock.System` usa `Lj$/time/Clock;.systemUTC`.
  Classes fora de `j$` que ainda citam `Ljava/time/`: **74 → 10** — `$$ExternalSyntheticAPIConversion`
  do D8 dentro de `SplashScreenViewProvider$ViewImpl31` e `AccessibilityNodeInfoCompat$Api34Impl` (só
  rodam em API 31+/34+) e commons-io/lang3/compress, que o app não importa (o R8 remove no release).
- **AVD `lemu_api25_2gb`, debug** — *Lumines* (id 40854): `[BOOT] PPSSPP` às 15:17:25, nenhum
  `NoClassDefFoundError`, pad com analógico na tela, PPSSPP a 55–60 fps. Toque segurado e **duplo
  toque** injetados por `sendevent` (< 200 ms entre os toques, o `DOUBLE_TAP_INTERVAL` do padkit):
  analógico ativo e jogo seguindo; arraste desloca o miolo. O desenho do analógico não distingue o L3 (o
  booleano do `foreground` vem de `getContinuousDirection`, é "analógico em uso"), mas todo primeiro
  dedo executa `Clock.System.now()`, `minus` e `compareTo` — o duplo toque só muda o resultado da
  comparação.
- **SM-A127M (Android 13, arm64), debug:** *Lumines* a 60 fps, arraste e toques no analógico, sem erro.
  Importa porque o código desugarizado usa `j$.time` em **todo** nível de API, não só abaixo da 26.
- **Release (R8 + L8):** `./gradlew :lemuroid-app:assembleFreeBundleRelease -PdevAbi=x86_64` →
  `BUILD SUCCESSFUL in 18m 46s`, com `l8DexDesugarLibFreeBundleRelease`. Instalação limpa no
  `lemu_api25_2gb` (id 41927 no prebuilt deste build): `[BOOT]` às 15:46:53, 60 fps, duplo toque e
  arraste no analógico, sem erro. Custo: o dex do L8 tem 480.668 bytes (**198.067 comprimido** no APK).
- **Auditoria do dex do release** (`audit_dex_api_level.py --mapping`), antes × depois:
  - antes (release de 2026-09-30): 80 APIs acima da 21. Além do `kotlinx-datetime`, `Map.getOrDefault`,
    `LinkedHashMap.remove(K,V)` e `java.util.function.*` (API 24) em código que o R8 manteve — que
    cairiam igual em Android 5–6 se executados. **O desugaring corrigiu esses de quebra.**
  - depois: fora da `j$` (wrappers de conversão da própria biblioteca) e do `java.util.function` que a
    `j$` passa a fornecer, só caminhos guardados, conferidos no bytecode das bibliotecas: okio 3.8.0
    (`FileSystem.<clinit>` tenta `Class.forName("java.nio.file.Files")` e cai em
    `JvmSystemFileSystem`); Conscrypt 2.5.2 (`Platform.provideTrustManagerByDefault()` = `false` no
    Android, `supportsX509ExtendedTrustManager()` = `SDK_INT > 23`, e o app usa o próprio `trustAll`
    em `ConscryptOkHttpHelper`); Retrofit 2.9 (`hasJava8Types` = `SDK_INT >= 24`); okhttp
    `Jdk9Platform` (nunca escolhida no Android); emoji2 `…_API24`.
  - com `--android`: a única chamada **sem guarda** fora de androidx/app é
    `gg.padkit.haptics.AndroidHapticGenerator → Context.getSystemService(Class)` (API 23) — bug à
    parte, abaixo. LibretroDroid `KtUtils → Looper.isCurrentThread` é guardado (`SDK_INT >= 23`).
- **AVD `lemu_api21_1gb` (Android 5.0) — não validável:** o `:game` cai antes, no haptics do padkit
  (`NoSuchMethodError: Context.getSystemService(Class)` em `PadKit(PadKit.kt:86)`), outro bug,
  registrado em `documentacao/bugs/open/2026-10-05-padkit-haptics-getsystemservice-android-5.md`.
  O mecanismo do desugaring é o mesmo do 7.1.
- **Gates:** `ktlintKotlinScriptCheck` (raiz e `:lemuroid-app`, onde estão as edições) passam.
  `lintFreeBundleDebug` e `ktlintMainSourceSetCheck` falham por passivo de commits anteriores
  (`2c02594`, `d0c76aa`) em arquivos que esta correção não toca — registrado em
  `documentacao/bugs/open/2026-10-05-lint-ktlint-gates-falhando-passivo-novo.md`.
- **Telemetria (item 2 do registro original):** não consultada — sem `JWT_SECRET_KEY`/`TRIAGEM_TOKEN`
  no ambiente, e a skill de triagem proíbe contornar. Assinatura a procurar: `Failed resolution of:
  Ljava/time/Instant;` com componente `game` (o handler do `CrashTelemetry` reporta exceção Java do
  `:game`).

## Lição

- **O lint não lê bytecode de biblioteca.** O `NewApi` que pegou o pitfall 12 nunca viu o padkit, a
  metadata do AAR não obrigou desugaring e o R8 só faz outlining. Biblioteca publicada "para JVM"
  (`kotlinx-datetime-jvm`) assume `java.time`. O dex final é a fonte da verdade: `dexdump` +
  `api-versions.xml` mostraram em segundos o que nenhum gate do build mostrava — e acharam mais dois
  problemas da mesma família (os `Map.getOrDefault` corrigidos de quebra e o haptics do padkit).
- **Validar no `minSdk`, não só "num Android velho".** O AVD 7.1 escondia o crash que derruba todo jogo
  no 5.0–5.1.
- **No release, o nome do outline do R8 mente sobre quem chama:** ele reaproveita o mesmo outline de API
  entre classes. Atribuição tem que subir para quem invoca o outline.

## Fora de escopo (registrado)

- `documentacao/bugs/open/2026-10-05-padkit-haptics-getsystemservice-android-5.md` — Android 5.0–5.1.
- `documentacao/bugs/open/2026-10-05-lint-ktlint-gates-falhando-passivo-novo.md` — gates quebrados.
- `documentacao/backlogs/2026-10-05-verificador-api-level-dex.md` — transformar a auditoria em gate.
- `documentacao/backlogs/2026-10-05-subir-padkit-1-0-0-compose-kotlin.md` — padkit 1.0.0.
