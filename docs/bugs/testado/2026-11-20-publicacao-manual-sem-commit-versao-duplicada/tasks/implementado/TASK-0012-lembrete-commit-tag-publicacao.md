---
tipo: implementacao
demandas: [2026-11-20-publicacao-manual-sem-commit-versao-duplicada]
depende_de: [TASK-0011]
integrado_em: 8aec970a7d525de314498601be1941b727be38b2
---
# Lembrete no fim do build-and-upload.ps1: comandos exatos de commit, tag e push do bump publicado

Demanda: `docs/bugs/aberto/2026-11-20-publicacao-manual-sem-commit-versao-duplicada/` (leia a seção "Planejamento").
Depende de `trava-versao-anunciada` (cria o `publish-guard.ps1` e o teste onde esta task entra).

## Objetivo

O bump do `lemuroid-app/build.gradle.kts` que o script grava ficou ~35 h sem commit na árvore principal e foi a origem
da 268 duplicada. O script não deve commitar (a árvore do dono é compartilhada com outras sessões: decisão do doc,
"O que fazer" item 2), mas deve terminar dizendo, em destaque, o que o dono tem de rodar.

## O que mudar

1. **`publish-guard.ps1::Get-PublishCommitReminder`** — função pura que devolve as linhas do lembrete a partir de
   `-VersionName <string> -Branch <string> -GradleDirty <bool> -TagExists <bool>`:
   - `GradleDirty`: `git add lemuroid-app/build.gradle.kts` e
     `git commit -m "chore(versao): publicado/<v>"` (mesma mensagem do `bf8b799`), só o `.kts` (nunca `git add -A`;
     dizer isso na linha de aviso);
   - `-not TagExists`: `git tag publicado/<v>`;
   - sempre que houver algo acima: `git push origin <branch> publicado/<v>`;
   - nada pendente: uma linha "versão <v> já commitada e com tag".
   - `Branch` vazio/`HEAD` (detached ou sem git): usar `<branch>` literal e avisar que o checkout não está num branch.
2. **`build-and-upload.ps1`**, no "SUMMARY" (fim, `:704` em diante), **só quando o passo 4 rodou** (sem `-SkipR2`):
   consultar `git rev-parse --abbrev-ref HEAD`, `git status --porcelain -- lemuroid-app/build.gradle.kts` e
   `git tag -l publicado/<v>` (cada um tolerando ausência de git: `$null` em vez de `throw`), chamar a função e
   imprimir num bloco destacado (cor de alerta, título "FALTA COMMITAR A VERSAO PUBLICADA" quando houver pendência),
   **antes** do "Todos os passos concluidos com sucesso!". Com `-SkipR2` nada foi anunciado: não imprimir tag.
   Comentário citando o id da demanda.
3. **`tests/test-publish-guard.ps1`**: casos do lembrete — dirty+sem tag (os 4 comandos, nesta ordem), limpo+sem
   tag (tag e push), dirty+com tag, nada pendente, branch `HEAD`/vazio (`<branch>` e aviso). Conferir a string exata
   `chore(versao): publicado/1.17.39` para `-VersionName 1.17.39`.

## Fora do escopo

- Commit/tag automáticos. A trava de versão (task anterior). Caminho versionado no R2 (outro bug).

## Plano de teste da task

- **Offline:** `powershell -NoProfile -File tests/test-publish-guard.ps1 -Offline` sai 0 com os casos novos;
  sem `-Offline` também (casos de rede da task anterior).
- **T2:** sem aparelho e sem publicação real (o script completo só roda de ponta a ponta publicando). Prova pelo teste
  acima e por leitura: `git diff <base> -- build-and-upload.ps1` mostra o bloco chamado só no ramo sem `-SkipR2`,
  depois do passo 4 e antes da mensagem final. A prova no terminal do dono é a próxima publicação real
  (`fecha_por: dono`).
