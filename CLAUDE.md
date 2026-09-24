# Lemuroid — Guia de Desenvolvimento

Emulador Android multi-sistema baseado em Libretro. Este arquivo documenta a arquitetura interna, decisões de design e features implementadas para auxiliar futuras sessões de desenvolvimento.

---

## Workflow de Documentação (obrigatório)

Roteamento por prefixo do pedido do usuário. Cada item vira **um arquivo Markdown** na pasta indicada.

| Prefixo | Onde documentar | Ciclo de vida |
|---------|-----------------|---------------|
| `[BUG]` | `documentacao/bugs/open/` ao **iniciar**; mover para `documentacao/bugs/done/` ao **resolver** | criar em `open` no começo da investigação; ao terminar a correção, mover o arquivo para `done` (atualizando Status) |
| `[FEATURE]` | `documentacao/funcionalidades/` | documentar a funcionalidade implementada |
| `[BACKLOG]` | `documentacao/backlogs/` | registrar a ideia/tarefa para o futuro |

- Nome de arquivo: `YYYY-MM-DD-slug-curto.md` (ex.: `2026-07-01-catalogo-some-scan-biblioteca.md`).
- Bugs seguem o formato dos arquivos existentes em `bugs/done/`: título com prefixo `[BUG]`, e blocos **Data / Status / Severidade / Branch**, depois **Sintoma / Causa-raiz / Correção / Validação / Lição**.
- "Mover para done" = mover fisicamente o `.md` de `bugs/open/` para `bugs/done/` (não duplicar).

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

### Remoções do catálogo (`CatalogRemovals`)

Jogos que o usuário exclui do catálogo (menu de contexto do jogo ▸ **Excluir do catálogo**, nas
duas UIs — Compose no mobile, `GameContextMenuListener` na TV) têm a chave
`"systemId/fileName"` gravada em `CatalogRemovals` (SharedPreferences `catalog_removals_prefs`).
Só deletar a linha de `games` **não** é permanente: qualquer passada completa do
`ManifestQuickLoader` (troca de `versionCode`, bump de `MANIFEST_SCHEMA_VERSION`) reinsere tudo do
manifest e o jogo voltaria sozinho.

O `load()` pula essas chaves ao montar a lista `games` e desconta `removedKeys.size` do
`expectedSize` do fast-skip. **Restaurar catálogo** (mobile: Ajustes ▸ ROMs; TV: Ajustes ▸
Diversos) limpa a lista, chama `ManifestQuickLoader.forceReload(context)` e dispara o quick-load.

> ⚠️ `forceReload` grava a flag `force_full_reload` — **não** limpa `loaded_manifest_schema`.
> Zerar o schema faria `loadedSchema` virar `-1` e re-disparar todas as migrações one-time
> guardadas por `loadedSchema < N` (a reclassificação de arcade da v27 varre o manifest inteiro
> mexendo em arquivos). Ao mexer nos fast-paths do loader, manter os dois `if` guardados por
> `!forceFullReload`.

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

Constante em `ManifestQuickLoader` para controle de versão do **esquema/conteúdo** do manifest (para forçar re-processamento no banco de dados):

| Versão | Mudança |
|--------|---------|
| <= 23 | Estrutura antiga |
| 24 | Mapeamento inicial de arcade para subsistemas |
| 25 | Correção e re-execução da migração de arcade (mame2003plus + fbneo) |
| 26 | Limpeza e remoção de duplicatas no `catalog_manifest.txt` |
| 27 | Deleção no DB de jogos do catálogo obsoletos + re-execução segura da migração de arcade |
| 28 | SNES: +493 títulos do lote `.zip` (romsrepository source_id=1); 33 realinhados como `isRepresentative=0` |
| 29 | SNES: +7 títulos presentes no catálogo do retrobat e ausentes aqui (bloco `Dem*`) + correção `Super Mario World I` → `Super Mario World` + 300 capas e 151 popularidades no lote `.zip` da v28 |
| 30 | SNES: mais 66 capas no lote `.zip` da v28 via HfsDB/HfsPlay (credenciais do retrobat), somando 366/493 (74%); catálogo SNES em 91% de cobertura |
| 31 | Passe de capas no catálogo inteiro: +4.711 capas novas e 3.577 capas erradas removidas (ver "Passe de capas v31" abaixo) |

### Backfill de capas (`super_scrapper`)

O script `E:\fetchimagers\super_scrapper.py` preenche capas via IGDB e outros providers, mas **grava manifests de 4 campos** — rodá-lo direto no `catalog_manifest.txt` do Lemuroid apagaria o 5º campo (`isRepresentative`) de todas as linhas. O procedimento seguro é:

