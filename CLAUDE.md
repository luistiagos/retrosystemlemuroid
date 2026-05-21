# Lemuroid — Guia de Desenvolvimento

Emulador Android multi-sistema baseado em Libretro. Este arquivo documenta a arquitetura interna, decisões de design e features implementadas para auxiliar futuras sessões de desenvolvimento.

---

## Estrutura do Projeto

| Módulo | Responsabilidade |
|--------|-----------------|
| `retrograde-app-shared` | Entidades Room, DAOs, lógica de biblioteca, catálogo, filtros de sistema |
| `lemuroid-app` | UI Compose (mobile + TV), ViewModels, Workers, injeção de dependência |
| `retrograde-libretro` | Bridge JNI com LibretroDroid |

---

## Banco de Dados (Room)

**Versão atual: 23**

Entidade principal: `Game` — representa um jogo no catálogo (ROM local ou placeholder 0-byte para download sob demanda).

### Histórico de Migrações Relevantes

| Versão | Mudança |
|--------|---------|
| 9→10 | Tabela `downloaded_roms` |
| 18→19 | Índice composto `(isFavorite, lastPlayedAt)` na tabela `games` |
| **19→20** | **Tabela `save_queue`** (fila de downloads persistente) |
| **20→21** | **Coluna `popularityIndex INTEGER NOT NULL DEFAULT 0` + índice em `games`** |
| **21→22** | **Índices compostos `(systemId, popularityIndex)` e `(isFavorite, title)`** |
| **22→23** | **Coluna `isRepresentative INTEGER NOT NULL DEFAULT 1` + índice composto `(systemId, isRepresentative, popularityIndex)`** |

Migrações em: [Migrations.kt](retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/db/dao/Migrations.kt)
Registro em: [LemuroidApplicationModule.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/LemuroidApplicationModule.kt)

> **Nota Room**: o arquivo `schemas/…/23.json` é gerado automaticamente pelo kapt no primeiro build após adicionar a nova versão. Nunca editar schemas manualmente.

### Tuning PRAGMA do SQLite

Aplicado no `onOpen` da `RoomDatabase.Callback` em `LemuroidApplicationModule.retrogradeDb()`:

| PRAGMA | Valor | Por quê |
|--------|-------|---------|
| `journal_mode` | WAL | Reads concorrentes + writes sem bloquear leitores (configurado via `setJournalMode`). |
| `synchronous` | NORMAL | ~2x writes mais rápidos; com WAL não há risco de corrupção, só de perder a última transação não-flushed. |
| `cache_size` | -32000 (32 MB) | Page cache em RAM para reduzir I/O em queries repetidas. |
| `mmap_size` | 256 MB | Memory-mapped I/O — SQLite lê páginas direto do mmap, sem cópia. Lazy. |
| `temp_store` | MEMORY | Temp tables e sort buffers em RAM. |

> ⚠️ **Pitfall crítico (Android 14+)**: `SupportSQLiteDatabase.execSQL("PRAGMA … = valor")` lança `SQLiteException: Queries can be performed using SQLiteDatabase query or rawQuery methods only.` em API 34+. Várias PRAGMAs (`synchronous`, `cache_size`, `mmap_size`, `temp_store`, ...) retornam o novo valor como resultset após o set, e `execSQL` rejeita statements com resultset. **Sempre use `db.query("PRAGMA …").use { /* discard cursor */ }`** dentro de try/catch — uma exception em `onOpen` propaga até `getWritableDatabase` e crasha o app antes da MainActivity.

---

## Catálogo de Jogos (`catalog_manifest.txt`)

Arquivo de assets com uma linha por jogo, formato pipe-delimitado:

```
system/filename.ext|título|https://cover-url.png|popularityIndex|isRepresentative
```

