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
- Build: --
- Testes executados: --
- E2E: --
- Desvios do design: --
