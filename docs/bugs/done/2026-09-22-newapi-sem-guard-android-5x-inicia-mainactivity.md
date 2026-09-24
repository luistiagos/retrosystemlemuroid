# [BUG] App não abre em Android 5.0/5.1 — 13 chamadas de API acima do `minSdkVersion` sem guard (`NoSuchMethodError`)

- **Detectado em:** 2026-09-22 (varredura do `NewApi` durante o fix de [[2026-09-22-gameactivity-nosuchmethoderror-setshowwhenlocked]] — **não** veio da telemetria)
- **Status:** Corrigido (2026-09-23)
- **Severidade:** Crítica (a pior das 13 está no `onCreate` da `MainActivity`: o app não abre em Android 5.0/5.1)
- **Branch:** version9
- **Origem:** `./gradlew :lemuroid-app:lintFreeBundleDebug`, relatório `lint-results-freeBundleDebug.txt`
- **Errors (serviço):** nenhum. Ver "Por que a telemetria está muda" abaixo.
- **Classe:** crash
- **Reincidência:** mesma família do bug corrigido em 2026-09-22 (`setShowWhenLocked`), mas **anterior** a ele: nenhuma destas linhas veio da regressão de `36aa389`.

---

## Sintoma

Em aparelho com Android 5.0 ou 5.1 (API 21/22) — que o `minSdkVersion = 21` ([deps.kt:6](file:///c:/projects/lemuroid/Lemuroid/buildSrc/src/main/java/deps.kt#L6)) declara suportado e a Play Store portanto oferece — a `MainActivity` morre no próprio `onCreate` com `NoSuchMethodError`. O app não chega a desenhar a Home.

Não há stack de produção para colar: o crash não foi observado, foi **deduzido do lint**. A assinatura esperada é a mesma do bug irmão, trocando o método:

```
java.lang.NoSuchMethodError: No virtual method isIgnoringBatteryOptimizations(Ljava/lang/String;)Z
    in class Landroid/os/PowerManager; ...
	at ...MainActivity.requestBatteryOptimizationExemption(MainActivity.kt:202)
	at ...MainActivity.onCreate(MainActivity.kt:188)
```

## Causa raiz

13 chamadas a APIs introduzidas depois da API 21, sem `Build.VERSION.SDK_INT` no caminho e sem `catch` que pegue `Error`. Cada uma foi verificada abrindo o call site e procurando guard no chamador — as listadas abaixo **não** tinham nenhum:

| Local | API | Chamada | Alcance |
|-------|-----|---------|---------|
| `MainActivity.kt:202` | 23 | `PowerManager.isIgnoringBatteryOptimizations` | **Chamado de `onCreate`** — mata o app na abertura. O único `SDK_INT` do arquivo guardava o `TIRAMISU` de outra função. |
| `HomeViewModel.kt:102,113` | 23 | `getSystemService(Class)`, `ConnectivityManager.activeNetwork` | `wifiStatusFlow()`, coletado num `viewModelScope.launch` do `init` — cai junto com a Home. |
| `HomeViewModel.kt:179,180` | 23 | idem, em `getMobileNetworkLabel()` | Só quando o Wi-Fi cai durante download. |
| `HomeScreen.kt:486,487,493,494` | 23 | idem, em `isWifiNeeded()` e `isNetworkAvailable()` | Caminho de download. |
| `DownloadForegroundService.kt:95` | 24 | `stopForeground(STOP_FOREGROUND_REMOVE)` | Android 5.0–**6.0**. Ao esvaziar a fila de downloads. |
| `TVCardFocusHighlight.kt:32,34` | 23 | `View.foreground` (setter) | No `OnFocusChangeListener` — primeiro movimento do controle na UI de TV. |
| `TVRomDownloadDialog.kt:37` | 23 | `TextView.setTextAppearance(int)` (sobrecarga de 1 argumento) | Diálogo de download na TV. |

Dois detalhes que o relatório sozinho não conta:

1. **`DownloadForegroundService` tinha o gêmeo certo e o errado no mesmo arquivo.** A linha 155 fazia a mesmíssima chamada **atrás** de `if (SDK_INT < VANILLA_ICE_CREAM) return`; a linha 95 não tinha guard nenhum. O `catch` da linha 76 é `RuntimeException`, que **não** pega `NoSuchMethodError` (é `Error`).
2. **19 dos 32 achados do lint são falso-positivo** e não entram nesta lista: `CrashTelemetry.kt` (14, API 30) e `CoreCrashFallback.kt` (5, API 30) guardam por `SDK_INT < R` no chamador e envolvem o corpo em `catch (ignored: Throwable)`. O lint não acompanha guard no chamador, daí o apontamento nos helpers privados. Esses ainda são imunes ao aparelho que mente a versão (ver pitfall 12), então não há o que fazer neles.

### Por que a telemetria está muda

Zero ocorrência no serviço, e isso **não** é evidência de que não acontece: o `TelemetryReporter` é instalado dentro do próprio app. Um `NoSuchMethodError` no `onCreate` da `MainActivity` pode matar o processo antes de o reporte sair, e um aparelho que nunca consegue abrir o app nunca reporta nada. A ausência de dado aqui é consistente tanto com "ninguém usa Android 5.x" quanto com "quem usa não consegue nem abrir".

## Como reproduzir

1. Emulador com Android 5.1 (API 22), imagem `armeabi-v7a` ou `x86`.
2. Instalar o APK e abrir o app.
3. Esperado (antes do fix): crash imediato em `MainActivity.onCreate`.
4. Para o site da API 24: emulador Android 6.0 (API 23), enfileirar um download e deixar a fila esvaziar.

## Correção

### Decisão: `minSdkVersion` continua 21

A alternativa de subir para 23 resolveria 12 dos 13 sites sem escrever guard nenhum, mas contraria o que o app é: o CLAUDE.md (pitfall 7, regra 4) registra que a build armeabi-v7a é distribuída para TV Box velha e que **bug de Android 5–7 é bug nosso na prática**. Abandonar 5.0/5.1 para não escrever ~60 linhas de compat é o trade errado aqui. Os 13 sites foram guardados.

Em todos, o molde é o do `setShowWhenLockedCompat`: **`SDK_INT` mais `catch (NoSuchMethodError)`**, porque as TV Box baratas anunciam Android 9/11 rodando 7.1 de verdade (pitfall 12) e o teste de versão sozinho passa num aparelho cujo `framework.jar` não tem o método.

### 1. Rede — novo [NetworkCompat.kt](file:///c:/projects/lemuroid/Lemuroid/lemuroid-app/src/main/java/com/swordfish/lemuroid/app/utils/android/NetworkCompat.kt) (9 sites)

Os quatro trechos duplicados em `HomeViewModel` e `HomeScreen` viraram chamadas a um helper único:

| Antes | Depois |
|-------|--------|
| `getSystemService(ConnectivityManager::class.java)` (API 23) | `Context.connectivityManagerCompat()` — sobrecarga por String, API 1, **sem guard necessário** |
| `getNetworkCapabilities(activeNetwork)?.hasTransport(WIFI)` | `ConnectivityManager.isOnWifiCompat()` |
| `getNetworkCapabilities(activeNetwork)?.hasCapability(INTERNET)` | `ConnectivityManager.hasInternetCompat()` |
| buckets de `linkDownstreamBandwidthKbps` | `ConnectivityManager.mobileGenerationCompat()` → `MobileGeneration` |

Só `getActiveNetwork` é API 23; `getNetworkCapabilities(Network)`, `NetworkRequest` e `NetworkCallback` são da 21 e continuam diretos. Abaixo da 23 o fallback é `activeNetworkInfo` (depreciado, existe desde a 1); o rótulo de rede móvel, que no caminho novo sai da banda estimada, sai do `NetworkInfo.subtype` no caminho legado — subtipo desconhecido cai no rótulo genérico "Móvel", que é o que o código já devolvia para rede não-celular.

`activeCapabilitiesCompat()` devolve `null` tanto para "aparelho sem `getActiveNetwork`" quanto para "não há rede ativa" — ambiguidade proposital e documentada no arquivo: nos dois casos o chamador cai para `activeNetworkInfo`, que responde `null`/desconectado quando realmente não há rede, então a resposta final é a mesma.

### 2. `MainActivity.requestBatteryOptimizationExemption()` (1 site — o crítico)

`if (SDK_INT < M) return` antes de tudo: abaixo da 23 **não existe Doze**, logo não há isenção a pedir nem tela de Ajustes para abrir — seguir em silêncio é o comportamento certo, não uma degradação. O `catch (NoSuchMethodError)` trata o aparelho mentiroso como "já isento" pelo mesmo motivo. De quebra, o `startActivity` cru com Intent implícita virou `startActivitySafely` (pitfall 9), que além do `ActivityNotFoundException` que já era tratado cobre `SecurityException`.

### 3. `DownloadForegroundService` (1 site)

Um único `stopForegroundCompat()` privado, usado nos **dois** pontos — o que estava cru (linha 95) e o que já estava guardado (linha 155). Ele delega a `ServiceCompat.stopForeground`, que escolhe entre a sobrecarga nova e a booleana da API 5, com `catch (NoSuchMethodError)` caindo em `stopForeground(true)`. Unificar era parte do fix: o arquivo já provou que a mesma linha copiada perde o guard no caminho.

### 4. `TVCardFocusHighlight` (2 sites)

`View.setForeground` é API 23 — mas `FrameLayout.setForeground` existe desde a API 1 e `ImageCardView` **é** um `FrameLayout`. O detalhe que fecha a porta para a solução óbvia: na API 23 o método subiu para `View` e **saiu** do `android.jar` de `FrameLayout` (`removed="23"` no `api-versions.xml`), então compilando contra a 35 o cast para `FrameLayout` continua resolvendo em `View.setForeground` — não silencia o lint nem evita o `NoSuchMethodError`. A saída é reflexão no caminho legado, com o `Method` cacheado: em Android 5.x ela acha o de `FrameLayout`, da 23 em diante acharia o de `View` herdado. Falhar aqui custa só a borda de foco (o realce padrão do Leanback continua), então nenhum caminho propaga.

Foi a única correção que trocou "usar a API certa" por reflexão, e de propósito: a borda existe justamente porque o realce padrão é fraco **na TV Box velha** — pular o realce castigaria o aparelho que o bug afeta.

### 5. `TVRomDownloadDialog` (1 site)

`TextViewCompat.setTextAppearance(view, resId)`, que cai na sobrecarga com `Context` (API 1) abaixo da 23.

### 6. Baseline de lint — o que impede o próximo passar em branco

`lint { baseline = file("lint-baseline.xml") }` em [lemuroid-app/build.gradle.kts](file:///c:/projects/lemuroid/Lemuroid/lemuroid-app/build.gradle.kts). O passivo restante (19 `NewApi` falso-positivo + 13 `RestrictedApi` de `dispatchKeyEvent` + 58 warnings) está congelado em `lemuroid-app/lint-baseline.xml`, e `:lemuroid-app:lintFreeBundleDebug` **passa**. A partir de agora a task só falha em achado **novo** — que é exatamente o que dá para agir, e é o que faltava quando o `setShowWhenLocked` entrou. Ao corrigir um item da lista, apagar o arquivo e rodar a task de novo para regenerar.

## Validação

- `:lemuroid-app:compileFreeBundleDebugKotlin` — **BUILD SUCCESSFUL**.
- `:lemuroid-app:lintFreeBundleDebug` — antes: 45 erros, com os 13 sites acima entre eles. Depois: **32 erros**, e o relatório não tem mais nenhum dos 13 — os `NewApi` remanescentes são exatamente os 19 de `CrashTelemetry.kt` e `CoreCrashFallback.kt` (conferido no relatório, linha a linha). Com o baseline aplicado a task termina **BUILD SUCCESSFUL**.
- `:lemuroid-app:ktlintMainSourceSetCheck` — `NetworkCompat.kt` sai sem nenhum apontamento. Os demais arquivos tocados só listam violações pré-existentes (o repositório acumula ~900, e a task já falhava antes desta correção).
- Conferência do `api-versions.xml` do SDK 35 para cada método envolvido (`FrameLayout#setForeground` `removed="23"`, `TextView#setTextAppearance(I)` `since="23"`, `Service#stopForeground(I)` `since="24"`, subtipos de `TelephonyManager` usados no fallback todos `since <= 13`) — nenhum nível de API foi suposto de memória.
- `javap` no `core-1.8.0.aar`/`core-1.13.1.aar` do cache Gradle confirmou `ServiceCompat.stopForeground(Service, int)` e `TextViewCompat.setTextAppearance(TextView, int)` nas versões que o projeto resolve.
- **Não** foi possível rodar em aparelho ou emulador com API 21/22 nesta sessão: nenhum caminho legado foi exercido em device real. O que está provado é que os 13 sites deixaram de chamar API acima do `minSdk`; o comportamento do fallback (rótulo de rede móvel por `subtype`, borda de foco por reflexão) segue não exercitado.

## Lição

1. **Esta lista não apareceu por telemetria nem por revisão**: apareceu porque, ao corrigir um `NoSuchMethodError`, o lint que já acusava a família inteira finalmente foi rodado. Um passivo de 45 erros que falha a task todo dia não é um alerta — é ruído, e some da vista. **Passivo de lint precisa de baseline no dia em que se decide não zerá-lo**, senão o próximo achado real nasce invisível.
2. **Ausência de telemetria não é ausência de bug** quando o reporter mora dentro do processo que morre. Crash de abertura é, por construção, o que menos aparece no painel.
3. **`removed="23"` no `api-versions.xml` é uma armadilha específica**: o método não sumiu do aparelho, subiu de classe. Quem assume que "basta usar o tipo antigo no receptor" acaba com código que o compilador resolve para a classe nova do mesmo jeito. Conferir o `api-versions.xml` custa uma consulta e responde sem achismo.
4. **Guard copiado se perde na cópia.** O `stopForeground` guardado e o cru conviviam no mesmo arquivo, a 60 linhas um do outro. Quando a mesma chamada aparece duas vezes, o guard vira função — não comentário.
