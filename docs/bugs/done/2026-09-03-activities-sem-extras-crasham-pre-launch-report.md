# [BUG] Activities lançadas sem os extras obrigatórios crasham — `GameActivity.onCreate` ignora o early-return do `BaseGameActivity`

**Data:** 2026-09-03
**Status:** Resolvido ✅ (2026-09-10)
**Severidade:** Baixa (não alcançável por usuário final — as activities não são `exported`; o gatilho é o **Pre-Launch Report** do Google Play, e é lá que o crash aparece para nós)
**Branch:** version9
**Origem:** telemetria `retrogamesystem/main` e `retrogamesystem/game`
(`ActivityThread.java::android.app.ActivityThread.performLaunchActivity`)
**Errors (serviço):** 15 ocorrências — 1665, 1666, 1668, 1669, 1678, 1679, 1681, 1683, 5245, 5246, 5664, 5665, 5672, 5673, 5674
**Aparelho:** Google Pixel 8 Pro (emulador) e dispositivos de teste/usuário, Android 14/15/16
**Observado em:** 2026-08-22 e nova rodada em 2026-09-10 (errors 5245, 5246, 5664, 5665, 5672, 5673, 5674 em `TVGamePadShortcutBindingActivity`)

---

## Sintoma

Cinco activities diferentes morrem em `performLaunchActivity`, sempre pela ausência do extra que
elas exigem, sempre na mesma ordem e no mesmo intervalo de ~10 s entre uma e a seguinte:

| Error | Activity | Exceção |
|---|---|---|
| 1665, 1678 | `mobile.feature.game.GameActivity` | `lateinit property game has not been initialized` |
| 1666, 1679 | `mobile.feature.gamemenu.GameMenuActivity` | `InvalidParameterException: Missing EXTRA_CORE_OPTIONS` |
| 1668, 1681 | `mobile.feature.input.GamePadBindingActivity` | `IllegalArgumentException: REQUEST_DEVICE has not been passed` |
| 1669, 1683 | `mobile.feature.input.GamePadShortcutBindingActivity` | idem |
| 5245, 5246, 5664, 5665, 5672–5674 | `tv.input.TVGamePadShortcutBindingActivity` | idem |

```
java.lang.RuntimeException: Unable to start activity
  ComponentInfo{app.retrogamesystem/…GameActivity}: l6.D: lateinit property game has not been initialized
	at android.app.ActivityThread.performLaunchActivity(ActivityThread.java:3645)
	…
Caused by: l6.D: lateinit property game has not been initialized
	at i4.c.F0(SourceFile:8)
	at com.swordfish.lemuroid.app.mobile.feature.game.GameActivity.j1(SourceFile:12)   <- startGameService
	at com.swordfish.lemuroid.app.mobile.feature.game.GameActivity.onCreate(SourceFile:4)
```

**O gatilho é um crawler, não um usuário.** Pixel 8 Pro em `x86_64` é o parque de emuladores do
Google; a sequência é a assinatura do Robo test do **Pre-Launch Report**, que lança as activities
declaradas uma a uma, sem extras. Duas execuções idênticas com 19 min de diferença = duas rodadas
do relatório.

## Causa-raiz

O `BaseGameActivity` **tinha** o guard, e ele funcionava:

```kotlin
game = intent.getSerializableExtra(EXTRA_GAME) as? Game ?: run { finish(); return }
systemCoreConfig = intent.getSerializableExtra(EXTRA_SYSTEM_CORE_CONFIG) as? SystemCoreConfig ?: run { finish(); return }
system = GameSystem.findByIdOrNull(game.systemId) ?: run { finish(); return }
```

