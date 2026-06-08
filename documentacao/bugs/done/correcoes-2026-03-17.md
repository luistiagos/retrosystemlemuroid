# Correções aplicadas — 2026-03-17

Registro de todos os bugs encontrados e corrigidos nesta data no fluxo de download de ROMs.
Os arquivos envolvidos são:

| Arquivo | Caminho |
|---------|---------|
| `RomsDownloadManager.kt` | `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/` |
| `RomsDownloadWork.kt` | `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/` |
| `HomeViewModel.kt` | `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/home/` |
| `HomeScreen.kt` | `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/home/` |
| `strings.xml` | `lemuroid-app/src/main/res/values/` |
| `strings-pt-rBR.xml` | `lemuroid-app/src/main/res/values-pt-rBR/` |

---

## Sessão A — Features de retomada e cancelamento

### A-1 · Feature — Auto-resume ao reabrir o app

**Problema:** Se o app fosse fechado durante o download ou extração, ao reabrir o processo
começaria do zero sem qualquer indicação ao usuário.

**Correção em `RomsDownloadManager.kt`:**
- Adicionado `PREF_DOWNLOAD_STARTED` — marcado `true` ao enfileirar o trabalho; limpo na
  conclusão ou cancelamento.
- Adicionado bloco `init` que re-enfileira `RomsDownloadWork` automaticamente se
  `PREF_DOWNLOAD_STARTED=true` e o download não está concluído.
  `ExistingWorkPolicy.KEEP` garante que não cria duplicata se o trabalho já está em fila.
- Adicionado `PREF_ARCHIVE_DOWNLOADED` — marcado `true` após o `.7z` ser baixado.
  Se a extração for interrompida e o app reabrir, `doDownload()` detecta o flag, pula o
  download e vai direto à extração (limpando pastas parcialmente extraídas antes).

### A-2 · Feature — Supressão do dialog enquanto download ativo

**Problema:** O dialog "Deseja baixar o pacote?" aparecia mesmo com download em andamento.

**Correção em `HomeViewModel.kt`:**
- `showDownloadPromptDialog` passou a incluir a condição `&& !romsDownloadManager.isDownloadStarted()`.

### A-3 · Feature — Botão Cancelar com confirmação

**Adicionado em `HomeScreen.kt`:** Botão "Cancelar" nos estados `Downloading` e `Extracting`,
abrindo um `AlertDialog` de confirmação.

**Adicionado em `HomeViewModel.kt`:** `cancelDownload()` que invoca `romsDownloadManager.cancelDownload()`.

**Adicionado em `RomsDownloadManager.kt`:** `cancelDownload()` — cancela o `UniqueWork` do
WorkManager, delete toda a `romsDir` e reseta os três flags (`PREF_DOWNLOAD_STARTED`,
`PREF_ARCHIVE_DOWNLOADED`, `PREF_DOWNLOAD_DONE`).

**Strings adicionadas** em `strings.xml` e `strings-pt-rBR.xml`:
`home_download_roms_cancel`, `home_download_cancel_dialog_title`,
`home_download_cancel_dialog_message`, `home_download_cancel_dialog_confirm`,
`home_download_cancel_dialog_dismiss`.

### A-4 · Bug crítico (ANR) — `cancelDownload()` bloqueava a UI thread

**Problema:** `HomeViewModel.cancelDownload()` chamava `romsDir.deleteRecursively()` (~5 GB)
diretamente na main thread — garantia de ANR.

**Correção em `HomeViewModel.kt`:**
```kotlin
// ANTES
fun cancelDownload() {
    romsDownloadManager.cancelDownload()  // deleteRecursively na UI thread!
}

// DEPOIS
fun cancelDownload() {
    viewModelScope.launch(Dispatchers.IO) {
        romsDownloadManager.cancelDownload()
    }
    downloadDialogDismissed.value = true
}
```

---

## Sessão B — Dialog não reaparecia após deleção das ROMs

