# [BUG] `amiga500/` e `gameandwatch/` sem alias no `manifest_alias.json` — 1.637 jogos somem do catálogo

**Data:** 2026-10-02
**Status:** ✅ Resolvido
**Severidade:** Alta (dois sistemas inteiros sem nenhum jogo no catálogo, em toda instalação)
**Branch:** version9
**Origem:** achado lateral de
`documentacao/bugs/done/2026-10-02-prebuilt-fileuri-sem-encoding-recria-catalogo.md`

---

## Sintoma

Instalação limpa no AVD `lemu_api25_2gb` (debug 1.17.23, `MANIFEST_SCHEMA_VERSION` 34), já com as URIs
do prebuilt corrigidas:

```
ManifestQuickLoader: deleted 1637 stale catalog games not in new manifest
ManifestQuickLoader: inserted=1 total=58156
```

O manifest tem 59.793 linhas; o loader monta 58.156. Contagem por sistema, prebuilt × DB do aparelho
depois da carga (`SELECT systemId, COUNT(*) FROM games GROUP BY systemId` nos dois): só faltam
`amiga500` (1.592) e `gameandwatch` (45) — 1.592 + 45 = 1.637.

## Causa-raiz

- `catalog_manifest.txt` usa as pastas `amiga500/` e `gameandwatch/` (introduzidas no commit `73e0aee`,
  2026-06-10; nenhuma linha `amiga/` ou `gw/` hoje).
- `SystemID.kt`: `AMIGA("amiga")` (linha 76) e `GAME_WATCH("gw")` (linha 81).
- `assets/manifest_alias.json` não mapeia nenhuma das duas pastas.
- `ManifestQuickLoader.load()` (passada completa): `systemId = manifestAlias[raw] ?: raw` →
  `"amiga500"`/`"gameandwatch"` → `GameSystem.findByIdOrNull(systemId) == null` → `continue`. A linha
  fica fora de `games`, e a limpeza de órfãos apaga a cópia que veio do prebuilt (o
  `PrebuiltDbGenerator` aplica o mesmo alias, mas não filtra por `GameSystem`, que ele não enxerga).

Toda instalação faz a passada completa no primeiro boot (`loadedSchema = -1`), então nenhum usuário tem
esses jogos no catálogo.

## Não investigado

- `RomSystemMapper.load()` itera as chaves do `mnemonico_map.json` (`"amiga": "amiga500"` na linha 72,
  `"gw": "gw"` na 77) e registra também o `dbname` aliasado delas. Conferir se acrescentar
  `amiga500`/`gameandwatch` ao alias muda alguma entrada desse mapa (à primeira vista não: as chaves
  dele são `amiga` e `gw`, que não estão no alias).
- Se o `HeavySystemFilter` esconde Amiga em `ULTRA_WEAK` (sim, pela tabela do CLAUDE.md) — não afeta a
  correção, só onde validar.
- O que mudou em `73e0aee` (renomeação de pasta ou entrada nova).

## Análise pré-correção (2026-10-02)

Símbolos abertos:
- `manifest_alias.json` — 10 entradas; nenhuma para `amiga500`/`gameandwatch`.
- Pastas do manifest (`cut -d'|' -f1 catalog_manifest.txt | cut -d/ -f1 | sort -u`): 72 pastas. Sem alias
  e fora do `SystemID` só `amiga500` e `gameandwatch` — todas as outras resolvem.
- `SystemID.kt` × `GameSystem.SYSTEMS`: todo `SystemID` está registrado no `GameSystem` (loop de grep,
  nenhum faltando), então "dbname existe no `SystemID.kt`" ⇔ `GameSystem.findByIdOrNull != null`. O
  guard no build pode ler o enum do fonte.
- `RomSystemMapper.load()` — registra as chaves do `mnemonico_map.json` e, se a **chave** estiver no
  alias, o dbname aliasado. Chaves são `amiga` → `amiga500` e `gw` → `gw`; nenhuma delas vira chave do
  alias, então o mapa não muda. Com `systemId = amiga`/`gw` o lookup do endpoint já funciona.
- `PrebuiltDbGenerator.generate(schemaJsonFile, manifestFile, outputDbFile)` — lê o alias de
  `manifestFile.parentFile`; `parseManifest` não filtra sistema. Task `generatePrebuiltDb` em
  `lemuroid-app/build.gradle.kts` declara `inputs` do schema, manifest, alias e `buildSrc` — falta o
  `SystemID.kt` como input se o guard passar a lê-lo.