- **Campo 4 (`popularityIndex`)**: inteiro positivo. Valores maiores = mais popular. `0` significa sem dados de popularidade. Varia tipicamente de 1 a ~1000.
- **Campo 5 (`isRepresentative`)**: `1` se este ROM é o representante do seu grupo `(systemId, title-limpo)`; `0` se é variante escondida do catálogo. Default `1` quando ausente (compatibilidade com manifests antigos).
- Sistemas usam aliases no manifest (`a26` → `atari2600`, `megadrive` → `md`, `colecovision` → `coleco`, `virtualboy` → `vb`, etc.). O mapa folder→dbname vive em **`assets/manifest_alias.json`** — fonte única lida tanto pelo `ManifestQuickLoader.loadManifestAlias()` (runtime) quanto pelo `PrebuiltDbGenerator` (build-time). Nunca duplicar como constante Kotlin: a duplicação já causou um bug de drift (Coleco/VB sumiram da lista por o prebuilt-db gravar `systemId` sem alias).

### Geração do Campo 5 (`isRepresentative`)

Computado **offline** pelo script Python `E:\fetchimagers\cleantitles\clean_titles.py`:

1. Limpa os títulos (remove `(USA)`, `(Rev 1)`, `[!]`, etc.)
2. Agrupa por `(systemId, título-limpo)`
3. Para cada grupo: escolhe a variante de maior `popularityIndex` (tiebreak: menor `fileName` ASC) — recebe `1`. Demais variantes recebem `0`.
4. Grava o novo manifest com 5º campo.

Isso elimina toda a lógica de agrupamento em runtime: o app só lê o campo e marca a coluna `isRepresentative` no DB.

### Pipeline de Carga

1. **`CatalogCoverProvider`** — lê e parseia `catalog_manifest.txt` em `Map<String, ManifestEntry>` (lazy, uma vez por processo).
2. **`ManifestQuickLoader.load()`** — roda no startup via `MainProcessInitializer` (50 ms após a app iniciar):
   - **URI rewrite**: substitui o prefixo sentinela `file:///lemuroid_prebuilt` pelo `romsDir` real em uma única SQL UPDATE (~100ms). No-op se o DB não veio do asset.
   - **Fast-path**: se `gameDao().countAll() >= manifest.size * 0.9`, marca prefs e pula tudo. Caso típico em devices que abriram com o asset pre-built ou já rodaram o load antes.
   - **Skip por versão+schema**: só pula se o `versionCode` do app E o `MANIFEST_SCHEMA_VERSION` salvos batem com os atuais. Bumpar `MANIFEST_SCHEMA_VERSION` força um reload one-time em todos os usuários (usado quando o formato do manifest muda).
   - `INSERT OR IGNORE` de todos os `Game` no banco (não sobrescreve dados enriquecidos pelo LibretroDB).
   - **Batch UPDATE de `popularityIndex` + `isRepresentative`** via `updateManifestFields()` em transação para jogos que já existiam (sincroniza mudanças no manifest após app update / schema bump).
   - Salva `versionCode` e `MANIFEST_SCHEMA_VERSION` em SharedPreferences.

### Prebuilt DB Asset (`retrograde-prebuilt.db`)

Para eliminar a tela "preparando ambiente" no primeiro startup pós-instalação (~5-15s), o APK contém um SQLite pre-populado em `assets/retrograde-prebuilt.db` com todos os ~29k games + FTS index já indexado.

**Geração no build (Gradle task `generatePrebuiltDb`):**

1. Lê `schemas/23.json` (Room schema source of truth) para obter:
   - `identityHash` (Room valida isso na abertura — se não bater, falha)
   - DDL exato de cada `@Entity` (tables + indices)
