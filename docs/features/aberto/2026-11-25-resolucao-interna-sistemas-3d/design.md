---
spec: spec.md
---

# Design — resolução interna nos sistemas 3D e nome claro no N64

## Símbolos tocados (abertos em 2026-11-25)

- `retrograde-app-shared/.../lib/library/GameSystem.kt` — `SystemCoreConfig` de cada sistema:
  - PS1 (`CoreID.PCSX_REARMED`): expõe `pcsx_rearmed_frameskip`; avançado `pcsx_rearmed_drc`; default só `drc=disabled`.
  - GameCube (`CoreID.DOLPHIN`): **sem** `exposedSettings` nem `defaultSettings`.
  - Nintendo DS (`CoreID.DESMUME`, 1º da lista; `CoreID.MELONDS` 2º): expõe `desmume_screens_layout`, `desmume_frameskip`.
  - Saturn (`CoreID.YABASANSHIRO`): default `yabasanshiro_resolution_mode=original` (comentário: "evita FBO de 704x512 x4";
    a tela preta do Saturn **não** vinha daí).
  - 3DO (`CoreID.OPERA`): default `opera_high_resolution=disabled`, sem comentário, desde `68b7920`.
  - N64 (`CoreID.MUPEN64_PLUS_NEXT`): expõe `mupen64plus-43screensize` e `-169screensize` **sem** lista de valores;
    default `43screensize=320x240`.
  - Dreamcast/Naomi/Atomiswave: `reicast_internal_resolution` com 4 `ExposedSetting.Value` (`value_flycast_resolution_*`).
- `retrograde-app-shared/.../lib/library/ExposedSetting.kt` — `ExposedSetting(key, titleId, values)`; `Value(key, titleId)`.
- `lemuroid-app/.../shared/coreoptions/LemuroidCoreOption.kt` — `getEntries`: `values` vazio → todos os valores que o core
  declara (primeira letra maiúscula); senão `getCorrectExposedSettings` = só os `Value` cuja chave o core declara
  (**valor fora da lista some em silêncio**; lista inteira fora = opção sem itens). `getCurrentIndex`: valor atual fora
  da lista → índice 0 (a tela mostra o 1º item, o valor gravado não muda).
- `lemuroid-app/.../shared/coreoptions/CoreOption.kt::fromLibretroDroidVariable` — valores vêm da `description` que o
  core reporta em execução (`"Nome; v1|v2|…"`). Ou seja: a lista válida é a do core rodando, não a de um fonte.
- `lemuroid-app/.../shared/game/BaseGameActivity.kt::displayOptionsDialog`/`transformExposedSetting` — monta o menu a
  partir de `exposedSettings`; chave que o core não reporta é descartada (`firstOrNull … ?.let`).
- `retrograde-app-shared/.../lib/core/CoreVariablesManager.kt::getOptionsForCore` — `defaultSettings` + preferências
  do usuário; `retrieveCustomCoreVariables` **só lê preferência de chave exposta**. Chave da preferência:
  `cv_<namespace>_<chave do core>` (não depende do título).
- `lemuroid-app/.../shared/game/viewmodel/GameViewModelRetroGameView.kt::updateCoreVariables` — troca no menu vai ao
  core na hora (`retroGameView.updateVariables`); se o core aplica na hora ou só no próximo boot é do core.
- Strings: títulos e valores de opção de core moram em `retrograde-app-shared/src/main/res/values{,-pt-rBR}/strings.xml`.
  `value_flycast_resolution_*` existe só em `values/` — causa da EARS-10.
- Teste de molde: `lemuroid-app/src/test/.../shared/game/GameSystemSingleScreenTest.kt` (JUnit puro sobre `GameSystem`).

## Caminho

`GameSystem` (chave exposta + valores rotulados + default nativo explícito) → `BaseGameActivity` monta o menu →
`LemuroidCoreOption` filtra pelos valores do core → escolha gravada em `cv_<sistema>_<chave>` →
`CoreVariablesManager` a entrega no boot e `updateCoreVariables` no meio do jogo.

## Portas de mão única

