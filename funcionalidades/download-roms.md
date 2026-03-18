# Download de Pacote de ROMs

> **Documento de especificação — estado atual da implementação.**
> Para o histórico detalhado de bugs e correções veja:
> [`correcoes-2026-03-16.md`](correcoes-2026-03-16.md) e [`correcoes-2026-03-17.md`](correcoes-2026-03-17.md).

---

## Descrição

Funcionalidade que baixa um único arquivo `.7z` (~5 GB) do HuggingFace, extrai, normaliza as
pastas para os dbnames do Lemuroid e aciona o scan de biblioteca. Aparece como:
- **Card na Home** sempre que não há jogos no DB e não há indexação em andamento.
- **Dialog de confirmação** na primeira vez (ou após deleção de ROMs).

---

## Arquivos envolvidos

| Arquivo | Caminho | Responsabilidade |
|---------|---------|-----------------|
| `RomsDownloadManager.kt` | `…/app/shared/roms/` | Orquestrador central: download, extração, normalização, flags |
| `RomsDownloadWork.kt` | `…/app/shared/roms/` | `CoroutineWorker` do WorkManager que chama `doDownload()` |
| `HomeViewModel.kt` | `…/feature/home/` | Estado da UI, dialog, fluxo combinado de jogos+indexação+download |
| `HomeScreen.kt` | `…/feature/home/` | Card Compose com barra de progresso, botão cancelar e AlertDialog |
| `strings.xml` / `strings-pt-rBR.xml` | `res/values/` | Strings da UI do card e dos dialogs |

---

## Fluxo de execução completo

### 1. Acionamento

O usuário confirma o dialog **ou** clica em "Download ROMs" no card.
`HomeViewModel.downloadAndExtractRoms()` → `RomsDownloadManager.downloadAndExtract()`:
1. Grava `PREF_DOWNLOAD_STARTED = true`.
2. Chama `RomsDownloadWork.enqueue()` com `ExistingWorkPolicy.KEEP` (sem duplicatas).

### 2. Auto-resume ao reabrir o app

O bloco `init` de `RomsDownloadManager` roda toda vez que o ViewModel é criado.
Se `PREF_DOWNLOAD_STARTED = true` e `!isDownloadDone()` → re-enfileira o trabalho
automaticamente, retomando de onde parou.

### 3. `doDownload()` — fase de download

**Se `PREF_ARCHIVE_DOWNLOADED = true` E arquivo `.7z` existe e tem tamanho > 0:**
→ Pula o download (extração foi interrompida na run anterior).
→ Deleta diretórios parcialmente extraídos.
→ Vai direto para a fase de extração.

**Caso contrário:**
→ Deleta qualquer conteúdo extraído anterior (preserva o `.seg*` e o `.7z` parcial).
→ Executa `downloadFileParallel()`.
→ Grava `PREF_ARCHIVE_DOWNLOADED = true`.

**Download paralelo (`downloadFileParallel`):**
- Faz um `HEAD` para obter `Content-Length` e verificar `Accept-Ranges: bytes`.
- Se o servidor não suportar ranges ou não retornar tamanho → fallback para `downloadFile()` (single-connection).
- Divide o arquivo em **4 segmentos** disjuntos; cada um baixado em paralelo via
  `coroutineScope { async { … } }.awaitAll()`.
- Cada segmento é salvo em `roms_download.7z.seg0`–`.seg3`.
- Resume por segmento: verifica o tamanho do arquivo `.seg*` e envia
  `Range: bytes=<bytes_já_baixados>-<fim_do_segmento>`.
- Após todos completarem: concatena os segmentos no `.7z` final e deleta os `.seg*`.

**Retry:** até 5 tentativas por segmento com backoff linear (5s, 10s, 15s, 20s, 25s).
`CancellationException` é sempre re-lançada antes do catch genérico.
HTTP 416 → segmento já completo (não é erro). HTTP 206 → append no `.seg*`.

### 4. `doDownload()` — fase de extração

```
extractSevenZ(archiveFile, romsDir) { progress -> ... }
```

- Itera entradas do `.7z` via `SevenZFile.builder()`.
- Valida cada entrada contra `canonicalPath` para prevenir path traversal.
- Buffer de leitura: 32 KB.
- Progresso reportado de 0f a 0.99f durante a extração; 1f ao finalizar.

