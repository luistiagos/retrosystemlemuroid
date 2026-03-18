# Funcionalidades — Índice

Documentação das funcionalidades implementadas no Lemuroid.

---

## Arquivos

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

### [`correcoes-2026-03-16.md`](correcoes-2026-03-16.md)
**Correções — primeira rodada (2026-03-16)**

Histórico detalhado das correções críticas que resolveram a perda silenciosa de ROMs em
re-downloads. Inclui diagnóstico de código, causa raiz e solução para os bugs #1, #2, #3,
#4, A e B. Bump de `EXTRACTION_VERSION` 2 → 3.

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
