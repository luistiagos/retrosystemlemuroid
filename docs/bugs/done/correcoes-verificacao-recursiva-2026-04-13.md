# Correções — Verificação Recursiva de Bugs (13/04/2026)

## Resumo

Verificação recursiva em 4 rounds sobre todas as otimizações de performance implementadas anteriormente.  
O loop de verificação encerrou quando nenhum novo bug foi encontrado na Round 4.

---

## Round 1 — 4 bugs encontrados e corrigidos

### Bug 1: Thread-safety do cache de caminhos de cores (GameLoader.kt)

**Arquivo:** `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/game/GameLoader.kt`

**Problema:** O `corePathCache` usava `mutableMapOf<String, String>()` (HashMap), que não é thread-safe. Como `findLibrary()` pode ser chamado por múltiplas coroutines simultaneamente, havia risco de `ConcurrentModificationException` ou corrupção de dados.

**Correção:** Substituído por `java.util.concurrent.ConcurrentHashMap<String, String>()` que é thread-safe por design.

```kotlin
// ANTES (bug)
private val corePathCache = mutableMapOf<String, String>()

// DEPOIS (corrigido)
private val corePathCache = java.util.concurrent.ConcurrentHashMap<String, String>()
```

---

### Bug 2: Dependência faltando no remember do Compose (LemuroidGameImage.kt)

**Arquivo:** `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/shared/compose/ui/LemuroidGameImage.kt`

**Problema:** O `remember` do `ImageRequest` usava apenas `game.coverFrontUrl` como chave, mas o lambda capturava `context` (de `LocalContext.current`) sem incluí-lo nas dependências. Se o contexto mudasse (ex: rotação de tela), o `ImageRequest` ficaria com uma referência obsoleta.

**Correção:** Adicionado `context` à lista de dependências do `remember`.

```kotlin
// ANTES (bug)
val imageRequest = remember(game.coverFrontUrl) { ... }

// DEPOIS (corrigido)
val imageRequest = remember(game.coverFrontUrl, context) { ... }
```

---

### Bug 3: Mesma dependência faltando (LemuroidSmallGameImage.kt)

**Arquivo:** `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/shared/compose/ui/LemuroidSmallGameImage.kt`

**Problema:** Idêntico ao Bug 2.

**Correção:** Idêntica ao Bug 2.

---

### Bug 4: CoroutineScope sem cancelamento (LibretroDBManager.kt)

**Arquivo:** `lemuroid-metadata-libretro-db/src/main/java/com/swordfish/lemuroid/metadata/libretrodb/db/LibretroDBManager.kt`

**Problema:** O `CoroutineScope(SupervisorJob() + Dispatchers.IO)` era criado mas nunca cancelado, podendo causar vazamento de memória se a instância fosse destruída.

**Correção:** Adicionado `import kotlinx.coroutines.cancel` e método `fun close()` que cancela o scope.

```kotlin
fun close() {
    scope.cancel()
}
```

---

## Round 2 — Build OK, auditoria confirmou correções

Todas as 4 correções da Round 1 foram verificadas como corretas.  
Build compilou com sucesso. Nenhum novo bug introduzido.

---

## Round 3 — 3 bugs encontrados e corrigidos

### Bug 5: Loop infinito em calculateInSampleSize (StatesPreviewManager.kt)

**Arquivo:** `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/saves/StatesPreviewManager.kt`

**Problema:** Se `reqWidth` ou `reqHeight` fossem 0, a condição `halfWidth / inSampleSize >= 0` seria sempre verdadeira, causando loop infinito (o `inSampleSize` dobraria até overflow de `Int`).

**Correção:** Adicionada guarda no início da função:

```kotlin
private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
    if (reqWidth <= 0 || reqHeight <= 0) return 1  // ← ADICIONADO
    // ... resto da lógica
}
```

---

### Bug 6: NPE após extractThumbnail (StatesPreviewManager.kt)

**Arquivo:** `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/saves/StatesPreviewManager.kt`

**Problema:** `ThumbnailUtils.extractThumbnail()` pode retornar `null` em certas versões do Android. O código não verificava o retorno, o que causaria NPE.

**Correção:** Adicionada verificação de nulo com recycle do bitmap original:

```kotlin
val thumbnail = ThumbnailUtils.extractThumbnail(bitmap, size, size)
if (thumbnail == null) {
    bitmap.recycle()
    return@withContext null
}
if (thumbnail !== bitmap) bitmap.recycle()
```

---

### Bug 7: Semaphore por batch ao invés de global (LemuroidLibrary.kt)

**Arquivo:** `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/LemuroidLibrary.kt`

**Problema:** O `Semaphore(MAX_CONCURRENT_METADATA)` era criado DENTRO de `processBatch()`, ou seja, cada batch recebia seu próprio semaphore. Com `flatMapMerge(concurrency = 4)` permitindo 4 batches simultâneos, o limite real era 4 × 4 = 16 operações concorrentes, e não 4 como pretendido. Em dispositivos com pouca RAM, isto poderia causar OOM.

**Correção:** Movido o semaphore para nível de classe:

```kotlin
class LemuroidLibrary(...) {
    /** Global semaphore to bound concurrent metadata lookups across all batches. */
    private val metadataSemaphore = Semaphore(MAX_CONCURRENT_METADATA)

    // Em processBatch():
    val newEntries = coroutineScope {
        entries.filterIsInstance<ScanEntry.File>()
            .map { async { metadataSemaphore.withPermit { ... } } }
            .awaitAll()
    }
}
```

---

## Round 4 — 0 bugs encontrados → LOOP ENCERRADO

Auditoria final confirmou que todas as correções estão corretas.  
Nenhum novo bug detectado. Nenhum código externo quebrado pelas alterações.

---

## Arquivos modificados

| Arquivo | Bugs corrigidos |
|---------|----------------|
| `GameLoader.kt` | #1 ConcurrentHashMap |
| `LemuroidGameImage.kt` | #2 remember + context |
| `LemuroidSmallGameImage.kt` | #3 remember + context |
| `LibretroDBManager.kt` | #4 scope.cancel() |
| `StatesPreviewManager.kt` | #5 guard size≤0, #6 null check thumbnail |
| `LemuroidLibrary.kt` | #7 semaphore global |

## Status do Build

**BUILD SUCCESSFUL** — Todas as 3 rounds de build compilaram com sucesso.
