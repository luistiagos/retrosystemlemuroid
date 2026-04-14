# Verificação Recursiva de Bugs — 14 de Abril de 2026

Processo de verificação em 5 rodadas até atingir zero bugs novos.  
Todas as correções foram compiladas com sucesso antes de avançar para a próxima rodada.

---

## Rodada 1 — 4 bugs corrigidos

### Bug R1-1 · RumbleManager — condição lógica invertida
**Arquivo:** `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/rumble/RumbleManager.kt`  
**Severidade:** CRÍTICO  
**Problema:** A condição de saída antecipada usava `&&` em vez de `||`:
```kotlin
// Antes (errado):
if (!enableRumble && rumbleSupported) return

// Depois (correto):
if (!enableRumble || !rumbleSupported) return
```
**Impacto:** Com `&&`, a função continuava executando a pipeline completa de vibração quando o usuário desativava o rumble mas o core não o suportava, ou quando o rumble estava ativado mas o core não suportava — ambos os casos desnecessários. A correção com `||` garante saída antecipada sempre que qualquer uma das condições impeça o uso do rumble.

---

### Bug R1-2 · ShortcutsGenerator — vazamento de stream e NPE em `response.body()`
**Arquivo:** `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/shortcuts/ShortcutsGenerator.kt`  
**Severidade:** HIGH  
**Problema:** `response.body()` pode retornar `null` (se a resposta HTTP não tem corpo), o que causaria NPE ao passar para `BitmapFactory.decodeStream()`. Além disso, o `InputStream` nunca era fechado explicitamente, vazando a conexão HTTP.
```kotlin
// Antes (com NPE e leak):
val response = thumbnailsApi.downloadThumbnail(coverUrl)
BitmapFactory.decodeStream(response.body()).cropToSquare()

// Depois (null-safe + stream fechado):
val response = thumbnailsApi.downloadThumbnail(coverUrl)
val body = response.body() ?: return@runCatching retrieveFallbackBitmap(game)
body.use { BitmapFactory.decodeStream(it).cropToSquare() }
```

---

### Bug R1-3 · MainActivity — `GlobalScope` em vez de `lifecycleScope`
**Arquivo:** `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/main/MainActivity.kt`  
**Severidade:** CRÍTICO (memory leak)  
**Problema:** `GlobalScope.safeLaunch` lançava uma corrotina fora do ciclo de vida da `Activity`. Além disso, importava `GlobalScope`, `DelicateCoroutinesApi` e a anotação `@OptIn` desnecessariamente.
```kotlin
// Antes (GlobalScope):
@OptIn(DelicateCoroutinesApi::class)
class MainActivity : ... {
    GlobalScope.safeLaunch {
        reviewManager.initialize(applicationContext)
    }
}

// Depois (lifecycleScope — corretamente cancelado com a Activity):
class MainActivity : ... {
    lifecycleScope.safeLaunch {
        reviewManager.initialize(applicationContext)
    }
}
```
Removidos: `import kotlinx.coroutines.GlobalScope`, `import kotlinx.coroutines.DelicateCoroutinesApi`, `@OptIn(DelicateCoroutinesApi::class)`.

---

### Bug R1-4 · SettingsInteractor — exceção engolida silenciosamente em `deleteDownloadedCores`
**Arquivo:** `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/settings/SettingsInteractor.kt`  
**Severidade:** MEDIUM  
**Problema:** Falhas ao deletar arquivos de core eram silenciadas sem log algum, dificultando depuração.
```kotlin
// Antes:
?.forEach { runCatching { it.deleteRecursively() } }

// Depois (com log de warning):
?.forEach {
    runCatching { it.deleteRecursively() }
        .onFailure { e -> Timber.w(e, "Failed to delete core: ${it.name}") }
}
```
Adicionado import `import timber.log.Timber`.

---

## Rodada 2 — 1 bug corrigido