1. Extrair as linhas-alvo para um manifest temporário de 4 campos, guardando o mapa `path → isRepresentative`.
2. Rodar o `super_scrapper` nesse subset (`--csv ""`, `--limit N` em blocos — o manifest só é salvo no fim de cada bloco).
3. Fazer o merge de volta aplicando **apenas** os campos 3 (capa) e 4 (popularidade). Títulos propostos pelo IGDB **não** são aplicados: mudar título altera o agrupamento de variantes e a ordenação alfabética.

> ⚠️ **Capas-lixo**: o catálogo carregava o resíduo de um bug antigo do scraper (match sem comparar título) — uma única imagem chegou a ser capa de 196 títulos distintos. Limpo na v31, mas o scraper **continua** produzindo esses falsos positivos: no último lote o filtro descartou 64 URLs. Ao gravar ou reaproveitar capas, **sempre** descartar URLs compartilhadas por ≥ 3 títulos distintos, ou o dano volta.

### Passe de capas v31 (catálogo inteiro)

Duas fontes, nesta ordem — a barata e exata primeiro:

**1. libretro-thumbnails por nome exato** (3.987 capas). O nome do arquivo no manifest já é, na maioria dos sistemas, o nome No-Intro — que é exatamente o nome do boxart no repo. Monta-se a URL candidata e faz-se um `HEAD`: **200 significa que existe uma capa com aquele nome**, o que já prova ser o jogo certo. Não há fuzzy match, logo não há risco de capa trocada.

- Não usar a API do GitHub para listar o repo: são 60 req/h sem token e estoura na hora. O `HEAD` direto em `raw.githubusercontent.com` roda a ~70/s com 16 threads — 16 mil entradas em ~4 min.
- Repos usam underscore no nome (`Sega_-_32X`), não espaço.
- **Expansão de região é obrigatória**: o manifest usa `(US)`/`(JP)`/`(EU)`, o No-Intro usa `(USA)`/`(Japan)`/`(Europe)`. Só isso levou o msx2 de 0% → 68% e o gg de 0% → 38%.

**2. `super_scrapper` (IGDB/HfsDB/HfsPlay)** para o que sobrou (724 capas, ~20% de acerto). A taxa é baixa de propósito: o que chega nesta fase é justamente o que o libretro não tinha — protótipos, demos japonesas, unlicensed.

**Limpeza das capas erradas.** Detecção: uma URL que serve de capa para ≥ 3 títulos **distintos** não é capa de nenhum deles. A ação é **esvaziar**, nunca substituir por palpite — capa vazia cai no placeholder e permite um scrape futuro achar a certa; capa errada parece certa e nunca se corrige. As linhas esvaziadas foram re-scrapeadas e a maioria recuperou a arte correta (gba 66%, nds 73%, gg 68%, nes 63%).

> ⚠️ **A chave de contagem NÃO pode incluir o `systemId`**: um jogo multiplataforma (Casper no GB, GBC e PSX) compartilha capa legitimamente, e contá-lo como 3 jogos distintos apaga capa boa — na primeira tentativa isso inflou o alvo em ~800 linhas. Normalizar também pontuação e numeral romano (`Fun 'n Games` == `Fun n Games`).

**Sem solução por scraping** (~17 mil linhas): `zxspectrum` (10.356) e `atari800` (5.475) usam nomes TOSEC (`Game (1987)(Publisher)[a2].tap`) e são majoritariamente títulos caseiros de micro 8-bit; `pico8`, `arduboy`, `lowresnx`, `uzebox`, `vircon32` são fantasy consoles/homebrew que nenhum provider comercial cataloga.

> ⚠️ **Desenvolvimento e Fast-Path**: Durante o desenvolvimento, o `versionCode` do aplicativo debug permanece o mesmo (ex: 231). Caso o arquivo `catalog_manifest.txt` seja alterado, o aplicativo irá pular (fast-skip) a carga do manifesto nas próximas instalações sob o mesmo build porque os dados em SharedPreferences já estarão marcados como processados para aquela versão. **Sempre que alterar o manifesto ou a lógica de carregamento, você DEVE bumpar a constante `MANIFEST_SCHEMA_VERSION` em `ManifestQuickLoader.kt`** para forçar o recarregamento.
>
> 💡 **Limpeza de registros órfãos**: O processo de reload na versão 27 compara todas as URIs presentes no banco de dados que pertencem ao diretório de ROMs interno ou ao prefixo sentinela e deleta as que não estão presentes no novo `catalog_manifest.txt`. Isso garante que jogos removidos do manifesto (como duplicatas) sumam do banco imediatamente pós-upgrade, mantendo o banco sincronizado com o manifesto de assets.

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
| `WEAK` | 1–2 GB | PSP, 3DS, GameCube |
| `ULTRA_WEAK` | ≤ 1 GB | PSP, 3DS, GameCube, NDS, N64, DOS, Sega CD, Dreamcast, 3DO, Saturn, Amiga (x4), PC-FX, Atari ST |

