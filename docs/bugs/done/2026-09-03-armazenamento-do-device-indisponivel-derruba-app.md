# [BUG] Armazenamento do aparelho em estado ruim derruba o app — `SQLiteFullException` e `workdb` em storage adotado que sumiu

**Data:** 2026-09-03  
**Status:** ✅ Resolvido (2026-09-17)  
**Severidade:** Baixa (2 ocorrências, ambas em Chromecast; não há perda de dados do usuário)  
**Branch:** version9  
**Origem:** telemetria `retrogamesystem/main`  
**Errors (serviço):** 2 ocorrências — 1696, 2003  
**Aparelhos:** Google Chromecast HD e Google Chromecast, **armeabi-v7a**, Android 14, app 1.17.9 / 1.17.10  
**Observado em:** 2026-08-22 03:36 e 2026-08-23 22:28  

> Classificação honesta: **a causa não é nossa.** O que é nosso é a exceção subir sem tratamento
> e matar o processo. Registrado como fronteira (ambiente do usuário, mas ação nossa possível),
> no mesmo espírito de [[2026-09-02-telemetria-lowmemory-e-eco-de-crash]].

---

## Sintoma

### 2003 — disco cheio durante uma transação do Room

```
android.database.sqlite.SQLiteFullException: database or disk is full (code 13 SQLITE_FULL)
	at android.database.sqlite.SQLiteConnection.nativeExecute(Native Method)
	at android.database.sqlite.SQLiteSession.endTransactionUnchecked(SQLiteSession.java:439)
	at android.database.sqlite.SQLiteDatabase.endTransaction(SQLiteDatabase.java:757)
	…
	at androidx.room.a$a$b.invokeSuspend(SourceFile:13)
	at kotlinx.coroutines… (Dispatchers.IO)
	Suppressed: DiagnosticCoroutineContextException: [T0{Cancelling}@…, Dispatchers.IO]
```

Thread `DefaultDispatcher-worker-3`. A falha é no **commit** da transação, não na query. Sendo
uma exceção não capturada dentro de uma coroutine sem handler, o processo cai.

### 1696 — `workdb` do WorkManager num storage adotado que não existe mais

```
java.lang.IllegalStateException: The file system on the device is in a bad state.
WorkManager cannot access the app's internal data store.
	at androidx.work.impl.utils.ForceStopRunnable.run(SourceFile:83)
Caused by: SQLiteCantOpenDatabaseException: Cannot open database
  '/mnt/expand/3ebe6f94-5e81-40a1-bef3-d68f1a64e712/user/0/app.retrogamesystem/no_backup/androidx.work.workdb'
  … Directory …/no_backup doesn't exist
```

Thread `WM.task-1`. O app foi movido para **storage adotado** (`/mnt/expand/<uuid>`) e o volume
não está montado — ou foi reformatado. O `ForceStopRunnable` do WorkManager transforma isso numa
`IllegalStateException` deliberada, que também não é capturada.

## Causa-raiz

Ambas são **estado do dispositivo**, não defeito de lógica:

- 2003: o Chromecast (armazenamento de 8 GB, e o app baixa ROMs) encheu. SQLite não consegue
  nem fechar a transação.
