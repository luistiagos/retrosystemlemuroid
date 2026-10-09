# Backlog: sistemas adicionáveis via libretro (que ainda não temos)

**Status:** backlog / levantamento (a priorizar)
**Objetivo:** catalogar quais consoles/computadores **ainda não suportados** dá pra adicionar ao Lemuroid usando cores libretro (do buildbot Android), com viabilidade, core a usar, custo e observações.

---

## 1. O que já temos hoje (referência — `SystemID.kt`, ~50 sistemas)
- **Nintendo:** NES, SNES, GB, GBC, GBA, N64, NDS, 3DS, Virtual Boy, Pokémon Mini
- **Sega:** SG-1000, SC-3000, Master System, Game Gear, Genesis/Mega Drive, Sega CD, Dreamcast *(DC quebrado — ver backlog do Flycast)*
- **Sony:** PlayStation (PSX), PSP
- **NEC:** PC Engine, PC Engine CD
- **Atari:** 2600, 5200, 7800, Lynx
- **SNK:** Neo Geo (cartucho, via FBNeo), Neo Geo Pocket, NGP Color
- **Bandai:** WonderSwan, WonderSwan Color
- **Outros consoles:** 3DO, ColecoVision, Intellivision, Vectrex, Watara Supervision
- **Computadores:** DOS, MSX, MSX2, Commodore 64, ZX Spectrum, Amstrad CPC
- **Arcade:** FBNeo, MAME2003-Plus, CPS1/2/3 + subsets FBNeo (DataEast, Galaxian, Toaplan, Taito, Psikyo, PGM, Kaneko, Cave, Technos, Seta)
- **Fantasy/indie:** Pico-8 (fake08), Vircon32

---

## 2. Faltando e adicionável via libretro

### Tier 1 — Vale a pena (cores Android maduros, bom catálogo)
| Sistema | Core libretro | Observação |
|---|---|---|
| **Sega Saturn** | Beetle Saturn / **YabaSanshiro** / Kronos | Lacuna grande. Beetle = preciso mas pesado; YabaSanshiro roda melhor em Android forte. Precisa BIOS. Marcar como sistema "heavy" no `HeavySystemFilter`. |
| **Commodore Amiga (+CD32)** | **PUAE** (puae) | Lacuna grande. Precisa Kickstart ROMs. |
| **Sega 32X** | **PicoDrive** | `genesis_plus_gx` NÃO faz 32X — precisa do PicoDrive (core novo). PicoDrive também faz MD/MS/GG/SegaCD, mas no app esses já são do genesis_plus_gx. |
| **Neo Geo CD** | **NeoCD** | Distinto do cartucho NeoGeo (FBNeo). Precisa BIOS NeoCD. |
| **PC-FX** | Beetle PC-FX | Nicho mas catálogo definido. Precisa BIOS. |
| **Atari Jaguar** | Virtual Jaguar | Compatibilidade limitada (core fraco) — gerenciar expectativa. |

### Tier 2 — Computadores retrô (muitos reaproveitam infra existente)
| Sistema | Core | Observação |
|---|---|---|
| **VIC-20 / Plus4 / C128 / PET** | VICE (xvic / xplus4 / x128 / xpet) | Já temos `VICE_X64SC` (C64) — mesma família, **barato**. |
| **Amstrad GX4000** | **cap32** | Mesmo core do Amstrad CPC que **já temos** → **muito barato**. |
| **Atari ST** | Hatari | Precisa TOS. |
| **Sharp X68000** | PX68k | Precisa BIOS/IPL. Bom catálogo JP. |
| **NEC PC-98** | Neko Project II Kai (np2kai) | Bom catálogo JP. |
| **NEC PC-88** | QUASI88 | Nicho. |
| **Tandy CoCo / Dragon 32** | XRoar | Nicho. |
| **Thomson MO/TO** | Theodore | Nicho (FR). |
| **Sam Coupé** | SimCoupe | Nicho. |

### Tier 3 — Microconsoles / fantasy / nicho (baratos, catálogo pequeno)
- **DSi** — melonDS (core que **já temos**; habilitar + firmware/NAND DSi).
- **TIC-80, WASM-4, Uzebox, Arduboy** — fantasy/microconsoles (como Pico-8/Vircon32 atuais).
- **Game & Watch** (gw), **Fairchild Channel F** (freechaf), **Mega Duck** (SameDuck), **Gamate / Creativision** (cores próprios ou via MAME).

### Tier 4 — Tecnicamente libretro, mas impraticável no Android (não recomendado)
- **GameCube/Wii** (Dolphin) — pesadíssimo.
- **PS2** (Play!) — fraco; já existe o projeto **ARMSX2** separado para PS2.
- **Philips CD-i** (SAME CDi) — parcial/instável.
- **Sega Naomi / Atomiswave** — usam **Flycast** → mesmo motor do Dreamcast (quebrado). Só após resolver o backlog do DC (`dreamcast-flycast-standalone-embed.md`).

---

## 3. Custo de adicionar um sistema (checklist)
1. `SystemID` novo (+ `dbname`) em `SystemID.kt`.
2. `CoreID` novo (nome libretro + `.so`) em `CoreID.kt` — **a menos que reuse um core já existente**.
3. Bloco `GameSystem` em `GameSystem.kt` (extensões suportadas, `SystemCoreConfig`, BIOS exigido, defaults/exposed settings, controllers).
4. Suporte no scanner (`ScanOptions` / `SerialScanner` se precisar de detecção especial) + `MetaSystemID` se aplicável.
5. O `.so` do core: baixar do buildbot e por em `lemuroid-cores/lemuroid_core_<x>/jniLibs/<abi>/` **e** `bundled-cores/.../jniLibs/<abi>/` (lembrar: `materializeNativeLibs` só recopia se faltar/<10KB).
6. `HeavySystemFilter`: classificar se o sistema é pesado (Saturn/Amiga/PC-98 → restringir em devices fracos), espelhando PSP/3DS/N64.
7. **Catálogo:** no modelo on-demand do app, o sistema só é útil com ROMs/covers no manifest (`catalog_manifest.txt` + alias em `manifest_alias.json`) e, se mudar o formato, bump de `MANIFEST_SCHEMA_VERSION`/`CATALOG_VERSION` + regenerar o prebuilt DB.
8. BIOS: se exigir, somar ao `BiosManager`/`BiosDownloader` (repo HuggingFace `luistiagos/bios`).
9. Shader default em `ShaderChooser` + strings/labels (i18n).

## 4. Ganhos mais baratos (reusam core já embarcado)
- **Amstrad GX4000** (cap32) — só novo SystemID/GameSystem.
- **DSi** (melonDS) — habilitar + BIOS DSi.
- **Variantes VICE** (VIC-20/Plus4/C128/PET) — core VICE já presente (x64sc); cada máquina é um binário VICE distinto, mas a infra/integração é a mesma.

## 5. Referências
- Cores Android: `https://buildbot.libretro.com/nightly/android/latest/<abi>/<core>_libretro_android.so.zip`
- Mapa folder→dbname do catálogo: `assets/manifest_alias.json`
- Backlog relacionado: `docs/backlogs/dreamcast-flycast-standalone-embed.md` (Dreamcast/Naomi/Atomiswave dependem disso).
