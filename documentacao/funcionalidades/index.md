# Funcionalidades — Índice

Documentação das funcionalidades implementadas no Lemuroid.

---

## Arquivos

### [`proporcao-tela.md`](proporcao-tela.md)
**Proporção da tela (Automática / 4:3 / 16:9 / 19:9 / 20:9 / 21:9 / Preencher)**

Lista de proporções alvo, no desenho adotado por DuckStation, Dolphin, PCSX2 e RetroArch —
"original" e "preencher" são casos particulares de uma proporção, não modos separados. O encaixe
acontece no renderer nativo (`VideoLayout::updateForegroundVertices`), então a feature exige
rebuild da `libretrodroid-patched.aar` a partir de `C:\projects\lemuroid\LibretroDroid-patched`.
Documenta o levantamento de mercado que motivou o desenho, o contrato do valor (`AUTO`/`FILL`/
explicita), por que o zoom-com-corte foi removido e por que isso dispensou o scissor.

---

### [`widescreen-cores-3d.md`](widescreen-cores-3d.md)
**Widescreen nos cores 3D (Dreamcast e N64)**

Opções que fazem o core **renderizar** imagem mais larga em vez de esticar ou cortar a imagem
4:3 pronta — o análogo dos widescreen patches do PCSX2. Documenta as chaves conferidas no
core de verdade (`reicast_widescreen_hack`, `mupen64plus-aspect`), por que console 2D nunca
pode ter isso, quais cores foram verificados e não têm, e como conferir a chave de um core.

---

### [`atualizacao-automatica.md`](atualizacao-automatica.md)
**Atualização automática do APK**

Descreve o fluxo de verificação, download e instalação de novas versões do app a partir do `version.json` publicado no R2 pelo `build-and-upload.ps1` (mesmo diretório dos APKs). Inclui o formato do anúncio com splits por ABI, verificação de SHA-256, throttle de 12h, estados da UI mobile e TV, `PackageInstaller`, `FileProvider` e preservação de dados.

---

### [`canais-catalogo-update.md`](canais-catalogo-update.md)
**Canais de catálogo e atualização**

Documenta como gerar APKs com `catalog_manifest` diferente e fluxo de update próprio por canal. Inclui parâmetros Gradle/PowerShell, endpoint por canal, comportamento com e sem `CatalogApplicationIdSuffix` e checklist de publicação.

---

### [`download-roms.md`](download-roms.md)
**Especificação atual — Download de pacote de ROMs**

Descreve o comportamento atual da funcionalidade de download automático de um pacote `.7z`
com ROMs. Inclui:
- Fluxo completo de execução (download → extração → normalização → scan)
- Download paralelo com 4 conexões (estilo aria2c) e resume automático
- Flags de SharedPreferences e mecanismo de versionamento (`EXTRACTION_VERSION = 6`)
- Estados da UI (`Idle`, `Downloading`, `Extracting`, `Done`, `Error`)
- Lógica do dialog de confirmação e supressão durante indexação
- Botão Cancelar com AlertDialog de confirmação
- `FOLDER_NAME_MAP` e `SYSTEM_DBNAMES` para normalização de pastas
- Configurações OkHttp

---

### [`selecionardiretorio.md`](selecionardiretorio.md)
**Seleção de diretório externo via SAF (Storage Access Framework)**

Descreve o fluxo de seleção e persistência de diretório externo para ROMs usando o SAF do
Android, incluindo o `ActivityResultLauncher`, `DocumentFile` APIs e a tela de configurações.

---

### [`performance-dispositivos-fracos.md`](performance-dispositivos-fracos.md)
**Performance e memória em dispositivos fracos (TV box / Smart TV) — fonte única**

Conjunto completo de otimizações de performance/memória para aparelhos de 1–2 GB com ~57
sistemas / ~58 mil entradas no catálogo. Cobre:
- **Infraestrutura existente:** banco pré-construído em build-time (`PrebuiltDbGenerator` +
  `createFromAsset`), fast-skip do loader, PRAGMA tuning, WAL, pre-warm, `HeavySystemFilter`.
- **Correções 2026-06-13:** `maxSize` na paginação, chaves anti-colisão no Compose, covers em
  RGB_565 + detecção de low-RAM por tier, trim/pre-warm isolado por processo + `onLowMemory`,
  shader `Sharp` em device fraco, `largeHeap`.
- O que foi deliberadamente **não** aplicado e por quê.

---

### [`performance-oportunidades.md`](performance-oportunidades.md)
**Análise de oportunidades adicionais de performance (TV box / Smart TV)**

Nova análise sobre a versão atual: oportunidades priorizadas (impacto × risco) para reduzir
ainda mais memória/GPU/startup em aparelhos fracos, além do que já está implementado.

---

### [`correcoes-2026-03-16.md`](correcoes-2026-03-16.md)
**Correções — primeira rodada (2026-03-16)**

Histórico detalhado das correções críticas que resolveram a perda silenciosa de ROMs em
re-downloads. Inclui diagnóstico de código, causa raiz e solução para os bugs #1, #2, #3,
#4, A e B. Bump de `EXTRACTION_VERSION` 2 → 3.

---

### [`correcoes-2026-03-18.md`](correcoes-2026-03-18.md)
**Correções e melhorias — 2026-03-18**

Robustez do download em dispositivo físico (Moto G86 5G) e UX da barra de progresso:
- `LinearProgressIndicator` depreciado corrigido para forma lambda
- 30 retries com backoff exponencial + jitter (era 5 retries linear)
- `PermanentHttpException` para fast-fail em erros HTTP 4xx
- Tratamento correto HTTP 200 vs 206 em `downloadSegment`
- `readTimeout(90s)` para evitar conexões travadas silenciosamente
- `WAKE_LOCK` declarado explicitamente no manifest (Motorola OEM)
- `PREF_LAST_DOWNLOAD_PROGRESS` — barra de progresso não volta a 0% ao retomar

---

### [`correcoes-2026-03-17.md`](correcoes-2026-03-17.md)
**Correções e melhorias — segunda rodada (2026-03-17)**

Histórico das sessões A, B e C:
- **Sessão A:** Download paralelo (aria2c), buffers maiores, `CancellationException` handling,
  auto-resume de download e extração, botão Cancelar com confirmação, fix de ANR.
- **Sessão B:** Card/dialog reaparecem após deleção de ROMs; guard contra falso `Idle`
  durante indexação pós-download.
- **Sessão C:** `CancellationException` em `RomsDownloadWork`, loop infinito em arquivo
  corrompido (C-2), `PREF_DOWNLOAD_STARTED` não limpo em falha permanent (C-3).
