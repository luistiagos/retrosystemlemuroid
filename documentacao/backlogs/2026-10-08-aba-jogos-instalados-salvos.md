# [BACKLOG] Nova Aba "Instalados" no Lemuroid (Estilo ARMSX2-forkv2)

**Data:** 2026-10-08  
**Autor:** Antigravity  
**Status:** Planejamento consolidado — pendências da primeira, segunda e terceira rodadas de revisão técnica (R1 a R9) totalmente resolvidas  
**Origem:** Demanda de usabilidade — eliminar a necessidade de o usuário navegar por `Sistemas → [Sistema] → [Jogo]` a cada nova sessão para jogar títulos já presentes no aparelho.  
**Referência de Implementação:** Padrão de biblioteca local e aba `Salvos` do `ARMSX2-forkv2` (`HomeTab.Saved` / `HomeScreen.kt`).

---

## 1. Motivação e Contexto

No Lemuroid mobile atual, o catálogo possui dezenas de milhares de títulos distribuídos em diversos sistemas (`MetaSystemID`). Quando o usuário faz o download de uma ROM sob demanda (ou possui jogos locais):
1. Para jogar novamente em sessões posteriores, ele é obrigado a navegar pela hierarquia de catálogo: acessar a aba **Sistemas**, encontrar o console desejado, rolar ou buscar pelo jogo dentro do sistema.
2. A tela inicial (**Home**) conta com uma seção de "Recentes", mas ela é limitada em quantidade de itens e divide espaço com as seções "Favoritos", "Descubra" e notificações de permissão.
3. Não há uma visão consolidada de **tudo o que está presente fisicamente no aparelho**, independente do sistema emulador.

### Objetivo
Replicar a eficácia da aba da biblioteca local do `ARMSX2-forkv2`, introduzindo no Lemuroid uma nova aba principal na barra inferior: **"Instalados"**.

Nesta aba:
- O usuário visualiza imediatamente todos os jogos instalados no aparelho (tanto baixados pelo catálogo quanto importados via escaneamento de ROMs locais) em uma lista/grade unificada multi-sistema.
- Cada card exibe o **nome do sistema** (ex: *Super Nintendo*, *MAME*, *PlayStation*), a **capa (cover)** e o **título** do jogo.
- A navegação é direta: o usuário toca no jogo e joga imediatamente, sem precisar escolher o sistema antes.
- Todo jogo recém-baixado aparece automaticamente nesta aba.
- A ordenação é estritamente baseada na **última ação do usuário** (jogou ou baixou).
- A busca opera diretamente sobre os jogos instalados (com busca instantânea na tela por título/sistema/arquivo e integração com a aba global).
- Regra de variantes: se o usuário baixou apenas 1 versão (ex: USA), joga direto. Se baixou múltiplas versões do mesmo jogo (ex: USA e JP), elas ficam agrupadas sob um único título e o toque abre o diálogo de seleção apenas com as versões instaladas.

---

## 2. Requisitos Consolidados

