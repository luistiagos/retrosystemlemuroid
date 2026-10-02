# [BUG] Prebuilt DB grava `fileUri` sem codificar — a primeira passada do loader apaga e reinsere ~54 mil jogos

**Data:** 2026-10-02
**Status:** ✅ Resolvido em 2026-10-02 (não commitado) — sobra um custo de ~15 s com outra causa, registrado à parte
**Severidade:** Média (desempenho do primeiro boot; anula o propósito do prebuilt)
**Branch:** version9
**Origem:** achado lateral da validação de
`documentacao/bugs/done/2026-10-02-smartstoragepicker-pasta-roms-alterna-entre-volumes.md`

---

## Sintoma

Instalação limpa no AVD `lemu_api25_2gb` (build debug 1.17.23, `MANIFEST_SCHEMA_VERSION` 34). Logcat
da primeira abertura:

```
ManifestQuickLoader: rewrote 59793 prebuilt URIs to file:///storage/0000-0000/.../files/roms
ManifestQuickLoader: deleted 53735 stale catalog games not in new manifest
ManifestQuickLoader: inserted=52099 total=58156
```

~38 s entre o rewrite e o fim da carga no emulador. O prebuilt existe justamente para evitar essa
carga (ver "Prebuilt DB Asset" no CLAUDE.md).

## Causa (confirmada)

As duas formas da mesma linha não batem:

- prebuilt (`lemuroid-app/build/intermediates/assets/freeBundleDebug/.../retrograde-prebuilt.db`):
  `file:///lemuroid_prebuilt/nes/Final Fantasy 3 (JP) (3DS Virtual Console).nes` — **sem encoding**;
- loader (`File(File(romsDir, systemId), fileName).toUri()`):
  `file:///storage/.../roms/nes/Final%20Fantasy%203%20(JP)%20(3DS%20Virtual%20Console).nes`.

O `rewritePrebuiltUris` troca só o prefixo, então a linha reescrita continua sem encoding. Como
`games.fileUri` é a chave única, o `INSERT OR IGNORE` vê uma linha nova e a limpeza de órfãos apaga a
antiga. Só sobrevivem os nomes sem caractere codificável (59.793 − 53.735 = 6.058).

## Análise (2026-10-02, antes da correção)

### Símbolos abertos

- `buildSrc/.../PrebuiltDbGenerator.kt::parseManifest` — monta
  `fileUri = "$PREBUILT_URI_PREFIX/$canonicalPath"` com o nome cru. `validate()` só confere
  versão, hash, contagens, tabelas e triggers; nada sobre a forma da URI.
- `ManifestQuickLoader::load` — URI do loader: `File(File(romsDir, systemId), fileName).toUri()`.
  A limpeza de órfãos compara contra `manifestUris + manifestSentinelUris`, ambos **codificados**
  (o sentinela é derivado da URI do loader por `replace(realPrefix, PREBUILT_URI_PREFIX)`), então
  toda linha do prebuilt com caractere codificável cai em `toDelete`.
- `GameDao::rewritePrebuiltUris` — `realPrefix || SUBSTR(fileUri, LENGTH(sentinel)+1)`: troca só o
  prefixo, preserva o sufixo como estiver.
- `GameDao::updateManifestFieldsWithTitle` — `UPDATE … WHERE fileUri = :fileUri`, **incondicional**.
  Com as URIs batendo, a passada completa atualiza as ~58 mil linhas existentes, e cada UPDATE
  dispara `games_bu` + `games_au` (delete + insert no FTS) mesmo sem mudar valor. Hipótese inicial:
  o custo voltaria por aí — **medida e descartada** (ver "Hipóteses descartadas").
- **Biblioteca** — `android.net.Uri` (fonte em `Sdk/sources/android-35/android/net/Uri.java`):
  `fromFile` = `PathPart.fromDecoded(file.getAbsolutePath())`; `getEncoded()` = `encode(decoded, "/")`;
  `encode` mantém `A-Z a-z 0-9 _-!.~'()*` (+ o `allow`), e o resto vira `%XX` dos bytes **UTF-8**
  com hex **maiúsculo**. `toString()` = `"file:" + "//" + "" + pathCodificado`.

### Números

Script `mstat.py` (scratchpad) sobre `lemuroid-app/src/main/assets/catalog_manifest.txt`:

