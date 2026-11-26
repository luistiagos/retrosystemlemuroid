---
tipo: implementacao
demandas: [2026-11-26-gamecube-mario-kart-double-dash-trava-tela-dolby-s20-fe]
depende_de: []
---

# TASK-0022 — GameCube: expor "Modo dual-core" no menu do jogo

Feita **à mão, fora do pipeline** (decisão do dono, 2026-11-26): caminho B do modo `bug` do motor.
Plano e símbolos já abertos: seção "O que fazer" da demanda
(`../../2026-11-26-gamecube-mario-kart-double-dash-trava-tela-dolby-s20-fe.md`). Não refazer a análise.

## O que muda

- `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/GameSystem.kt`, bloco
  `SystemID.GAMECUBE` / `CoreID.DOLPHIN`, `exposedSettings`: + `ExposedSetting("dolphin_main_cpu_thread",
  R.string.setting_dolphin_dual_core)`, sem `values` (vira switch pelo `BOOLEAN_SET`). `defaultSettings` **não muda**.
- `retrograde-app-shared/src/main/res/values/strings.xml`: `setting_dolphin_dual_core` = "Dual-core mode".
- `retrograde-app-shared/src/main/res/values-pt-rBR/strings.xml`: `setting_dolphin_dual_core` = "Modo dual-core".
- `retrograde-app-shared/config/ktlint/baseline.xml`: regenerar se a inserção deslocar passivo do `GameSystem.kt`
  (pitfall do baseline por `linha:coluna`, `CLAUDE.md`), conferindo que só passivo antigo se moveu.

## Teste que prova

- Unidade (`lemuroid-app/src/test/java/com/swordfish/lemuroid/app/shared/game/`): `dolphin_main_cpu_thread` está nas
  `exposedSettings` de GAMECUBE/DOLPHIN e **não** está nas `defaultSettings`. Controle positivo: o teste falha no
  código de antes (rodar contra cópia/worktree da base, nunca `git stash`).
- E2E no moto g86: MKDD (Europe `.rvz`) ▸ menu ▸ opções: switch "Modo dual-core" ligado; desligar, sair, reabrir; no
  `logcat` do PID do `:game`, `CPU Thread separate = No`; o jogo passa da abertura. Religar e conferir
  `CPU Thread separate = Yes`. O mesmo switch aparece na UI de TV (mesmo `CoreOptionsPreferenceHelper`), se houver
  como abrir.
- `./gradlew ktlintCheck` completo e `:lemuroid-app:testFreeBundleDebugUnitTest` verdes.

#### Execucao

- Build: (a preencher)
- Testes executados: (a preencher)
- E2E: (a preencher)
- Desvios: (a preencher)
