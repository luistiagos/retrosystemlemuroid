# [BACKLOG] Cadeia de fallbacks multi-fonte para download on-demand (estilo RetroBatNew)

**Status:** pendente — colocado em backlog por decisão do dono em 2026-11-13 (ainda sem previsão de início; requer análise prévia dos `source_id`s/hosters ativos nos sistemas do Lemuroid e mantém `.rar` fora de escopo nesta etapa).

**Data:** 2026-11-13  
**Origem:** Diagnóstico feito após adicionar novas ROMs de GameCube diretamente por URL do Archive.org em `source_id=1`, contrastando o comportamento do `RomOnDemandManager.kt` no Lemuroid com o pipeline multi-fonte do RetroBatNew (`RomDownloadManager.Chain.cs` e auxiliares).

---

## Problema atual

Hoje o Lemuroid resolve qualquer ROM on-demand usando **uma única chamada e um único espelho**:

- `RomOnDemandManager.kt` (`downloadRom()` e `ensureArcadeDependencies()`) consulta fixamente:
  ```text
  GET https://emuladores.pythonanywhere.com/find_by_file?path=<fileName>&source_id=1&system=<endpointSystem>
  ```
- Isso obriga qualquer jogo no catálogo do Lemuroid a existir especificamente em `source_id=1` — mesmo quando existem outras fontes na tabela `ROM`/`SOURCE` (Archive.org, HuggingFace secundário, Myrient, hosters HTTP etc.).
- Se esse único link retornar `401`, `403`, `404`, estiver truncado no servidor, vier em formato compactado diferente do placeholder (ex.: `.7z`/`.zip`/`.rvz` para placeholder `.iso`) ou o `pythonanywhere` oscilar, o download falha de vez.
- `downloadToFile()` faz até 5 retries na **mesma URL** do zero (sem `Range`/retomada e sem troca por fonte lenta), apagando o `.download` no erro.
- `ensureArchiveSession()` / `ArchiveCookieJar` depende exclusivamente de `BuildConfig.ARCHIVE_EMAIL` e `BuildConfig.ARCHIVE_PASSWORD` embutidos em tempo de build (`lemuroid-app/build.gradle.kts`), sem busca/renovação dinâmica em `/api/credentials?domain=archive.org`.

---

## Referência técnica verificada no RetroBatNew