### B-1 · Bug — Card e dialog não reapareciam após deletar diretório de ROMs

**Problema:** `PREF_DOWNLOAD_DONE=true` persistia de um download anterior concluído.
Após deletar manualmente os arquivos (Rescan → 0 ROMs), o card permanecia oculto e
nenhum dialog de confirmação aparecia, porque `isDownloadDone()` retornava `true`.

**Correção em `HomeViewModel.kt`:**
- Adicionado `noGamesFlow: Flow<Boolean>` que combina os três queries de jogos do DB.
- `getDownloadRomsState()` passou a usar `combine(romsDownloadManager.state, noGamesFlow, indexingFlow)`
  e faz override `Done → Idle` quando:
  ```
  dlState == Done && noGames && !isIndexing && !isDownloadStarted()
  ```
- Removida a condição `&& !romsDownloadManager.isDownloadDone()` do cálculo de
  `showDownloadPromptDialog` — a condição `showNoGamesCard` sozinha já cobre todos os casos.

### B-2 · Bug (regressão) — Card e dialog piscavam logo após download concluído

**Problema introduzido pelo fix B-1:** Imediatamente após a conclusão do download, o
`LibraryIndexScheduler` roda a varredura de biblioteca. Durante a varredura, o DB está
temporariamente vazio → `noGames=true` → override `Done → Idle` era disparado →
card de "Download ROMs" e dialog de confirmação apareciam por alguns segundos antes de os
jogos serem populados no DB.

**Correção em `HomeViewModel.kt`:**
- Adicionado `indexingFlow: Flow<Boolean>` (reutilizando `indexingInProgress(appContext)`).
- Override `Done → Idle` agora só é disparado quando `!isIndexing`.
- Dialog condition passou a incluir `&& !state.indexInProgress`.

```kotlin
// Fluxo final correto:
fun getDownloadRomsState(): Flow<DownloadRomsState> = combine(
    romsDownloadManager.state, noGamesFlow, indexingFlow,
) { dlState, noGames, isIndexing ->
    if (dlState is Done && noGames && !isIndexing && !isDownloadStarted()) Idle else dlState
}

// Dialog condition:
showDownloadPromptDialog = state.showNoGamesCard
    && !state.indexInProgress
    && !romsDownloadManager.isDownloadStarted()
    && !dismissed
```

---

## Sessão C — Revisão geral do fluxo

### C-1 · Bug — `CancellationException` engolida em `RomsDownloadWork.doWork()`

**Problema:** O bloco `catch (e: Throwable)` interceptava `CancellationException`
(subclasse de `Throwable`) e retornava `Result.failure()`, causando um estado FAILED
falso no WorkManager ao invés do estado CANCELLED esperado.

**Correção em `RomsDownloadWork.kt`:**
```kotlin
// Adicionado ANTES do catch genérico:
} catch (e: CancellationException) {
    throw e  // propaga corretamente para o WorkManager
} catch (e: Throwable) {
    Result.failure(...)
}
```

### C-2 · Bug — Loop infinito de retry em arquivo corrompido

**Problema:** Se a extração falhasse (arquivo `.7z` corrompido ou truncado),
`PREF_ARCHIVE_DOWNLOADED=true` nunca era limpo. Cada "Tentar novamente" seguia o caminho:
> skip download (flag true) → tenta extrair → falha → loop eterno

**Correção em `RomsDownloadManager.kt`:**
```kotlin
try {
    extractSevenZ(archiveFile, romsDir) { ... }
} catch (e: CancellationException) {
    throw e  // preserva arquivo para resume na próxima abertura
} catch (e: Exception) {
    // Arquivo corrompido: força re-download na próxima tentativa
    archiveFile.delete()
    prefs.edit().putBoolean(PREF_ARCHIVE_DOWNLOADED, false).apply()
    throw e
}
```

### C-3 · Bug — `PREF_DOWNLOAD_STARTED` não era limpo em falha permanente

