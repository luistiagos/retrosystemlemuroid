# [BUG] Crash ao iniciar jogo em Android < 8.1 (API 27) — `NoSuchMethodError: No virtual method setShowWhenLocked(Z)V`

- **Detectado em:** 2026-09-22 22:50 (telemetria de produção)
- **Status:** Corrigido
- **Severidade:** Crítica (nenhum jogo abre em Android 5.0–8.0; atinge TV Box e aparelho legado, que são público-alvo do app)
- **Complexidade:** media — correção de chamada a API de framework mais recente que minSdk com guard de versão e try-catch de fallback.
- **Branch:** version9
- **Origem:** telemetria `retrogamesystem/game` (`SourceFile::com.swordfish.lemuroid.app.mobile.feature.game.GameActivity.onGameCreated`)
- **Errors (serviço):** 8297, 8292, 8291, 8286, 8282, 8279, 8277, 8275, 8273, 8272 (10 ocorrências iniciais) + 8319, 8315, 8314, 8306 (4 ocorrências triadas em 2026-09-24, observadas em builds de produção anteriores ao release do fix). Total: 14 ocorrências.
- **Classe:** crash
- **Reincidência:** primeira vez detectado — regressão introduzida em `36aa389` (2026-09-03), o commit do fix de `2026-09-03-activities-sem-extras-crasham-pre-launch-report.md`. Antes desse commit as duas chamadas não existiam no projeto, então o crash é novo: o `git log -S"setShowWhenLocked"` devolve `36aa389` como único commit em ambos os arquivos.

---

## Sintoma

Ao iniciar **qualquer** jogo, em dispositivos com Android anterior à API 27 (5.0 Lollipop até 8.0 Oreo — TV Box, TVs antigas, smartphones legados), o processo `:game` morre antes de a tela de jogo aparecer:

```
java.lang.NoSuchMethodError: No virtual method setShowWhenLocked(Z)V in class Landroid/app/Activity; or its super classes (declaration of 'android.app.Activity' appears in /system/framework/framework.jar)
	at G3.b.a(SourceFile:1)
	at com.swordfish.lemuroid.app.mobile.feature.game.GameActivity.f1(SourceFile:1)
	at com.swordfish.lemuroid.app.shared.game.BaseGameActivity.onCreate(SourceFile:31)
	at android.app.Activity.performCreate(Activity.java:6975)
```

O quadro `G3.b.a` é a classe sintética que o R8 gera ao fazer *API modeling outlining*: ele extrai para fora do método a chamada a uma API mais nova que o `minSdkVersion`, para que a verificação do método que a contém não falhe. O outline **não** adiciona guard de runtime — ele só move o ponto onde o `NoSuchMethodError` é lançado.

## Causa raiz

