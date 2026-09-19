# [BUG] "O núcleo do libretro não foi carregado" (Sega 32X, 3DO e outros sistemas novos)

**Data:** 2026-06-20
**Status:** CORRIGIDO
**Severidade:** ALTA — sistemas novos não lançavam nenhum jogo
**Branch:** version9

---

## Sintoma

Ao lançar uma ROM de **Sega 32X** (e **3DO**, e outros sistemas novos), o app mostrava
*"O núcleo do libretro não foi carregado..."*.

## Causa-raiz

Os cores desses sistemas são **baixados dinamicamente** de uma **tag fixa** do repositório de
cores: `CORES_VERSION = "1.18.0"` → `raw.githubusercontent.com/luistiagos/libretrocores/1.18.0/lemuroid_core_<core>/...`.

Os 18 cores novos (picodrive, opera, dolphin, yabasanshiro, puae, neocd, o2em, mednafen_pcfx,
atari800, hatari, freechaf, gw, fake08, lowresnx, uzem, arduous, sameduck, virtualjaguar)
existiam no **branch `main`** do repo de cores, mas **não na tag `1.18.0`** — a tag foi criada
**antes** desses cores serem adicionados. Resultado: o download dava **HTTP 404** → core
indisponível → "núcleo não carregado".

### Evidência
- `HEAD` de `raw.githubusercontent.com/.../1.18.0/lemuroid_core_picodrive/.../picodrive_libretro_android.so` → **404**.
- O mesmo arquivo na ref `main` → **200**.
- Core de controle (`genesis_plus_gx`, antigo) na `1.18.0` → **200** (por isso sistemas antigos funcionavam).
- `git`: a tag `1.18.0` (2026-06-01) não continha o módulo; os cores entraram em `main` depois.

## Correção

Opção escolhida: **nova versão de cores `1.19.0`**.

1. **Repo de cores (`luistiagos/libretrocores`):** criada e publicada a tag **`1.19.0`** no
   `main` atual (que contém todos os 18 cores novos). Validado: as URLs em `1.19.0` retornam
   **200** para todos.
2. **App ([CoreDownloader.kt](../../../retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/core/CoreDownloader.kt)):**
   `CORES_VERSION` bumpado de `"1.18.0"` → `"1.19.0"` (fonte única; o `CoreUpdaterImpl`
   reaproveita). Como a versão mudou, os cores passam a ser baixados para `cores/1.19.0/`
   (downloads frescos, uma vez por core).

> Mudar string/constante de versão **não** altera schema do app. Nenhum bump de DB necessário.

## Validação
- URLs de todos os 18 cores em `1.19.0` → **200**.
- `:lemuroid-app:assembleFreeBundleDebug` — **BUILD SUCCESSFUL** — instalado em `ZY32LMNN9B`.
- (No 32X, o core passou a carregar; o problema seguinte — imagens de CD precisarem de BIOS —
  está documentado em [2026-06-20-rom-corrompida-bios-sega32x.md](2026-06-20-rom-corrompida-bios-sega32x.md).)

## Lição
O download de core aponta para uma **tag imutável** (`CORES_VERSION`). Ao adicionar cores ao
repo, é obrigatório **criar/publicar a tag correspondente** (ou bumpar `CORES_VERSION` para uma
tag que os contenha). Cores no `main` mas fora da tag = 404 em runtime.
