---
resultado: passou
base: 9aecbf473353a3ef0f565768dfbb475d9d106356
---
# Aceitação de 2026-11-14-ktlint-vermelho-telemetryreporter

Lane wt-1 = ramo principal (`origin/version10` @ `9aecbf4`), com TASK-0003 (`09dbea7c1c`) integrada
(`git merge-base --is-ancestor 09dbea7c1c HEAD` -> sim). Execução 20261114-1912, 2026-11-14.

## O que provar (Passo 1)

A demanda é um gate de estilo do build, sem caminho de usuário (o Plano de teste da demanda dispensa o T2 e o aparelho).
O sintoma é `./gradlew :lemuroid-app:ktlintCheck` sair 1 com 18 apontamentos em `TelemetryReporter.kt` (linhas
208/228/248/251). Itens:

1. **Sintoma ausente:** `ktlintCheck` completo (Main + Test + KotlinScript) sai 0 no ramo principal, e
   `TelemetryReporter.kt` não aparece em nenhum relatório.
2. **Correção no ar sem congelar de novo:** as 4 construções estão reformatadas no ramo principal e o bloco
   `TelemetryReporter.kt` não está mais no `lemuroid-app/config/ktlint/baseline.xml`.
3. **Regressão (T3):** o `build` do helper compila e os testes de unidade (incluindo `MedidaEnvioTest`) não têm falha nova.

## Resultado (Passo 5)

1. `./gradlew :lemuroid-app:ktlintCheck --rerun-tasks -q` na lane -> **EXIT=0**. Os três relatórios
   `lemuroid-app/build/reports/ktlint/{ktlintMainSourceSetCheck,ktlintTestSourceSetCheck,ktlintKotlinScriptCheck}/*.txt`
   foram gerados às 19:43–19:44 e têm 0 bytes. `grep -rl TelemetryReporter lemuroid-app/build/reports/ktlint/` não
   achou nada. Evidência: `aceitacao-ktlintcheck.log`. O controle positivo (base sem a correção: EXIT=1, 18
   apontamentos) já está registrado no TASK-0003 (`reports/TASK-0003-evidencias/ktlint-base-relatorio.txt`) e em
   `tasks/aberto/evidencias/ktlint-base.log`.
2. `TelemetryReporter.kt` no HEAD: `postJson`/`getFallback` (finally, linhas 208–211 e 231–234) com o
   `try { conn?.disconnect() } catch (ignored: Throwable) { }` quebrado em várias linhas. O `catch` continua engolindo
   qualquer `Throwable`. `cap` (254) e `capTail` (260) têm um parâmetro por linha, e os corpos não mudaram.
   `grep -c TelemetryReporter lemuroid-app/config/ktlint/baseline.xml` -> 0.
3. `python pipeline.py build --bug TASK-0004` -> **BUILD SUCCESSFUL**, EXIT=0. `testFreeBundleDebugUnitTest`: 16 suites,
   96 testes, 0 falhas, 0 erros, 1 pulado. É o mesmo número das rodadas do TASK-0003, e `MedidaEnvioTest` está
   presente e verde. O Gradle marcou `compileFreeBundleDebugKotlin` e `testFreeBundleDebugUnitTest` como UP-TO-DATE.
   Isso quer dizer que as entradas são idênticas às da rodada anterior nesta lane, e de lá para cá só entraram commits
   de `docs/`. Os resultados valem para este HEAD. Evidência: `aceitacao-build.log`.

Sem instalação no aparelho e sem logcat: a mudança é só de formatação, sem comportamento em runtime a observar. O
projeto não tem `deploy`.