1. **Chave de preferência do N64.** Forma literal mantida: `cv_n64_mupen64plus-43screensize` /
   `cv_n64_mupen64plus-169screensize`, com os **mesmos valores crus** (`640x480`…). Só título e rótulo mudam.
   Descartado: chave/valor novos ("2x") — quebraria a escolha de quem já tem o app (EARS-8).
2. **Chaves novas gravadas no aparelho** (`cv_psx_pcsx_rearmed_neon_enhancement_enable`, `cv_gc_dolphin_efb_scale`,
   `cv_nds_desmume_internal_resolution`, `cv_saturn_yabasanshiro_resolution_mode`, `cv_3do_opera_high_resolution`),
   com valor cru do core. Versão anterior do app não as lê (não estão expostas lá) — rollback inofensivo.
3. **Rótulo de valor** (padrão que os próximos copiam): `Nativa (WxH)` para o nativo, `Nx (WxH)` para múltiplo
   inteiro, `WxH` (ou o nome do core, ex. `720p`) para o resto. Mesmo formato do Dreamcast. Descartado: lista vazia
   (valores crus) — sem rótulo "nativa", contraria EARS-7; descartado também gerar rótulo em runtime — exigiria mudar
   `ExposedSetting.Value` (porta de modelo serializado no Intent do menu) para ganho só cosmético.

## Dados

| chave | quem escreve | quem lê |
|---|---|---|
| as 5 de cima + as 2 do N64 | menu do jogo (mobile e TV) | `CoreVariablesManager` no boot do `:game` |
| `defaultSettings` explícitos de resolução nativa (GC, DS, PS1; Saturn e 3DO já têm) | código | idem — não depender do default do `.so` num rebuild |

## Comportamento em erro

- Core não reporta a chave → `transformExposedSetting` devolve `null` e a opção não aparece (nada quebra). Pego pela
  TASK-0018 (análise) antes do rótulo.
- Valor rotulado que o core não declara → some em silêncio (`getCorrectExposedSettings`). Guarda: teste com a lista
  capturada (EARS-4).
- Resolução alta demais para o aparelho → OOM/GL error dentro do core → caminho de crash existente
  (`BaseGameActivity.isEmulatorFailure`, pitfall 8). Decisão do dono: sem teto.

## Garantias negativas

- Nenhum sistema muda de resolução sozinho: `defaultSettings` nativo em todos os cinco (`GameSystem.kt`), coberto por
  `padraoNativoNosCinco`.
- A escolha do N64 nunca se perde: chaves/valores do N64 inalterados, coberto por `n64ChavesGravadasInalteradas`.

## Varredura

- validação e limites: EARS-4 (só valores do core; sem teto por decisão).
- falha e falha parcial: existente — `BaseGameActivity::transformExposedSetting` (chave ausente some).
- idempotência e duplicata: n/a — escolher o mesmo valor regrava a mesma preferência.
- autorização e limite de taxa: n/a — sem rede.
- concorrência e ordem: existente — `GameViewModelRetroGameView::updateCoreVariables` (fila da GLThread).
- ciclo de vida do dado: EARS-8 (chave do N64), porta 2 (rollback).
- falha de dependência externa: n/a — o `.so` não muda; EARS-4 congela a lista do core atual.
- transições de estado: EARS-5 (aplica na hora ou "(reiniciar)").
- observabilidade: existente — `Timber.i("Updating core variable …")` em `updateCoreVariables`.

## Build e deploy

Só Kotlin e strings; nenhum `.so`, nenhuma migração, nenhum bump de manifest. Chega na próxima versão publicada.

## Rollback

Reverter os commits. Preferências gravadas ficam órfãs e são ignoradas (porta 2).

## Valores declarados pelos cores (preenchido pela TASK-0018)

| sistema | chave | valores (ordem do core) | aplica |
|---|---|---|---|
| PS1 | `pcsx_rearmed_neon_enhancement_enable` | -- | -- |
| GameCube | `dolphin_efb_scale` | -- | -- |
| DS | `desmume_internal_resolution` | -- | -- |
| Saturn | `yabasanshiro_resolution_mode` | -- | -- |
| 3DO | `opera_high_resolution` | -- | -- |
| N64 4:3 | `mupen64plus-43screensize` | -- | -- |
| N64 16:9 | `mupen64plus-169screensize` | -- | -- |
