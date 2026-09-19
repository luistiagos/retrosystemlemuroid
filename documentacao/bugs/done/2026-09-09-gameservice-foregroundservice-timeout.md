# [BUG] `ForegroundServiceDidNotStopInTimeException` no `GameService` — recorrência de builds anteriores ao fix de 2026-09-02

**Data:** 2026-09-09 (fechado em 2026-09-10)
**Status:** ✅ Resolvido — sem mudança de código. As ocorrências são de builds anteriores ao fix
de [2026-09-02-foreground-service-datasync-timeout](2026-09-02-foreground-service-datasync-timeout.md).
Pendente: conferir o `app=` dos 4 reports (ver Validação).
**Severidade:** Média (crash de background quando o serviço dataSync não encerra dentro do timeout do SO)
**Branch:** version9
**Origem:** telemetria `retrogamesystem/game` (`ActivityThread.java::android.app.ActivityThread.generateForegroundServiceDidNotStopInTimeException`)
**Errors (serviço):** 4 ocorrências — 4340, 4742, 5181, 5375
**Aparelhos:** Android 14 / 15 / 16 (sdk 34, 35, 36), segundo o report original

---

## Sintoma

```
android.app.RemoteServiceException$ForegroundServiceDidNotStopInTimeException: A foreground service of type dataSync did not stop within its timeout: ComponentInfo{app.retrogamesystem/com.swordfish.lemuroid.app.mobile.feature.game.GameService}
	at android.app.ActivityThread.generateForegroundServiceDidNotStopInTimeException(ActivityThread.java:2676)
	at android.app.ActivityThread.throwRemoteServiceException(ActivityThread.java:2638)
	at android.app.ActivityThread$H.handleMessage(ActivityThread.java:3021)
```

A stack é idêntica (inclusive `ActivityThread.java:2676`) à dos errors 1396…3710 do bug de
2026-09-02.

## Causa-raiz

**É o mesmo bug de 2026-09-02, em builds que ainda não tinham o fix.** A prova está no próprio
texto da exceção: `of type dataSync`. O sistema escreve ali o tipo com que o serviço **foi
promovido a foreground em runtime**. Hoje o `GameService` não tem como rodar como `dataSync`:

- [AndroidManifest.xml:96-103](../../../lemuroid-app/src/main/AndroidManifest.xml#L96-L103)
  declara `android:foregroundServiceType="specialUse"` +
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`;
- [GameService.kt:59-65](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/game/GameService.kt#L59-L65)
  passa `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` explicitamente na API 34+. Nas APIs < 34 usa o
  overload legado, e lá não existe timeout de FGS.

`specialUse` não tem teto de tempo. Com o código atual, essa exceção não pode sair do
`GameService`.

O fix entrou no commit `36aa389` (2026-09-03). O `versionCode` era 243 (1.17.12) **antes e
depois** desse commit, então um APK 1.17.12 pode ou não conter o fix, conforme a data em que
foi gerado. A partir de **1.17.13 (244, `a7f1179`)** o fix está garantido.

### O que o report original supunha e não se confirma

- **"Encerramento/salvamento demorado segura o serviço."** Não é o mecanismo. O teto de `dataSync`
  (Android 15+) é **cumulativo, 6 h por 24 h**, e não depende de teardown. O `GameService`
  também não executa trabalho nenhum: só exibe e cancela a notificação (`displayNotification` /
  `hideNotification`). Não há operação assíncrona para migrar para `WorkManager`.
- **Android 14 (sdk 34).** O timeout de `dataSync` só existe a partir do Android 15. No 14, o único
  FGS com timeout é o `shortService`. Um report `dataSync` com sdk 34 não bate. Vale conferir
  esse caso específico quando a telemetria for aberta.

## Correção

Nenhuma alteração de código. O fix está em produção desde 1.17.13 (detalhes no
[bug de 2026-09-02](2026-09-02-foreground-service-datasync-timeout.md#correção-aplicada)). Este
arquivo foi registrado lá como recorrência.

## Validação

Feito:
- Único manifesto de fonte do app: `lemuroid-app/src/main/AndroidManifest.xml`. Nenhum source
  set de flavor redeclara o serviço, e os manifestos de `lemuroid-app-ext-free` /
  `lemuroid-app-ext-play` não declaram `<service>`.
- O manifesto **mesclado** de release
  (`build/intermediates/merged_manifest/freeBundleRelease/…/AndroidManifest.xml`, gerado em
  2026-09-05 23:37) contém o `GameService` com `foregroundServiceType="specialUse"` e a property.
- O único call-site de `GameService.startService` / `stopService` é o
  [GameActivity.kt:31-37](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/game/GameActivity.kt#L31-L37).

**Não feito:** conferir a versão do app nos 4 reports. O acesso à telemetria (token gerado a
partir do `.env` do backend) foi bloqueado nesta sessão. A versão está no **contexto do report**,
campo `app=<versionName>` (montado por `TelemetryReporter.deviceContext()`). O esperado é que as
4 ocorrências venham com `app` ≤ 1.17.12. **Se alguma vier com 1.17.13 ou posterior, reabrir:**
nesse caso o tipo em runtime não é o que o código define, e a hipótese acima está errada.

## Lição

Crash de build antigo continua chegando por semanas depois do fix, porque a base não atualiza de
uma vez. Antes de abrir bug a partir da telemetria:
1. Conferir o `app=` do contexto contra a versão do fix.
2. Comparar o **texto** da exceção com o código atual. Aqui `of type dataSync` já mostrava que o
   build era anterior ao fix, sem precisar de telemetria.
3. Procurar em `bugs/done/` pela mesma stack. Recorrência se anexa ao arquivo existente, não
   vira bug novo.
