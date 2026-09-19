# Correções — 19/03/2026

## Resumo

Passagem recursiva de revisão de bugs em todo o codebase. Todos os bugs encontrados foram corrigidos e o build foi verificado com sucesso (`BUILD SUCCESSFUL`).

---

## Bug 1 — `SaveSyncWork`: `setForegroundAsync` descartando o Future

**Arquivo:** `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/savesync/SaveSyncWork.kt`

**Problema:** A função `displayNotification()` chamava `setForegroundAsync(foregroundInfo)` em uma função comum (não-suspensa). O `ListenableFuture` retornado era silenciosamente descartado — o serviço em primeiro plano podia nunca iniciar, causando `ForegroundServiceStartNotAllowedException` no Android 12+.

**Correção:** Convertida para `suspend fun displayNotification()` usando `setForeground(foregroundInfo)` (API suspensa do `CoroutineWorker`), que aguarda corretamente a conclusão.

---

## Bug 2 — `SaveSyncWork`: `catch(Throwable)` engolindo `CancellationException`

**Arquivo:** `lemuroid-app/.../SaveSyncWork.kt`

**Problema:** `catch (e: Throwable)` ao redor de `saveSyncManager.sync(coresToSync)` interceptava `CancellationException`, impedindo o cancelamento cooperativo da corrotina pelo WorkManager.

**Correção:** Adicionado `catch (e: CancellationException) { throw e }` antes do `catch (Throwable)`.

---

## Bug 3 — `FlowUtils.safeCollect`: padrão global engolindo `CancellationException`

**Arquivo:** `retrograde-util/src/main/java/com/swordfish/lemuroid/common/coroutines/FlowUtils.kt`

**Impacto:** ALTO — `safeCollect` é usado em todo o app (GameViewModelInput, GameViewModelTouchControls, TVGameActivity, MainTVActivity, etc.)

**Problema:** O bloco interno `try { block(it) } catch (e: Throwable) { onError(e) }` capturava `CancellationException` lançadas dentro de qualquer chamada suspensa dentro do bloco `safeCollect`. Corrotinas filhas jamais propagavam o cancelamento corretamente.

**Correção:** Adicionado `catch (e: CancellationException) { throw e }` antes do `catch (Throwable)`. Também adicionado `import kotlinx.coroutines.CancellationException` ao arquivo.

---

## Bug 4 — `CoroutineUtils.safeLaunch`: `catch(Throwable)` engolindo `CancellationException`

**Arquivo:** `retrograde-util/src/main/java/com/swordfish/lemuroid/common/coroutines/CoroutineUtils.kt`

**Problema:** O utilitário `safeLaunch` lançava corrotinas com `launch { try { block() } catch (e: Throwable) { Timber.e(e) } }`. Se o escopo pai fosse cancelado, a `CancellationException` era capturada e logada como erro, e a corrotina completava normalmente em vez de cooperativamente cancelar.

**Correção:** Adicionado `catch (e: CancellationException) { throw e }` e `import kotlinx.coroutines.CancellationException`.

---

## Bug 5 — `GameViewModelSaves.restoreAutoSaveAsync`: `catch(Throwable)` engolindo `CancellationException`

**Arquivo:** `lemuroid-app/.../GameViewModelSaves.kt`

**Problema:** `waitGLEvent<FrameRendered>()` e `restoreQuickSave(saveState)` são chamadas suspensas dentro de `try { ... } catch (e: Throwable)`. Se o `viewModelScope` fosse cancelado (ex: tela fechada antes do primeiro frame), a `CancellationException` era logada como erro.

**Correção:** Adicionado `catch (e: CancellationException) { throw e }` antes do genérico.

---

## Bug 6 — `GameViewModelSaves.loadSlot`: `catch(Throwable)` engolindo `CancellationException`

**Arquivo:** `lemuroid-app/.../GameViewModelSaves.kt`

**Problema:** `withContext(Dispatchers.IO) { loadSaveState(it) }` dentro de `catch (e: Throwable)` — se cancelado durante a troca de contexto, `CancellationException` era capturada e um toast de "falha ao carregar estado" era exibido indevidamente.

**Correção:** Adicionado `catch (e: CancellationException) { throw e }` antes do genérico.

---

## Bug 7 — `GameViewModelRetroGameView.initializeCoreVariablesFlow`: `catch(Exception)` engolindo `CancellationException`

**Arquivo:** `lemuroid-app/.../GameViewModelRetroGameView.kt`

**Problema:** `catch (e: Exception)` captura `CancellationException` pois esta herda de `Exception` no Kotlin. Quando o ciclo de vida sai do estado `RESUMED`, a corrotina deveria ser cancelada cooperativamente, mas o cancelamento era silenciado.

**Correção:** Adicionado `catch (e: CancellationException) { throw e }` antes do `catch (Exception)`.

---

## Bug 8 — `BaseGameScreenViewModel.reset`: `catch(Throwable)` engolindo `CancellationException`

**Arquivo:** `lemuroid-app/.../BaseGameScreenViewModel.kt`

**Problema:** `delay(...)` e `retroGameView.retroGameViewFlow().reset()` são pontos de cancelamento. Se o `viewModelScope` fosse cancelado durante o reset (ex: tela fechada), a `CancellationException` era capturada e logada como erro.

**Correção:** Adicionado `catch (e: CancellationException) { throw e }` antes do genérico.

---

## Bug 9 — `GameViewModelInput.initializeControllersConfigFlow`: `catch(Exception)` engolindo `CancellationException`

**Arquivo:** `lemuroid-app/.../GameViewModelInput.kt`