**Tratamento de falha durante extração:**
- `CancellationException` → re-lança (preserva arquivo `.7z` e flag `PREF_ARCHIVE_DOWNLOADED`
  para que a próxima run retome a extração sem re-baixar).
- Qualquer outra `Exception` → **deleta o arquivo `.7z`** + limpa `PREF_ARCHIVE_DOWNLOADED = false`
  + re-lança. Garante que o próximo retry faça um download fresco (sem loop infinito).

### 5. `doDownload()` — normalização

1. `archiveFile.delete()` — libera espaço.
2. `normalizeExtractedFolders(romsDir)`:
   - **`unwrapWrapperFolders()`** — se `romsDir` contém exatamente um subdiretório cujo nome
     não está em `FOLDER_NAME_MAP` **nem** em `SYSTEM_DBNAMES`, move o conteúdo um nível acima
     e deleta o wrapper. Repete recursivamente para múltiplos níveis de wrapping.
   - **`renameSystemFoldersRecursive()`** — percorre toda a árvore em profundidade; para cada
     pasta cujo nome (lowercase) esteja em `FOLDER_NAME_MAP`, renomeia para o dbname
     correspondente. Conflitos são resolvidos por merge via `safeMoveFile()`.

### 6. `doDownload()` — cópia SAF (opcional)

Se o usuário configurou um diretório externo (SAF):
- Lista todos os arquivos em `romsDir`.
- Copia cada um para a árvore SAF via `copyFileToSaf()` (recria estrutura de pastas).
- Deleta `romsDir` e recria vazia.

### 7. Conclusão

```
LibraryIndexScheduler.scheduleLibrarySync(appContext)
prefs: PREF_DOWNLOAD_DONE=true, PREF_DOWNLOAD_STARTED=false,
       PREF_ARCHIVE_DOWNLOADED=false, PREF_EXTRACTION_VERSION=EXTRACTION_VERSION
```

---

## SharedPreferences (`home_download_prefs`)

| Chave | Tipo | Ciclo de vida |
|-------|------|--------------|
| `download_done` | Boolean | `true` ao concluir; limpo ao cancelar ou quando `EXTRACTION_VERSION` muda |
| `download_started` | Boolean | `true` ao enfileirar; `false` ao concluir, cancelar **ou falhar definitivamente** |
| `archive_downloaded` | Boolean | `true` após `.7z` baixado; `false` ao concluir, cancelar ou falha de extração |
| `extraction_version` | Int | Versão da lógica de extração gravada; comparada com `EXTRACTION_VERSION = 6` |

**`EXTRACTION_VERSION = 6`** — se o valor armazenado for menor que 6, o `initialState`
é forçado para `Idle` e `PREF_DOWNLOAD_DONE` é limpo, obrigando re-download.
Deve ser incrementado sempre que a lógica de extração/normalização mudar de forma
incompatível com extrações anteriores.

---

## Estados da UI (`DownloadRomsState`)

| Estado | O que mostra no card |
|--------|---------------------|
| `Idle` | Mensagem descritiva + botão "Download ROMs" |
| `Downloading(progress)` | Texto "Baixando… X%" + `LinearProgressIndicator` + botão "Cancelar" |
| `Extracting(progress)` | Texto "Extraindo… X%" + `LinearProgressIndicator` + botão "Cancelar" |
| `Done` | Mensagem de sucesso + botão "Baixar novamente" |
| `Error(message)` | Mensagem de erro + botão "Tentar novamente" |

O card é visível quando `downloadRomsState !is Done`.

**`getDownloadRomsState()` em `HomeViewModel`:**
Combina `romsDownloadManager.state` + `noGamesFlow` + `indexingFlow`.
Override `Done → Idle` ocorre quando:
```
dlState == Done && noGames && !isIndexing && !isDownloadStarted()
```
Isso garante que o card/dialog reapareça se o usuário deletar as ROMs depois de um
download concluído, sem piscar durante a varredura de biblioteca pós-download.

---

## Dialog de confirmação

Aparece quando `showDownloadPromptDialog = true`:
```
showNoGamesCard && !indexInProgress && !isDownloadStarted() && !dismissed
```
- `showNoGamesCard` — DB sem jogos (favorites + recents + discovery = 0)
- `!indexInProgress` — evita piscar durante varredura pós-download (DB temporariamente vazio)
- `!isDownloadStarted()` — download já em andamento, não perguntar de novo
- `!dismissed` — usuário fechou o dialog nesta sessão

