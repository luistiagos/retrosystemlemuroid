# Revisão Recursiva de Bugs — 15/Abr/2026

## Processo

Três rodadas de verificação foram executadas até que nenhum bug fosse encontrado:

| Rodada | Bugs encontrados | Status |
|--------|-----------------|--------|
| 1      | 1               | Corrigido |
| 2      | 17              | Corrigidos |
| 3      | 0               | Verificação final limpa |

---

## Rodada 1 — Crash por SystemID desconhecido (TV)

### Bug #1: `GameSystem.findById()` inseguro em `TVHomeViewModel.kt`
- **Arquivo:** `lemuroid-app/.../tv/home/TVHomeViewModel.kt` linha 109
- **Severidade:** HIGH
- **Problema:** `GameSystem.findById(systemId)` lança `NoSuchElementException` se o systemId do banco não for reconhecido pelo enum `GameSystem`.
- **Correção:** Trocado por `GameSystem.findByIdOrNull(systemId)` com `mapNotNull`.

---

## Rodada 2 — Padrões inseguros em todo o codebase

### Bugs #2–#10: Todas as chamadas `GameSystem.findById()` sem null safety

Cada chamada foi convertida para `findByIdOrNull()` com tratamento de null apropriado:

| # | Arquivo | Linha | Correção aplicada |
|---|---------|-------|-------------------|
| 2 | `GameUtils.kt` | 27 | `findByIdOrNull()?.shortTitleResId ?: game.systemId` |
| 3 | `GameLoader.kt` | 76 | `findByIdOrNull() ?: throw GameLoaderException(Generic)` |
| 4 | `GameLauncher.kt` | 33 | `findByIdOrNull() ?: return@launch` |
| 5 | `BaseGameActivity.kt` | 97 | `findByIdOrNull() ?: run { finish(); return }` |
| 6 | `CoreUpdateWork.kt` | 69 | `.mapNotNull { findByIdOrNull(it) }` + removido `.asFlow()/.toList()` desnecessário |
| 7 | `LemuroidLibrary.kt` | 307 | `findByIdOrNull() ?: return null` |
| 8 | `GameViewModelRetroGameView.kt` | 260 | `findByIdOrNull() ?: GameSystem.findById("snes")` (fallback seguro) |
| 9 | `LibretroDBMetadataProvider.kt` | 248 | `.mapNotNull { findByIdOrNull(it) }` |
| 10 | `LibretroDBMetadataProvider.kt` | 331 | `extractGameSystem()` agora retorna `GameSystem?` |

### Bug #11: `extractGameSystem()` com `rom.system!!` force-unwrap
- **Arquivo:** `LibretroDBMetadataProvider.kt` linha 333
- **Severidade:** CRITICAL
- **Problema:** Dupla insegurança: `GameSystem.findById()` + `rom.system!!`
- **Correção:** Retorno nullable com `val systemId = dedicatedSystemId ?: rom.system ?: return null`

### Bug #12: `convertToGameMetadata()` não propagava null
- **Arquivo:** `LibretroDBMetadataProvider.kt` linha 187
- **Severidade:** HIGH
- **Correção:** Retorno alterado para `GameMetadata?`, retorna `null` quando `extractGameSystem()` retorna `null`.

### Bugs #13–#14: `file.serial!!` e `file.systemID!!` force-unwraps
- **Arquivo:** `LibretroDBMetadataProvider.kt` linhas 294, 305
- **Severidade:** HIGH
- **Problema:** Force-unwrap em campos nullable após null-check com `if`, mas sem smart-cast.
- **Correção:** Usadas variáveis locais com `?: return null`.

### Bugs #15–#16: Casts inseguros nos TV Presenters
- **Arquivo:** `GamePresenter.kt` linha 25 — `item as Game` → `item as? Game ?: return`
- **Arquivo:** `SystemPresenter.kt` linha 19 — `item as MetaSystemInfo` → `item as? MetaSystemInfo ?: return`
- **Severidade:** HIGH

### Bug #17: Callers de `extractGameSystem()` não tratavam nullable
- **Arquivo:** `LibretroDBMetadataProvider.kt` linhas 203, 225–228
- **Severidade:** HIGH
- **Correção:** Atualizado para `?.scanOptions?.scanByFilename == true` e `?.let { sys -> ... } == true`

---

## Rodada 3 — Verificação Final

Nenhum bug encontrado. Verificações realizadas:
- Zero chamadas `GameSystem.findById(` restantes sem safety (exceto fallback "snes" válido)
- Zero operadores `!!` nos 11 arquivos editados
- Todos os callers de tipos nullable tratam corretamente
- Nenhum `.first()` em coleções potencialmente vazias
- Nenhum bug novo introduzido pelas correções

---

## Arquivos Modificados

1. `lemuroid-app/.../tv/home/TVHomeViewModel.kt`
2. `lemuroid-app/.../utils/games/GameUtils.kt`
3. `retrograde-app-shared/.../lib/game/GameLoader.kt`
4. `lemuroid-app/.../shared/game/GameLauncher.kt`
5. `lemuroid-app/.../shared/game/BaseGameActivity.kt`
6. `lemuroid-app/.../shared/library/CoreUpdateWork.kt`
7. `retrograde-app-shared/.../lib/library/LemuroidLibrary.kt`
8. `lemuroid-app/.../shared/game/viewmodel/GameViewModelRetroGameView.kt`
9. `lemuroid-metadata-libretro-db/.../LibretroDBMetadataProvider.kt`
10. `lemuroid-app/.../tv/shared/GamePresenter.kt`
11. `lemuroid-app/.../tv/home/SystemPresenter.kt`

## Impacto

Todas as 17 correções eliminam potenciais crashes `NoSuchElementException` ou `NullPointerException` que ocorreriam quando:
- O banco de dados contém IDs de sistema obsoletos ou não reconhecidos
- ROMs de sistemas desconhecidos são indexadas
- Metadados de bibliotecas externas contêm dados inesperados