**Problema:** `waitRetroGameViewInitialized()` é uma chamada suspensa longa. `catch (e: Exception)` capturava `CancellationException`, impedindo o término cooperativo quando o ciclo de vida saía do estado esperado.

**Correção:** Adicionado `catch (e: CancellationException) { throw e }` antes do genérico.

---

## Bug 10 — `ChannelUpdateWork`: `catch(Throwable)` engolindo `CancellationException`

**Arquivo:** `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/channel/ChannelUpdateWork.kt`

**Problema:** Worker do canal de TV (Android TV) com `catch (e: Throwable)` ao redor de `channelHandler.update()` — CancellationException do WorkManager era engolida.

**Correção:** Adicionado `catch (e: CancellationException) { throw e }` antes do genérico.

---

## Bug 11 — `CacheCleanerWork`: `catch(Throwable)` engolindo `CancellationException`

**Arquivo:** `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/storage/cache/CacheCleanerWork.kt`

**Problema:** `doWork()` com `catch (e: Throwable)` ao redor de `performCleaning()` — CancellationException do WorkManager era engolida.

**Correção:** Adicionado `catch (e: CancellationException) { throw e }` antes do genérico.

---

## Bug 12 — `ExternalGameLauncherActivity`: `catch(Throwable)` engolindo `CancellationException`

**Arquivo:** `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/ExternalGameLauncherActivity.kt`

**Problema:** Dentro de `lifecycleScope.launch { try { loadGame(gameId) } catch (e: Throwable) { ... } }`. Se a Activity fosse destruída enquanto o jogo carregava, `CancellationException` era capturada e `displayErrorMessage()` era chamado em uma Activity já destruída.

**Correção:** Adicionado `catch (e: CancellationException) { throw e }` antes do genérico.

---

## Bug 13 — `StorageFrameworkPickerLauncher`: `catch(Exception)` engolindo `CancellationException`

**Arquivo:** `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/settings/StorageFrameworkPickerLauncher.kt`

**Problema:** Dentro de `lifecycleScope.launch { ... }` ao mover ROMs para um diretório SAF: `withContext(Dispatchers.IO) { copyFileToSaf(...) }` com `catch (e: Exception)` sem rethrow de `CancellationException`.

**Correção:** Adicionado `catch (e: CancellationException) { throw e }` antes do genérico.

---

## Bug 14 — `LemuroidLibrary.indexLibrary`: `catch(Throwable)` engolindo `CancellationException`

**Arquivo:** `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/LemuroidLibrary.kt`

**Problema:** `indexProviders(startedAtMs)` é uma operação longa e suspensa. `catch (e: Throwable)` silenciava o cancelamento do WorkManager durante a indexação de biblioteca.

**Correção:** Adicionado `catch (e: CancellationException) { throw e }` antes do genérico. O bloco `finally { cleanUp(startedAtMs) }` ainda executa corretamente.

---

## Bug 15 — `PPSSPPAssetsManager.retrieveAssetsIfNeeded`: `catch(Throwable)` engolindo `CancellationException`

**Arquivo:** `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/core/assetsmanager/PPSSPPAssetsManager.kt`

**Problema:** `coreUpdaterApi.downloadZip(...)` é uma chamada de rede suspensa. Se cancelada, `CancellationException` era interceptada e o diretório de assets era deletado desnecessariamente.

**Correção:** Adicionado `catch (e: CancellationException) { throw e }` antes do genérico — assets não são deletados em caso de cancelamento, apenas em caso de erro real.

---

## Bug 16 — `GameLoader`: `catch(Exception)` wrapping `CancellationException` em `GameLoaderException`

**Arquivo:** `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/game/GameLoader.kt`

**Problema:** `catch (e: Exception)` transformava qualquer exceção (incluindo `CancellationException`) em `GameLoaderException(GameLoaderError.Generic)`. Isso fazia o fluxo de carregamento mostrar uma mensagem de "erro genérico" ao invés de cancelar cooperativamente.

**Correção:** Adicionado `catch (e: CancellationException) { throw e }` antes do genérico, entre os catch de `GameLoaderException` e `Exception`.

---

## Bug 17 — `CoreUpdaterImpl` (free): `catch(Throwable)` engolindo `CancellationException` no `flatMapMerge`

**Arquivo:** `lemuroid-app-ext-free/src/main/java/com/swordfish/lemuroid/ext/feature/core/CoreUpdaterImpl.kt`

**Problema:** Dentro do `flow { try { retrieveAssets(); retrieveFile() } catch (e: Throwable) { ... } }` usado em `flatMapMerge` — o isolamento de erro por núcleo era intencional, mas `CancellationException` também era capturada, impedindo o cancelamento cooperativo do download de cores.

**Correção:** Adicionado `catch (e: CancellationException) { throw e }` antes do genérico.

---

## Bug 18 — `CoreUpdaterImpl` (play): múltiplos `catch(Throwable)` engolindo `CancellationException`

**Arquivo:** `lemuroid-app-ext-play/src/main/java/com/swordfish/lemuroid/ext/feature/core/CoreUpdaterImpl.kt`

**Problema:** Três blocos `catch (e: Throwable)` em funções suspensas:
- `cancelPendingInstalls` — após o install
- `waitForCompletion` — aguardando Play Store
- `installAssets` — instalação de assets
- `cancelPendingInstall` — cancelamento de sessão

Todos engoliam `CancellationException`, fazendo `CoreUpdateWork.doWork()` receber `Result.success()` ao invés de propagar o cancelamento ao WorkManager.

**Correção:** Adicionado `catch (e: CancellationException) { throw e }` em cada um dos quatro blocos.

---

## Verificação

Build executado após todas as correções:

```
BUILD SUCCESSFUL
```

Todos os módulos compilaram sem erros.