Arquivos analisados em `c:\projects\retrobatnew\source\emulatorlauncher\emulatorLauncher\`:

| Componente | Papel no RetroBatNew |
|---|---|
| `RomDownloadManager.Chain.cs` | Orquestrador da cadeia `repo,hf,alias,alt,minerva`, deduplicação `UrlDedupeKey`, preservação de erro diagnóstico e circuit breaker `RepoRespondendo` (`RepoMudoTtl = 120s`) |
| `RomDownloadManager.cs` | `BuildFallbackUrls`, `DownloadWithResume`, `DownloadAndMaybeExtract`, `FixDiscImageExtensionByContent`, `GetArchiveOrgCredentials`, `BuildAlternateDownloadRequests` |
| `HfSystemPaths.cs` + `hf-system-paths.json` | Mapa de pastas reais de cada sistema no HuggingFace com refresh oportunista em background (`GET /api/systems/hf_paths`, validade de 7 dias) |
| `RomDownloadManager.SlowSource.cs` | `SlowSourceMonitor` / `SlowSourcePolicy`: troca de espelho quando lento (< 250 KB/s ou ETA > 45 min após 45s grace + 20s confirmação, em >= 8 MB), estacionando `.partial` (`ParkPartial`) |
| `RomDownloadManager.Resolvers.cs` (+ 16 parciais de hosters) | Despacho `RequiresDirectLinkResolution` / `ResolveDirectDownloadUrl` para encurtadores e hosters indiretos |
| `RomDownloadManager.Transfer.cs` | Validação forte de `ETag`/`Content-Range` + `SegmentedReadStream` (2–4 conexões para arquivos >= 64 MB) |
| `Common/RomContainerCheck.cs` | Verificação de cabeçalho `.rvz`/`.wia` (`wia_file_size` em `0x2C`) e `.chd` v5 comprimido contra upload truncado no servidor |

---

## Plano arquitetural por fases para o Lemuroid

### Fase 1 — Cadeia multi-fonte (`/api/roms/download_sources`) + Circuit Breaker + Archive.org dinâmico
- **Endpoint primário de fontes:** trocar o lookup primário de `/find_by_file?source_id=1` por:
  ```text
  GET https://emuladores.pythonanywhere.com/api/roms/download_sources?path=<fileName>&system=<endpointSystem>
  ```
  que retorna `sources` ordenados por `source_id` crescente (`source_id`, `link`, `format`, `senha`).
- **Fallback legado dentro do grupo `repo`:** só chamar `/find_by_file?...&source_id=1&system=...` se `download_sources` devolver lista vazia (aplicar a mesma lógica em `ensureArcadeDependencies()`).
- **Loop unificado multi-candidato:**
  - Avançar para o próximo candidato/servidor em `401`, `403`, `404`, falha de hoster ou erro pós-download, em vez de encerrar na primeira URL.
  - Desduplicar URLs entre grupos com equivalente ao `UrlDedupeKey` (decodificando path e ignorando `?download=...` no `huggingface.co`).
  - Preservar o erro mais informativo quando os mirrors finais retornarem apenas `404` (`IsFileAbsentError`).
  - Abortar imediatamente apenas em cancelamento do usuário (`CancellationException`) ou falta de espaço em disco.
- **Circuit Breaker de 120s:** abrir por 2 min em timeout, falha de conexão ou erro DNS no `emuladores.pythonanywhere.com` (nunca em `404`/`429`/`500`), pulando `repo` (e `alias`) direto para o grupo `hf`.
- **Archive.org dinâmico:** se `BuildConfig.ARCHIVE_EMAIL` / `BuildConfig.ARCHIVE_PASSWORD` estiverem vazios ou falharem com `401`/`403` após renovação de sessão, consultar `GET https://emuladores.pythonanywhere.com/api/credentials?domain=archive.org` (`user`, `password`).

### Fase 2 — Grupo `hf` com `hf-system-paths.json`
- Embutir cópia inicial de `hf_system_paths.json` em `lemuroid-app/src/main/assets/` e atualizar em cache local (`filesDir`) em background via `GET /api/systems/hf_paths` (TTL de 7 dias, sem bloquear boot/download).
- **Normalização e dupla consulta (`systemId` + `endpointSystem`):**
  - Fazer lookup case-insensitive no mapa (por ex., `mame2003Plus` vs `mame2003plus`).
  - Consultar prefixos usando **tanto** `game.systemId` quanto `RomSystemMapper.toEndpointSystem(context, game.systemId)`: no Lemuroid (`mnemonico_map.json`), sub-sistemas arcade como `cps1`, `cps3` e `neogeo` mapeiam para `fbneo`, mas no `hf-system-paths.json` possuem pastas próprias no HuggingFace; e `SystemID.SEGACD` usa `dbname = "scd"` contra `megacd`.
- Ordem de geração de URLs no grupo `hf`: `HfSystemPaths(game.systemId)` -> `HfSystemPaths(endpointSystem)` -> `https://huggingface.co/datasets/luisluis123/lemusets/resolve/main/roms/{sys}/{file}` -> `https://huggingface.co/datasets/luistiagos/roms/resolve/main/{sys}/{file}`.

### Fase 3 — Grupo `alias`, compactação `.zip`/`.7z`, Room sync e validação de container
- **Tentativa com extensão compactada no `repo`:** se `download_sources` devolver vazio para `game.fileName`, tentar `baseName + ".7z"` e `baseName + ".zip"` (respeitando `GameSystem.supportedExtensions` em `GameSystem.kt`).
- **Grupo `alias` (`/api/roms/by_alias`):**
  - Consultar `GET /api/roms/by_alias?system=<endpointSystem>&path=<fileName>` para recuperar entradas do mesmo jogo em outro formato ou região.
  - Filtrar `(Part N)` incompatível; separar aliases PT/BR e adiar aliases cross-region para o final da fila quando houver candidatos anteriores.