2. Lê `catalog_manifest.txt` (5 campos)
3. Cria SQLite via sqlite-jdbc (Kotlin, sem dependência Android)
4. Aplica schema das entities + FTS4 (CREATE VIRTUAL TABLE + triggers)
5. Cria `room_master_table` com `id=42, identity_hash=<do JSON>`
6. Bulk INSERT de todos os games com `fileUri = "file:///lemuroid_prebuilt/<systemId>/<fileName>"` (placeholder — o app reescreve no primeiro boot)
7. Bulk populate `INSERT INTO fts_games SELECT id, title FROM games` (1 statement em vez de 29k inserts com tokenization individual)
8. Cria trigger `games_ai` DEPOIS dos inserts (para que futuros inserts no Android disparem normalmente)
9. **Build-time validation**: re-verifica que `user_version`, `identity_hash`, contagens de `games` e `fts_games`, tabelas e triggers existem. Falha o build se algo divergir.

**Implementação:** [PrebuiltDbGenerator.kt](buildSrc/src/main/kotlin/PrebuiltDbGenerator.kt) + registro em [lemuroid-app/build.gradle.kts](lemuroid-app/build.gradle.kts) na task `generatePrebuiltDb` (dep de `mergeAssets` e `packageAssets` via `androidComponents.onVariants`).

**Runtime:**

- `Room.databaseBuilder(...).createFromAsset("retrograde-prebuilt.db")` em [LemuroidApplicationModule.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/LemuroidApplicationModule.kt). Room só consulta o asset quando o DB on-disk ainda não existe.
- **Helper `buildRoomBuilder(useAsset)`**: encapsula a configuração do Room — flip de flag permite buildar versão "sem asset" para diagnóstico.
- **Pre-warm em background Thread** após `build()`: força o `openHelper.writableDatabase` para que a primeira query foreground não pague o custo de copy + validation. Exceções aqui são logadas via Timber mas não derrubam o app.
- **URI rewrite** acontece no início do `ManifestQuickLoader.load()`: `UPDATE games SET fileUri = :realPrefix || SUBSTR(fileUri, LENGTH(:sentinel) + 1) WHERE fileUri LIKE :sentinel || '%'`. Wrapped em try/catch defensivo.

**Custo:** APK debug = 54MB, release (R8) = 29MB. O DB pre-built bruto é 17.6MB, mas comprime bem dentro do APK.

**Resultado medido:** `Room DB pre-warm OK in 400 ms` (vs. 5-15s do load via INSERT antes). Home abre direto com seção "Descubra" populada — tela "preparando ambiente" eliminada.

### Pitfalls do `createFromAsset` — Lições Aprendidas

Bugs descobertos durante a implementação que causaram crash no primeiro boot. **Resolvidos** mas registrados aqui para evitar regressão:

1. **`Callback.onCreate` ainda dispara quando Room copia o asset** — Room trata o DB recém-copiado como "criado" e chama todos os `RoomDatabase.Callback.onCreate()` registrados. O `GameSearchDao.CALLBACK.onCreate` chamava `MIGRATION.migrate(db)` que executava `CREATE VIRTUAL TABLE fts_games USING FTS4(...)` sem `IF NOT EXISTS`. Como o asset já contém `fts_games`, SQLite lançava `table fts_games already exists` → crash.
   - **Fix em [GameSearchDao.kt](retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/db/dao/GameSearchDao.kt):** guard verificando `sqlite_master` antes de chamar a migration + `CREATE … IF NOT EXISTS` em todos os DDLs da migration.
   - **Regra geral:** qualquer DDL dentro de `RoomDatabase.Callback.onCreate` DEVE ser idempotente.

2. **`db.execSQL("PRAGMA … = valor")` quebra em Android 14+** — ver nota na seção "Tuning PRAGMA" acima.
   - **Fix:** trocar `db.execSQL` por `db.query(...).use { }` para todas as PRAGMAs no `onOpen` callback.

3. **`fileUri` sentinela vs. real** — o build não conhece o `romsDir` do device, então o asset usa `file:///lemuroid_prebuilt/<systemId>/<fileName>`. Sem rewrite, lookups por fileUri quebrariam. Solução: uma única SQL UPDATE no boot (~100ms para 30k rows). Idempotente: roda toda vez mas só afeta rows que ainda têm o prefixo sentinela.

