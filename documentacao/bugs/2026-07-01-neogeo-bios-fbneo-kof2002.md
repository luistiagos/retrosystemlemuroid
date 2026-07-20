# [BUG] KOF 2002 (e todo Neo Geo) mostra "FBNeo Error: romset missing" — BIOS neogeo.zip

**Data:** 2026-07-01
**Status:** CORRIGIDO — inclui migração v24 para instalações existentes (in-place)
**Severidade:** ALTA — todos os jogos Neo Geo eram injogáveis
**Branch:** version9

---

## Sintoma

Ao lançar **KOF 2002** (e qualquer jogo Neo Geo) o FBNeo exibe a tela azul interna:

```
FBNeo Error
This game is known but one of your romsets is missing files for THIS VERSION of FBNeo.
Verify the following romsets : kof2002 neogeo

ROM with name sp-s3.sp1 and CRC 0x91b64be3 is missing
ROM with name sm1.sm1  and CRC 0x94416d67 is missing
ROM with name sfix.sfix and CRC 0xc2ea0cfd is missing
ROM with name 000-lo.lo and CRC 0x5a86cff2 is missing
```

Não é crash do app: o core **carrega e roda** (controles aparecem). Os arquivos citados
(`sp-s3.sp1`, `sm1.sm1`, `sfix.sfix`, `000-lo.lo`) **não são do KOF 2002** — são ROMs internas do
**`neogeo.zip`**, o BIOS do sistema Neo Geo, que o FBNeo exige na pasta `system/` do RetroArch.

## Causa-raiz

O app tem toda a mecânica de auto-provisionar o `neogeo.zip` (registrado em
[BiosManager.kt](../../retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/bios/BiosManager.kt)
como BIOS de `SystemID.FBNEO`, baixável via
[BiosDownloader.kt](../../retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/bios/BiosDownloader.kt)),
mas ela **nunca disparava** para jogos Neo Geo:

1. Só `SystemID.NEOGEO` declara `requiredBIOSFiles = listOf("neogeo.zip")`
   ([GameSystem.kt](../../retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/GameSystem.kt)).
   O sistema genérico `SystemID.FBNEO` ("FBNeo - Arcade Games") **não declara BIOS** (correto — a
   maioria dos arcades FBNeo, ex. CPS1/2/3, não precisa de BIOS).
2. **No catálogo, TODOS os ~1818 jogos de arcade estavam em `fbneo/`** — inclusive todo o Neo Geo
   (`fbneo/kof2002.zip`, toda a série KOF, Metal Slug, Samurai Shodown, etc.). Zero entradas
   `neogeo/`. Logo os jogos Neo Geo resolviam para `SystemID.FBNEO`, o check pré-lançamento
   `getMissingBiosFiles` retornava vazio, a ROM ia direto ao FBNeo, e o core mostrava o erro
   interno porque o `neogeo.zip` não estava em `system/`.

## Correção (dados + código)

### 1. Reclassificação do catálogo (`catalog_manifest.txt`)

Reclassificados os **401** romsets de arcade que pertencem a um sub-sistema dedicado, movendo o
prefixo de pasta `fbneo/` → pasta do sistema correto. Como `ManifestQuickLoader` e
`PrebuiltDbGenerator` derivam `systemId` do folder (`manifestAlias[raw] ?: raw`), e todos esses
folders são `dbname`s válidos (sem alias), a mudança é só de dados:

| Sistema | Jogos | | Sistema | Jogos |
|---------|-------|---|---------|-------|
| **neogeo** | **102** | | taito | 44 |
| cps1 | 29 | | dataeast | 36 |
| cps2 | 47 | | galaxian | 49 |
| cps3 | 10 | | psikyo | 15 |
| toaplan | 14 | | pgm | 16 |
| kaneko | 9 | | cave | 8 |
| technos | 6 | | seta | 15 |

Restam **1418** em `fbneo/` (arcades sem sub-sistema dedicado — catch-all correto).

Após a reclassificação, a **região de arcade** do manifest (linhas ~6686–8503, isolada entre
`colecovision` e `gb`) foi reagrupada e reordenada seguindo a convenção do resto do arquivo:
**agrupado por sistema (ordem alfabética do folder) e ordenado por nome do arquivo (path)
ascendente** — igual a nes/snes/3ds. Assim os novos blocos (`neogeo/`, `cps1/`, …) ficam
contíguos e ordenados em vez de espalhados onde as linhas `fbneo/` estavam.