- **Extração no Android:**
  - `.zip`: expandir a lógica hoje restrita a `extractMultiDiscZipIfNeeded` para extrair também arquivos single-ROM quando `GameSystem.supportedExtensions` não listar `"zip"` ou o formato pedido/entregue exigir extração.
  - `.7z`: usar `org.apache.commons.compress.archivers.sevenz.SevenZFile` (a dependência `org.apache.commons:commons-compress:1.26.1` já existe em `lemuroid-app/build.gradle.kts`).
  - **`.rar` explicitamente ADIADO (ver seção dedicada abaixo).**
- **Sincronização atômica no banco Room (`games` + `downloaded_roms`):**
  - Quando `alias` ou extração alterar nome/extensão (ex.: placeholder `.iso` receber `.rvz`, ou `.7z` extrair `.chd`), atualizar `Game.fileName` / `Game.fileUri` (`GameDao`) e `DownloadedRom` (`DownloadedRomDao`) mantendo consistência de chaves `(systemId, fileName)` e `fileUri` único, retornando o `Game` atualizado em `DownloadResult.Success(updatedGame)` (já esperado por `RomDownloadDialog.kt`, `TVRomDownloadDialog.kt` e `SaveQueueManager.kt`).
  - Reavaliar no `ManifestQuickLoader.kt` como garantir que futuros bumps de `MANIFEST_SCHEMA_VERSION` não criem duplicata quando o arquivo baixado mudou de extensão via alias.
- **Integridade de container + sniffing de extensão:**
  - Portar verificação de truncamento de cabeçalho para `.rvz`/`.wia` (u64 BE no offset `0x2C`) e `.chd` v5 comprimido (`RomContainerCheck`), além de sniff `.chd` / `.iso` / `.bin` quando a extensão da URL/path divergir dos bytes entregues. Em truncamento confirmado, descartar o arquivo e seguir para a próxima fonte da cadeia.

### Fase 4 — Retomada por `Range`, Slow-Source Switching, Hosters HTTP e UI
- **Retomada entre tentativas/pausas (`Range` + sidecar `.partial.json`):** guardar `url`, `expectedSize`, `eTag`, `lastModified`; enviar `Range` + `If-Range`; checar espaço livre via `StatFs` antes de baixar arquivos grandes (GameCube/Dreamcast/PSP/3DS). *(Observação: `RomsDownloadManager.kt` já implementa `Range` + `206`/`416`/`429` em outra frente e pode servir de referência de padrões OkHttp.)*
- **Slow-Source Switching adaptado para Android:** monitoramento de taxa média + ETA máxima, estacionando parciais por URL (`ParkPartial`) para poder alternar entre `source_id`s lentos sem perder o que já foi baixado.
- **Hosters indiretos:** portar resolvers HTTP puros em Kotlin/OkHttp conforme o levantamento prévio (item 1 das pendências abaixo).
- **UI (opcional):** exibir status neutro de tentativa de fonte no progresso (`"Conectando ao servidor 1..."` / `"Servidor 2"` em `strings.xml`), sem expor domínios ou `source_id`s internos.

---

## Decisões explícitas e pendências em aberto (2026-11-13)