O problema é que esse `return` **só sai de `BaseGameActivity.onCreate`** — quem chamou foi
`GameActivity.onCreate`, que continuava executando as próprias linhas:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)     // <- abortou lá dentro, mas devolveu normalmente
    setShowWhenLocked(true)
    setTurnScreenOn(true)
    startGameService()                     // <- lê `game`, que nunca foi atribuída
}
```

`startGameService()` faz `GameService.startService(applicationContext, game)` — o acesso ao
`lateinit var game` lança `UninitializedPropertyAccessException`, que é exatamente o `l6.D` do
stack ofuscado. O `TVGameActivity` tinha o mesmo formato (`super.onCreate()` seguido de
`initializeFlows()`); não crashava só porque o fluxo que ele inicia não lê `game`.

As outras activities são o mesmo padrão em variantes mais simples: lançam a exceção direto do
`onCreate` quando o extra falta, em vez de `finish()`:

- `GameMenuActivity` e **`TVGameMenuActivity`** (esta ainda sem ocorrência na telemetria, mas com
  o mesmo `throw InvalidParameterException("Missing EXTRA_GAME")`);
- as quatro activities de binding de controle, via construtor de `InputBindingUpdater` /
  `ShortcutBindingUpdater`, que fazia o `parseExtras` já na inicialização da propriedade `extras`.
  O `ShortcutBindingUpdater` ainda tinha um segundo caminho: `GameShortcutType.valueOf` lança com
  string inválida.

## Alcance real

Nenhuma dessas activities tem `android:exported="true"` nem intent-filter no
[AndroidManifest.xml](../../../lemuroid-app/src/main/AndroidManifest.xml), e o `targetSdk` é 35 —
logo o default é `exported=false`. Um app de terceiros **não** consegue lançá-las — e o shell
também não: num build *user* (moto g86, Android 16), `adb shell am start -n …/.GameActivity`
devolve `SecurityException: Permission Denial: … not exported from uid 10686`, e
`run-as <pkg> am start` também falha (`package=com.android.shell does not belong to uid=10686`).
O Robo test consegue porque roda **instrumentado, no uid do próprio app** — e para o próprio uid
`exported` não se aplica.

Portanto: **não havia impacto em usuário final.** O custo era (a) o Pre-Launch Report acusar os
crashes em toda submissão, e (b) ruído por rodada na telemetria.

## Como reproduzir

O `am start` do shell **não** serve (ver "Alcance real"). Para simular o Robo test é preciso um
build **temporário** que exporte as activities — um overlay em
`lemuroid-app/src/debug/AndroidManifest.xml` (o source set `debug` não tem manifest próprio):

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <activity android:name="com.swordfish.lemuroid.app.mobile.feature.game.GameActivity" android:exported="true" />
        <!-- idem para as outras 7 activities abaixo -->
    </application>
</manifest>
```

Com ele instalado (`assembleFreeBundleDebug` → `adb install -r`), lançar cada uma sem extras e
olhar `adb logcat -b events` (`wm_finish_activity` / `am_crash`):

```
adb shell am start -W -n app.retrogamesystem.debug/com.swordfish.lemuroid.app.mobile.feature.game.GameActivity
adb shell am start -W -n app.retrogamesystem.debug/com.swordfish.lemuroid.app.mobile.feature.gamemenu.GameMenuActivity
adb shell am start -W -n app.retrogamesystem.debug/com.swordfish.lemuroid.app.mobile.feature.input.GamePadBindingActivity
adb shell am start -W -n app.retrogamesystem.debug/com.swordfish.lemuroid.app.mobile.feature.input.GamePadShortcutBindingActivity
adb shell am start -W -n app.retrogamesystem.debug/com.swordfish.lemuroid.app.tv.game.TVGameActivity
adb shell am start -W -n app.retrogamesystem.debug/com.swordfish.lemuroid.app.tv.gamemenu.TVGameMenuActivity
adb shell am start -W -n app.retrogamesystem.debug/com.swordfish.lemuroid.app.tv.input.TVGamePadBindingActivity
adb shell am start -W -n app.retrogamesystem.debug/com.swordfish.lemuroid.app.tv.input.TVGamePadShortcutBindingActivity
```

> ⚠️ **Apagar o overlay e reinstalar um build limpo logo depois.** Activity exportada num build
> que sai da máquina reabre exatamente a superfície que o `exported=false` fecha.

## Correção

1. **[BaseGameActivity.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/BaseGameActivity.kt)
   — `onCreate` agora é `final`**, e a base chama um hook `protected open fun onGameCreated()` na
   última linha, depois de toda a inicialização. Se o guard aborta, o hook nunca roda.
   [GameActivity](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/game/GameActivity.kt)
   (`setShowWhenLocked`/`setTurnScreenOn`/`startGameService`) e
   [TVGameActivity](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/game/TVGameActivity.kt)
   (`initializeFlows`) passaram para o hook. A ordem de execução é a mesma de antes — o código da
   subclasse já rodava depois de todo o `super.onCreate()`.
   - **Por que `final` e não uma flag `initializationFailed`:** a flag depende de cada subclasse
     lembrar de checá-la, que é exatamente a armadilha que produziu o bug. Com `final`, uma
     subclasse nova que tente sobrescrever `onCreate` não compila.