**Problema:** Quando o download falhava definitivamente (5 tentativas × 5 retries esgotados),
`PREF_DOWNLOAD_STARTED` permanecia `true`. A cada reabertura do app, o bloco `init` de
`RomsDownloadManager` re-enfileirava o trabalho automaticamente — mesmo o usuário não tendo
clicado em "Tentar novamente". O app ficava em loop de tentativas de download automáticas
para sempre, até um cancelamento explícito.

**Correção em `RomsDownloadManager.kt`:** Adicionado `clearDownloadStarted()`:
```kotlin
fun clearDownloadStarted() {
    prefs.edit().putBoolean(PREF_DOWNLOAD_STARTED, false).apply()
}
```

**Correção em `RomsDownloadWork.kt`:** Chamado no path de failure:
```kotlin
} catch (e: Throwable) {
    val msg = ...
    RomsDownloadManager(applicationContext).clearDownloadStarted()
    Result.failure(workDataOf(KEY_ERROR to msg))
}
```

---

## Tabela resumo de todos os bugs corrigidos em 2026-03-17

| ID | Severidade | Arquivo(s) | Sintoma | Fix |
|----|-----------|-----------|---------|-----|
| A-1 | Feature | `RomsDownloadManager`, `HomeViewModel` | Download reiniciava do zero após fechar o app | `PREF_DOWNLOAD_STARTED` + `PREF_ARCHIVE_DOWNLOADED` + `init` auto-resume |
| A-2 | Feature | `HomeViewModel` | Dialog aparecia mesmo com download em andamento | `!isDownloadStarted()` na condição do dialog |
| A-3 | Feature | `HomeScreen`, `HomeViewModel`, strings | Sem botão de cancelar | `AlertDialog` de confirmação + `cancelDownload()` |
| A-4 | **Crítico** (ANR) | `HomeViewModel` | `deleteRecursively` (~5 GB) na UI thread → ANR garantido | `viewModelScope.launch(Dispatchers.IO)` |
| B-1 | **Alto** | `HomeViewModel` | Card/dialog não reapareciam após deletar ROMs | `noGamesFlow` + override `Done→Idle` em `getDownloadRomsState()` |
| B-2 | **Alto** (regressão) | `HomeViewModel` | Card/dialog piscavam logo após download concluído | `indexingFlow` + guard `!isIndexing` + `!indexInProgress` no dialog |
| C-1 | **Médio** | `RomsDownloadWork` | `CancellationException` retornava `Result.failure()` falso | `catch (CancellationException) { throw e }` antes do catch genérico |
| C-2 | **Alto** | `RomsDownloadManager` | Loop infinito de retry em arquivo corrompido | `try/catch` em `extractSevenZ` + delete archive + clear flag |
| C-3 | **Alto** | `RomsDownloadWork`, `RomsDownloadManager` | Auto-resume em loop após falha permanente (sem ação do usuário) | `clearDownloadStarted()` no path de `Result.failure()` |

---

## Comportamento esperado após todas as correções

| Cenário | Resultado esperado |
|---------|-------------------|
| App fechado durante download → reabre | Retoma automaticamente de onde parou |
| App fechado durante extração → reabre | Pula download, retoma extração |
| Download concluído → delete ROMs → rescan 0 jogos | Card e dialog reaparecem |
| Download concluído → scan em andamento (DB vazio) | Card/dialog suprimidos durante indexação |
| Download falha (5 tentativas esgotadas) → fecha app | **Não** auto-retoma; aguarda usuário clicar "Tentar novamente" |
| Arquivo .7z corrompido → "Tentar novamente" | Deleta arquivo corrompido e reinicia download do zero |
| Cancelar durante download ou extração | Todos os arquivos deletados em IO thread; dialog não reaparece |
| Cancelar → Trabalho cancelado → WorkManager CANCELLED | `CancellationException` propagada; estado CANCELLED correto |
