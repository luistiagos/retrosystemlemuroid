# [BUG] Sistemas com logos vetoriais improvisados e ausentes do SystemLogoResolver

**Data:** 2026-10-05  
**Status:** ✅ Resolvido  
**Severidade:** Média (inconsistência visual em sistemas na UI móvel e Android TV Leanback)  
**Branch:** version9  

---

## 1. Sintoma

Sistemas recém-adicionados ou recentemente visíveis no catálogo (exemplo: **Game & Watch** / `gw`) exibiam um desenho vetorial (card vermelho com botões desenhados em `<vector>` XML) em vez dos logos oficiais estilizados (normal e hover) extraídos do RetroBat (`logos_extracted/all-systems/`). Ao passar o foco no Android TV ou selecionar o sistema, não havia efeito de hover.

## 2. Investigação e Causa-raiz

### Por que o logo era vetorial e por que "sumiu / reapareceu"?

1. **Origem do desenho vetorial:**
   - No commit `73e0aeef` (10/06/2026), 4 novos sistemas foram adicionados: `gw` (Game & Watch), `amiga` (Amiga 500), `atarist` (Atari ST) e `pcfx` (PC-FX).
   - O desenvolvedor seguiu à risca o guia `docs/prompts/adicionar-novo-sistema-libretro.md` (Passo 13), que instruía textualmente: *"Não use PNG. Use sempre vector drawable XML"*.
   - Como resultado, foram criados manualmente arquivos `<vector>` improvisados:
     - `game_system_gw.xml`
     - `game_system_amiga.xml`
     - `game_system_atarist.xml`
     - `game_system_pcfx.xml`
   - Porém, dois dias antes (commit `a29ad4c0`, 08/06/2026), o projeto havia migrado para a arquitetura de logos rasterizados RetroBat de alta qualidade com pares **normal** (277x192) e **hover** (333x230) gerenciados por `SystemLogoResolver.kt`, extraídos em `logos_extracted/all-systems/`.

2. **Por que o usuário teve a percepção de que o logo "havia sumido hoje"?**
   - Até 02/10/2026 (commit `bc9c3432`), a pasta `gameandwatch/` do `catalog_manifest.txt` não possuía alias no `manifest_alias.json`. O `ManifestQuickLoader` descartava todos os 45 jogos de Game & Watch em qualquer inicialização. Com 0 jogos, o Game & Watch ficava oculto ou vazio na UI.
   - Quando o bug do catálogo foi corrigido em 02/10/2026, o sistema finalmente passou a aparecer na UI com seus jogos — momento em que o desenho vetorial improvisado do commit de junho ficou visível pela primeira vez ao usuário.

### Auditoria Completa dos 79 Sistemas

Uma varredura automatizada foi executada em todos os 79 `SystemID` e `MetaSystemID` contra os drawables do projeto e o `SystemLogoResolver.kt`:

1. **4 sistemas usavam XML vetorial improvisado e estavam sem hover:**
   - `GAME_WATCH` (`gw`): tinha `game_system_gw.xml`.
   - `AMIGA` (`amiga`): tinha `game_system_amiga.xml`.
   - `ATARI_ST` (`atarist`): tinha `game_system_atarist.xml`.
   - `PCFX` (`pcfx`): tinha `game_system_pcfx.xml`.

2. **5 sistemas já tinham os PNGs normal e hover no drawable, mas foram omitidos do `SystemLogoResolver.kt`:**
   - `GAMECUBE` (`gc`)
   - `SATURN` (`saturn`)
   - `JAGUAR` (`jaguar`)
   - `ODYSSEY2` (`odyssey2`)
   - `NEOCD` (`neocd`)

Todos os outros 70 sistemas já possuíam os pares normais/hover em PNG e estavam mapeados no `SystemLogoResolver`.

## 3. Correção Aplicada

1. **Cópia dos Logos de Alta Qualidade:**
   - `109-gw__theme-gameandwatch-normal.png` (35.666 B) -> `game_system_gw.png`
   - `109-gw__theme-gameandwatch-hover.png` (87.601 B) -> `game_system_gw_hover.png`
   - `061-amiga500-normal.png` (51.722 B) -> `game_system_amiga.png`
   - `061-amiga500-hover.png` (119.903 B) -> `game_system_amiga_hover.png`
   - `074-atarist-normal.png` (46.869 B) -> `game_system_atarist.png`
   - `074-atarist-hover.png` (135.189 B) -> `game_system_atarist_hover.png`
   - `053-pcfx-normal.png` (32.919 B) -> `game_system_pcfx.png`
   - `053-pcfx-hover.png` (68.936 B) -> `game_system_pcfx_hover.png`

2. **Backup dos XMLs Vetoriais Antigos:**
   - Renomeados para `bkp_game_system_<id>.xml` seguindo a convenção histórica do repositório (`bkp_game_system_nes.xml`, `bkp_game_system_snes.xml`, etc.), evitando colisão de IDs de recursos no AAPT.

3. **Atualização do `SystemLogoResolver.kt`:**
   - Adicionadas as 9 entradas faltantes no `when (metaSystem)`:
     ```kotlin
     MetaSystemID.GAMECUBE -> R.drawable.game_system_gc_hover
     MetaSystemID.SATURN -> R.drawable.game_system_saturn_hover
     MetaSystemID.JAGUAR -> R.drawable.game_system_jaguar_hover
     MetaSystemID.ODYSSEY2 -> R.drawable.game_system_odyssey2_hover
     MetaSystemID.NEOCD -> R.drawable.game_system_neocd_hover
     MetaSystemID.AMIGA -> R.drawable.game_system_amiga_hover
     MetaSystemID.PCFX -> R.drawable.game_system_pcfx_hover
     MetaSystemID.GAME_WATCH -> R.drawable.game_system_gw_hover
     MetaSystemID.ATARI_ST -> R.drawable.game_system_atarist_hover
     ```
   - Total em `SystemLogoResolver.kt`: **79 de 79 sistemas (100% de cobertura)**.

4. **Atualização do Guia de Engenharia:**
   - Atualizado `docs/prompts/adicionar-novo-sistema-libretro.md` (Passo 13 e tabela inicial) para orientar o uso dos PNGs de `logos_extracted/all-systems/` e registro no `SystemLogoResolver.kt`.

## 4. Validação

- Auditoria automatizada (`check_systems.py` e `check_matrix.py`): 79/79 `norm:PNG, hov:PNG`.
- `./gradlew :retrograde-app-shared:compileDebugKotlin`: `BUILD SUCCESSFUL`.
- `./gradlew :lemuroid-app:compileFreeBundleDebugKotlin`: `BUILD SUCCESSFUL` (recursos processados e mergeados pelo AAPT sem erros).
