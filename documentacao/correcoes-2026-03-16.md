# Correções — 2026-03-16

Primeira rodada de correções críticas ao sistema de download e normalização de ROMs.

---

## Resumo das correções

| ID | Severidade | Arquivo | Descrição |
|----|-----------|---------|-----------|
| #1 | Crítico | `RomsDownloadManager.kt` | `renameTo` silencioso apagando ROMs em re-downloads |
| #2 | Crítico | `RomsDownloadManager.kt` | Mesmo problema no merge de pastas duplicadas |
| #3 | Alto | `RomsDownloadManager.kt` | Sega CD e MAME ausentes do `FOLDER_NAME_MAP` |
| #4 | Médio | `LibretroDBMetadataProvider.kt` | `parentContainsSystem` usava substring em vez de segmento exato |
| A | Crítico | `RomsDownloadManager.kt` | `unwrapWrapperFolders` desfazia pastas já corretamente nomeadas (dbnames) |
| B | Crítico | `RomsDownloadManager.kt` | `safeMoveFile` lançava exceção silenciosa em diretórios |
| Extra | `RomsDownloadManager.kt` | `EXTRACTION_VERSION` bumped 2 → 3 para forçar re-download com lógica nova |

---

## Bug #1 — Crítico: deleção silenciosa em `unwrapWrapperFolders`

**Comportamento observado:** ROMs desapareciam silenciosamente em qualquer re-download
(quando a pasta de destino já existia de extração anterior).

**Código problemático:**
```kotlin
wrapper.listFiles()?.forEach { child ->
    child.renameTo(File(dir, child.name))  // retorna false sem exceção se destino existe
}
wrapper.deleteRecursively()  // apaga tudo que não foi movido!
```

**Causa raiz:** `File.renameTo()` no Android retorna `false` (sem lançar exceção) quando
o destino já existe. O `deleteRecursively()` posterior apagava silenciosamente todos os
arquivos que falharam na movimentação.

**Correção:** Substituído `renameTo` por `safeMoveFile()` que, em caso de falha na
movimentação atômica, faz cópia byte-a-byte e só deleta a origem após confirmar que o
destino está completo.

---

## Bug #2 — Crítico: mesmo problema no merge de `renameSystemFoldersRecursive`

**Comportamento observado:** Quando duas pastas do `.7z` mapeavam para o mesmo dbname
(ex: "Mega Drive/" e "Genesis/" → "md/"), a segunda era perdida ao ser merged com a
primeira.

**Código problemático:**
```kotlin
if (target.exists()) {
    folder.walkTopDown().filter { it.isFile }.forEach { file ->
        file.renameTo(dest)       // falha silenciosa
    }
    folder.deleteRecursively()   // apaga o que não moveu
}
```

**Causa raiz:** Idêntica ao Bug #1.

**Correção:** Substituído `renameTo` por `safeMoveFile()` na mesma função.

---

## Bug #3 — Alto: Sega CD e MAME ausentes do `FOLDER_NAME_MAP`

**Comportamento observado:** ROMs de Sega CD e MAME nunca eram detectadas pelo Lemuroid.

**Causa raiz:** Esses sistemas não possuem `uniqueExtensions` — dependem exclusivamente da
detecção por nome de pasta (`parentContainsSystem`). Sem entradas no `FOLDER_NAME_MAP`,
as pastas nunca eram renomeadas para `scd` e `mame2003plus`, impossibilitando a detecção.

**Correção:** Adicionadas entradas:
```kotlin
"sega cd"   to "scd",  "mega cd"       to "scd",
"segacd"    to "scd",  "megacd"        to "scd",
"sega-cd"   to "scd",  "mega-cd"       to "scd",
"mame"      to "mame2003plus",
"mame 2003" to "mame2003plus",
"mame2003"  to "mame2003plus",
"mame 2003 plus"  to "mame2003plus",
"mame2003plus"    to "mame2003plus",
```

