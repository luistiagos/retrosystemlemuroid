---
tipo: implementacao
demandas: [2026-11-25-resolucao-interna-sistemas-3d]
depende_de: []
---

# TASK-0020 — Traduzir para pt-BR os valores da resolução interna do Dreamcast, Naomi e Atomiswave

- **Criterio:** EARS-10
- **Toca:** `retrograde-app-shared/src/main/res/values-pt-rBR/strings.xml` — design, "Símbolos tocados" (Strings):
  `value_flycast_resolution_native|2x|3x|4x` existem só em `values/`. Acrescentar "Nativa (640x480)",
  "2x (1280x960)", "3x (1920x1440)", "4x (2560x1920)".
- **Testes novos:** `lemuroid-app/src/test/java/com/swordfish/lemuroid/app/shared/game/GameSystemResolutionSettingTest.kt`
  ::rotulosDeResolucaoTraduzidosPtBr (lê os dois `strings.xml` do disco: todo `value_*resolution*` de `values/` existe
  em `values-pt-rBR/`) — teste; controle positivo: o teste falha na base atual. e2e
  `evidencias/aceitacao.md`::dreamcast-valores-pt-br (app em português, Opções do core de um jogo de Dreamcast) — e2e
- **Testes existentes:** --
- **Pronto local:** build verde + testes verdes; print do menu do Dreamcast em português
- **Publicacao:** próxima versão publicada pelo dono
- **Rollback:** reverter o commit (volta a mostrar em inglês)

#### Execucao
- Build: --
- Testes executados: --
- E2E: --
- Desvios do design: --
