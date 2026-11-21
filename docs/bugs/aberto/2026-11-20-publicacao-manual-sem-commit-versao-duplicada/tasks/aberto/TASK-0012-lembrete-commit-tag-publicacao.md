---
tipo: implementacao
demandas: [2026-11-20-publicacao-manual-sem-commit-versao-duplicada]
depende_de: [TASK-0011]
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
