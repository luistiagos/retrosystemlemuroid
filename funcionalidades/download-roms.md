# Download de Pacote de ROMs

## Descrição

Funcionalidade que baixa um arquivo `.7z` multi-part, extrai, normaliza pastas e adiciona todas as ROMs ao sistema. Aparece como card de confirmação ao abrir o app (sem ROMs) ou via botão de download.

## Arquivo principal

`lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/RomsDownloadManager.kt`

## Fluxo de execução

1. Baixa 5 partes (`roms.7z.001` a `roms.7z.005`) do HuggingFace sequencialmente.
2. Concatena as partes em `roms_combined.7z`.
3. Extrai via `extractSevenZ()` para `getInternalRomsDirectory()`.
4. Deleta o arquivo combinado.
5. Chama `normalizeExtractedFolders(romsDir)`:
   - `unwrapWrapperFolders()` — remove wrapper único (ex: "roms/")
   - `renameSystemFoldersRecursive()` — renomeia pastas legíveis para dbnames
6. Se SAF configurado: copia para SAF e deleta romsDir.
7. Agendas `LibraryIndexScheduler`.

## Bugs identificados (diagnóstico de 2026-03-16)

### Bug #1 — CRÍTICO: deleção silenciosa em `unwrapWrapperFolders`

```kotlin
wrapper.listFiles()?.forEach { child ->
    child.renameTo(File(dir, child.name))  // falha silenciosa se destino existe
}
wrapper.deleteRecursively()  // APAGA o que não foi movido!
```

**Causa**: `File.renameTo()` no Android retorna `false` sem exceção quando o destino já existe. O `deleteRecursively()` posterior apaga os arquivos que falharam ao ser movidos. Afeta re-downloads (quando pastas já existem de extração anterior).

### Bug #2 — CRÍTICO: mesmo problema no merge de `renameSystemFoldersRecursive`

```kotlin
if (target.exists()) {
    folder.walkTopDown().filter { it.isFile }.forEach { file ->
        file.renameTo(dest)       // falha silenciosa
    }
    folder.deleteRecursively()   // apaga o que não moveu
}
```

**Causa**: Mesma lógica do Bug #1. Quando duas pastas mapeiam para o mesmo dbname (ex: "Mega Drive/" e "Genesis/" ambas → "md/"), e "md/" já existe, os arquivos da segunda pasta são perdidos.

### Bug #3 — ALTO: sistemas ausentes do FOLDER_NAME_MAP

Sistemas faltantes:
- `scd` (Sega CD) — nenhuma entrada. SegaCD não tem `uniqueExtensions`, então **nunca** é reconhecido.
- `mame2003plus` — nenhuma entrada.

### Bug #4 — MÉDIO: `parentContainsSystem` usa substring

```kotlin
if (lowercasePath.contains(dbname)) return true  // substring, não segmento!
```

- Caminho `.../snes/game.smc` também corresponde a dbname `"nes"`
- Caminho `.../gba/game.gba` também corresponde a dbname `"gb"`

A ordenação por comprimento em `sortedSystemIds` mitiga parcialmente, mas não elimina o bug.

## Por que APENAS Mega Drive funciona

O Mega Drive tem `uniqueExtensions = listOf("gen", "smd", "md")`. Estas extensões são **únicas no sistema** — o passo `findByUniqueExtension` as reconhece **independente da pasta em que estão**.

Os outros sistemas falham porque:
1. Seus arquivos no `.7z` podem ter extensões não-padrão (ex: `.rom`, `.bin` para NES), OU
2. Durante re-extrações, os Bugs #1 e #2 apagam os arquivos silenciosamente

## EXTRACTION_VERSION

Constante `EXTRACTION_VERSION = 2` força re-download em usuários com versão anterior. Mas como os Bugs #1 e #2 se manifestam **exatamente** em re-extrações (quando pastas já existem), a versão que deveria "corrigir" acaba **piorando** o problema.

## Sistemas sem extensão única (exigem detecção por pasta)

| Sistema | dbname | Mapeado em FOLDER_NAME_MAP? |
|---------|--------|----------------------------|
| PSX     | psx    | Sim ("playstation", "psx", "ps1") |
| PSP     | psp    | Sim ("psp", "playstation portable") |
| SegaCD  | scd    | **NÃO** ← bug |
| FBNeo   | fbneo  | Sim ("arcade") |
| MAME    | mame2003plus | **NÃO** ← bug |

### Bug A — CRÍTICO: `unwrapWrapperFolders` desfazia pastas já com dbname correto

`FOLDER_NAME_MAP` só contém nomes legíveis ("mega drive", "genesis"...) como chaves, não os dbnames ("md", "nes"...). Se o `.7z` extraísse um diretório raiz único já corretamente nomeado "md/", a função o trataria como wrapper e espalharia as ROMs soltas na raiz, onde a detecção por pasta pararia de funcionar.

**Adicionado `SYSTEM_DBNAMES`** — conjunto com todos os 25 dbnames válidos. A verificação passou a ser:
```kotlin
if (FOLDER_NAME_MAP[wrapperLower] == null && !SYSTEM_DBNAMES.contains(wrapperLower))
```

### Bug B — CRÍTICO: `safeMoveFile` lançava exceção em diretórios

O fallback de cópia usava `src.inputStream()`, que lança `IOException` para diretórios. O `try-catch` capturava silenciosamente e retornava `false`, e o `deleteRecursively()` seguinte apagava o conteúdo que não foi movido.

**Correção:** `safeMoveFile` agora detecta `src.isDirectory` e no fallback percorre todos os arquivos internos copiando individualmente.

---

## Correções aplicadas (2026-03-16)

| # | Arquivo | Correção |
|---|---------|---------|
| Bug #1 | `RomsDownloadManager.kt` | `unwrapWrapperFolders`: substituído `renameTo` por `safeMoveFile` |
| Bug #2 | `RomsDownloadManager.kt` | `renameSystemFoldersRecursive`: substituído `renameTo` por `safeMoveFile` + fallback de cópia |
| Bug #3 | `RomsDownloadManager.kt` | Adicionados `sega cd/mega cd/scd → "scd"` e `mame/mame2003plus → "mame2003plus"` ao `FOLDER_NAME_MAP` |
| Bug #4 | `LibretroDBMetadataProvider.kt` | `parentContainsSystem`: removido `.contains(dbname)` (substring); usa comparação por segmento exato |
| Bug #4 | `LibretroDBMetadataProvider.kt` | `FOLDER_ALIASES`: adicionadas as mesmas entradas de Sega CD e MAME |
| Bug A | `RomsDownloadManager.kt` | Adicionado `SYSTEM_DBNAMES`; `unwrapWrapperFolders` verifica se nome já é dbname válido |
| Bug B | `RomsDownloadManager.kt` | `safeMoveFile`: fallback trata diretórios percorrendo arquivos internos |
| Extra | `RomsDownloadManager.kt` | `EXTRACTION_VERSION` 2 → 3 para forçar re-download com nova lógica |
| Extra | `RomsDownloadManager.kt` | `downloadAndExtract`: limpa `romsDir` existente antes de extrair |
