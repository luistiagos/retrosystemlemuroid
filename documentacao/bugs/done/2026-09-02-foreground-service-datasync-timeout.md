# [BUG] Android 15+ mata o app durante o jogo — `GameService` declarado como `dataSync` estoura o teto de 6 h/dia

**Data:** 2026-09-02
**Status:** Resolvido no código ✅ — validação de sessão longa em aparelho Android 15+ pendente
**Severidade:** Alta (o app é morto **durante a partida**, sem aviso; atinge quem joga muito)
**Branch:** version9
**Origem:** telemetria `retrogamesystem/game` e `retrogamesystem/main`
(`ActivityThread.java::android.app.ActivityThread.generateForegroundServiceDidNotStopInTimeException`)
**Errors (serviço):**
- `GameService` — 9 ocorrências: 3710, 3626, 3366, 3343, 3324, 2607, 1435, 1433, 1396
- `DownloadForegroundService` — 1530 (mesmo timeout), 1532 (restart com intent null), 2005 (`mAllowStartForeground false`)
- `SystemForegroundService` do WorkManager (`SHORT_SERVICE`) — 1703, 1422

**Aparelhos:** Xiaomi 24117RN76L, Samsung SM-A075M, OPPO CPH2819 — **Android 15 e 16**
**Janela observada:** 2026-08-18 17:56 → 2026-09-01 21:44

---

## Sintoma

```
android.app.RemoteServiceException$ForegroundServiceDidNotStopInTimeException:
A foreground service of type dataSync did not stop within its timeout:
ComponentInfo{app.retrogamesystem/com.swordfish.lemuroid.app.mobile.feature.game.GameService}
	at android.app.ActivityThread.generateForegroundServiceDidNotStopInTimeException(ActivityThread.java:2676)
```

O contexto do report mostra o processo `:game` com jogo em andamento
(`system=snes; core=snes9x; game=Donkey Kong Country II`). Não há stack da app — o sistema
**injeta** essa exceção no processo e o mata.

Variação no serviço de download (error 1532):

```
java.lang.RuntimeException: Unable to start service DownloadForegroundService@… with null
Caused by: android.app.ForegroundServiceStartNotAllowedException:
  Time limit already exhausted for foreground service type dataSync
	at com.swordfish.lemuroid.app.shared.roms.DownloadForegroundService.onStartCommand(SourceFile:10)
```

## Causa-raiz

A partir do **Android 15 (API 35)** um foreground service do tipo `dataSync` tem teto
**cumulativo de 6 horas por dia**; ao estourar, o sistema chama `Service.onTimeout()` e, se o
serviço não parar, lança `ForegroundServiceDidNotStopInTimeException` e mata o processo.
Depois disso, qualquer `startForeground(dataSync)` no mesmo dia falha com
*"Time limit already exhausted"*.