- 59.793 linhas, **53.269** com caractere codificável no nome (o log apagou 53.735; a diferença é
  alias/sistema não registrado/duplicata). Caracteres: espaço 53.260, `[` `]` 9.417, `,` 8.849,
  `&` 792, `+` 707, `#` 31, `@` 18, `$` 10, `%` 7, `=` 3, `;` 3, `^` 1, e 7 não-ASCII
  (combinantes `U+0301`/`U+0304`/`U+030C`, `ç`, `‚`).
- 31 nomes com `#` e 7 com `%`: na forma sem encoding, `Uri.parse(fileUri).path` **corta** no `#`
  (vira fragmento) e decodifica `%` errado — esses jogos não abririam por caminho.
- 6 nomes com `/` dentro (`pce/pcengine/...`); nenhum `//`, `\` ou espaço nas pontas — então a
  normalização de `File.getAbsolutePath()` não muda nada e concatenar strings equivale ao `File`.

### Respostas aos "não investigado"

- **Fast-path numa instalação nova:** os dois atalhos exigem `loadedSchema == MANIFEST_SCHEMA_VERSION`,
  e numa instalação nova `loadedSchema = -1`. Logo **toda** instalação nova faz a passada completa —
  o CLAUDE.md dizia o contrário ("caso típico em devices que abriram com o asset pre-built"). Não é
  para mudar: a passada também roda as migrações one-time (`loadedSchema < N`) e reinsere o que a
  v27 apaga; com URIs iguais e UPDATE condicional ela vira só leitura.
- **Forma mista em produção:** como a passada completa sempre roda no primeiro boot e a limpeza apaga
  as linhas sem encoding, instalações normais terminam 100% codificadas. Única janela: o Room re-copiar
  o asset por `fallbackToDestructiveMigration` numa atualização sem mudar `MANIFEST_SCHEMA_VERSION`
  (aí o fast-skip vale e as URIs ficariam cruas). A correção no gerador fecha essa janela também.

### Hipóteses descartadas

- **Codificar no `rewritePrebuiltUris` (SQL):** SQLite não tem função de percent-encoding; fazer em
  Kotlin linha a linha custaria o mesmo que o problema. O lugar é o build.
- **Usar `java.net.URI`/`URLEncoder` no gerador:** `URLEncoder` troca espaço por `+` e codifica
  `'()*!~`; `URI` multiargumento deixa `, & + $ @ = ;` e os não-ASCII sem codificar. Nenhum bate com o `Uri.encode`
  do Android — replicar o algoritmo (é curto) é o único jeito de bater byte a byte.
- **Fazer instalação nova pular a passada (detectar prebuilt):** a v27 (`deleteFbneoByFileName`)
  roda em instalação nova e a passada é quem reinsere o que ela apaga; pular arrisca perder linha.
- **UPDATE condicional em `updateManifestFieldsWithTitle`** (`AND (title != :title OR …)`, para não
  disparar `games_bu`/`games_au` em linha que não muda): **testado e revertido, sem ganho.** Três
  instalações limpas de cada variante (`pm clear` + abrir, mesmo APK), intervalo `v25 reclassified`
  → `inserted=`: com a condição 24,1 / 22,8 / 23,6 s; sem ela 24,2 / 24,5 / 19,7 s. O ruído do
  emulador é maior que qualquer diferença — os triggers FTS não são o gargalo (ver "Custo residual").
  Cuidado com amostra única: a primeira rodada com a condição deu 13,8 s e parecia provar o contrário.

### Plano (executado)

1. `PrebuiltDbGenerator`: `encodeUriPath()` replicando `Uri.encode(s, "/")` + guard em `validate()`.
2. ~~UPDATE condicional no `GameDao`~~ — descartado, ver acima.
3. Validar em instalação limpa no `lemu_api25_2gb`.
4. Corrigir o CLAUDE.md (fast-path não vale para instalação nova).

## Correção

[PrebuiltDbGenerator.kt](../../../buildSrc/src/main/kotlin/PrebuiltDbGenerator.kt):

- `encodeUriPath()` — cópia do `Uri.encode(path, "/")` do Android (mesmo conjunto permitido, `%XX`
  UTF-8 maiúsculo, codificação por *runs* para pares substitutos saírem como uma sequência só).
  `fileUri = "$PREBUILT_URI_PREFIX/${encodeUriPath(canonicalPath)}"`.