2. **[GameMenuActivity](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/gamemenu/GameMenuActivity.kt)
   e [TVGameMenuActivity](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/gamemenu/TVGameMenuActivity.kt)**:
   o montador do `GameMenuRequest` virou `parseGameMenuRequest(): GameMenuRequest?`, com
   `?: return null` onde antes havia `throw`; o `onCreate` faz `finish(); return` com `null`.
   Os extras opcionais mantêm exatamente os defaults anteriores.
3. **[InputBindingUpdater](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/input/InputBindingUpdater.kt)
   e [ShortcutBindingUpdater](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/input/ShortcutBindingUpdater.kt)**:
   construtor `private` recebendo `IntentExtras` já validado, e uma fábrica
   `fromIntent(inputDeviceManager, scope, intent)` que devolve `null` quando o extra falta (ou,
   no de atalho, quando o `REQUEST_SHORTCUT_TYPE` não é um `GameShortcutType` válido). As quatro
   activities de binding (mobile e TV) fazem `?: run { finish(); return }`. Com `finish()` no
   `onCreate` a activity vai direto para `onDestroy`, sem ganhar foco — os `onKeyDown`/`onKeyUp`/
   `onGenericMotionEvent` que leem o `lateinit` do updater nunca chegam a rodar.
4. Conferidas as demais activities não-exportadas do manifesto (`StorageFrameworkPickerLauncher`,
   `TVFolderPickerActivity`, `TVFolderPickerLauncher`, `TVSettingsActivity`, `GameCrashActivity`):
   nenhuma exige extra.
5. Regra registrada em **Convenções de Código** no `CLAUDE.md`.

## Validação

- `.\gradlew.bat :lemuroid-app:compileFreeDynamicDebugKotlin` → **BUILD SUCCESSFUL**.
  Nenhum warning novo: os avisos nos arquivos tocados são as deprecações de
  `getSerializable`/`getParcelable` e o unchecked cast de `Array<LemuroidCoreOption>` no
  `TVGameMenuActivity`, todos já presentes antes.
- **Em aparelho** — moto g86 5G, Android 16, arm64, `freeBundle` debug 1.17.19 com o overlay
  temporário de "Como reproduzir": as 8 activities lançadas sem extras deram `Status: ok`;
  nenhum `FATAL EXCEPTION`/`AndroidRuntime` nos buffers `main` e `crash`; no buffer `events`, um
  `wm_finish_activity … app-request` para **cada uma das 8** (fecharam por conta própria) e
  nenhum `am_crash`/`am_anr`/`am_proc_died`.
- Não houve rodada "antes" no aparelho: o crash pré-correção está documentado pelos 15 errors com
  stack, e sem o overlay o shell nem consegue lançar as activities.
- A garantia estrutural é verificada pelo compilador: com `onCreate` `final`, nenhuma subclasse
  de `BaseGameActivity` consegue voltar a rodar código depois do abort.
- Confirmação definitiva: a próxima rodada do Pre-Launch Report sem esses errors na telemetria.

## Lição

`finish(); return` dentro de uma superclasse **não interrompe o `onCreate` da subclasse** — o
`super.onCreate()` simplesmente retorna e o resto do método filho roda com o estado pela metade.
Toda vez que uma classe base aborta a inicialização, ela precisa **sinalizar** o aborto, não
apenas voltar — e a forma mais segura de sinalizar é não deixar a subclasse ter onde rodar:
`onCreate` `final` + hook chamado só no caminho feliz. Uma flag pública seria mais uma coisa para
cada subclasse esquecer. Aqui o sintoma foi um `lateinit` não inicializado; podia ter sido pior,
porque `startGameService()` já teria subido um foreground service para um jogo que não existe.

E, independente disso: activity com extra obrigatório tem que tratar a ausência com `finish()`.
"Não é exportada, então só nós a lançamos" é falso na prática — o Robo test do Google lança todas,
porque roda no uid do app. Pelo mesmo motivo, `adb shell am start` **não** reproduz esse tipo de
crash num build user: para testar é preciso exportar temporariamente.
