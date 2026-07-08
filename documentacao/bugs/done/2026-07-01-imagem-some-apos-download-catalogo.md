# [BUG] Imagem do jogo some após baixá-lo do catálogo

**Data:** 2026-07-01
**Status:** CORRIGIDO (compila; kapt Dagger validado)
**Severidade:** ALTA — corrompe silenciosamente a linha do jogo no banco (perde cover, popularidade, flag de representante, favorito)
**Branch:** version9

---

## Sintoma

Ao baixar um jogo do catálogo (fila de download) e, em seguida, jogá-lo, a **capa (cover)
desaparece** da lista — o jogo passa a exibir o fallback de texto (`TextDrawable`). Antes do
download a mesma linha mostrava a imagem normalmente (carregada de `coverFrontUrl` do manifest).

## Causa-raiz

`SaveQueueManager.buildGame()` reconstrói um objeto `Game` **lossy** a partir do `SaveQueueEntry`,
jogando fora campos que existem na linha real do banco:

```kotlin
private fun buildGame(entry: SaveQueueEntry): Game = Game(
    id = entry.gameId,
    fileName = entry.fileName,
    fileUri = entry.fileUri,
    title = entry.title,
    systemId = entry.systemId,
    developer = null,
    coverFrontUrl = null,   // ← descarta entry.coverUrl!
    lastIndexedAt = 0L,     // ← zera
    // popularityIndex = 0 (default), isRepresentative = true (default), isFavorite = false (default)
)
```

Esse `Game` lossy então **volta a ser gravado no banco** por dois caminhos, ambos usando
`game.copy(...)` (que preserva os campos zerados):

1. **Todo jogo** — ao terminar de jogar:
   `GameLaunchTaskHandler.updateGamePlayedTimestamp()` →
   `gameDao().update(game.copy(lastPlayedAt = now))`.
   Como o fluxo pós-download abre o diálogo "baixado, jogar agora?" e chama `onGamePlay(game)`
   com o objeto lossy, ao sair do jogo a linha é sobrescrita com `coverFrontUrl = null`,
   `popularityIndex = 0`, `isRepresentative = true`, `developer = null`, `isFavorite = false`,
   `lastIndexedAt = 0`.
2. **Jogos multi-disco (zip → cue/gdi)** — `RomOnDemandManager.extractMultiDiscZipIfNeeded()` faz
   `gameDao.update(game.copy(fileUri = ..., fileName = ...))`, gravando o cover nulo já no momento
   do download (antes mesmo de jogar).

### Cadeia completa

`onGameClick` (placeholder) → `pendingDownloadGame` (Game **com** cover) → `enqueue` (entry guarda
`coverUrl`) → `processQueue` → **`buildGame` (cover=null)** → `downloadRom` →
`DownloadResult.Success(gameLossy)` → `justCompleted` → MainActivity `playAfterDownload` →
`onGamePlay(gameLossy)` → `launchGameAsync` → ao sair: `update(gameLossy.copy(lastPlayedAt))` →
**linha do banco perde a cover** → catálogo re-consulta → `coverFrontUrl` nulo → fallback de texto.

O `SaveQueueEntry` é uma projeção leve para a UI da fila; nunca deveria ser a fonte da verdade
regravada na tabela `games`.

## Correção

`buildGame` passa a buscar a **linha autoritativa do banco** por `gameId` antes do download,
com fallback de alta fidelidade (preservando `coverFrontUrl = entry.coverUrl`) quando a linha não
existe. Assim o objeto que percorre todo o fluxo (download → extração multi-disco → play →
`updateGamePlayedTimestamp`) carrega todos os campos corretos, e qualquer `copy()+update()`
downstream preserva cover, popularidade, `isRepresentative`, favorito e `lastIndexedAt`.

**Arquivos:**

- [SaveQueueManager.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/SaveQueueManager.kt)
  — injeta `GameDao`; `buildGame` vira `suspend` e faz `gameDao.selectById(entry.gameId)` com
  fallback construído a partir do `entry` (agora com `coverFrontUrl = entry.coverUrl`).
- [LemuroidApplicationModule.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/LemuroidApplicationModule.kt)
  — provider `saveQueueManager(...)` passa `retrogradeDatabase.gameDao()` ao construtor.

Sem migração de schema — mudança 100% em runtime.

## Validação

- `:lemuroid-app:compileFreeBundleDebugKotlin` — **BUILD SUCCESSFUL** (kapt Dagger validou a nova
  dependência `GameDao`; Kotlin validou o `buildGame` agora `suspend`).
- Warnings restantes são pré-existentes (deprecations, unchecked cast em `SaveQueueViewModel`).

## Lição

Objetos de UI/projeção (como `SaveQueueEntry`) nunca devem ser convertidos em entidades e
regravados no banco: qualquer campo que a projeção não carrega vira `null`/default e é persistido
por um `copy()+update()` posterior. Quando um fluxo precisa da entidade completa, buscá-la do DAO
pela PK — não reconstruí-la a partir de um subconjunto de campos.
