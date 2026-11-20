---
fecha_por: dono
---

# [BUG] Duas versões 1.17.37 (versionCode 268) diferentes foram publicadas: quem instalou a primeira nunca receberia a segunda

**Data:** 2026-11-20
**Severidade:** alta: o cliente que instalou a 1.17.37 de 19/11 ficaria sem as correções da 1.17.37 de 20/11, sem aviso, porque o versionCode é o mesmo
**Complexidade:** baixa
**Branch:** version10
**Origem:** investigacao (publicação do pipeline 20261119-2228)

---

## Sintoma

Em 2026-11-19 e 2026-11-20 saíram duas publicações com o mesmo `versionCode 268` / `1.17.37` e binários diferentes.
O app compara só o `versionCode`. Quem instalou a primeira nunca seria avisado da segunda, que tinha as correções da
execução 20261119-2228 (ANR do canal de notificação, marcador de foreground no lowmemory).

## Evidência

- `dist/version.json` da árvore principal, gravado em 2026-11-19 02:23 (-03), tem `versionCode 268`,
  `versionName 1.17.37`, `sha256 92c9818b…`. O `dist/retro-game-system-arm64.apk` (200337932 B) é da mesma hora.
  O `version.json` público tinha `Last-Modified: Thu, 19 Nov 2026 05:25:07 GMT`, com o mesmo conteúdo.
- Nesse momento o git estava em 267: `19cacda` (2026-11-19 02:12 -03) sobe `266 → 267` / `1.17.35 → 1.17.36`, e não há
  tag `publicado/1.17.36` nem `publicado/1.17.37`.
- O `268` só entrou no git em `ec4e2f6` (2026-11-20 13:00 -03), o commit "chore(terceiros): codigo local nao commitado de
  outra sessao" da finalização. O bump ficou sem commit na árvore principal por ~35 h e foi recolhido como "código de
  terceiros".
- A publicação do pipeline (`reports/20261119-2228-publicacao.log`, 12:46 -03) partiu do origin em 267 e o script subiu
  de novo: `versionCode: 267 -> 268`, `versionName: 1.17.36 -> 1.17.37`. O resultado foi outro binário 268
  (`581eaf18…`, 195323821 B).

## Causa-raiz

`build-and-upload.ps1` (bloco "BUMP DE VERSAO", `:106-160`) grava o bump no `build.gradle.kts` e publica, mas **não**
commita nem cria a tag. Também não confere se a versão nova é maior que a que está no ar. Quando o bump não é
commitado, ele se perde para qualquer lane ou máquina que parta do origin, e a próxima publicação repete o número.

## Hipóteses descartadas

- **Rodar o script duas vezes na mesma árvore**: não explica. Na mesma árvore o segundo bump iria para 269. A
  duplicação exige duas árvores com o mesmo ponto de partida (árvore principal com o bump não commitado e lane do
  pipeline a partir do origin).

## O que fazer

1. `build-and-upload.ps1`, antes do bump: ler o `version.json` público do canal (`$versionJsonUrl`) e abortar se o
   `versionCode` que vai ser gerado for `<=` o anunciado, com mensagem dizendo qual versão está no ar e de qual commit
   o script partiu. Sem `version.json` (canal novo), segue.
2. `build-and-upload.ps1`, no fim com sucesso: imprimir em destaque o lembrete com os comandos exatos
   `git add lemuroid-app/build.gradle.kts`, `git commit -m "chore(versao): publicado/<v>"`, `git tag publicado/<v>` e
   `git push origin <branch> publicado/<v>`. Commit automático não: o dono publica da árvore principal compartilhada,
   e o script não deve commitar por cima do trabalho de outras sessões.
3. Desde 2026-11-20 o pipeline não publica mais (`publicacao: null` no `.pipeline/projeto.json`, decisão do dono), o que
   elimina a segunda árvore desta ocorrência. O item 1 continua necessário: vale também para duas máquinas ou
   checkouts do dono.

Prova do item 1: com o `version.json` público em 269, rodar o script num checkout com `versionCode = 268` (bump iria
para 269) e ver que ele aborta antes de compilar.

## Correção

Situação em produção resolvida pela 1.17.38 / 269 (`bf8b799`, tag `publicado/1.17.38`), maior que as duas 268.