### 1. [A ANALISAR] Mapeamento dos `source_id`s / hosters para sistemas do Lemuroid
- Antes de implementar resolvers na Fase 4 (e mesmo para decidir o filtro de URLs utilizáveis nas Fases 1–3), **é necessário auditar** no banco `romsrepository` (`ROM` + `SOURCE`) quais `source_id`s e domínios aparecem de fato nos **82 sistemas de `SystemID.kt`** presentes no Lemuroid (ex.: `gc`, `psp`, `psx`, `dc`, `saturn`, `3ds`, `nds`, `n64`, `mame289`, `naomi`, `amiga`, etc. — excluindo sistemas apenas de desktop como `ps2`, `switch`, `xbox`, `wii`).
- Objetivos dessa análise futura:
  1. Medir quantos títulos do Lemuroid existem em cada `source_id` (HuggingFace, Archive.org, Myrient/direto, Google Drive, MediaFire, PixelDrain, Gofile, MegaDB, 1fichier, encurtadores etc.).
  2. Definir quais resolvers HTTP puros (OkHttp) valem o esforço no Android e quais links devem ser ignorados limpo na cadeia caso exijam navegador/JS pesado ou container não suportado.

### 2. [NÃO IMPLEMENTAR AGORA] Suporte a arquivos `.rar`
- **Decisão do dono (2026-11-13):** o Lemuroid **não terá suporte a `.rar` nesta etapa**.
- **Comportamento enquanto `.rar` estiver em backlog:**
  - Não adicionar biblioteca nova de descompressão `.rar`/RAR5 no Gradle por enquanto.
  - Na Fase 1/3, candidatos cujo destino exigiria extrair um `.rar` (`link` terminando em `.rar`, `format == "rar"` ou fallback `ArchiveLookupExtensions`) devem ser filtrados/ignorados quando o sistema não suportar `.rar` nativamente, evitando baixar centenas de MB que o app não conseguiria extrair — pulando direto para outros `source_id`s, `hf` ou `alias` em `.zip`/`.7z`/`.rvz`/`.chd`/`.iso`.
- **Nota técnica para quando `.rar` entrar no escopo:**
  - `org.apache.commons:commons-compress:1.26.1` **não** extrai `.rar`.
  - Bibliotecas Java puras comuns (como `junrar`) suportam apenas RAR v4 e falham em arquivos **RAR5**.
  - Como muitos pacotes no catálogo usam RAR5 (ou múltiplos volumes `.part1.rar`), o suporte completo exigirá uma dependência nativa/JNI no Android (ex.: wrapper JNI para `libarchive`, `unrar` C++ SDK oficial ou `7-Zip-JBinding` compilado para as ABIs do app: `arm64-v8a`, `armeabi-v7a`, `x86_64`), avaliando impacto em tamanho do APK, licença e streaming de extração no scoped storage/cache.

### 3. Fora de escopo permanente / específico de Desktop (RetroBatNew)
- **Minerva / Torrent (`aria2c.exe`)**: não aplicável neste plano mobile.
- **Fallback TLS via `curl.exe`** para Windows 7/8.1: o Lemuroid já usa OkHttp + Conscrypt (`ConscryptOkHttpHelper`).
- **Conversor NSZ e ajustes específicos de Switch/PS2**: sistemas ausentes no Lemuroid.
- **WebView/Chromium fallback (`Modsfire s4` Challenge):** evitar no pipeline inicial a menos que a análise de `source_id`s mostre real necessidade.

---

## Riscos e cuidados arquiteturais ao sair do backlog

- **Chave `(systemId, fileName)` no Room vs mudança de extensão via `alias`/`.7z`:** sem cuidado ao atualizar `games` + `downloaded_roms` + `ManifestQuickLoader`, um arquivo baixado como `.rvz` para um placeholder `.iso` pode virar card duplicado na atualização do catálogo.
- **Armazenamento externo (SAF / `DocumentFile`) vs interno (`filesDir`):** extração `.7z` via `SevenZFile` exige `File`/`SeekableByteChannel` seekable — ou seja, baixar e extrair primeiro em staging local do app e só depois mover/copiar para o destino final (especialmente se o usuário usar pasta externa via SAF).
- **Espaço temporário duplo:** ao extrair `.zip`/`.7z` grandes (GameCube/Dreamcast/PSP), validar `StatFs` considerando o tamanho do archive + conteúdo extraído + margem de segurança.