### `MANIFEST_SCHEMA_VERSION`

Constante em `ManifestQuickLoader` que tracking de versão do **formato** do manifest, independente do `versionCode` do app:

| Versão | Mudança |
|--------|---------|
| v1 | 4 campos: `path \| title \| coverUrl \| popularityIndex` |
| v2 | + 5º campo `isRepresentative` (agrupamento pré-computado) |

> **Sem placeholders em disco**: o check `isGamePlaceholder` em `GameInteractor` usa `File.length() == 0L`, que retorna `0` para arquivos **inexistentes** (comportamento garantido pela JVM). Portanto não é necessário criar arquivos 0-byte — o diálogo de download é disparado corretamente sem eles.

---

## Feature: Ordenação do Catálogo por Popularidade

**Arquivos envolvidos:**
- [GamesViewModel.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/games/GamesViewModel.kt)
- [GamesScreen.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/games/GamesScreen.kt)
- [GameDao.kt](retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/db/dao/GameDao.kt)

### Comportamento

- **Padrão**: jogos listados por `popularityIndex DESC` (mais populares primeiro), com jogos baixados sempre no topo.
- **Header da tela de catálogo**: dois `FilterChip` — **Popularidade** e **A-Z** — permitem trocar a ordenação instantaneamente.
- A troca de ordenação não recarrega a Activity; o `MutableStateFlow<GameSortOrder>` faz `flatMapLatest` trocar a query paginada.

### Enumeração

```kotlin
enum class GameSortOrder { POPULARITY, ALPHABETICAL }
```

### Queries SQL (GameDao)

> **Nota**: O `GamesViewModel` usa as queries **agrupadas** (`selectGrouped*`) desde que a feature de variantes foi implementada. As queries originais abaixo permanecem no DAO mas não são mais chamadas pelo catálogo por sistema.

| Método | Ordenação |
|--------|-----------|
| `selectBySystemSortedByPopularity(systemId)` | downloaded DESC, popularityIndex DESC, title ASC |
| `selectBySystemsSortedByPopularity(systemIds)` | idem, multi-sistema |
| `selectBySystem(systemId)` | downloaded DESC, title ASC (legado / A-Z) |
| `selectBySystems(systemIds)` | idem, multi-sistema |

---

## Feature: Agrupamento de Variantes (Grupos de ROMs)

**Arquivos envolvidos:**
- [GameDao.kt](retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/db/dao/GameDao.kt)
- [GamesViewModel.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/games/GamesViewModel.kt)
- [GamesScreen.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/games/GamesScreen.kt)
- [LemuroidGameListRow.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/shared/compose/ui/LemuroidGameListRow.kt)
- [MainViewModel.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/main/MainViewModel.kt)
- [MainActivity.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/main/MainActivity.kt)
- [GameVariantsModal.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/main/GameVariantsModal.kt)

### Comportamento

Jogos com o mesmo `title` no mesmo `systemId` são agrupados — apenas **um representante por título** aparece na lista do catálogo. O representante é **pré-computado offline** pelo script Python e marcado no `catalog_manifest.txt` (campo 5 = `1`); as variantes recebem `0`.

- **Badge**: ícone `ContentCopy` (20 dp) na linha indica que há múltiplas variantes.
- **Tap**: abre `GameVariantsModal` (BottomSheet) listando todos os ROMs do grupo pelo `fileName` sem extensão, com a cover do jogo representante.
- **Escolha de variante**: se já baixada → `gameInteractor.onGamePlay(variant)` direto; se placeholder → `pendingDownloadGame = variant` (AlertDialog de confirmação → enqueue → SaveQueueModal).

### Chave Composta

`"systemId/title"` — garante que "Tetris" no NES e "Tetris" no Game Boy são grupos independentes.

