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

**Versão atual: 21**

Entidade principal: `Game` — representa um jogo no catálogo (ROM local ou placeholder 0-byte para download sob demanda).

### Histórico de Migrações Relevantes

| Versão | Mudança |
|--------|---------|
| 9→10 | Tabela `downloaded_roms` |
| 18→19 | Índice composto `(isFavorite, lastPlayedAt)` na tabela `games` |
| **19→20** | **Tabela `save_queue`** (fila de downloads persistente) |
| **20→21** | **Coluna `popularityIndex INTEGER NOT NULL DEFAULT 0` + índice em `games`** |

Migrações em: [Migrations.kt](retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/db/dao/Migrations.kt)
Registro em: [LemuroidApplicationModule.kt](lemuroid-app/src/main/java/com/swordfish/lemuroid/app/LemuroidApplicationModule.kt)

> **Nota Room**: o arquivo `schemas/…/21.json` é gerado automaticamente pelo kapt no primeiro build após adicionar a nova versão. Nunca editar schemas manualmente.

---

## Catálogo de Jogos (`catalog_manifest.txt`)

Arquivo de assets com uma linha por jogo, formato pipe-delimitado:

```
system/filename.ext|título|https://cover-url.png|popularityIndex
```

- **Campo 4 (`popularityIndex`)**: inteiro positivo. Valores maiores = mais popular. `0` significa sem dados de popularidade. Varia tipicamente de 1 a ~1000.
- Sistemas usam aliases no manifest (`a26` → `atari2600`, `megadrive` → `md`, etc.) — ver `MANIFEST_ALIAS` em [ManifestQuickLoader.kt](retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/catalog/ManifestQuickLoader.kt).

### Pipeline de Carga

1. **`CatalogCoverProvider`** — lê e parseia `catalog_manifest.txt` em `Map<String, ManifestEntry>` (lazy, uma vez por processo).
2. **`ManifestQuickLoader.load()`** — roda no startup via `MainProcessInitializer` (500 ms após a app iniciar):
   - **Skip por versão**: se o `versionCode` do app já foi salvo em SharedPreferences (`manifest_loader_prefs`), define `catalogReady = true` imediatamente e retorna. Isso torna todos os lançamentos pós-instalação instantâneos.
   - `INSERT OR IGNORE` de todos os `Game` no banco (não sobrescreve dados enriquecidos pelo LibretroDB).
   - **Batch UPDATE de `popularityIndex`** em transação para jogos que já existiam (útil em atualizações do app).
   - Salva o `versionCode` atual em SharedPreferences para habilitar o skip nos próximos lançamentos.

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

| Método | Ordenação |
|--------|-----------|
| `selectBySystemSortedByPopularity(systemId)` | downloaded DESC, popularityIndex DESC, title ASC |
| `selectBySystemsSortedByPopularity(systemIds)` | idem, multi-sistema |
| `selectBySystem(systemId)` | downloaded DESC, title ASC (legado / A-Z) |
| `selectBySystems(systemIds)` | idem, multi-sistema |

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
