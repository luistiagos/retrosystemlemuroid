# Manifest-First Catalog Loader

**Data:** 2026-05-06
**Tipo:** Refatoração de performance / arquitetura de carga do catálogo
**Status:** implementado

---

## 1. Motivação

Antes desta mudança, ao iniciar o app pela primeira vez (ou após download de ROMs), o catálogo só aparecia depois que `LibraryIndexWork` rodasse o scan completo via LibretroDB para cada arquivo:

1. Listar arquivos ROM no diretório
2. Para cada arquivo, executar 7 estratégias de detecção no LibretroDB (CRC, Serial, Filename, Path+Filename, Unique extension, Known system, Path+SupportedExt)
3. Calcular URL de thumbnail do libretro
4. Se sem thumbnail, fallback para `catalog_manifest.txt`
5. Inserir Game no DB
6. UI exibe catálogo

Com `catalog_manifest.txt` cobrindo a maioria das capas (22.301 entradas em 18 sistemas), o passo (2-3) virou trabalho redundante e lento — o usuário esperava o scan terminar antes de ver qualquer coisa.

O projeto irmão **ARMSX2** resolve isso lendo o manifest direto e exibindo o grid imediatamente. Portamos a mesma estratégia.

---

## 2. Arquitetura

### 2.1 Fluxo novo (catálogo)

```
App Start
  └─ MainProcessInitializer
       └─ ManifestQuickLoader.load() (background, IO dispatcher)
            ├─ CatalogCoverProvider.getAllEntries()
            │    → Map<"systemId/fileName", coverUrl>
            ├─ romsDir.walkTopDown() filtrando por extensões suportadas
            ├─ Para cada arquivo:
            │    key = "${file.parentFile.name}/${file.name}"
            │    if manifest[key] != null:
            │      criar Game(systemId, fileName, title, coverUrl)
            └─ GameDao.insertIfNotExists(games)  ← INSERT OR IGNORE
                 └─ UI exibe catálogo IMEDIATAMENTE
```

### 2.2 Fluxo de scan completo (preservado)

`LibraryIndexWork` (com LibretroDB) continua rodando, mas **apenas para casos onde o arquivo não casa com o manifest**:

| Trigger | Quem chama | Mantido como scan completo? |
|---------|------------|-----------------------------|
| Folder picker (SAF) | `StorageFrameworkPickerLauncher` | Sim — `scheduleManualLibrarySync` |
| Mount SD/USB | `MediaMountedReceiver` | Sim — `scheduleManualLibrarySync` |
| Botão "Rescan" das settings | `SettingsScreen` / `TVHomeFragment` | Sim — `scheduleLibrarySync` |
| Mudança de pasta nas settings | `SettingsInteractor` | Sim — `scheduleLibrarySync` |
| "ROM não encontrada → Rescan" | `GameLauncher` | Sim — `scheduleLibrarySync` |
| Download do catálogo via HuggingFace | `RomsDownloadManager` | **Não** — agora `triggerCatalogQuickLoad` |
| Streaming do catálogo | `StreamingRomsManager` | **Não** — agora `triggerCatalogQuickLoad` |
| Download on-demand | `RomOnDemandManager` | **Não** — agora `triggerCatalogQuickLoad` |

**Princípio:** se o arquivo veio do nosso catálogo curado, manifest é fonte da verdade — não precisa LibretroDB. Se o usuário trouxe ROMs de fora, scan completo.

### 2.3 Match sem ambiguidade

ROMs do catálogo são organizadas em subpastas por dbname:
```
{romsDir}/snes/Super Mario World (USA).sfc
{romsDir}/nes/Super Mario Bros.nes
```

Manifest usa o mesmo formato:
```
snes/Super Mario World (USA).sfc|https://...
nes/Super Mario Bros.nes|https://...
```

Match: `parentFolder.name + "/" + fileName` = chave do manifest. Sem colisão entre sistemas.

---

## 3. Componentes alterados

### 3.1 `CatalogCoverProvider.kt` (modificado)

Adicionado `getAllEntries(): Map<String, String>` para expor o map completo. O método existente `getCoverUrl(systemId, fileName)` continua funcionando para o fallback do `CatalogFallbackMetadataProvider`.

**Caminho:** `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/catalog/CatalogCoverProvider.kt`

### 3.2 `ManifestQuickLoader.kt` (novo)

Componente central. Single method `suspend fun load(): Int`.

**Caminho:** `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/catalog/ManifestQuickLoader.kt`