- **Controle positivo:** `git show <base>:build-and-upload.ps1 | Select-String "git "` não acha nenhum comando git.
- **T3:** a receita T2 da task `trava-versao-anunciada` (cópia de rascunho, `-SkipHF -SkipR2`) continua com o mesmo
  resultado: o lembrete não aparece com `-SkipR2` e a ausência de git não lança.

## Correcao (pipeline 20261120-2117, rodada 1)

- `publish-guard.ps1::Get-PublishCommitReminder` (novo, puro): devolve `{Pending; Lines}`. Dirty -> aviso "nunca git add -A",
  `git add lemuroid-app/build.gradle.kts`, `git commit -m "chore(versao): publicado/<v>"`; sem tag -> `git tag publicado/<v>`;
  havendo algo -> `git push origin <branch> publicado/<v>`; nada pendente -> "versao <v> ja commitada e com tag".
  Branch vazio/`HEAD`/`$null` -> `<branch>` literal + AVISO.
- `build-and-upload.ps1` (RESUMO, antes de "Todos os passos concluidos"): so com `-not $SkipR2`; `rev-parse --abbrev-ref HEAD`,
  `status --porcelain -- lemuroid-app/build.gradle.kts`, `tag -l publicado/<v>` com EAP=Continue e try/catch (sem git:
  branch `$null`, dirty assumido `$true`, tag ausente -> mostra todos os comandos). Bloco vermelho
  "FALTA COMMITAR A VERSAO PUBLICADA" quando pendente.
- `tests/test-publish-guard.ps1`: casos dirty+sem tag (4 comandos, ordem, string exata `chore(versao): publicado/1.17.39`),
  limpo+sem tag, dirty+com tag, nada pendente, aviso `git add -A`, branch `HEAD`/''/$null, e posicao do lembrete no script.
  Resultado: `-Offline` 48/48, com rede 51/51 (Windows PowerShell 5.1); `ParseFile` do `build-and-upload.ps1` sem erros.

## Estado

Feito: as 3 partes; testes offline/online verdes. Falta: nada alem do teste (T2 = leitura do diff; prova real na proxima
publicacao do dono).

## Teste

Rodada 1 (2026-11-20), aprovada. Evidencia em `C:\projects\lemuroid\.bugfix-pipeline-Lemuroid\reports\TASK-0012-evidencia\`.

- **Offline:** `tests/test-publish-guard.ps1 -Offline` 48/48; sem `-Offline` 51/51. Diff so nos 3 arquivos reservados (+ esta task).
- **Controle positivo:** `git show origin/version10:build-and-upload.ps1 | grep -c "git "` = 0 (a base nao diz nada sobre commit/tag).
- **T2:** `build-and-upload.ps1` REAL rodado pelo harness da TASK-0013 (cenario `sucesso`, bucket R2 falso, `-SkipBuild -SkipHF`,
  raiz temporaria sem git) com uma copia do harness que tambem copia o `publish-guard.ps1` (ver achado abaixo): PASS, exit 0, e a
  saida (`script.out.sucesso.log`) termina com o bloco "FALTA COMMITAR A VERSAO PUBLICADA (1.17.38-DEBUG)", aviso de checkout
  sem branch, `git add lemuroid-app/build.gradle.kts`, `git commit -m "chore(versao): publicado/1.17.38-DEBUG"`, `git tag`,
  `git push origin <branch> ...`, ANTES de "Todos os passos concluidos com sucesso!". Ausencia de git nao lancou.
- **T3:** com `-SkipR2` o bloco nao roda (o `if (-not $SkipR2)` envolve tudo; caso "lembrete no resumo" do teste confere a
  posicao); cenario de sucesso do harness segue com anuncio coerente.
- **T4:** `script.err.log` do cenario sem erro novo.
- **Achado lateral:** `2026-11-20-harness-publicacao-sem-publish-guard` (o harness nao copia o `publish-guard.ps1`; quebra na base).
- Prova no terminal do dono: proxima publicacao real (`fecha_por: dono`).
