---
fecha_por: prova
tasks: [TASK-0003, TASK-0004]
---
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

## Planejamento (pipeline 20261114-1912, 2026-11-14)

**Veredito:** corrigir — 1 task `implementacao` (`corrigir-estilo-telemetryreporter`): reformatar as 4 construções do
`TelemetryReporter.kt` pelas regras do ktlint e tirar do baseline o bloco do arquivo, que fica sem nenhum apontamento.
**Complexidade:** baixa — só formatação, sem mudança de comportamento, dois arquivos.
**Severidade:** baixo — nenhum usuário vê; derruba o gate `ktlintCheck` de toda mudança em `lemuroid-app`
(o dev precisa separar os 18 apontamentos alheios dos seus).
**Arquivos a alterar (reserva da onda):**
- `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/telemetry/TelemetryReporter.kt` — as 4 construções
- `lemuroid-app/config/ktlint/baseline.xml` — remover o `<file name=".../telemetry/TelemetryReporter.kt">` (18 entradas)

### Causa raiz (simbolos abertos)

**Não é código novo: é passivo antigo deslocado.** É o pitfall do `CLAUDE.md` ("Estilo: ktlint com baseline": o
baseline casa por `linha:coluna:regra`).

- `lemuroid-app/config/ktlint/baseline.xml`, bloco `TelemetryReporter.kt`: 18 entradas nas linhas **191, 211, 231,
  234** (6 + 6 + 3 + 3). As colunas e regras são as mesmas do relatório da lane.
- `3145b78` (`feat(telemetria): medida...`) acrescentou **17 linhas** ao `TelemetryReporter.kt`, todas acima da 191, e
  não removeu nenhuma. Não mexeu no baseline. Resultado: 191→208, 211→228, 231→248, 234→251, que são exatamente as
  linhas do relatório. Os 18 apontamentos "voltaram".
- `TelemetryReporter::postJson` (finally, linha 208) e `TelemetryReporter::getFallback` (finally, linha 228):
  `try { conn?.disconnect() } catch (ignored: Throwable) {}` numa linha só. Em erro, engole qualquer `Throwable` do
  `disconnect` (intencional: telemetria nunca lança). Regras: `try-catch-finally-spacing` (4 por linha) e
  `statement-wrapping` (2 por linha).
- `TelemetryReporter::cap(s, max)` (248) e `TelemetryReporter::capTail(s, maxBytes)` (251): dois parâmetros na mesma
  linha da assinatura. No sabor `ktlint_official`, `function-signature` exige um parâmetro por linha a partir de 2
  parâmetros. Isso dá 3 apontamentos por função: os dois parâmetros e o `)`.
- `.editorconfig`: `max_line_length=120`, sem `ktlint_code_style`, então vale `ktlint_official`.

### Provas (comando literal -> resultado)

- `git show 3145b78^:<TelemetryReporter.kt> | grep -n "try {.*catch\|private fun cap"` -> linhas 191, 211, 231
  (`cap`) e 234 (`capTail`), as mesmas do baseline.
- `grep -n "try {.*catch\|^    private fun" <TelemetryReporter.kt>` no HEAD -> 208, 228, 248 (`cap`), 251
  (`capTail`): +17.
- `git show --stat 3145b78` -> `TelemetryReporter.kt | 17 ++`. `git diff 3145b78^ 3145b78 -- <arquivo>`: 0 linhas
  removidas.
- `git log 3145b78..HEAD -- lemuroid-app/config/ktlint/baseline.xml` -> vazio: o baseline não foi tocado depois.
- O relatório da lane (evidência acima): EXIT=1, 18 apontamentos, só neste arquivo. Não rodei o ktlint na worktree
  de planning: ela é compartilhada e o submódulo está vazio. A aritmética de linhas acima prova a causa sem rodar.

### Hipoteses descartadas (e por que)

- **"Código novo do 3145b78, corrigir sem tocar no baseline"** (a "Correção provável" da 1a passada): as 4 construções
  já existiam antes do commit e estavam congeladas. Corrigir só o código resolve o vermelho. Mas o bloco de 18 entradas
  ficaria apontando para as linhas 191/211/231/234, que passam a ter outro código, e poderia esconder uma violação
  futura que caísse na mesma `linha:coluna:regra`. Por isso o bloco sai junto.
- **Atualizar os números de linha no baseline (191→208 etc.)**: deixa o check verde, mas mantém o passivo e quebra de
  novo na próxima inserção acima. O `CLAUDE.md` manda corrigir, não congelar de novo.
- **Rodar `ktlintGenerateBaseline`**: proibido pelo manual, seção Dev (grava no submódulo `lemuroid-cores`, fora de
  qualquer commit). Tirar o bloco à mão tem o mesmo efeito para este arquivo, porque depois da correção ele não tem
  nenhum apontamento.

### Tarefas

- [ ] `corrigir-estilo-telemetryreporter` (implementacao) — `TelemetryReporter::postJson`/`getFallback` (finally),
  `::cap`, `::capTail` + bloco do arquivo no `baseline.xml` — prova: `./gradlew :lemuroid-app:ktlintCheck` sai 0.

### Plano de teste

- **Offline (sem slot):** na lane da task, `./gradlew :lemuroid-app:ktlintCheck` (a task completa, que roda
  `ktlintMainSourceSetCheck`, `ktlintTestSourceSetCheck` e `ktlintKotlinScriptCheck`) sai **0**, e
  `TelemetryReporter.kt` não aparece em nenhum relatório de `lemuroid-app/build/reports/ktlint/`. O `git diff` do
  `.kt` é só quebra de linha e indentação, sem token novo além de `,` final e `{`/`}`. O `build` do helper compila e os
  testes de unidade passam sem falha nova.
- **T2 reproducao como o usuario:** dispensada. O bug é de gate de estilo do build e não tem caminho de usuário.
- **Controle positivo:** na base (`origin/version10`), o mesmo `./gradlew :lemuroid-app:ktlintCheck` sai 1 com os 18
  apontamentos em `TelemetryReporter.kt` (208, 228, 248, 251). É a evidência já registrada na lane wt-1. Repetir na
  base sincronizada antes de provar o fix.
- **T3 regressao direcionada:** a telemetria não muda de comportamento (o `catch` continua engolindo `Throwable` no
  `disconnect`, e `cap`/`capTail` mantêm o corpo). Prova: os testes de unidade do `build`
  (`lemuroid-app/build/test-results/testFreeBundleDebugUnitTest/*.xml`), em especial `MedidaEnvioTest`, sem `fail`
  novo, e a revisão do diff. Sem aparelho.

### Sessoes seguintes

- dev: effort low, ultrathink não — troca mecânica de formatação em 4 pontos + remoção de um bloco XML.
- teste: effort low, ultrathink não — rodar `ktlintCheck` na base e no fix e comparar os relatórios.