### Coluna `isRepresentative`

Coluna `INTEGER NOT NULL DEFAULT 1` na tabela `games`. Populada a partir do 5º campo do manifest.

- **`true` (1)**: aparece no catálogo agrupado.
- **`false` (0)**: variante escondida — ainda existe no DB e aparece no `GameVariantsModal`, mas o `selectGrouped*` filtra fora.

ROMs importadas manualmente (via LibretroDB scan) não passam pelo manifest e recebem `true` por default no construtor de `Game` — então aparecem individualmente, como sempre.

### Queries SQL (GameDao) — Agrupadas

Antes do `isRepresentative`, as queries agrupadas usavam subquery correlacionada com `MAX(popularityIndex * 10000000 - id)` — esse caminho era O(n²) no pior caso e foi identificado como gargalo crítico em devices weak. Substituído por um filtro trivial `WHERE isRepresentative = 1`, otimizado pelo índice composto `(systemId, isRepresentative, popularityIndex)`.

| Método | Descrição |
|--------|-----------|
| `selectGroupedBySystemSortedByPopularity(systemId)` | `WHERE systemId=? AND isRepresentative=1`, ordem: downloaded DESC, popularityIndex DESC, title ASC |
| `selectGroupedBySystemsSortedByPopularity(systemIds)` | idem, multi-sistema |
| `selectGroupedBySystem(systemId)` | `WHERE systemId=? AND isRepresentative=1`, ordem: downloaded DESC, title ASC |
| `selectGroupedBySystems(systemIds)` | idem, multi-sistema |
| `selectVariantsByTitle(systemId, title)` | Todos os ROMs do grupo — usado pelo modal |
| `selectAllCompositeKeysWithVariants()` | Chaves `"systemId/title"` com `COUNT(*) > 1` — alimenta o badge |

### `titlesWithVariants` no MainViewModel

```kotlin
val titlesWithVariants: StateFlow<Set<String>> =
    retrogradeDb.gameDao()
        .selectAllCompositeKeysWithVariants()
        .map { it.toHashSet() as Set<String> }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptySet())
```

Mantido no `MainViewModel` (não no `GamesViewModel`) para que o `onGameClick` da `MainActivity` sirva a **todas** as telas (Home, Favoritos, Busca, Sistemas) sem acoplamento por tela.

### Roteamento do Tap (`onGameClick`)

```kotlin
val variantKey = "${game.systemId}/${game.title}"
when {
    variantKey in titlesWithVariants -> pendingVariantsGame.value = game
    !isGamePlaceholder(game)        -> gameInteractor.onGamePlay(game)
    else                            -> pendingDownloadGame.value = game
}
```

### Comportamentos Conhecidos

- O badge `ContentCopy` só é exibido na tela de catálogo por sistema (`GamesScreen`). Em Favoritos e Busca o badge não aparece, mas o tap abre o modal corretamente.
- Long-press em um jogo agrupado abre o menu de contexto agindo sobre o **representante** — não navega pelo modal de variantes.
- Script Python de limpeza de títulos: `E:\fetchimagers\cleantitles\clean_titles.py` gera `catalog_manifest_clean.txt` com títulos sem região/Rev/Disc/data, ordenados alfabeticamente por sistema, e adiciona o 5º campo `isRepresentative` (1=rep, 0=variante).

---

## Feature: Seção "Descubra" na Home com Jogos Populares

**Arquivos envolvidos:**
- [HomeViewModel.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/home/HomeViewModel.kt)
- [GameDao.kt](retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/db/dao/GameDao.kt)

### Comportamento

A seção **Descubra** exibe até 10 jogos populares com cover, um por sistema, escolhidos aleatoriamente a cada carregamento.