- 1696: `/mnt/expand/<uuid>` é o caminho de *adoptable storage*. Quando o cartão/volume adotado
  some, todo o diretório de dados do app desaparece com ele. O WorkManager detecta e aborta de
  propósito — [é comportamento documentado da própria biblioteca](https://issuetracker.google.com/issues/138326753).

O que era nosso: nos dois casos a exceção subia até o `UncaughtExceptionHandler` e o app fechava.
Para o usuário isso é indistinguível de um bug — e a tela que ele via era a genérica de erro.

## Como reproduzir

- **2003:** encher o armazenamento do aparelho (`fallocate`/copiar arquivos até sobrar < 1 MB) e
  disparar qualquer escrita no catálogo (favoritar um jogo, terminar um download).
- **1696:** mover o app para um cartão SD adotado, desligar, remover o cartão, ligar.

## Correção

### 1. WorkManager com `Configuration.Provider` e `setInitializationExceptionHandler`

Em [LemuroidApplication.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/LemuroidApplication.kt), a classe implementou `androidx.work.Configuration.Provider`:

```kotlin
override val workManagerConfiguration: Configuration
    get() = Configuration.Builder()
        .setInitializationExceptionHandler { throwable ->
            Timber.e(throwable, "WorkManager initialization failed (bad storage or file system state)")
        }
        .build()
```

No WorkManager 2.8+, o `ForceStopRunnable` verifica se há um `initializationExceptionHandler` configurado antes de relançar `IllegalStateException`. Com o handler presente, a exceção é consumida e logada, impedindo a morte do processo na thread `WM.task-1`.

Em [MainProcessInitializer.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/startup/MainProcessInitializer.kt), chamadas ao `WorkManager.getInstance()` foram protegidas com `try/catch` defensivo.

### 2. Tratamento defensivo nas escritas do Room

- [GameInteractor.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/GameInteractor.kt): `onFavoriteToggle` envolve a atualização no `gameDao().update()` em `try/catch (e: SQLiteException)`. Caso falhe, exibe toast amigável via `Context.displayToast(R.string.home_download_roms_out_of_space)`.
- [GameLaunchTaskHandler.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/main/GameLaunchTaskHandler.kt): `updateGamePlayedTimestamp` captura `SQLiteException` para que falhas de persistência de timestamp após o encerramento do jogo não derrubem a Activity principal.
- [RomOnDemandManager.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/RomOnDemandManager.kt):
  - Em `downloadRom`: proteger a inserção de `downloadedRomDao.insert()`. Se falhar por `SQLiteException`, notifica via toast e devolve `DownloadResult.Failure` informando que o armazenamento está insuficiente (evita mentir sucesso para o usuário).
  - Em `prepareGameForLaunch`, `deleteRom` e `deleteFromCatalog`: operações de persistência e deleção protegidas contra `SQLiteException`.
- [SaveQueueManager.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/SaveQueueManager.kt): escritas e atualizações de estado no `saveQueueDao` protegidas contra `SQLiteException`, mantendo a integridade da fila em memória e emitindo toast se o enfileiramento falhar por disco cheio.

### 3. Tratamento Centralizado e Filtragem na Telemetria (`CrashTelemetry`)

Em [CrashTelemetry.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/telemetry/CrashTelemetry.kt):
- Adicionada a função `isStorageEnvironmentFailure(throwable: Throwable?): Boolean` que inspeciona a cadeia de causas e exceções suprimidas em busca de `SQLiteFullException`, `SQLiteCantOpenDatabaseException`, `SQLiteDiskIOException`, ou mensagens indicativas de disco cheio (`SQLITE_FULL`, `ENOSPC`, `No space left on device`) e storage corrompido/ausente (`The file system on the device is in a bad state`, `Cannot open database`).
- No `installUncaughtHandler`:
  - Se ocorrer uma falha de ambiente de armazenamento em **thread de background** (fora da main thread), ela é interceptada: o erro é registrado no log local (`Timber.e`), um toast amigável é agendado na UI thread (`home_download_roms_out_of_space`) e a execução não propaga para o `uncaughtException` do sistema — **evitando a morte do processo**.
  - Erros de ambiente de armazenamento são filtrados da `CrashTelemetry`, evitando poluir o painel de bugs abertos com falhas externas ao app.

## Validação

1. **Testes Unitários:**
   - Criado [StorageEnvironmentFailureTest.kt](../../../lemuroid-app/src/test/java/com/swordfish/lemuroid/app/shared/telemetry/StorageEnvironmentFailureTest.kt) cobrindo 9 cenários de detecção: exceções diretas, mensagens de erro, causas aninhadas, exceções suprimidas, WorkManager `ForceStopRunnable`, `ENOSPC`, e subclasses mock de `SQLiteException`.
   - Executado via Gradle:
     ```
     .\gradlew.bat :lemuroid-app:testFreeBundleDebugUnitTest --tests "com.swordfish.lemuroid.app.shared.telemetry.StorageEnvironmentFailureTest"
     ```
     **Resultado:** BUILD SUCCESSFUL, todos os testes passaram.
2. **Compilação e Bytecode:**
   - Executado:
     ```
     .\gradlew.bat :lemuroid-app:compileFreeBundleDebugKotlin :lemuroid-app:kaptFreeBundleDebugKotlin
     ```
     **Resultado:** BUILD SUCCESSFUL em 5s com zero erros.

## Lição

"Sem espaço" e "volume sumiu" não são condições exóticas no público-alvo deste app — TV box e
Chromecast têm 8 GB e o app existe para baixar ROMs. Toda escrita em disco no caminho de
background precisa de um plano para o caso de o disco não estar lá; o default (exceção não
tratada em coroutine = processo morto) é o pior deles.
Além disso, componentes de background como o WorkManager fornecem hooks específicos (`initializationExceptionHandler`) projetados para interceptar e absorver falhas decorrentes do ambiente do dispositivo antes que elas se propaguem para a thread de execução não tratada.