O `excludedDbNames` é calculado uma vez no `init` do `HomeViewModel` e repassado para todas as queries que precisam de filtragem (recentes, favoritos, descubra).

> ⚠️ **PSX não está em nenhum tier** (removido em 2026-09-05). O PCSX-ReARMed roda
> aceitavelmente em set-top box de 1 GB, e a classificação `am.isLowRamDevice || totalGb <= 1.0`
> derruba em `ULTRA_WEAK` **qualquer** aparelho ≤ 2 GB com `ro.config.low_ram=true` — o padrão de
> TV box barata. Resultado: o catálogo inteiro de PlayStation sumia numa BTV de 2 GB.
>
> 💡 O filtro só existe na UI **mobile** (`MetaSystemsViewModel`). `TVHomeViewModel` **não**
> chama `HeavySystemFilter` — esconde apenas PSP e 3DS. A busca também não filtra por sistema.
> Portanto, sistema ausente na lista mas achado pela busca = filtro de RAM (e UI mobile);
> ausente também na busca = linhas fora do banco, outro problema.

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
- **Toast só por `Context.displayToast`** ([SafeToast.kt](retrograde-util/src/main/java/com/swordfish/lemuroid/common/SafeToast.kt)). `Toast.makeText(...).show()` direto é proibido — ver pitfall 7.
- **Intent implícita só por `startActivitySafely` / `launchSafely`** ([SafeIntents.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/utils/android/SafeIntents.kt)). `startActivity` cru vale só para Intent explícita — ver pitfall 9.
- **Activity com extra obrigatório: extra ausente → `finish(); return`, nunca `throw`.** O Robo test do Pre-Launch Report lança toda activity declarada no manifesto sem extras, mesmo as não-exportadas. `BaseGameActivity.onCreate` é `final` de propósito — o `return` do abort só sai do método da base, então código de subclasse vai em `onGameCreated()`, que só roda quando a inicialização chegou ao fim.
- **Mostrar Activity sobre a tela de bloqueio só por `setShowWhenLockedCompat`** ([ActivityUtils.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/utils/android/ActivityUtils.kt)). `setShowWhenLocked`/`setTurnScreenOn` crus são API 27 e crasham com `minSdk 21` — ver pitfall 12.
- **Rede só por `NetworkCompat`** ([NetworkCompat.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/utils/android/NetworkCompat.kt)). `getSystemService(Class)` e `ConnectivityManager.activeNetwork` são API 23 — ver pitfall 12.

### Estilo: ktlint com baseline

`./gradlew ktlintCheck` **passa** e volta a falhar em violação **nova**. O passivo (1.750 apontamentos, ~900 só no `main` de `lemuroid-app`) está congelado em `<modulo>/config/ktlint/baseline.xml`, configurado no [build.gradle.kts](build.gradle.kts) raiz junto com o `apply` do plugin. Regenerar com `./gradlew ktlintGenerateBaseline` — **depois de corrigir** algo, nunca para calar apontamento recém-criado.

Duas coisas medidas antes de escolher o baseline, para não repetir a tentativa:

1. **Não adianta trocar `ktlint_code_style`.** O código já está escrito no sabor `ktlint_official` (o default do ktlint 1.x quando o `.editorconfig` não diz outro). Medido no `main` de `lemuroid-app`: `ktlint_official` 902, `intellij_idea` **1.628** (a regra `function-signature` inverte de sentido — 124 → 803 — e o `continuation_indent_size=8` passa a valer, +360 de `indent`), `android_studio` **2.927** (proíbe trailing comma, que o código usa em todo lugar).
2. **Espaço em branco sobrando pode derrubar a task inteira de um módulo.** Um espaço depois de `filter {` em `StorageProviderRegistry.kt` fazia a regra `argument-list-wrapping` lançar `IllegalArgumentException: First node in sequence must be a whitespace containing a newline`, e o ktlint reportava isso como "failed to parse file" — mensagem que aponta para a direção errada. Se um módulo inteiro falhar com "failed to parse", rodar com `--stacktrace` e ler qual **regra** estourou.

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

### 5. Scan de biblioteca apaga o catálogo (games placeholder somem, sobra só o baixado)

**Sintoma:** Após algum tempo, todos os jogos **não-baixados** desaparecem do catálogo; sobram apenas os que foram baixados.

**Causa:** `LemuroidLibrary.indexLibrary()` (rodado pela `LibraryIndexWork`) faz um scan do filesystem: para cada arquivo **presente no disco** atualiza `lastIndexedAt`; no `cleanUp()` chama `removeDeletedGames()` → `deleteByLastIndexedAtLessThan(startedAtMs)`, que **apaga todo game cujo `lastIndexedAt` não foi renovado** (interpretado como "arquivo removido do disco"). Mas neste fork os games do catálogo são **placeholders só-no-DB, sem arquivo em disco** (ver "Sem placeholders em disco"). O scan nunca os encontra → `lastIndexedAt` fica antigo → são apagados. O guard `NOT EXISTS downloaded_roms` (fix parcial anterior) só protege os baixados — por isso sobram apenas eles.