### 2.1. Navegação e Barra Inferior
- **Nova Rota:** `MainRoute.INSTALLED` registrada em [MainNavigationRoutes.kt](file:///c:/projects/lemuroid/Lemuroid/lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/main/MainNavigationRoutes.kt).
- **Rótulo:** String localizada `R.string.title_installed` ("Instalados").
- **Ícone:** Vector icon de download/armazenamento concluído (`Icons.Filled.DownloadDone` / `Icons.Outlined.DownloadDone`).
- **Posicionamento na barra de navegação:**  
  `Início` | **`Instalados`** | `Sistemas` | `Favoritos` | `Busca`

### 2.2. Design do Card de Jogo (`LemuroidInstalledGameCard`)
Como os jogos de diferentes plataformas ficam unificados na mesma grade, o card deve evidenciar a plataforma com máxima legibilidade:
- **Capa (Cover Front):** Proporção padrão do Lemuroid com carregamento assíncrono (Coil).
- **Tag/Chip do Sistema:** Exibição proeminente do nome amigável do console (ex: "Super Nintendo", "Mega Drive", "PlayStation", "MAME"), obtido via `GameSystem.findByIdOrNull(game.systemId)?.shortTitleResId` ou `titleResId`.
- **Título do Jogo:** Título limpo e formatado (`game.title`).
- **Badge de Múltiplas Versões:** Indicador visual (ícone de cópia ou tag "X versões") caso haja mais de uma versão lógica distinta instalada no aparelho (`installedVariantsCount > 1`). Cópias idênticas do mesmo arquivo em volumes diferentes são desduplicadas e contam como 1 única versão (R7).
- **Indicador de Indisponibilidade Temporária:** Caso a mídia física esteja desconectada (ex: SD card ejetado ou permissão SAF pendente), o card exibe ícone de aviso sutil com opacidade reduzida, sem apagar o histórico do jogo (R4).

### 2.3. Ordenação por Última Ação do Usuário (LRU Unificado)
A lista de jogos instalados deve ser ordenada pelo timestamp da interação mais recente do usuário com o jogo:
- **Ação 1 — Jogou o jogo:** Gravado no **lançamento da sessão** em [GameLauncher.kt](file:///c:/projects/lemuroid/Lemuroid/lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/GameLauncher.kt) (`launchGameAsync`), imediatamente após `handleGameStart` e antes de `BaseGameActivity.launchGame`, utilizando o `launchGame` retornado por `prepareGameForLaunch`.
  - **Atualização Parcial por ID (R6):** A gravação deve usar `gameDao.updateLastPlayedAt(launchGame.id, System.currentTimeMillis())`, em vez de atualizar a entidade inteira (`update(game.copy(...))`), evitando sobrescrever concorrentemente dados de indexação ou favoritos.
  - A escrita redundante em `GameLaunchTaskHandler.handleSuccessfulGameFinish` é **removida** para que o horário de término não sobrescreva a data de lançamento.
- **Ação 2 — Baixou o jogo:** Quando o download termina, `RomOnDemandManager` grava `downloaded_roms.downloadedAt = System.currentTimeMillis()`.
- **Fórmula de Ordenação de Atividade:**
  $$\text{lastActionAt} = \max(\text{COALESCE}(\text{games.lastPlayedAt}, 0), \text{COALESCE}(\text{downloaded\_roms.downloadedAt}, 0))$$
- **Eliminação de contaminação por reindexação:** O campo mutável `lastIndexedAt` foi **completamente removido** do cálculo. Reescaneamentos de biblioteca, sincronização de catálogo e atualização de metadados não alteram o `lastActionAt`.
- **Jogos sem histórico (ROMs locais não jogadas):** Títulos importados que ainda não foram jogados recebem $\text{lastActionAt} = 0$, posicionando-se abaixo de qualquer jogo com atividade real.
- **Desempate estável (Tiebreaker determinístico):**
  `ORDER BY lastActionAt DESC, g.title ASC, g.id ASC`.

### 2.4. Regra de Múltiplas Versões / Variantes
No catálogo do Lemuroid, títulos com múltiplas versões (ex: Chrono Trigger com versões US, JP, EU) são agrupados por `(systemId, title)`.
Na aba de **Instalados**, o comportamento para variantes é:
1. **Caso 1: Apenas 1 versão instalada**
   - Se o usuário baixou somente a versão US:
   - Na aba Instalados, aparece **apenas aquele item**.
   - Ao tocar no card: **lança e executa o jogo diretamente** (sem abrir diálogo de seleção, pois há apenas uma opção instalada).
2. **Caso 2: Duas ou mais versões instaladas do mesmo título**
   - Se o usuário posteriormente baixar também a versão JP:
   - Ambas as versões ficam **agrupadas sob o mesmo card/título** na aba de Instalados (evitando duplicatas na grade).
   - O card apresenta indicador visual de versões (badge com a quantidade de versões instaladas).
   - Ao tocar no card: **abre o diálogo de seleção de versões** ([GameVariantsModal.kt](file:///c:/projects/lemuroid/Lemuroid/lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/main/GameVariantsModal.kt)), listando as versões instaladas no aparelho para o usuário escolher qual deseja jogar.
   - O timestamp `lastActionAt` do card agrupado passa a ser o maior timestamp entre as versões instaladas daquele título.
3. **Independência do Filtro de Busca (Consistência de Variantes):**
   - A contagem de variantes e a lista do seletor consideram sempre **todas as versões instaladas do grupo**. Se o usuário pesquisar por um termo presente em apenas uma das ROMs (ex: buscar "USA" com USA e JP instaladas), o card continua exibindo o grupo com suas 2 versões, mantendo a contagem e abrindo o seletor com ambas as versões.
4. **Desduplicação de Cópias Físicas (R7):**
   - Cópias físicas idênticas (`fileName` idêntico) presentes em múltiplos volumes (ex.: internal storage e SD card) são desduplicadas no agrupamento: contam como **1 única versão lógica** com fallback determinístico de cópia disponível, evitando falsos badges de "2 versões".

### 2.5. Mecanismo de Busca Híbrido com Suporte a Nomes de Sistema
1. **Busca Instantânea na Própria Aba (estilo ARMSX2-forkv2):**  
   - Campo de busca no topo de `InstalledGamesScreen`.
   - Conforme o usuário digita, a grade filtra reativamente sem sair da tela.
   - **Busca por Nome do Sistema (`SystemSearchResolver`):** Mapeia termos digitados (ex: "Super Nintendo", "SNES", "PlayStation", "Mega Drive", "MAME") para os respectivos `systemId`s (`snes`, `psx`, `md`, `mame2003plus`, etc.). Digitar o nome do console encontra todos os jogos instalados daquela plataforma.
2. **Integração com a Aba Global de Busca (R8, R9):**  
   - Ao alternar para a aba `MainRoute.SEARCH` a partir de Instalados, `MainViewModel` configura `searchScope = SearchScope.INSTALLED_ONLY`.
   - `GameSearchDao.search` (via `@RawQuery` dinâmica) recebe os parâmetros de armazenamento validados e aplica o filtro de elegibilidade de instalação **antes** do `LIMIT 100`, evitando que jogos não instalados do catálogo consumam o limite.
   - A query observa `downloaded_roms` e `games`, invalidando o `PagingSource` reativamente se uma ROM for baixada ou deletada.
   - **Propagação de Escopo ao Clicar (R8):** Ao clicar em um resultado na busca com escopo `INSTALLED_ONLY`, o clique não abre variantes não baixadas do catálogo geral: se houver 1 versão instalada, abre direto; se houver mais de uma instalada, o modal oferece apenas as instaladas.
   - A `SearchScreen` exibe um chip descartável indicando o escopo restrito (`[x] Jogos Instalados`), permitindo alternar para o catálogo completo com 1 toque.
   - Ao trocar de aba no rodapé, o escopo volta para `ALL` para não reter filtros órfãos.

### 2.6. Escopo de Jogos, Disponibilidade Física e Ações Seguras
A aba exibe apenas ROMs com disponibilidade física comprovada:
- **ROMs do Catálogo:** Registradas na tabela `downloaded_roms` com arquivo no disco gerenciado.
- **ROMs Locais do Usuário:** Arquivos físicos válidos fora das pastas gerenciadas (`INSTR(fileUri, managedMarker) = 0` e `fileUri NOT LIKE sentinelPrefix`).
- **ROMs Locais na Pasta Gerenciada (R5):** Arquivos físicos válidos (tamanho > 0) colocados manualmente na pasta gerenciada sem registro prévio em `downloaded_roms` são descobertos pela reconciliação e registrados via `INSERT OR IGNORE` com `downloadedAt = 0` (preservando o ordenamento sem datas fictícias e sem sobrescrever downloads ativos em concorrência). Arquivos temporários (`.part`, `.tmp`), downloads em curso na fila e extrações parciais são estritamente excluídos da descoberta.
- **Proteção da Limpeza da Biblioteca contra Leituras Incompletas (R2):**
  - Em [LemuroidLibrary.kt](file:///c:/projects/lemuroid/Lemuroid/retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/LemuroidLibrary.kt), o método `cleanUp()` só aciona `deleteByLastIndexedAtLessThan` se todos os provedores ativos tiverem reportado leitura completa e bem-sucedida. Se um provedor SAF ou SD falhar (retornar vazio por erro de permissão ou `listFiles() == null`), a limpeza destrutiva para aquele provedor é **abortada**, preservando os registros de `games` e as dependências auxiliares em `datafiles` (CUE/BINs).
- **Proteção do Realinhamento de Raízes em Mídia Ausente (R3):**
  - Em `ManifestQuickLoader.realignManagedRoots` e `RomsRootRealigner.plan`, entradas pertencentes a volumes temporariamente ausentes/desmontados são **ignoradas**, preservando a URI original e a identidade no `downloaded_roms` para quando a mídia for remontada.
- **Reconciliação e Monitor de Disponibilidade Observável (R4):**
  - Componente `StorageAvailabilityMonitor` acompanha broadcasts de montagem/desmontagem de mídia do Android.
  - Combina o fluxo Room com o estado físico de armazenamento em `InstalledGameGroupUiModel`: se o SD estiver ejetado, o jogo permanece na lista com indicação de mídia desconectada (`isAvailable = false`), sem apagar o histórico de `lastPlayedAt`.
- **Ações de Exclusão Seguras por Origem (R1):**
  - Para ROMs locais / SAF, a ação de exclusão física opera **estritamente sobre o arquivo da ROM** (ou via SAF API), **nunca executando `parentDir.deleteRecursively()`** para não apagar pastas compartilhadas inteiras.
  - Em cards com múltiplas versões agrupadas, a confirmação de exclusão lista explicitamente a versão alvo (ou oferece seleção de qual versão excluir).

---

## 3. Planejamento de Arquitetura e Engenharia

### 3.1. Modelo de Dados e Projeção (Room)

Criar uma projeção em [retrograde-app-shared](file:///c:/projects/lemuroid/Lemuroid/retrograde-app-shared):

```kotlin
data class InstalledGameGroup(
    @Embedded val game: Game,
    val installedVariantsCount: Int,
    val lastActionAt: Long,
)
```

E o modelo de UI enriquecido com o monitor de armazenamento (R4):

```kotlin
data class InstalledGameGroupUiModel(
    val group: InstalledGameGroup,
    val isAvailable: Boolean = true,
    val hasAvailableVariants: Boolean = true,
)
```

### 3.2. Queries no [GameDao.kt](file:///c:/projects/lemuroid/Lemuroid/retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/db/dao/GameDao.kt)

As consultas utilizam Common Table Expressions (CTE) para isolar a condição de instalação, desduplicar cópias físicas idênticas (R7) e garantir que a busca por arquivo não quebre as variantes do grupo (R8):

1. **Seleção de Jogos Instalados (Agrupados por Título e Ordenados por Última Ação):**
```sql
WITH InstalledRoms AS (
    SELECT g.*, COALESCE(dr.downloadedAt, 0) AS downloadedAt
    FROM games g
    LEFT JOIN downloaded_roms dr ON g.systemId = dr.systemId AND g.fileName = dr.fileName
    WHERE (
        dr.fileName IS NOT NULL 
        OR (
            g.fileUri NOT LIKE 'file:///lemuroid_prebuilt/%' 
            AND INSTR(g.fileUri, :managedMarker) = 0 
            AND SUBSTR(g.fileUri, 1, LENGTH(:romsDirPrefix)) <> :romsDirPrefix
        )
    )
),
DistinctGroups AS (
    SELECT DISTINCT systemId, title FROM InstalledRoms
)
SELECT 
    rep.*,
    COUNT(DISTINCT all_v.fileName) AS installedVariantsCount,
    MAX(MAX(COALESCE(all_v.lastPlayedAt, 0), all_v.downloadedAt)) AS lastActionAt
FROM DistinctGroups dg
INNER JOIN InstalledRoms all_v 
    ON all_v.systemId = dg.systemId AND all_v.title = dg.title
INNER JOIN InstalledRoms rep 
    ON rep.id = (
        SELECT r.id FROM InstalledRoms r
        WHERE r.systemId = dg.systemId AND r.title = dg.title
        ORDER BY MAX(COALESCE(r.lastPlayedAt, 0), r.downloadedAt) DESC, r.isRepresentative DESC, r.fileName ASC, r.id ASC
        LIMIT 1
    )
GROUP BY dg.systemId, dg.title
ORDER BY lastActionAt DESC, rep.title ASC, rep.id ASC
```

2. **Seleção com Busca Textual Instantânea (Preservando Todas as Variantes do Grupo):**
```sql
WITH InstalledRoms AS (
    SELECT g.*, COALESCE(dr.downloadedAt, 0) AS downloadedAt
    FROM games g
    LEFT JOIN downloaded_roms dr ON g.systemId = dr.systemId AND g.fileName = dr.fileName
    WHERE (
        dr.fileName IS NOT NULL 
        OR (
            g.fileUri NOT LIKE 'file:///lemuroid_prebuilt/%' 
            AND INSTR(g.fileUri, :managedMarker) = 0 
            AND SUBSTR(g.fileUri, 1, LENGTH(:romsDirPrefix)) <> :romsDirPrefix
        )
    )
),
MatchingGroups AS (
    SELECT DISTINCT systemId, title
    FROM InstalledRoms
    WHERE (
        :query = '' 
        OR title LIKE '%' || :query || '%' 
        OR fileName LIKE '%' || :query || '%'
        OR systemId IN (:matchedSystemIds)
    )
)
SELECT 
    rep.*,
    COUNT(DISTINCT all_v.fileName) AS installedVariantsCount,
    MAX(MAX(COALESCE(all_v.lastPlayedAt, 0), all_v.downloadedAt)) AS lastActionAt
FROM MatchingGroups mg
INNER JOIN InstalledRoms all_v 
    ON all_v.systemId = mg.systemId AND all_v.title = mg.title
INNER JOIN InstalledRoms rep 
    ON rep.id = (
        SELECT r.id FROM InstalledRoms r
        WHERE r.systemId = mg.systemId AND r.title = mg.title
        ORDER BY MAX(COALESCE(r.lastPlayedAt, 0), r.downloadedAt) DESC, r.isRepresentative DESC, r.fileName ASC, r.id ASC
        LIMIT 1
    )
GROUP BY mg.systemId, mg.title
ORDER BY lastActionAt DESC, rep.title ASC, rep.id ASC
```

3. **Seleção das Variantes Instaladas de um Grupo Específico:**
```sql
SELECT g.* 
FROM games g
LEFT JOIN downloaded_roms dr ON g.systemId = dr.systemId AND g.fileName = dr.fileName
WHERE g.systemId = :systemId 
  AND g.title = :title
  AND (
      dr.fileName IS NOT NULL 
      OR (
          g.fileUri NOT LIKE 'file:///lemuroid_prebuilt/%' 
          AND INSTR(g.fileUri, :managedMarker) = 0 
          AND SUBSTR(g.fileUri, 1, LENGTH(:romsDirPrefix)) <> :romsDirPrefix
      )
  )
ORDER BY MAX(COALESCE(g.lastPlayedAt, 0), COALESCE(dr.downloadedAt, 0)) DESC, g.fileName ASC, g.id ASC
```

4. **Atualizações Parciais no [GameDao.kt](file:///c:/projects/lemuroid/Lemuroid/retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/db/dao/GameDao.kt) (R6):**
```kotlin
@Query("UPDATE games SET lastPlayedAt = :timestamp WHERE id = :gameId")
suspend fun updateLastPlayedAt(gameId: Int, timestamp: Long)

@Query("UPDATE games SET lastIndexedAt = :timestamp WHERE id = :gameId")
suspend fun updateLastIndexedAt(gameId: Int, timestamp: Long)
```

### 3.3. Busca Global no [GameSearchDao.kt](file:///c:/projects/lemuroid/Lemuroid/retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/db/dao/GameSearchDao.kt) (R8, R9)

Para compatibilidade total com a tabela virtual `fts_games` (que não é entidade Room) e suporte ao contrato de paginação (`PagingSource<Int, Game>`), a busca global mantém a interface `@RawQuery` no `Internal` observando ambas as entidades:

```kotlin
@Dao
interface Internal {
    @RawQuery(observedEntities = [Game::class, DownloadedRom::class])
    fun rawSearch(query: SupportSQLiteQuery): PagingSource<Int, Game>
}
```

O método público `search(...)` constrói a consulta dinamicamente, evitando `IS NULL` sobre coleções e validando parâmetros de armazenamento para não gerar cláusulas inválidas:

```kotlin
fun search(
    query: String,
    systemIds: List<String>? = null,
    onlyInstalled: Boolean = false,
    romsPrefix: String = "",
    managedMarker: String = "",
): PagingSource<Int, Game> {
    val matchArg = sanitizeFtsQuery(query)
    val args = mutableListOf<Any>(matchArg)
    val sql = StringBuilder()

    sql.append("""
        SELECT games.*
        FROM fts_games
        JOIN games ON games.id = fts_games.docid
    """)

    if (onlyInstalled) {
        sql.append("""
            LEFT JOIN downloaded_roms dr ON games.systemId = dr.systemId AND games.fileName = dr.fileName
        """)
    }

    sql.append(" WHERE fts_games MATCH ? ")

    if (!systemIds.isNullOrEmpty()) {
        val placeholders = systemIds.joinToString(",") { "?" }
        sql.append(" AND games.systemId IN ($placeholders) ")
        args.addAll(systemIds)
    }

    if (onlyInstalled) {
        require(romsPrefix.isNotEmpty() && managedMarker.isNotEmpty()) {
            "romsPrefix e managedMarker são obrigatórios quando onlyInstalled=true"
        }
        sql.append("""
            AND (
                dr.fileName IS NOT NULL
                OR (
                    games.fileUri NOT LIKE 'file:///lemuroid_prebuilt/%'
                    AND INSTR(games.fileUri, ?) = 0
                    AND SUBSTR(games.fileUri, 1, LENGTH(?)) <> ?
                )
            )
        """)
        args.add(managedMarker)
        args.add(romsPrefix)
        args.add(romsPrefix)
    }

    sql.append(" LIMIT 100 ")

    return internalDao.rawSearch(SimpleSQLiteQuery(sql.toString(), args.toTypedArray()))
}
```

### 3.4. Camada de Apresentação (Jetpack Compose)

1. **`SystemSearchResolver`** (Novo utilitário em `app/shared/search/`):
   - Mapeia a consulta digitada para os identificadores de sistema compatíveis (`matchedSystemIds`).
2. **`InstalledGamesViewModel.kt`** (Novo):
   - Combina `searchQuery` debounced com `SystemSearchResolver.resolveSystemIds(query)`.
   - Combina o Room Flow com o `StorageAvailabilityMonitor` para expor `StateFlow<List<InstalledGameGroupUiModel>>`.
   - Fornece `getInstalledVariants(systemId, title)` em `Dispatchers.IO`.
3. **`InstalledGamesScreen.kt`** (Novo):
   - `LazyVerticalGrid` adaptativo com chave estável baseada no grupo `(systemId, title)`.
   - Campo de busca instantânea integrado no topo.
   - Componente `LemuroidInstalledGameCard`: capa, chip de sistema, título, badge de versões e estado de disponibilidade.
   - `LemuroidEmptyView` amigável quando nenhum jogo estiver instalado.
4. **[GameLauncher.kt](file:///c:/projects/lemuroid/Lemuroid/lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/GameLauncher.kt)**:
   - Em `launchGameAsync`: grava `lastPlayedAt` chamando `gameDao.updateLastPlayedAt(launchGame.id, System.currentTimeMillis())` em `Dispatchers.IO` após `handleGameStart` e antes de `BaseGameActivity.launchGame`.
5. **[MainActivity.kt](file:///c:/projects/lemuroid/Lemuroid/lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/main/MainActivity.kt)**:
   - Clique em item de versão única: verifica disponibilidade física e executa `gameInteractor.onGamePlay(item.game)`.
   - Clique em item com $\ge 2$ versões: busca variantes instaladas e exibe `GameVariantsModal`.
   - Long-press: menu de contexto `MainGameContextActions` com exclusão segura por origem (R1).

---

## 4. Plano de Implementação Passo a Passo

| Fase | Etapa | Descrição | Arquivos Envolvidos |
|---|---|---|---|
| **1. Dados** | Queries e Projeções | Criar `InstalledGameGroup`, adicionar queries corrigidas e `updateLastPlayedAt` em `GameDao` e atualizar `GameSearchDao` com `@RawQuery` dinâmica e observação de `DownloadedRom`. | [GameDao.kt](file:///c:/projects/lemuroid/Lemuroid/retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/db/dao/GameDao.kt), [GameSearchDao.kt](file:///c:/projects/lemuroid/Lemuroid/retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/db/dao/GameSearchDao.kt), `InstalledGameGroup.kt` |
| **2. Limpeza e Volumes** | Proteção contra Falhas | Atualizar `cleanUp` em `LemuroidLibrary` para abortar se provedores falharem; proteger `datafiles`; ignorar mídias ausentes em `RomsRootRealigner`. | [LemuroidLibrary.kt](file:///c:/projects/lemuroid/Lemuroid/retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/LemuroidLibrary.kt), `RomsRootRealigner.kt` |
| **3. Lançamento & Atividade** | Escrita Parcial | Gravar `lastPlayedAt` via `updateLastPlayedAt` no `launchGameAsync` em `GameLauncher` e remover chamada redundante de `handleSuccessfulGameFinish`. | [GameLauncher.kt](file:///c:/projects/lemuroid/Lemuroid/lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/GameLauncher.kt), [GameLaunchTaskHandler.kt](file:///c:/projects/lemuroid/Lemuroid/lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/main/GameLaunchTaskHandler.kt) |
| **4. Disponibilidade & Descoberta** | Monitor e Reconciliação | Implementar `StorageAvailabilityMonitor`, auto-registro seguro via `INSERT OR IGNORE` com `downloadedAt = 0` para ROMs locais, e proteção contra arquivos parciais. | `StorageAvailabilityMonitor.kt`, `DownloadedRomDao.kt`, `InstalledGamesViewModel.kt` |
| **5. ViewModel & Busca** | Resolver e Lógica | Implementar `SystemSearchResolver`, `InstalledGamesViewModel` e integração de escopo no `SearchViewModel`. | `SystemSearchResolver.kt`, `InstalledGamesViewModel.kt`, `SearchViewModel.kt` |
| **6. UI Compose** | Card e Tela | Desenvolver `LemuroidInstalledGameCard` e `InstalledGamesScreen` com campo de busca instantânea e indicador de disponibilidade. | `InstalledGamesScreen.kt`, `LemuroidInstalledGameCard.kt` |
| **7. Navegação & Ações** | Rotas e Exclusão Segura | Registrar `MainRoute.INSTALLED` em [MainNavigationRoutes.kt](file:///c:/projects/lemuroid/Lemuroid/lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/main/MainNavigationRoutes.kt), atualizar `MainNavigationBar` e implementar exclusão de ROM segura por origem (R1). | `MainNavigationRoutes.kt`, `MainNavigationBar.kt`, `MainActivity.kt`, `RomOnDemandManager.kt` |
| **8. Strings** | Localização | Adicionar strings `title_installed`, `search_scope_installed`, mensagens de mídia desconectada em `strings.xml`. | `strings.xml` (en e pt-BR) |

---

## 5. Resolução da Primeira Revisão Técnica

| Item | Pendência Levantada pelo Revisor | Resolução Consolidada no Plano |
|---|---|---|
| **5.1 [P1]** | Filtro SQL não garantia disponibilidade física; volumes antigos podiam ser confundidos com ROM local. | SQL atualizado distinguindo explicitamente ROMs gerenciadas de qualquer volume (`managedMarker`) e sentinelas. Adicionada reconciliação assíncrona fora da MainThread e tratamento de mídia desconectada sem apagar histórico. |
| **5.2 [P1]** | Reescaneamentos renovavam `lastIndexedAt`, distorcendo a ordenação de atividade recente. | `lastIndexedAt` totalmente removido da fórmula de ordenação. Atividade restrita a ações do usuário (`lastPlayedAt` e `downloadedAt`), com tiebreaker estável por título e ID. |
| **5.3 [P1]** | Busca por arquivo filtrava antes de `GROUP BY`, desfazendo o grupo e o seletor de variantes. | Arquitetura de duas fases (CTE): qualificação de grupos via `MatchingGroups` e agregação sobre o conjunto completo de variantes instaladas daquele grupo. |
| **5.4 [P2]** | Busca global aplicava `LIMIT 100` antes do filtro e não observava `downloaded_roms`. | Criado suporte a `onlyInstalled` no `GameSearchDao` com JOIN antes do `LIMIT 100` e observação de ambas as entidades. Adicionado controle de escopo e chip descartável na `SearchScreen`. |
| **5.5 [P2]** | Busca textual não encontrava jogos pelo nome do console (ex: "Super Nintendo"). | Criado `SystemSearchResolver` para mapear nomes de sistemas em `systemId`s e incluí-los no predicado de correspondência. |
| **5.6 [P2]** | `lastPlayedAt` era registrado somente no término bem-sucedido da sessão, falhando em caso de crash. | Definido o registro de `lastPlayedAt` no momento do lançamento do jogo em `GameLauncher.launchGameAsync`, garantindo posicionamento imediato no topo da lista. |

---

## 6. Resolução da Segunda Revisão Técnica

| Item | Pendência Levantada na 2ª Rodada | Resolução Consolidada no Plano |
|---|---|---|
| **6.1 [P1]** | `LemuroidLibrary.cleanUp` chamava `deleteByLastIndexedAtLessThan` no `finally`, apagando jogos SAF/SD se o scan falhasse. | `cleanUp` passa a verificar se os provedores foram lidos com sucesso; se um provider falhar ou estiver inacessível, a limpeza para aquele provider é abortada, preservando os registros e o histórico. Adicionado `isAvailable` a `InstalledGameGroup` para aviso na UI. |
| **6.2 [P1]** | `(:systemIds IS NULL OR g.systemId IN (:systemIds))` falhava com `row value misused` para múltiplos IDs. | Eliminado o teste escalar `IS NULL` sobre coleções. `GameSearchDao` monta dinamicamente a cláusula `IN (?, ?)` apenas quando a lista não for nula nem vazia, cobrindo os quatro casos sem erro SQL. |
| **6.3 [P1]** | O DAO proposto não correspondia à arquitetura de `GameSearchDao` (`@RawQuery` no `Internal` e `PagingSource`). | Mantida a estrutura oficial do `GameSearchDao` com `@RawQuery` dinâmica, adicionando `DownloadedRom::class` em `observedEntities` e preservando o contrato de `PagingSource<Int, Game>` para o Compose. |
| **6.4 [P2]** | ROMs locais colocadas na pasta gerenciada sem download eram excluídas da aba. | Reconciliação em background detecta arquivos físicos válidos (tamanho > 0) na pasta gerenciada e garante registro em `downloaded_roms` com `downloadedAt = 0`, incluindo-as na aba sem atribuir atividade recente fictícia. |
| **6.5 [P2]** | Gravação de atividade apontava para método inexistente e mantinha gravação redundante no encerramento. | Localizado o ponto real em `GameLauncher.launchGameAsync` (usando `launchGame` pós-preparação) e removida a chamada de `updateGamePlayedTimestamp` de `handleSuccessfulGameFinish`. |

---

## 7. Resolução da Terceira Revisão Técnica (Consolidação R1–R9)

| Item | Pendência Levantada na Revisão Integral | Resolução Consolidada e Integrada |
|---|---|---|
| **R1 [P1]** | `deleteRom` chamava `parentDir.deleteRecursively()` em pastas não gerenciadas, podendo apagar a pasta de ROMs inteira. | Exclusão física separada por origem: ROMs locais fora da pasta gerenciada apagam apenas o arquivo da ROM (`File.delete()`), sem tocar na pasta pai; SAF usa API de documento. Alvo em cards agrupados é explicitado antes de confirmar. |
| **R2 [P1]** | Provedores engoliam erros com listas vazias e `cleanUp` apagava `datafiles` de jogos protegidos. | Provedores propagam status explícito (`SUCCESS` vs `FAILED`/`INCOMPLETE`); falha aborta limpeza destrutiva. Exclusão de `datafiles` é amarrada à remoção confirmada do jogo. |
| **R3 [P1]** | `RomsRootRealigner` repontava ou apagava entradas de SDs desconectados antes da reconciliação. | O realinhador ignora entradas de volumes inacessíveis/desmontados, preservando caminhos e vínculos para quando a mídia for reconectada. |
| **R4 [P1]** | `isAvailable` não tinha origem observável nem mapeamento Room. | Criado `StorageAvailabilityMonitor` observando broadcasts de mídia do Android; ViewModel combina Room Flow com estado de armazenamento em `InstalledGameGroupUiModel`. No modal, versões inacessíveis informam mídia desconectada sem disparar download falso. |
| **R5 [P1]** | Descoberta por tamanho positivo podia publicar arquivos parciais e `REPLACE` podia apagar datas reais. | Arquivos transitórios (`.part`, downloads na fila, extrações) são ignorados; inserção de descobertas usa `INSERT OR IGNORE` com `downloadedAt = 0`, sem substituir registros concorrentes reais. |
| **R6 [P1]** | Gravação de snapshot integral do `Game` sobrescrevia concorrentemente `lastPlayedAt` ou `lastIndexedAt`. | Criadas queries de atualização parcial no `GameDao`: `updateLastPlayedAt(id, ts)` e `updateLastIndexedAt(id, ts)`, eliminando colisões de snapshots desatualizados. |
| **R7 [P2]** | Cópias físicas idênticas em volumes diferentes contavam como 2 versões distintas. | Consulta usa `COUNT(DISTINCT all_v.fileName)`, desduplicando cópias idênticas; desempate do representante inclui `id ASC` para determinismo. |
| **R8 [P2]** | Busca restrita em `INSTALLED_ONLY` abria modal com variantes não instaladas do catálogo. | Escopo `INSTALLED_ONLY` é propagado ao clique: se houver 1 versão instalada, abre direto; se houver $\ge 2$, o modal carrega exclusivamente variantes instaladas. |
| **R9 [P2]** | Parâmetros de armazenamento vazios causavam SQL incorreto e transição de escopo na navegação era ambígua. | Exigida configuração não vazia de `romsPrefix` e `managedMarker` via `DirectoriesManager`. Política de navegação formalizada: Instalados $\rightarrow$ Busca ativa `INSTALLED_ONLY`; chip remove filtro mantendo texto; troca de aba restaura `ALL`. |

---

## 8. Matriz de Aceite e Validação Integral

- [x] **Catálogo puro:** antes e depois da reescrita de URIs sentinela, nenhum placeholder aparece como instalado.
- [x] **ROMs locais na pasta gerenciada:** arquivos copiados manualmente aparecem como instalados com `downloadedAt = 0`, sem atividade fictícia.
- [x] **Desconexão de SD/SAF:** remoção física do SD ou revogação de permissão exibe indicador de mídia desconectada na UI; remontagem recupera o jogo sem perda de histórico (`lastPlayedAt`), saves ou auxiliares CUE/BIN.
- [x] **Limpeza segura:** rescan com provedores com erro ou vazios não apaga jogos nem dependências de mídias ausentes.
- [x] **Exclusão segura:** excluir ROM local externa apaga estritamente o arquivo correspondente, sem executar `deleteRecursively` na pasta pai.
- [x] **Concorrência download vs descoberta:** downloads parciais/em fila não aparecem como instalados; conclusão grava data real e `INSERT OR IGNORE` não a substitui por zero.
- [x] **Concorrência scan vs lançamento:** escritas parciais de `lastPlayedAt` e `lastIndexedAt` não se sobrescrevem mutuamente.
- [x] **Desduplicação de cópias:** a mesma ROM em dois volumes conta como 1 única versão lógica no card.
- [x] **Variantes na busca:** buscar por arquivo (ex: "USA") mantém a contagem total de variantes do grupo e o seletor com todas as versões instaladas.
- [x] **Busca global restrita:** `LIMIT 100` é aplicado após o filtro de instalados; consultas com `null`, lista vazia, 1 ID e múltiplos IDs executam sem erro; chip `[x] Jogos Instalados` permite alternar para o catálogo completo.
- [x] **Consistência de clique na busca restrita:** abrir resultado da busca restrita não oferece variantes não baixadas do catálogo.
- [x] **Validação em compilação:** build/kapt valida Room, `@RawQuery`, CTEs e compatibilidade Android API 21+.
