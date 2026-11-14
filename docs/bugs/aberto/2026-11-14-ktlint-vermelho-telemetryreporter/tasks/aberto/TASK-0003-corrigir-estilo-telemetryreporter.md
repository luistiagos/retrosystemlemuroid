---
tipo: implementacao
demandas: [2026-11-14-ktlint-vermelho-telemetryreporter]
depende_de: []
---
# Corrigir o estilo das 4 construções do TelemetryReporter e tirar o bloco dele do baseline do ktlint

## Objetivo

`./gradlew :lemuroid-app:ktlintCheck` volta a sair 0. Os 18 apontamentos de `TelemetryReporter.kt` são passivo
antigo: estavam congelados no baseline nas linhas 191/211/231/234 e o commit `3145b78` os empurrou 17 linhas para
baixo (o baseline casa por `linha:coluna:regra`). A causa e as provas estão no doc da demanda, seção "Planejamento".
Corrija as construções. Não congele de novo.

## O que mudar

Arquivo: `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/telemetry/TelemetryReporter.kt`

1. `TelemetryReporter::postJson`, no `finally` (~linha 208), e `TelemetryReporter::getFallback`, no `finally`
   (~linha 228): hoje é `try { conn?.disconnect() } catch (ignored: Throwable) {}`. Quebre em várias linhas:
   ```kotlin
   try {
       conn?.disconnect()
   } catch (ignored: Throwable) {
   }
   ```
   (ou outra forma que o ktlint aceite). O comportamento tem de ser o mesmo: engolir **qualquer** `Throwable` do
   `disconnect`, porque a telemetria nunca lança.
2. `TelemetryReporter::cap(s, max)` (~248) e `TelemetryReporter::capTail(s, maxBytes)` (~251): um parâmetro por linha,
   com vírgula final. É a regra `function-signature` do `ktlint_official` para 2+ parâmetros:
   ```kotlin
   private fun cap(
       s: String,
       max: Int,
   ): String = if (s.length <= max) s else s.substring(0, max)
   ```
   O corpo não muda.

Arquivo: `lemuroid-app/config/ktlint/baseline.xml`

3. Remova o elemento inteiro `<file name="src/main/java/com/swordfish/lemuroid/app/shared/telemetry/TelemetryReporter.kt">`
   (18 `<error>`, linhas 191/211/231/234). Depois do passo 1-2 o arquivo não tem nenhum apontamento. Deixar o bloco
   esconderia uma violação futura que caísse nessas coordenadas. Não toque em outro bloco.
   **Não rode `ktlintGenerateBaseline`**: o manual proíbe, porque ele grava no submódulo `lemuroid-cores`.

## Teste que prova

- `./gradlew :lemuroid-app:ktlintCheck` (a task completa, não só `ktlintMainSourceSetCheck`) sai **0**, e
  `TelemetryReporter.kt` não aparece em `lemuroid-app/build/reports/ktlint/*/*.txt`.
- `git diff` do `.kt`: só quebra de linha, indentação e vírgula final.
- O `build` do helper compila e os testes de unidade (incluindo `MedidaEnvioTest`) passam sem falha nova.

## Plano de teste

- **Controle positivo:** na base sincronizada, `./gradlew :lemuroid-app:ktlintCheck` sai 1 com 18 apontamentos em
  `TelemetryReporter.kt` (linhas 208, 228, 248, 251). Guarde o `.txt` do relatório na evidência.
- **Com a mudança:** o mesmo comando sai 0. Guarde a saída.
- **T2 (UI):** dispensado. É gate de estilo do build, sem caminho de usuário.
- **T3:** testes de unidade do `build` (`lemuroid-app/build/test-results/testFreeBundleDebugUnitTest/*.xml`) sem `fail`
  novo. Sem aparelho: não há mudança de comportamento.

## Correcao (pipeline 20261114-1912, rodada 1)

- `TelemetryReporter::postJson` e `::getFallback` (finally): `try { conn?.disconnect() } catch (ignored: Throwable) {}`
  quebrado em várias linhas; continua engolindo qualquer `Throwable`.
- `TelemetryReporter::cap` e `::capTail`: um parâmetro por linha com vírgula final; corpos intactos.
- `lemuroid-app/config/ktlint/baseline.xml`: removido o `<file>` de `TelemetryReporter.kt` (18 `<error>`); nenhum outro bloco tocado.

Provas (lane wt-1):
- Controle positivo, antes da edição: `./gradlew :lemuroid-app:ktlintCheck -q` -> EXIT=1, 18 apontamentos, todos em
  `TelemetryReporter.kt` (try-catch-finally-spacing 8, function-signature 6, statement-wrapping 4).
  Log: `reports/TASK-0003-ktlint-antes.log`.
- Com a mudança: mesmo comando -> **EXIT=0**, relatórios `lemuroid-app/build/reports/ktlint/*/*.txt` vazios.
- `build --bug TASK-0003` -> BUILD SUCCESSFUL; `testFreeBundleDebugUnitTest`: 96 testes, 0 falhas, 0 erros, 1 pulado.
- `git diff` do `.kt`: +16/-4, só quebra de linha, indentação e `,` final.

## Teste (pipeline 20261114-1912, rodada 1) — aprovado

- **Diff** (`git diff origin/version10...HEAD`): só os 2 arquivos reservados; `.kt` +16/-4 apenas quebra de linha,
  indentação e `,` final (os `catch (ignored: Throwable)` seguem engolindo tudo); baseline: só o `<file>` de
  `TelemetryReporter.kt` removido (18 `<error>`), nada adicionado.
- **Controle positivo** (base `origin/version10` @ ee90baa, os 2 arquivos restaurados da base na lane):
  `./gradlew :lemuroid-app:ktlintCheck -q` -> EXIT=1, 18 apontamentos em `TelemetryReporter.kt`.
  Evidência: `evidencias/ktlint-base-relatorio.txt`.
- **Com a mudança**: mesmo comando -> EXIT=0, relatórios `lemuroid-app/build/reports/ktlint/*/*.txt` vazios.
  Evidência: `evidencias/ktlint-fix.txt` (saída vazia).
- **T2 (UI)**: dispensado pelo Plano de teste (gate de estilo, sem caminho de usuário); sem instalação no aparelho.
- **T3**: `build --bug TASK-0003` após `sync` (base ee90baa) -> BUILD SUCCESSFUL; `testFreeBundleDebugUnitTest`
  96 testes / 0 falhas / 0 erros / 1 pulado (igual à rodada do dev).
- **T4**: sem aparelho, sem logcat (sem mudança de comportamento).