- [GameActivity.kt](file:///c:/projects/lemuroid/Lemuroid/lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/game/GameActivity.kt) — dentro de `onGameCreated()`, chamado no fim de `BaseGameActivity.onCreate`.
- [ExternalGameLauncherActivity.kt](file:///c:/projects/lemuroid/Lemuroid/lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/ExternalGameLauncherActivity.kt) — dentro de `onCreate`.

```kotlin
setShowWhenLocked(true)
setTurnScreenOn(true)
```

`Activity.setShowWhenLocked(boolean)` e `Activity.setTurnScreenOn(boolean)` foram introduzidos no **Android 8.1 Oreo MR1 (API 27)**. O projeto tem `minSdkVersion = 21` ([deps.kt:6](file:///c:/projects/lemuroid/Lemuroid/buildSrc/src/main/java/deps.kt#L6)) e distribui build armeabi-v7a para TV Box velha. Sem checagem de runtime, a invocação direta é `NoSuchMethodError` fatal em todo aparelho com API < 27.

O `NewApi` do Android Lint **não** está desabilitado ([lemuroid-app/build.gradle.kts:216](file:///c:/projects/lemuroid/Lemuroid/lemuroid-app/build.gradle.kts#L216) só remove `MissingTranslation`, `ExtraTranslation` e `EnsureInitializerMetadata`) — o check existia e teria acusado, mas o lint não roda no loop de desenvolvimento, então a regressão passou por três semanas até a telemetria acusar.

## Como reproduzir

1. Emulador ou aparelho físico com Android 7.0 (API 24) ou 8.0 (API 26).
2. Abrir qualquer jogo da biblioteca.
3. Crash imediato, antes do primeiro frame.

## Correção

Novo helper `Activity.setShowWhenLockedCompat()` em [ActivityUtils.kt](file:///c:/projects/lemuroid/Lemuroid/lemuroid-app/src/main/java/com/swordfish/lemuroid/app/utils/android/ActivityUtils.kt), e os dois call sites passam a usá-lo:

```kotlin
fun Activity.setShowWhenLockedCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        try {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            return
        } catch (e: NoSuchMethodError) {
            Timber.w(e, "setShowWhenLocked ausente com SDK_INT=${Build.VERSION.SDK_INT}; usando flags de janela")
        }
    }

    @Suppress("DEPRECATION")
    window.addFlags(
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
    )
}
```

**Por que o guard por `SDK_INT` sozinho não basta aqui.** É o que o `NewApi` pediria e o que resolve no aparelho honesto, mas o pitfall 7 do CLAUDE.md documenta que as TV Box baratas deste público **anunciam Android 9/11 rodando 7.1 de verdade**. Nessas, `SDK_INT >= 27` passa e o `framework.jar` continua sem o método — o crash sobreviveria ao guard. Por isso o `NoSuchMethodError` também é capturado, e os dois caminhos de falha convergem para as flags de janela equivalentes. O `try/catch` funciona mesmo com o outlining do R8: a região protegida cobre a chamada ao método sintético, então o erro lançado lá dentro sobe e é capturado no call site.

`FLAG_SHOW_WHEN_LOCKED` e `FLAG_TURN_SCREEN_ON` estão depreciadas desde a própria API 27 — mas quem as consome é exatamente o aparelho pré-27, e só ele chega nesse caminho.

## Validação

- `:lemuroid-app:compileFreeBundleDebugKotlin` — **BUILD SUCCESSFUL**.
- `:lemuroid-app:ktlintMainSourceSetCheck` — `ActivityUtils.kt` e `GameActivity.kt` sem apontamento. As 6 violações listadas em `ExternalGameLauncherActivity.kt` são pré-existentes (imports `safeLaunch`/`Timber` não usados, linhas 19 e 32; bloco `gameId` nas linhas 63-64) e não vêm desta correção — o repositório já acumula 911 violações em arquivos da UI de TV.
- `:lemuroid-app:lintFreeBundleDebug` — os três arquivos alterados saem **sem nenhum apontamento**; o `NewApi` que a chamada crua produzia desapareceu. O relatório segue com 45 erros pré-existentes (a task já falhava antes desta correção).
- `grep` confirmou que os dois call sites corrigidos são os únicos usos de `setShowWhenLocked`/`setTurnScreenOn` no código do projeto (as ocorrências restantes são `FLAG_KEEP_SCREEN_ON` dentro de `tmp/`, fontes de terceiros não compiladas).
- Não foi possível rodar em aparelho com API < 27 nesta sessão. O caminho legado não foi exercido em device real.

## Lição

1. **`minSdkVersion = 21` com `compileSdkVersion = 35` significa que toda chamada de API nova é um crash em potencial**, e o compilador não avisa — só o `NewApi` do lint, que não roda no loop de dev. Ao adicionar qualquer API de framework, checar em que nível ela entrou.
2. **Neste app, guard por `SDK_INT` é condição necessária mas não suficiente.** O pitfall 7 já dizia isso para bug de framework antigo; vale igual para API ausente, e pela mesma razão: as TV Box mentem a versão. Quando o custo de errar é crash, o guard por versão anda junto com o `try/catch`.
3. O quadro sintético do R8 (`G3.b.a`) num stack trace de `NoSuchMethodError` é assinatura de *API modeling* — ele indica exatamente que a chamada era mais nova que o `minSdk`, e não que haja um bug de ofuscação.
4. A regressão entrou junto de um fix de crash não relacionado (Pre-Launch Report), num commit grande. Mudança de uma linha em `onCreate` de Activity crítica merece o mesmo escrutínio que o fix que a acompanha.

## Irmãos da mesma família (não corrigidos aqui)

O lint acusa outras 32 chamadas `NewApi`. 19 são falso-positivo — `CrashTelemetry.kt` e `CoreCrashFallback.kt` guardam por `SDK_INT < R` no chamador e envolvem o corpo em `catch (ignored: Throwable)`, e o lint não acompanha guard no chamador. As 13 restantes são genuinamente sem guard, do mesmo tipo do bug acima, sendo a pior delas `MainActivity.kt:202` — chamada de `onCreate`, ou seja, o app não abre em Android 5.0/5.1.

Levantamento completo, alcance de cada uma e próximos passos em [[2026-09-22-newapi-sem-guard-android-5x-inicia-mainactivity]]. Ficaram fora deste fix porque são anteriores a esta regressão e a primeira decisão ali é se Android 5.x continua suportado — o que não cabia a esta correção.

> **Atualização (2026-09-23):** as 13 foram corrigidas. `minSdkVersion` continua 21 e cada site ganhou guard no molde deste, mais um `lint-baseline.xml` que faz a task voltar a falhar só em achado novo.
