# [BUG] Activities lançadas sem os extras obrigatórios crasham — `GameActivity.onCreate` ignora o early-return do `BaseGameActivity`

**Data:** 2026-09-03
**Status:** 🔴 Aberto — causa-raiz confirmada no código; sem correção aplicada
**Severidade:** Baixa (não alcançável por usuário final — as activities não são `exported`; o gatilho é o **Pre-Launch Report** do Google Play, e é lá que o crash aparece para nós)
**Branch:** version9
**Origem:** telemetria `retrogamesystem/main` e `retrogamesystem/game`
(`ActivityThread.java::android.app.ActivityThread.performLaunchActivity`)
**Errors (serviço):** 8 ocorrências — 1665, 1666, 1668, 1669, 1678, 1679, 1681, 1683
**Aparelho:** Google Pixel 8 Pro, **abi=x86_64** (emulador), Android 14 (sdk 34), app 1.17.9
**Observado em:** 2026-08-22 01:36:28 → 01:37:00 e 01:55:07 → 01:55:39 (duas passadas idênticas, 19 min de intervalo)

---

## Sintoma

Quatro activities diferentes morrem em `performLaunchActivity`, sempre pela ausência do extra que
elas exigem, sempre na mesma ordem e no mesmo intervalo de ~10 s entre uma e a seguinte:

| Error | Activity | Exceção |
|---|---|---|
| 1665, 1678 | `mobile.feature.game.GameActivity` | `lateinit property game has not been initialized` |
| 1666, 1679 | `mobile.feature.gamemenu.GameMenuActivity` | `InvalidParameterException: Missing EXTRA_CORE_OPTIONS` |
| 1668, 1681 | `mobile.feature.input.GamePadBindingActivity` | `IllegalArgumentException: REQUEST_DEVICE has not been passed` |
| 1669, 1683 | `mobile.feature.input.GamePadShortcutBindingActivity` | idem |

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

O `BaseGameActivity` **tem** o guard, e ele funciona
([BaseGameActivity.kt:103-105](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/BaseGameActivity.kt#L103-L105)):

```kotlin
game = intent.getSerializableExtra(EXTRA_GAME) as? Game ?: run { finish(); return }
systemCoreConfig = intent.getSerializableExtra(EXTRA_SYSTEM_CORE_CONFIG) as? SystemCoreConfig ?: run { finish(); return }
system = GameSystem.findByIdOrNull(game.systemId) ?: run { finish(); return }
```

O problema é que esse `return` **só sai de `BaseGameActivity.onCreate`** — quem chamou foi
`GameActivity.onCreate`, que continua executando as próprias linhas
([GameActivity.kt:12-17](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/game/GameActivity.kt#L12-L17)):

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)     // <- abortou lá dentro, mas devolveu normalmente
    setShowWhenLocked(true)
    setTurnScreenOn(true)
    startGameService()                     // <- lê `game`, que nunca foi atribuída
}
```

`startGameService()` faz `GameService.startService(applicationContext, game)`
([GameActivity.kt:31-33](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/game/GameActivity.kt#L31-L33))
— o acesso ao `lateinit var game` lança `UninitializedPropertyAccessException`, que é exatamente
o `l6.D` do stack ofuscado.

As outras três activities são o mesmo padrão em variantes mais simples: lançam a exceção
direto do `onCreate` quando o extra falta, em vez de `finish()`.

## Alcance real

As quatro activities **não têm `android:exported="true"` nem intent-filter** no
[AndroidManifest.xml](../../lemuroid-app/src/main/AndroidManifest.xml) (linhas 88, 116, 121, 125),
e o `targetSdk` é 35 — logo o default é `exported=false`. Um app de terceiros **não** consegue
lançá-las. Quem consegue é o shell (`am start` com privilégio), que é justamente o que o Robo
test usa.

Portanto: **não há impacto em usuário final.** O custo é (a) o Pre-Launch Report acusar 4 crashes
em toda submissão, e (b) 8 eventos de ruído por rodada na telemetria.

## Como reproduzir

```
adb shell am start -n app.retrogamesystem/com.swordfish.lemuroid.app.mobile.feature.game.GameActivity
adb shell am start -n app.retrogamesystem/com.swordfish.lemuroid.app.mobile.feature.gamemenu.GameMenuActivity
adb shell am start -n app.retrogamesystem/com.swordfish.lemuroid.app.mobile.feature.input.GamePadBindingActivity
adb shell am start -n app.retrogamesystem/com.swordfish.lemuroid.app.mobile.feature.input.GamePadShortcutBindingActivity
```

Cada um reproduz o crash correspondente na hora.

## Próximos passos

- [ ] Fazer o abort do `BaseGameActivity` ser observável pela subclasse — p.ex. um
      `protected var initializationFailed` (ou trocar o par `finish(); return` por uma flag
      checada em `GameActivity.onCreate` antes de `startGameService()`). Só o `return` do
      `super` não basta, e essa é a armadilha.
- [ ] Aplicar o mesmo tratamento nas outras três: extra ausente deve virar `finish()`, não
      exceção.
- [ ] Conferir se `TVGameActivity` tem o mesmo buraco (herda do mesmo `BaseGameActivity`).

## Lição

`finish(); return` dentro de uma superclasse **não interrompe o `onCreate` da subclasse** — o
`super.onCreate()` simplesmente retorna e o resto do método filho roda com o estado pela metade.
Toda vez que uma classe base aborta a inicialização, ela precisa **sinalizar** o aborto, não
apenas voltar. Aqui o sintoma foi um `lateinit` não inicializado; podia ter sido pior, porque
`startGameService()` já teria subido um foreground service para um jogo que não existe.