Fora do escopo (registrado, não corrigido aqui):
- `CatalogFallbackMetadataProvider` chama `catalog.getCoverUrl(systemId, fileName)` com o dbname, mas o
  mapa do `CatalogCoverProvider` é chaveado pela pasta do manifest — erra para **todo** sistema
  aliasado (`md`, `coleco`, `vb`…), não só estes dois. Só afeta ROM achada pelo scan sem capa no DB.

Tasks:
1. `manifest_alias.json`: `"amiga500": "amiga"`, `"gameandwatch": "gw"`.
2. `ManifestQuickLoader.MANIFEST_SCHEMA_VERSION` 34 → 35 + histórico; tabela do `CLAUDE.md`.
3. `PrebuiltDbGenerator.generate(..., systemIdFile)`: extrair os dbnames do `SystemID.kt` e falhar o
   build se alguma pasta do manifest, pós-alias, não estiver lá. `SystemID.kt` vira `inputs.file`.
   Prova: build com o alias atual falha; com o alias corrigido passa.
4. Validar no AVD `lemu_api25_2gb` em instalação limpa: sem `deleted N stale`, e `amiga`/`gw` com
   1.592/45 linhas.

## Correção

1. [manifest_alias.json](../../../lemuroid-app/src/main/assets/manifest_alias.json): `"amiga500": "amiga"`
   e `"gameandwatch": "gw"`.
2. `ManifestQuickLoader.MANIFEST_SCHEMA_VERSION` 34 → **35**, com linha no histórico do `.kt` e na tabela
   do `CLAUDE.md`: instalações existentes refazem a passada completa e inserem os 1.637 jogos.
3. Guard no build: `PrebuiltDbGenerator.generate` recebe `systemIdFile`, extrai os dbnames do enum
   `SystemID.kt` por regex (`loadSystemDbNames`) e `requireKnownSystems` falha a task
   `generatePrebuiltDb` se alguma pasta do manifest, pós-alias, não for dbname — com o nome da pasta e a
   contagem de linhas na mensagem. `SystemID.kt` entrou nos `inputs` da task em
   `lemuroid-app/build.gradle.kts`, para a task não ficar UP-TO-DATE quando o enum muda.

`RomSystemMapper` não precisou mudar (ver análise): o download usa `amiga` → `amiga500` e `gw` → `gw`
do `mnemonico_map.json`, que já cobriam o dbname.

## Validação

- Guard: `./gradlew :lemuroid-app:generatePrebuiltDb` com o alias **antigo** →
  `BUILD FAILED … catalog_manifest.txt has systems that are not a SystemID dbname after
  manifest_alias.json: amiga500 (1592 rows), gameandwatch (45 rows)`. Com o alias corrigido →
  `validate: games=59793 fts=59793 … BUILD SUCCESSFUL`.
- Instalação limpa no AVD `lemu_api25_2gb` (`assembleFreeBundleDebug -PdevAbi=x86_64`, `uninstall` antes):
  ```
  ManifestQuickLoader: rewrote 59793 prebuilt URIs to file:///storage/emulated/0/Android/data/app.retrogamesystem.debug/files/roms
  ManifestQuickLoader: inserted=1 total=59793
  ```
  sem a linha `deleted N stale catalog games` (antes: `deleted 1637`, `total=58156`).
- DB puxado do aparelho (`run-as … cat databases/retrograde*`):
  `amiga` 1.592 linhas (1.581 representantes, 495 com capa), `gw` 45 (43, 45); `games` = 59.793;
  0 URIs com o prefixo sentinela; `fileUri` de `gw` no formato real
  (`…/files/roms/gw/Armor%20Battle%20(Mattel%20Electronics).mgw`).
- Não validado: a tela de sistemas e o download de um jogo Amiga/G&W no aparelho. O AVD é tier `WEAK`,
  que não esconde Amiga; em `ULTRA_WEAK` (≤ 1 GB) o `HeavySystemFilter` esconde Amiga de propósito.

## Lição

- Pasta nova no `catalog_manifest.txt` com nome diferente do `SystemID.dbname` some **inteira e em
  silêncio**: o loader faz `continue` e a limpeza de órfãos apaga a cópia do prebuilt. O prebuilt
  tinha as linhas, o que escondia o problema de qualquer conferência feita só no asset.
- O guard tem que estar no lado que **não** depende de Android (buildSrc), lendo a mesma fonte que o
  runtime usa (`SystemID.kt`, `manifest_alias.json`) — mesma lógica do bug de drift do Coleco/VB.