- `validate()` ganhou dois guards, os dois derrubam o build:
  1. `URI_PATH_KNOWN_ANSWERS` — 14 pares nome → URI tirados do DB do aparelho (saída real de
     `File.toUri()`), um por caractere especial do catálogo: espaço, `( ) # % , [ ] & + @ $ ! = ; ^ ' ~`
     e um acento combinante (`U+0301`), mais um nome com `/` interno (`pce/pcengine/…`).
  2. Varredura das 59.793 linhas: o sufixo de toda `fileUri` só tem caractere permitido ou `%XX`, e
     decodificado volta exatamente a `systemId/fileName`.

Nada muda no app: o `rewritePrebuiltUris` continua trocando só o prefixo, e agora o sufixo já está na
forma certa.

## Validação

- **Build:** `[PrebuiltDbGenerator] validate: games=59793 …` e `BUILD SUCCESSFUL`.
- **O guard pega regressão:** tirando `(` e `)` do conjunto permitido, `generatePrebuiltDb` falha com
  `encodeUriPath diverged from Android's File.toUri(): "nes/Final Fantasy 3 (JP) …" -> "…%28JP%29…"`.
- **Instalação limpa no `lemu_api25_2gb`** (debug x86_64; `uninstall` + `install` e mais 9
  rodadas com `pm clear`, todas com o mesmo resultado nas contagens):

  | | `deleted` | `inserted` | rewrite → fim |
  |---|---|---|---|
  | antes | 53.735 | 52.099 | ~38 s |
  | depois | 1.637 | 1 | ~25 s |

  Isso prova a igualdade das URIs contra a implementação real do Android **para as 58.156 linhas** que
  o loader monta: uma URI divergente apareceria nas duas contagens. As que sobram têm outra causa:
  - `inserted=1` é `mame2003plus/1941j.zip`, que a migração v27 (`deleteFbneoByFileName`) apaga em
    toda instalação nova e a passada reinsere. Esperado.
  - `deleted 1637` = 1.592 `amiga500` + 45 `gameandwatch`, sistemas sem alias no
    `manifest_alias.json` (`SystemID` usa `amiga` e `gw`): o loader não os monta e apaga a linha do
    prebuilt. É o catálogo inteiro desses dois sistemas sumindo — outro bug, registrado em
    `documentacao/bugs/open/2026-10-02-amiga500-gameandwatch-sem-alias-somem-do-catalogo.md`.
  Diff por sistema (prebuilt × DB do aparelho) feito com `SELECT systemId, COUNT(*) … GROUP BY systemId`
  nos dois lados: só esses dois sistemas diferem.

## Custo residual (fora deste bug)

Rodada instrumentada (Timber temporário, removido; `diff` contra a cópia limpa conferido), instalação
limpa: montar a lista 1.434 ms · `insertIfNotExists` 4.080 ms · **loop de
`updateManifestFieldsWithTitle` 14.588 ms** · `selectAll` 1.944 ms · filtro + delete 495 ms. O loop
faz 58 mil chamadas `suspend` ao Room, uma por linha já existente, e o custo é por chamada — não pela
escrita (ver a hipótese do UPDATE condicional, descartada). Próximo passo registrado em
`documentacao/backlogs/2026-10-02-loader-loop-update-58k-chamadas-primeiro-boot.md`.

## Lição

- **URI montada à mão tem que sair do mesmo algoritmo que a de comparação.** Concatenar
  `"file://…/$nome"` e comparar com `File.toUri()` só funciona para nome sem espaço — 89% do catálogo
  tem. E nenhuma API da JVM (`URLEncoder`, `java.net.URI`) produz a forma do `android.net.Uri`.
- **Validação de asset tem que olhar o conteúdo, não só a estrutura.** O `validate()` conferia hash,
  versão e contagens e deixou passar 53 mil linhas que o app apagaria no primeiro boot.
- **No emulador, uma amostra de tempo não prova nada.** A mesma variante deu 13,8 s e 23,6 s. Medir
  ≥3 rodadas por variante, com `pm clear` em vez de reinstalar (o dex2oat pós-instalação disputa CPU).
