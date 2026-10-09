# Backlog: Avaliação de Inclusão de Novos Sistemas

> Avaliação de viabilidade de inclusão dos sistemas abaixo no Lemuroid, segundo o critério central de
> [`docs/prompts/adicionar-novo-sistema-libretro.md`](../prompts/adicionar-novo-sistema-libretro.md):
> **só é possível incluir um sistema que tenha um core Libretro com `.so` Android compilado para as 4 ABIs.**
> Sistemas sem core Libretro Android estão fora de escopo (não se porta emulador nativo).
>
> **Data da pesquisa:** 2026-06-03
> **Fonte de verificação dos cores:** buildbot oficial do Libretro — `https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/`

---

## Achados relevantes do código atual

- **Amstrad CPC** (core `cap32`) já está no projeto **e já aceita a extensão `.cpr`** — que é o formato de cartucho do GX4000. Ou seja, jogos do GX4000 já são carregáveis pelo sistema CPC existente.
- A família **VICE** (C64 via `vice_x64sc`) **não usa BIOS externo** — o VICE embute as ROMs de sistema no core. Vale para PET/VIC-20/Plus-4/C128. Teclado virtual já implementado.
- **Watara Supervision** já está implementado (`SystemID.SUPERVISION`, core `potator`).

---

## Avaliação por sistema

