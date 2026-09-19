# [BUG] Catálogo some após um tempo — sobram só as ROMs baixadas

**Data:** 2026-07-01
**Status:** CORRIGIDO (compila; kapt Room + Dagger validados)
**Severidade:** ALTA — o app perde todo o catálogo navegável; só restam os jogos já baixados
**Branch:** version9

---

## Sintoma

Após um certo período de uso, **todas as ROMs e sistemas não-baixados desaparecem do catálogo**,
ficando apenas o que o usuário já havia baixado. Não é falha de download nem do manifest — os
placeholders somem do banco.

## Causa-raiz

O scan de biblioteca (`LibraryIndexWork` → `LemuroidLibrary.indexLibrary()`) é destrutivo para o
catálogo:

1. `indexLibrary()` percorre o filesystem via `LocalStorageProvider`. Para cada arquivo
   **presente no disco**, renova o `lastIndexedAt` do game
   ([LemuroidLibrary.kt](../../../retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/LemuroidLibrary.kt) — `updateGames`).
2. No `cleanUp()`, chama `removeDeletedGames()` → `gameDao().deleteByLastIndexedAtLessThan(startedAtMs)`,
   que **apaga todo game cujo `lastIndexedAt` não foi renovado** neste scan — interpretando isso
   como "arquivo removido do disco".
3. Mas neste fork os games do catálogo são **placeholders só-no-DB, sem arquivo em disco**
   (ver CLAUDE.md, "Sem placeholders em disco"). O scan nunca os encontra → `lastIndexedAt` fica
   antigo → **são todos apagados**.
4. A query já tinha um guard parcial `NOT EXISTS downloaded_roms` (correção anterior de um sintoma
   parecido). Esse guard protege **apenas os baixados** — por isso o resultado é exatamente
   "sobra só o que foi baixado".

### Gatilho ("após um certo período de tempo")

`MediaMountedReceiver` dispara em `ACTION_MEDIA_MOUNTED` (remontagem de storage — evento comum do
sistema) e chama `LibraryIndexScheduler.scheduleManualLibrarySync`. Também qualquer rescan de
settings / troca de pasta enfileira a `LibraryIndexWork`. Nenhuma ação explícita do usuário é
necessária.

### Por que só os baixados sobrevivem

Baixados ficam em `downloaded_roms` (protegidos pelo `NOT EXISTS`) **e** existem em disco na pasta
interna de ROMs (então o scan renova o `lastIndexedAt` deles quando a pasta interna é escaneada).
Placeholders não têm nenhuma das duas proteções.

## Correção

Estender o guard para que o scan **nunca apague games sob a pasta de ROMs gerenciada** (onde vivem
todo o catálogo + downloads) nem sob o prefixo sentinela do prebuilt DB. Mantém-se a limpeza legada
apenas para ROMs importadas pelo usuário **fora** dessa pasta (pasta externa / SAF) que sumiram do
disco.

**Arquivos:**

- [GameDao.kt](../../../retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/db/dao/GameDao.kt)
  — `deleteByLastIndexedAtLessThan` agora recebe `romsPrefix` e `sentinelPrefix` e adiciona:
  ```sql
  AND SUBSTR(fileUri, 1, LENGTH(:romsPrefix))     <> :romsPrefix
  AND SUBSTR(fileUri, 1, LENGTH(:sentinelPrefix)) <> :sentinelPrefix
  ```
  Usa `SUBSTR` (não `LIKE`) para não quebrar com `_`/`%` no path. Prefixo vazio protege tudo
  (fail-safe: nada é deletado).
- [LemuroidLibrary.kt](../../../retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/LemuroidLibrary.kt)
  — injeta `DirectoriesManager`; computa `romsUriPrefix`
  (`getInternalRomsDirectory().toUri().toString().trimEnd('/')`, cacheado, fallback `""`);
  const `PREBUILT_URI_PREFIX = "file:///lemuroid_prebuilt"`; passa ambos no `removeDeletedGames`.
- [LemuroidApplicationModule.kt](../../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/LemuroidApplicationModule.kt)
  — provider `lemuroidLibrary(...)` passa `directoriesManager` ao construtor.

Sem migração de schema e sem regenerar o prebuilt DB — mudança 100% em runtime.

### Comportamento após o fix

| Caso | Antes | Depois |
|------|-------|--------|
| Placeholder do catálogo (sem arquivo) | apagado | **preservado** (sob `romsPrefix`) |
| ROM baixada (pasta interna) | preservada (via `downloaded_roms`) | preservada (prefixo + `downloaded_roms`) |
| ROM do usuário em pasta externa, ainda presente | preservada | preservada (renova `lastIndexedAt`) |
| ROM do usuário em pasta externa, deletada | removida | **removida** (limpeza legada mantida) |

## Validação

- `:retrograde-app-shared:compileDebugKotlin` — **BUILD SUCCESSFUL** (kapt do Room validou a `@Query`).
- `:lemuroid-app:compileFreeBundleDebugKotlin` — **BUILD SUCCESSFUL** (kapt do Dagger validou a nova
  dependência `DirectoriesManager`).
- Warnings restantes são pré-existentes (opt-in de coroutines, deprecations, unchecked casts).

## Lição

Em um fork catálogo-first onde o `games` é **autoritativo** (populado por manifest/prebuilt DB +
download sob demanda), o scan de filesystem deve ser **aditivo + metadata-refresh**, nunca
destrutivo para o catálogo. Deletar linha "porque o arquivo não está no disco" é incompatível com
placeholders só-no-DB. Este é o mesmo mecanismo que já havia exigido o guard `downloaded_roms` — o
fix agora cobre também os placeholders. (Ver CLAUDE.md, Pitfalls de Android / Room, item 5.)

## Nota lateral (fora do escopo, mesmo mecanismo)

`removeDeletedDataFiles` ainda apaga `datafiles` por `lastIndexedAt`. Placeholders não têm datafiles
(sem impacto no catálogo), mas jogos multi-disco **baixados** poderiam perder refs de datafile se
uma pasta externa estiver configurada e a pasta interna não for escaneada. Endurecer isso fica como
melhoria futura, se necessário.