---

## Botão Cancelar

Disponível nos estados `Downloading` e `Extracting`.
Ao clicar → `AlertDialog` com duas opções:
- **Confirmar:** `HomeViewModel.cancelDownload()` → `viewModelScope.launch(Dispatchers.IO)` →
  `romsDownloadManager.cancelDownload()`:
  1. `WorkManager.cancelUniqueWork(UNIQUE_WORK_ID)`
  2. `romsDir.deleteRecursively()` (deletado em IO thread para evitar ANR)
  3. Limpa `PREF_DOWNLOAD_STARTED`, `PREF_ARCHIVE_DOWNLOADED`, remove `PREF_DOWNLOAD_DONE`
  4. `downloadDialogDismissed.value = true` (dialog não reaparece na mesma sessão)
- **Descartar:** fecha o AlertDialog sem ação.

---

## Mapeamento de pastas (`FOLDER_NAME_MAP`)

50+ entradas mapeando nomes legíveis (lowercase) para dbnames do Lemuroid.
Exemplos:

| Nome no arquivo | dbname |
|-----------------|--------|
| "mega drive", "genesis", "megadrive", "sega megadrive" | `md` |
| "super nintendo", "snes", "super nes" | `snes` |
| "playstation", "psx", "ps1", "ps one" | `psx` |
| "sega cd", "mega cd", "segacd", "sega-cd" | `scd` |
| "mame", "mame 2003", "mame2003plus" | `mame2003plus` |
| "game boy advance", "gameboy advance" | `gba` |
| "nintendo ds", "nds" | `nds` |
| "nintendo 64", "n64" | `n64` |

`SYSTEM_DBNAMES` — conjunto com os 25 dbnames válidos (`nes`, `snes`, `md`, `gb`, `gbc`,
`gba`, `n64`, `sms`, `psp`, `nds`, `gg`, `atari2600`, `psx`, `fbneo`, `mame2003plus`,
`pce`, `lynx`, `atari7800`, `scd`, `ngp`, `ngc`, `ws`, `wsc`, `dos`, `3ds`).
Usado em `unwrapWrapperFolders` para não tratar pastas já corretamente nomeadas como wrappers.

---

## Sistemas que dependem de detecção por pasta

Estes sistemas não têm extensões únicas no Lemuroid — seus arquivos só são reconhecidos
se estiverem em pastas corretamente nomeadas:

| Sistema | dbname | Nomes aceitos em FOLDER_NAME_MAP |
|---------|--------|----------------------------------|
| PSX | `psx` | "playstation", "psx", "ps1", "ps one" |
| PSP | `psp` | "psp", "playstation portable" |
| Sega CD | `scd` | "sega cd", "mega cd", "segacd", "megacd", "sega-cd", "mega-cd" |
| FBNeo | `fbneo` | "arcade" |
| MAME | `mame2003plus` | "mame", "mame 2003", "mame2003", "mame 2003 plus", "mame2003plus" |
| NDS | `nds` | "nintendo ds", "nds" |
| N64 | `n64` | "nintendo 64", "n64" |

---

## OkHttp — configurações

| Parâmetro | Valor |
|-----------|-------|
| `connectTimeout` | 30 s |
| `readTimeout` | 0 (sem timeout — necessário para arquivos grandes) |
| `followRedirects` | true |
| `ConnectionPool` | 8 conexões, 5 min de keep-alive |
| Buffer I/O (`downloadFile`, `downloadSegment`) | 256 KB |
| Buffer I/O (`extractSevenZ`) | 32 KB |
| SSL bypass | Apenas em `BuildConfig.DEBUG` (emuladores com CAs desatualizadas) |

---

## Histórico de correções

| Versão | Arquivo | Detalhes |
|--------|---------|---------|
| 2026-03-16 | Múltiplos | Correções críticas de deleção silenciosa, mapeamentos faltantes, detecção substring. Ver [`correcoes-2026-03-16.md`](correcoes-2026-03-16.md) |
| 2026-03-17 | Múltiplos | Download paralelo, auto-resume, cancelar, ANR, loop infinito, PREF_DOWNLOAD_STARTED em failure. Ver [`correcoes-2026-03-17.md`](correcoes-2026-03-17.md) |