**Algoritmo (`HomeViewModel.discoveryGames`):**
1. Consulta um pool de até 300 jogos com `coverFrontUrl IS NOT NULL AND popularityIndex > 0`, ordenados por `popularityIndex DESC`, excluindo sistemas bloqueados pelo filtro de RAM do dispositivo.
2. Agrupa por `systemId`.
3. Para cada sistema, pega os top-10 mais populares do pool e sorteia 1 aleatório.
4. Embaralha os representantes de cada sistema.
5. Limita a `CAROUSEL_MAX_ITEMS = 10`.

**Resultado**: sempre mostra jogos conhecidos/populares com imagem, com variedade entre sistemas, e nunca exibe sistemas que o dispositivo não suporta (ex.: PSP/3DS em dispositivos ≤ 2 GB de RAM).

### Queries SQL (GameDao)

| Método | Quando usar |
|--------|-------------|
| `selectTopPopularWithCovers(limit)` | Dispositivos NORMAL (sem restrição de sistemas) |
| `selectTopPopularWithCoversExcluding(limit, excludedSystemIds)` | Dispositivos WEAK / ULTRA_WEAK |

---

## Filtro de Sistemas por RAM (`HeavySystemFilter`)

| Tier | RAM | Sistemas ocultos |
|------|-----|-----------------|
| `NORMAL` | > 2 GB | nenhum |
| `WEAK` | 1–2 GB | PSP, 3DS |
| `ULTRA_WEAK` | ≤ 1 GB | PSP, 3DS, NDS, N64, PSX, DOS, Sega CD |

O `excludedDbNames` é calculado uma vez no `init` do `HomeViewModel` e repassado para todas as queries que precisam de filtragem (recentes, favoritos, descubra).

---

## Download sob Demanda (`RomOnDemandManager`)

**Arquivo:** [RomOnDemandManager.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/RomOnDemandManager.kt)

Jogos no catálogo são placeholders 0-byte até o usuário tocar neles. O fluxo é:

1. `GameInteractor.onGamePlay()` detecta arquivo 0-byte via `isGamePlaceholder(game)` e chama o `onPlaceholderGame` callback (configurado na MainActivity).
2. `downloadRom()` consulta o endpoint `find_by_file` (pythonanywhere) para obter a URL real, com fallback ao HuggingFace.
3. Download via OkHttp com pause/resume e cancelamento limpo.
4. Após o download, registra em `DownloadedRomDao` e dispara `triggerCatalogQuickLoad`.

### Robustez do Download

- **Cancelamento**: `cancelActiveDownload()` chama `call.cancel()`. O catch de `IOException` verifica `call.isCanceled()` e relança como `CancellationException` para evitar retry indevido.
- **`PermanentHttpException`**: erros HTTP 4xx (exceto 429) são marcados como permanentes e não são retentados. Inclui espaço insuficiente.
- **Verificação de espaço**: antes de escrever, compara `contentLength` com `usableSpace` do diretório de destino e falha rapidamente com mensagem amigável se não houver espaço.
- **Detecção ENOSPC**: `isNoSpaceError(e)` percorre a cadeia de `cause` procurando "ENOSPC" ou "No space left" — detecta a falha mesmo quando encapsulada.
- **Retry com backoff**: até 5 tentativas em erros de rede (IO), 3 tentativas no lookup; respeita o header `Retry-After` em respostas 429.
- **Suporte a archive.org**: `ensureArchiveSession()` faz login via POST em `archive.org/account/login` e armazena os cookies em `ArchiveCookieJar` (in-memory). CDN valida os mesmos cookies no redirect.

### Mapeamento de Sistemas

`RomSystemMapper.toEndpointSystem(systemId)` converte o `systemId` interno para o nome usado pelo endpoint pythonanywhere.

---

## Fila de Downloads (`SaveQueueManager`)

**Arquivos envolvidos:**
- [SaveQueueManager.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/SaveQueueManager.kt)
- [SaveQueueDao.kt](retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/db/dao/SaveQueueDao.kt)
- [SaveQueueItem.kt](retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/db/entity/SaveQueueItem.kt)
- [SaveQueueViewModel.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/main/SaveQueueViewModel.kt)
- [SaveQueueModal.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/main/SaveQueueModal.kt)