| # | Sistema | Core Libretro (Android) | Possível? | Complexidade | Observações |
|---|---------|------------------------|-----------|--------------|-------------|
| 1 | Atari ST | `hatari` ✓ | ✅ Sim | **Média** | Exige BIOS TOS. Computador → teclado + mouse + troca de disquetes. |
| 2 | Amstrad GX4000 | `cap32` ✓ (**já no projeto**) | ✅ Sim | **Baixa** | Mesmo core do Amstrad CPC. `.cpr` **já é aceito** pelo sistema CPC atual — quase "de graça". |
| 3 | Apple II | — | ❌ **Não** | — | Sem core Libretro com build Android. Fora de escopo. |
| 4 | Atari ST *(duplicado de #1)* | `hatari` ✓ | ✅ Sim | **Média** | Mesmo que #1. |
| 5 | Atari Jaguar | `virtualjaguar` ✓ | ✅ Sim | **Média** | Sem BIOS obrigatório. Controle atípico (keypad numérico + A/B/C) → layout custom. Tier MODERATE. |
| 6 | Uzebox | `uzem` ✓ | ✅ Sim | **Baixa** | Sem BIOS, core leve. ⚠️ Biblioteca só homebrew → sem catálogo/covers. |
| 7 | PET 2001 | `vice_xpet` ✓ | ✅ Sim | **Baixa-Média** | Família VICE (template = C64), sem BIOS. Teclado. ⚠️ Muito nicho, sem catálogo. |
| 8 | Commodore VIC-20 | `vice_xvic` ✓ | ✅ Sim | **Baixa-Média** | Idem VICE. |
| 9 | Commodore Plus/4 | `vice_xplus4` ✓ | ✅ Sim | **Baixa-Média** | Idem VICE. |
| 10 | Commodore 128 | `vice_x128` ✓ | ✅ Sim | **Baixa-Média** | Idem VICE. |
| 11 | Amiga | `puae` / `puae2021` ✓ | ✅ Sim | **Média-Alta** | Exige Kickstart (BIOS, várias versões). Multi-disco, WHDLoad, teclado. |
| 12 | Amiga CD32 | `puae` ✓ | ✅ Sim | **Média** | Kickstart + ROM estendida CD32 (BIOS). É console (joypad, sem teclado) → mais simples que o Amiga. |
| 13 | Commodore CDTV | `puae` ✓ | ✅ Sim | **Média** | Kickstart + ROM CDTV (BIOS). Nicho. |
| 14 | Mega Duck | `sameduck` ✓ | ✅ Sim | **Baixa** | Sem BIOS, tipo Game Boy (2 botões). Biblioteca pequena. |
| 15 | Super Cassette Vision | — | ❌ **Não** | — | Sem core Libretro Android dedicado. |
| 16 | Channel F | `freechaf` ✓ | ✅ Sim | **Baixa** | BIOS pequena (2 arquivos). Core leve. |
| 17 | TIC-80 | `tic80` ✓ | ✅ Sim | **Baixa** | Fantasy console, sem BIOS. Padrão idêntico ao PICO-8 (`fake08`) já existente. ⚠️ Sem catálogo de covers. |
| 18 | LowRes NX | `lowresnx` ✓ | ✅ Sim | **Baixa** | Fantasy console, sem BIOS. ⚠️ Homebrew. |
| 19 | Arduboy | `arduous` ✓ | ✅ Sim | **Baixa** | Sem BIOS, `.hex`. ⚠️ Homebrew, sem covers. |
| 20 | NEC PC-8801 | `quasi88` ✓ | ✅ Sim | **Média** | Exige BIOS. Computador japonês, disco + teclado. Nicho. |
| 21 | NEC PC-9801 | `np2kai` ✓ | ✅ Sim | **Média** | Exige BIOS (font + bios). Computador japonês, disco. Nicho. |
| 22 | Oric Atmos 48k | — | ❌ **Não** | — | Sem core Libretro Android. |
| 23 | Othello Multivision | `genesis_plus_gx` ✓ (**já no projeto**) | ⚠️ Sim, mas | **Baixa** | Hardware compatível com SG-1000 (já suportado). Valor marginal — é praticamente o SG-1000. |
| 24 | Philips P2000 | `m2000` ✓ | ✅ Sim | **Média** | Exige BIOS. Computador holandês, nicho. |
| 25 | Philips CD-i | `same_cdi` ✓ | ✅ Sim | **Alta** | Baseado em MAME, pesado/experimental, exige BIOS (`cdimono1`). Tier VERY_HEAVY, performance ruim em Android. |
| 26 | Solarus | — | ❌ **Não** | — | Sem build Android no buildbot; além disso é game-engine que precisa de pacotes de dados. |
| 27 | ScummVM | `scummvm` ✓ | ⚠️ Sim, mas | **Alta** | Core existe, mas jogos são **pastas de dados** (não ROM single-file) — conflita com o modelo de scan/catálogo do Lemuroid. Exige tratamento especial. |
| 28 | Sharp X1 | `x1` ✓ | ✅ Sim | **Média** | Exige BIOS (IPLROM). Computador japonês, nicho. |
| 29 | Sharp X68000 | `px68k` ✓ | ✅ Sim | **Média** | Exige BIOS (`iplrom.dat` + `cgrom.dat`). Disco + teclado. Boa biblioteca de jogos. |
| 30 | Sinclair ZX81 | `81` (EightyOne) ✓ | ✅ Sim | **Baixa** | Sem BIOS, core leve. Teclado mínimo. Nicho. |
| 31 | Spectravideo | `bluemsx` ✓ | ✅ Sim | **Média** | Precisa do `bluemsx` (novo core) + estrutura de system-roms. fMSX atual não cobre SVI. Nicho. |
| 32 | Thomson (MO/TO) | `theodore` ✓ | ✅ Sim | **Baixa-Média** | BIOS embutida no core. Computador francês, teclado. Nicho. |
| 33 | Supervision (Watara) | `potator` ✓ | ⏹️ **Já existe** | — | `SystemID.SUPERVISION` já implementado. Nada a fazer. |
| 34 | GameCube | `dolphin` ✓ | ⚠️ Teórico | **Alta / inviável** | Core existe no buildbot, mas Dolphin-libretro Android é instável e exige hardware muito forte. **Não recomendado.** |
| 35 | Sega Saturn | `yabause` / `mednafen_saturn` ✓ | ✅ Sim | **Alta** | VERY_HEAVY + BIOS. Yabause = lento em ARM; mednafen_saturn = muito pesado. É o exemplo do próprio prompt. |
| 36 | Nintendo Wii | `dolphin` ✓ | ⚠️ Teórico | **Alta / inviável** | Mesmo caso do GameCube, ainda mais pesado. **Não recomendado.** |

---

## Resumo

- **Não possíveis (sem core Android):** Apple II (3), Super Cassette Vision (15), Oric Atmos (22), Solarus (26). → 4 itens.
- **Já existe / desnecessário:** Supervision (33, já implementado); Othello Multivision (23, ≈ SG-1000); GX4000 (2, já carregável via Amstrad CPC).
- **Possíveis mas desaconselhados (inviáveis na prática):** GameCube (34) e Wii (36) — Dolphin no Android.

---

## Candidatos recomendados (melhor custo/benefício primeiro)

1. **Amstrad GX4000** — Baixa. Core já bundled, `.cpr` já aceito. Só formalizar como sistema.
2. **Mega Duck, Channel F, ZX81** — Baixa, biblioteca clássica real, covers prováveis no catálogo.
3. **Atari Jaguar** — Média, mas é um console "de verdade" com demanda alta de usuários.
4. **Sharp X68000, Amiga CD32** — Média, bibliotecas fortes de jogos (mais valor que os micros nicho).
5. **Família VICE (VIC-20/Plus-4/C128/PET)** — Baixa-Média graças ao template C64, porém valor menor (nicho).
6. **Fantasy consoles (TIC-80, LowRes NX, Arduboy, Uzebox)** — Baixa tecnicamente, mas ⚠️ **sem catálogo/covers** (homebrew). O valor central do Lemuroid (catálogo + download sob demanda) não se aplica bem a eles.

---

## Caveats transversais

- **BIOS** (Atari ST, Amiga/CD32/CDTV, PC-8801/9801, X1, X68000, P2000, CD-i, Saturn): cada um precisa de MD5/CRC32 reais + upload no HuggingFace (passo 5 do prompt) — bump de complexidade e dependência de fornecer os arquivos.
- **Computadores** (todos os micros): exigem teclado virtual + scan por pasta. O padrão já existe (C64/MSX/CPC/ZX), mas a UX é sempre pior que a de consoles.
- **Catálogo**: o `catalog_manifest.txt` é gerado offline e só faz sentido com covers + popularidade. Sistemas homebrew/nicho entrariam sem catálogo embarcado — apareceriam apenas via importação manual de ROMs.

---

## Lista original do backlog (referência)

```
1  Atari ST
2  Amstrad GX4000
3  Apple 2
4  Atari ST
5  Atari Jaguar
6  Uzebox
7  Pet 2001 Series
8  Commodore vic 20
9  Commodore plus/4
10 Commodore 128
11 Amiga
12 Amiga CD 32
13 Commodore CD TV
14 Mega Duck
15 Super Cassete Vision
16 Channel F
17 Tic-80
18 Lowres NX
19 Arduboy
20 Nec PC8801
21 Nec PC9801
22 Oric Atmos 48k
23 Othello Multivision
24 Philips P2000
25 Philips CDI
26 Solarus
27 Scumm VM
28 Sharp X1
29 Sharp X68000
30 Sinclair ZX81
31 Spectravideo
32 Thompson
33 Supervision
34 Gamecube
35 Sega Saturn
36 Nintendo Wii
```
