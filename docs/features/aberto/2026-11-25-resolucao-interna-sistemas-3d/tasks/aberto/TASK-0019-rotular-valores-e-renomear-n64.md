---
tipo: implementacao
demandas: [2026-11-25-resolucao-interna-sistemas-3d]
depende_de: [TASK-0018]
---

# TASK-0019 — Rotular os valores de resolução dos seis sistemas e renomear as opções do N64

- **Criterio:** EARS-3, EARS-4, EARS-5, EARS-6, EARS-7, EARS-8, EARS-9
- **Toca:** `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/library/GameSystem.kt`,
  `retrograde-app-shared/src/main/res/values/strings.xml`, `retrograde-app-shared/src/main/res/values-pt-rBR/strings.xml`
  — design, "Portas de mão única" 1 e 3 e a tabela preenchida pela TASK-0018. Cada `ExposedSetting` de resolução
  (os cinco + as duas do N64) ganha um `ExposedSetting.Value` por valor que o core declara, **todos, sem teto**,
  com rótulo `Nativa (WxH)` / `Nx (WxH)` / `WxH`. Título com "(reiniciar)" onde a TASK-0018 viu que só aplica no boot.
  N64: títulos "Resolução interna (4:3)" / "(16:9)", chaves e valores crus inalterados.
- **Testes novos:** `lemuroid-app/src/test/java/com/swordfish/lemuroid/app/shared/game/GameSystemResolutionSettingTest.kt`
  ::valoresContidosNosDeclaradosPeloCore (todo `Value` está na lista capturada **e** toda a lista capturada tem
  `Value` — sem teto), ::n64ValoresRotulados, ::n64ChavesGravadasInalteradas — teste;
  e2e `evidencias/aceitacao.md`::*-2x-print-antes-depois (5), ::n64-titulos, ::n64-valores,
  ::n64-escolha-sobrevive-update (app publicado com 640x480 escolhido → instalar por cima → continua 640x480),
  ::tv-dpad-resolucao (AVD `lemu_tv_api25`), ::contagem-de-valores-por-sistema — e2e.
  Controle positivo de `valoresContidos…`: incluir um valor inexistente e ver o teste falhar.
- **Testes existentes:** `GameSystemResolutionSettingTest.kt` (TASK-0017), `GameSystemSingleScreenTest.kt`
- **Pronto local:** build verde + testes verdes; roteiro e2e acima no moto g86 e no AVD de TV
- **Publicacao:** próxima versão publicada pelo dono; verificação da `spec.md`
- **Rollback:** reverter o commit; as escolhas gravadas são valores crus do core e continuam válidas na versão anterior

#### Execucao
- Build: --
- Testes executados: --
- E2E: --
- Desvios do design: --