**Gatilho:** `MediaMountedReceiver` em `ACTION_MEDIA_MOUNTED` (remontagem de storage) chama `scheduleManualLibrarySync`; também qualquer rescan de settings / troca de pasta. Daí o "após um certo período de tempo".

**Regra:** O scan deve ser **aditivo + metadata-refresh** para o catálogo, nunca destrutivo. `deleteByLastIndexedAtLessThan` recebe `romsPrefix` (URI da pasta de ROMs gerenciada) e `sentinelPrefix` (`file:///lemuroid_prebuilt`) e **nunca** apaga games sob esses prefixos — todo o catálogo + downloads vivem sob `romsPrefix`. Só ROMs importadas pelo usuário fora da pasta gerenciada (pasta externa / SAF) que sumiram do disco são removidas. Comparação por `SUBSTR` (não `LIKE`, para não quebrar com `_`/`%` no path); prefixo vazio protege tudo. Prefixo passado por `LemuroidLibrary.romsUriPrefix` (via `DirectoriesManager`).

### 6. O `.so` de Flycast (Dreamcast) empacotado tem que ser o do buildbot **e** patchado

Este espaço já produziu dois bugs de produção — ambos de empacotamento, ambos invisíveis até um aparelho crashar em código nativo.

**6a — sem `libandroid.so` no DT_NEEDED (2026-07-07).** Todo jogo de Dreamcast crasha com SIGSEGV ~1–2 s após o boot. O `.so` do buildbot libretro não linka `libandroid.so`; o símbolo weak `ASharedMemory_create` fica nulo → fallback `open("/dev/ashmem")` → EACCES com targetSdk ≥ 29 (o app usa 35) → fastmem desliga (`[VMEM] ... errno 13` no logcat) → o caminho fallback do dynarec trunca o pointer tag (`0xb4…`) do Android 11+ → SIGSEGV. Corrigido por `python patch_flycast_libandroid.py` (raiz do repo, requer `pip install lief`). Detalhes em `documentacao/bugs/done/2026-07-07-dreamcast-crash-boot-ashmem-libandroid.md`.

**6b — core compilado à mão no lugar do buildbot (2026-09-02).** O `.so` de **arm64-v8a** (só essa ABI) tinha virado o build local de `E:/projects/lemuroid/flycast_src` — o fork antigo `libretro/flycast`, sem o patch. Resultado: SIGTRAP `TRAP_BRKPT` na `GLThread` (`os_DebugBreak` do handler de sinal do dynarec) em **todo** aparelho arm64, por seis semanas. Detalhes em `documentacao/bugs/done/2026-09-02-flycast-arm64-core-hand-build-sigtrap.md`.

**Regra:** rodar o script continua valendo, mas **a instrução em documento não é o guard** — ela já falhou uma vez. O guard é a task Gradle `verifyFlycastCore` ([FlycastCoreVerifier.kt](buildSrc/src/main/kotlin/FlycastCoreVerifier.kt)), pendurada em `merge*NativeLibs` / `package*` / `bundle*`: o build **falha** se algum `.so` de Flycast (2 módulos × 4 ABIs) não listar `libandroid.so` no DT_NEEDED, ou se embutir um caminho de fonte absoluto (marca de core compilado à mão — os binários do buildbot não embutem nenhum). Se a task acusar, **não desabilite**: restaure o binário do buildbot e rode o patch.

### 7. `Toast` mata o processo no Android 7.1 — e o público-alvo é exatamente esse aparelho

**Sintoma:** Em TV box (MXQ 4K Pro e clones), **todo** jogo de **todo** sistema termina na tela de crash com `Unable to add window -- token android.os.BinderProxy@… is not valid; is your activity running?`.

**Causa:** No 7.1 (API 25) o `NotificationManagerService` expira o token da janela do toast por tempo (2 s / 3,5 s), mas quem monta a janela é o app — `Toast$TN.handleShow` roda numa mensagem da main thread. Main thread ocupada (o boot do jogo faz `dlopen` do core nela) = token vencido quando o `addView` finalmente roda = `BadTokenException`. O `try/catch` do framework só existe a partir da API 26.