Os 102 Neo Geo agora resolvem para `SystemID.NEOGEO` → check de BIOS detecta `neogeo.zip` ausente
→ [GameViewModelRetroGameView.kt](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/viewmodel/GameViewModelRetroGameView.kt)
dispara `BiosDownloader.downloadMissing(["neogeo.zip"])` → grava em `system/neogeo.zip` → retry →
FBNeo encontra o BIOS.

### 2. `ArcadeSubSystemRoms.kt` (consistência com scan manual)

- Adicionados `NEOGEO_ROMS` (148 romsets) e `CPS1_ROMS` (32 romsets), com ramos em
  `dedicatedSystemIdForRom` (NEOGEO e CPS1 **primeiro** na precedência).
- **Corrigidos 3 erros pré-existentes**: `doubledr` (Double Dragon Neo Geo) e `matrim` (Matrimelee)
  estavam em `TECHNOS_ROMS`; `karnovr` (Karnov's Revenge) em `DATAEAST_ROMS`. Todos são Neo Geo e
  precisam do BIOS — removidos de lá e incluídos em `NEOGEO_ROMS`. Isso corrige também o path de
  **importação manual** desses jogos.

### Cuidados na classificação Neo Geo (falsos-amigos excluídos por título)

Excluídos do Neo Geo apesar do título parecido: `prehisle` (Alpha-68K, ≠ `preisle2` que é Neo Geo),
`pspikes` (Video System, ≠ `pspikes2`), `footchmp`/`pwrgoal`/`pbobble`/`pbobble2/3/4` (Taito, ≠
`pbobblen`/`pbobbl2n` que são Neo Geo), `ket` (Cave), `gstream`, `wwjgtin`, `ultracin`. Auditoria
por título nos leftovers `fbneo/` não encontrou nenhum Neo Geo perdido.

## Validação

- Manifest: 58347 linhas (inalterado), `neogeo/kof2002.zip` presente, todas as linhas alteradas
  preservaram os 5 campos. Backup em `catalog_manifest.txt.bak_reclassify`.
- `dedicatedSystemIdForRom`: `doubledr`/`matrim`/`karnovr` → `neogeo`.
- Todos os 14 sistemas-alvo têm `GameSystem` registrado (nenhum jogo some do catálogo).
- Script de reclassificação: `scratchpad/reclassify.py` (parseia os 12 sets existentes do Kotlin +
  NEOGEO/CPS1 curados; modo relatório e `--apply`).

### 3. Download de BIOS por-jogo (independente do SystemID)

Em vez de depender só da classificação de sistema, o check de BIOS agora também é **por romset**:
`ArcadeSubSystemRoms.requiredBiosForRom(fileName)` retorna `["neogeo.zip"]` para qualquer romset
Neo Geo, e `BiosManager.getMissingBiosFiles` o soma ao required quando `coreID == FBNEO`. Assim, um
jogo Neo Geo que ainda esteja sob `SystemID.FBNEO` (linhas de catálogo legadas em instalações
in-place, ou scan manual) **também** dispara o auto-download do `neogeo.zip`. Isso resolve o item 2
sem migração de DB: o KOF 2002 pode aparecer tanto em Neo Geo quanto em FBNeo — em ambos funciona.

### 4. Verificação do repositório de BIOS (`luistiagos/bios`)

Varredura completa (MD5 de todos os 37 BIOS registrados + presença no repo):

| BIOS | Achado | Ação tomada |
|------|--------|-------------|
| `neogeo.zip` | Conteúdo válido/completo (34 arquivos: `sp-s3.sp1`, `sm1.sm1`, `sfix.sfix`, `000-lo.lo`, uni-bios…), mas MD5 registrado errado | **Corrigido no código**: MD5 `DFFB72F1…` → `DEAFC7F11273D660392F4BBC97D9308E`, CRC32 `362E948D` → `3B5D3421` |
| `dc/dc_boot.bin`, `dc/dc_flash.bin` | Presentes no repo sob `dc/`, mas `externalName` sem o prefixo → download buscava `BASE/dc_boot.bin` (404) | **Corrigido no código**: `externalName` → `dc/dc_boot.bin` / `dc/dc_flash.bin` (padrão do neocd). MD5 dos arquivos confere. |
| `gba_bios.bin` | Arquivo no repo **É o BIOS canônico da Nintendo** (CRC32 `81977335` + SHA1 `300C20DF…D25D3492` conferem); o **MD5 registrado no código estava errado** por transcrição | **Corrigido no código**: MD5 `…7AB1FCE4AB` → `A860E8C0B6D573D191E4EC7DB1B1E4F6` (o bug fazia o download do BIOS do GBA sempre falhar na verificação) |
| `pgm.zip` | Subido ao repo. MD5 `87CC944EEF4C671AA2629A8BA48A08E0`, CRC32 `BF3DD2EF`. Contém 4 arquivos (`pgm_m01s.rom`, `pgm_p01s.u20`, `pgm_p02s.u20`, `pgm_t01s.rom`) | **Registrado + ligado no código**: entrada em `BiosManager.SUPPORTED_BIOS` (`SystemID.PGM`) + `in PGM_ROMS -> listOf("pgm.zip")` em `requiredBiosForRom`. ⚠️ **Testar em device**: o BIOS PGM do MAME também traz `pgm_a01s.rom`/`pgm_b01s.rom`; se o FBNeo reclamar deles, adicioná-los ao `pgm.zip` (o MD5 registrado mudará). |

### 5. Download on-demand dos ROMs reclassificados ("rom not found")

**Sintoma:** após a reclassificação, tocar num jogo `neogeo`/`cps1`/`cps2`/… para baixar dava
*"rom not found"*.

**Causa:** [RomOnDemandManager](../../lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/roms/RomOnDemandManager.kt)
resolve a origem via `RomSystemMapper.toEndpointSystem(systemId)`, lido de `mnemonico_map.json`. Esse
mapa só tinha `fbneo`; os 14 novos `systemId` retornavam `null` → `DownloadResult.NotFound`. Os
arquivos de ROM, porém, continuam hospedados sob o dataset **`luistiagos/fbneo`** no servidor.

**Correção (dados):** em [mnemonico_map.json](../../lemuroid-app/src/main/assets/mnemonico_map.json),
mapear os 14 sub-sistemas de arcade para o endpoint `fbneo`:
`neogeo, cps1, cps2, cps3, dataeast, galaxian, toaplan, taito, psikyo, pgm, kaneko, cave, technos, seta → "fbneo"`.
O destino local do download continua usando o `systemId` real (`roms/<systemId>/<rom>.zip`), casando
com o `fileUri`. Validado contra o endpoint `find_by_file` (HTTP 200 → URL correta) para kof2002
(neogeo), sf2 (cps1), kov (pgm), mvsc (cps2), donpachi (cave).

### 6. Sistemas reclassificados sumiam do catálogo em instalações existentes (migração v24)

**Sintoma:** após rebuild+reinstalação, os novos sistemas (neogeo, cps1, cps2, …) **não apareciam
no catálogo**.

**Causa:** `Room.databaseBuilder().createFromAsset()` só copia o prebuilt DB quando o arquivo do DB
**não existe** no device. Reinstalar por cima (`adb install -r` / Android Studio Run / update da
Play Store) **preserva** o DB antigo → os jogos continuavam com `systemId=fbneo` → as queries de
sistema (`selectSystemsWithCount` → `MetaSystemsViewModel`) não viam neogeo/cps1/etc. O prebuilt DB
empacotado estava correto (validado: neogeo 102, cps1 29, …), mas só instalação limpa o copia.

**Correção — migração one-time v24** em [ManifestQuickLoader](../../retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/catalog/ManifestQuickLoader.kt):
`MANIFEST_SCHEMA_VERSION` 23 → **24**; no bloco `loadedSchema < 24`, para cada romset que o manifest
agora classifica num sub-sistema de arcade, re-aponta a linha `(fbneo, <rom>)` do DB para
`(<sistema>, <rom>)` — `UPDATE OR IGNORE` de `systemId`+`fileUri` (`reassignArcadeSystem`) +
`deleteFbneoByFileName` (remove sobra/duplicata) — e **move** eventual ROM já baixada de
`roms/fbneo/<rom>` para `roms/<sistema>/<rom>`, atualizando `downloaded_roms`. Idempotente e
best-effort (nunca derruba o boot); no-op em instalação limpa (jogos já vêm no sistema certo).
Métodos novos em [GameDao](../../retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/db/dao/GameDao.kt)
e [DownloadedRomDao](../../retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/db/dao/DownloadedRomDao.kt).

Nota: agora `reinstalar por cima` já resolve. Para o **teste imediato** também vale desinstalar/limpar
dados (instalação limpa copia o prebuilt DB direto).

## Pendências / caveats

1. **BIOS no repositório HuggingFace** — resolvido: `neogeo.zip` e `pgm.zip` validados no repo
   (ver seção 4). Testar em device se o `pgm.zip` (4 arquivos) basta pro FBNeo.
