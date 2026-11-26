---
tipo: implementacao
demandas: [2026-11-25-resolucao-interna-sistemas-3d]
depende_de: []
---

# TASK-0017 — Expor a resolução interna em PS1, GameCube, DS, Saturn e 3DO, com padrão nativo explícito

- **Criterio:** EARS-1, EARS-2
- **Toca:** `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/GameSystem.kt`,
  `retrograde-app-shared/src/main/res/values/strings.xml`, `retrograde-app-shared/src/main/res/values-pt-rBR/strings.xml`
  — design, "Símbolos tocados" e "Dados". `ExposedSetting` com lista de valores **vazia** nesta task (o menu mostra
  os valores crus do core, o que permite a TASK-0018 lê-los no aparelho). Título "Resolução interna" /
  "Internal resolution". `defaultSettings` nativos explícitos em GC, DS (DeSmuME) e PS1; Saturn e 3DO já têm.
- **Testes novos:** `lemuroid-app/src/test/java/com/swordfish/lemuroid/app/shared/game/GameSystemResolutionSettingTest.kt`
  ::psxExpoeResolucao, ::gamecubeExpoeResolucao, ::ndsExpoeResolucao, ::saturnExpoeResolucao, ::operaExpoeResolucao
  (chave em `exposedSettings` do core certo) e ::padraoNativoNosCinco (`defaultSettings` com o valor nativo) — teste
- **Testes existentes:** `GameSystemSingleScreenTest.kt`, `MsxControllerConfigTest.kt` (leem `GameSystem`)
- **Pronto local:** build verde + testes verdes; no moto g86, a opção aparece em Opções do core de um jogo de cada
  sistema com o valor nativo marcado
- **Publicacao:** próxima versão publicada pelo dono; verificação da `spec.md`
- **Rollback:** reverter o commit; as preferências `cv_<sistema>_<chave>` gravadas ficam órfãs e são ignoradas

#### Execucao
- Build: `./gradlew :lemuroid-app:testFreeBundleDebugUnitTest` compila app + shared (freeBundleDebug) verde;
  `./gradlew ktlintCheck --continue` verde depois de regenerar `retrograde-app-shared/config/ktlint/baseline.xml`
  (só os 5 `no-blank-line-in-list` antigos do `GameSystem.kt`, deslocados pelas linhas novas).
- Testes executados: `GameSystemResolutionSettingTest` 6/6 (novo), `GameSystemSingleScreenTest` 11/11,
  `MsxControllerConfigTest` 1/1.
- E2E: **pendente** — nenhum aparelho no `adb devices` em 2026-11-25. Falta abrir um jogo de cada sistema no
  moto g86 e ver "Resolução interna" com o valor nativo marcado (feito junto da captura da TASK-0018).
- Valores nativos usados nos `defaultSettings` (de onde vieram):
  - `pcsx_rearmed_neon_enhancement_enable=disabled`, `opera_high_resolution=disabled`: liga/desliga do core.
  - `dolphin_efb_scale=1`: lido em execução pelo `printRetroVariables`
    (`docs/backlog/arquivados/2026-10-25-suporte-nintendo-wii.md:275`). No binário o rótulo é `1x Native (640x528)`.
  - `desmume_internal_resolution=256x192`: 1º valor após a descrição, no `.so` arm64.
  - Saturn: no `.so`, `Resolution Mode (restart); original|2x|4x|720p|1080p|4k` — o core já declara "(restart)",
    insumo para a EARS-5 na TASK-0018.
- Desvios do design: um título por core (`setting_<core>_internal_resolution`, en + pt-BR, mesmo texto) em vez de
  uma string única, para a TASK-0018 poder acrescentar "(reiniciar)" só onde o core pede.