**Regras:**
1. **Nunca** `Toast.makeText(...).show()` direto. Use `Context.displayToast` — ele monta o toast sobre um `ContextWrapper` cujo `WindowManager` engole a `BadTokenException`.
2. **Nunca guarde por `SDK_INT`** ao lidar com bug de framework antigo: essas boxes anunciam Android 9/11 rodando 7.1 de verdade. Guard por versão deixa de fora justamente o aparelho afetado.
3. Não enfileire toast/diálogo durante o boot do jogo. Se o aviso é sobre o jogo, espere `waitGLEvent<FrameRendered>()` — precedente em `GameViewModelInput.initializeControllerConfigsFlow`.
4. `minSdkVersion` é **21** e a build armeabi-v7a é distribuída para TV box velha: qualquer API nova precisa de guard, e qualquer bug conhecido de Android 5–7 é bug nosso na prática.

Detalhes em `documentacao/bugs/open/2026-08-16-tvbox-mxq-crash-toast-badtoken.md`.

### 8. A tela de crash não pode acusar o núcleo por qualquer exceção

**Sintoma:** Usuário limpa cache e faz reset de fábrica atrás de um bug de UI, porque a tela dizia "o núcleo do Libretro teve um problema… tente formatação de fábrica".

**Causa:** `RESULT_UNEXPECTED_ERROR` (qualquer exceção Java não tratada no processo `:game`) mostrava sempre `lemuroid_crash_disclamer` e ainda relançava o jogo com cada core restante — repetindo o mesmo crash 2-3 vezes.

**Regra:** Crash de core é **SIGSEGV**: não desenrola pela JVM e só é visto na sessão seguinte por `ApplicationExitInfo`. Portanto exceção Java que chega ao `UncaughtExceptionHandler` é bug de app até prova em contrário — `BaseGameActivity.isEmulatorFailure` só devolve `true` com frame de `com.swordfish.libretrodroid` ou `OutOfMemoryError`. Quando é `false`: sem fallback de core e mensagem `lemuroid_app_error_disclamer`.

**Terceira categoria (2026-09-03): núcleo travado.** `GLRetroView.GLThreadTimeoutException` é lançada de dentro do `runOnGLThread` — casaria o teste por pacote e viraria "crash de core", com conselho de formatação de fábrica e fallback que só paga outro timeout de 30 s. Mas o disclaimer de app também mente ali ("o problema não é… nem do núcleo de emulação"). Por isso `isCoreStall` é testado **antes** dos outros dois, `isEmulatorFailure` devolve `false` para ela **antes** do teste por pacote, e a tela usa `lemuroid_core_stalled_disclamer`, sem fallback. Ao adicionar qualquer exceção nossa lançada de dentro do pacote `libretrodroid`, decidir explicitamente em qual das três categorias ela cai — o default (teste por pacote) a joga na pior delas.

**Ao ler um report:** o `text1` da tela é disclaimer fixo, **o sinal real é o `text2`** (mensagem da exceção); o `text3` traz aparelho + versão do Android + versão do app — uma foto da tela tem que bastar para diagnosticar.

**Para validar mudança nesta classificação** a travada real não é necessária (nem reproduz sob demanda): build temporário com `postDelayed { throw … }` no `onCreate` do `BaseGameActivity`, uma rodada por categoria, conferindo o `W GameLaunchTaskHandler:` no logcat e a tela. Depois `diff` do `.kt` contra a cópia limpa antes de rebuildar — instrumentação esquecida num build de distribuição derruba todo jogo em 20 s.

### 9. Intent implícita de sistema não pode ser lançada crua — em TV não há handler

**Sintoma:** `ActivityNotFoundException` em `Instrumentation.checkStartActivityResult` ao tocar num item de Ajustes. Visto em Fire TV Stick (seletor de `application/zip`) e TCL Smart TV (`ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION`).

**Causa:** builds de Android TV e Fire OS frequentemente não embarcam `DocumentsUI`/Files, e a tela de "acesso a todos os arquivos" não existe. `startActivity` com Intent implícita — e `ActivityResultLauncher.launch`, que chama `startActivityForResult` de forma **síncrona** — lança e derruba o app.

**Regras:**
1. Toda Intent implícita passa por `Context.startActivitySafely` ou `ActivityResultLauncher.launchSafely` ([SafeIntents.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/utils/android/SafeIntents.kt)). Intent explícita (`Intent(context, X::class.java)`) não precisa.
2. **Nunca guardar por `resolveActivity`.** O app não declara `<queries>` no manifesto, então na API 30+ o filtro de visibilidade de pacotes devolve `null` mesmo havendo handler — o guard esconderia botões que funcionam. Iniciar por Intent implícita continua permitido sem `<queries>`; só a consulta é filtrada. Pelo mesmo motivo, não esconder itens de UI "quando não houver handler".
3. Quando a Intent existe para **conceder algo** (permissão), falhar em abri-la exige desfazer o estado otimista da UI — o switch de "acesso a todos os arquivos" volta para desligado, senão mente.
4. Numa cadeia de fallback, só a última tentativa recebe `fallbackMessage`; as anteriores passam `null`.