Também adicionadas as entradas correspondentes em `FOLDER_ALIASES` em
`LibretroDBMetadataProvider.kt`.

---

## Bug #4 — Médio: `parentContainsSystem` usava correspondência por substring

**Comportamento observado:** Arquivo em pasta `snes/` era falsamente reconhecido como sendo
do sistema `nes`; arquivo em `gba/` era falsamente reconhecido como `gb`.

**Código problemático:**
```kotlin
if (lowercasePath.contains(dbname)) return true  // substring!
```

- `".../snes/game.smc".contains("nes")` → `true` (falso positivo)
- `".../gba/game.gba".contains("gb")` → `true` (falso positivo)

**Causa raiz:** A ordenação por comprimento decrescente de `sortedSystemIds` mitigava casos
óbvios, mas não eliminava o problema.

**Correção:** Substituído `.contains()` por verificação de segmento exato de caminho:
```kotlin
val segments = lowercasePath.split(File.separator)
if (segments.contains(dbname)) return true
```

---

## Bug A — Crítico: `unwrapWrapperFolders` desfazia pastas já com dbname correto

**Comportamento observado:** Se o `.7z` extraísse um diretório raiz cujo nome já era um
dbname válido (ex: "md/"), a função tratava esse diretório como wrapper e espalhava seu
conteúdo na raiz — quebrando a detecção por pasta.

**Causa raiz:** `FOLDER_NAME_MAP` contém apenas nomes legíveis como chaves ("mega drive",
"genesis", …), não os dbnames ("md", "nes", …). A condição `if (FOLDER_NAME_MAP[wrapperName] == null)`
não bastava para proteger pastas já nomeadas corretamente.

**Correção:** Adicionado `SYSTEM_DBNAMES` — conjunto com os 25 dbnames válidos do Lemuroid.
Condição passou a ser:
```kotlin
if (FOLDER_NAME_MAP[wrapperLower] == null && !SYSTEM_DBNAMES.contains(wrapperLower)) {
    // só então trata como wrapper descartável
}
```

---

## Bug B — Crítico: `safeMoveFile` lançava exceção silenciosa em diretórios

**Comportamento observado:** O fallback de cópia (usado quando `renameTo` falha) lançava
`IOException` ao tentar abrir um diretório como stream. A exceção era capturada silenciosamente
e o `deleteRecursively()` posterior apagava o diretório sem tê-lo copiado.

**Código problemático (caminho de fallback):**
```kotlin
src.inputStream().use { input ->  // IOException se src for diretório!
    dest.outputStream().use { output ->
        input.copyTo(output)
    }
}
src.delete()
```

**Correção:** `safeMoveFile` agora verifica `src.isDirectory` antes de tentar abrir o
stream. Para diretórios, percorre todos os arquivos internos recursivamente:
```kotlin
if (src.isDirectory) {
    src.walkTopDown().filter { it.isFile }.forEach { file ->
        val relative = file.relativeTo(src)
        val destFile = File(dest, relative.path)
        destFile.parentFile?.mkdirs()
        safeMoveFile(file, destFile)
    }
    src.deleteRecursively()
    return true
}
```

---

## Efeito combinado dos bugs (pré-correção)

Antes dessas correções, o fluxo de normalização em re-downloads produzia:
1. `unwrapWrapperFolders` espalhava conteúdo de pastas válidas (Bug A).
2. `renameSystemFoldersRecursive` tentava mover arquivos de pastas duplicadas (Bug #2).
3. `safeMoveFile` falhava silenciosamente em subdiretórios (Bug B).
4. `deleteRecursively()` apagava tudo que não foi movido (Bugs #1, #2, B).

**Resultado prático:** apenas sistemas com `uniqueExtensions` exclusivas (ex: Mega Drive com
`.smd`, `.gen`) sobreviviam porque o Lemuroid os detectava pela extensão, não pela pasta.
Todos os demais sistemas eram perdidos.