**Responsabilidades:**
- Walk recursivo no `directoriesManager.getInternalRomsDirectory()`
- Filtro por `GameSystem.getSupportedExtensions()`
- Extração de `systemId` via parent folder name
- Validação contra `GameSystem.findByIdOrNull(systemId)` — descarta pastas que não são sistemas conhecidos
- Lookup no manifest map; descarta se não casar
- Construção de `Game` com:
  - `fileName` = nome do arquivo
  - `fileUri` = URI `file://...`
  - `title` = nome sem extensão (similar ao ARMSX2)
  - `systemId` = nome da pasta pai
  - `coverFrontUrl` = URL do manifest
  - `developer` = null (enriquecimento posterior só se houver scan completo)
  - `lastIndexedAt` = `System.currentTimeMillis()`
- Bulk insert via `gameDao.insertIfNotExists` (idempotente)

**Garantia de idempotência:** `INSERT OR IGNORE` na coluna `fileUri` (UNIQUE index existente). Rodar duas vezes não duplica nem sobrescreve.

### 3.3 `GameDao.insertIfNotExists` (novo)

```kotlin
@Insert(onConflict = OnConflictStrategy.IGNORE)
suspend fun insertIfNotExists(games: List<Game>): List<Long>
```

**Caminho:** `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/db/dao/GameDao.kt`

**Por que IGNORE e não REPLACE:**
- Se um game já foi enriquecido pelo LibretroDB scan (folder picker), tem `developer`, `title` limpo, etc.
- Quick load não tem esses dados — só tem o que o manifest dá.
- IGNORE preserva o registro enriquecido.

### 3.4 `LibraryIndexScheduler.triggerCatalogQuickLoad` (novo)

Helper estático suspend que recupera `ManifestQuickLoader` da `LemuroidApplication` e chama `load()`. Encapsula o pattern para os download managers.

**Caminho:** `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/library/LibraryIndexScheduler.kt`

```kotlin
suspend fun triggerCatalogQuickLoad(applicationContext: Context) {
    val app = applicationContext.applicationContext as? LemuroidApplication ?: return
    app.manifestQuickLoader.load()
}
```

### 3.5 DI (Dagger)

**`LemuroidApplicationModule.kt`** — adicionados dois `@Provides`:

```kotlin
@Provides @PerApp @JvmStatic
fun catalogCoverProvider(context: Context): CatalogCoverProvider = CatalogCoverProvider(context)

@Provides @PerApp @JvmStatic
fun manifestQuickLoader(
    directoriesManager: DirectoriesManager,
    catalogCoverProvider: CatalogCoverProvider,
    db: RetrogradeDatabase,
) = ManifestQuickLoader(directoriesManager, catalogCoverProvider, db)
```

`gameMetadataProvider` agora consome `CatalogCoverProvider` via DI em vez de instanciar localmente — uma única instância na aplicação.

**`LemuroidApplication.kt`** — campo injetado:

```kotlin
@Inject lateinit var manifestQuickLoader: ManifestQuickLoader
```

### 3.6 Startup

**`MainProcessInitializer.kt`** — bloco novo no `create()`:

```kotlin
GlobalScope.launch(Dispatchers.IO) {
    try {
        val app = context.applicationContext as? LemuroidApplication
        app?.manifestQuickLoader?.load()
    } catch (e: Throwable) {
        Timber.e(e, "MainProcessInitializer: manifest quick load failed")
    }
}
```

Roda em paralelo com o agendamento de `CoreUpdateWork` e `SaveSyncWork`. Não bloqueia main thread.

### 3.7 Substituições nos download managers

| Arquivo | Linhas | Antes | Depois |
|---------|--------|-------|--------|
| `RomsDownloadManager.kt` | 354, 377 | `scheduleLibrarySync(appContext)` | `triggerCatalogQuickLoad(appContext)` |
| `StreamingRomsManager.kt` | 339, 355, 396, 470, 487 | `scheduleLibrarySync(appContext)` | `triggerCatalogQuickLoad(appContext)` |
| `RomOnDemandManager.kt` | 205, 281 | `scheduleLibrarySync(context)` | `triggerCatalogQuickLoad(context)` |

---

## 4. Performance

### Antes
- ~22 mil ROMs no catálogo
- Cada ROM: 7 lookups no LibretroDB SQLite + cálculo de thumbnail URL
- Tempo estimado em dispositivo médio: dezenas de segundos a minutos
- UI bloqueada (catálogo vazio) até conclusão