### Comportamento

Permite enfileirar múltiplos downloads; apenas **um** jogo baixa de cada vez — os demais ficam como `QUEUED`.

**Estados (`SaveQueueState`):** `QUEUED` → `SAVING` → `SAVED` (removido após 3 s) ou `ERROR`. Pode ser pausado (`PAUSED`) e retomado.

**Persistência:** a tabela `save_queue` (migração 19→20) garante que a fila sobreviva a restarts da app. Ao iniciar, itens `SAVING` são revertidos para `QUEUED` e o processador é relançado automaticamente.

**Fila de processamento:** coroutine `processQueue()` roda em loop até que não haja mais itens `QUEUED`. `ensureProcessorRunning()` evita múltiplos processadores simultâneos.

**Sinal de conclusão:** `justCompleted: SharedFlow<Game>` emite o jogo assim que o download termina; a MainActivity observa e pode lançar o jogo automaticamente.

### Integração na TopBar

[MainTopBar.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/main/MainTopBar.kt) recebe `hasSaveQueueActive` e `saveQueueProgress`:

- Quando há itens ativos: ícone `Download` com `BadgedBox` + `CircularProgressIndicator` (10 dp) na action bar.
- `LinearProgressIndicator` determinístico abaixo da TopBar (visível somente quando não há outra operação em progresso).
- Toque no ícone abre o `SaveQueueModal` (BottomSheet) com a lista de jogos em espera.

### SaveQueueModal

`ModalBottomSheet` com `LazyColumn` de `SaveQueueItemRow`. Cada linha exibe:
- Cover (56×72 dp), título, estado em texto.
- Botão Pause/Resume para o download ativo.
- Botão Cancel (X) para todos os estados exceto SAVED e ERROR.
- `LinearProgressIndicator` determinístico (ou indeterminístico enquanto `progress == 0`).

---

## Catálogo Embedded / Placeholders (`StreamingRomsManager`)

**Arquivo:** [StreamingRomsManager.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/StreamingRomsManager.kt)

### Propósito

Cria os arquivos 0-byte placeholder para todos os jogos do catálogo, permitindo que apareçam no banco sem precisar de acesso à rede. Funciona em dois modos:

1. **Catálogo embedded** (modo atual): lê `catalog_manifest.txt` dos assets e cria os placeholders diretamente em disco. Nenhuma requisição de rede.
2. **HuggingFace API** (fallback legado): lista arquivos via tree API e faz download real de cada um.

### `CATALOG_VERSION`

Constante que força re-population quando o manifest ganhou novos sistemas/entradas:

| Versão | Motivo |
|--------|--------|
| 5→6 | PSP adicionado após fix no HeavySystemFilter |
| 6→7 | Fix no SerialScanner; re-indexar .iso PSP |
| 8→9 | Fallback de extensão archive para megacd/ngp/ngc/psp |
| 9→10 | Fix no formato do manifest — pipe ilegal em nomes de arquivo Android |

Ao detectar versão antiga, reseta `PREF_DOWNLOAD_DONE` e reenfileira o `StreamingRomsWork`. Arquivos existentes nunca são deletados.

### Startup

`MainProcessInitializer` chama `StreamingRomsManager.markCatalogPopulated(context)` **sincronamente** antes de qualquer coisa, gravando `PREF_DOWNLOAD_DONE = true` e `PREF_CATALOG_VERSION = CATALOG_VERSION`. Isso impede que o init background do `StreamingRomsManager` enfileira o trabalho de download mesmo num primeiro lançamento pós-instalação.

### SharedPreferences (`streaming_roms_prefs`)

