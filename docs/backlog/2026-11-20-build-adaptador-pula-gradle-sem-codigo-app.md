# Build do adaptador do pipeline pula o Gradle quando o diff não tem código do app

Origem: execução `20261120-2117` do pipeline (bugs de publicação, só `.ps1`). O dev da TASK-0011 levou 14 min: ~6 min
de trabalho e **7,6 min parado esperando o `build` do adaptador** (`assembleFreeBundleDebug` + testes de unidade), num
diff sem uma linha de Kotlin. A TASK-0013 teve uma espera de 3,4 min do mesmo tipo, e a integração roda o mesmo build de
novo antes do push.

## Análise

Símbolos abertos (motor em `C:\projects\pipeline-global\skill\scripts\pipeline.py`):

- `rodar_cmd_projeto(path, kind, extra, log)`: troca `{wt}`/`{root}` no `build.cmd`, acrescenta `extra` (as
  `build.flags`, ex. `--emulador` → `-PdevAbi=x86_64`) **no fim da lista** e roda com `cwd=ROOT` (a árvore principal).
  Devolve o código de saída; não há placeholder `{base}`.
- Quem chama o `build`: `cmd_build` (dev/teste, `H build --bug`, na lane do item), `verificar_integracao` (lane já
  rebaseada sobre a base, antes do push; rc≠0 → `IntegracaoVermelha`, volta ao dev) e o `final-sync-main` (l. ~8856, lane
  com os commits `chore(terceiros)` sobre a base). Nos três, a lane é worktree do repo e enxerga `origin/version10`.
- O dev da TASK-0011 disparou o build **antes** do commit: o diff a medir tem de incluir a árvore de trabalho e os
  arquivos não rastreados, não só `base..HEAD`.
- `projeto.md` (seção do teste E2E): o APK instalado é o que o `build` deixou na lane; sem ele, `aparelho-instalar`
  recusa dizendo isso. Lane é worktree nova (sem `build/` velho), então pular não deixa APK obsoleto para trás.

Prova de que o Gradle não lê esses arquivos: `git grep -n -i "\.ps1\|\.py\b\|python" -- '*.gradle*' '*.kts' buildSrc`
só acha comentários (nenhum `.ps1`/`.md` entra no build).

Hipóteses descartadas:
- Placeholder `{base}` no motor: exigiria mudar o motor (todos os projetos). O comando lê o `base` do próprio
  `{root}\.pipeline\projeto.json`, sem duplicar o valor.
- `git diff base..HEAD`: perde mudança não commitada (o caso real da TASK-0011) e, com a base andando, conta commits de
  outros. Usar `merge-base HEAD <base>` contra a árvore de trabalho + `ls-files --others`.
- Pular também com diff vazio: não. Diff vazio ou erro de git → compila (o lado seguro).

## O que fazer

1. `.pipeline/projeto.json` → `build.cmd`: antes do submódulo e do Gradle, listar os arquivos alterados desde o
   `merge-base` (árvore de trabalho + não rastreados). Se a lista não for vazia e **todo** arquivo for `*.ps1`, `*.md`,
   `docs/**` ou `.pipeline/**`, imprimir o motivo e `exit 0`. Qualquer outro arquivo, lista vazia ou erro de git → build
   como hoje. O `gradlew` continua sendo o último comando (as `flags` são acrescentadas depois dele).
   Prova: worktree descartável com (a) só `.ps1` alterado → pula; (b) `.kt` alterado → chega no Gradle;
   (c) `.ps1` não commitado e não rastreado → pula; (d) com `-PdevAbi=x86_64` no fim → mesmo resultado.
2. Aplicar **depois** que a execução `20261120-2117` terminar (o motor lê o adaptador da árvore principal a cada
   comando), commitar e dar push.
