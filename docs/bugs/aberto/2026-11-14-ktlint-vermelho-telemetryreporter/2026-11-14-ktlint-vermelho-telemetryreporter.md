# `:lemuroid-app:ktlintCheck` vermelho na base: 18 apontamentos novos em `TelemetryReporter.kt` (commit 3145b78)

- **Origem:** achado lateral do pipeline em TASK-0001 (execução 20261114-1244)

**Esperado:** `./gradlew :lemuroid-app:ktlintCheck` passa (CLAUDE.md, "Estilo: ktlint com baseline": o passivo está
congelado e o check só falha em violação nova).

**Observado (2026-11-14, lane wt-1 do pipeline, base `aa3e3df`):** `ktlintMainSourceSetCheck` falha com 18 apontamentos,
todos em `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/telemetry/TelemetryReporter.kt`:

- linhas 208 e 228: `try { ... } catch (...) { ... }` numa linha só (`standard:try-catch-finally-spacing` 8,
  `standard:statement-wrapping` 4);
- linhas 248 e 251: assinatura de função com parâmetro na mesma linha do parêntese (`standard:function-signature` 6).

O arquivo não mudou desde `3145b78 feat(telemetria): medida(id, resultado) para o /logMedida (T28 parte 1)` e é o mesmo
em `origin/version10`. O `ktlintTestSourceSetCheck` passa.

**Evidência:** `lemuroid-app/build/reports/ktlint/ktlintMainSourceSetCheck/ktlintMainSourceSetCheck.txt` da lane
(comando: `./gradlew :lemuroid-app:ktlintCheck -q`, EXIT=1).

**Correção provável:** quebrar as quatro construções conforme as regras (não congelar no baseline: é código novo).
