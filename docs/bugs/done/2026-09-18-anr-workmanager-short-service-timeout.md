# [BUG] ANR no Android 14+ durante download/sync — `SystemForegroundService` tipo `SHORT_SERVICE` estoura timeout de 3 min

- **Detectado em:** 2026-09-16 23:43 (telemetria de produção)
- **Status:** Resolvido no código ✅
- **Severidade:** Alta (mata downloads/sincronizações com mais de 3 min em Android 14+)
- **Branch:** version9
- **Origem:** telemetria `retrogamesystem/anr` (`libc.so::__epoll_pwait+8`)
- **Errors (serviço):** 7032, 6390, 6387, 6386 (4 ocorrências)
- **Classe:** fail / ANR
- **Reincidência:** primeira vez detectado (Android 14 / sdk 34, app 1.17.19)

---

## Sintoma

Usuário inicia uma tarefa de background longa (como download de pacote de ROMs via `RomsDownloadWork` ou sincronização de biblioteca via `LibraryIndexWork`) em aparelhos com Android 14+ (ex.: Infinix X6882, Android 14).
Após exatamente 3 minutos (180 segundos), o sistema Android encerra o processo com ANR fatal do sistema:

```
ANR: user request after error: A foreground service of FOREGROUND_SERVICE_TYPE_SHORT_SERVICE did not stop within a timeout: ComponentInfo{app.retrogamesystem/androidx.work.impl.foreground.SystemForegroundService}
```

## Causa raiz

Confirmada no código-fonte em [WorkManagerUtils.kt:8-20](file:///c:/projects/lemuroid/Lemuroid/lemuroid-app/src/main/java/com/swordfish/lemuroid/app/utils/android/WorkManagerUtils.kt#L8-L20):

```kotlin
fun createSyncForegroundInfo(
    notificationId: Int,
    notification: Notification,
): ForegroundInfo {
    // Android 16 removed support for DATA_SYNC FGS type. Use SHORT_SERVICE on API 34+.
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else -> ForegroundInfo(notificationId, notification)
    }
}
```

O tipo `FOREGROUND_SERVICE_TYPE_SHORT_SERVICE` introduzido na API 34 possui um **timeout máximo estrito de 3 minutos** imposto pelo Android. Ele foi projetado exclusivamente para tarefas curtas e não extensíveis.
Ao usar `SHORT_SERVICE` para `RomsDownloadWork` (downloads que podem levar muitos minutos dependendo da conexão e tamanho dos acervos) ou `LibraryIndexWork`, qualquer execução que ultrapasse 180 segundos é sumariamente interrompida pelo sistema operacional gerando ANR.

Além disso, a premissa do comentário ("Android 16 removed support for DATA_SYNC... Use SHORT_SERVICE on API 34+") está incorreta: no Android 14 (API 34, `UPSIDE_DOWN_CAKE`) e Android 15 (API 35), `DATA_SYNC` é perfeitamente válido e suportado para downloads e sincronizações. Mesmo que tipos mudem em futuras versões, `SHORT_SERVICE` nunca pode ser usado para operações de tempo indeterminado como downloads de ROMs.

## Como reproduzir

1. Em um aparelho ou emulador com Android 14 (API 34).
2. Iniciar um download de acervo de ROMs com duração superior a 3 minutos.
3. Aguardar 180 segundos; o SO emite o ANR de timeout do `SHORT_SERVICE` e mata o app.

## Correção proposta

Em [WorkManagerUtils.kt](file:///c:/projects/lemuroid/Lemuroid/lemuroid-app/src/main/java/com/swordfish/lemuroid/app/utils/android/WorkManagerUtils.kt):
Utilizar `FOREGROUND_SERVICE_TYPE_DATA_SYNC` na API 34 (ou `specialUse` configurado adequadamente, ou não forçar `SHORT_SERVICE` para downloads):

```kotlin
fun createSyncForegroundInfo(
    notificationId: Int,
    notification: Notification,
): ForegroundInfo {
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else -> ForegroundInfo(notificationId, notification)
    }
}
```

## Correção

[WorkManagerUtils.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/utils/android/WorkManagerUtils.kt)
parou de forçar `SHORT_SERVICE` na API 34+; usa `DATA_SYNC` a partir da API 29 (Q), como já
recomendado na correção proposta acima:

```kotlin
fun createSyncForegroundInfo(
    notificationId: Int,
    notification: Notification,
): ForegroundInfo {
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else -> ForegroundInfo(notificationId, notification)
    }
}
```

O comentário incorreto ("Android 16 removed support for DATA_SYNC…") foi removido e substituído
por uma nota explicando por que `SHORT_SERVICE` nunca serve para este helper: os cinco
chamadores (`RomsDownloadWork`, `StreamingRomsWork`, `LibraryIndexWork`, `CoreUpdateWork`,
`SaveSyncWork`) são todos jobs de duração indeterminada.

`AndroidManifest.xml` já declarava a permissão `FOREGROUND_SERVICE_DATA_SYNC` e o tipo
`dataSync|shortService` mesclado no `SystemForegroundService` — nenhuma mudança necessária ali.
O `shortService` continua declarado no manifest (inofensivo, apenas permite o tipo; nada no
código o solicita mais) e as três outras chamadas (`SaveSyncWork`, `LibraryIndexWork`,
`CoreUpdateWork`) já protegiam `setForeground()` com try/catch antes desta correção — mantido
como rede de segurança caso uma versão futura do Android invalide `DATA_SYNC`.

## Validação

- `:lemuroid-app:compileFreeBundleDebugKotlin` — BUILD SUCCESSFUL.

## Lição

O comentário original ("Android 16 removed support for DATA_SYNC FGS type") generalizou uma
preocupação futura em uma decisão de runtime errada para o presente: trocou um tipo válido e
sem timeout (`DATA_SYNC`, API 29+) por um com teto rígido de 3 minutos (`SHORT_SERVICE`,
API 34+) para todo device Android 14/15 atual, quebrando o caso comum para blindar um caso
hipotético. Mitigar risco futuro não pode piorar o comportamento correto do presente — e o
tipo escolhido precisa caber no tempo real da operação (aqui, minutos de download), não no que
"pode ser removido depois".