Detalhes em `documentacao/bugs/done/2026-09-02-intents-sistema-sem-resolve-crasham-tv.md`.

### 10. `tmpfile()` não funciona em processo de app — e o core não checa o retorno

**Sintoma:** salvar (ou carregar) estado de Saturn mata o processo `:game` com `signal 6 (SIGABRT)` e `Abort message: FORTIFY: fwrite: null FILE*`, três quadros abaixo de `LibretroDroid::serializeState()`.

**Causa:** `tmpfile()` do bionic cria o arquivo em `$TMPDIR` e, sem a variável, cai em `/data/local/tmp` — que é `shell:shell drwxrwx--x`, então o uid do app leva `EACCES` e `tmpfile()` devolve `NULL`. O Android **não exporta `TMPDIR`** para processos de app. O yabasanshiro passa esse `NULL` direto para `fwrite`, e o `_FORTIFY_SOURCE` transforma em `abort()`. Determinístico, em todo aparelho — a telemetria mostrava só 2 ocorrências porque pouca gente joga Saturn e salva.

**Regras:**
1. `TMPDIR` é exportado por [NativeTempDir.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/startup/NativeTempDir.kt), chamado no topo de `LemuroidApplication.onCreate`. Não mover para depois das threads (`setenv` corre com `getenv` nativo) nem condicionar a `isMainProcess()` (que devolve `true` quando não resolve o nome, e aí o `:game` fica de fora).
2. **Criar o diretório antes de exportar.** O bionic atual não tem segundo fallback: `$TMPDIR` inexistente = `ENOENT`, pior que a variável ausente.
3. Ao adicionar um core novo, conferir `llvm-nm -D --undefined-only` por `tmpfile`/`mkstemp`/`tmpnam`. Já importam: `yabasanshiro`, `libppsspp`, `atari800`, `hatari`, `libfake08`. `tmpnam` usa `P_tmpdir` = `/tmp` e ignora `TMPDIR` — segue quebrado, sem correção possível do lado do app.
4. Com o `.so` no repo, `llvm-objdump -d --start-address=…` (NDK) responde o que "não temos as fontes do core" sugere ser impossível: os quadros do tombstone levam à instrução exata.

Detalhes em `documentacao/bugs/done/2026-09-02-saturn-yabasanshiro-serializestate-fwrite-null.md`.

### 11. Nada que dependa do estado emulado pode rodar antes do **primeiro frame**

**Sintoma:** sair do jogo (ou pedir um save pelo menu) durante a tela preta de carregamento mata o
processo `:game` com `SIGSEGV … fault addr 0x14` em `retro_serialize_size`, três quadros abaixo de
`GLRetroView.serializeState`.

**Causa:** o app marca o jogo como pronto (`GameState.Ready`) assim que constrói a `GLRetroView` —
a ROM só é carregada depois, na GLThread. E **`retro_load_game` retornar também não basta**: o
Dolphin termina o boot numa thread própria e o PPSSPP/Mupen64 montam estado durante o primeiro
frame. Serializar antes disso lê estrutura que ainda não existe. A janela é o tempo inteiro de carga
— segundos numa ISO de GameCube.

**Regra:** o critério é `hasRenderedFrame` (primeiro `retro_run` concluído), guardado por
`requireGameRunning()` no [GLRetroView.kt](../LibretroDroid-patched/libretrodroid/src/main/java/com/swordfish/libretrodroid/GLRetroView.kt);
quem chama trata `GLRetroView.GameNotLoadedException` como **"não há o que salvar"** — sucesso, sem
toast de falha e sem telemetria: jogo que nunca rodou não tem progresso a perder. Ao adicionar
qualquer chamada nova ao core, decidir se ela entra nessa guarda.

**Corolário de threading:** exceção lançada de dentro de um `queueEvent` sobe na GLThread, onde
ninguém a captura — mata o processo. Só as chamadas bloqueantes (`runOnGLThread`) podem lançar; as
fire-and-forget ignoram e logam.

Detalhes em `documentacao/bugs/open/2026-08-09-anr-inicializar-jogo-runongl-thread.md`.

### 12. API acima do `minSdkVersion` é `NoSuchMethodError` — e `SDK_INT` mente nas TV Box

**Sintoma:** `java.lang.NoSuchMethodError: No virtual method setShowWhenLocked(Z)V in class Landroid/app/Activity;` no `onCreate` do `GameActivity` — **todo** jogo, em **todo** aparelho com Android 5.0–8.0, por três semanas.

**Causa:** `setShowWhenLocked`/`setTurnScreenOn` entraram na API 27; o projeto tem `minSdkVersion = 21`. `compileSdkVersion = 35` deixa compilar sem um aviso sequer. O quadro sintético do R8 no stack (`G3.b.a`) é *API modeling outlining* — o R8 tira a chamada nova de dentro do método para a verificação não falhar, mas **não** cria guard de runtime; só muda onde o erro é lançado.