### Bug R2-1 · MobileGameScreen — divisão por zero no cálculo do viewport
**Arquivo:** `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/game/MobileGameScreen.kt`  
**Severidade:** HIGH  
**Problema:** O cálculo do viewport dividia por `fullPos.width` e `fullPos.height` sem verificar se eram zero. Em Float, divisão por zero retorna `Infinity`/`NaN`, corrompendo o viewport durante layout com dimensões ainda não inicializadas.
```kotlin
// Antes:
if (fullPos == null || viewPos == null) return@LaunchedEffect
val viewport = RectF(
    (viewPos.left - fullPos.left) / fullPos.width,  // NaN se width=0
    ...
)

// Depois:
if (fullPos == null || viewPos == null) return@LaunchedEffect
if (fullPos.width <= 0f || fullPos.height <= 0f) return@LaunchedEffect  // guard adicionado
val viewport = RectF(...)
```

---

## Rodada 3 — 2 bugs corrigidos

### Bug R3-1 · TVFolderPickerStorageFragment — `indexOf` retornando -1 sem verificação
**Arquivo:** `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/folderpicker/TVFolderPickerStorageFragment.kt`  
**Severidade:** HIGH  
**Problema:** `it.indexOf("/Android/data/")` retorna `-1` se o caminho não contém a substring. `it.substring(0, -1)` lança `StringIndexOutOfBoundsException`. Embora o `runCatching` externo protegesse do crash, ele descartava **todos** os volumes de armazenamento e caía no fallback menos preciso.
```kotlin
// Antes:
.map { it.absolutePath }
.map { File(it.substring(0, it.indexOf("/Android/data/"))) }

// Depois (via mapNotNull, descarta individualmente os paths sem a substring):
.mapNotNull { dir ->
    val path = dir.absolutePath
    val idx = path.indexOf("/Android/data/")
    if (idx >= 0) File(path.substring(0, idx)) else null
}
```

---

### Bug R3-2 · TVGamePadBindingFragment — id. ao R3-1
**Arquivo:** `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/settings/TVGamePadBindingFragment.kt`  
**Severidade:** HIGH  
**Problema e correção:** Idênticos ao Bug R3-1. Mesmo padrão, mesmo fix aplicado.

---

## Rodada 4 — 1 correção de baixa severidade

### Bug R4-1 · RomOnDemandManager — `FileOutputStream` sem `.use{}` em `deleteRom`
**Arquivo:** `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/RomOnDemandManager.kt`  
**Severidade:** LOW  
**Problema:** `FileOutputStream(destFile, false).close()` chamava `close()` diretamente. Se `close()` lançasse uma exceção, o stream não seria liberado corretamente. Além disso, era inconsistente com a outro uso do mesmo arquivo que já usava `.use {}`.
```kotlin
// Antes:
FileOutputStream(destFile, false).close()

// Depois:
FileOutputStream(destFile, false).use { }
```

---

## Rodada 5 — zero bugs reais encontrados

A rodada 5 escaneou `retrograde-library`, `StreamingRomsManager`, `StreamingRomsWork` e outros módulos de baixo nível. Um potencial TOCTOU em `SavesCoherencyEngine` foi analisado e classificado como **falso positivo** em contexto Android: saves são escritos exclusivamente pelo thread do emulador e a verificação de coerência é feita durante o carregamento do jogo — nunca concorrentemente.

**Loop encerrado após Rodada 5 sem novos bugs.**

---

## Resumo

| Rodada | Bugs encontrados | Bugs corrigidos |
|--------|-----------------|-----------------|
| 1 | 4 | 4 (RumbleManager, ShortcutsGenerator, MainActivity, SettingsInteractor) |
| 2 | 1 | 1 (MobileGameScreen viewport) |
| 3 | 2 | 2 (TVFolderPickerStorageFragment, TVGamePadBindingFragment) |
| 4 | 1 | 1 (RomOnDemandManager) |
| 5 | 0 | 0 — loop encerrado |
| **Total** | **8** | **8** |

Todos os 8 bugs foram corrigidos e o build compilou com sucesso em cada rodada.