| Chave | Significado |
|-------|-------------|
| `streaming_download_done` | Catálogo 100% populado |
| `streaming_download_started` | Download iniciado pelo usuário |
| `streaming_download_paused` | Pause explícito do usuário |
| `streaming_catalog_version` | Versão do catálogo já processada |
| `wifi_only_download` | Restringir download à Wi-Fi |

---

## Convenções de Código

- ViewModels usam `MutableStateFlow` + `combine`/`flatMapLatest` para reatividade.
- Telas Compose recebem apenas lambdas e estado imutável — nenhuma referência de ViewModel nas `@Composable` folhas.
- Queries Room paginadas retornam `PagingSource<Int, Game>`; queries de lista retornam `Flow<List<Game>>`.
- Migrações sempre em `Migrations.kt`, registradas em `LemuroidApplicationModule.kt`.
- `PermanentHttpException` sinaliza erros HTTP não-retriáveis (4xx exceto 429); capturado em `downloadToFile` antes do bloco geral de `IOException`.

---

## Pitfalls de Android / Room

Bugs reais que crasharam o app em produção. Cada um inclui o sintoma, a causa raiz, e a regra a seguir para não regredir.

### 1. PRAGMA + `db.execSQL` em Android 14+

**Sintoma:** `SQLiteException: unknown error (code 0 SQLITE_OK): Queries can be performed using SQLiteDatabase query or rawQuery methods only.` lançada em `RoomDatabase.Callback.onOpen`, propagando pelo `getWritableDatabase` e crashando o app antes da MainActivity.

**Causa:** Em API 34+, `SupportSQLiteDatabase.execSQL` rejeita statements que retornam resultset. Várias PRAGMAs (`synchronous = NORMAL`, `cache_size`, `mmap_size`, `temp_store`, ...) retornam o novo valor após o set.

**Regra:** PRAGMAs com `= valor` **sempre** via `db.query(pragma).use { }`, dentro de try/catch para não derrubar o boot. Nunca via `execSQL`.

### 2. `Callback.onCreate` dispara após `createFromAsset`

**Sintoma:** Crash no primeiro boot pós-instalação com `SQLITE_ERROR: table fts_games already exists`.

**Causa:** Room considera o DB resultante de `createFromAsset` como "criado" e invoca todos os `RoomDatabase.Callback.onCreate()`. Se algum callback contém DDL não-idempotente (`CREATE VIRTUAL TABLE`, `CREATE TRIGGER`, etc.) e o asset já tem esses objetos, o SQLite lança erro.

**Regra:** DDL dentro de `Callback.onCreate` **sempre** com `IF NOT EXISTS`. Ou guard verificando `sqlite_master` antes:
```kotlin
val exists = db.query("SELECT 1 FROM sqlite_master WHERE name = 'foo' LIMIT 1")
    .use { it.moveToFirst() }
if (!exists) { ... }
```

### 3. `fileUri` no asset pre-built

**Sintoma:** ROMs do catálogo ficariam apontando para `file:///lemuroid_prebuilt/...` (URI inválida no device).

**Causa:** O build-time generator não conhece o `romsDir` real do device (varia por package, debug suffix, etc.). Usa um prefixo sentinela.

**Regra:** No primeiro boot, `ManifestQuickLoader.load()` faz uma SQL UPDATE única substituindo o prefixo sentinela pelo prefixo real (~100ms para 30k rows). Idempotente: roda toda vez mas só afeta rows ainda com o prefixo sentinela.

### 4. `validation` build-time é crítica para `createFromAsset`

**Sintoma:** Sem validação, qualquer mismatch entre o schema do asset e o que Room espera resulta em crash no runtime (RoomOpenHelper rejects identityHash mismatch, schema drift, etc).

**Regra:** O `PrebuiltDbGenerator` (em buildSrc) re-abre o DB gerado e valida `user_version`, `identity_hash`, contagens, tabelas e triggers. Se algo divergir do esperado, a Gradle task falha. Nunca empacote um asset que não passou pela validação.