**Regras:**
1. Ao chamar qualquer API de framework, conferir em que nível ela entrou. O compilador não avisa; só o `NewApi` do lint, que não roda no loop de dev (`./gradlew :lemuroid-app:lintFreeBundleDebug` antes de fechar mudança que toque em API de sistema).
2. **`SDK_INT` sozinho não é guard suficiente neste app.** Pelo mesmo motivo do pitfall 7, as TV Box baratas anunciam Android 9/11 rodando 7.1 de verdade: o teste passa e o `framework.jar` segue sem o método. Guard de versão anda junto com `try/catch` do `NoSuchMethodError`, e os dois caminhos de falha caem no equivalente legado.
3. Mostrar sobre a tela de bloqueio / acender o display só por `Activity.setShowWhenLockedCompat` ([ActivityUtils.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/utils/android/ActivityUtils.kt)).
4. **Rede só por [NetworkCompat.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/utils/android/NetworkCompat.kt)** — `getSystemService(Class)` e `ConnectivityManager.activeNetwork` são API 23. `connectivityManagerCompat()`, `isOnWifiCompat()`, `hasInternetCompat()` e `mobileGenerationCompat()` já caem em `activeNetworkInfo` abaixo disso.
5. **O lint agora passa — e volta a falhar em achado novo.** `lemuroid-app/lint-baseline.xml` congela o passivo já analisado (19 `NewApi` falso-positivo de `CrashTelemetry`/`CoreCrashFallback`, 13 `RestrictedApi`, 58 warnings); `:lemuroid-app:lintFreeBundleDebug` termina **BUILD SUCCESSFUL**. **Se falhar, o achado é seu** — não acrescente ao baseline para calar. Ao corrigir um item da lista, apague o arquivo e rode a task para regenerar.
6. **`removed="23"` no `api-versions.xml` do SDK não é "sumiu do aparelho", é "subiu de classe".** `FrameLayout.setForeground` existe desde a API 1 mas saiu do `android.jar` de `FrameLayout` na 23 — compilando contra a 35, o cast para `FrameLayout` ainda resolve em `View.setForeground` (API 23) e o `NoSuchMethodError` continua. Nesse formato, o caminho legado é reflexão. Conferir o nível em `<SDK>/platforms/android-35/data/api-versions.xml`, nunca de memória.

Detalhes em `documentacao/bugs/done/2026-09-22-gameactivity-nosuchmethoderror-setshowwhenlocked.md` e
`documentacao/bugs/done/2026-09-22-newapi-sem-guard-android-5x-inicia-mainactivity.md` (os outros 13 sites da mesma família).

### 13. O LibretroDroid nunca descarrega um core — e o build garante isso

**Sintoma:** SIGSEGV na GLThread com `fault addr == pc`, `SEGV_MAPERR` e todos os frames
`<unknown>` (1.17.4). E, do mesmo `dlclose`, SIGABRT `terminating` em `__cxa_finalize` ao abrir
outro jogo (até a 1.17.12).

**Causa:** `Core::close()` fazia `dlclose` do core a partir do `destroy()`/`create()` na main,
sem exclusão contra a GLThread. O código do core sumia com a thread dentro dele. Quando o PC não
tem mapa, o unwinder cai para o LR cru. **LR também sem mapa (ou igual ao PC) = biblioteca
descarregada em uso**, não ponteiro selvagem.

**Regras:**
1. `Core::close()` só descarta o handle; o core fica carregado até o processo `:game` morrer.
   Patch em `libs/libretrodroid-patches/core-no-dlclose.patch` — o checkout externo não tem
   commit dele.
2. **Não guardar cópias `.bak`/`.known-good` do AAR em `libs/`.** As três que existiam importavam
   `dlclose` e não tinham `CoreWorkGuard` — foram apagadas em 2026-09-24 (recuperáveis pelo
   histórico, commit `9190756`). Versão anterior do AAR se busca no git, não em cópia solta.
   A task `verifyLibretroDroidBridge`
   ([LibretroDroidBridgeVerifier.kt](buildSrc/src/main/kotlin/LibretroDroidBridgeVerifier.kt)),
   pendurada nos mesmos `merge*NativeLibs`/`package*`/`bundle*` que a `verifyFlycastCore`, derruba o
   build nos dois casos. Se ela acusar, **não desabilite**: reaplique os patches e rebuilde o AAR.

Detalhes em `documentacao/bugs/done/2026-09-03-investigacao-sigsegv-glthread-pc-desmapeado.md`.

---

## Ambiente de build