Os três serviços do app estão declarados como `dataSync`
([AndroidManifest.xml:91-104](../../../lemuroid-app/src/main/AndroidManifest.xml#L91-L104)):

```xml
<service android:name=".app.mobile.feature.game.GameService"      android:foregroundServiceType="dataSync" android:process=":game" />
<service android:name=".app.shared.roms.DownloadForegroundService" android:foregroundServiceType="dataSync" />
<service android:name="androidx.work.impl.foreground.SystemForegroundService"
         android:foregroundServiceType="dataSync|shortService" tools:node="merge" />
```

### 1. `GameService` não é um data-sync — e é o que roda por horas

[GameService.kt:52-60](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/game/GameService.kt#L52-L60)
só existe para manter a notificação "jogo em execução" e segurar o processo `:game` vivo:

```kotlin
private fun displayNotification(game: Game?) {
    val notification = NotificationsManager(applicationContext).gameRunningNotification(game)
    ServiceCompat.startForeground(
        this,
        NotificationsManager.GAME_RUNNING_NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,   // <- tipo errado
    )
}
```

Não sincroniza dado nenhum. É justamente o serviço que fica **horas** de pé, então é ele que
consome a cota de `dataSync` do dia e depois derruba a partida. O tipo correto para uma sessão
de jogo é `specialUse` (com `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` declarada) ou `mediaPlayback`.

### 2. Nenhum dos serviços implementa `onTimeout()`

Nem `GameService` nem `DownloadForegroundService` sobrescrevem `Service.onTimeout(int, int)`
(API 35). Sem isso o app não tem chance de parar sozinho antes da exceção fatal.

### 3. `startForeground` / `startForegroundService` sem guarda

[DownloadForegroundService.kt:57-62](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/DownloadForegroundService.kt#L57-L62)
chama `ServiceCompat.startForeground` direto em `onStartCommand`, e
[DownloadForegroundService.kt:114-118](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/DownloadForegroundService.kt#L114-L118)
chama `ContextCompat.startForegroundService` sem try/catch. As duas lançam
`ForegroundServiceStartNotAllowedException` (cota esgotada; app em background) e o processo cai.

### 4. `START_STICKY` fecha o ciclo

[DownloadForegroundService.kt:62](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/DownloadForegroundService.kt#L62)
devolve `START_STICKY`. Depois de morto pelo timeout, o sistema **reinicia** o serviço com
`intent == null` — e o `startForeground` falha de novo pela mesma cota esgotada. É exatamente o
que o error 1532 registra (`Unable to start service … with null`). `GameService` já usa
`START_NOT_STICKY` e não sofre disso.

## Como reproduzir

Aparelho com Android 15+ (os reports são de Android 15/16). Jogar sessões somando mais de 6 h
no mesmo dia — não precisa ser contínuo, a cota é cumulativa por 24 h. A partida seguinte é
morta assim que o teto estoura.

## Correção aplicada

1. **`GameService` → `specialUse`**: o manifest e
   [GameService.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/game/GameService.kt)
   passaram a usar `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`, com a permissão
   `FOREGROUND_SERVICE_SPECIAL_USE` e a `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="…"/>`.
   Em APIs anteriores à 34, o serviço usa o overload legado de `startForeground`, evitando
   resolver o tipo novo nos aparelhos antigos suportados pelo app.
2. **`onTimeout()` foi implementado nos dois serviços**, com guard de API 35. O download pausa
   o item ativo antes de remover o foreground e chamar `stopSelfResult(startId)`; o jogo também
   remove o foreground e se encerra dentro da janela de tolerância do sistema.
3. **`startForeground` e `startForegroundService` agora são protegidos** contra
   `ForegroundServiceStartNotAllowedException`. A detecção da exceção de API 31 fica isolada em
   `ForegroundServiceUtils`, com `@DoNotInline`, para preservar o `minSdkVersion 21`. Quando o
   foreground não é permitido, o app registra o caso e tenta exibir uma notificação comum.
4. **`DownloadForegroundService` agora retorna `START_NOT_STICKY`** e rejeita imediatamente
   `intent == null`. A fila persistida continua sob responsabilidade do `SaveQueueManager`.

> ⚠️ `minSdkVersion` é 21 — todo uso de `onTimeout` e das constantes novas precisa de guard por
> `Build.VERSION.SDK_INT`, e o `foregroundServiceType` novo tem que continuar válido nas APIs
> antigas (ver pitfall 7 do CLAUDE.md).

## Validação

- `:lemuroid-app:compileFreeBundleDebugKotlin` — passou.
- `:lemuroid-app:processFreeBundleDebugMainManifest` — passou; o manifest mesclado contém a
  permissão, o tipo `specialUse` e `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`.
- Ktlint dos três arquivos Kotlin alterados — sem ocorrências. A checagem global continua
  falhando por violações preexistentes em outros arquivos do projeto.
- Android Lint completo — nenhuma incompatibilidade nova de API/manifest nas adições. A tarefa
  global continua falhando por 40 erros preexistentes (o primeiro é `CrashTelemetry.kt:122`, uso
  de `ApplicationExitInfo.getReason` sem guard de API 30).

Ainda é necessário validar em aparelho Android 15+ com uma sessão longa ou reduzir o timeout de
teste via `device_config` para exercitar o callback real do framework.

## Próximos passos

- [x] Trocar o tipo do `GameService` para `specialUse`.
- [x] Implementar `onTimeout` + guardas de exceção nos dois serviços.
- [ ] Validar em aparelho Android 15+ com sessão longa ou timeout reduzido via `device_config`.
- [ ] Reavaliar `dataSync|shortService` do `SystemForegroundService` do WorkManager — os
      errors 1703/1422 são ANR de `SHORT_SERVICE` que não parou no prazo (3 min), com
      `LibraryIndexWork`/`StreamingRomsWork` como suspeitos.