### Depois
- Lazy load do manifest (HashMap em memória) — uma vez por processo
- Walk recursivo no diretório de ROMs (cheap)
- Lookup O(1) por arquivo
- Bulk INSERT OR IGNORE
- Tempo estimado: < 1 segundo para milhares de jogos
- UI exibe catálogo imediatamente

---

## 5. Compatibilidade e edge cases

### 5.1 Game já enriquecido pelo LibretroDB
`INSERT OR IGNORE` em `fileUri` UNIQUE → registro enriquecido preservado.

### 5.2 Arquivo no disco mas não no manifest
Quick load ignora silenciosamente. O scan completo (se disparado depois por folder picker, etc.) cobre esses casos.

### 5.3 Pasta com nome inválido como sistema
`GameSystem.findByIdOrNull(systemId) == null` → arquivo descartado. Walk só considera subpastas que correspondem a um dbname válido.

### 5.4 Manifest vazio ou ausente
`getAllEntries()` retorna mapa vazio (mesmo comportamento que `loadFromAssets` já tinha em IOException). `load()` early-returns 0.

### 5.5 ROMs dir não existe
Early return 0. Não cria a pasta — isso é responsabilidade do `DirectoriesManager` quando o app baixa ROMs.

### 5.6 Idempotência
Múltiplas chamadas de `load()` são seguras: arquivos já no DB são ignorados. Pode ser chamado a cada batch de download (StreamingRomsManager chama a cada 100 arquivos).

### 5.7 Cleanup de games removidos
Quick load **não** remove games cujos arquivos foram apagados. Esse cleanup continua a cargo do `LemuroidLibrary.indexLibrary()` quando rodar (folder picker, rescan manual). Para o fluxo do catálogo isso é aceitável: arquivos só somem se o usuário deletar via app, e nesse caso o `RomOnDemandManager.deleteRom` já trata.

---

## 6. Diagrama final

```
┌──────────────────────────────────────────────────────────────┐
│ App Start                                                    │
│   └─ MainProcessInitializer                                  │
│        └─ ManifestQuickLoader.load() ──────────┐             │
│                                                ▼             │
│                                    catalog_manifest.txt      │
│                                                ▼             │
│                                          GameDao             │
│                                       (INSERT OR IGNORE)     │
│                                                ▼             │
│                                          UI exibe catálogo   │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│ Catálogo download / streaming / on-demand                    │
│   └─ Após cada batch: triggerCatalogQuickLoad(ctx)           │
│        (mesmo caminho rápido — sem LibretroDB)               │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│ Folder picker / SD mount / Rescan manual                     │
│   └─ scheduleLibrarySync / scheduleManualLibrarySync         │
│        └─ LibraryIndexWork                                   │
│             └─ LemuroidLibrary.indexLibrary()                │
│                  └─ LibretroDB scan completo (CRC/Serial/    │
│                     filename) — único caminho que detecta    │
│                     sistema para ROMs fora do catálogo       │
└──────────────────────────────────────────────────────────────┘
```

---

## 7. Arquivos modificados (resumo)

| Arquivo | Tipo |
|---------|------|
| `retrograde-app-shared/.../catalog/CatalogCoverProvider.kt` | modificado |
| `retrograde-app-shared/.../catalog/ManifestQuickLoader.kt` | criado |
| `retrograde-app-shared/.../db/dao/GameDao.kt` | modificado |
| `lemuroid-app/.../LemuroidApplicationModule.kt` | modificado |
| `lemuroid-app/.../LemuroidApplication.kt` | modificado |
| `lemuroid-app/.../shared/startup/MainProcessInitializer.kt` | modificado |
| `lemuroid-app/.../shared/library/LibraryIndexScheduler.kt` | modificado |
| `lemuroid-app/.../shared/roms/RomsDownloadManager.kt` | modificado |
| `lemuroid-app/.../shared/roms/StreamingRomsManager.kt` | modificado |
| `lemuroid-app/.../shared/roms/RomOnDemandManager.kt` | modificado |

---

## 8. Referência cruzada: ARMSX2

| Conceito | ARMSX2 | Lemuroid (após esta mudança) |
|----------|--------|-------------------------------|
| Manifest | `catalog_manifest_ps2.txt` | `catalog_manifest.txt` |
| Parser | `CatalogParser.parse(context)` | `CatalogCoverProvider.getAllEntries()` |
| Match local | `markDownloaded(entries, romsDir)` | `ManifestQuickLoader.load()` |
| Multi-sistema | Não (só PS2) | Sim — via subpastas dbname |
| LibretroDB | Não usa | Reservado para imports manuais |
| Título | filename sem extensão | filename sem extensão |