**Armadilha:** o `gradle.properties` **versionado** carrega caminhos absolutos da máquina de build original — `org.gradle.user.home=E:/.gradle`, `-Djava.io.tmpdir=E:/gradle_tmp` e `org.gradle.java.home=C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot`. Em qualquer máquina sem drive `E:` (ou sem aquele JDK exato) o build não sobe.

**Não conserte editando o arquivo do projeto** — isso quebraria a máquina original. Escreva os overrides em `%USERPROFILE%\.gradle\gradle.properties`, que tem **precedência maior** que o `gradle.properties` do projeto:

```properties
org.gradle.java.home=<caminho do JDK 17>
org.gradle.jvmargs=-Xmx5120m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8 -Djava.io.tmpdir=<tmp que exista>
```

E aponte `GRADLE_USER_HOME` (variável de ambiente) para um `.gradle` que exista, senão o `org.gradle.user.home=E:/.gradle` do projeto vale.

**O que é necessário:** JDK 17; SDK com `platforms;android-35` (compileSdk 35), `build-tools;34.0.0` e `platform-tools`; `local.properties` (não versionado) com `sdk.dir` apontando para esse SDK. **NDK não é necessário** para compilar o app — só para rebuildar o `libretrodroid-patched.aar`. Assinatura de release usa o `debug.keystore` do próprio repo, não precisa de keystore separado.

**Máquina atual (configurada em 2026-08-16):** JDK 17.0.20 em `D:\DevCaches\jdk-17`, SDK em `D:\DevCaches\Android\Sdk`, `GRADLE_USER_HOME=C:\Users\luist\.gradle`, tmp em `%LOCALAPPDATA%\Temp\gradle_tmp`. Variáveis `JAVA_HOME`/`ANDROID_HOME`/`ANDROID_SDK_ROOT`/`GRADLE_USER_HOME` gravadas no escopo User, e `platform-tools` (adb) no PATH.

---

## Emulador para Android antigo (AVD)

O público-alvo real é TV box e aparelho velho (`minSdkVersion 21`), e metade dos pitfalls acima
só aparece em Android 5–7. Configurado em 2026-09-23 no SDK `D:\DevCaches\Android\Sdk`:

| AVD | Imagem | RAM | Serve para |
|-----|--------|-----|-----------|
| `lemu_api25_2gb` | `android-25;google_apis;x86_64` | 2 GB | Android 7.1 — pitfall 7 (Toast BadToken) e 12 (`NoSuchMethodError`); tier `WEAK` |
| `lemu_api21_1gb` | `android-21;default;x86_64` | 1 GB | `minSdkVersion`; tier `ULTRA_WEAK` do `HeavySystemFilter` |
| `lemu_tv_api25` | `android-25;android-tv;x86` | 2 GB | UI Leanback (TV) — pitfall 9 (intents sem `DocumentsUI`) |

Aceleração: **AEHD 2.2** (`extras;google;Android_Emulator_Hypervisor_Driver`), serviço de kernel
`aehd`. Escolhido em vez de WHPX porque esta é uma CPU AMD com SVM ligado e **sem Hyper-V ativo** —
ligar o Hyper-V degradaria VirtualBox/WSL. Conferir com `emulator -accel-check`;
desinstalar com `silent_install.bat -u`.

> ⚠️ **O emulador precisa ser lançado destacado do shell.** Rodar `emulator -avd …` como processo
> filho de uma sessão de ferramenta faz o emulador receber o pedido de shutdown quando a sessão
> termina (ele sai com código 0, salvando snapshot — parece sucesso). Use `Start-Process`.

### `-PdevAbi` — como o APK chega no emulador

`splits.abi` só distribui `arm64-v8a` e `armeabi-v7a`, então por padrão **nenhum APK tem `.so` de
x86** e o app não instala no emulador. Os `.so` de x86/x86_64 já existem em
`lemuroid-cores/*/jniLibs` e no `libretrodroid-patched.aar` — só o filtro os barrava. Por isso o
`include` aceita ABIs extras por propriedade:

```
./gradlew :lemuroid-app:assembleFreeBundleDebug -PdevAbi=x86_64   # x86 para o AVD de TV
adb -s emulator-5554 install -r -t lemuroid-app/build/outputs/apk/freeBundle/debug/lemuroid-app-free-bundle-x86_64-debug.apk
```

Sem a propriedade nada muda: build de distribuição continua saindo só com as duas ABIs ARM.

> ⚠️ **O que o x86_64 NÃO testa:** os pitfalls 6a/6b (Flycast/`libandroid.so`, dynarec ARM) e o
> truncamento de pointer tag do Android 11+ são específicos de ARM — o core x86 nem usa o mesmo
> dynarec. Para esses, ou aparelho real, ou `system-images;android-25;google_apis;armeabi-v7a`
> (roda o APK de distribuição sem alteração, mas por emulação TCG pura: lento demais para jogar,
> útil só para ver se o core carrega).
